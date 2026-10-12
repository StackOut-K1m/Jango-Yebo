package com.jangoyebo.core.loan;

import com.jangoyebo.NeedsDocker;
import com.jangoyebo.common.security.JwtTokenProvider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDate;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 대출 현황 (API 16.1, P1-13).
 */
@NeedsDocker
@SpringBootTest
@AutoConfigureMockMvc
class LoanApiTests {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JwtTokenProvider tokenProvider;

    @Autowired
    private JdbcTemplate jdbc;

    private Long businessId;
    private String token;

    @BeforeEach
    void setUp() {
        String email = "loan-api-" + System.nanoTime() + "@test.local";
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
    void 대출이_없으면_빈_목록이다() throws Exception {
        // 「거래 없음」은 오류가 아니다(공통규약 2.5).
        mockMvc.perform(get(url()).header("Authorization", bearer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items.length()").value(0));
    }

    @Test
    void 금융망_대출은_원금이_비고_총액과_일_상환액이_나온다() throws Exception {
        // LOAN_08 의 loanBalance 는 원금+이자 총액이라 원금을 지어내지 않는다.
        loan(null, "5414247", "16.800", "DAILY", 180, accountId());

        mockMvc.perform(get(url()).header("Authorization", bearer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items.length()").value(1))
                .andExpect(jsonPath("$.data.items[0].principal").doesNotExist())
                .andExpect(jsonPath("$.data.items[0].totalRepayable").value(5414247))
                // 5,414,247 / 180 = 30,079.15 → 내림
                .andExpect(jsonPath("$.data.items[0].dailyPaymentAmount").value(30079))
                .andExpect(jsonPath("$.data.items[0].repaymentCycle").value("DAILY"));
    }

    @Test
    void 금리는_퍼센트가_아니라_비율로_나간다() throws Exception {
        // 명세 16.1 이 5.2% 를 0.052 로 적는다. DB 는 금융망을 따라 퍼센트로 담는다.
        loan(null, "5414247", "16.800", "DAILY", 180, accountId());

        mockMvc.perform(get(url()).header("Authorization", bearer()))
                .andExpect(jsonPath("$.data.items[0].interestRate").value(0.168));
    }

    @Test
    void 매일_갚는_대출의_다음_상환일은_내일이다() throws Exception {
        loan(null, "5414247", "16.800", "DAILY", 180, accountId());

        mockMvc.perform(get(url()).header("Authorization", bearer()))
                .andExpect(jsonPath("$.data.items[0].nextPaymentDate")
                        .value(LocalDate.now().plusDays(1).toString()));
    }

    @Test
    void 출금계좌를_모르면_예보에_반영되지_않는다고_알린다() throws Exception {
        // 금융망 대출은 우리 앱에 없는 계좌에서도 빠진다. 감추지 않고 이유를 말한다.
        loan(null, "5414247", "16.800", "DAILY", 180, null);

        mockMvc.perform(get(url()).header("Authorization", bearer()))
                .andExpect(jsonPath("$.data.items[0].affectsForecast").value(false))
                .andExpect(jsonPath("$.data.items[0].forecastNote").value(
                        org.hamcrest.Matchers.containsString("예보에 반영되지 않습니다")));
    }

    @Test
    void 동기화는_접수만_하고_202_를_준다() throws Exception {
        mockMvc.perform(post(url() + "/sync").header("Authorization", bearer()))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.data.jobType").value("LOAN_SYNC"))
                .andExpect(jsonPath("$.data.jobId").isNumber());
    }

    @Test
    void 남의_사업장_대출은_못_본다() throws Exception {
        mockMvc.perform(get("/api/v1/businesses/999999/loans").header("Authorization", bearer()))
                .andExpect(status().isForbidden());
    }

    // ── 픽스처 ────────────────────────────────────────────────────────────────

    private String url() {
        return "/api/v1/businesses/" + businessId + "/loans";
    }

    private String bearer() {
        return "Bearer " + token;
    }

    private Long accountId() {
        String suffix = String.valueOf(System.nanoTime());
        return jdbc.queryForObject("""
                INSERT INTO core.account (business_id, bank_code, account_number_encrypted,
                                          account_number_hash, account_number_masked, name, status)
                VALUES (?, '999', 'enc', ?, '999****0000', '주계좌', 'CONNECTED') RETURNING id
                """, Long.class, businessId, suffix);
    }

    private void loan(String principal, String totalRepayable, String rate,
                      String cycle, int periodDays, Long accountId) {
        LocalDate startedOn = LocalDate.now();
        jdbc.update("""
                INSERT INTO core.loan (business_id, external_loan_no, lender_name, product_name,
                                       principal_amount, total_repayable, interest_rate,
                                       repayment_cycle, withdrawal_account_id,
                                       started_on, maturity_date, status)
                VALUES (?, ?, '싸피은행', '소상공인 비상자금',
                        CAST(? AS NUMERIC), CAST(? AS NUMERIC), CAST(? AS NUMERIC),
                        ?, ?, ?, ?, 'REPAYING')
                """, businessId, "L" + System.nanoTime(),
                principal, totalRepayable, rate, cycle, accountId,
                startedOn, startedOn.plusDays(periodDays));
    }
}
