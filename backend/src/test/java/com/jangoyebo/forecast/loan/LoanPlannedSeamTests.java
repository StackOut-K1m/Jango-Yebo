package com.jangoyebo.forecast.loan;

import com.jangoyebo.NeedsDocker;
import com.jangoyebo.core.LoansChanged;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@code core} 의 {@link LoansChanged} → {@code forecast} 의 예정 거래 생성 (P1-13).
 *
 * <p><b>이 이음매가 끊기면 조용히 아무 일도 안 일어난다.</b> 동기화는 성공하고 대출도
 * 저장되는데 곡선만 안 바뀐다 — 오류가 없어 로그를 봐도 모른다. 그래서 따로 건다.
 *
 * <p>{@code AFTER_COMMIT} 리스너라 <b>커밋되는 트랜잭션 안에서 발행해야</b> 한다.
 * 트랜잭션 밖에서 부르면 이벤트가 조용히 버려진다 — 그 자체가 이 테스트가 잡는 함정이다.
 */
@NeedsDocker
@SpringBootTest
@org.springframework.context.annotation.Import(LoanPlannedSeamTests.Probe.class)
class LoanPlannedSeamTests {

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private ApplicationEventPublisher events;

    @Autowired
    private TransactionTemplate tx;

    private Long businessId;
    private Long accountId;

    /** {@link #곡선_재계산을_건다()} 가 보는 값. 아래 {@code @TestConfiguration} 이 채운다. */
    private static volatile boolean recalculationRequested;

    @org.springframework.boot.test.context.TestConfiguration
    static class Probe {
        @org.springframework.context.event.EventListener
        public void on(com.jangoyebo.forecast.run.ForecastInputsChanged event) {
            if (event.reason() == com.jangoyebo.forecast.run.ForecastInputsChanged.Reason.LOAN_CHANGED) {
                recalculationRequested = true;
            }
        }
    }

    @BeforeEach
    void setUp() {
        recalculationRequested = false;
        String email = "loan-seam-" + System.nanoTime() + "@test.local";
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
    }

    @Test
    void 대출이_바뀌면_예정_거래가_생긴다() {
        // 실제 시연 대출과 같은 값 — 500만 · 365일 · 8.4%, 금융망이 준 총액 1억 5,830만.
        loan(accountId, "158300000");

        tx.executeWithoutResult(status -> events.publishEvent(new LoansChanged(businessId)));

        // 개설일이 오늘이라 첫 회차는 내일이다 — 오늘+1 … 오늘+90 으로 90건.
        assertThat(loanRows()).isEqualTo(90);
        assertThat(jdbc.queryForObject("""
                SELECT amount_expected FROM forecast.planned_transaction
                 WHERE business_id = ? AND source_type = 'LOAN'
                 ORDER BY expected_date LIMIT 1
                """, java.math.BigDecimal.class, businessId))
                .as("158,300,000 / 365 = 433,698.63 → 내림")
                .isEqualByComparingTo("433698");
    }

    @Test
    void 트랜잭션_밖에서_발행하면_아무_일도_안_일어난다() {
        // AFTER_COMMIT 리스너의 성질이다. 이걸 모르면 「왜 안 되지」로 오래 헤맨다.
        loan(accountId, "158300000");

        events.publishEvent(new LoansChanged(businessId));

        assertThat(loanRows()).isZero();
    }

    @Test
    void 연동이_끊긴_계좌의_대출은_곡선에_안_들어간다() {
        // 잔액이 시작 잔액에 없는데 지출만 넣으면 없는 공백이 생긴다.
        jdbc.update("UPDATE core.account SET status = 'DISCONNECTED' WHERE id = ?", accountId);
        loan(accountId, "158300000");

        tx.executeWithoutResult(status -> events.publishEvent(new LoansChanged(businessId)));

        assertThat(loanRows()).isZero();
    }

    @Test
    void 완납된_대출의_남은_회차를_치운다() {
        // 대출 하나씩 지우면 목록에서 빠진 대출의 미래 회차가 영영 남는다 —
        // 곡선이 갚을 필요 없는 돈을 계속 뺀다.
        loan(accountId, "158300000");
        tx.executeWithoutResult(st -> events.publishEvent(new LoansChanged(businessId)));
        assertThat(loanRows()).isEqualTo(90);

        jdbc.update("UPDATE core.loan SET status = 'CLOSED' WHERE business_id = ?", businessId);
        tx.executeWithoutResult(st -> events.publishEvent(new LoansChanged(businessId)));

        assertThat(loanRows()).isZero();
    }

    @Test
    void 곡선_재계산을_건다() {
        // 예정 거래만 새로 만들고 멈추면 곡선이 옛 것으로 남는다. 대출만 바뀌는 경로가 있다.
        loan(accountId, "158300000");

        tx.executeWithoutResult(st -> events.publishEvent(new LoansChanged(businessId)));

        assertThat(recalculationRequested)
                .as("ForecastInputsChanged(LOAN_CHANGED) 가 나갔는가").isTrue();
    }

    private int loanRows() {
        return jdbc.queryForObject("""
                SELECT count(*) FROM forecast.planned_transaction
                 WHERE business_id = ? AND source_type = 'LOAN'
                """, Integer.class, businessId);
    }

    private void loan(Long withdrawalAccountId, String totalRepayable) {
        LocalDate startedOn = LocalDate.now();
        jdbc.update("""
                INSERT INTO core.loan (business_id, external_loan_no, lender_name, product_name,
                                       principal_amount, total_repayable, interest_rate,
                                       repayment_cycle, withdrawal_account_id,
                                       started_on, maturity_date, status)
                VALUES (?, ?, '우리은행', '소상공인 일반대출',
                        5000000, CAST(? AS NUMERIC), 8.400, 'DAILY', ?, ?, ?, 'NORMAL')
                """, businessId, "L" + System.nanoTime(), totalRepayable, withdrawalAccountId,
                startedOn, startedOn.plusDays(365));
    }
}
