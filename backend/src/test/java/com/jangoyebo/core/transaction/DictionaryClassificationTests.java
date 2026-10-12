package com.jangoyebo.core.transaction;

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

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 거래처 사전으로 먼저 분류하기 (명세 8.5 — "규칙 → 거래처 사전 순으로 먼저 처리하고,
 * 남은 거래만 배치로 묶어 AI 워커에 전달한다").
 *
 * <p><b>도메인 규칙 3 이 지켜지는지를 보는 테스트다.</b> "확정된 거래처↔분류 매핑은 DB에
 * 캐시해서 재사용한다. LLM 호출은 처음 보는 패턴에만."
 *
 * <p><b>Docker가 필요하다.</b>
 */
@NeedsDocker
@SpringBootTest
@AutoConfigureMockMvc
class DictionaryClassificationTests {

    @Autowired private MockMvc mockMvc;
    @Autowired private JwtTokenProvider tokenProvider;
    @Autowired private DictionaryClassificationService service;
    @Autowired private TransactionClassificationRequestFactory requestFactory;
    @Autowired private JdbcTemplate jdbc;

    private Long businessId;
    private Long accountId;
    private String token;

    @BeforeEach
    void setUp() {
        String unique = String.valueOf(System.nanoTime());
        String email = "dict-" + unique + "@test.local";
        Long userId = jdbc.queryForObject("""
                INSERT INTO core.users (email, password_hash, name)
                VALUES (?, 'x', '사전분류') RETURNING id
                """, Long.class, email);
        token = tokenProvider.createAccessToken(userId, email);
        businessId = jdbc.queryForObject("""
                INSERT INTO core.business (owner_user_id, name, industry_code)
                VALUES (?, '사전분류 사업장', 'FOOD_CAFE') RETURNING id
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

    private Long unclassified(String description) {
        return unclassified(description, "WITHDRAWAL");
    }

    private Long unclassified(String description, String flowType) {
        return jdbc.queryForObject("""
                INSERT INTO core.transaction
                    (business_id, account_id, external_transaction_id, source_type, occurred_at,
                     transaction_date, flow_type, amount, description,
                     classification_status, needs_confirmation)
                VALUES (?, ?, ?, 'FIN_API', now(), DATE '2026-08-25', ?, 1200000,
                        ?, 'UNCLASSIFIED', FALSE) RETURNING id
                """, Long.class, businessId, accountId, "ext-" + System.nanoTime(),
                flowType, description);
    }

    /** 사전에 한 줄 심는다. 사용자 확정을 거치지 않는 지름길이다. */
    private void dictionaryEntry(String canonicalName, String category, boolean active) {
        dictionaryEntry(canonicalName, category, active, "WITHDRAWAL");
    }

    private void dictionaryEntry(String canonicalName, String category, boolean active,
                                 String flowType) {
        dictionaryEntry(canonicalName, category, active, flowType, 3);
    }

    /** {@code confirmedCount = 0} 이면 모델이 담아 둔 항목이다(사람이 본 적 없음). */
    private void dictionaryEntry(String canonicalName, String category, boolean active,
                                 String flowType, int confirmedCount) {
        jdbc.update("""
                INSERT INTO core.counterparty
                    (business_id, canonical_name, display_name, default_category_id,
                     default_flow_type, confirmed_count, active)
                VALUES (?, ?, ?, ?, ?, ?, ?)
                """, businessId, canonicalName, canonicalName,
                category == null ? null : categoryId(category), flowType, confirmedCount, active);
    }

    private Map<String, Object> transactionRow(Long transactionId) {
        return jdbc.queryForMap("SELECT * FROM core.transaction WHERE id = ?", transactionId);
    }

    /**
     * 기준선 — 사전에 있는 적요는 LLM 없이 분류되고 <b>확인을 묻지 않으며 확정으로 간다</b>.
     *
     * <p>사장님이 이미 확정한 짝이라 같은 것을 또 묻는 것이 이 기능이 없애려는 일이다. 상태도
     * 확정이어야 앞뒤가 맞는다 — 모델의 0.85 추측이 확정인데 사람이 직접 확정한 적요가 제안에
     * 머물면 약한 근거가 더 높은 상태를 받는다. 사람이 한 확정과는 {@code classification_source}
     * 가 {@code DICTIONARY} 로 남아 갈린다.
     */
    @Test
    void classifiesWhatTheDictionaryKnows() {
        Long transactionId = unclassified("김영자");
        dictionaryEntry("김영자", "RENT", true);

        assertThat(service.classify(businessId)).isEqualTo(1);

        Map<String, Object> transaction = transactionRow(transactionId);
        assertThat(transaction.get("category_id")).isEqualTo(categoryId("RENT"));
        assertThat(transaction.get("classification_status")).isEqualTo("CONFIRMED");
        assertThat(transaction.get("classification_source")).isEqualTo("DICTIONARY");
        assertThat(transaction.get("needs_confirmation")).isEqualTo(false);
        // 사전은 모델이 아니다. 확률을 붙이면 없는 측정치를 지어내는 것이 된다.
        assertThat(transaction.get("classification_confidence")).isNull();
        assertThat((String) transaction.get("classification_reason")).contains("3회");
    }

    /** 이력에도 남는다 — 이 분류가 어디서 왔는지 나중에 가릴 수 있어야 한다. */
    @Test
    void recordsDictionaryHistory() {
        Long transactionId = unclassified("김영자");
        dictionaryEntry("김영자", "RENT", true);

        service.classify(businessId);

        assertThat(jdbc.queryForList("""
                SELECT classifier_type, new_status, changed_by, confidence
                  FROM core.transaction_classification_history WHERE transaction_id = ?
                """, transactionId)).singleElement().satisfies(history -> {
            assertThat(history.get("classifier_type")).isEqualTo("DICTIONARY");
            assertThat(history.get("new_status")).isEqualTo("CONFIRMED");
            // 시스템이 한 일이고 사전은 모델이 아니다.
            assertThat(history.get("changed_by")).isNull();
            assertThat(history.get("confidence")).isNull();
        });
    }

    /** 처음 보는 적요는 남겨 둔다. 그것이 LLM 이 할 일이다. */
    @Test
    void leavesUnknownDescriptionsForTheModel() {
        Long known = unclassified("김영자");
        Long unknown = unclassified("처음 보는 적요");
        dictionaryEntry("김영자", "RENT", true);

        assertThat(service.classify(businessId)).isEqualTo(1);

        assertThat(transactionRow(known).get("classification_status")).isEqualTo("CONFIRMED");
        assertThat(transactionRow(unknown).get("classification_status")).isEqualTo("UNCLASSIFIED");
    }

    /**
     * <b>사전이 처리한 거래는 워커 요청에 담기지 않는다.</b>
     *
     * <p>걸러내는 것이 목적이지 두 번 분류하는 것이 아니다 — 담기면 값을 아끼지 못한다.
     */
    @Test
    void removesMatchedTransactionsFromTheWorkerRequest() {
        unclassified("김영자");
        Long unknown = unclassified("처음 보는 적요");
        dictionaryEntry("김영자", "RENT", true);

        service.classify(businessId);

        assertThat(requestFactory.create(9012L, businessId).orElseThrow().transactions())
                .extracting(r -> r.transactionId())
                .containsExactly(unknown);
    }

    /**
     * <b>반대 방향 거래에는 사전을 적용하지 않는다.</b>
     *
     * <p>배달 플랫폼은 정산금을 입금하고 광고비를 출금하는데 은행 적요는 둘 다 법인명이다.
     * 적요만 맞춰 보면 광고비 출금에 <b>입금 카테고리인 플랫폼 정산금</b>이 붙고,
     * {@code needsConfirmation = false} 라 사장님에게 묻지도 않는다.
     *
     * <p>그 분류는 08장이 <b>매출 4종</b>으로 못박은 것이라, 나중에 매출 집계·신고서·What-if 가
     * 그대로 읽는다.
     */
    @Test
    void doesNotApplyADepositEntryToAWithdrawal() {
        Long withdrawal = unclassified("우아한형제들", "WITHDRAWAL");
        dictionaryEntry("우아한형제들", "PLATFORM_SETTLEMENT", true, "DEPOSIT");

        assertThat(service.classify(businessId)).isZero();

        assertThat(transactionRow(withdrawal).get("classification_status"))
                .as("방향이 다르면 사전이 아니라 LLM 이 볼 일이다")
                .isEqualTo("UNCLASSIFIED");
    }

    /** 방향별로 따로 쌓아 두면 같은 적요라도 각자 맞는 분류가 붙는다. */
    @Test
    void appliesEachDirectionSeparately() {
        Long deposit = unclassified("우아한형제들", "DEPOSIT");
        Long withdrawal = unclassified("우아한형제들", "WITHDRAWAL");
        dictionaryEntry("우아한형제들", "PLATFORM_SETTLEMENT", true, "DEPOSIT");
        dictionaryEntry("우아한형제들", "ADVERTISING", true, "WITHDRAWAL");

        assertThat(service.classify(businessId)).isEqualTo(2);

        assertThat(transactionRow(deposit).get("category_id"))
                .isEqualTo(categoryId("PLATFORM_SETTLEMENT"));
        assertThat(transactionRow(withdrawal).get("category_id"))
                .isEqualTo(categoryId("ADVERTISING"));
    }

    /**
     * <b>모델이 담아 둔 짝도 확정으로 간다 — 다만 근거 문장이 다르다.</b>
     *
     * <p>사전에 담기는 모델 판단은 신뢰도 0.85 이상뿐이라(`rememberSuggestion`) 이미
     * {@code classifyByLlm} 이 확정으로 보낸 것과 같은 등급이다. 여기서 다시 물으면 <b>한 실행
     * 안에서 같은 적요가 배치 경계로 갈린다</b> — 첫 20건은 LLM 이 확정하고 나머지는 사전이
     * 잡아 제안이 된다.
     *
     * <p>대신 <b>근거는 갈라 둔다.</b> 사장님 자신의 지난 결정인지 모델 판단의 재사용인지는
     * 나중에 되짚을 때 의심할 지점이 다르다.
     */
    @Test
    void confirmsButNamesTheModelWhenTheEntryCameFromTheModel() {
        Long transactionId = unclassified("우아한형제들");
        dictionaryEntry("우아한형제들", "PLATFORM_SETTLEMENT", true, "WITHDRAWAL", 0);

        assertThat(service.classify(businessId)).isEqualTo(1);

        Map<String, Object> transaction = transactionRow(transactionId);
        assertThat(transaction.get("classification_status")).isEqualTo("CONFIRMED");
        assertThat(transaction.get("needs_confirmation")).isEqualTo(false);
        assertThat((String) transaction.get("classification_reason")).contains("AI");
    }

    /** 비활성이거나 기본 분류가 없는 항목은 볼 것이 없다. */
    @Test
    void skipsEntriesThatCannotClassify() {
        Long inactive = unclassified("비활성 거래처");
        Long noCategory = unclassified("분류 없는 거래처");
        dictionaryEntry("비활성 거래처", "RENT", false);
        dictionaryEntry("분류 없는 거래처", null, true);

        assertThat(service.classify(businessId)).isZero();

        assertThat(transactionRow(inactive).get("classification_status")).isEqualTo("UNCLASSIFIED");
        assertThat(transactionRow(noCategory).get("classification_status")).isEqualTo("UNCLASSIFIED");
    }

    /**
     * <b>확정 한 번이 다음 거래를 자동으로 분류한다.</b>
     *
     * <p>도메인 규칙 3 이 돌아가는 전 구간이다 — 사장님이 8.3 으로 확정하면 사전에 쌓이고,
     * 다음 달 같은 적요가 들어오면 LLM 없이 분류된다. 사전 축적과 사전 분류가 <b>같은
     * 열쇠(적요)</b>를 쓰는지가 여기서 드러난다.
     */
    @Test
    void oneConfirmationClassifiesTheNextTransaction() throws Exception {
        Long lastMonth = unclassified("우아한형제들");
        mockMvc.perform(patch("/api/v1/transactions/" + lastMonth)
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"category\": \"PLATFORM_SETTLEMENT\"}"))
                .andExpect(status().isOk());

        Long thisMonth = unclassified("우아한형제들");

        assertThat(service.classify(businessId)).isEqualTo(1);
        assertThat(transactionRow(thisMonth).get("category_id"))
                .isEqualTo(categoryId("PLATFORM_SETTLEMENT"));
        assertThat(transactionRow(thisMonth).get("classification_source")).isEqualTo("DICTIONARY");
    }
}
