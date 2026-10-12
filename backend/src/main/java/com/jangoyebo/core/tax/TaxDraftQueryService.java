package com.jangoyebo.core.tax;

import com.jangoyebo.common.error.BusinessException;
import com.jangoyebo.common.error.ErrorCode;
import com.jangoyebo.common.tenancy.BusinessGuard;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 신고서 초안 조회 (API 14.3).
 *
 * <p><b>저장은 평평하고 응답은 섹션이다.</b> {@code fields} 는 항목 키로만 저장하고, 섹션
 * 구조와 라벨은 서식 템플릿에서 가져와 조립한다 — 같은 것을 두 군데 저장하면 서식이 개정될
 * 때 둘이 어긋난다.
 */
@Service
public class TaxDraftQueryService {

    private static final Logger log = LoggerFactory.getLogger(TaxDraftQueryService.class);

    /** 도메인 규칙 4 — 세무 값은 자동 확정하지 않는다. 응답에 항상 붙인다(14.3). */
    static final String DISCLAIMER =
            "이 문서는 초안입니다. 세액과 신고 유형은 서비스가 확정하지 않으며 "
                    + "최종 확인은 사용자 또는 세무전문가가 합니다.";

    private final JdbcTemplate jdbc;
    private final TaxFilingDraftRepository drafts;
    private final TaxFormTemplateRepository templates;
    private final BusinessGuard guard;

    public TaxDraftQueryService(JdbcTemplate jdbc,
                                TaxFilingDraftRepository drafts,
                                TaxFormTemplateRepository templates,
                                BusinessGuard guard) {
        this.jdbc = jdbc;
        this.drafts = drafts;
        this.templates = templates;
        this.guard = guard;
    }

    /**
     * 초안 상세.
     *
     * <p><b>서식은 {@code templateId} 로 부른다.</b> {@code formType + taxYear} 로 찾으면 그
     * 사이에 템플릿 행이 바뀌었을 때 <b>저장된 숫자에 다른 라벨이 붙는다</b>. 초안은 생성될
     * 때 쓴 정의로 그려져야 한다.
     *
     * <p><b>{@code readOnly} 가 아니다.</b> 상한 초안을 여기서 {@code STALE} 로 적기 때문이다
     * ({@link #hasChangedSince}). 한 번 적히면 다음 조회는 그 검사를 건너뛴다.
     */
    @Transactional
    public Map<String, Object> detail(Long draftId) {
        TaxFilingDraft draft = owned(draftId);
        TaxFormTemplate template = templates.findById(draft.getTemplateId())
                .orElseThrow(() -> new BusinessException(ErrorCode.TAX_DRAFT_NOT_FOUND));

        if (draft.getStatus() == TaxDraftStatus.READY && hasChangedSince(draft)) {
            draft.markStale();
        }

        Map<String, Object> stored = draft.getFields();
        Map<String, Object> overrides = draft.getOverrides() == null
                ? Map.of() : draft.getOverrides();

        Set<String> rendered = new LinkedHashSet<>();
        List<Map<String, Object>> sections = new ArrayList<>();
        Map<String, BigDecimal> numeric = new LinkedHashMap<>();

        for (Map<String, Object> definition : template.getSections()) {
            List<Map<String, Object>> fields = new ArrayList<>();
            for (Object raw : asList(definition.get("fields"))) {
                Map<String, Object> fieldDefinition = asMap(raw);
                String fieldKey = asString(fieldDefinition.get("fieldKey"));
                Map<String, Object> value = asMap(stored.get(fieldKey));
                if (value.isEmpty()) {
                    continue;
                }
                rendered.add(fieldKey);
                fields.add(effective(fieldKey, fieldDefinition, value,
                        asMap(overrides.get(fieldKey)), numeric));
            }
            if (!fields.isEmpty()) {
                sections.add(section(asString(definition.get("sectionKey")),
                        asString(definition.get("label")), fields));
            }
        }

        // 저장은 됐는데 서식이 모르는 항목. 템플릿 행을 제자리에서 고치지 않는 한 일어나지
        // 않지만, 조용히 버리면 **신고서에서 금액 한 줄이 소리 없이 사라진다.**
        List<Map<String, Object>> orphans = stored.keySet().stream()
                .filter(key -> !rendered.contains(key))
                .map(key -> render(key, key, asMap(stored.get(key))))
                .toList();
        if (!orphans.isEmpty()) {
            log.warn("초안 {} 에 서식이 모르는 항목이 {}개 있다 — 템플릿 {} 이 생성 이후 바뀌었는지 확인하라",
                    draftId, orphans.size(), draft.getTemplateId());
            sections.add(section("UNMAPPED", "서식에서 찾지 못한 항목", orphans));
        }

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("draftId", draft.getId());
        body.put("taxYear", draft.getTaxYear());
        body.put("formType", template.getFormType());
        body.put("status", draft.getStatus().name());
        body.put("periodStart", draft.getPeriodStart());
        body.put("periodEnd", draft.getPeriodEnd());
        body.put("generatedAt", draft.getGeneratedAt());
        body.put("basedOnReviewRate", draft.getBasedOnReviewRate());
        if (draft.getHometaxReference() != null) {
            body.put("hometaxReference", draft.getHometaxReference());
        }
        Map<String, Object> revenueCheck = revenueCheck(draft, numeric.get("TOTAL_REVENUE"));
        if (revenueCheck != null) {
            body.put("revenueCheck", revenueCheck);
        }
        body.put("sections", sections);
        if (draft.getExcludedSummary() != null) {
            body.put("excluded", draft.getExcludedSummary());
        }
        body.put("disclaimer", DISCLAIMER);
        return body;
    }

    /**
     * 집계값 위에 사용자 수정값을 겹친 「지금 보이는 값」.
     *
     * <p><b>산식은 다시 푼다.</b> 저장된 결과를 그대로 쓰면 사장님이 ㉛ 감가상각비를 적어도
     * 필요경비 합계와 소득금액이 그대로다 — 한 화면 안에서 숫자가 서로 맞지 않는다.
     *
     * <p>원래 집계값은 {@code aggregatedValue} 로 남긴다. 무엇을 고쳤는지가 근거다.
     */
    private Map<String, Object> effective(String fieldKey,
                                          Map<String, Object> definition,
                                          Map<String, Object> stored,
                                          Map<String, Object> override,
                                          Map<String, BigDecimal> numeric) {
        Map<String, Object> field = render(fieldKey, asString(definition.get("label")), stored);
        String formula = asString(definition.get("formula"));

        if ("CALCULATED".equals(definition.get("source")) && formula != null) {
            BigDecimal value = FormulaEvaluator.evaluate(formula, numeric);
            field.put("value", value);
            numeric.put(fieldKey, value);
            return field;
        }

        if (override.containsKey("value")) {
            Object original = stored.get("value");
            if (original != null) {
                field.put("aggregatedValue", original);
            }
            field.put("value", override.get("value"));
            field.put("status", "FILLED");
        }
        if (override.get("note") != null) {
            field.put("overrideNote", override.get("note"));
        }
        if (Boolean.TRUE.equals(override.get("confirmed"))) {
            field.put("confirmed", true);
        }
        // 재생성이 물려준 값이다. 화면이 「지난번에 적으신 값입니다」로 구분해 줘야
        // 사장님이 새 기간에 그대로 써도 되는지 판단할 수 있다.
        if (Boolean.TRUE.equals(override.get("carriedOver"))) {
            field.put("carriedOver", true);
        }
        numeric.put(fieldKey, amount(field.get("value")));
        return field;
    }

    /**
     * 생성 이후 근거가 달라졌는가 (14.3 — {@code STALE}).
     *
     * <p><b>이벤트로 밀지 않고 여기서 센다.</b> 이 저장소의 {@code TransactionsChanged} 는
     * 「유실돼도 손실이 없다」를 전제로 만든 것이라({@code event_publication} 테이블이 없다)
     * 재시작 한 번에 사라질 수 있다. 다른 소비자는 다음 수집이 같은 일을 다시 해서 괜찮지만,
     * 상한 초안은 <b>되돌릴 다음 실행이 없다</b> — 틀린 숫자가 {@code READY} 인 채로 남는다.
     *
     * <p><b>명세보다 넓게 본다.</b> 14.3 은 검토 상태(8.7)만 말하는데 <b>재분류도 금액을
     * 바꾼다</b>. 분류 이력이 append-only 라 그 시각만 보면 된다. 증빙 연결은 따로 볼 필요가
     * 없다 — 연결되면 검토 상태가 자동 판정되면서 {@code tax_reviewed_at} 이 함께 찍힌다.
     *
     * <p><b>정산 대사(14.1)도 본다.</b> ⑪ 매출액이 정산의 총매출에서 오므로 정산을 더하거나
     * 고치면 금액이 달라지는데, 거래는 하나도 안 움직인다 — 앞의 둘만 보면 <b>매출이 틀린
     * 초안이 {@code READY} 로 남는다</b>.
     */
    private boolean hasChangedSince(TaxFilingDraft draft) {
        Timestamp since = Timestamp.from(draft.getGeneratedAt());
        return Boolean.TRUE.equals(jdbc.queryForObject("""
                SELECT EXISTS (SELECT 1 FROM core.transaction
                                WHERE business_id = ? AND transaction_date BETWEEN ? AND ?
                                  AND tax_reviewed_at > ?)
                    OR EXISTS (SELECT 1 FROM core.transaction_classification_history h
                                 JOIN core.transaction t ON t.id = h.transaction_id
                                WHERE t.business_id = ? AND t.transaction_date BETWEEN ? AND ?
                                  AND h.created_at > ?)
                    OR EXISTS (SELECT 1 FROM core.settlement
                                WHERE business_id = ? AND sales_to BETWEEN ? AND ?
                                  AND updated_at > ?)
                """, Boolean.class,
                draft.getBusinessId(), draft.getPeriodStart(), draft.getPeriodEnd(), since,
                draft.getBusinessId(), draft.getPeriodStart(), draft.getPeriodEnd(), since,
                draft.getBusinessId(), draft.getPeriodStart(), draft.getPeriodEnd(), since));
    }

    /**
     * 수입금액 대사 (14.3 {@code revenueCheck}).
     *
     * <p>세 값을 나란히 놓는다 — 서비스 집계(입금·정산 기준) · 국세청 수입금액(사용자가
     * 옮겨 적은 값) · POS 판매 총액(참고).
     *
     * <p><b>POS 값은 항목 금액에 반영하지 않는다.</b> 판매 층을 더하면 같은 매출이 두 번
     * 들어간다(ERD 1.9 「소비처와 비소비처」). 다만 「현금 매출이 빠지지 않았나」를 묻는 데는
     * 판매 층이 답을 갖고 있다 — 인쇄물은 결제수단과 무관하게 판매 전량을 담기 때문이다.
     *
     * <p>문장은 도메인 규칙 1 을 따른다. 「누락입니다」가 아니라 「…일 수 있습니다」다.
     */
    private Map<String, Object> revenueCheck(TaxFilingDraft draft, BigDecimal serviceTotal) {
        BigDecimal total = serviceTotal == null ? BigDecimal.ZERO : serviceTotal;
        BigDecimal ntsRevenue = draft.getHometaxReference() == null
                ? null : amountOrNull(draft.getHometaxReference().get("ntsRevenue"));
        BigDecimal productSales = jdbc.queryForObject("""
                SELECT COALESCE(SUM(COALESCE(billed_amount, gross_amount)), 0)
                  FROM core.product_sales_period
                 WHERE business_id = ? AND period_start >= ? AND period_end <= ?
                """, BigDecimal.class,
                draft.getBusinessId(), draft.getPeriodStart(), draft.getPeriodEnd());

        boolean hasProductSales = productSales != null && productSales.signum() > 0;
        if (ntsRevenue == null && !hasProductSales) {
            return null;
        }

        Map<String, Object> check = new LinkedHashMap<>();
        check.put("serviceTotal", total);
        List<String> notes = new ArrayList<>();
        if (ntsRevenue != null) {
            check.put("ntsRevenue", ntsRevenue);
            BigDecimal gap = total.subtract(ntsRevenue);
            check.put("difference", gap);
            if (gap.signum() != 0) {
                notes.add("국세청 수입금액과 %,d원 차이가 있습니다.".formatted(gap.abs().longValue()));
            }
        }
        if (hasProductSales) {
            check.put("productSalesTotal", productSales);
            if (productSales.compareTo(total) > 0) {
                notes.add("POS 판매 총액이 서비스 집계보다 %,d원 많아 현금 매출 미입금분일 수 있습니다."
                        .formatted(productSales.subtract(total).longValue()));
            }
            notes.add("POS 판매 총액은 참고값이며 항목 금액에 반영하지 않습니다.");
        }
        if (!notes.isEmpty()) {
            check.put("note", String.join(" ", notes));
        }
        return check;
    }

    /** 귀속연도별 초안 목록. 민감 항목은 애초에 저장되지 않으므로 담길 것이 없다. */
    @Transactional(readOnly = true)
    public List<Map<String, Object>> list(Long businessId, int taxYear) {
        return jdbc.query("""
                SELECT d.id, d.status, d.based_on_review_rate, d.generated_at,
                       d.period_start, d.period_end, t.form_type
                  FROM core.tax_filing_draft d
                  JOIN core.tax_form_template t ON t.id = d.template_id
                 WHERE d.business_id = ? AND d.tax_year = ?
                 ORDER BY d.id DESC
                """, (rs, i) -> {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("draftId", rs.getLong("id"));
            row.put("formType", rs.getString("form_type"));
            row.put("status", rs.getString("status"));
            row.put("basedOnReviewRate", rs.getBigDecimal("based_on_review_rate"));
            row.put("periodStart", rs.getDate("period_start").toLocalDate());
            row.put("periodEnd", rs.getDate("period_end").toLocalDate());
            var generatedAt = rs.getTimestamp("generated_at");
            if (generatedAt != null) {
                row.put("generatedAt", generatedAt.toInstant());
            }
            return row;
        }, businessId, taxYear);
    }

    /**
     * 항목의 근거 거래 (14.3 {@code basis}).
     *
     * <p><b>저장하지 않고 다시 조회한다.</b> 어떤 거래가 어느 항목에 들어갔는지를 저장해 두면
     * 거래가 바뀌었을 때 근거와 화면이 어긋난다(ERD).
     *
     * <p><b>정산에 연결된 입금도 목록에 넣는다</b>(집계에서는 뺐다). 빼고 내보내면 ⑪ 매출액의
     * 근거 화면이 <b>비어 보인다</b> — 금액이 정산에서 왔기 때문이다. 대신 {@code settlementLinked}
     * 로 표시하고, 항목 값과 목록 합계의 차이는 {@code settlementSummary} 가 설명한다.
     */
    @Transactional(readOnly = true)
    public Map<String, Object> basis(Long draftId, String fieldKey, Pageable pageable) {
        TaxFilingDraft draft = owned(draftId);
        TaxFormTemplate template = templates.findById(draft.getTemplateId())
                .orElseThrow(() -> new BusinessException(ErrorCode.TAX_DRAFT_NOT_FOUND));

        List<String> categories = new ArrayList<>();
        boolean settlementBacked = false;
        for (var entry : template.getCategoryMapping().entrySet()) {
            Map<String, Object> rule = asMap(entry.getValue());
            if (fieldKey.equals(rule.get("fieldKey"))) {
                categories.add(entry.getKey());
                settlementBacked |= Boolean.TRUE.equals(rule.get("settlementPreferred"));
            }
        }

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("fieldKey", fieldKey);
        if (categories.isEmpty()) {
            // 사용자가 값을 넣는 항목(㉛ 감가상각비 등)에는 근거 거래가 없다. 오류가 아니다.
            body.put("items", List.of());
            body.put("page", pageable.getPageNumber());
            body.put("size", pageable.getPageSize());
            body.put("totalElements", 0L);
            body.put("totalPages", 0);
            return body;
        }

        Page<Map<String, Object>> page = transactions(draft, categories, pageable);
        body.put("items", page.getContent());
        body.put("page", page.getNumber());
        body.put("size", page.getSize());
        body.put("totalElements", page.getTotalElements());
        body.put("totalPages", page.getTotalPages());

        if (settlementBacked) {
            Map<String, Object> summary = settlementSummary(draft);
            if (((Number) summary.get("count")).intValue() > 0) {
                body.put("settlementSummary", summary);
            }
        }
        return body;
    }

    private Page<Map<String, Object>> transactions(TaxFilingDraft draft, List<String> categories,
                                                   Pageable pageable) {
        String where = """
                 WHERE t.business_id = ?
                   AND t.transaction_date BETWEEN ? AND ?
                   AND t.classification_status = 'CONFIRMED'
                   AND t.tax_review_status IN ('EVIDENCE_CONFIRMED', 'EVIDENCE_MISSING', 'UNREVIEWED')
                   AND c.code IN (%s)
                """.formatted(String.join(",", categories.stream().map(c -> "?").toList()));

        List<Object> arguments = new ArrayList<>();
        arguments.add(draft.getBusinessId());
        arguments.add(draft.getPeriodStart());
        arguments.add(draft.getPeriodEnd());
        arguments.addAll(categories);
        Object[] args = arguments.toArray();

        Long total = jdbc.queryForObject("""
                SELECT COUNT(*) FROM core.transaction t
                  JOIN core.transaction_category c ON c.id = t.category_id
                """ + where, Long.class, args);

        List<Object> pagedArguments = new ArrayList<>(arguments);
        pagedArguments.add(pageable.getPageSize());
        pagedArguments.add(pageable.getOffset());
        Object[] paged = pagedArguments.toArray();

        List<Map<String, Object>> rows = jdbc.query("""
                SELECT t.id, t.occurred_at, t.description, t.amount, t.flow_type,
                       c.code AS category, t.tax_review_status,
                       EXISTS (SELECT 1 FROM core.settlement_transaction st
                                WHERE st.transaction_id = t.id) AS settlement_linked
                  FROM core.transaction t
                  JOIN core.transaction_category c ON c.id = t.category_id
                """ + where + """
                 ORDER BY t.transaction_date DESC, t.id DESC
                 LIMIT ? OFFSET ?
                """, (rs, i) -> {
            Map<String, Object> row = new LinkedHashMap<>();
            long transactionId = rs.getLong("id");
            row.put("transactionId", transactionId);
            row.put("occurredAt", rs.getTimestamp("occurred_at").toInstant());
            row.put("description", rs.getString("description"));
            row.put("amount", rs.getBigDecimal("amount"));
            row.put("direction", rs.getString("flow_type"));
            row.put("category", rs.getString("category"));
            row.put("taxReviewStatus", rs.getString("tax_review_status"));
            if (rs.getBoolean("settlement_linked")) {
                row.put("settlementLinked", true);
            }
            row.put("evidences", evidences(transactionId));
            return row;
        }, paged);

        return new PageImpl<>(rows, pageable, total == null ? 0 : total);
    }

    private List<Map<String, Object>> evidences(long transactionId) {
        return jdbc.query("""
                SELECT e.id, e.evidence_type, e.original_filename
                  FROM core.transaction_evidence te
                  JOIN core.evidence e ON e.id = te.evidence_id
                 WHERE te.transaction_id = ?
                 ORDER BY e.id
                """, (rs, i) -> Map.<String, Object>of(
                "evidenceId", rs.getLong("id"),
                "evidenceType", rs.getString("evidence_type"),
                "fileName", rs.getString("original_filename")), transactionId);
    }

    /** 귀속 기준일은 정산 종료일이다 — 집계와 같은 규칙이어야 근거가 맞는다. */
    private Map<String, Object> settlementSummary(TaxFilingDraft draft) {
        return jdbc.queryForObject("""
                SELECT COUNT(*)                              AS n,
                       COALESCE(SUM(gross_sales), 0)         AS gross,
                       COALESCE(SUM(cancellation_amount), 0) AS cancellations,
                       COALESCE(SUM(fee_amount), 0)          AS fees
                  FROM core.settlement
                 WHERE business_id = ? AND sales_to BETWEEN ? AND ?
                """, (rs, i) -> {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("count", rs.getInt("n"));
            row.put("grossSales", rs.getBigDecimal("gross"));
            row.put("cancellations", rs.getBigDecimal("cancellations"));
            row.put("fees", rs.getBigDecimal("fees"));
            row.put("note", "매출액은 총매출에서 취소를 뺀 금액입니다. "
                    + "목록의 입금액은 수수료를 뗀 뒤의 금액이라 합계가 다릅니다.");
            return row;
        }, draft.getBusinessId(), draft.getPeriodStart(), draft.getPeriodEnd());
    }

    // --- 잡동사니 -------------------------------------------------------------

    /** 직접 경로다 — Hibernate 필터가 걸리지 않으므로 명시 검증이 필요하다. */
    private TaxFilingDraft owned(Long draftId) {
        TaxFilingDraft draft = drafts.findById(draftId)
                .orElseThrow(() -> new BusinessException(ErrorCode.TAX_DRAFT_NOT_FOUND));
        guard.assertOwned(draft);
        return draft;
    }

    /** 산식에 넣을 수 있는 값만 숫자로 본다. 성명·과세기간 같은 글자는 0 이다. */
    private static BigDecimal amount(Object value) {
        BigDecimal parsed = amountOrNull(value);
        return parsed == null ? BigDecimal.ZERO : parsed;
    }

    private static BigDecimal amountOrNull(Object value) {
        if (value instanceof BigDecimal decimal) {
            return decimal;
        }
        if (value instanceof Number number) {
            return new BigDecimal(number.toString());
        }
        return null;
    }

    private static Map<String, Object> render(String fieldKey, String label,
                                              Map<String, Object> stored) {
        Map<String, Object> field = new LinkedHashMap<>();
        field.put("fieldKey", fieldKey);
        field.put("label", label);
        field.putAll(stored);
        return field;
    }

    private static Map<String, Object> section(String key, String label,
                                               List<Map<String, Object>> fields) {
        Map<String, Object> section = new LinkedHashMap<>();
        section.put("sectionKey", key);
        section.put("label", label);
        section.put("fields", fields);
        return section;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asMap(Object value) {
        return value instanceof Map<?, ?> map ? (Map<String, Object>) map : Map.of();
    }

    private static List<?> asList(Object value) {
        return value instanceof List<?> list ? list : List.of();
    }

    private static String asString(Object value) {
        return value == null ? null : value.toString();
    }
}
