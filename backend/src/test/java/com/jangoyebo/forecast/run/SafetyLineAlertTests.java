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
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 안전선 하회 알림 (P1-08 · API 10.3 {@code SAFETY_LINE}).
 *
 * <p>여기가 지키는 것은 하나다 — <b>안전선을 정하면 0원이 아니라 그 선을 기준으로 경고가
 * 온다.</b> 안전선 설정 자체는 {@code SafetyLineTests} 가, 등급 판정은
 * {@code RiskLevelRuleTests} 가 본다.
 *
 * <p>{@code ForecastApiTests} 와 같은 방식으로 실행 스레드를 기다리지 않고
 * {@code worker.execute()} 를 직접 부른다.
 */
@NeedsDocker
@SpringBootTest
@AutoConfigureMockMvc
class SafetyLineAlertTests {

    @Autowired private MockMvc mockMvc;
    @Autowired private JwtTokenProvider tokenProvider;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private ForecastRunWorker worker;

    private Long businessId;
    private String token;

    @BeforeEach
    void setUp() {
        String email = "safety-alert-" + System.nanoTime() + "@test.local";
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

    /** 이 기능이 있는 이유 그 자체다. 예전에는 0원 아래로 가야만 경고가 왔다. */
    @Test
    void 안전선_아래로_내려가면_경고한다() {
        connectedAccount(new BigDecimal("5000000"));
        plannedWithdrawal(LocalDate.now().plusDays(3), new BigDecimal("3000000"));
        safetyLine(new BigDecimal("3000000"));

        worker.execute(businessId);

        List<Map<String, Object>> alerts = safetyLineAlerts();
        assertThat(alerts).hasSize(1);
        assertThat(alerts.get(0).get("risk_level")).isEqualTo("CAUTION");
        assertThat((String) alerts.get(0).get("dedup_key"))
                .isEqualTo("safety-line:" + LocalDate.now().plusDays(3));
    }

    @Test
    void 안전선을_정하지_않으면_경고하지_않는다() {
        // 비교선이 없다. 0원 아래로 가지 않는 한 알릴 것이 없다.
        connectedAccount(new BigDecimal("5000000"));
        plannedWithdrawal(LocalDate.now().plusDays(3), new BigDecimal("3000000"));

        worker.execute(businessId);

        assertThat(safetyLineAlerts()).isEmpty();
    }

    /** 같은 골짜기에 두 알림을 띄우면 급한 쪽이 묻힌다. */
    @Test
    void 공백이_겹치면_공백만_경고한다() {
        connectedAccount(new BigDecimal("1000000"));
        plannedWithdrawal(LocalDate.now().plusDays(3), new BigDecimal("3000000"));
        safetyLine(new BigDecimal("3000000"));

        worker.execute(businessId);

        assertThat(activeCount("CASH_GAP")).isEqualTo(1);
        assertThat(activeCount("SAFETY_LINE")).isZero();
    }

    @Test
    void 깊게_내려가면_위험_등급이_붙는다() {
        // 2.6 — 안전선의 25% 미만이면 DANGER 다.
        connectedAccount(new BigDecimal("5000000"));
        plannedWithdrawal(LocalDate.now().plusDays(3), new BigDecimal("3000000"));
        safetyLine(new BigDecimal("10000000"));

        worker.execute(businessId);

        assertThat(safetyLineAlerts().get(0).get("risk_level")).isEqualTo("DANGER");
    }

    @Test
    void 하회_알림에_확정_표현을_쓰지_않는다() {
        // 도메인 규칙 1 — 예측은 항상 범위로 말한다.
        connectedAccount(new BigDecimal("5000000"));
        plannedWithdrawal(LocalDate.now().plusDays(3), new BigDecimal("3000000"));
        safetyLine(new BigDecimal("3000000"));

        worker.execute(businessId);

        String message = (String) safetyLineAlerts().get(0).get("message");
        assertThat(message).contains("가능성");
        assertThat(message).doesNotContain("모자랍니다");
        assertThat(message).doesNotContain("부족합니다");
    }

    /**
     * 공통규약 2.6 이 「안전선 변경」을 재계산 트리거에서 뺐다. 그래도 <b>알림은</b> 따라가야
     * 한다 — 등급과 달리 알림은 저장된 행이라 아무도 안 고치면 옛 기준으로 남는다.
     */
    @Test
    void 안전선을_올리면_재계산_없이_경고가_생긴다() throws Exception {
        connectedAccount(new BigDecimal("5000000"));
        plannedWithdrawal(LocalDate.now().plusDays(3), new BigDecimal("3000000"));
        worker.execute(businessId);
        assertThat(activeCount("SAFETY_LINE")).isZero();

        Long runBefore = latestRunId();
        putSafetyLine("3000000");

        assertThat(activeCount("SAFETY_LINE")).isEqualTo(1);
        assertThat(latestRunId()).isEqualTo(runBefore);
    }

    @Test
    void 안전선을_내려_하회가_사라지면_경고가_내려간다() throws Exception {
        connectedAccount(new BigDecimal("5000000"));
        plannedWithdrawal(LocalDate.now().plusDays(3), new BigDecimal("3000000"));
        safetyLine(new BigDecimal("3000000"));
        worker.execute(businessId);
        assertThat(activeCount("SAFETY_LINE")).isEqualTo(1);

        putSafetyLine("1000000");

        assertThat(activeCount("SAFETY_LINE")).isZero();
    }

    @Test
    void 하회가_사라지면_다음_실행에서_경고가_내려간다() {
        connectedAccount(new BigDecimal("5000000"));
        Long plannedId = plannedWithdrawal(LocalDate.now().plusDays(3), new BigDecimal("3000000"));
        safetyLine(new BigDecimal("3000000"));
        worker.execute(businessId);
        assertThat(activeCount("SAFETY_LINE")).isEqualTo(1);

        jdbc.update("UPDATE forecast.planned_transaction SET status = 'CANCELLED' WHERE id = ?", plannedId);
        worker.execute(businessId);

        assertThat(activeCount("SAFETY_LINE")).isZero();
    }

    @Test
    void 같은_하회는_알림을_쌓지_않고_갱신한다() {
        // API 10.3 — 같은 원인이면 dedupKey 가 같고 내용만 덮는다.
        connectedAccount(new BigDecimal("5000000"));
        plannedWithdrawal(LocalDate.now().plusDays(3), new BigDecimal("3000000"));
        safetyLine(new BigDecimal("3000000"));

        worker.execute(businessId);
        worker.execute(businessId);

        assertThat(activeCount("SAFETY_LINE")).isEqualTo(1);
    }

    // --- current 블록 (API 12.1) ----------------------------------------------

    @Test
    void 안전선_응답이_하회일과_원인을_함께_준다() throws Exception {
        // SRS P1-08 인수 조건 — 「안전선 하회일과 원인 표시」.
        connectedAccount(new BigDecimal("5000000"));
        plannedWithdrawal(LocalDate.now().plusDays(3), new BigDecimal("3000000"));
        safetyLine(new BigDecimal("3000000"));
        worker.execute(businessId);

        mockMvc.perform(get(url()).header("Authorization", bearer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.amount").value(3000000))
                .andExpect(jsonPath("$.data.current.currentBalance").value(5000000))
                .andExpect(jsonPath("$.data.current.riskLevel").value("SAFE"))
                .andExpect(jsonPath("$.data.current.next30dLowestBalance").value(2000000))
                .andExpect(jsonPath("$.data.current.shortfallAgainstLine").value(1000000))
                .andExpect(jsonPath("$.data.current.firstBelowLineDate")
                        .value(LocalDate.now().plusDays(3).toString()))
                .andExpect(jsonPath("$.data.current.causes[0].plannedTransactionId").isNumber());
    }

    @Test
    void 하회가_없으면_부족액과_하회일이_빠진다() throws Exception {
        // 0 으로 채우면 「딱 안전선에 붙어 있다」로 읽힌다.
        connectedAccount(new BigDecimal("5000000"));
        safetyLine(new BigDecimal("1000000"));
        worker.execute(businessId);

        mockMvc.perform(get(url()).header("Authorization", bearer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.current.riskLevel").value("SAFE"))
                .andExpect(jsonPath("$.data.current.shortfallAgainstLine").doesNotExist())
                .andExpect(jsonPath("$.data.current.firstBelowLineDate").doesNotExist())
                .andExpect(jsonPath("$.data.current.causes.length()").value(0));
    }

    @Test
    void 예측_실행이_없으면_current_키가_빠진다() throws Exception {
        // 구현현황 §2 non_null — 없는 값은 키 자체가 없다. 조회가 곡선을 만들지도 않는다.
        safetyLine(new BigDecimal("3000000"));

        mockMvc.perform(get(url()).header("Authorization", bearer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.amount").value(3000000))
                .andExpect(jsonPath("$.data.current").doesNotExist());
    }

    @Test
    void 안전선을_정하지_않아도_현재_상태는_준다() throws Exception {
        // 금액을 정하려는 사람에게 「지금 잔고·30일 최저」가 곧 근거다.
        connectedAccount(new BigDecimal("5000000"));
        plannedWithdrawal(LocalDate.now().plusDays(3), new BigDecimal("3000000"));
        worker.execute(businessId);

        mockMvc.perform(get(url()).header("Authorization", bearer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.amount").doesNotExist())
                .andExpect(jsonPath("$.data.current.currentBalance").value(5000000))
                .andExpect(jsonPath("$.data.current.next30dLowestBalance").value(2000000))
                .andExpect(jsonPath("$.data.current.firstBelowLineDate").doesNotExist());
    }

    // --- 헬퍼 -----------------------------------------------------------------

    private String url() {
        return "/api/v1/businesses/" + businessId + "/safety-line";
    }

    private String bearer() {
        return "Bearer " + token;
    }

    private void putSafetyLine(String amount) throws Exception {
        mockMvc.perform(put(url())
                        .header("Authorization", bearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"amount\":" + amount + ",\"method\":\"MANUAL\"}"))
                .andExpect(status().isOk());
    }

    private List<Map<String, Object>> safetyLineAlerts() {
        return jdbc.queryForList("""
                SELECT risk_level, message, dedup_key FROM core.alert
                 WHERE business_id = ? AND alert_type = 'SAFETY_LINE' AND status = 'ACTIVE'
                 ORDER BY id
                """, businessId);
    }

    private int activeCount(String type) {
        Integer count = jdbc.queryForObject("""
                SELECT count(*) FROM core.alert
                 WHERE business_id = ? AND alert_type = ? AND status = 'ACTIVE'
                """, Integer.class, businessId, type);
        return count == null ? 0 : count;
    }

    private Long latestRunId() {
        return jdbc.queryForObject("""
                SELECT max(id) FROM forecast.forecast_run WHERE business_id = ?
                """, Long.class, businessId);
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
