package com.jangoyebo.forecast.planned;

import com.jangoyebo.NeedsDocker;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 자동 반영한 거래를 예정 거래에 정확히 붙인다 (API 9.6).
 *
 * <p>최소 매처(9.5)는 금액·날짜로 <b>추정</b>하고 허용폭이 좁다. 따라잡기로 늦게 나간 거래는
 * 그 폭 밖이라 영영 안 붙는데, 우리는 금융망 거래번호를 알고 있으므로 추정할 필요가 없다.
 */
@NeedsDocker
@SpringBootTest
class PlannedExecutionReconcileTests {

    private static final LocalDate TODAY = LocalDate.now();

    @Autowired
    private PlannedExecutionReconciler reconciler;

    @Autowired
    private PlannedExecutionRepository executions;

    @Autowired
    private PlannedTransactionRepository planned;

    @Autowired
    private PlannedMatchService matchService;

    @Autowired
    private JdbcTemplate jdbc;

    private Long businessId;
    private Long accountId;
    private Long patternId;

    @BeforeEach
    void setUp() {
        Long userId = jdbc.queryForObject("""
                INSERT INTO core.users (email, password_hash, name, status)
                VALUES (?, 'hash', '김사장', 'ACTIVE') RETURNING id
                """, Long.class, "recon-" + System.nanoTime() + "@test.local");
        businessId = jdbc.queryForObject("""
                INSERT INTO core.business (owner_user_id, name, taxation_type, status)
                VALUES (?, '카페 한잔', 'GENERAL', 'ACTIVE') RETURNING id
                """, Long.class, userId);
        accountId = jdbc.queryForObject("""
                INSERT INTO core.account (business_id, provider, bank_code, account_number_encrypted,
                                          account_number_hash, account_number_masked, name,
                                          status, sync_status, current_balance, balance_as_of)
                VALUES (?, 'FINOPENAPI', '999', 'enc', ?, '999-**-1234', '주계좌',
                        'CONNECTED', 'SUCCESS', 5000000, now()) RETURNING id
                """, Long.class, businessId, "hash-" + System.nanoTime());
        patternId = jdbc.queryForObject("""
                INSERT INTO forecast.recurring_pattern
                       (business_id, account_id, name, flow_type, cadence_type, day_of_week,
                        expected_amount_min, expected_amount_max, confidence, status)
                VALUES (?, ?, '최민서', 'WITHDRAWAL', 'WEEKLY', 5, 210000, 210000, 0.9, 'CONFIRMED')
                RETURNING id
                """, Long.class, businessId, accountId);
    }

    /**
     * 사흘 늦게 나간 거래도 <b>원래 회차에</b> 붙는다.
     *
     * <p>최소 매처의 {@code WEEKLY} 허용폭은 하루다 — 이 거래는 그 폭 밖이라 매처로는 못 붙는다.
     */
    @Test
    void 늦게_나간_거래도_원래_예정거래에_붙는다() {
        LocalDate due = TODAY.minusDays(3);
        Long plannedId = planned(due, "210000");
        Long transactionId = transaction("FIN-LATE", TODAY, "210000");
        ledger(plannedId, due, "210000", "FIN-LATE", TODAY);

        assertThat(reconciler.reconcile(businessId, TODAY)).isEqualTo(1);

        PlannedTransaction row = planned.findById(plannedId).orElseThrow();
        assertThat(row.getStatus()).isEqualTo(PlannedStatus.MATCHED);
        assertThat(row.getMatchedTransactionId()).isEqualTo(transactionId);
        assertThat(row.getMatchedDateDiffDays()).as("실제일 − 예정일").isEqualTo(3);
        assertThat(row.getMatchedAmountDiff()).isEqualByComparingTo("0");

        assertThat(executions.findByPlannedTransactionId(plannedId).orElseThrow().getStatus())
                .isEqualTo(PlannedExecutionStatus.MATCHED);
    }

    /** 동기화가 아직 그 거래를 안 가져왔다. 원장을 열어 둔 채 다음 실행이 다시 본다. */
    @Test
    void 아직_동기화되지_않았으면_그대로_둔다() {
        Long plannedId = planned(TODAY, "210000");
        ledger(plannedId, TODAY, "210000", "FIN-NOT-YET", TODAY);

        assertThat(reconciler.reconcile(businessId, TODAY)).isZero();

        assertThat(executions.findByPlannedTransactionId(plannedId).orElseThrow().getStatus())
                .isEqualTo(PlannedExecutionStatus.EXECUTED);
        assertThat(planned.findById(plannedId).orElseThrow().getStatus())
                .isEqualTo(PlannedStatus.EXPECTED);
    }

    /**
     * <b>정확 매칭은 닻을 옮기지 않는다.</b>
     *
     * <p>닻 재조정은 「실제가 늦게 들어왔으니 앞으로의 예정일도 민다」를 한다. 그런데 자동 반영이
     * 늦은 것은 거래처가 늦게 준 것이 아니라 <b>우리 서버가 꺼져 있었던 것</b>이다. 그걸로
     * 일정을 밀면 앞으로의 회차가 통째로 어긋난다.
     */
    @Test
    void 정확_매칭은_앞으로의_예정일을_밀지_않는다() {
        LocalDate due = TODAY.minusDays(3);
        Long plannedId = planned(due, "210000");
        Long future = planned(TODAY.plusDays(4), "210000");
        transaction("FIN-LATE", TODAY, "210000");
        ledger(plannedId, due, "210000", "FIN-LATE", TODAY);

        reconciler.reconcile(businessId, TODAY);
        // 매처가 그다음에 돈다 — 이 거래는 이미 붙어 있으므로 매처도 닻 재조정도 보지 않는다.
        matchService.matchAndCollect(businessId, TODAY);

        assertThat(planned.findById(future).orElseThrow().getExpectedDate())
                .isEqualTo(TODAY.plusDays(4));
    }

    /** 매처가 먼저 같은 거래를 붙였다. 결과가 같으니 원장만 따라간다. */
    @Test
    void 매처가_먼저_붙였으면_원장만_정리한다() {
        Long plannedId = planned(TODAY, "210000");
        Long transactionId = transaction("FIN-1", TODAY, "210000");
        ledger(plannedId, TODAY, "210000", "FIN-1", TODAY);
        jdbc.update("""
                UPDATE forecast.planned_transaction
                   SET status = 'MATCHED', matched_transaction_id = ?, matched_date_diff_days = 0
                 WHERE id = ?
                """, transactionId, plannedId);

        assertThat(reconciler.reconcile(businessId, TODAY)).isZero();

        assertThat(executions.findByPlannedTransactionId(plannedId).orElseThrow().getStatus())
                .isEqualTo(PlannedExecutionStatus.MATCHED);
    }

    /**
     * 그 거래를 다른 예정 거래가 가져갔다.
     *
     * <p>{@code uk_pt_matched} 는 flush 때 터져 <b>이 실행 전체를 되돌린다.</b> 그래서 붙이기
     * 전에 미리 걸러야 한다 — 한 건 때문에 나머지가 다 사라지면 안 된다.
     */
    @Test
    void 이미_다른_예정거래에_붙은_거래는_건너뛴다() {
        Long taken = planned(TODAY, "210000");
        Long mine = planned(TODAY.minusDays(1), "210000");
        Long transactionId = transaction("FIN-1", TODAY, "210000");
        jdbc.update("""
                UPDATE forecast.planned_transaction
                   SET status = 'MATCHED', matched_transaction_id = ?, matched_date_diff_days = 0
                 WHERE id = ?
                """, transactionId, taken);
        ledger(mine, TODAY.minusDays(1), "210000", "FIN-1", TODAY);

        assertThat(reconciler.reconcile(businessId, TODAY)).isZero();

        assertThat(planned.findById(mine).orElseThrow().getStatus()).isEqualTo(PlannedStatus.EXPECTED);
        // 원장은 닫는다 — 열어 두면 매 실행마다 같은 실패를 반복한다.
        assertThat(executions.findByPlannedTransactionId(mine).orElseThrow().getStatus())
                .isEqualTo(PlannedExecutionStatus.MATCHED);
    }

    // --- 픽스처 --------------------------------------------------------------

    private Long planned(LocalDate date, String amount) {
        return jdbc.queryForObject("""
                INSERT INTO forecast.planned_transaction
                       (business_id, account_id, source_type, recurring_pattern_id, name,
                        expected_date, original_expected_date, flow_type,
                        amount_expected, amount_min, amount_max, status)
                VALUES (?, ?, 'PATTERN', ?, '최민서', ?, ?, 'WITHDRAWAL', ?::numeric, ?::numeric,
                        ?::numeric, 'EXPECTED')
                RETURNING id
                """, Long.class, businessId, accountId, patternId, date, date, amount, amount, amount);
    }

    private Long transaction(String externalId, LocalDate date, String amount) {
        return jdbc.queryForObject("""
                INSERT INTO core.transaction
                       (business_id, account_id, external_transaction_id, source_type,
                        occurred_at, transaction_date, flow_type, amount, description)
                VALUES (?, ?, ?, 'FIN_API', ?, ?, 'WITHDRAWAL', ?::numeric, '최민서')
                RETURNING id
                """, Long.class, businessId, accountId, externalId,
                date.atStartOfDay().atZone(java.time.ZoneId.of("Asia/Seoul")).toOffsetDateTime(),
                date, amount);
    }

    private void ledger(Long plannedId, LocalDate due, String amount, String finId, LocalDate finDate) {
        jdbc.update("""
                INSERT INTO forecast.planned_execution
                       (planned_transaction_id, business_id, account_id, recurring_pattern_id,
                        due_date, flow_type, amount, summary, fin_transaction_unique_no,
                        fin_transaction_date, status, executed_at)
                VALUES (?, ?, ?, ?, ?, 'WITHDRAWAL', ?::numeric, '최민서', ?, ?, 'EXECUTED', now())
                """, plannedId, businessId, accountId, patternId, due, amount, finId, finDate);
    }
}
