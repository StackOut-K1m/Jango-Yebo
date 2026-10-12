package com.jangoyebo.core.counterparty;

import com.jangoyebo.NeedsDocker;
import com.jangoyebo.common.security.JwtTokenProvider;
import com.jangoyebo.core.transaction.TransactionClassificationService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 거래처 사전 축적 (API 8.6 · 도메인 규칙 3).
 *
 * <p>규칙 3 은 "확정된 거래처↔분류 매핑은 DB에 캐시해서 재사용한다. LLM 호출은 처음 보는
 * 패턴에만" 이다. 이 단계는 <b>쌓는 쪽</b>만 본다 — 분류 전에 사전을 보는 것은 다음이다.
 *
 * <p>여기서 가장 중요한 것은 <b>무엇이 안 쌓이는가</b> 다. LLM 제안이 사전이 되면 모델의
 * 추측이 다음 거래를 자동 분류하고 그 결과가 또 쌓여, 한 번 틀리면 계속 틀린다.
 *
 * <p><b>Docker가 필요하다.</b>
 */
@NeedsDocker
@SpringBootTest
@AutoConfigureMockMvc
class CounterpartyDictionaryTests {

    @Autowired private MockMvc mockMvc;
    @Autowired private JwtTokenProvider tokenProvider;
    @Autowired private TransactionClassificationService classificationService;
    @Autowired private JdbcTemplate jdbc;

    private Long userId;
    private Long businessId;
    private Long accountId;
    private String token;

    @BeforeEach
    void setUp() {
        String unique = String.valueOf(System.nanoTime());
        String email = "cp-" + unique + "@test.local";
        userId = jdbc.queryForObject("""
                INSERT INTO core.users (email, password_hash, name)
                VALUES (?, 'x', '사전') RETURNING id
                """, Long.class, email);
        token = tokenProvider.createAccessToken(userId, email);
        businessId = business();
        accountId = account(businessId);
    }

    private Long business() {
        return jdbc.queryForObject("""
                INSERT INTO core.business (owner_user_id, name) VALUES (?, '사전 사업장') RETURNING id
                """, Long.class, userId);
    }

    private Long account(Long ownerBusinessId) {
        return jdbc.queryForObject("""
                INSERT INTO core.account
                    (business_id, bank_code, account_number_encrypted, account_number_hash,
                     account_number_masked, name, status)
                VALUES (?, '088', 'enc', ?, '***1234', '주거래', 'CONNECTED') RETURNING id
                """, Long.class, ownerBusinessId, "hash-" + System.nanoTime());
    }

    private Long categoryId(String code) {
        return jdbc.queryForObject(
                "SELECT id FROM core.transaction_category WHERE code = ?", Long.class, code);
    }

    private Long transaction(Long ownerBusinessId, Long ownerAccountId, String description) {
        return transaction(ownerBusinessId, ownerAccountId, description, "WITHDRAWAL");
    }

    private Long transaction(Long ownerBusinessId, Long ownerAccountId, String description,
                             String flowType) {
        return jdbc.queryForObject("""
                INSERT INTO core.transaction
                    (business_id, account_id, external_transaction_id, source_type, occurred_at,
                     transaction_date, flow_type, amount, description,
                     classification_status, needs_confirmation)
                VALUES (?, ?, ?, 'FIN_API', now(), DATE '2026-08-25', ?, 1200000,
                        ?, 'UNCLASSIFIED', FALSE) RETURNING id
                """, Long.class, ownerBusinessId, ownerAccountId,
                "ext-" + System.nanoTime(), flowType, description);
    }

    private void confirm(Long transactionId, String category) throws Exception {
        mockMvc.perform(patch("/api/v1/transactions/" + transactionId)
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"category\": \"" + category + "\"}"))
                .andExpect(status().isOk());
    }

    private List<Map<String, Object>> dictionary(Long ownerBusinessId) {
        return jdbc.queryForList("""
                SELECT canonical_name, default_category_id, default_flow_type,
                       confirmed_count, active, last_used_at
                  FROM core.counterparty WHERE business_id = ? ORDER BY id
                """, ownerBusinessId);
    }

    /** 기준선 — 사용자가 확정하면 (적요 → 분류) 짝이 사전에 남는다. */
    @Test
    void remembersWhatTheUserConfirmed() throws Exception {
        confirm(transaction(businessId, accountId, "김영자"), "RENT");

        assertThat(dictionary(businessId)).singleElement().satisfies(row -> {
            assertThat(row.get("canonical_name")).isEqualTo("김영자");
            assertThat(row.get("default_category_id")).isEqualTo(categoryId("RENT"));
            assertThat(row.get("default_flow_type")).isEqualTo("WITHDRAWAL");
            assertThat(row.get("confirmed_count")).isEqualTo(1);
            assertThat(row.get("active")).isEqualTo(true);
            assertThat(row.get("last_used_at")).isNotNull();
        });
    }

    /** 같은 적요를 또 확정해도 행이 늘지 않는다. 갈리면 어느 분류가 정본인지 알 수 없다. */
    @Test
    void countsRepeatedConfirmationsInOneRow() throws Exception {
        confirm(transaction(businessId, accountId, "김영자"), "RENT");
        confirm(transaction(businessId, accountId, "김영자"), "RENT");

        assertThat(dictionary(businessId)).singleElement()
                .satisfies(row -> assertThat(row.get("confirmed_count")).isEqualTo(2));
    }

    /**
     * <b>마지막 확정이 이긴다.</b>
     *
     * <p>사장님이 분류를 바꿨다면 그것이 지금의 판단이고, 사전은 다음 거래에 그 판단을
     * 적용해야 한다. 확정 횟수는 바뀌어도 줄이지 않는다 — 이 값은 "이 분류가 몇 번 맞았나"가
     * 아니라 "이 적요를 몇 번 만졌나"다.
     */
    @Test
    void lastConfirmationWins() throws Exception {
        confirm(transaction(businessId, accountId, "김영자"), "RENT");
        confirm(transaction(businessId, accountId, "김영자"), "MATERIALS");

        assertThat(dictionary(businessId)).singleElement().satisfies(row -> {
            assertThat(row.get("default_category_id")).isEqualTo(categoryId("MATERIALS"));
            assertThat(row.get("confirmed_count")).isEqualTo(2);
        });
    }

    /**
     * <b>LLM 제안도 사전에 담는다 — 다만 확정 횟수 0 이다.</b>
     *
     * <p>안 담으면 같은 판단을 수백 번 반복한다(실측: 612건에 서로 다른 판단은 61개).
     * 그대로 확정으로 담으면 모델의 추측이 다음 거래를 자동 분류하고 그 결과가 또 쌓여
     * 한 번 틀리면 계속 틀린다. 그래서 담되 <b>사람이 본 적 없음</b>을 0 으로 남긴다.
     *
     * <p><b>거래가 {@code CONFIRMED} 로 가도 이 0 은 그대로다.</b> 신뢰도 0.85 이상은 화면에서
     * 확정으로 보이지만, 그것은 모델이 스스로 확정한 것이라 사전의 "확정 횟수"에 들어가면
     * 안 된다 — 도메인 규칙 3 의 "확정된 매핑"이 사람의 판단을 뜻하지 않게 되고, 규칙이
     * 자기 출력을 근거로 삼는다. 두 값이 갈라져 있는지가 이 테스트의 핵심이다.
     */
    @Test
    void remembersModelSuggestionsAsUnconfirmed() {
        Long transactionId = transaction(businessId, accountId, "우아한형제들");

        classificationService.apply(businessId, callback(transactionId, "PLATFORM_SETTLEMENT"));

        assertThat(jdbc.queryForObject(
                "SELECT classification_status FROM core.transaction WHERE id = ?",
                String.class, transactionId))
                .as("신뢰도가 기준 이상이라 거래 자체는 확정으로 간다")
                .isEqualTo("CONFIRMED");
        assertThat(dictionary(businessId)).singleElement().satisfies(row -> {
            assertThat(row.get("default_category_id")).isEqualTo(categoryId("PLATFORM_SETTLEMENT"));
            assertThat(row.get("confirmed_count"))
                    .as("거래는 확정됐어도 사람이 본 적 없는 매핑이라 0 이다")
                    .isEqualTo(0);
        });
    }

    /**
     * 신뢰도가 기준 미만이면 담지 않는다.
     *
     * <p>모델 스스로 자신 없다고 한 판단을 재사용하면, 아낀 호출보다 잘못 붙는 분류가 비싸다.
     */
    @Test
    void doesNotRememberLowConfidenceSuggestions() {
        Long transactionId = transaction(businessId, accountId, "우아한형제들");

        classificationService.apply(businessId,
                callback(transactionId, "PLATFORM_SETTLEMENT", 0.40));

        assertThat(dictionary(businessId)).isEmpty();
    }

    /**
     * <b>모델이 사람의 확정을 덮지 않는다</b> (요청 4번 — USER 가 LLM 보다 우선).
     *
     * <p>사장님이 고쳐 둔 것을 모델이 되돌리면 고친 것이 무슨 소용인가.
     */
    @Test
    void modelDoesNotOverwriteAUserConfirmation() throws Exception {
        confirm(transaction(businessId, accountId, "우아한형제들"), "ADVERTISING");

        classificationService.apply(businessId,
                callback(transaction(businessId, accountId, "우아한형제들"), "PLATFORM_SETTLEMENT"));

        assertThat(dictionary(businessId)).singleElement().satisfies(row -> {
            assertThat(row.get("default_category_id")).isEqualTo(categoryId("ADVERTISING"));
            assertThat(row.get("confirmed_count")).isEqualTo(1);
        });
    }

    /**
     * <b>같은 적요라도 방향이 다르면 다른 거래처다.</b>
     *
     * <p>은행은 브랜드명이 아니라 법인명을 찍는다. 배달 플랫폼은 <b>정산금을 입금</b>하고
     * <b>광고비를 출금</b>하는데 둘 다 적요가 {@code 우아한형제들} 이다(AI 평가셋
     * {@code ai/evals/rewrite.py} 의 「함정 3」이 이것을 일부러 만든다).
     *
     * <p>한 줄로 합치면 마지막 확정이 이겨서, 다음 출금에 <b>입금 카테고리</b>가 붙는다 —
     * 카테고리는 방향이 고정돼 있다(입금 7종·출금 16종).
     */
    @Test
    void keepsDepositAndWithdrawalApartForTheSameDescription() throws Exception {
        confirm(transaction(businessId, accountId, "우아한형제들", "DEPOSIT"), "PLATFORM_SETTLEMENT");
        confirm(transaction(businessId, accountId, "우아한형제들", "WITHDRAWAL"), "ADVERTISING");

        assertThat(dictionary(businessId)).hasSize(2)
                .anySatisfy(row -> {
                    assertThat(row.get("default_flow_type")).isEqualTo("DEPOSIT");
                    assertThat(row.get("default_category_id")).isEqualTo(categoryId("PLATFORM_SETTLEMENT"));
                })
                .anySatisfy(row -> {
                    assertThat(row.get("default_flow_type")).isEqualTo("WITHDRAWAL");
                    assertThat(row.get("default_category_id")).isEqualTo(categoryId("ADVERTISING"));
                });
    }

    /** 적요가 없으면 열쇠가 없다. 분류는 되지만 사전에는 못 넣는다. */
    @Test
    void ignoresTransactionsWithoutDescription() throws Exception {
        confirm(transaction(businessId, accountId, null), "RENT");

        assertThat(dictionary(businessId)).isEmpty();
    }

    /** 사전은 사업장마다 따로다. 같은 적요라도 가게가 다르면 다른 거래처다. */
    @Test
    void keepsDictionariesPerBusiness() throws Exception {
        Long otherBusinessId = business();
        confirm(transaction(businessId, accountId, "김영자"), "RENT");
        confirm(transaction(otherBusinessId, account(otherBusinessId), "김영자"), "MATERIALS");

        assertThat(dictionary(businessId)).singleElement()
                .satisfies(row -> assertThat(row.get("default_category_id"))
                        .isEqualTo(categoryId("RENT")));
        assertThat(dictionary(otherBusinessId)).singleElement()
                .satisfies(row -> assertThat(row.get("default_category_id"))
                        .isEqualTo(categoryId("MATERIALS")));
    }

    /** 명세 20.3 콜백의 {@code data}. */
    private Map<String, Object> callback(Long transactionId, String category) {
        return callback(transactionId, category, 0.95);
    }

    private Map<String, Object> callback(Long transactionId, String category, double confidence) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("transactionId", transactionId);
        row.put("category", category);
        row.put("confidence", confidence);
        row.put("reason", "적요가 플랫폼 정산 계좌명이다");

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("status", "COMPLETED");
        data.put("modelName", "claude-sonnet-5");
        data.put("pipeline", "LLM");
        data.put("results", List.of(row));
        return data;
    }
}
