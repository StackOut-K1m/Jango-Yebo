package com.jangoyebo.forecast.run;

import com.jangoyebo.NeedsDocker;
import com.jangoyebo.common.security.JwtTokenProvider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import java.math.BigDecimal;
import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 예측 옵션과 잔여 경로 (API 10.7 · P0-07).
 *
 * <p>테스트 환경의 워커 주소({@code http://localhost:9998})는 열려 있지 않다. 그래서
 * {@code residual-mode: MODEL} 이어도 실제로는 <b>통계로 내려간 결과</b>를 본다 —
 * 이것이 「모델이 실패해도 곡선은 반드시 산출된다」(SRS 13장)를 있는 그대로 확인하는
 * 방식이다. 모델 경로 자체의 분기는 {@code ResidualProviderTests} 가 따로 덮는다.
 */
@NeedsDocker
@SpringBootTest
@AutoConfigureMockMvc
class ForecastSettingsApiTests {

    private static final String DIRTY_PREFIX = "forecast:dirty:";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JwtTokenProvider tokenProvider;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private ForecastRunWorker worker;

    @Autowired
    private StringRedisTemplate redis;

    private Long businessId;
    private Long accountId;
    private String token;

    @BeforeEach
    void setUp() throws Exception {
        String email = "forecast-settings-" + System.nanoTime() + "@test.local";
        Long userId = jdbc.queryForObject("""
                INSERT INTO core.users (email, password_hash, name, status)
                VALUES (?, 'hash', '김사장', 'ACTIVE') RETURNING id
                """, Long.class, email);
        businessId = jdbc.queryForObject("""
                INSERT INTO core.business (owner_user_id, name, taxation_type, status)
                VALUES (?, '온기식당', 'GENERAL', 'ACTIVE') RETURNING id
                """, Long.class, userId);
        token = tokenProvider.createAccessToken(userId, email);
        redis.delete(DIRTY_PREFIX + businessId);
    }

    // --- 10.7 조회·수정 --------------------------------------------------------

    @Test
    void 한_번도_건드리지_않으면_기본값이_온다() throws Exception {
        // 행이 없는 것은 오류가 아니다. 404 를 주면 화면이 빈 토글을 그리지 못한다.
        assertThat(rowCount()).isZero();

        getSettings()
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.applyResidualModel").value(true))
                .andExpect(jsonPath("$.data.applySeasonality").value(false))
                .andExpect(jsonPath("$.data.applyVatReserve").value(false))
                .andExpect(jsonPath("$.data.peakMonths.length()").value(0))
                .andExpect(jsonPath("$.data.offMonths.length()").value(0));

        // 조회는 행을 만들지 않는다 — 읽기만 했는데 쓰기가 일어나면 안 된다.
        assertThat(rowCount()).isZero();
    }

    @Test
    void 보내지_않은_필드는_그대로_둔다() throws Exception {
        patchSettings("""
                {"applyResidualModel": false, "peakMonths": [7, 8]}
                """).andExpect(status().isOk());

        patchSettings("""
                {"applySeasonality": true}
                """)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.applySeasonality").value(true))
                .andExpect(jsonPath("$.data.applyResidualModel").value(false))
                .andExpect(jsonPath("$.data.peakMonths.length()").value(2));
    }

    @Test
    void 성수기_월을_저장한다() throws Exception {
        // P1-07 이 서기 전까지 읽지 않지만, 사용자 입력을 버리면 다시 받아야 한다.
        patchSettings("""
                {"peakMonths": [7, 8, 12], "offMonths": [2]}
                """)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.peakMonths[0]").value(7))
                .andExpect(jsonPath("$.data.peakMonths[2]").value(12))
                .andExpect(jsonPath("$.data.offMonths[0]").value(2));

        getSettings()
                .andExpect(jsonPath("$.data.peakMonths.length()").value(3))
                .andExpect(jsonPath("$.data.offMonths.length()").value(1));
    }

    @Test
    void 사업장마다_따로_저장된다() throws Exception {
        patchSettings("""
                {"applyResidualModel": false}
                """).andExpect(status().isOk());

        Long otherUser = jdbc.queryForObject("""
                INSERT INTO core.users (email, password_hash, name, status)
                VALUES (?, 'hash', '두번째', 'ACTIVE') RETURNING id
                """, Long.class, "second-" + System.nanoTime() + "@test.local");
        Long otherBusiness = jdbc.queryForObject("""
                INSERT INTO core.business (owner_user_id, name, taxation_type, status)
                VALUES (?, '두번째가게', 'GENERAL', 'ACTIVE') RETURNING id
                """, Long.class, otherUser);
        String otherToken = tokenProvider.createAccessToken(otherUser, "second@test.local");

        mockMvc.perform(get("/api/v1/businesses/" + otherBusiness + "/forecast-settings")
                .header("Authorization", "Bearer " + otherToken))
                .andExpect(jsonPath("$.data.applyResidualModel").value(true));
    }

    @Test
    void 남의_사업장_옵션은_바꿀_수_없다() throws Exception {
        Long otherUser = jdbc.queryForObject("""
                INSERT INTO core.users (email, password_hash, name, status)
                VALUES (?, 'hash', '남', 'ACTIVE') RETURNING id
                """, Long.class, "outsider-" + System.nanoTime() + "@test.local");
        Long otherBusiness = jdbc.queryForObject("""
                INSERT INTO core.business (owner_user_id, name, taxation_type, status)
                VALUES (?, '남의가게', 'GENERAL', 'ACTIVE') RETURNING id
                """, Long.class, otherUser);

        mockMvc.perform(patch("/api/v1/businesses/" + otherBusiness + "/forecast-settings")
                .header("Authorization", bearer())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"applyResidualModel\": false}"))
                .andExpect(status().isForbidden());
    }

    // --- 토글이 곡선을 바꾼다 (화면구성도 SC-03-1) ------------------------------

    @Test
    void 토글이_켜져_있으면_잔여가_곡선에_실린다() throws Exception {
        // 워커가 없으므로 통계로 내려간다. 폴백해도 잔여는 실린다는 것이 요점이다.
        connectedAccount(new BigDecimal("5000000"));
        seedDailyTransactions(90);
        worker.execute(businessId);

        getBalance()
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.basis.residual.applied").value(true))
                .andExpect(jsonPath("$.data.basis.residual.source").value("BASELINE"))
                .andExpect(jsonPath("$.data.modelVersion").value("engine-1.2/weekday-median-0.1"))
                .andExpect(jsonPath("$.data.appliedOptions.applyResidualModel").value(true));
    }

    @Test
    void 토글을_끄면_잔여_없이_곡선이_나온다() throws Exception {
        // 인수 조건 — 끈 상태에서도 곡선과 부족일은 정상 산출된다.
        connectedAccount(new BigDecimal("5000000"));
        seedDailyTransactions(90);
        patchSettings("""
                {"applyResidualModel": false}
                """);
        worker.execute(businessId);

        getBalance()
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.curve.length()").value(30))
                .andExpect(jsonPath("$.data.basis.residual.applied").value(false))
                .andExpect(jsonPath("$.data.basis.residual.source").value("NONE"))
                .andExpect(jsonPath("$.data.modelVersion").value("engine-1.2/none"))
                .andExpect(jsonPath("$.data.appliedOptions.applyResidualModel").value(false));
    }

    @Test
    void 토글을_껐다_켜면_밴드_폭이_달라진다() throws Exception {
        // 화면구성도 SC-03-1 — 사용자가 토글을 만졌을 때 곡선에서 차이가 보여야 한다.
        connectedAccount(new BigDecimal("5000000"));
        seedDailyTransactions(90);

        worker.execute(businessId);
        long withResidual = bandWidthOnLastDay();

        patchSettings("""
                {"applyResidualModel": false}
                """);
        worker.execute(businessId);
        long withoutResidual = bandWidthOnLastDay();

        assertThat(withResidual).isGreaterThan(withoutResidual);
        // 예정 거래가 없으니 잔여를 빼면 밴드가 아예 닫힌다.
        assertThat(withoutResidual).isZero();
    }

    @Test
    void 옵션을_바꾸면_재계산이_예약된다() throws Exception {
        // 재계산을 안 걸면 다음 트리거가 올 때까지 옛 곡선이 남는다.
        connectedAccount(new BigDecimal("5000000"));
        seedDailyTransactions(90);
        worker.execute(businessId);

        patchSettings("""
                {"applyResidualModel": false}
                """).andExpect(status().isOk());

        assertThat(isDirty()).isTrue();
    }

    @Test
    void 곡선과_무관한_옵션은_재계산을_걸지_않는다() throws Exception {
        // P1 옵션은 저장만 된다. 여기서 재계산을 걸면 같은 곡선을 다시 계산할 뿐이다.
        connectedAccount(new BigDecimal("5000000"));
        worker.execute(businessId);

        patchSettings("""
                {"applySeasonality": true, "peakMonths": [7]}
                """).andExpect(status().isOk());

        assertThat(isDirty()).isFalse();
    }

    @Test
    void 같은_값으로_다시_보내면_재계산을_걸지_않는다() throws Exception {
        // 화면이 전체 폼을 그대로 PATCH 해도 곡선이 흔들리지 않아야 한다.
        connectedAccount(new BigDecimal("5000000"));
        worker.execute(businessId);

        patchSettings("""
                {"applyResidualModel": true}
                """).andExpect(status().isOk());

        assertThat(isDirty()).isFalse();
    }

    @Test
    void 잔여가_실리면_근거에_보정_내역이_남는다() throws Exception {
        // 사용자가 「왜 이 금액인가」를 되짚을 수 있어야 한다(도메인 규칙 1).
        connectedAccount(new BigDecimal("5000000"));
        seedDailyTransactions(90);
        worker.execute(businessId);

        getBalance()
                .andExpect(jsonPath("$.data.adjustments.length()").value(1))
                .andExpect(jsonPath("$.data.adjustments[0].type").value("MODEL_RESIDUAL"))
                .andExpect(jsonPath("$.data.basis.residual.historyDays").value(90));
    }

    @Test
    void 표본이_짧으면_사유를_알려준다() throws Exception {
        // 「예보가 이상하다」만으로는 진단이 안 된다. 짧다는 사실을 사용자가 봐야 한다.
        connectedAccount(new BigDecimal("5000000"));
        seedDailyTransactions(10);
        worker.execute(businessId);

        getBalance()
                .andExpect(jsonPath("$.data.basis.residual.source").value("NONE"))
                .andExpect(jsonPath("$.data.warnings").value(
                        org.hamcrest.Matchers.hasItem(
                                org.hamcrest.Matchers.containsString("최소 30일"))));
    }

    // --- 헬퍼 -----------------------------------------------------------------

    private ResultActions getSettings() throws Exception {
        return mockMvc.perform(get(url("/forecast-settings")).header("Authorization", bearer()));
    }

    private ResultActions getBalance() throws Exception {
        return mockMvc.perform(get(url("/forecast/balance")).header("Authorization", bearer()));
    }

    private ResultActions patchSettings(String body) throws Exception {
        return mockMvc.perform(patch(url("/forecast-settings"))
                .header("Authorization", bearer())
                .contentType(MediaType.APPLICATION_JSON)
                .content(body));
    }

    private String url(String suffix) {
        return "/api/v1/businesses/" + businessId + suffix;
    }

    private String bearer() {
        return "Bearer " + token;
    }

    /** {@code execute()} 가 표시를 지우므로, 재계산 예약을 보려면 그 뒤에 다시 봐야 한다. */
    private boolean isDirty() {
        return Boolean.TRUE.equals(redis.hasKey(DIRTY_PREFIX + businessId));
    }

    private int rowCount() {
        Integer count = jdbc.queryForObject(
                "SELECT count(*) FROM forecast.forecast_setting WHERE business_id = ?",
                Integer.class, businessId);
        return count == null ? 0 : count;
    }

    /** 마지막 날의 {@code balanceMax − balanceMin}. 잔여가 실리면 벌어지고 아니면 닫힌다. */
    private long bandWidthOnLastDay() {
        BigDecimal width = jdbc.queryForObject("""
                SELECT d.balance_max - d.balance_min
                  FROM forecast.daily_balance_forecast d
                  JOIN forecast.forecast_run r ON r.id = d.forecast_run_id
                 WHERE r.business_id = ? AND r.status = 'COMPLETED'
                 ORDER BY r.base_at DESC, d.forecast_date DESC
                 LIMIT 1
                """, BigDecimal.class, businessId);
        return width == null ? 0L : width.longValue();
    }

    private void connectedAccount(BigDecimal balance) {
        accountId = jdbc.queryForObject("""
                INSERT INTO core.account (business_id, provider, bank_code, account_number_encrypted,
                                          account_number_hash, account_number_masked, name,
                                          status, sync_status, current_balance, balance_as_of)
                VALUES (?, 'FINOPENAPI', '004', 'enc', ?, '004-**-1234', '주계좌',
                        'CONNECTED', 'SUCCESS', ?, now())
                RETURNING id
                """, Long.class, businessId, "hash-" + System.nanoTime(), balance);
    }

    /**
     * 오늘부터 거슬러 {@code days} 일치 입금을 하루 한 건씩 넣는다.
     *
     * <p>예정 거래와 짝지어지지 않으므로 전부 잔여로 남는다 — 통계 베이스라인이 값을
     * 낼 수 있는 최소 조건이다.
     *
     * <p><b>같은 요일 안에서도 금액이 달라야 한다.</b> 금액이 {@code i % 7} 로만 정해지면
     * 요일마다 값이 하나씩만 쌓여 {@code q10 = q50 = q90} 이 되고, 잔여를 태워도 밴드가
     * 닫힌 채로 나온다 — 폭을 재는 단정이 조용히 무의미해진다. 그래서 주차도 섞는다.
     */
    private void seedDailyTransactions(int days) {
        LocalDate today = LocalDate.now();
        for (int i = 0; i < days; i++) {
            LocalDate date = today.minusDays(i);
            jdbc.update("""
                    INSERT INTO core.transaction
                        (business_id, account_id, external_transaction_id, source_type, occurred_at,
                         transaction_date, flow_type, amount, description, classification_status,
                         needs_confirmation)
                    VALUES (?, ?, ?, 'FIN_API', now(), ?, 'DEPOSIT', ?, '카드 정산',
                            'CONFIRMED', FALSE)
                    """, businessId, accountId, "ext-" + System.nanoTime() + "-" + i,
                    date, new BigDecimal(200_000 + (i % 7) * 20_000 + (i / 7) * 3_000));
        }
    }
}
