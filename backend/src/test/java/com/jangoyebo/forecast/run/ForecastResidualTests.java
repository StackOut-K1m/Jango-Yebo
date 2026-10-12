package com.jangoyebo.forecast.run;

import com.jangoyebo.NeedsDocker;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 매처와 잔여 집계가 실행에 실제로 반영되는지 (S15P21C105-136).
 *
 * <p><b>{@code 매칭된_예정거래는_곡선에서_빠진다} 가 이 이슈의 존재 이유다.</b>
 * 그 경로는 {@code S15P21C105-132} 에서 이미 만들었지만 {@code MATCHED} 가 생기지 않아
 * 발동한 적이 없었다. 여기서 처음으로 실제 매칭이 태운다.
 *
 * <p>실행 스레드는 테스트에서 꺼 두므로 {@code execute()} 를 직접 부른다.
 */
@NeedsDocker
@SpringBootTest
class ForecastResidualTests {

    private static final LocalDate TODAY = LocalDate.now();

    @Autowired
    private ForecastRunWorker worker;

    @Autowired
    private ForecastRunService runService;

    @Autowired
    private JdbcTemplate jdbc;

    private Long businessId;
    private Long accountId;

    @BeforeEach
    void setUp() {
        Long userId = jdbc.queryForObject("""
                INSERT INTO core.users (email, password_hash, name, status)
                VALUES (?, 'hash', '김사장', 'ACTIVE') RETURNING id
                """, Long.class, "residual-" + System.nanoTime() + "@test.local");
        businessId = jdbc.queryForObject("""
                INSERT INTO core.business (owner_user_id, name, taxation_type, status)
                VALUES (?, '온기식당', 'GENERAL', 'ACTIVE') RETURNING id
                """, Long.class, userId);
        accountId = jdbc.queryForObject("""
                INSERT INTO core.account (business_id, provider, bank_code, account_number_encrypted,
                                          account_number_hash, account_number_masked, name,
                                          status, sync_status, current_balance, balance_as_of)
                VALUES (?, 'FINOPENAPI', '004', 'enc', ?, '004-**-1234', '주계좌',
                        'CONNECTED', 'SUCCESS', 5000000, now()) RETURNING id
                """, Long.class, businessId, "hash-" + System.nanoTime());
    }

    @Test
    void 매칭된_예정거래는_곡선에서_빠진다() {
        // 이 이슈의 존재 이유다. 이미 잔액에 반영된 것을 다시 더하면 이중 계상이다.
        // S15P21C105-132 가 만든 경로를 여기서 처음으로 실제 매칭이 태운다.
        plannedWithdrawal(TODAY.plusDays(1), "3000000");
        worker.execute(businessId);
        BigDecimal withoutMatch = firstDayBalance();

        // 같은 금액의 실제 출금이 들어와 붙는다.
        transaction(TODAY, "WITHDRAWAL", "3000000");
        worker.execute(businessId);
        BigDecimal withMatch = firstDayBalance();

        // 예정 거래가 빠졌으므로 곡선이 300만원 위로 올라간다.
        assertThat(withMatch.subtract(withoutMatch)).isEqualByComparingTo("3000000");
    }

    @Test
    void 실행이_잔여_표본_일수를_남긴다() {
        transaction(TODAY.minusDays(10), "DEPOSIT", "500000");

        worker.execute(businessId);

        Integer historyDays = jdbc.queryForObject("""
                SELECT (basis -> 'residual' ->> 'historyDays')::int
                  FROM forecast.forecast_run
                 WHERE business_id = ? AND status = 'COMPLETED'
                 ORDER BY base_at DESC LIMIT 1
                """, Integer.class, businessId);

        assertThat(historyDays).isEqualTo(11);   // 10일 전 ~ 오늘
    }

    @Test
    void 거래가_없으면_표본_일수가_비어_있다() {
        worker.execute(businessId);

        String historyDays = jdbc.queryForObject("""
                SELECT basis -> 'residual' ->> 'historyDays'
                  FROM forecast.forecast_run
                 WHERE business_id = ? AND status = 'COMPLETED'
                 ORDER BY base_at DESC LIMIT 1
                """, String.class, businessId);

        assertThat(historyDays).isNull();
    }

    @Test
    void 표본이_충분하면_잔여를_태운다() {
        // S15P21C105-136 은 시계열만 만들고 「아직 태우지 않는다」를 단정했다. 이제 태운다.
        // 테스트 환경에는 워커가 없으므로 통계로 내려간 결과를 본다 — 그것이 폴백의 증거다.
        transaction(TODAY.minusDays(200), "DEPOSIT", "500000");

        worker.execute(businessId);

        assertThat(residualSource()).isEqualTo("BASELINE");
    }

    @Test
    void 표본이_모자라면_잔여를_태우지_않는다() {
        // 근거 없는 숫자를 내느니 「모른다」를 낸다. 곡선은 예정 거래만으로도 나온다.
        transaction(TODAY.minusDays(10), "DEPOSIT", "500000");

        worker.execute(businessId);

        assertThat(residualSource()).isEqualTo("NONE");
    }

    /**
     * <b>확정한 패턴의 과거 거래는 잔여에서 빠진다</b> (S15P21C105-151).
     *
     * <p>예정 거래는 앞으로만 만들어져 과거에는 뺄 대상이 없었다. 그래서 갓 승인한 패턴의
     * 과거 거래가 잔여에 통째로 남고 예정 투영과 <b>이중 계상</b>됐다.
     *
     * <p><b>집계된 시계열을 직접 본다.</b> 실행 결과(`basis.residual.total*`)는 그 시계열을
     * 보고 낸 <b>예측</b>이라, 통계 폴백이 양쪽 다 0 을 내면 차이가 안 드러난다.
     * 뺄셈이 돌았는지는 집계 단계에서만 보인다.
     */
    @Test
    void 확정한_패턴의_과거거래는_잔여에서_빠진다() {
        Long patternId = confirmedPattern();
        for (int i = 0; i < 12; i++) {
            link(patternId, transaction(TODAY.minusDays(200L - i * 7L), "DEPOSIT", "500000"));
        }

        var inputs = runService.loadResidual(businessId, TODAY);

        assertThat(inputs.series().totalDeposit()).isEqualByComparingTo("0");
    }

    /** 후보는 곡선에 안 들어가므로 그 거래는 잔여에 남아야 한다. 빼면 곡선이 반대로 기운다. */
    @Test
    void 후보_상태_패턴의_거래는_잔여에_남는다() {
        Long patternId = candidatePattern();
        for (int i = 0; i < 12; i++) {
            link(patternId, transaction(TODAY.minusDays(200L - i * 7L), "DEPOSIT", "500000"));
        }

        var inputs = runService.loadResidual(businessId, TODAY);

        assertThat(inputs.series().totalDeposit()).isEqualByComparingTo("6000000");
    }

    private Long confirmedPattern() {
        return pattern("CONFIRMED");
    }

    private Long candidatePattern() {
        return pattern("CANDIDATE");
    }

    private Long pattern(String status) {
        return jdbc.queryForObject("""
                INSERT INTO forecast.recurring_pattern
                    (business_id, account_id, name, flow_type, cadence_type, holiday_rule,
                     expected_amount_min, expected_amount_max, confidence, status, created_at, updated_at)
                VALUES (?, ?, '카드정산', 'DEPOSIT', 'WEEKLY', 'NEXT_BUSINESS_DAY',
                        500000, 500000, 0.9, ?, now(), now())
                RETURNING id
                """, Long.class, businessId, accountId, status);
    }

    private void link(Long patternId, Long transactionId) {
        jdbc.update("""
                INSERT INTO forecast.recurring_pattern_transaction
                    (recurring_pattern_id, transaction_id, match_score, created_at)
                VALUES (?, ?, 0.9, now())
                """, patternId, transactionId);
    }

    private String residualSource() {
        return jdbc.queryForObject("""
                SELECT basis -> 'residual' ->> 'source'
                  FROM forecast.forecast_run
                 WHERE business_id = ? AND status = 'COMPLETED'
                 ORDER BY base_at DESC LIMIT 1
                """, String.class, businessId);
    }

    // --- 헬퍼 -----------------------------------------------------------------

    private BigDecimal firstDayBalance() {
        return jdbc.queryForObject("""
                SELECT d.balance_expected
                  FROM forecast.daily_balance_forecast d
                  JOIN forecast.forecast_run r ON r.id = d.forecast_run_id
                 WHERE r.business_id = ? AND r.status = 'COMPLETED'
                 ORDER BY r.base_at DESC, d.forecast_date ASC
                 LIMIT 1
                """, BigDecimal.class, businessId);
    }

    private Long plannedWithdrawal(LocalDate date, String amount) {
        return jdbc.queryForObject("""
                INSERT INTO forecast.planned_transaction
                    (business_id, account_id, source_type, name, expected_date, original_expected_date,
                     flow_type, amount_expected, amount_min, amount_max, status, version, created_at, updated_at)
                VALUES (?, ?, 'USER', '급여', ?, ?, 'WITHDRAWAL', ?, ?, ?, 'CONFIRMED', 0, now(), now())
                RETURNING id
                """, Long.class, businessId, accountId, date, date,
                new BigDecimal(amount), new BigDecimal(amount), new BigDecimal(amount));
    }

    private Long transaction(LocalDate date, String flowType, String amount) {
        return jdbc.queryForObject("""
                INSERT INTO core.transaction
                    (business_id, account_id, source_type, occurred_at, transaction_date, flow_type,
                     amount, description, classification_status, is_excluded, created_at, updated_at)
                VALUES (?, ?, 'CSV', ?, ?, ?, ?, '적요', 'UNCLASSIFIED', false, now(), now())
                RETURNING id
                """, Long.class, businessId, accountId,
                java.sql.Timestamp.valueOf(date.atStartOfDay()), date, flowType, new BigDecimal(amount));
    }
}
