package com.jangoyebo.core.business;

import com.jangoyebo.NeedsDocker;
import com.jangoyebo.common.security.JwtTokenProvider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import java.math.BigDecimal;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 사업장 목록·상세의 파생 필드 (API 7.1 {@code accountCount}·{@code currentRiskLevel}).
 *
 * <p><b>둘 다 스텁이었다.</b> 로그인 직후 첫 화면이 읽는 값인데 계좌 수는 리터럴 {@code 0},
 * 위험 등급은 언제나 {@code null} 이었다.
 *
 * <p><b>등급은 안전선 화면과 같은 출처여야 한다.</b> 목록 배지와 상세 화면이 다른 등급을
 * 말하면 사장님은 어느 쪽을 믿어야 할지 모른다.
 *
 * <p><b>Docker가 필요하다.</b>
 */
@NeedsDocker
@SpringBootTest
@AutoConfigureMockMvc
class BusinessSummaryTests {

    @Autowired private MockMvc mockMvc;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private JwtTokenProvider tokenProvider;
    @Autowired private com.jangoyebo.forecast.run.ForecastRunWorker worker;

    private Long userId;
    private Long businessId;
    private String token;

    @BeforeEach
    void setUp() {
        String unique = String.valueOf(System.nanoTime());
        String email = "bizsummary-" + unique + "@test.local";
        userId = jdbc.queryForObject("""
                INSERT INTO core.users (email, password_hash, name) VALUES (?, 'x', '정유선')
                RETURNING id
                """, Long.class, email);
        token = tokenProvider.createAccessToken(userId, email);
        businessId = jdbc.queryForObject("""
                INSERT INTO core.business (owner_user_id, name) VALUES (?, '유선상회') RETURNING id
                """, Long.class, userId);
    }

    // --- 픽스처 --------------------------------------------------------------

    private void account(String status, String balance) {
        jdbc.update("""
                INSERT INTO core.account (business_id, provider, bank_code, account_number_encrypted,
                                          account_number_hash, account_number_masked, name,
                                          status, sync_status, current_balance, balance_as_of)
                VALUES (?, 'FINOPENAPI', '004', 'enc', ?, '004-**-1234', '주계좌',
                        ?, 'SUCCESS', ?, now())
                """, businessId, "hash-" + System.nanoTime(), status, new BigDecimal(balance));
    }

    private void account(String status) {
        account(status, "5000000");
    }

    /**
     * 진짜 예측을 돌린다.
     *
     * <p>{@code forecast_run} 과 곡선을 손으로 넣지 않는다 — 열이 여럿이고 {@code basis} 같은
     * JSONB 까지 있어 픽스처가 스키마를 따라가지 못한다. 워커를 부르면 조회가 읽는 것과 같은
     * 행이 생긴다.
     */
    private void runForecast() {
        worker.execute(businessId);
    }

    private void safetyLine(String amount) {
        jdbc.update("""
                INSERT INTO core.safety_balance_policy (business_id, method, amount, auto_update, updated_at)
                VALUES (?, 'MANUAL', ?, false, now())
                ON CONFLICT (business_id) DO UPDATE SET amount = EXCLUDED.amount
                """, businessId, new BigDecimal(amount));
    }

    private ResultActions list() throws Exception {
        return mockMvc.perform(get("/api/v1/businesses")
                .header("Authorization", "Bearer " + token));
    }

    private ResultActions detail() throws Exception {
        return mockMvc.perform(get("/api/v1/businesses/" + businessId)
                .header("Authorization", "Bearer " + token));
    }

    // --- accountCount --------------------------------------------------------

    @Test
    void countsConnectedAccounts() throws Exception {
        account("CONNECTED");
        account("CONNECTED");

        list().andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items[0].accountCount").value(2));
        detail().andExpect(jsonPath("$.data.accountCount").value(2));
    }

    /**
     * <b>연동을 끊은 계좌는 빼고 센다.</b> 잔액 합계가 {@code CONNECTED} 만 세므로(§3.3),
     * 개수만 그대로 세면 화면이 「계좌 3개」라 하고 곡선은 2개로 돈다.
     */
    @Test
    void leavesOutAccountsThatAreNotConnected() throws Exception {
        account("CONNECTED");
        account("DISCONNECTED");
        account("PENDING_VERIFICATION");

        list().andExpect(jsonPath("$.data.items[0].accountCount").value(1));
    }

    @Test
    void reportsZeroBeforeAnyAccountIsLinked() throws Exception {
        list().andExpect(jsonPath("$.data.items[0].accountCount").value(0));
    }

    // --- currentRiskLevel ----------------------------------------------------

    /**
     * <b>예측 실행이 없으면 키가 없다.</b> 여기서 예측을 돌리지 않는다(공통규약 2.6) — 화면은
     * 그때 위험 배너를 숨긴다.
     */
    @Test
    void omitsTheRiskLevelUntilAForecastHasRun() throws Exception {
        account("CONNECTED");

        list().andExpect(jsonPath("$.data.items[0].currentRiskLevel").doesNotExist());
        detail().andExpect(jsonPath("$.data.currentRiskLevel").doesNotExist());
    }

    @Test
    void fillsTheRiskLevelFromTheStoredCurve() throws Exception {
        account("CONNECTED", "5000000");
        safetyLine("2000000");
        runForecast();

        list().andExpect(jsonPath("$.data.items[0].currentRiskLevel").value("SAFE"));
        detail().andExpect(jsonPath("$.data.currentRiskLevel").value("SAFE"));
    }

    /** 안전선 위가 아니면 등급이 올라간다 — 목록 배지가 실제로 달라져야 한다. */
    @Test
    void raisesTheGradeWhenTheBalanceSitsUnderTheSafetyLine() throws Exception {
        account("CONNECTED", "1000000");
        safetyLine("2000000");
        runForecast();

        list().andExpect(jsonPath("$.data.items[0].currentRiskLevel").value("CAUTION"));
    }

    /** 안전선을 안 정했어도 등급은 나온다 — 0원 기준 판정이 남아 있다. */
    @Test
    void stillGradesWithoutASafetyLine() throws Exception {
        account("CONNECTED", "5000000");
        runForecast();

        list().andExpect(jsonPath("$.data.items[0].currentRiskLevel").exists());
    }
}
