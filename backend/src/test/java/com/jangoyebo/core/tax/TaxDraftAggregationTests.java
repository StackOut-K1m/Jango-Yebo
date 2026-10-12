package com.jangoyebo.core.tax;

import com.jangoyebo.NeedsDocker;
import com.jangoyebo.common.error.BusinessException;
import com.jangoyebo.common.error.ErrorCode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 신고서 초안 집계 (API 14.3 · P0-12).
 *
 * <p><b>여기서 지키는 것은 「그럴듯하게 틀린 숫자」다.</b> 집계가 어긋나도 앱은 잘 뜨고
 * 화면에는 멀쩡한 금액이 찍힌다 — 소득금액만 조용히 달라진다. 특히 정산 보정 네 줄은
 * 각각 실수의 방향이 반대라(매출 과소 / 경비 과소 / 매출 이중계상) 하나씩 못박는다.
 *
 * <p><b>Docker가 필요하다.</b>
 */
@NeedsDocker
@SpringBootTest
class TaxDraftAggregationTests {

    private static final LocalDate FROM = LocalDate.of(2026, 1, 1);
    private static final LocalDate TO = LocalDate.of(2026, 12, 31);

    @Autowired private JdbcTemplate jdbc;
    @Autowired private TaxDraftService service;
    @Autowired private TaxDraftAggregator aggregator;
    @Autowired private TaxFilingDraftRepository drafts;

    private Long userId;
    private Long businessId;
    private Long accountId;

    @BeforeEach
    void setUp() {
        String unique = String.valueOf(System.nanoTime());
        userId = jdbc.queryForObject("""
                INSERT INTO core.users (email, password_hash, name, phone)
                VALUES (?, 'x', '정유선', '010-1234-5678') RETURNING id
                """, Long.class, "taxdraft-" + unique + "@test.local");
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
        Long categoryId = category == null ? null : jdbc.queryForObject(
                "SELECT id FROM core.transaction_category WHERE code = ?", Long.class, category);
        return jdbc.queryForObject("""
                INSERT INTO core.transaction
                    (business_id, account_id, category_id, source_type, occurred_at,
                     transaction_date, flow_type, amount, description, deduplication_key,
                     classification_status, tax_review_status)
                VALUES (?, ?, ?, 'FIN_API', ?, DATE '2026-06-15', ?, ?, '거래', ?, ?, ?)
                RETURNING id
                """, Long.class, businessId, accountId, categoryId,
                Timestamp.from(Instant.now()), flow, new BigDecimal(amount),
                "dedup-" + System.nanoTime(), classificationStatus, reviewStatus);
    }

    private Long settlement(String gross, String cancellations, String fees, String status) {
        return jdbc.queryForObject("""
                INSERT INTO core.settlement
                    (business_id, platform_name, sales_from, sales_to,
                     gross_sales, cancellation_amount, fee_amount, status)
                VALUES (?, '배달의민족', DATE '2026-06-01', DATE '2026-06-30', ?, ?, ?, ?)
                RETURNING id
                """, Long.class, businessId, new BigDecimal(gross),
                new BigDecimal(cancellations), new BigDecimal(fees), status);
    }

    private void link(Long settlementId, Long transactionId, String amount) {
        jdbc.update("""
                INSERT INTO core.settlement_transaction
                    (settlement_id, transaction_id, allocated_amount)
                VALUES (?, ?, ?)
                """, settlementId, transactionId, new BigDecimal(amount));
    }

    private void attachEvidence(Long transactionId) {
        Long evidenceId = jdbc.queryForObject("""
                INSERT INTO core.evidence
                    (business_id, uploaded_by, original_filename, storage_key, mime_type, file_size)
                VALUES (?, ?, '영수증.pdf', ?, 'application/pdf', 1024) RETURNING id
                """, Long.class, businessId, userId, "key-" + System.nanoTime());
        jdbc.update("""
                INSERT INTO core.transaction_evidence (transaction_id, evidence_id)
                VALUES (?, ?)
                """, transactionId, evidenceId);
    }

    private TaxFilingDraft generate() {
        TaxFilingDraft draft = service.request(
                businessId, userId, 2026, "SIMPLE_LEDGER", FROM, TO, null);
        aggregator.generate(draft.getId());
        return drafts.findById(draft.getId()).orElseThrow();
    }

    @SuppressWarnings("unchecked")
    private BigDecimal valueOf(TaxFilingDraft draft, String fieldKey) {
        Map<String, Object> field = (Map<String, Object>) draft.getFields().get(fieldKey);
        assertThat(field).as("항목 %s 이 서식에 없다", fieldKey).isNotNull();
        Object value = field.get("value");
        return value == null ? null : new BigDecimal(value.toString());
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> field(TaxFilingDraft draft, String fieldKey) {
        return (Map<String, Object>) draft.getFields().get(fieldKey);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> excluded(TaxFilingDraft draft, String bucket) {
        return (Map<String, Object>) draft.getExcludedSummary().get(bucket);
    }

    // --- 카테고리 매핑 ---------------------------------------------------------

    @Test
    void sumsExpensesIntoTheMappedFormField() {
        tx("RENT", "WITHDRAWAL", "1200000", "EVIDENCE_CONFIRMED");
        tx("RENT", "WITHDRAWAL", "1200000", "EVIDENCE_CONFIRMED");
        tx("PAYROLL", "WITHDRAWAL", "2400000", "EVIDENCE_MISSING");

        TaxFilingDraft draft = generate();

        assertThat(valueOf(draft, "RENT")).isEqualByComparingTo("2400000");
        assertThat(valueOf(draft, "PAYROLL")).isEqualByComparingTo("2400000");
        assertThat(draft.getStatus()).isEqualTo(TaxDraftStatus.READY);
    }

    /** 네 카테고리가 한 항목(㊴ 기타)으로 모인다. */
    @Test
    void mergesSeveralCategoriesIntoOneField() {
        tx("UTILITIES", "WITHDRAWAL", "100000", "UNREVIEWED");
        tx("TELECOM", "WITHDRAWAL", "50000", "UNREVIEWED");
        tx("INSURANCE", "WITHDRAWAL", "30000", "UNREVIEWED");
        tx("SUBSCRIPTION", "WITHDRAWAL", "20000", "UNREVIEWED");

        assertThat(valueOf(generate(), "OTHER_EXPENSE")).isEqualByComparingTo("200000");
    }

    // --- 정산 보정 (이 슬라이스의 핵심) -----------------------------------------

    /**
     * <b>매출은 입금액이 아니라 총매출에서 온다.</b>
     *
     * <p>입금액을 쓰면 수수료만큼 매출이 적게 잡힌다. 명세가 「순정산 입금액을 총매출로
     * 보정한다」고 적은 것이 이것이다.
     */
    @Test
    void usesGrossSalesRatherThanTheNetDeposit() {
        Long deposit = tx("PLATFORM_SETTLEMENT", "DEPOSIT", "850000", "EVIDENCE_CONFIRMED");
        Long settlement = settlement("1000000", "50000", "100000", "MATCHED");
        link(settlement, deposit, "850000");

        // 총매출 1,000,000 − 취소 50,000 = 950,000. 입금 850,000 이 아니다.
        assertThat(valueOf(generate(), "SALES")).isEqualByComparingTo("950000");
    }

    /**
     * <b>정산 수수료는 출금 거래로 찍히지 않는다.</b>
     *
     * <p>총매출을 매출로 잡았으면 그 수수료를 경비에 넣어야 소득금액이 맞는다. 빠뜨리면
     * 경비가 적어 <b>소득이 과대 계상</b>된다 — 세금을 더 내게 만드는 방향이라 조용히 넘어간다.
     */
    @Test
    void addsTheSettlementFeeToCommissionBecauseItHasNoTransaction() {
        Long deposit = tx("PLATFORM_SETTLEMENT", "DEPOSIT", "850000", "EVIDENCE_CONFIRMED");
        link(settlement("1000000", "50000", "100000", "MATCHED"), deposit, "850000");

        TaxFilingDraft draft = generate();

        assertThat(valueOf(draft, "COMMISSION")).isEqualByComparingTo("100000");
        assertThat(field(draft, "COMMISSION").get("reviewNote").toString())
                .contains("출금 거래로는 찍히지 않는");
    }

    /**
     * <b>연결된 입금 거래를 두 번 세지 않는다.</b>
     *
     * <p>총매출과 입금을 다 더하면 매출이 거의 두 배가 된다. 가장 크게 틀리는 경우인데
     * 숫자만 보면 「장사가 잘됐네」로 읽힌다.
     */
    @Test
    void doesNotCountTheLinkedDepositOnTopOfGrossSales() {
        Long deposit = tx("PLATFORM_SETTLEMENT", "DEPOSIT", "850000", "EVIDENCE_CONFIRMED");
        link(settlement("1000000", "50000", "100000", "MATCHED"), deposit, "850000");

        assertThat(valueOf(generate(), "SALES"))
                .as("총매출 950,000 뿐이어야 한다. 1,800,000 이면 입금이 겹쳐 들어간 것이다")
                .isEqualByComparingTo("950000");
    }

    /** 정산 대사가 없는 플랫폼 입금은 입금액 그대로 쓰되 확인 필요로 남긴다. */
    @Test
    void flagsPlatformDepositsThatHaveNoSettlement() {
        tx("CARD_SETTLEMENT", "DEPOSIT", "700000", "EVIDENCE_CONFIRMED");

        TaxFilingDraft draft = generate();

        assertThat(valueOf(draft, "SALES")).isEqualByComparingTo("700000");
        assertThat(field(draft, "SALES").get("status")).isEqualTo("NEEDS_REVIEW");
        assertThat(field(draft, "SALES").get("reviewNote").toString())
                .contains("수수료 차감 전");
    }

    /** 대사가 안 맞은 정산도 매출로 센다 — 입금 확인 문제이지 매출이 없었던 것이 아니다. */
    @Test
    void countsMismatchedSettlementsAndSaysSo() {
        settlement("1000000", "0", "100000", "MISMATCHED");

        TaxFilingDraft draft = generate();

        assertThat(valueOf(draft, "SALES")).isEqualByComparingTo("1000000");
        assertThat(field(draft, "SALES").get("reviewNote").toString()).contains("대사가 맞지 않은");
    }

    /** 귀속 기준일은 정산 종료일이다. 기간 밖에서 끝난 정산은 들어오지 않는다. */
    @Test
    void attributesSettlementsByTheirEndDate() {
        jdbc.update("""
                INSERT INTO core.settlement
                    (business_id, platform_name, sales_from, sales_to,
                     gross_sales, cancellation_amount, fee_amount, status)
                VALUES (?, '배달의민족', DATE '2026-12-28', DATE '2027-01-03', 500000, 0, 0, 'MATCHED')
                """, businessId);

        assertThat(valueOf(generate(), "SALES")).isEqualByComparingTo("0");
    }

    // --- 제외 --------------------------------------------------------------

    @Test
    void separatesPersonalNeedsReviewAndUnclassified() {
        tx("PERSONAL", "WITHDRAWAL", "300000", "PERSONAL_SUSPECTED");
        tx("MATERIALS", "WITHDRAWAL", "500000", "BUSINESS_USE_UNCLEAR");
        tx("MATERIALS", "WITHDRAWAL", "700000", "UNREVIEWED", "SUGGESTED");
        tx("MATERIALS", "WITHDRAWAL", "100000", "EVIDENCE_CONFIRMED");

        TaxFilingDraft draft = generate();

        assertThat(valueOf(draft, "PURCHASES"))
                .as("확인 필요·미분류는 항목 금액에서 빠진다")
                .isEqualByComparingTo("100000");
        assertThat(excluded(draft, "personalSuspected")).containsEntry("count", 1);
        assertThat(excluded(draft, "needsReview")).containsEntry("count", 1);
        assertThat(excluded(draft, "unclassified"))
                .as("분류를 안 돌린 사업장의 빈 초안을 「매출 0」으로 읽지 않게 한다")
                .containsEntry("count", 1);
    }

    /** 설비 구입은 항목 금액에 넣지 않는다 — 감가상각 검토 대상이다. */
    @Test
    void keepsEquipmentOutOfEveryFormField() {
        tx("EQUIPMENT", "WITHDRAWAL", "3400000", "EVIDENCE_CONFIRMED");
        tx("RENT", "WITHDRAWAL", "1000000", "EVIDENCE_CONFIRMED");

        TaxFilingDraft draft = generate();

        assertThat(valueOf(draft, "TOTAL_EXPENSE"))
                .as("설비 340만원이 섞이면 4,400,000 이 된다")
                .isEqualByComparingTo("1000000");
        assertThat(valueOf(draft, "DEPRECIATION")).isNull();
    }

    // --- 산식 --------------------------------------------------------------

    @Test
    void computesTheFormFormulas() {
        tx("CASH_SALES", "DEPOSIT", "10000000", "EVIDENCE_CONFIRMED");
        tx("OTHER_INCOME", "DEPOSIT", "500000", "EVIDENCE_CONFIRMED");
        tx("MATERIALS", "WITHDRAWAL", "3000000", "EVIDENCE_CONFIRMED");
        tx("RENT", "WITHDRAWAL", "1200000", "EVIDENCE_CONFIRMED");

        TaxFilingDraft draft = generate();

        assertThat(valueOf(draft, "TOTAL_REVENUE")).isEqualByComparingTo("10500000");
        assertThat(valueOf(draft, "COST_OF_SALES")).isEqualByComparingTo("3000000");
        assertThat(valueOf(draft, "TOTAL_EXPENSE")).isEqualByComparingTo("4200000");
        // 세무조정 기본값이 0 이라 조정 전후가 같다.
        assertThat(valueOf(draft, "ADJUSTED_REVENUE")).isEqualByComparingTo("10500000");
        assertThat(valueOf(draft, "ADJUSTED_EXPENSE")).isEqualByComparingTo("4200000");
        assertThat(valueOf(draft, "NET_INCOME")).isEqualByComparingTo("6300000");
    }

    // --- 근거 요약·민감 항목 ----------------------------------------------------

    /**
     * 증빙이 둘인 거래도 <b>한 건</b>이다.
     *
     * <p>{@code transaction_evidence} 를 바로 조인하면 그 거래가 두 줄이 되어 금액 합계까지
     * 부풀어 오른다.
     */
    @Test
    void countsATransactionOnceEvenWithTwoEvidences() {
        Long rent = tx("RENT", "WITHDRAWAL", "1200000", "EVIDENCE_CONFIRMED");
        attachEvidence(rent);
        attachEvidence(rent);

        TaxFilingDraft draft = generate();

        assertThat(valueOf(draft, "RENT"))
                .as("2,400,000 이면 증빙 조인이 금액을 부풀린 것이다")
                .isEqualByComparingTo("1200000");
        @SuppressWarnings("unchecked")
        Map<String, Object> basis = (Map<String, Object>) field(draft, "RENT").get("basisSummary");
        assertThat(basis).containsEntry("transactionCount", 1).containsEntry("evidenceCount", 1);
    }

    @Test
    void neverStoresSensitiveValues() {
        TaxFilingDraft draft = generate();

        assertThat(field(draft, "HOME_ADDRESS")).containsEntry("value", null)
                .containsEntry("sensitive", true);
        assertThat(field(draft, "BIRTH_DATE")).containsEntry("value", null);
    }

    @Test
    void fillsProfileFieldsAndMasksTheRegistrationNumber() {
        TaxFilingDraft draft = generate();

        assertThat(field(draft, "NAME")).containsEntry("value", "정유선");
        assertThat(field(draft, "PHONE")).containsEntry("value", "010-1234-5678");
        assertThat(field(draft, "BIZ_REG_NO")).containsEntry("value", "123-**-**789");
        assertThat(field(draft, "INCOME_TYPE")).containsEntry("value", 40);
    }

    @Test
    void reportsTheReviewRateAtGenerationTime() {
        tx("RENT", "WITHDRAWAL", "100000", "EVIDENCE_CONFIRMED");
        tx("RENT", "WITHDRAWAL", "100000", "EVIDENCE_CONFIRMED");
        tx("RENT", "WITHDRAWAL", "100000", "EVIDENCE_CONFIRMED");
        tx("RENT", "WITHDRAWAL", "100000", "UNREVIEWED");

        assertThat(generate().getBasedOnReviewRate()).isEqualByComparingTo("0.7500");
    }

    /** 검토할 거래가 없었던 것과 검토를 안 한 것은 다르다. */
    @Test
    void reportsAFullRateWhenThereIsNothingToReview() {
        assertThat(generate().getBasedOnReviewRate()).isEqualByComparingTo("1.0000");
    }

    // --- 접수 규칙 ------------------------------------------------------------

    @Test
    void refusesFormsThatOnlyGuide() {
        assertThatThrownBy(() -> service.request(
                businessId, userId, 2026, "SIMPLE_RATE", FROM, TO, null))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.FORM_NOT_SUPPORTED);
    }

    @Test
    void returnsTheInFlightDraftInsteadOfMakingASecondOne() {
        TaxFilingDraft first = service.request(businessId, userId, 2026, "SIMPLE_LEDGER", FROM, TO, null);
        TaxFilingDraft second = service.request(businessId, userId, 2026, "SIMPLE_LEDGER", FROM, TO, null);

        assertThat(second.getId()).isEqualTo(first.getId());
    }

    /** 완료된 초안은 재생성 대상이다 — 새 행이 생겨야 이력이 남는다. */
    @Test
    void makesANewDraftOnceTheEarlierOneIsReady() {
        TaxFilingDraft first = service.request(businessId, userId, 2026, "SIMPLE_LEDGER", FROM, TO, null);
        aggregator.generate(first.getId());

        TaxFilingDraft second = service.request(businessId, userId, 2026, "SIMPLE_LEDGER", FROM, TO, null);

        assertThat(second.getId()).isNotEqualTo(first.getId());
    }

    @Test
    void refusesAPeriodOutsideTheTaxYear() {
        assertThatThrownBy(() -> service.request(businessId, userId, 2026, "SIMPLE_LEDGER",
                LocalDate.of(2025, 12, 1), TO, null))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("귀속연도");
    }

    /** 홈택스 참고값은 표시용이다 — ⑦ 주업종코드가 거기서 온다. */
    @Test
    void carriesTheHometaxIndustryCodeIntoTheForm() {
        TaxFilingDraft draft = service.request(businessId, userId, 2026, "SIMPLE_LEDGER", FROM, TO,
                Map.of("industryCode", "552305", "ntsRevenue", 98500000));
        aggregator.generate(draft.getId());

        assertThat(field(drafts.findById(draft.getId()).orElseThrow(), "INDUSTRY_CODE"))
                .containsEntry("value", "552305");
    }
}
