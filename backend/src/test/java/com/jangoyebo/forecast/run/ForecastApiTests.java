package com.jangoyebo.forecast.run;

import com.jangoyebo.NeedsDocker;
import com.jangoyebo.common.security.JwtTokenProvider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.ZoneId;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 예상 잔액 곡선·정산 공백 API (P0-07·08 인수 지점).
 *
 * <p>실행 스레드는 테스트에서 꺼 두고({@code FORECAST_WORKER_ENABLED=false})
 * {@code execute()} 를 직접 부른다 — {@code JobWorkerTests} 가 {@code pollOnce()} 를
 * 직접 부르는 것과 같은 방식이다. 5초 디바운스를 기다리면 테스트가 그만큼 느려지고
 * 단정 시점과 경합한다.
 */
@NeedsDocker
@SpringBootTest
@AutoConfigureMockMvc
class ForecastApiTests {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JwtTokenProvider tokenProvider;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private ForecastRunWorker worker;

    @Autowired
    private ForecastRunRepository runs;

    private Long businessId;
    private String token;

    @BeforeEach
    void setUp() {
        String email = "forecast-api-" + System.nanoTime() + "@test.local";
        Long userId = jdbc.queryForObject("""
                INSERT INTO core.users (email, password_hash, name, status)
                VALUES (?, 'hash', '김사장', 'ACTIVE') RETURNING id
                """, Long.class, email);
        businessId = jdbc.queryForObject("""
                INSERT INTO core.business (owner_user_id, name, taxation_type, status)
                VALUES (?, '온기식당', 'GENERAL', 'ACTIVE') RETURNING id
                """, Long.class, userId);
        token = tokenProvider.createAccessToken(userId, email);
    }

    @Test
    void 계좌가_없으면_예측할_수_없다고_알린다() throws Exception {
        // 거래가 전혀 없어 계산 자체가 불가능할 때만 422 다(공통규약 2.5).
        mockMvc.perform(get(url("/forecast/balance")).header("Authorization", bearer()))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error.code").value("FORECAST_INSUFFICIENT_DATA"));
    }

    @Test
    void 계좌는_있는데_실행이_없으면_예약하고_알린다() throws Exception {
        connectedAccount(new BigDecimal("5000000"));

        mockMvc.perform(get(url("/forecast/balance")).header("Authorization", bearer()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("FORECAST_NOT_READY"));

        // 조회가 계산하지는 않지만 재계산을 예약한다 — 첫 방문이 첫 실행을 건다.
        worker.execute(businessId);
        assertThat(runs.findFirstByBusinessIdAndStatusOrderByBaseAtDesc(
                businessId, ForecastRunStatus.COMPLETED)).isPresent();
    }

    @Test
    void 곡선이_삼십일치로_나온다() throws Exception {
        connectedAccount(new BigDecimal("5000000"));
        worker.execute(businessId);

        mockMvc.perform(get(url("/forecast/balance")).header("Authorization", bearer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.days").value(30))
                .andExpect(jsonPath("$.data.curve.length()").value(30))
                .andExpect(jsonPath("$.data.currentBalance").value(5000000))
                .andExpect(jsonPath("$.data.modelVersion").value("engine-1.2/none"))
                .andExpect(jsonPath("$.data.basis.residual.applied").value(false))
                .andExpect(jsonPath("$.data.basis.residual.source").value("NONE"))
                .andExpect(jsonPath("$.data.curve[0].riskLevel").value("SAFE"));
    }

    @Test
    void 안전선을_설정하지_않으면_키가_있고_값이_비어_있다() throws Exception {
        // null 이 의미를 갖는 필드다 — 키가 없으면 "아직 안 온 값"과 구분되지 않는다.
        connectedAccount(new BigDecimal("5000000"));
        worker.execute(businessId);

        mockMvc.perform(get(url("/forecast/balance")).header("Authorization", bearer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.safetyLine").doesNotExist())
                .andExpect(jsonPath("$.data").value(org.hamcrest.Matchers.hasKey("safetyLine")));
    }

    @Test
    void 안전선을_넘기면_주의_등급이_나온다() throws Exception {
        connectedAccount(new BigDecimal("1000000"));
        safetyLine(new BigDecimal("2000000"));
        worker.execute(businessId);

        mockMvc.perform(get(url("/forecast/balance")).header("Authorization", bearer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.safetyLine").value(2000000))
                .andExpect(jsonPath("$.data.curve[0].riskLevel").value("CAUTION"));
    }

    @Test
    void 안전선을_바꾸면_재계산_없이_등급이_바뀐다() throws Exception {
        // 등급은 저장하지 않는 파생값이라 조회 시점의 안전선과 비교한다.
        connectedAccount(new BigDecimal("1000000"));
        worker.execute(businessId);

        mockMvc.perform(get(url("/forecast/balance")).header("Authorization", bearer()))
                .andExpect(jsonPath("$.data.curve[0].riskLevel").value("SAFE"));

        safetyLine(new BigDecimal("2000000"));

        mockMvc.perform(get(url("/forecast/balance")).header("Authorization", bearer()))
                .andExpect(jsonPath("$.data.curve[0].riskLevel").value("CAUTION"));
    }

    @Test
    void 기간을_주면_잘라서_준다() throws Exception {
        connectedAccount(new BigDecimal("5000000"));
        worker.execute(businessId);

        mockMvc.perform(get(url("/forecast/balance")).param("days", "7").header("Authorization", bearer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.days").value(7))
                .andExpect(jsonPath("$.data.curve.length()").value(7));
    }

    @Test
    void 허용하지_않는_기간은_거부한다() throws Exception {
        connectedAccount(new BigDecimal("5000000"));
        worker.execute(businessId);

        mockMvc.perform(get(url("/forecast/balance")).param("days", "10").header("Authorization", bearer()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"));
    }

    @Test
    void 계좌별_곡선은_조용히_무시하지_않고_막는다() throws Exception {
        // 무시하면 사용자가 계좌별 값을 본다고 착각한다.
        connectedAccount(new BigDecimal("5000000"));
        worker.execute(businessId);

        mockMvc.perform(get(url("/forecast/balance")).param("accountId", "1").header("Authorization", bearer()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.message").value(org.hamcrest.Matchers.containsString("계좌별")));
    }

    @Test
    void 예정_출금이_잔액을_넘기면_공백이_잡힌다() throws Exception {
        connectedAccount(new BigDecimal("1000000"));
        plannedWithdrawal(LocalDate.now().plusDays(3), new BigDecimal("3000000"));
        worker.execute(businessId);

        mockMvc.perform(get(url("/forecast/cash-gaps")).header("Authorization", bearer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items.length()").value(1))
                .andExpect(jsonPath("$.data.items[0].riskLevel").value("GAP"))
                .andExpect(jsonPath("$.data.items[0].maxShortfallAmount").value(2000000))
                .andExpect(jsonPath("$.data.items[0].cashGapId").isNumber())
                .andExpect(jsonPath("$.data.basis.referenceCurveDays").value(30));
    }

    @Test
    void 공백_식별자가_알림_식별자와_같다() throws Exception {
        // 공백 구간은 저장하지 않는 파생값이라 알림의 id 를 쓴다(ERD 1.3).
        connectedAccount(new BigDecimal("1000000"));
        plannedWithdrawal(LocalDate.now().plusDays(3), new BigDecimal("3000000"));
        worker.execute(businessId);

        Long alertId = jdbc.queryForObject("""
                SELECT id FROM core.alert WHERE business_id = ? AND alert_type = 'CASH_GAP'
                """, Long.class, businessId);

        mockMvc.perform(get(url("/forecast/cash-gaps")).header("Authorization", bearer()))
                .andExpect(jsonPath("$.data.items[0].cashGapId").value(alertId));
    }

    @Test
    void 공백_알림에_확정_표현을_쓰지_않는다() throws Exception {
        connectedAccount(new BigDecimal("1000000"));
        plannedWithdrawal(LocalDate.now().plusDays(3), new BigDecimal("3000000"));
        worker.execute(businessId);

        String message = jdbc.queryForObject("""
                SELECT message FROM core.alert WHERE business_id = ? AND alert_type = 'CASH_GAP'
                """, String.class, businessId);

        assertThat(message).contains("가능성");
        assertThat(message).doesNotContain("부족합니다");
    }

    @Test
    void 공백이_사라지면_알림이_내려간다() throws Exception {
        connectedAccount(new BigDecimal("1000000"));
        Long plannedId = plannedWithdrawal(LocalDate.now().plusDays(3), new BigDecimal("3000000"));
        worker.execute(businessId);
        assertThat(activeCashGapCount()).isEqualTo(1);

        jdbc.update("UPDATE forecast.planned_transaction SET status = 'CANCELLED' WHERE id = ?", plannedId);
        worker.execute(businessId);

        assertThat(activeCashGapCount()).isZero();
        mockMvc.perform(get(url("/forecast/cash-gaps")).header("Authorization", bearer()))
                .andExpect(jsonPath("$.data.items.length()").value(0));
    }

    /**
     * 당일자 예정 출금도 곡선에 실린다.
     *
     * <p>시작 잔액은 이미 일어난 거래만 담는다. 곡선에 당일 점이 없다는 이유로 여기서까지
     * 빠지면 <b>전 구간이 그 금액만큼 높아져 공백 경보가 울리지 않는다.</b> 기준일을 정하는
     * 워커와 같은 KST 로 날짜를 잡는다.
     */
    @Test
    void 당일자_예정_출금도_공백으로_잡힌다() throws Exception {
        connectedAccount(new BigDecimal("1000000"));
        plannedWithdrawal(LocalDate.now(ZoneId.of("Asia/Seoul")), new BigDecimal("3000000"));

        worker.execute(businessId);

        mockMvc.perform(get(url("/forecast/cash-gaps")).header("Authorization", bearer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items.length()").value(1))
                .andExpect(jsonPath("$.data.items[0].maxShortfallAmount").value(2000000));
    }

    @Test
    void 매칭된_예정거래는_곡선에_들어가지_않는다() throws Exception {
        // 이미 일어나 잔액에 반영된 것을 다시 더하면 이중 계상이다.
        connectedAccount(new BigDecimal("1000000"));
        Long plannedId = plannedWithdrawal(LocalDate.now().plusDays(3), new BigDecimal("3000000"));
        jdbc.update("UPDATE forecast.planned_transaction SET status = 'MATCHED' WHERE id = ?", plannedId);

        worker.execute(businessId);

        mockMvc.perform(get(url("/forecast/cash-gaps")).header("Authorization", bearer()))
                .andExpect(jsonPath("$.data.items.length()").value(0));
    }

    @Test
    void 다시_계산해도_같은_곡선이_나온다() throws Exception {
        // 인수 조건: 동일 입력 + 동일 modelVersion 에 대해 재현된다 (SRS 13장).
        // baseTime 은 실행 시각이라 당연히 달라진다 — 재현성이 보장하는 것은 곡선 값이다.
        connectedAccount(new BigDecimal("5000000"));
        plannedWithdrawal(LocalDate.now().plusDays(5), new BigDecimal("300000"));

        worker.execute(businessId);
        String first = curveOnly();
        worker.execute(businessId);
        String second = curveOnly();

        assertThat(second).isEqualTo(first);
        assertThat(first).contains("expectedBalance");
    }

    @Test
    void 수동_새로고침은_접수만_한다() throws Exception {
        connectedAccount(new BigDecimal("5000000"));

        mockMvc.perform(post(url("/forecast/refresh"))
                        .header("Authorization", bearer())
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.data.status").value("SCHEDULED"));
    }

    @Test
    void 예측_옵션은_잔여_토글만_실제로_켠다() throws Exception {
        mockMvc.perform(get(url("/forecast-settings")).header("Authorization", bearer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.applySeasonality").value(false))
                .andExpect(jsonPath("$.data.applyVatReserve").value(false));
    }

    @Test
    void 남의_사업장은_보이지_않는다() throws Exception {
        Long otherUser = jdbc.queryForObject("""
                INSERT INTO core.users (email, password_hash, name, status)
                VALUES (?, 'hash', '남', 'ACTIVE') RETURNING id
                """, Long.class, "other-" + System.nanoTime() + "@test.local");
        Long otherBusiness = jdbc.queryForObject("""
                INSERT INTO core.business (owner_user_id, name, taxation_type, status)
                VALUES (?, '남의가게', 'GENERAL', 'ACTIVE') RETURNING id
                """, Long.class, otherUser);

        mockMvc.perform(get("/api/v1/businesses/" + otherBusiness + "/forecast/balance")
                        .header("Authorization", bearer()))
                .andExpect(status().isForbidden());
    }

    // --- 헬퍼 -----------------------------------------------------------------

    /**
     * <b>공백에 지원 제도 제안이 붙는다</b> (17.3 마지막 줄 · API 10.2).
     *
     * <p>공백 연계는 두 방향인데 오래 한쪽만 있었다 — 지원 제도 화면에서 공백을 보여 주는
     * 것은 되고, <b>공백 알림에서 지원 제도를 보여 주는 것은 없었다.</b> 사장님이 실제로
     * 마주치는 쪽은 후자다. 알림은 밀려오고 지원 제도 화면은 찾아 들어가야 한다.
     */
    @Test
    void 공백에_지원_제도_제안이_붙는다() throws Exception {
        jdbc.update("DELETE FROM core.support_program");
        connectedAccount(new BigDecimal("1000000"));
        plannedWithdrawal(LocalDate.now().plusDays(3), new BigDecimal("3000000"));
        supportProgram("소상공인 긴급 경영안정자금", null);
        worker.execute(businessId);

        mockMvc.perform(get(url("/forecast/cash-gaps")).header("Authorization", bearer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items[0].suggestions.length()").value(2))
                .andExpect(jsonPath("$.data.items[0].suggestions[0].type").value("SCENARIO"))
                .andExpect(jsonPath("$.data.items[0].suggestions[1].type").value("SUPPORT_PROGRAM"))
                .andExpect(jsonPath("$.data.items[0].suggestions[1].description")
                        .value("신청할 수 있는 지원 제도 1건 확인"));
    }

    /**
     * <b>지급 시기를 모르면 「공백 전」을 말하지 않는다</b> (도메인 규칙 1).
     *
     * <p>공고의 97% 가 지급 시기를 적지 않는다(실측 32건 중 1건). 모르는 것을 근거로
     * 「공백이 메워진다」고 말하면 사장님이 오지 않을 돈을 기다리며 다른 대책을 접는다.
     * 위 테스트가 그 경우고, 이쪽은 <b>실제로 안다</b> — 그때만 문구가 달라진다.
     */
    @Test
    void 집행이_공백보다_빠른_것이_있으면_문구가_달라진다() throws Exception {
        jdbc.update("DELETE FROM core.support_program");
        connectedAccount(new BigDecimal("1000000"));
        plannedWithdrawal(LocalDate.now().plusDays(3), new BigDecimal("3000000"));
        supportProgram("즉시 지급 바우처", 1);
        worker.execute(businessId);

        mockMvc.perform(get(url("/forecast/cash-gaps")).header("Authorization", bearer()))
                .andExpect(jsonPath("$.data.items[0].suggestions[1].description")
                        .value("공백 전 집행 가능성이 있는 지원 제도 1건 확인"));
    }

    /**
     * <b>공고가 없으면 그 줄을 아예 빼고 시나리오만 남는다.</b>
     *
     * <p>「지원 제도 0건」은 사장님에게 쓸모가 없다. 수집 cron 이 아직 안 붙은 환경에서는
     * 그 빈 줄이 <b>매번</b> 나간다 — 명세 10.2 의 둘째 예시도 시나리오 하나뿐이다.
     */
    @Test
    void 공고가_없으면_시나리오_제안만_나간다() throws Exception {
        jdbc.update("DELETE FROM core.support_program");
        connectedAccount(new BigDecimal("1000000"));
        plannedWithdrawal(LocalDate.now().plusDays(3), new BigDecimal("3000000"));
        worker.execute(businessId);

        mockMvc.perform(get(url("/forecast/cash-gaps")).header("Authorization", bearer()))
                .andExpect(jsonPath("$.data.items[0].suggestions.length()").value(1))
                .andExpect(jsonPath("$.data.items[0].suggestions[0].type").value("SCENARIO"));
    }

    /** 조건을 하나도 안 건 공고다 — 점수 0.5 로 추천에 남는다(17.3). */
    private void supportProgram(String title, Integer disbursementDays) {
        jdbc.update("""
                INSERT INTO core.support_program
                    (source_name, external_id, title, source_url, support_type, status,
                     application_end_date, expected_disbursement_days, eligibility)
                VALUES ('BIZINFO', ?, ?, 'https://example.test/p', 'SUBSIDY', 'OPEN',
                        ?, ?, '{"regions": [], "industries": []}'::jsonb)
                """, "PBLN_" + System.nanoTime(), title,
                LocalDate.now().plusDays(30), disbursementDays);
    }

    private String url(String suffix) {
        return "/api/v1/businesses/" + businessId + suffix;
    }

    private String bearer() {
        return "Bearer " + token;
    }

    /** 곡선 배열만 뽑는다. 실행 시각·실행 id 처럼 회차마다 달라지는 값은 뺀다. */
    private String curveOnly() throws Exception {
        String body = mockMvc.perform(get(url("/forecast/balance")).header("Authorization", bearer()))
                .andReturn().getResponse().getContentAsString();
        int from = body.indexOf("\"curve\"");
        int to = body.indexOf("\"lowestPoint\"");
        return body.substring(from, to);
    }

    private int activeCashGapCount() {
        Integer count = jdbc.queryForObject("""
                SELECT count(*) FROM core.alert
                 WHERE business_id = ? AND alert_type = 'CASH_GAP' AND status = 'ACTIVE'
                """, Integer.class, businessId);
        return count == null ? 0 : count;
    }

    private void connectedAccount(BigDecimal balance) {
        jdbc.update("""
                INSERT INTO core.account (business_id, provider, bank_code, account_number_encrypted,
                                          account_number_hash, account_number_masked, name,
                                          status, sync_status, current_balance, balance_as_of)
                VALUES (?, 'FINOPENAPI', '004', 'enc', ?, '004-**-1234', '주계좌',
                        'CONNECTED', 'SUCCESS', ?, now())
                """, businessId, "hash-" + System.nanoTime(), balance);
    }

    private void safetyLine(BigDecimal amount) {
        jdbc.update("""
                INSERT INTO core.safety_balance_policy (business_id, method, amount, auto_update, updated_at)
                VALUES (?, 'MANUAL', ?, false, now())
                ON CONFLICT (business_id) DO UPDATE SET amount = EXCLUDED.amount
                """, businessId, amount);
    }

    private Long plannedWithdrawal(LocalDate date, BigDecimal amount) {
        return jdbc.queryForObject("""
                INSERT INTO forecast.planned_transaction
                    (business_id, source_type, name, expected_date, original_expected_date, flow_type,
                     amount_expected, amount_min, amount_max, status, version, created_at, updated_at)
                VALUES (?, 'USER', '급여', ?, ?, 'WITHDRAWAL', ?, ?, ?, 'CONFIRMED', 0, now(), now())
                RETURNING id
                """, Long.class, businessId, date, date, amount, amount, amount);
    }
}
