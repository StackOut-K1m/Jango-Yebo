package com.jangoyebo.core.loan;

import com.jangoyebo.NeedsDocker;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 대출이 만든 거래를 예측에서 빼기 (P1-13).
 *
 * <p><b>잘못 빼는 쪽이 더 위험하다.</b> 안 빼면 곡선이 상환을 두 번 세지만(과소 예측),
 * 진짜 지출을 빼면 곡선이 없는 여유를 만든다(과대 예측) — 사장님이 쓸 수 있다고 믿는 돈이
 * 실제로는 없다. 그래서 「빼지 않는」 경계를 먼저 못박는다.
 */
@NeedsDocker
@SpringBootTest
class LoanTransactionMatcherTests {

    private static final LocalDate 개설일 = LocalDate.of(2026, 9, 15);

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private LoanTransactionMatcher matcher;

    private Long businessId;
    private Long accountId;
    private Long loanId;

    @BeforeEach
    void setUp() {
        String email = "loan-match-" + System.nanoTime() + "@test.local";
        Long userId = jdbc.queryForObject("""
                INSERT INTO core.users (email, password_hash, name, status)
                VALUES (?, 'hash', '김사장', 'ACTIVE') RETURNING id
                """, Long.class, email);
        businessId = jdbc.queryForObject("""
                INSERT INTO core.business (owner_user_id, name, taxation_type, status)
                VALUES (?, '한밤포차', 'GENERAL', 'ACTIVE') RETURNING id
                """, Long.class, userId);
        accountId = jdbc.queryForObject("""
                INSERT INTO core.account (business_id, bank_code, account_number_encrypted,
                                          account_number_hash, account_number_masked, name, status)
                VALUES (?, '999', 'enc', ?, '999****0386', '주계좌', 'CONNECTED') RETURNING id
                """, Long.class, businessId, "h" + System.nanoTime());
        loanId = jdbc.queryForObject("""
                INSERT INTO core.loan (business_id, external_loan_no, lender_name, product_name,
                                       principal_amount, total_repayable, interest_rate,
                                       repayment_cycle, withdrawal_account_id,
                                       started_on, maturity_date, status)
                VALUES (?, ?, '우리은행', '소상공인 일반대출', 5000000, 158300000, 8.400,
                        'DAILY', ?, ?, ?, 'REPAYING') RETURNING id
                """, Long.class, businessId, "L" + System.nanoTime(), accountId,
                개설일, 개설일.plusDays(365));
    }

    @Test
    void 대출금_입금을_뺀다() {
        // 일회성이라 패턴엔 안 잡히지만 잔여에는 들어간다 — 한 번 받은 500만이
        // 「앞으로도 들어올 돈」으로 부풀려진다.
        Long tx = 거래(개설일, "DEPOSIT", "5000000", "(대출) : 대출금 입금");

        assertThat(matcher.exclude(loanId)).isEqualTo(1);
        assertThat(제외됐나(tx)).isTrue();
    }

    @Test
    void 상환_거래를_회차와_잇고_뺀다() {
        회차(1, 개설일.plusDays(1), "433698");
        Long tx = 거래(개설일.plusDays(1), "WITHDRAWAL", "433698", "(대출) : 대출금 상환");

        assertThat(matcher.exclude(loanId)).isEqualTo(1);
        assertThat(제외됐나(tx)).isTrue();
        assertThat(jdbc.queryForObject("""
                SELECT transaction_id FROM core.loan_repayment WHERE loan_id = ?
                """, Long.class, loanId))
                .as("회차와 거래가 이어져야 나중에 「그날 정말 빠졌나」를 볼 수 있다")
                .isEqualTo(tx);
    }

    @Test
    void 금액이_다르면_안_뺀다() {
        // 같은 날 같은 계좌에서 나간 진짜 지출이다. 빼면 곡선이 없는 여유를 만든다.
        회차(1, 개설일.plusDays(1), "433698");
        Long 진짜지출 = 거래(개설일.plusDays(1), "WITHDRAWAL", "433699", "식자재");

        assertThat(matcher.exclude(loanId)).isZero();
        assertThat(제외됐나(진짜지출)).isFalse();
    }

    @Test
    void 날짜가_다르면_안_뺀다() {
        회차(1, 개설일.plusDays(1), "433698");
        Long 다른날 = 거래(개설일.plusDays(2), "WITHDRAWAL", "433698", "식자재");

        assertThat(matcher.exclude(loanId)).isZero();
        assertThat(제외됐나(다른날)).isFalse();
    }

    @Test
    void 다른_계좌의_거래는_안_뺀다() {
        회차(1, 개설일.plusDays(1), "433698");
        Long 다른계좌 = jdbc.queryForObject("""
                INSERT INTO core.account (business_id, bank_code, account_number_encrypted,
                                          account_number_hash, account_number_masked, name, status)
                VALUES (?, '999', 'enc', ?, '999****9999', '보조계좌', 'CONNECTED') RETURNING id
                """, Long.class, businessId, "h2" + System.nanoTime());
        Long tx = 거래(다른계좌, 개설일.plusDays(1), "WITHDRAWAL", "433698", "식자재");

        assertThat(matcher.exclude(loanId)).isZero();
        assertThat(제외됐나(tx)).isFalse();
    }

    @Test
    void 적요가_다르면_상환으로_잇지_않는다() {
        // 실측(2026-09-16 08:30) — 금융망 상환 출금 적요는 「(대출) : 소상공인 일반대출 (1회차) 대출상환금 출금」
        // 으로 고정 형식이다. 같은 날 같은 계좌에서 우연히 회차와 같은 금액이 나간 진짜 지출은
        // 적요에 「대출」이 없으므로 잇지 않는다 — 이어 버리면 그 지출이 곡선에서 사라진다.
        회차(1, 개설일.plusDays(1), "433698");
        Long 우연히같은금액 = 거래(개설일.plusDays(1), "WITHDRAWAL", "433698", "식자재 대금");

        assertThat(matcher.exclude(loanId)).isZero();
        assertThat(제외됐나(우연히같은금액)).isFalse();
        assertThat(jdbc.queryForObject("""
                SELECT transaction_id FROM core.loan_repayment WHERE loan_id = ?
                """, Long.class, loanId))
                .as("회차가 엉뚱한 거래에 묶이면 「그날 정말 빠졌나」가 거짓이 된다")
                .isNull();
    }

    @Test
    void 실측_적요_형식으로_상환을_잇는다() {
        // 픽스처의 「(대출) : 대출금 상환」은 추정 문구였다. 실제 금융망 문구로도 이어지는지 고정한다.
        회차(1, 개설일.plusDays(1), "433698");
        Long tx = 거래(개설일.plusDays(1), "WITHDRAWAL", "433698",
                "(대출) : 소상공인 일반대출 (1회차) 대출상환금 출금");

        assertThat(matcher.exclude(loanId)).isEqualTo(1);
        assertThat(제외됐나(tx)).isTrue();
    }

    @Test
    void 적요에_대출이_없으면_입금은_안_뺀다() {
        // 개설일에 우연히 같은 금액이 들어올 수 있다. 한 겹 더 건다.
        Long tx = 거래(개설일, "DEPOSIT", "5000000", "카드 정산");

        assertThat(matcher.exclude(loanId)).isZero();
        assertThat(제외됐나(tx)).isFalse();
    }

    @Test
    void 여러_번_돌려도_같은_결과다() {
        // 동기화는 몇 번이든 다시 돈다. 이미 뺀 것을 또 세면 보고 숫자가 거짓이 된다.
        거래(개설일, "DEPOSIT", "5000000", "(대출) : 대출금 입금");
        회차(1, 개설일.plusDays(1), "433698");
        거래(개설일.plusDays(1), "WITHDRAWAL", "433698", "(대출) : 대출금 상환");

        assertThat(matcher.exclude(loanId)).isEqualTo(2);
        assertThat(matcher.exclude(loanId)).isZero();
    }

    // ── 픽스처 ────────────────────────────────────────────────────────────────

    private boolean 제외됐나(Long transactionId) {
        return Boolean.TRUE.equals(jdbc.queryForObject(
                "SELECT is_excluded FROM core.transaction WHERE id = ?", Boolean.class, transactionId));
    }

    private void 회차(int no, LocalDate dueDate, String amount) {
        jdbc.update("""
                INSERT INTO core.loan_repayment (loan_id, installment_no, due_date, amount, status)
                VALUES (?, ?, ?, CAST(? AS NUMERIC), 'SUCCESS')
                """, loanId, no, dueDate, amount);
    }

    private Long 거래(LocalDate date, String flow, String amount, String description) {
        return 거래(accountId, date, flow, amount, description);
    }

    private Long 거래(Long account, LocalDate date, String flow, String amount, String description) {
        return jdbc.queryForObject("""
                INSERT INTO core.transaction (business_id, account_id, source_type,
                                              deduplication_key, occurred_at, transaction_date,
                                              flow_type, amount, description,
                                              classification_status, needs_confirmation, is_excluded)
                VALUES (?, ?, 'FIN_API', ?, ?, ?, ?, CAST(? AS NUMERIC), ?,
                        'UNCLASSIFIED', FALSE, FALSE)
                RETURNING id
                """, Long.class, businessId, account, "d" + System.nanoTime(),
                date.atStartOfDay().atOffset(java.time.ZoneOffset.ofHours(9)), date,
                flow, amount, description);
    }
}
