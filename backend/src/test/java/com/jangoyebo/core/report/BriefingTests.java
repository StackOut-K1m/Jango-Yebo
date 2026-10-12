package com.jangoyebo.core.report;

import com.jangoyebo.NeedsDocker;
import com.jangoyebo.common.security.JwtTokenProvider;
import com.jangoyebo.forecast.run.ForecastRunWorker;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 주간·월간 브리핑 — 지표와 저장 (API 15.2 · P1-17).
 *
 * <p><b>이 슬라이스는 AI 없이 완결된다.</b> 명세가 「생성 실패 시 지표만이라도 반환한다」고
 * 정했으므로 문장이 없는 브리핑도 정상 산출물이다.
 *
 * <p><b>지표가 이 기능의 전부다.</b> LLM 은 여기 없는 수치를 쓸 수 없으므로(도메인 규칙 2),
 * 지표가 틀리거나 빈약하면 문장도 그대로 틀리거나 빈약해진다.
 *
 * <p><b>Docker가 필요하다.</b>
 */
@NeedsDocker
@SpringBootTest
@AutoConfigureMockMvc
class BriefingTests {

    private static final LocalDate FROM = LocalDate.of(2026, 9, 7);
    private static final LocalDate TO = LocalDate.of(2026, 9, 13);

    @Autowired private MockMvc mockMvc;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private JwtTokenProvider tokenProvider;
    @Autowired private BriefingService service;
    @Autowired private ForecastRunWorker worker;

    private Long userId;
    private Long businessId;
    private Long accountId;
    private String token;

    @BeforeEach
    void setUp() {
        String unique = String.valueOf(System.nanoTime());
        String email = "briefing-" + unique + "@test.local";
        userId = jdbc.queryForObject("""
                INSERT INTO core.users (email, password_hash, name) VALUES (?, 'x', '정유선')
                RETURNING id
                """, Long.class, email);
        token = tokenProvider.createAccessToken(userId, email);
        businessId = jdbc.queryForObject("""
                INSERT INTO core.business (owner_user_id, name) VALUES (?, '유선상회') RETURNING id
                """, Long.class, userId);
        accountId = jdbc.queryForObject("""
                INSERT INTO core.account (business_id, provider, bank_code, account_number_encrypted,
                                          account_number_hash, account_number_masked, name,
                                          status, sync_status, current_balance, balance_as_of)
                VALUES (?, 'FINOPENAPI', '004', 'enc', ?, '004-**-1234', '주계좌',
                        'CONNECTED', 'SUCCESS', 5000000, now()) RETURNING id
                """, Long.class, businessId, "hash-" + unique);
    }

    // --- 픽스처 --------------------------------------------------------------

    private void tx(String category, String flow, String amount, LocalDate date) {
        Long categoryId = jdbc.queryForObject(
                "SELECT id FROM core.transaction_category WHERE code = ?", Long.class, category);
        jdbc.update("""
                INSERT INTO core.transaction
                    (business_id, account_id, category_id, source_type, occurred_at,
                     transaction_date, flow_type, amount, description, deduplication_key,
                     classification_status)
                VALUES (?, ?, ?, 'FIN_API', ?, ?, ?, ?, ?, ?, 'CONFIRMED')
                """, businessId, accountId, categoryId, Timestamp.from(Instant.now()), date,
                flow, new BigDecimal(amount), category + " 거래", "dedup-" + System.nanoTime());
    }

    private Long plannedWithdrawal(String name, String amount, LocalDate date) {
        return jdbc.queryForObject("""
                INSERT INTO forecast.planned_transaction
                    (business_id, account_id, source_type, name, expected_date,
                     original_expected_date, flow_type, amount_expected, amount_min, amount_max,
                     status)
                VALUES (?, ?, 'USER', ?, ?, ?, 'WITHDRAWAL', ?, ?, ?, 'EXPECTED') RETURNING id
                """, Long.class, businessId, accountId, name, date, date,
                new BigDecimal(amount), new BigDecimal(amount), new BigDecimal(amount));
    }

    /** 저장된 JSONB 원문. <b>LLM 이 읽을 바로 그 글자다</b>(20.7 입력). */
    private String rawMetrics(Long briefingId) {
        return jdbc.queryForObject(
                "SELECT metrics::text FROM core.report WHERE id = ?", String.class, briefingId);
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> metricsOf(Long briefingId) {
        try {
            return new tools.jackson.databind.ObjectMapper()
                    .readValue(rawMetrics(briefingId), List.class);
        } catch (Exception e) {
            throw new IllegalStateException("지표를 읽지 못했다", e);
        }
    }

    /** 파싱하면 정수도 {@code 2000000.0} 이 된다 — 값 비교는 숫자로 한다. */
    private static void assertValue(Map<String, Object> metric, String expected) {
        assertThat(new BigDecimal(metric.get("value").toString()))
                .isEqualByComparingTo(new BigDecimal(expected));
    }

    private static Map<String, Object> labelled(List<Map<String, Object>> metrics, String prefix) {
        return metrics.stream()
                .filter(m -> String.valueOf(m.get("label")).startsWith(prefix))
                .findFirst()
                .orElseThrow(() -> new AssertionError("「%s」로 시작하는 지표가 없다".formatted(prefix)));
    }

    private ResultActions detail(Long briefingId) throws Exception {
        return mockMvc.perform(get("/api/v1/briefings/" + briefingId)
                .header("Authorization", "Bearer " + token));
    }

    // --- 지표 ----------------------------------------------------------------

    @Test
    void sumsDepositsAndWithdrawalsForThePeriod() {
        tx("CASH_SALES", "DEPOSIT", "2000000", FROM.plusDays(1));
        tx("RENT", "WITHDRAWAL", "1200000", FROM.plusDays(2));

        Report report = service.generate(businessId, ReportType.WEEKLY, FROM, TO).orElseThrow();
        List<Map<String, Object>> metrics = metricsOf(report.getId());

        assertValue(labelled(metrics, "기간 입금 합계"), "2000000");
        assertValue(labelled(metrics, "기간 출금 합계"), "1200000");
    }

    /** 기간 밖 거래가 섞이면 브리핑이 지난주를 잘못 말한다. */
    @Test
    void leavesOutTransactionsOutsideThePeriod() {
        tx("CASH_SALES", "DEPOSIT", "2000000", FROM.plusDays(1));
        tx("CASH_SALES", "DEPOSIT", "9000000", TO.plusDays(1));

        Report report = service.generate(businessId, ReportType.WEEKLY, FROM, TO).orElseThrow();

        assertValue(labelled(metricsOf(report.getId()), "기간 입금 합계"), "2000000");
    }

    /** 증감률의 분모는 <b>직전 같은 길이 기간</b>이다 — 주간이면 그 전 주다. */
    @Test
    void comparesAgainstThePreviousPeriodOfTheSameLength() {
        tx("CASH_SALES", "DEPOSIT", "1000000", FROM.minusDays(3));   // 직전 주
        tx("CASH_SALES", "DEPOSIT", "1500000", FROM.plusDays(1));    // 이번 주

        Report report = service.generate(businessId, ReportType.WEEKLY, FROM, TO).orElseThrow();

        assertThat(new BigDecimal(
                labelled(metricsOf(report.getId()), "기간 입금 합계").get("changeRate").toString()))
                .as("1,000,000 → 1,500,000 이면 +0.5")
                .isEqualByComparingTo("0.5");
    }

    /**
     * <b>직전 기간이 0 이면 증감률을 내지 않는다.</b> 0 으로 나눌 수 없고, 「무한대로 늘었다」는
     * 사장님에게 아무 말도 아니다 — 그때는 절대액만 읽힌다.
     */
    @Test
    void omitsTheChangeRateWhenThereIsNothingToCompareWith() {
        tx("CASH_SALES", "DEPOSIT", "1500000", FROM.plusDays(1));

        Report report = service.generate(businessId, ReportType.WEEKLY, FROM, TO).orElseThrow();

        assertThat(labelled(metricsOf(report.getId()), "기간 입금 합계")).doesNotContainKey("changeRate");
    }

    /** 모든 지표에 근거가 붙는다 — 화면이 숫자를 눌러 원본으로 갈 수 있어야 한다(15.2). */
    @Test
    void attachesASourceRefToEveryMetric() {
        tx("CASH_SALES", "DEPOSIT", "2000000", FROM.plusDays(1));
        tx("RENT", "WITHDRAWAL", "1200000", FROM.plusDays(2));
        plannedWithdrawal("급여", "3000000", TO.plusDays(5));
        worker.execute(businessId);

        Report report = service.generate(businessId, ReportType.WEEKLY, FROM, TO).orElseThrow();

        assertThat(metricsOf(report.getId()))
                .isNotEmpty()
                .allSatisfy(metric -> assertThat(metric).containsKey("sourceRef"));
    }

    /** 예정 거래는 {@code forecast} 스키마라 포트로 읽는다. 근거에 id 가 실려야 되짚을 수 있다. */
    @Test
    void namesTheNextLargePaymentWithItsPlannedTransactionId() {
        tx("CASH_SALES", "DEPOSIT", "2000000", FROM.plusDays(1));
        plannedWithdrawal("소모품", "100000", TO.plusDays(2));
        Long payroll = plannedWithdrawal("급여", "3000000", TO.plusDays(5));

        Report report = service.generate(businessId, ReportType.WEEKLY, FROM, TO).orElseThrow();
        Map<String, Object> metric = labelled(metricsOf(report.getId()), "다음 대형 지출");

        assertThat(metric.get("label").toString()).contains("급여");
        assertValue(metric, "3000000");
        assertThat(((Map<?, ?>) metric.get("sourceRef")).get("plannedTransactionId").toString())
                .isEqualTo(payroll.toString());
    }

    /**
     * <b>없는 지표는 0 으로 채우지 않는다.</b> 「다음 지출 0원」은 말이 안 되고, 0 을 넣으면
     * LLM 이 그것을 그대로 문장에 쓴다.
     */
    @Test
    void omitsMetricsThatHaveNoBasis() {
        tx("CASH_SALES", "DEPOSIT", "2000000", FROM.plusDays(1));

        Report report = service.generate(businessId, ReportType.WEEKLY, FROM, TO).orElseThrow();

        assertThat(metricsOf(report.getId()))
                .noneSatisfy(metric -> assertThat(metric.get("label").toString())
                        .startsWith("다음 대형 지출"));
    }

    // --- 저장 ----------------------------------------------------------------

    /** 거래도 예측도 없는 새 사업장에 빈 브리핑을 남기면 목록이 빈 카드로 찬다. */
    @Test
    void doesNotCreateABriefingWithNoMetricsAtAll() {
        assertThat(service.generate(businessId, ReportType.WEEKLY, FROM, TO)).isEmpty();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM core.report WHERE business_id = ?",
                Integer.class, businessId)).isZero();
    }

    /**
     * <b>같은 기간을 다시 돌리면 갈아 끼운다.</b> 배치가 재시도돼도 두 벌이 생기지 않는다
     * ({@code uk_report_period}).
     */
    @Test
    void replacesTheEarlierRunForTheSamePeriod() {
        tx("CASH_SALES", "DEPOSIT", "1000000", FROM.plusDays(1));
        Long first = service.generate(businessId, ReportType.WEEKLY, FROM, TO).orElseThrow().getId();

        tx("CASH_SALES", "DEPOSIT", "500000", FROM.plusDays(2));
        Long second = service.generate(businessId, ReportType.WEEKLY, FROM, TO).orElseThrow().getId();

        assertThat(second).isEqualTo(first);
        assertValue(labelled(metricsOf(second), "기간 입금 합계"), "1500000");
    }

    /**
     * <b>다시 돌릴 때 옛 문장을 지운다.</b> 지난 숫자로 쓴 문장이 새 숫자 옆에 남으면 둘이
     * 어긋난 채로 읽힌다 — 브리핑에서 그건 거짓말이다.
     */
    @Test
    void dropsTheOldNarrativeWhenMetricsChange() {
        tx("CASH_SALES", "DEPOSIT", "1000000", FROM.plusDays(1));
        Long id = service.generate(businessId, ReportType.WEEKLY, FROM, TO).orElseThrow().getId();
        jdbc.update("""
                UPDATE core.report SET headline = '지난 문장', narrative = '지난 본문',
                                       status = 'COMPLETED'
                 WHERE id = ?
                """, id);

        tx("CASH_SALES", "DEPOSIT", "500000", FROM.plusDays(2));
        service.generate(businessId, ReportType.WEEKLY, FROM, TO);

        detailStatusIsPendingWithoutText(id);
    }

    private void detailStatusIsPendingWithoutText(Long id) {
        Map<String, Object> row = jdbc.queryForMap(
                "SELECT headline, narrative, status FROM core.report WHERE id = ?", id);
        assertThat(row.get("headline")).isNull();
        assertThat(row.get("narrative")).isNull();
        assertThat(row.get("status")).isEqualTo("PENDING");
    }

    // --- 조회 ----------------------------------------------------------------

    /**
     * <b>문장이 없으면 키가 없다.</b> 전역 {@code non_null} 정책이라 빈 문자열이 아니라 키가
     * 통째로 빠진다 — 화면은 그때 지표만 그린다(구현현황 3.3).
     */
    @Test
    void leavesOutHeadlineAndContentUntilTheSentenceArrives() throws Exception {
        tx("CASH_SALES", "DEPOSIT", "2000000", FROM.plusDays(1));
        Long id = service.generate(businessId, ReportType.WEEKLY, FROM, TO).orElseThrow().getId();

        detail(id).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("PENDING"))
                .andExpect(jsonPath("$.data.metrics").isArray())
                .andExpect(jsonPath("$.data.headline").doesNotExist())
                .andExpect(jsonPath("$.data.content").doesNotExist());
    }

    @Test
    void listsBriefingsNewestFirstAndFiltersByPeriodType() throws Exception {
        // 기간마다 거래가 있어야 브리핑이 생긴다 — 지표가 0건이면 만들지 않는 것이 규칙이다.
        tx("CASH_SALES", "DEPOSIT", "2000000", FROM.plusDays(1));
        tx("CASH_SALES", "DEPOSIT", "1000000", FROM.minusDays(5));
        tx("CASH_SALES", "DEPOSIT", "3000000", LocalDate.of(2026, 8, 10));
        service.generate(businessId, ReportType.WEEKLY, FROM, TO);
        service.generate(businessId, ReportType.WEEKLY, FROM.minusDays(7), TO.minusDays(7));
        service.generate(businessId, ReportType.MONTHLY,
                LocalDate.of(2026, 8, 1), LocalDate.of(2026, 8, 31));

        mockMvc.perform(get("/api/v1/businesses/" + businessId + "/briefings")
                        .header("Authorization", "Bearer " + token))
                .andExpect(jsonPath("$.data.items.length()").value(3))
                .andExpect(jsonPath("$.data.items[0].periodStart").value(FROM.toString()));

        mockMvc.perform(get("/api/v1/businesses/" + businessId + "/briefings?periodType=MONTHLY")
                        .header("Authorization", "Bearer " + token))
                .andExpect(jsonPath("$.data.items.length()").value(1))
                .andExpect(jsonPath("$.data.items[0].periodType").value("MONTHLY"));
    }

    @Test
    void refusesAnotherBusiness() throws Exception {
        tx("CASH_SALES", "DEPOSIT", "2000000", FROM.plusDays(1));
        Long id = service.generate(businessId, ReportType.WEEKLY, FROM, TO).orElseThrow().getId();
        String otherEmail = "other-" + System.nanoTime() + "@test.local";
        Long otherUser = jdbc.queryForObject("""
                INSERT INTO core.users (email, password_hash, name) VALUES (?, 'x', '남') RETURNING id
                """, Long.class, otherEmail);

        mockMvc.perform(get("/api/v1/briefings/" + id)
                        .header("Authorization", "Bearer "
                                + tokenProvider.createAccessToken(otherUser, otherEmail)))
                .andExpect(status().isForbidden());
    }

    @Test
    void refusesAnUnknownPeriodType() throws Exception {
        mockMvc.perform(get("/api/v1/businesses/" + businessId + "/briefings?periodType=DAILY")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isBadRequest());
    }
}
