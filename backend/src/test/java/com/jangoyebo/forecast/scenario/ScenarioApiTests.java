package com.jangoyebo.forecast.scenario;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import com.jangoyebo.NeedsDocker;
import com.jangoyebo.common.security.JwtTokenProvider;
import com.jangoyebo.forecast.run.ForecastRunWorker;
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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * What-if 시나리오 API (API 11.1·11.2 — P0-09·P0-13 인수 지점).
 *
 * <p>{@code FORECAST_WORKER_ENABLED=false} 라 실행 스레드가 돌지 않는다. 기준 실행은
 * {@code worker.execute()} 를 직접 불러 만든다 — 시나리오는 <b>완료된 실행 위에서만</b>
 * 계산되므로 그것 없이는 모든 요청이 {@code 409} 다.
 *
 * <p>날짜는 <b>오늘 기준 상대값</b>으로 쓴다. 기준일이 실행 시점의 KST 날짜라 고정 날짜를
 * 쓰면 「지난 날짜」 검증에 걸려 어느 날 갑자기 깨진다.
 */
@NeedsDocker
@SpringBootTest
@AutoConfigureMockMvc
class ScenarioApiTests {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JwtTokenProvider tokenProvider;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private ForecastRunWorker worker;

    @Autowired
    private ObjectMapper objectMapper;

    private static final LocalDate TODAY = LocalDate.now(ZoneId.of("Asia/Seoul"));

    private Long businessId;
    private String token;

    @BeforeEach
    void setUp() {
        String email = "scenario-api-" + System.nanoTime() + "@test.local";
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

    // --- 11.1 계산 -----------------------------------------------------------------

    @Test
    void 저장하지_않으면_결과만_돌려준다() throws Exception {
        ready();

        mockMvc.perform(create(body(30, false, depositDecrease())))
                .andExpect(status().isOk())
                // save=false 는 DB 에 남기지 않는다(ERD — 파라미터만 저장한다).
                .andExpect(jsonPath("$.data.scenarioId").doesNotExist())
                .andExpect(jsonPath("$.data.days").value(30))
                .andExpect(jsonPath("$.data.originalCurve.length()").value(30))
                .andExpect(jsonPath("$.data.modifiedCurve.length()").value(30))
                .andExpect(jsonPath("$.data.comparison.original").exists())
                .andExpect(jsonPath("$.data.comparison.modified").exists());

        assertThat(scenarioCount()).isZero();
    }

    @Test
    void 저장하면_201_과_아이디가_나오고_파라미터가_남는다() throws Exception {
        ready();

        String response = mockMvc.perform(create(
                        "{\"name\":\"정산 지연 + 머신 교체\",\"days\":30,\"save\":true,\"parameters\":["
                                + settlementDelay(2) + "," + extraExpense(10, 1500000) + "]}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.scenarioId").exists())
                .andExpect(jsonPath("$.data.name").value("정산 지연 + 머신 교체"))
                .andReturn().getResponse().getContentAsString();

        Long scenarioId = json(response).path("data").path("scenarioId").asLong();
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM forecast.scenario_adjustment WHERE scenario_id = ?",
                Integer.class, scenarioId)).isEqualTo(2);
    }

    @Test
    void 이름을_비우면_서버가_기본값을_붙인다() throws Exception {
        ready();

        // DB 가 NOT NULL 이고 화면은 빈 이름을 아예 보내지 않는다.
        mockMvc.perform(create(body(30, true, depositDecrease())))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.name").value("이름 없는 시나리오"));
    }

    @Test
    void 기간을_줄이면_곡선도_줄어든다() throws Exception {
        ready();

        mockMvc.perform(create(body(7, false, depositDecrease())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.days").value(7))
                .andExpect(jsonPath("$.data.originalCurve.length()").value(7));
    }

    @Test
    void 기간은_7_14_30_뿐이다() throws Exception {
        ready();

        mockMvc.perform(create(body(10, false, depositDecrease())))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"));
    }

    /**
     * 원본 곡선은 대시보드(10.1)와 <b>같은 숫자</b>여야 한다.
     *
     * <p>시나리오는 예측을 새로 돌리지 않고 저장된 실행의 원장만 변형한다. 그래서 파라미터가
     * 원장을 건드리지 않는 한 원본 곡선은 10.1 이 내는 값과 원 단위까지 같다 — 이것이
     * 「원본 대비 얼마나 나빠지나」라는 비교가 성립하는 근거다.
     */
    @Test
    void 원본_곡선은_예상_잔액_곡선과_같은_값이다() throws Exception {
        ready();
        plannedWithdrawal(5, new BigDecimal("2000000"));
        worker.execute(businessId);

        JsonNode forecast = json(mockMvc.perform(get(url("/forecast/balance?days=30"))
                        .header("Authorization", bearer()))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString()).path("data").path("curve");

        JsonNode scenario = json(mockMvc.perform(create(body(30, false, depositDecrease())))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString()).path("data").path("originalCurve");

        assertThat(scenario).hasSize(forecast.size());
        for (int i = 0; i < forecast.size(); i++) {
            assertThat(scenario.get(i).path("date").asText())
                    .isEqualTo(forecast.get(i).path("date").asText());
            assertThat(scenario.get(i).path("expectedBalance").asLong())
                    .isEqualTo(forecast.get(i).path("expectedBalance").asLong());
        }
    }

    @Test
    void 지출일을_미루면_최저_잔액이_달라진다() throws Exception {
        ready();
        Long plannedId = plannedWithdrawal(3, new BigDecimal("4000000"));
        worker.execute(businessId);

        String response = mockMvc.perform(create(body(30, false,
                        expenseDateShift(plannedId, TODAY.plusDays(25)))))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        JsonNode comparison = json(response).path("data").path("comparison");
        // 나가는 돈은 같지만 나가는 날이 달라 최저점이 뒤로 밀린다.
        assertThat(comparison.path("original").path("lowestBalanceDate").asText())
                .isNotEqualTo(comparison.path("modified").path("lowestBalanceDate").asText());
    }

    @Test
    void 안전선이_없으면_하회_일수를_내지_않고_이유를_말한다() throws Exception {
        ready();

        String response = mockMvc.perform(create(body(30, false, depositDecrease())))
                .andExpect(status().isOk())
                // 0 과 다르다 — 「하회한 날이 없다」가 아니라 「잴 선이 없다」다.
                .andExpect(jsonPath("$.data.comparison.original.belowSafetyLineDays").doesNotExist())
                .andReturn().getResponse().getContentAsString();

        assertThat(response).contains("안전선이 설정되지 않아");
    }

    @Test
    void 안전선이_있으면_하회_일수를_센다() throws Exception {
        ready();
        // 잔액 2천만에 안전선 3천만 — 예정 거래가 없어 곡선이 수평이고 30일 내내 아래다.
        safetyLine(new BigDecimal("30000000"));

        mockMvc.perform(create(body(30, false, depositDecrease())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.comparison.original.belowSafetyLineDays").value(30));
    }

    @Test
    void 안전선_위에_있으면_하회_일수가_0이다() throws Exception {
        ready();
        safetyLine(new BigDecimal("10000000"));

        mockMvc.perform(create(body(30, false, depositDecrease())))
                .andExpect(status().isOk())
                // 0 은 「재 봤더니 없다」다 — 키가 빠지는 「잴 선이 없다」와 다르다.
                .andExpect(jsonPath("$.data.comparison.original.belowSafetyLineDays").value(0));
    }

    @Test
    void 대출_실행은_원금_입금과_상환_회차를_곡선에_넣는다() throws Exception {
        ready();

        String response = mockMvc.perform(create(body(30, false,
                        "{\"type\":\"LOAN_EXECUTION\",\"amount\":10000000,\"interestRate\":0.045,"
                                + "\"loanPeriodDays\":365,\"repaymentCycle\":\"DAILY\","
                                + "\"disbursementDate\":\"" + TODAY.plusDays(1) + "\"}")))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        JsonNode curve = json(response).path("data").path("modifiedCurve");
        JsonNode original = json(response).path("data").path("originalCurve");
        // 실행 다음 날부터 매일 빠지지만, 받은 돈이 훨씬 커서 잔액은 원본보다 높다.
        assertThat(curve.get(1).path("expectedBalance").asLong())
                .isGreaterThan(original.get(1).path("expectedBalance").asLong());

        // **창 끝에서는 다시 내려가 있어야 한다.** 금융망 실물은 일할이자를 기간만큼 부과해
        // (P·(1 + r·d²/365) — S15P21C105-221 실측) 1,000만·4.5%·365일이면 하루 477,397 원씩
        // 빠진다. 받은 1,000만이 한 달 안에 다 나가고도 남는다.
        //
        // 첫날만 보면 이것을 놓친다 — 그날은 입금만 있고 상환은 다음 날부터라 무조건 높다.
        // 이자 식이 정상 단리로 돌아가면 하루 28,630 원(30일에 83만)이라 받은 1,000만이
        // 그대로 남는다 — 창 끝이 원본보다 높아지고 그때 이 단언이 걸린다.
        assertThat(curve.get(curve.size() - 1).path("expectedBalance").asLong())
                .as("일 상환이 받은 돈을 넘어서므로 창 끝 잔액은 원본보다 낮다")
                .isLessThan(original.get(original.size() - 1).path("expectedBalance").asLong());
    }

    /**
     * <b>매출 변화는 「매출로 분류된 예정 입금」에만 걸린다</b>(11.1).
     *
     * <p>대상을 고르는 값이 {@code DEPOSIT_DECREASE}(패턴)와 달라서, 패턴 쪽이 멀쩡해도
     * 이쪽만 조용히 0건이 될 수 있다. 실제로 그랬다 — 분류가 탐지보다 늦게 돌면 패턴에
     * 분류가 안 붙고, 그 패턴이 만든 예정 입금도 분류가 비어 곡선이 꿈쩍하지 않았다.
     * 그 경로를 여기서 종단으로 묶는다.
     */
    @Test
    void 매출로_분류된_예정_입금은_매출_변화가_곡선을_움직인다() throws Exception {
        connectedAccount(new BigDecimal("20000000"));
        plannedDeposit(5, new BigDecimal("4000000"), "CARD_SETTLEMENT");
        worker.execute(businessId);

        String response = mockMvc.perform(create(body(30, false, salesChange("-0.5"))))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        JsonNode data = json(response).path("data");
        assertThat(data.path("warnings").toString())
                .as("대상이 있으므로 「매출로 분류된 예정 입금이 없다」가 붙으면 안 된다")
                .doesNotContain("매출로 분류된");
        // 입금 전날은 같고, 입금일부터 절반이 덜 들어와 낮다.
        assertThat(data.path("modifiedCurve").get(3).path("expectedBalance").asLong())
                .isEqualTo(data.path("originalCurve").get(3).path("expectedBalance").asLong());
        assertThat(data.path("modifiedCurve").get(29).path("expectedBalance").asLong())
                .isLessThan(data.path("originalCurve").get(29).path("expectedBalance").asLong());
    }

    /**
     * 반대쪽. 분류가 비면 <b>곡선은 그대로 두고 경고로 말한다</b> — 조용히 0을 곱하거나
     * 아무 입금이나 골라 잡지 않는다. 「거래 없음」과 「분류 없음」은 다른 말이다.
     */
    @Test
    void 분류가_비면_매출_변화를_반영하지_않고_경고한다() throws Exception {
        connectedAccount(new BigDecimal("20000000"));
        plannedDeposit(5, new BigDecimal("4000000"), null);
        worker.execute(businessId);

        String response = mockMvc.perform(create(body(30, false, salesChange("-0.5"))))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        JsonNode data = json(response).path("data");
        assertThat(data.path("warnings").toString()).contains("매출로 분류된");
        assertThat(data.path("modifiedCurve").get(29).path("expectedBalance").asLong())
                .isEqualTo(data.path("originalCurve").get(29).path("expectedBalance").asLong());
    }

    // --- 검증 ----------------------------------------------------------------------

    @Test
    void 모르는_파라미터는_본문_오류가_아니라_파라미터_오류다() throws Exception {
        ready();

        mockMvc.perform(create(body(30, false, "{\"type\":\"MATERIAL_COST_CHANGE\",\"rate\":0.1}")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("SCENARIO_PARAM_INVALID"))
                .andExpect(jsonPath("$.error.detail.index").value(0));
    }

    @Test
    void 파라미터가_없으면_본문_오류다() throws Exception {
        ready();

        mockMvc.perform(create("{\"days\":30,\"save\":false,\"parameters\":[]}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"));
    }

    @Test
    void 입금_예정_거래는_지출일_변경의_대상이_아니다() throws Exception {
        ready();
        Long depositId = plannedDeposit(5, new BigDecimal("800000"));
        worker.execute(businessId);

        mockMvc.perform(create(body(30, false, expenseDateShift(depositId, TODAY.plusDays(10)))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("SCENARIO_PARAM_INVALID"))
                .andExpect(jsonPath("$.error.message").value(
                        org.hamcrest.Matchers.containsString("plannedTransactionId")));
    }

    /**
     * 남의 것과 없는 것에 같은 답을 준다.
     *
     * <p>「그 예정 거래는 남의 것입니다」는 그것이 존재한다는 사실을 흘린다. 계좌번호
     * 검증이 같은 이유로 뭉뚱그려져 있다.
     */
    @Test
    void 남의_예정_거래와_없는_예정_거래의_답이_같다() throws Exception {
        ready();
        Long strangerId = strangerPlannedWithdrawal();

        String missing = message(mockMvc.perform(create(body(30, false,
                        expenseDateShift(999_999L, TODAY.plusDays(10)))))
                .andExpect(status().isBadRequest())
                .andReturn().getResponse().getContentAsString());
        String stranger = message(mockMvc.perform(create(body(30, false,
                        expenseDateShift(strangerId, TODAY.plusDays(10)))))
                .andExpect(status().isBadRequest())
                .andReturn().getResponse().getContentAsString());

        assertThat(stranger).isEqualTo(missing);
    }

    @Test
    void 실행이_없으면_예약하고_알린다() throws Exception {
        connectedAccount(new BigDecimal("20000000"));

        mockMvc.perform(create(body(30, false, depositDecrease())))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("FORECAST_NOT_READY"));
    }

    @Test
    void 계좌가_없으면_계산할_수_없다고_알린다() throws Exception {
        mockMvc.perform(create(body(30, false, depositDecrease())))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error.code").value("FORECAST_INSUFFICIENT_DATA"));
    }

    /** 틀린 본문이 예측 재계산을 예약하면 안 된다 — 그래서 본문을 먼저 본다. */
    @Test
    void 본문이_틀리면_실행_없음보다_먼저_걸린다() throws Exception {
        connectedAccount(new BigDecimal("20000000"));

        mockMvc.perform(create(body(10, false, depositDecrease())))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"));
    }

    // --- 11.2 저장·조회·삭제 --------------------------------------------------------

    @Test
    void 저장_목록은_최신순이고_파라미터를_함께_준다() throws Exception {
        ready();
        create201("{\"name\":\"먼저\",\"days\":30,\"save\":true,\"parameters\":["
                + settlementDelay(2) + "]}");
        create201("{\"name\":\"나중\",\"days\":30,\"save\":true,\"parameters\":["
                + salesChange("-0.05") + "]}");

        mockMvc.perform(get(url("/scenarios")).header("Authorization", bearer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items.length()").value(2))
                .andExpect(jsonPath("$.data.items[0].name").value("나중"))
                .andExpect(jsonPath("$.data.items[0].parameters[0].type").value("SALES_CHANGE"))
                .andExpect(jsonPath("$.data.items[0].parameters[0].rate").value(-0.05))
                // 페이징 필드를 두지 않는다(9.2·10.3 과 같다).
                .andExpect(jsonPath("$.data.totalElements").doesNotExist());
    }

    @Test
    void 저장한_시나리오는_열_때마다_지금_데이터로_다시_계산된다() throws Exception {
        ready();
        Long scenarioId = create201(body(30, true, depositDecrease()));

        String baseTime = json(mockMvc.perform(get("/api/v1/scenarios/" + scenarioId)
                        .header("Authorization", bearer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.scenarioId").value(scenarioId))
                .andReturn().getResponse().getContentAsString())
                .path("data").path("baseTime").asText();

        assertThat(baseTime).isEqualTo(latestBaseAt());
    }

    @Test
    void 이름을_바꿔도_파라미터는_그대로다() throws Exception {
        ready();
        Long scenarioId = create201(body(30, true, depositDecrease()));

        mockMvc.perform(patch("/api/v1/scenarios/" + scenarioId)
                        .header("Authorization", bearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"연휴 대비\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.name").value("연휴 대비"))
                .andExpect(jsonPath("$.data.parameters[0].type").value("DEPOSIT_DECREASE"));
    }

    @Test
    void 삭제하면_조정까지_사라진다() throws Exception {
        ready();
        Long scenarioId = create201(body(30, true, depositDecrease()));

        mockMvc.perform(delete("/api/v1/scenarios/" + scenarioId).header("Authorization", bearer()))
                .andExpect(status().isNoContent());

        mockMvc.perform(get("/api/v1/scenarios/" + scenarioId).header("Authorization", bearer()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("SCENARIO_NOT_FOUND"));
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM forecast.scenario_adjustment WHERE scenario_id = ?",
                Integer.class, scenarioId)).isZero();
    }

    @Test
    void 남의_시나리오는_볼_수_없다() throws Exception {
        ready();
        Long scenarioId = create201(body(30, true, depositDecrease()));
        String stranger = strangerToken();

        mockMvc.perform(get("/api/v1/scenarios/" + scenarioId)
                        .header("Authorization", "Bearer " + stranger))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("BUSINESS_ACCESS_DENIED"));
    }

    /**
     * 저장한 시나리오가 예정 거래를 붙잡지 않는다 ({@code V32}).
     *
     * <p>외래키에 삭제 규칙이 없으면 이 삭제가 {@code 500} 이 된다. 시나리오는 가정이지
     * 원장이 아니므로 남는 쪽은 시나리오다 — 대신 반영하지 못했다고 말한다.
     */
    @Test
    void 참조하던_예정_거래를_지워도_삭제가_되고_조회는_이유를_말한다() throws Exception {
        ready();
        Long plannedId = plannedWithdrawal(5, new BigDecimal("900000"));
        worker.execute(businessId);
        Long scenarioId = create201(body(30, true, expenseDateShift(plannedId, TODAY.plusDays(20))));

        mockMvc.perform(delete("/api/v1/planned-transactions/" + plannedId)
                        .header("Authorization", bearer()))
                .andExpect(status().isNoContent());

        String response = mockMvc.perform(get("/api/v1/scenarios/" + scenarioId)
                        .header("Authorization", bearer()))
                .andExpect(status().isOk())
                // 저장한 조건은 목록에 그대로 남는다 — 사라지면 결과가 왜 달라졌는지 알 수 없다.
                .andExpect(jsonPath("$.data.parameters[0].type").value("EXPENSE_DATE_SHIFT"))
                .andReturn().getResponse().getContentAsString();

        assertThat(response).contains("삭제되어");
    }

    // --- 상품 매출 변화 -------------------------------------------------------------

    @Test
    void 구성비가_없으면_상품_매출_변화를_거절한다() throws Exception {
        ready();

        mockMvc.perform(create(body(30, false, productSalesChange())))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("PRODUCT_SALES_NOT_REGISTERED"));
    }

    @Test
    void 구성비가_있으면_쓴_기간을_경고로_밝힌다() throws Exception {
        ready();
        productSales("카페라떼", "161950");
        productSales("아메리카노", "508500");

        String response = mockMvc.perform(create(body(30, false, productSalesChange())))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        // 계절성 때문에 어느 기간 구성비인지 항상 밝혀야 한다(11.1).
        assertThat(response).contains("카페라떼 구성비").contains("2026-08-01~2026-08-31");
    }

    @Test
    void 등록되지_않은_상품은_거절한다() throws Exception {
        ready();
        productSales("카페라떼", "161950");

        mockMvc.perform(create(body(30, false,
                        "{\"type\":\"PRODUCT_SALES_CHANGE\",\"productKey\":\"없는메뉴\",\"priceRate\":0.1}")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("SCENARIO_PARAM_INVALID"));
    }

    // --- helpers -------------------------------------------------------------------

    /** 계산이 가능한 최소 상태 — 연결 계좌 하나와 완료된 실행 하나. */
    private void ready() {
        connectedAccount(new BigDecimal("20000000"));
        worker.execute(businessId);
    }

    private org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder create(String body) {
        return post(url("/scenarios"))
                .header("Authorization", bearer())
                .contentType(MediaType.APPLICATION_JSON)
                .content(body);
    }

    private Long create201(String body) throws Exception {
        String response = mockMvc.perform(create(body))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return json(response).path("data").path("scenarioId").asLong();
    }

    private static String body(int days, boolean save, String... parameters) {
        return "{\"days\":%d,\"save\":%s,\"parameters\":[%s]}"
                .formatted(days, save, String.join(",", parameters));
    }

    private static String depositDecrease() {
        return "{\"type\":\"DEPOSIT_DECREASE\",\"rate\":0.2}";
    }

    private static String settlementDelay(int days) {
        return "{\"type\":\"SETTLEMENT_DELAY\",\"delayDays\":%d}".formatted(days);
    }

    private static String salesChange(String rate) {
        return "{\"type\":\"SALES_CHANGE\",\"rate\":%s}".formatted(rate);
    }

    private static String extraExpense(int daysAhead, long amount) {
        return "{\"type\":\"EXTRA_EXPENSE\",\"date\":\"%s\",\"amount\":%d,\"title\":\"머신 교체\"}"
                .formatted(TODAY.plusDays(daysAhead), amount);
    }

    private static String expenseDateShift(Long plannedTransactionId, LocalDate newDate) {
        return "{\"type\":\"EXPENSE_DATE_SHIFT\",\"plannedTransactionId\":%d,\"newDate\":\"%s\"}"
                .formatted(plannedTransactionId, newDate);
    }

    private static String productSalesChange() {
        return "{\"type\":\"PRODUCT_SALES_CHANGE\",\"productKey\":\"카페라떼\","
                + "\"quantityRate\":-0.05,\"priceRate\":0.10}";
    }

    private JsonNode json(String response) throws Exception {
        return objectMapper.readTree(response);
    }

    private String message(String response) throws Exception {
        return json(response).path("error").path("message").asText();
    }

    private String url(String suffix) {
        return "/api/v1/businesses/" + businessId + suffix;
    }

    private String bearer() {
        return "Bearer " + token;
    }

    private String latestBaseAt() {
        return jdbc.queryForObject("""
                SELECT to_char(base_at AT TIME ZONE 'UTC', 'YYYY-MM-DD"T"HH24:MI:SS.USZ')
                  FROM forecast.forecast_run
                 WHERE business_id = ? AND status = 'COMPLETED'
                 ORDER BY base_at DESC LIMIT 1
                """, String.class, businessId);
    }

    private int scenarioCount() {
        return jdbc.queryForObject(
                "SELECT count(*) FROM forecast.scenario WHERE business_id = ?", Integer.class, businessId);
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

    private Long plannedWithdrawal(int daysAhead, BigDecimal amount) {
        return planned(businessId, daysAhead, amount, "WITHDRAWAL");
    }

    private Long plannedDeposit(int daysAhead, BigDecimal amount) {
        return planned(businessId, daysAhead, amount, "DEPOSIT");
    }

    /** @param categoryCode {@code null} 이면 분류가 비어 있는 입금 — 매출 변화의 대상이 아니다 */
    private Long plannedDeposit(int daysAhead, BigDecimal amount, String categoryCode) {
        Long id = plannedDeposit(daysAhead, amount);
        jdbc.update("""
                UPDATE forecast.planned_transaction
                   SET category_id = (SELECT id FROM core.transaction_category WHERE code = ?)
                 WHERE id = ?
                """, categoryCode, id);
        return id;
    }

    private Long planned(Long owner, int daysAhead, BigDecimal amount, String flowType) {
        LocalDate date = TODAY.plusDays(daysAhead);
        return jdbc.queryForObject("""
                INSERT INTO forecast.planned_transaction
                    (business_id, source_type, name, expected_date, original_expected_date, flow_type,
                     amount_expected, amount_min, amount_max, status, version, created_at, updated_at)
                VALUES (?, 'USER', '급여', ?, ?, ?, ?, ?, ?, 'CONFIRMED', 0, now(), now())
                RETURNING id
                """, Long.class, owner, date, date, flowType, amount, amount, amount);
    }

    private void productSales(String productName, String billedAmount) {
        jdbc.update("""
                INSERT INTO core.product_sales_period
                    (business_id, period_start, period_end, product_name, product_key,
                     billed_amount, source_type, checksum_status)
                VALUES (?, DATE '2026-08-01', DATE '2026-08-31', ?, ?, ?, 'OCR', 'MATCHED')
                """, businessId, productName, productName, new BigDecimal(billedAmount));
    }

    private Long strangerPlannedWithdrawal() {
        return planned(strangerBusiness(), 5, new BigDecimal("500000"), "WITHDRAWAL");
    }

    private String strangerToken() {
        String email = "stranger-" + System.nanoTime() + "@test.local";
        Long userId = jdbc.queryForObject("""
                INSERT INTO core.users (email, password_hash, name, status)
                VALUES (?, 'hash', '남', 'ACTIVE') RETURNING id
                """, Long.class, email);
        jdbc.update("""
                INSERT INTO core.business (owner_user_id, name, taxation_type, status)
                VALUES (?, '남의 가게', 'GENERAL', 'ACTIVE')
                """, userId);
        return tokenProvider.createAccessToken(userId, email);
    }

    private Long strangerBusiness() {
        Long userId = jdbc.queryForObject("""
                INSERT INTO core.users (email, password_hash, name, status)
                VALUES (?, 'hash', '남', 'ACTIVE') RETURNING id
                """, Long.class, "stranger-biz-" + System.nanoTime() + "@test.local");
        return jdbc.queryForObject("""
                INSERT INTO core.business (owner_user_id, name, taxation_type, status)
                VALUES (?, '남의 가게', 'GENERAL', 'ACTIVE') RETURNING id
                """, Long.class, userId);
    }
}
