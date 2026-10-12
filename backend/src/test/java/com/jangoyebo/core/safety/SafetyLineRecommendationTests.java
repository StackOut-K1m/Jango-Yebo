package com.jangoyebo.core.safety;

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
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 안전선 추천값 (API 12.1 · P1-08).
 *
 * <p><b>0 원을 추천하지 않는 것이 이 기능의 핵심이다.</b> 근거가 없을 때 0 을 내보내면
 * 화면은 「안전선 0 원이면 됩니다」로 읽는다 — 「모른다」와 전혀 다른 말이고, 돈을 세는
 * 화면에서 그 차이는 사고다.
 *
 * <p><b>Docker가 필요하다.</b>
 */
@NeedsDocker
@SpringBootTest
@AutoConfigureMockMvc
class SafetyLineRecommendationTests {

    @Autowired private MockMvc mockMvc;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private JwtTokenProvider tokenProvider;

    private Long businessId;
    private Long accountId;
    private String token;

    @BeforeEach
    void setUp() {
        String unique = String.valueOf(System.nanoTime());
        String email = "safetyrec-" + unique + "@test.local";
        Long userId = jdbc.queryForObject("""
                INSERT INTO core.users (email, password_hash, name) VALUES (?, 'x', '정유선') RETURNING id
                """, Long.class, email);
        token = tokenProvider.createAccessToken(userId, email);
        businessId = jdbc.queryForObject("""
                INSERT INTO core.business (owner_user_id, name) VALUES (?, '유선상회') RETURNING id
                """, Long.class, userId);
        accountId = jdbc.queryForObject("""
                INSERT INTO core.account
                    (business_id, bank_code, account_number_encrypted, account_number_hash,
                     account_number_masked, name, status)
                VALUES (?, '088', 'enc', ?, '***1234', '주거래', 'CONNECTED') RETURNING id
                """, Long.class, businessId, "hash-" + unique);
    }

    private void tx(String category, String amount, LocalDate date) {
        tx(category, amount, date, "CONFIRMED", false);
    }

    private void tx(String category, String amount, LocalDate date,
                    String classificationStatus, boolean excluded) {
        Long categoryId = jdbc.queryForObject(
                "SELECT id FROM core.transaction_category WHERE code = ?", Long.class, category);
        jdbc.update("""
                INSERT INTO core.transaction
                    (business_id, account_id, category_id, source_type, occurred_at,
                     transaction_date, flow_type, amount, description, deduplication_key,
                     classification_status, is_excluded)
                VALUES (?, ?, ?, 'FIN_API', ?, ?, 'WITHDRAWAL', ?, ?, ?, ?, ?)
                """, businessId, accountId, categoryId, Timestamp.from(Instant.now()), date,
                new BigDecimal(amount), category, "dedup-" + System.nanoTime(),
                classificationStatus, excluded);
    }

    private ResultActions recommendations() throws Exception {
        return mockMvc.perform(get("/api/v1/businesses/" + businessId + "/safety-line/recommendations")
                .header("Authorization", "Bearer " + token));
    }

    @Test
    void sumsFixedCostsOverTheLastThirtyDays() throws Exception {
        LocalDate today = LocalDate.now();
        tx("RENT", "1200000", today.minusDays(5));
        tx("PAYROLL", "2000000", today.minusDays(10));
        tx("INSURANCE", "150000", today.minusDays(15));
        tx("SUBSCRIPTION", "50000", today.minusDays(20));

        recommendations()
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.fixedCostBased.amount").value(3400000))
                .andExpect(jsonPath("$.data.fixedCostBased.basis").value(
                        org.hamcrest.Matchers.containsString("고정비")))
                .andExpect(jsonPath("$.data.fixedCostBased.transactionCount").value(4))
                // 필수지출은 급여 + 임차료뿐이다.
                .andExpect(jsonPath("$.data.essentialExpenseBased.amount").value(3200000))
                .andExpect(jsonPath("$.data.referencePeriod").exists());
    }

    /**
     * <b>근거가 없으면 0 이 아니라 키를 뺀다.</b>
     *
     * <p>거래가 하나도 없는 새 사업장에 「안전선 0 원」을 추천하면 그 화면은 거짓말을 한다.
     */
    @Test
    void omitsCandidatesThatHaveNoBasis() throws Exception {
        recommendations()
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.fixedCostBased").doesNotExist())
                .andExpect(jsonPath("$.data.essentialExpenseBased").doesNotExist())
                // 기간은 늘 알려 준다 — 화면이 「최근 30일 기준으로 볼 게 없었다」를 말할 수 있다.
                .andExpect(jsonPath("$.data.referencePeriod").exists());
    }

    /** 고정비는 있는데 급여·임차료가 없으면 그쪽만 빠진다. */
    @Test
    void omitsOnlyTheCandidateThatHasNoBasis() throws Exception {
        tx("INSURANCE", "150000", LocalDate.now().minusDays(3));

        recommendations()
                .andExpect(jsonPath("$.data.fixedCostBased.amount").value(150000))
                .andExpect(jsonPath("$.data.essentialExpenseBased").doesNotExist());
    }

    @Test
    void ignoresTransactionsOlderThanTheWindow() throws Exception {
        tx("RENT", "1200000", LocalDate.now().minusDays(45));

        recommendations().andExpect(jsonPath("$.data.fixedCostBased").doesNotExist());
    }

    /**
     * 제안 상태의 분류가 틀려 있으면 안전선이 부풀고, 사장님은 필요 이상으로 경고를 받는다.
     */
    @Test
    void ignoresClassificationsThatAreNotConfirmed() throws Exception {
        tx("RENT", "1200000", LocalDate.now().minusDays(3), "SUGGESTED", false);

        recommendations().andExpect(jsonPath("$.data.fixedCostBased").doesNotExist());
    }

    /**
     * 곡선에서 뺀 일회성 지출이 바닥선을 올리면 안 된다.
     *
     * <p>신고서 초안이 {@code is_excluded} 를 보지 않는 것과 반대인데, 저쪽은 세무상 경비
     * 판단이고 여기는 <b>앞으로 나갈 돈</b>의 바닥선이라서다.
     */
    @Test
    void ignoresTransactionsExcludedFromTheForecast() throws Exception {
        tx("RENT", "1200000", LocalDate.now().minusDays(3), "CONFIRMED", true);

        recommendations().andExpect(jsonPath("$.data.fixedCostBased").doesNotExist());
    }

    /** 입금은 고정비가 아니다 — 카테고리가 출금 전용이라도 방향을 한 번 더 본다. */
    @Test
    void countsOnlyWithdrawals() throws Exception {
        Long categoryId = jdbc.queryForObject(
                "SELECT id FROM core.transaction_category WHERE code = 'RENT'", Long.class);
        jdbc.update("""
                INSERT INTO core.transaction
                    (business_id, account_id, category_id, source_type, occurred_at,
                     transaction_date, flow_type, amount, description, deduplication_key,
                     classification_status)
                VALUES (?, ?, ?, 'FIN_API', ?, ?, 'DEPOSIT', 1200000, '임차료 환급', ?, 'CONFIRMED')
                """, businessId, accountId, categoryId, Timestamp.from(Instant.now()),
                LocalDate.now().minusDays(3), "dedup-" + System.nanoTime());

        recommendations().andExpect(jsonPath("$.data.fixedCostBased").doesNotExist());
    }

    @Test
    void refusesAnotherBusiness() throws Exception {
        String otherEmail = "other-" + System.nanoTime() + "@test.local";
        Long otherUser = jdbc.queryForObject("""
                INSERT INTO core.users (email, password_hash, name) VALUES (?, 'x', '남') RETURNING id
                """, Long.class, otherEmail);

        mockMvc.perform(get("/api/v1/businesses/" + businessId + "/safety-line/recommendations")
                        .header("Authorization", "Bearer "
                                + tokenProvider.createAccessToken(otherUser, otherEmail)))
                .andExpect(status().isForbidden());
    }
}
