package com.jangoyebo.core.counterparty;

import com.jangoyebo.NeedsDocker;
import com.jangoyebo.common.security.JwtTokenProvider;
import com.jangoyebo.core.transaction.DictionaryClassificationService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 거래처 사전 조회·수정 (API 8.6).
 *
 * <p><b>사전이 스스로 분류하기 때문에 필요한 문이다.</b> 잘못된 줄이 있으면 거래를 하나씩
 * 고쳐도 소용이 없다 — 그 건만 바뀌고 사전은 그대로라 다음 달 같은 적요에 또 붙는다.
 *
 * <p><b>Docker가 필요하다.</b>
 */
@NeedsDocker
@SpringBootTest
@AutoConfigureMockMvc
class MerchantApiTests {

    @Autowired private MockMvc mockMvc;
    @Autowired private JwtTokenProvider tokenProvider;
    @Autowired private DictionaryClassificationService dictionaryClassificationService;
    @Autowired private JdbcTemplate jdbc;

    private Long userId;
    private Long businessId;
    private Long accountId;
    private String token;

    @BeforeEach
    void setUp() {
        String unique = String.valueOf(System.nanoTime());
        String email = "merchant-" + unique + "@test.local";
        userId = jdbc.queryForObject("""
                INSERT INTO core.users (email, password_hash, name)
                VALUES (?, 'x', '사전화면') RETURNING id
                """, Long.class, email);
        token = tokenProvider.createAccessToken(userId, email);
        businessId = jdbc.queryForObject("""
                INSERT INTO core.business (owner_user_id, name) VALUES (?, '사전화면 사업장') RETURNING id
                """, Long.class, userId);
        accountId = jdbc.queryForObject("""
                INSERT INTO core.account
                    (business_id, bank_code, account_number_encrypted, account_number_hash,
                     account_number_masked, name, status)
                VALUES (?, '088', 'enc', ?, '***1234', '주거래', 'CONNECTED') RETURNING id
                """, Long.class, businessId, "hash-" + unique);
    }

    private Long categoryId(String code) {
        return jdbc.queryForObject(
                "SELECT id FROM core.transaction_category WHERE code = ?", Long.class, code);
    }

    private Long entry(Long ownerBusinessId, String canonicalName, String category,
                       String flowType, int confirmedCount) {
        return jdbc.queryForObject("""
                INSERT INTO core.counterparty
                    (business_id, canonical_name, display_name, default_category_id,
                     default_flow_type, confirmed_count, active, last_used_at)
                VALUES (?, ?, ?, ?, ?, ?, TRUE, now()) RETURNING id
                """, Long.class, ownerBusinessId, canonicalName, canonicalName,
                categoryId(category), flowType, confirmedCount);
    }

    private Long entry(String canonicalName, String category) {
        return entry(businessId, canonicalName, category, "DEPOSIT", 3);
    }

    private ResultActions list(String query) throws Exception {
        return mockMvc.perform(get("/api/v1/businesses/" + businessId + "/merchants" + query)
                .header("Authorization", "Bearer " + token));
    }

    private ResultActions rename(Long merchantId, String body) throws Exception {
        return mockMvc.perform(patch("/api/v1/merchants/" + merchantId)
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body));
    }

    /** 기준선 — 사전이 명세 8.6 의 모양으로 나온다. */
    @Test
    void listsTheDictionary() throws Exception {
        entry("우아한형제들", "PLATFORM_SETTLEMENT");

        list("")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items.length()").value(1))
                .andExpect(jsonPath("$.data.items[0].displayName").value("우아한형제들"))
                .andExpect(jsonPath("$.data.items[0].matchPatterns[0]").value("우아한형제들"))
                .andExpect(jsonPath("$.data.items[0].defaultCategory").value("PLATFORM_SETTLEMENT"))
                .andExpect(jsonPath("$.data.items[0].direction").value("DEPOSIT"))
                .andExpect(jsonPath("$.data.items[0].confirmedCount").value(3))
                .andExpect(jsonPath("$.data.totalElements").value(1));
    }

    /**
     * {@code avgDelayDays} 는 내보내지 않는다.
     *
     * <p>ERD 가 예정 거래 매칭에서 집계한다고 정했고 그 경로(P1-05)가 없다. 0 으로 내보내면
     * "지연이 없다" 로 읽힌다 — 키 자체가 없어야 포맷터가 «—» 로 받는다.
     */
    @Test
    void omitsAverageDelayUntilItCanBeMeasured() throws Exception {
        entry("우아한형제들", "PLATFORM_SETTLEMENT");

        list("").andExpect(jsonPath("$.data.items[0].avgDelayDays").doesNotExist());
    }

    /**
     * 검색은 <b>표시 이름과 적요 양쪽</b>을 본다.
     *
     * <p>고친 이름으로만 찾히면 통장에 찍힌 문자열로는 못 찾는다.
     */
    @Test
    void searchesBothTheDisplayNameAndTheDescription() throws Exception {
        Long id = entry("우아한형제들", "PLATFORM_SETTLEMENT");
        rename(id, "{\"displayName\": \"배달의민족\"}").andExpect(status().isOk());
        entry("신한카드", "CARD_SETTLEMENT");

        list("?q=배달").andExpect(jsonPath("$.data.items.length()").value(1))
                .andExpect(jsonPath("$.data.items[0].displayName").value("배달의민족"));
        list("?q=우아한").andExpect(jsonPath("$.data.items.length()").value(1))
                .andExpect(jsonPath("$.data.items[0].displayName").value("배달의민족"));
    }

    /** 검색어를 비운 첫 진입이 500 이 되지 않는다 (8.2 에서 겪은 함정). */
    @Test
    void listsWithoutAKeyword() throws Exception {
        entry("우아한형제들", "PLATFORM_SETTLEMENT");

        list("?q=").andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items.length()").value(1));
    }

    /**
     * <b>이름을 바꿔도 매칭은 그대로다.</b>
     *
     * <p>한 칸이 표시 이름과 매칭 열쇠를 겸하면 이름을 고치는 순간 사전이 안 걸린다 —
     * 적요는 여전히 법인명으로 들어오기 때문이다. V19 이 그 둘을 나눈 이유다.
     */
    @Test
    void renamingDoesNotBreakMatching() throws Exception {
        Long id = entry(businessId, "우아한형제들", "PLATFORM_SETTLEMENT", "WITHDRAWAL", 3);
        rename(id, "{\"displayName\": \"배달의민족\"}").andExpect(status().isOk());

        Long transactionId = jdbc.queryForObject("""
                INSERT INTO core.transaction
                    (business_id, account_id, external_transaction_id, source_type, occurred_at,
                     transaction_date, flow_type, amount, description,
                     classification_status, needs_confirmation)
                VALUES (?, ?, ?, 'FIN_API', now(), DATE '2026-08-25', 'WITHDRAWAL', 1200000,
                        '우아한형제들', 'UNCLASSIFIED', FALSE) RETURNING id
                """, Long.class, businessId, accountId, "ext-" + System.nanoTime());

        assertThat(dictionaryClassificationService.classify(businessId)).isEqualTo(1);
        assertThat(jdbc.queryForMap(
                "SELECT category_id FROM core.transaction WHERE id = ?", transactionId))
                .containsEntry("category_id", categoryId("PLATFORM_SETTLEMENT"));
    }

    /** 분류를 바꾸면 이후 거래부터 적용된다. 이미 붙은 것은 그대로 둔다(명세 8.6). */
    @Test
    void changingTheCategoryAppliesFromNextTime() throws Exception {
        Long id = entry(businessId, "김영자", "MATERIALS", "WITHDRAWAL", 3);

        rename(id, "{\"defaultCategory\": \"RENT\"}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.defaultCategory").value("RENT"));

        assertThat(jdbc.queryForMap(
                "SELECT default_category_id, confirmed_count FROM core.counterparty WHERE id = ?", id))
                .containsEntry("default_category_id", categoryId("RENT"))
                .as("사람이 고친 것이므로 확정으로 센다")
                .containsEntry("confirmed_count", 4);
    }

    /**
     * <b>모델이 담아 둔 줄을 고치면 그 자리에서 확정이 된다.</b>
     *
     * <p>확정 횟수 0 은 "사람이 본 적 없음" 이고, 사장님이 고친 순간 그 뜻이 사라진다 —
     * 다음부터 확인을 묻지 않는다.
     */
    @Test
    void editingAModelEntryPromotesItToConfirmed() throws Exception {
        Long id = entry(businessId, "우아한형제들", "PLATFORM_SETTLEMENT", "WITHDRAWAL", 0);

        rename(id, "{\"defaultCategory\": \"ADVERTISING\"}").andExpect(status().isOk());

        assertThat(jdbc.queryForObject(
                "SELECT confirmed_count FROM core.counterparty WHERE id = ?", Integer.class, id))
                .isEqualTo(1);
    }

    /** 모르는 분류 코드는 400 이다. 조용히 무시하면 화면은 바뀐 줄 알고 사전은 그대로다. */
    @Test
    void rejectsAnUnknownCategory() throws Exception {
        Long id = entry("우아한형제들", "PLATFORM_SETTLEMENT");

        rename(id, "{\"defaultCategory\": \"COFFEE_BEANS\"}")
                .andExpect(status().isBadRequest());
    }

    /** 남의 사업장 사전은 고칠 수 없다. */
    @Test
    void rejectsAnotherUsersMerchant() throws Exception {
        Long otherUserId = jdbc.queryForObject("""
                INSERT INTO core.users (email, password_hash, name)
                VALUES (?, 'x', '남') RETURNING id
                """, Long.class, "other-" + System.nanoTime() + "@test.local");
        Long otherBusinessId = jdbc.queryForObject("""
                INSERT INTO core.business (owner_user_id, name) VALUES (?, '남의 사업장') RETURNING id
                """, Long.class, otherUserId);
        Long id = entry(otherBusinessId, "우아한형제들", "PLATFORM_SETTLEMENT", "DEPOSIT", 3);

        rename(id, "{\"displayName\": \"내 것\"}")
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("BUSINESS_ACCESS_DENIED"));
    }

    /** 사전은 사업장마다 따로다. 목록에 남의 것이 섞이지 않는다. */
    @Test
    void listsOnlyThisBusiness() throws Exception {
        entry("우아한형제들", "PLATFORM_SETTLEMENT");
        Long otherUserId = jdbc.queryForObject("""
                INSERT INTO core.users (email, password_hash, name)
                VALUES (?, 'x', '남') RETURNING id
                """, Long.class, "other2-" + System.nanoTime() + "@test.local");
        Long otherBusinessId = jdbc.queryForObject("""
                INSERT INTO core.business (owner_user_id, name) VALUES (?, '남의 사업장') RETURNING id
                """, Long.class, otherUserId);
        entry(otherBusinessId, "신한카드", "CARD_SETTLEMENT", "DEPOSIT", 3);

        list("").andExpect(jsonPath("$.data.items.length()").value(1))
                .andExpect(jsonPath("$.data.items[0].displayName").value("우아한형제들"));
    }

    /** 없는 항목은 404 다. */
    @Test
    void returnsNotFoundForAnUnknownMerchant() throws Exception {
        rename(999_999_999L, "{\"displayName\": \"없음\"}")
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("MERCHANT_NOT_FOUND"));
    }

    /** 사전이 비어 있어도 정상 빈 목록이다. */
    @Test
    void returnsAnEmptyPageWhenNothingIsRemembered() throws Exception {
        list("").andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items.length()").value(0))
                .andExpect(jsonPath("$.data.totalElements").value(0));
    }

    /** 명세 8.6 의 응답 필드가 빠짐없이 나온다. */
    @Test
    void keepsTheContractShape() throws Exception {
        entry("우아한형제들", "PLATFORM_SETTLEMENT");

        Map<String, Object> item = jdbc.queryForMap(
                "SELECT id FROM core.counterparty WHERE business_id = ?", businessId);
        list("").andExpect(jsonPath("$.data.items[0].merchantId").value(item.get("id")))
                .andExpect(jsonPath("$.data.items[0].lastUsedAt").exists());
    }
}
