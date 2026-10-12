package com.jangoyebo.core.tax;

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
import org.springframework.test.web.servlet.ResultActions;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 초안 수정·수입금액 대사·상함 판정 (API 14.3).
 *
 * <p><b>수정이 산식을 타고 흘러야 한다.</b> ㉛ 감가상각비를 적었는데 소득금액이 그대로면
 * 한 화면 안에서 숫자가 서로 맞지 않는 신고서가 된다.
 *
 * <p><b>Docker가 필요하다.</b>
 */
@NeedsDocker
@SpringBootTest
@AutoConfigureMockMvc
class TaxDraftPatchTests {

    private static final LocalDate FROM = LocalDate.of(2026, 1, 1);
    private static final LocalDate TO = LocalDate.of(2026, 12, 31);

    @Autowired private MockMvc mockMvc;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private JwtTokenProvider tokenProvider;
    @Autowired private TaxDraftService service;
    @Autowired private TaxDraftAggregator aggregator;

    private Long userId;
    private Long businessId;
    private Long accountId;
    private String token;

    @BeforeEach
    void setUp() {
        String unique = String.valueOf(System.nanoTime());
        String email = "taxpatch-" + unique + "@test.local";
        userId = jdbc.queryForObject("""
                INSERT INTO core.users (email, password_hash, name, phone)
                VALUES (?, 'x', '정유선', '010-1234-5678') RETURNING id
                """, Long.class, email);
        token = tokenProvider.createAccessToken(userId, email);
        businessId = jdbc.queryForObject("""
                INSERT INTO core.business
                    (owner_user_id, name, registration_number_masked, industry_code)
                VALUES (?, '유선상회', '123-**-**789', '552305') RETURNING id
                """, Long.class, userId);
        accountId = jdbc.queryForObject("""
                INSERT INTO core.account
                    (business_id, bank_code, account_number_encrypted, account_number_hash,
                     account_number_masked, name, status)
                VALUES (?, '088', 'enc', ?, '***1234', '주거래', 'CONNECTED') RETURNING id
                """, Long.class, businessId, "hash-" + unique);
    }

    // --- 픽스처 --------------------------------------------------------------

    private Long tx(String category, String flow, String amount) {
        Long categoryId = jdbc.queryForObject(
                "SELECT id FROM core.transaction_category WHERE code = ?", Long.class, category);
        return jdbc.queryForObject("""
                INSERT INTO core.transaction
                    (business_id, account_id, category_id, source_type, occurred_at,
                     transaction_date, flow_type, amount, description, deduplication_key,
                     classification_status, tax_review_status)
                VALUES (?, ?, ?, 'FIN_API', ?, DATE '2026-06-15', ?, ?, ?, ?,
                        'CONFIRMED', 'EVIDENCE_CONFIRMED')
                RETURNING id
                """, Long.class, businessId, accountId, categoryId,
                Timestamp.from(Instant.now()), flow, new BigDecimal(amount), category + " 거래",
                "dedup-" + System.nanoTime());
    }

    private Long generate() {
        TaxFilingDraft draft = service.request(
                businessId, userId, 2026, "SIMPLE_LEDGER", FROM, TO, null);
        aggregator.generate(draft.getId());
        return draft.getId();
    }

    private ResultActions patchDraft(Long draftId, String body) throws Exception {
        return mockMvc.perform(patch("/api/v1/tax-drafts/" + draftId)
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body));
    }

    private ResultActions getDraft(Long draftId) throws Exception {
        return mockMvc.perform(get("/api/v1/tax-drafts/" + draftId)
                .header("Authorization", "Bearer " + token));
    }

    private static String field(String sectionKey, String fieldKey, String leaf) {
        return "$.data.sections[?(@.sectionKey == '%s')].fields[?(@.fieldKey == '%s')].%s"
                .formatted(sectionKey, fieldKey, leaf);
    }

    // --- 수정 ----------------------------------------------------------------

    /**
     * <b>수정이 산식을 타고 흐른다.</b>
     *
     * <p>㉛ 감가상각비 68만원을 적으면 ㊵ 일반관리비등계 · 필요경비 합계 · ⑱ · ⑲ · ㉒ 가
     * 전부 따라 움직여야 한다. 저장된 집계 결과를 그대로 쓰면 이 줄들이 옛 숫자에 멈춘다.
     */
    @Test
    void anOverrideFlowsThroughEveryFormulaBelowIt() throws Exception {
        tx("CASH_SALES", "DEPOSIT", "10000000");
        tx("RENT", "WITHDRAWAL", "1200000");

        patchDraft(generate(), """
                {"overrides": [{"fieldKey": "DEPRECIATION", "value": 680000,
                                "note": "정액법 5년, 세무사 확인"}]}
                """)
                .andExpect(status().isOk())
                .andExpect(jsonPath(field("EXPENSE", "DEPRECIATION", "value")).value(680000))
                .andExpect(jsonPath(field("EXPENSE", "GENERAL_EXPENSE_TOTAL", "value"))
                        .value(1880000))
                .andExpect(jsonPath(field("EXPENSE", "TOTAL_EXPENSE", "value")).value(1880000))
                .andExpect(jsonPath(field("INCOME", "ADJUSTED_EXPENSE", "value")).value(1880000))
                .andExpect(jsonPath(field("INCOME", "NET_INCOME", "value")).value(8120000));
    }

    /** 원래 집계값이 남아야 무엇을 고쳤는지 알 수 있다. */
    @Test
    void keepsTheAggregatedValueBesideTheOverride() throws Exception {
        tx("RENT", "WITHDRAWAL", "1200000");

        patchDraft(generate(), """
                {"overrides": [{"fieldKey": "RENT", "value": 1000000, "note": "1월분 이중계상"}]}
                """)
                .andExpect(status().isOk())
                .andExpect(jsonPath(field("EXPENSE", "RENT", "value")).value(1000000))
                .andExpect(jsonPath(field("EXPENSE", "RENT", "aggregatedValue")).value(1200000))
                .andExpect(jsonPath(field("EXPENSE", "RENT", "overrideNote")).value("1월분 이중계상"));
    }

    /** 한 칸을 고치려고 전체를 다시 보내야 하면 그 사이 다른 수정이 지워진다. */
    @Test
    void mergesPatchesInsteadOfReplacingThem() throws Exception {
        Long draftId = generate();

        patchDraft(draftId, """
                {"overrides": [{"fieldKey": "DEPRECIATION", "value": 500000}]}
                """).andExpect(status().isOk());
        patchDraft(draftId, """
                {"overrides": [{"fieldKey": "VEHICLE", "value": 300000}]}
                """).andExpect(status().isOk());

        getDraft(draftId)
                .andExpect(jsonPath(field("EXPENSE", "DEPRECIATION", "value")).value(500000))
                .andExpect(jsonPath(field("EXPENSE", "VEHICLE", "value")).value(300000));
    }

    @Test
    void refusesSensitiveFields() throws Exception {
        patchDraft(generate(), """
                {"overrides": [{"fieldKey": "BIRTH_DATE", "value": 19880101}]}
                """)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("SENSITIVE_FIELD_NOT_STORABLE"));
    }

    /** ㉒ 소득금액을 손으로 덮으면 ⑭−⑱ 과 맞지 않는 신고서가 된다. */
    @Test
    void refusesCalculatedFields() throws Exception {
        patchDraft(generate(), """
                {"overrides": [{"fieldKey": "NET_INCOME", "value": 1}]}
                """)
                .andExpect(status().isBadRequest());
    }

    /** 오타를 받아 두면 화면은 저장됐다고 하고 그 값은 어느 칸에도 안 나타난다. */
    @Test
    void refusesFieldKeysTheFormDoesNotHave() throws Exception {
        patchDraft(generate(), """
                {"overrides": [{"fieldKey": "NO_SUCH_FIELD", "value": 1000}]}
                """)
                .andExpect(status().isBadRequest());
    }

    @Test
    void marksConfirmedFields() throws Exception {
        tx("RENT", "WITHDRAWAL", "1200000");

        patchDraft(generate(), """
                {"confirmedFieldKeys": ["RENT"]}
                """)
                .andExpect(status().isOk())
                .andExpect(jsonPath(field("EXPENSE", "RENT", "confirmed")).value(true));
    }

    @Test
    void refusesADraftThatBelongsToAnotherBusiness() throws Exception {
        Long draftId = generate();
        String otherEmail = "other-" + System.nanoTime() + "@test.local";
        Long otherUser = jdbc.queryForObject("""
                INSERT INTO core.users (email, password_hash, name) VALUES (?, 'x', '남') RETURNING id
                """, Long.class, otherEmail);

        mockMvc.perform(patch("/api/v1/tax-drafts/" + draftId)
                        .header("Authorization", "Bearer "
                                + tokenProvider.createAccessToken(otherUser, otherEmail))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"confirmedFieldKeys\": [\"RENT\"]}"))
                .andExpect(status().isForbidden());
    }

    // --- 수입금액 대사 ---------------------------------------------------------

    @Test
    void comparesServiceTotalWithTheNtsFigureAndPos() throws Exception {
        tx("CASH_SALES", "DEPOSIT", "97800000");
        jdbc.update("""
                INSERT INTO core.product_sales_period
                    (business_id, period_start, period_end, product_name, product_key,
                     gross_amount, source_type, checksum_status)
                VALUES (?, DATE '2026-06-01', DATE '2026-06-30', '아메리카노', 'americano',
                        98420000, 'MANUAL', 'UNAVAILABLE')
                """, businessId);

        patchDraft(generate(), """
                {"hometaxReference": {"ntsRevenue": 98500000, "filingGuideType": "D"}}
                """)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.revenueCheck.serviceTotal").value(97800000))
                .andExpect(jsonPath("$.data.revenueCheck.ntsRevenue").value(98500000))
                .andExpect(jsonPath("$.data.revenueCheck.productSalesTotal").value(98420000))
                .andExpect(jsonPath("$.data.revenueCheck.difference").value(-700000))
                // 도메인 규칙 1 — 「누락입니다」가 아니라 「…일 수 있습니다」다.
                .andExpect(jsonPath("$.data.revenueCheck.note").value(
                        org.hamcrest.Matchers.containsString("일 수 있습니다")))
                .andExpect(jsonPath("$.data.revenueCheck.note").value(
                        org.hamcrest.Matchers.containsString("반영하지 않습니다")));
    }

    /** 참고할 값이 하나도 없으면 대사 블록 자체를 내보내지 않는다. */
    @Test
    void omitsRevenueCheckWhenThereIsNothingToCompare() throws Exception {
        tx("CASH_SALES", "DEPOSIT", "1000000");

        getDraft(generate())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.revenueCheck").doesNotExist());
    }

    // --- 상함 판정 ------------------------------------------------------------

    @Test
    void staysReadyWhenNothingChanged() throws Exception {
        tx("RENT", "WITHDRAWAL", "1200000");

        getDraft(generate()).andExpect(jsonPath("$.data.status").value("READY"));
    }

    /** 검토 상태(8.7)가 바뀌면 근거가 달라진 것이다. */
    @Test
    void marksStaleWhenATaxReviewChangesAfterGeneration() throws Exception {
        Long rent = tx("RENT", "WITHDRAWAL", "1200000");
        Long draftId = generate();

        jdbc.update("""
                UPDATE core.transaction
                   SET tax_review_status = 'PERSONAL_SUSPECTED', tax_reviewed_at = now() + interval '1 minute'
                 WHERE id = ?
                """, rent);

        getDraft(draftId).andExpect(jsonPath("$.data.status").value("STALE"));
    }

    /**
     * <b>재분류도 금액을 바꾼다.</b>
     *
     * <p>명세 14.3 은 검토 상태만 말하지만, 분류가 바뀌면 그 금액이 다른 항목으로 옮겨 간다 —
     * 초안이 {@code READY} 인 채로 남으면 틀린 숫자를 맞는 것처럼 보여 준다.
     */
    @Test
    void marksStaleWhenAClassificationChangesAfterGeneration() throws Exception {
        Long rent = tx("RENT", "WITHDRAWAL", "1200000");
        Long draftId = generate();

        jdbc.update("""
                INSERT INTO core.transaction_classification_history
                    (transaction_id, classifier_type, previous_status, new_status, created_at)
                VALUES (?, 'USER', 'CONFIRMED', 'CONFIRMED', now() + interval '1 minute')
                """, rent);

        getDraft(draftId).andExpect(jsonPath("$.data.status").value("STALE"));
    }

    /** 한 번 적히면 다음 조회는 검사를 건너뛴다 — 컬럼과 응답이 갈라지지 않는다. */
    @Test
    void persistsTheStaleTransition() throws Exception {
        Long rent = tx("RENT", "WITHDRAWAL", "1200000");
        Long draftId = generate();
        jdbc.update("""
                UPDATE core.transaction SET tax_reviewed_at = now() + interval '1 minute' WHERE id = ?
                """, rent);

        getDraft(draftId).andExpect(jsonPath("$.data.status").value("STALE"));

        String stored = jdbc.queryForObject(
                "SELECT status FROM core.tax_filing_draft WHERE id = ?", String.class, draftId);
        org.assertj.core.api.Assertions.assertThat(stored).isEqualTo("STALE");
    }
}
