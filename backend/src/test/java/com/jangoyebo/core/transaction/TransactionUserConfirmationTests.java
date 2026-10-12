package com.jangoyebo.core.transaction;

import com.jangoyebo.NeedsDocker;
import com.jangoyebo.common.domain.FlowType;
import com.jangoyebo.common.security.JwtTokenProvider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 사용자 확정과 그 이력 (API 8.3 → {@code TRANSACTION_CLASSIFICATION_HISTORY}).
 *
 * <p><b>이 이력이 few-shot 의 유일한 원천이다.</b> 남지 않으면
 * {@link FewShotExampleProvider} 가 영원히 빈 목록을 내고 프롬프트에 예시가 한 건도 붙지
 * 않는다 — 조용히 망가지는 종류라 화면에서는 드러나지 않는다. 그래서 마지막 테스트가
 * <b>PATCH 한 번이 예시까지 이어지는지</b>를 끝까지 따라간다.
 *
 * <p><b>Docker가 필요하다.</b>
 */
@NeedsDocker
@SpringBootTest
@AutoConfigureMockMvc
class TransactionUserConfirmationTests {

    @Autowired private MockMvc mockMvc;
    @Autowired private JwtTokenProvider tokenProvider;
    @Autowired private FewShotExampleProvider fewShotExampleProvider;
    @Autowired private JdbcTemplate jdbc;

    private Long userId;
    private Long businessId;
    private Long accountId;
    private String token;

    @BeforeEach
    void setUp() {
        String unique = String.valueOf(System.nanoTime());
        String email = "confirm-" + unique + "@test.local";
        userId = jdbc.queryForObject("""
                INSERT INTO core.users (email, password_hash, name)
                VALUES (?, 'x', '확정') RETURNING id
                """, Long.class, email);
        token = tokenProvider.createAccessToken(userId, email);
        businessId = jdbc.queryForObject("""
                INSERT INTO core.business (owner_user_id, name) VALUES (?, '확정 사업장') RETURNING id
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

    private Long transaction(String description, String status, String category) {
        return jdbc.queryForObject("""
                INSERT INTO core.transaction
                    (business_id, account_id, category_id, external_transaction_id, source_type,
                     occurred_at, transaction_date, flow_type, amount, description,
                     classification_status, needs_confirmation)
                VALUES (?, ?, ?, ?, 'FIN_API', now(), DATE '2026-08-25', 'WITHDRAWAL', 1200000,
                        ?, ?, FALSE) RETURNING id
                """, Long.class, businessId, accountId,
                category == null ? null : categoryId(category),
                "ext-" + System.nanoTime(), description, status);
    }

    private void patchTransaction(Long transactionId, String body) throws Exception {
        mockMvc.perform(patch("/api/v1/transactions/" + transactionId)
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk());
    }

    private org.springframework.test.web.servlet.ResultActions confirm(Long transactionId)
            throws Exception {
        return mockMvc.perform(
                post("/api/v1/transactions/" + transactionId + "/confirm-classification")
                        .header("Authorization", "Bearer " + token));
    }

    private List<Map<String, Object>> historyOf(Long transactionId) {
        return jdbc.queryForList("""
                SELECT classifier_type, previous_status, new_status, previous_category_id,
                       new_category_id, changed_by, confidence
                  FROM core.transaction_classification_history
                 WHERE transaction_id = ? ORDER BY id
                """, transactionId);
    }

    /** 기준선 — 미분류 거래에 사용자가 분류를 달면 확정되고 이력이 남는다. */
    @Test
    void recordsHistoryWhenUserSetsCategory() throws Exception {
        Long transactionId = transaction("김영자", "UNCLASSIFIED", null);

        patchTransaction(transactionId, "{\"category\": \"RENT\"}");

        assertThat(jdbc.queryForMap(
                "SELECT classification_status, category_id FROM core.transaction WHERE id = ?",
                transactionId))
                .containsEntry("classification_status", "CONFIRMED")
                .containsEntry("category_id", categoryId("RENT"));
        assertThat(historyOf(transactionId)).singleElement().satisfies(history -> {
            assertThat(history.get("classifier_type")).isEqualTo("USER");
            assertThat(history.get("previous_status")).isEqualTo("UNCLASSIFIED");
            assertThat(history.get("new_status")).isEqualTo("CONFIRMED");
            assertThat(history.get("previous_category_id")).isNull();
            assertThat(history.get("new_category_id")).isEqualTo(categoryId("RENT"));
            assertThat(history.get("changed_by")).isEqualTo(userId);
            // 사용자 확정에 신뢰도는 없다. 0 으로 두면 "확신도 0%로 확정"과 구분되지 않는다.
            assertThat(history.get("confidence")).isNull();
        });
    }

    /** LLM 이 잘못 분류한 것을 사용자가 고치면 그 정정이 남는다 — 정확도 추적의 재료다. */
    @Test
    void recordsHistoryWhenUserCorrectsTheSuggestion() throws Exception {
        Long transactionId = transaction("김영자", "SUGGESTED", "MATERIALS");

        patchTransaction(transactionId, "{\"category\": \"RENT\"}");

        assertThat(historyOf(transactionId)).singleElement().satisfies(history -> {
            assertThat(history.get("previous_status")).isEqualTo("SUGGESTED");
            assertThat(history.get("previous_category_id")).isEqualTo(categoryId("MATERIALS"));
            assertThat(history.get("new_category_id")).isEqualTo(categoryId("RENT"));
        });
    }

    /**
     * <b>제안을 그대로 수락한 것도 남긴다.</b>
     *
     * <p>분류는 그대로지만 상태가 {@code SUGGESTED} 에서 {@code CONFIRMED} 로 간다. 사람이
     * 옳다고 한 짝이라 예시로서의 가치가 제안과 다르다 — 이것을 빠뜨리면 few-shot 은
     * "사용자가 고친 것"만 배우고 "맞게 분류한 것"은 못 배운다.
     */
    @Test
    void recordsHistoryWhenUserAcceptsTheSuggestion() throws Exception {
        Long transactionId = transaction("김영자", "SUGGESTED", "RENT");

        patchTransaction(transactionId, "{\"category\": \"RENT\"}");

        assertThat(historyOf(transactionId)).singleElement().satisfies(history -> {
            assertThat(history.get("previous_status")).isEqualTo("SUGGESTED");
            assertThat(history.get("new_status")).isEqualTo("CONFIRMED");
        });
    }

    /** 메모만 고친 PATCH 는 분류를 건드리지 않는다. 이력도 남기지 않는다. */
    @Test
    void doesNotRecordHistoryWhenOnlyMemoChanges() throws Exception {
        Long transactionId = transaction("김영자", "SUGGESTED", "RENT");

        patchTransaction(transactionId, "{\"memo\": \"8월 임차료\"}");

        assertThat(historyOf(transactionId)).isEmpty();
        assertThat(jdbc.queryForObject(
                "SELECT classification_status FROM core.transaction WHERE id = ?",
                String.class, transactionId))
                .as("메모만 고쳤으므로 제안 상태 그대로다")
                .isEqualTo("SUGGESTED");
    }

    /**
     * 이미 확정된 거래를 같은 분류로 다시 보내는 것은 정정이 아니다.
     *
     * <p>남기면 같은 짝이 이력에 쌓여 few-shot 예시가 한 거래처로 도배된다 — 화면에서 저장을
     * 두 번 누르는 것만으로 그렇게 된다.
     */
    @Test
    void doesNotRecordHistoryWhenNothingChanges() throws Exception {
        Long transactionId = transaction("김영자", "CONFIRMED", "RENT");

        patchTransaction(transactionId, "{\"category\": \"RENT\"}");

        assertThat(historyOf(transactionId)).isEmpty();
    }

    // --- 8.4 제안 수락 --------------------------------------------------------

    /** 기준선 — 「맞아요」 한 번에 제안이 확정된다. */
    @Test
    void acceptsTheSuggestion() throws Exception {
        Long transactionId = transaction("김영자", "SUGGESTED", "RENT");

        confirm(transactionId)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.classification.status").value("CONFIRMED"))
                .andExpect(jsonPath("$.data.classification.source").value("USER"))
                .andExpect(jsonPath("$.data.category").value("RENT"));

        assertThat(historyOf(transactionId)).singleElement()
                .satisfies(history -> assertThat(history.get("classifier_type")).isEqualTo("USER"));
    }

    /**
     * <b>수락은 분류를 바꾼 것이 아니라 근거가 그대로 유효하다.</b>
     *
     * <p>사장님이 나중에 "이건 왜 임차료였지" 를 볼 자료가 그 문장뿐이다. 정정(8.3)에서 지우는
     * 것과 갈리는 지점이다 — 거기서는 모델이 고른 분류 자체가 틀렸다.
     */
    @Test
    void keepsTheModelReasonWhenAccepted() throws Exception {
        Long transactionId = transaction("김영자", "SUGGESTED", "RENT");
        jdbc.update("""
                UPDATE core.transaction
                   SET classification_source = 'LLM',
                       classification_reason = '매월 25일 동일 금액 반복 출금'
                 WHERE id = ?
                """, transactionId);

        confirm(transactionId).andExpect(status().isOk());

        assertThat(jdbc.queryForMap(
                "SELECT classification_source, classification_reason FROM core.transaction WHERE id = ?",
                transactionId))
                .containsEntry("classification_source", "USER")
                .containsEntry("classification_reason", "매월 25일 동일 금액 반복 출금");
    }

    /**
     * 제안 상태가 아니면 409 다.
     *
     * <p>사용자가 보고 있던 화면이 낡은 것이다. 조용히 성공으로 처리하면 그 사이 누가 바꿔 놓은
     * 분류를 확정한 것이 되고, 화면은 무엇이 확정됐는지 모른 채 성공만 받는다.
     */
    @Test
    void rejectsWhenNotSuggested() throws Exception {
        confirm(transaction("김영자", "CONFIRMED", "RENT"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("CLASSIFICATION_STATE_CONFLICT"));

        confirm(transaction("우아한형제들", "UNCLASSIFIED", null))
                .andExpect(status().isConflict());
    }

    /**
     * <b>PATCH 한 번이 다음 분류의 예시까지 이어진다.</b>
     *
     * <p>①의 목적이 이것이다 — 이력을 남기는 것 자체가 아니라, 그 이력이 프롬프트에 실려
     * LLM 이 같은 거래처를 다시 틀리지 않게 하는 것이다. 중간 어느 고리가 끊겨도 화면에는
     * 아무 증상이 없으므로 끝까지 따라가 확인한다.
     */
    @Test
    void feedsTheNextPromptAsAFewShotExample() throws Exception {
        Long transactionId = transaction("우아한형제들", "UNCLASSIFIED", null);

        patchTransaction(transactionId, "{\"category\": \"PLATFORM_SETTLEMENT\"}");

        assertThat(fewShotExampleProvider.forBusiness(businessId))
                .containsExactly(new FewShotExample(
                        "우아한형제들", FlowType.WITHDRAWAL, "PLATFORM_SETTLEMENT"));
    }

    /** 응답도 확정 상태로 나간다 — 화면이 저장 직후 다시 조회하지 않아도 되게. */
    @Test
    void returnsTheConfirmedTransaction() throws Exception {
        Long transactionId = transaction("김영자", "UNCLASSIFIED", null);

        mockMvc.perform(patch("/api/v1/transactions/" + transactionId)
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"category\": \"RENT\", \"memo\": \"8월 임차료\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.category").value("RENT"))
                .andExpect(jsonPath("$.data.classification.status").value("CONFIRMED"))
                .andExpect(jsonPath("$.data.classification.source").value("USER"));
    }

    /**
     * 사용자가 분류를 바꾸면 <b>모델이 준 근거를 지운다.</b>
     *
     * <p>남겨 두면 임차료로 고친 거래에 "배민 정산 계좌명입니다" 가 붙어 있게 된다 — 화면은
     * 그 문장을 지금 분류의 근거로 보여준다.
     */
    @Test
    void clearsTheModelReasonWhenUserOverrides() throws Exception {
        Long transactionId = transaction("우아한형제들", "SUGGESTED", "PLATFORM_SETTLEMENT");
        jdbc.update("""
                UPDATE core.transaction
                   SET classification_source = 'LLM',
                       classification_reason = '적요는 배달의민족 정산 계좌명입니다'
                 WHERE id = ?
                """, transactionId);

        patchTransaction(transactionId, "{\"category\": \"RENT\"}");

        assertThat(jdbc.queryForMap(
                "SELECT classification_source, classification_reason FROM core.transaction WHERE id = ?",
                transactionId))
                .containsEntry("classification_source", "USER")
                .containsEntry("classification_reason", null);
    }
}
