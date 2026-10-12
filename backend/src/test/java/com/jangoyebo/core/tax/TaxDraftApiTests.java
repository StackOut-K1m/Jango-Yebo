package com.jangoyebo.core.tax;

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
 * 신고서 초안 조회 (API 14.3).
 *
 * <p><b>저장은 평평하고 응답은 섹션이다.</b> 그 조립이 틀어지면 금액은 맞는데 화면이
 * 엉뚱한 라벨을 붙이거나, 더 나쁘게 <b>한 줄이 소리 없이 사라진다</b>.
 *
 * <p><b>Docker가 필요하다.</b>
 */
@NeedsDocker
@SpringBootTest
@AutoConfigureMockMvc
class TaxDraftApiTests {

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
        String email = "taxread-" + unique + "@test.local";
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

    private Long tx(String category, String flow, String amount, String reviewStatus) {
        return tx(category, flow, amount, reviewStatus, "CONFIRMED");
    }

    private Long tx(String category, String flow, String amount,
                    String reviewStatus, String classificationStatus) {
        Long categoryId = jdbc.queryForObject(
                "SELECT id FROM core.transaction_category WHERE code = ?", Long.class, category);
        return jdbc.queryForObject("""
                INSERT INTO core.transaction
                    (business_id, account_id, category_id, source_type, occurred_at,
                     transaction_date, flow_type, amount, description, deduplication_key,
                     classification_status, tax_review_status)
                VALUES (?, ?, ?, 'FIN_API', ?, DATE '2026-06-15', ?, ?, ?, ?, ?, ?)
                RETURNING id
                """, Long.class, businessId, accountId, categoryId,
                Timestamp.from(Instant.now()), flow, new BigDecimal(amount), category + " 거래",
                "dedup-" + System.nanoTime(), classificationStatus, reviewStatus);
    }

    private Long settlementWithDeposit(String gross, String cancellations, String fees) {
        Long deposit = tx("PLATFORM_SETTLEMENT", "DEPOSIT", "850000", "EVIDENCE_CONFIRMED");
        Long settlementId = jdbc.queryForObject("""
                INSERT INTO core.settlement
                    (business_id, platform_name, sales_from, sales_to,
                     gross_sales, cancellation_amount, fee_amount, status)
                VALUES (?, '배달의민족', DATE '2026-06-01', DATE '2026-06-30', ?, ?, ?, 'MATCHED')
                RETURNING id
                """, Long.class, businessId, new BigDecimal(gross),
                new BigDecimal(cancellations), new BigDecimal(fees));
        jdbc.update("""
                INSERT INTO core.settlement_transaction
                    (settlement_id, transaction_id, allocated_amount)
                VALUES (?, ?, 850000)
                """, settlementId, deposit);
        return deposit;
    }

    private Long generate() {
        TaxFilingDraft draft = service.request(
                businessId, userId, 2026, "SIMPLE_LEDGER", FROM, TO, null);
        aggregator.generate(draft.getId());
        return draft.getId();
    }

    private ResultActions getDraft(Long draftId) throws Exception {
        return mockMvc.perform(get("/api/v1/tax-drafts/" + draftId)
                .header("Authorization", "Bearer " + token));
    }

    private ResultActions getBasis(Long draftId, String fieldKey) throws Exception {
        return mockMvc.perform(get("/api/v1/tax-drafts/" + draftId + "/basis")
                .param("fieldKey", fieldKey)
                .header("Authorization", "Bearer " + token));
    }

    // --- 상세 ----------------------------------------------------------------

    @Test
    void assemblesSectionsInFormOrderWithLabels() throws Exception {
        tx("RENT", "WITHDRAWAL", "1200000", "EVIDENCE_CONFIRMED");

        getDraft(generate())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("READY"))
                .andExpect(jsonPath("$.data.formType").value("SIMPLE_LEDGER"))
                .andExpect(jsonPath("$.data.sections[0].sectionKey").value("IDENTITY"))
                .andExpect(jsonPath("$.data.sections[1].sectionKey").value("BUSINESS"))
                .andExpect(jsonPath("$.data.sections[2].sectionKey").value("REVENUE"))
                .andExpect(jsonPath("$.data.sections[2].fields[0].fieldKey").value("SALES"))
                .andExpect(jsonPath("$.data.sections[2].fields[0].label").value("⑪ 매출액"));
    }

    /** 응답에서도 값은 없다. 저장하지 않으므로 나올 것이 없는 게 정상이다. */
    @Test
    void keepsSensitiveValuesNullInTheResponse() throws Exception {
        getDraft(generate())
                .andExpect(status().isOk())
                // 저장하지 않으므로 값이 없고, non_null 직렬화라 **키 자체가 없다**
                // (구현현황 3.3 — 없는 필드는 키가 없다).
                .andExpect(jsonPath("$.data.sections[0].fields[0].fieldKey").value("HOME_ADDRESS"))
                .andExpect(jsonPath("$.data.sections[0].fields[0].value").doesNotExist())
                .andExpect(jsonPath("$.data.sections[0].fields[0].sensitive").value(true))
                .andExpect(jsonPath("$.data.sections[0].fields[3].fieldKey").value("BIRTH_DATE"))
                .andExpect(jsonPath("$.data.sections[0].fields[3].value").doesNotExist());
    }

    /** 도메인 규칙 4 — 세무 값은 자동 확정하지 않는다. */
    @Test
    void alwaysCarriesTheDisclaimer() throws Exception {
        getDraft(generate())
                .andExpect(jsonPath("$.data.disclaimer").value(TaxDraftQueryService.DISCLAIMER));
    }

    /**
     * 서식이 모르는 저장 항목을 <b>버리지 않는다</b>.
     *
     * <p>일어나선 안 되는 일이지만, 일어나면 신고서에서 금액 한 줄이 소리 없이 사라진다.
     */
    @Test
    void surfacesStoredFieldsTheFormDoesNotKnow() throws Exception {
        Long draftId = generate();
        jdbc.update("""
                UPDATE core.tax_filing_draft
                   SET fields = fields || ?::jsonb
                 WHERE id = ?
                """, "{\"GHOST\": {\"value\": 777000, \"source\": \"AUTO_TRANSACTION\", \"status\": \"FILLED\"}}",
                draftId);

        getDraft(draftId)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.sections[?(@.sectionKey == 'UNMAPPED')].fields[0].fieldKey")
                        .value("GHOST"));
    }

    @Test
    void refusesADraftThatBelongsToAnotherBusiness() throws Exception {
        Long draftId = generate();

        String otherEmail = "other-" + System.nanoTime() + "@test.local";
        Long otherUser = jdbc.queryForObject("""
                INSERT INTO core.users (email, password_hash, name)
                VALUES (?, 'x', '남') RETURNING id
                """, Long.class, otherEmail);

        mockMvc.perform(get("/api/v1/tax-drafts/" + draftId)
                        .header("Authorization", "Bearer "
                                + tokenProvider.createAccessToken(otherUser, otherEmail)))
                .andExpect(status().isForbidden());
    }

    // --- 목록 ----------------------------------------------------------------

    @Test
    void listsDraftsNewestFirst() throws Exception {
        Long first = generate();
        Long second = generate();

        mockMvc.perform(get("/api/v1/businesses/" + businessId + "/tax-drafts")
                        .param("taxYear", "2026")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items.length()").value(2))
                .andExpect(jsonPath("$.data.items[0].draftId").value(second))
                .andExpect(jsonPath("$.data.items[1].draftId").value(first))
                .andExpect(jsonPath("$.data.items[0].formType").value("SIMPLE_LEDGER"));
    }

    // --- 근거 ----------------------------------------------------------------

    @Test
    void basisListsTheContributingTransactions() throws Exception {
        tx("RENT", "WITHDRAWAL", "1200000", "EVIDENCE_CONFIRMED");
        tx("RENT", "WITHDRAWAL", "1200000", "UNREVIEWED");

        getBasis(generate(), "RENT")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.fieldKey").value("RENT"))
                .andExpect(jsonPath("$.data.totalElements").value(2))
                .andExpect(jsonPath("$.data.items[0].category").value("RENT"))
                .andExpect(jsonPath("$.data.items[0].direction").value("WITHDRAWAL"));
    }

    /** 항목 금액에서 뺀 거래는 근거에도 없어야 한다 — 합계가 안 맞아 보인다. */
    @Test
    void basisLeavesOutWhatTheTotalLeftOut() throws Exception {
        tx("MATERIALS", "WITHDRAWAL", "100000", "EVIDENCE_CONFIRMED");
        tx("MATERIALS", "WITHDRAWAL", "500000", "BUSINESS_USE_UNCLEAR");
        tx("MATERIALS", "WITHDRAWAL", "700000", "UNREVIEWED", "SUGGESTED");

        getBasis(generate(), "PURCHASES")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.totalElements").value(1));
    }

    /**
     * ⑪ 매출액의 근거는 <b>비어 보이면 안 된다</b>.
     *
     * <p>금액이 정산에서 왔으므로 연결된 입금 거래도 목록에 넣고, 항목 값과 목록 합계의
     * 차이는 {@code settlementSummary} 가 설명한다.
     */
    @Test
    void basisShowsSettlementLinkedDepositsAndExplainsTheGap() throws Exception {
        settlementWithDeposit("1000000", "50000", "100000");

        getBasis(generate(), "SALES")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.totalElements").value(1))
                .andExpect(jsonPath("$.data.items[0].settlementLinked").value(true))
                .andExpect(jsonPath("$.data.settlementSummary.count").value(1))
                .andExpect(jsonPath("$.data.settlementSummary.grossSales").value(1000000))
                .andExpect(jsonPath("$.data.settlementSummary.fees").value(100000));
    }

    /** 사용자가 값을 넣는 항목에는 근거 거래가 없다. 오류가 아니다. */
    @Test
    void basisIsEmptyForUserEnteredFields() throws Exception {
        getBasis(generate(), "DEPRECIATION")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items.length()").value(0))
                .andExpect(jsonPath("$.data.totalElements").value(0));
    }

    @Test
    void paginatesBasis() throws Exception {
        for (int i = 0; i < 3; i++) {
            tx("RENT", "WITHDRAWAL", "100000", "EVIDENCE_CONFIRMED");
        }

        mockMvc.perform(get("/api/v1/tax-drafts/" + generate() + "/basis")
                        .param("fieldKey", "RENT").param("page", "1").param("size", "2")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items.length()").value(1))
                .andExpect(jsonPath("$.data.page").value(1))
                .andExpect(jsonPath("$.data.totalElements").value(3))
                .andExpect(jsonPath("$.data.totalPages").value(2));
    }

    @Test
    void basisCarriesLinkedEvidences() throws Exception {
        Long rent = tx("RENT", "WITHDRAWAL", "1200000", "EVIDENCE_CONFIRMED");
        Long evidenceId = jdbc.queryForObject("""
                INSERT INTO core.evidence
                    (business_id, uploaded_by, evidence_type, original_filename,
                     storage_key, mime_type, file_size)
                VALUES (?, ?, 'TAX_INVOICE', '임차료_2026-06.pdf', ?, 'application/pdf', 2048)
                RETURNING id
                """, Long.class, businessId, userId, "key-" + System.nanoTime());
        jdbc.update("""
                INSERT INTO core.transaction_evidence (transaction_id, evidence_id) VALUES (?, ?)
                """, rent, evidenceId);

        getBasis(generate(), "RENT")
                .andExpect(jsonPath("$.data.items[0].evidences[0].evidenceType").value("TAX_INVOICE"))
                .andExpect(jsonPath("$.data.items[0].evidences[0].fileName")
                        .value("임차료_2026-06.pdf"));
    }

    // --- 보류 금액 ------------------------------------------------------------

    /**
     * 설비 구입은 경비에 넣지 않되 <b>얼마가 왜 빠졌는지</b>는 보여 준다.
     *
     * <p>아무 데도 안 나타나면 사장님은 그 돈이 누락된 줄 안다.
     */
    @Test
    void attachesPendingAmountToDepreciation() throws Exception {
        tx("EQUIPMENT", "WITHDRAWAL", "2000000", "EVIDENCE_CONFIRMED");
        tx("EQUIPMENT", "WITHDRAWAL", "1400000", "EVIDENCE_CONFIRMED");
        tx("RENT", "WITHDRAWAL", "1000000", "EVIDENCE_CONFIRMED");

        getDraft(generate())
                .andExpect(status().isOk())
                .andExpect(jsonPath(
                        "$.data.sections[?(@.sectionKey == 'EXPENSE')].fields[?(@.fieldKey == 'DEPRECIATION')].pendingAmount")
                        .value(3400000))
                // 값이 비어 있다는 것은 TaxDraftAggregationTests 가 본다. 여기서 같은 것을
                // 필터 경로로 보면 JsonPath 가 없는 키를 [null] 로 돌려줘 단정이 헛돈다.
                .andExpect(jsonPath(
                        "$.data.sections[?(@.sectionKey == 'EXPENSE')].fields[?(@.fieldKey == 'TOTAL_EXPENSE')].value")
                        .value(1000000));
    }
}
