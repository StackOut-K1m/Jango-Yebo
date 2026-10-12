package com.jangoyebo.core.loan;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 대출이 만든 거래를 예측에서 뺀다 (P1-13).
 *
 * <h2>왜 빼야 하나 — 같은 돈이 두 번 들어간다</h2>
 *
 * <p>대출 상환은 <b>이미 {@code source_type = LOAN} 예정 거래</b>로 곡선에 들어가 있다.
 * 그런데 실제로 빠진 거래가 원장에 그대로 남아 있으면 두 곳에서 또 센다.
 *
 * <ol>
 *   <li><b>반복 패턴 탐지</b> — 매일 같은 금액·같은 적요라 주기가 또렷하다. 잡히면
 *       {@code PATTERN} 예정 거래가 생겨 <b>곡선이 같은 상환을 두 번 뺀다.</b>
 *       게다가 {@code PATTERN} 은 자동 반영 대상이라, 켜 둔 계좌면 금융망이 08:30 에 빼고
 *       우리가 09:00 에 또 뺀다 — <b>실제 돈이 하루에 두 번 나간다.</b></li>
 *   <li><b>잔여 집계</b> — 패턴이 설명하지 못한 나머지가 잔여다. 대출 상환이 거기 섞이면
 *       모델이 「이 가게는 매일 43만원씩 나간다」를 미래로 이어 붙인다.</li>
 * </ol>
 *
 * <p>대출금 <b>입금</b>도 같은 이유로 뺀다. 일회성이라 패턴에는 안 잡히지만 잔여에는 들어가,
 * 한 번 받은 500만원이 <b>앞으로도 들어올 돈</b>으로 부풀려진다.
 *
 * <h2>한 플래그가 둘을 동시에 막는다</h2>
 *
 * <p>{@code TRANSACTION.is_excluded} 를 세우면 된다. 패턴 탐지와 잔여 집계가
 * <b>같은 조회</b>({@code TransactionHistory#findForPatternDetection})를 쓰고, 그 SQL 이
 * 이미 {@code is_excluded = FALSE} 를 걸기 때문이다. ERD 도 이 컬럼을
 * 「예측·집계 제외 전용」으로 정의한다 — 세무상 경비 제외는 {@code tax_review_status} 가 맡는다.
 *
 * <h2>우리가 이은 것만 뺀다</h2>
 *
 * <p>상환은 <b>회차와 거래를 먼저 잇고</b>({@code loan_repayment.transaction_id}) 그 링크를
 * 따라 제외한다. 조건만으로 한꺼번에 지우면 같은 날 같은 금액의 <b>진짜 지출</b>이 딸려
 * 들어갈 수 있고, 그건 곡선을 과대 예측으로 만든다 — 빼는 쪽 실수가 더 위험하다.
 */
@Service
public class LoanTransactionMatcher {

    private static final Logger log = LoggerFactory.getLogger(LoanTransactionMatcher.class);

    /**
     * 대출금 입금. 개설일·원금·계좌가 다 맞아야 한다.
     *
     * <p>적요도 본다 — 금융망이 「(대출) : 대출금 입금」으로 준다(2026-09-15 실측).
     * 세 조건만으로도 대개 유일하지만, 같은 날 같은 금액이 우연히 들어올 수 있어 한 겹 더 건다.
     */
    private static final String EXCLUDE_DISBURSEMENT = """
            UPDATE core.transaction t
               SET is_excluded = TRUE, updated_at = now()
              FROM core.loan l
             WHERE l.id = ?
               AND t.business_id = l.business_id
               AND t.account_id = l.withdrawal_account_id
               AND t.flow_type = 'DEPOSIT'
               AND t.transaction_date = l.started_on
               AND t.amount = l.principal_amount
               AND t.description LIKE '%대출%'
               AND t.is_excluded = FALSE
            """;

    /**
     * 회차 ↔ 거래. 아직 안 이어진 회차만 본다.
     *
     * <p>적요도 본다 — 금융망이 「(대출) : 소상공인 일반대출 (1회차) 대출상환금 출금」처럼
     * <b>「(대출) : {상품명} ({n}회차) 대출상환금 출금」</b> 고정 형식으로 준다(2026-09-16 08:30 실측).
     * 계좌·날짜·금액·방향 넷만으로도 대개 유일하지만, 매일 나가는 43만원과 같은 날 같은 금액의
     * 식자재 대금이 겹치면 <b>진짜 지출이 회차에 묶여 곡선에서 사라진다.</b> 입금 쪽과 같은
     * 기준({@code '%대출%'})으로 건다 — 상품명이 바뀌어도 접두 「(대출)」은 남는다.
     */
    private static final String LINK_REPAYMENTS = """
            UPDATE core.loan_repayment r
               SET transaction_id = t.id, updated_at = now()
              FROM core.transaction t, core.loan l
             WHERE r.loan_id = ?
               AND l.id = r.loan_id
               AND r.transaction_id IS NULL
               AND t.business_id = l.business_id
               AND t.account_id = l.withdrawal_account_id
               AND t.flow_type = 'WITHDRAWAL'
               AND t.transaction_date = r.due_date
               AND t.amount = r.amount
               AND t.description LIKE '%대출%'
            """;

    /** 이어 붙인 거래만 뺀다. */
    private static final String EXCLUDE_LINKED = """
            UPDATE core.transaction t
               SET is_excluded = TRUE, updated_at = now()
              FROM core.loan_repayment r
             WHERE r.loan_id = ?
               AND r.transaction_id = t.id
               AND t.is_excluded = FALSE
            """;

    private final JdbcTemplate jdbc;

    public LoanTransactionMatcher(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 이 대출이 만든 거래를 원장에서 찾아 예측 대상에서 뺀다.
     *
     * <p>여러 번 돌려도 안전하다 — 이미 뺀 것과 이미 이어진 회차는 조건에서 걸러진다.
     *
     * @return 이번에 새로 뺀 거래 수
     */
    @Transactional
    public int exclude(Long loanId) {
        int disbursement = jdbc.update(EXCLUDE_DISBURSEMENT, loanId);
        int linked = jdbc.update(LINK_REPAYMENTS, loanId);
        int repayments = jdbc.update(EXCLUDE_LINKED, loanId);

        if (disbursement + repayments > 0) {
            log.info("대출 {} — 예측에서 제외: 대출금 입금 {}건, 상환 {}건 (회차 연결 {}건)",
                    loanId, disbursement, repayments, linked);
        }
        return disbursement + repayments;
    }
}
