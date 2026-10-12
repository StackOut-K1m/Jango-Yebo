package com.jangoyebo.core.tax;

import com.jangoyebo.common.error.BusinessException;
import com.jangoyebo.common.error.ErrorCode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 신고서 초안 집계 (P0-12 · API 14.3).
 *
 * <p><b>AI 를 부르지 않는다.</b> 명세가 「초안 생성은 AI 워커를 호출하지 않는다」로 정한
 * P0 경로다(공통규약 23장). 여기서 나오는 숫자는 전부 거래·증빙·정산 대사에서 온다.
 *
 * <p><b>서식 정의는 읽기만 한다.</b> 어떤 카테고리가 어느 항목으로 가는지, 어떤 항목이
 * 어떤 산식인지는 전부 {@link TaxFormTemplate} 의 JSONB 에 있다. 이 클래스에 항목 이름이
 * 상수로 박히면 서식이 개정될 때 코드를 고쳐야 한다.
 */
@Service
public class TaxDraftAggregator {

    private static final Logger log = LoggerFactory.getLogger(TaxDraftAggregator.class);

    private final JdbcTemplate jdbc;
    private final TaxFormTemplateRepository templates;
    private final TaxFilingDraftRepository drafts;

    public TaxDraftAggregator(JdbcTemplate jdbc,
                              TaxFormTemplateRepository templates,
                              TaxFilingDraftRepository drafts) {
        this.jdbc = jdbc;
        this.templates = templates;
        this.drafts = drafts;
    }

    @Transactional
    public Long generate(Long draftId) {
        TaxFilingDraft draft = drafts.findById(draftId)
                .orElseThrow(() -> new BusinessException(ErrorCode.VALIDATION_FAILED,
                        "초안 %d 이 없습니다.".formatted(draftId)));
        TaxFormTemplate template = templates.findById(draft.getTemplateId())
                .orElseThrow(() -> new BusinessException(ErrorCode.VALIDATION_FAILED,
                        "서식 템플릿 %d 이 없습니다.".formatted(draft.getTemplateId())));

        Long businessId = draft.getBusinessId();
        LocalDate from = draft.getPeriodStart();
        LocalDate to = draft.getPeriodEnd();

        Map<String, CategoryTotal> byCategory = categoryTotals(businessId, from, to);
        SettlementTotal settlements = settlementTotals(businessId, from, to);
        Map<String, Object> excluded = excludedSummary(businessId, from, to);

        Map<String, Object> fields = buildFields(draft, template, byCategory, settlements);

        draft.complete(fields, excluded, reviewRate(businessId, from, to), Instant.now());
        log.info("초안 {} 집계 완료 — 사업장 {} · 항목 {}개 · 정산 {}건",
                draftId, businessId, fields.size(), settlements.count());
        return draftId;
    }

    // --- 조회 ----------------------------------------------------------------

    /**
     * 카테고리별 합계.
     *
     * <p><b>정산 대사에 연결된 입금 거래는 뺀다.</b> 그 거래의 금액은 수수료를 뗀 뒤의
     * 입금액이고, 매출액은 정산의 총매출에서 온다 — 둘을 다 더하면 매출이 두 번 계상된다.
     *
     * <p>증빙 건수는 별도 집계를 조인해 센다. {@code transaction_evidence} 를 바로 조인하면
     * 증빙이 둘인 거래가 두 줄이 되어 <b>금액 합계가 부풀어 오른다</b>.
     */
    private Map<String, CategoryTotal> categoryTotals(Long businessId, LocalDate from, LocalDate to) {
        String sql = """
                SELECT c.code                                                        AS code,
                       MAX(c.name)                                                   AS name,
                       COUNT(*)                                                      AS tx_count,
                       COALESCE(SUM(t.amount), 0)                                    AS amount,
                       COUNT(*) FILTER (WHERE t.tax_review_status = 'UNREVIEWED')    AS unreviewed,
                       COUNT(*) FILTER (WHERE e.n > 0)                               AS with_evidence
                  FROM core.transaction t
                  JOIN core.transaction_category c ON c.id = t.category_id
                  LEFT JOIN (SELECT transaction_id, COUNT(*) AS n
                               FROM core.transaction_evidence
                              GROUP BY transaction_id) e ON e.transaction_id = t.id
                 WHERE t.business_id = ?
                   AND t.transaction_date BETWEEN ? AND ?
                   AND t.classification_status = 'CONFIRMED'
                   AND t.tax_review_status IN ('EVIDENCE_CONFIRMED', 'EVIDENCE_MISSING', 'UNREVIEWED')
                   AND NOT EXISTS (SELECT 1 FROM core.settlement_transaction st
                                    WHERE st.transaction_id = t.id)
                 GROUP BY c.code
                """;
        Map<String, CategoryTotal> totals = new HashMap<>();
        jdbc.query(sql, rs -> {
            totals.put(rs.getString("code"), new CategoryTotal(
                    rs.getString("name"),
                    rs.getInt("tx_count"),
                    rs.getBigDecimal("amount"),
                    rs.getInt("unreviewed"),
                    rs.getInt("with_evidence")));
        }, businessId, from, to);
        return totals;
    }

    /**
     * 기간 안 정산 대사 합계.
     *
     * <p><b>귀속 기준일은 정산 종료일({@code sales_to})이다</b> <i>(2026-09-16 결정)</i>.
     * 기간에 걸친 정산을 날짜로 쪼개 배분하지 않는다는 뜻이다 — 한 정산이 두 초안에
     * 나뉘어 들어가면 어느 쪽에서도 총매출이 맞지 않는다. <b>나중에 바꾸려면</b> 여기와
     * 문서(구현현황 「정산 귀속 기준」)를 같이 고친다. 주(週) 단위 정산이 연말에 걸치는
     * 경우가 실제로 관측되면 일할 배분을 검토할 자리다.
     *
     * <p><b>{@code MISMATCHED}·{@code PENDING} 도 센다</b> <i>(2026-09-16 결정)</i>.
     * 대사가 안 맞았다는 것은 입금 확인이 덜 됐다는 뜻이지 매출이 없었다는 뜻이 아니다 —
     * 빼면 매출이 과소 계상된다. 대신 건수를 남겨 화면이 「대사 미완 3건 포함」을 말할 수
     * 있게 한다. <b>나중에 바꾸려면</b> 이 주석과 같은 문서 절을 함께 고친다.
     */
    private SettlementTotal settlementTotals(Long businessId, LocalDate from, LocalDate to) {
        String sql = """
                SELECT COUNT(*)                                          AS n,
                       COALESCE(SUM(gross_sales), 0)                     AS gross,
                       COALESCE(SUM(cancellation_amount), 0)             AS cancellations,
                       COALESCE(SUM(fee_amount), 0)                      AS fees,
                       COUNT(*) FILTER (WHERE status <> 'MATCHED')       AS unmatched
                  FROM core.settlement
                 WHERE business_id = ?
                   AND sales_to BETWEEN ? AND ?
                """;
        return jdbc.queryForObject(sql, (rs, i) -> new SettlementTotal(
                rs.getInt("n"),
                rs.getBigDecimal("gross"),
                rs.getBigDecimal("cancellations"),
                rs.getBigDecimal("fees"),
                rs.getInt("unmatched")), businessId, from, to);
    }

    /**
     * 항목에서 뺀 거래 (14.3 {@code excluded}).
     *
     * <p>{@code unclassified} 는 <b>명세에 없는 칸이다</b>. 집계 대상이 확정 분류 거래뿐이라,
     * 분류를 안 돌린 사업장은 초안이 거의 빈 채로 나온다 — 그것을 「매출이 0」으로 읽으면
     * 안 된다. 명세의 두 칸(개인 사용·확인 필요)에는 담을 자리가 없어 한 칸을 더 뒀다.
     */
    private Map<String, Object> excludedSummary(Long businessId, LocalDate from, LocalDate to) {
        // 개인 사용 — 검토 상태가 PERSONAL_SUSPECTED 이거나 카테고리가 PERSONAL 이면 제외다.
        // OR 로 묶어 한 거래가 둘 다여도 한 번만 세게 한다.
        Map<String, Object> personal = countAndSum("""
                SELECT COUNT(*) AS n, COALESCE(SUM(t.amount), 0) AS amount
                  FROM core.transaction t
                  LEFT JOIN core.transaction_category c ON c.id = t.category_id
                 WHERE t.business_id = ? AND t.transaction_date BETWEEN ? AND ?
                   AND t.classification_status = 'CONFIRMED'
                   AND (t.tax_review_status = 'PERSONAL_SUSPECTED' OR c.code = 'PERSONAL')
                """, businessId, from, to);

        Map<String, Object> needsReview = countAndSum("""
                SELECT COUNT(*) AS n, COALESCE(SUM(amount), 0) AS amount
                  FROM core.transaction
                 WHERE business_id = ? AND transaction_date BETWEEN ? AND ?
                   AND classification_status = 'CONFIRMED'
                   AND tax_review_status IN
                       ('BUSINESS_USE_UNCLEAR', 'DUPLICATE_SUSPECTED', 'EXPERT_REVIEW')
                """, businessId, from, to);

        Map<String, Object> unclassified = countAndSum("""
                SELECT COUNT(*) AS n, COALESCE(SUM(amount), 0) AS amount
                  FROM core.transaction
                 WHERE business_id = ? AND transaction_date BETWEEN ? AND ?
                   AND classification_status <> 'CONFIRMED'
                """, businessId, from, to);

        return Map.of(
                "personalSuspected", personal,
                "needsReview", needsReview,
                "unclassified", unclassified);
    }

    private Map<String, Object> countAndSum(String sql, Long businessId, LocalDate from, LocalDate to) {
        return jdbc.queryForObject(sql, (rs, i) -> Map.<String, Object>of(
                "count", rs.getInt("n"),
                "amount", rs.getBigDecimal("amount")), businessId, from, to);
    }

    /**
     * 생성 시점 검토 확정률 (14.3 {@code basedOnReviewRate}).
     *
     * <p>대상 거래가 하나도 없으면 1 이다 — 0 으로 두면 화면이 「검토 0%」라고 말하는데,
     * 검토할 것이 없었던 것과 검토를 안 한 것은 다르다.
     */
    private BigDecimal reviewRate(Long businessId, LocalDate from, LocalDate to) {
        return jdbc.queryForObject("""
                SELECT CASE WHEN COUNT(*) = 0 THEN 1.0
                            ELSE 1.0 - (COUNT(*) FILTER (WHERE tax_review_status = 'UNREVIEWED'))::numeric
                                       / COUNT(*)
                       END AS rate
                  FROM core.transaction
                 WHERE business_id = ? AND transaction_date BETWEEN ? AND ?
                   AND classification_status = 'CONFIRMED'
                """, BigDecimal.class, businessId, from, to)
                .setScale(4, RoundingMode.HALF_UP);
    }

    // --- 조립 ----------------------------------------------------------------

    /**
     * 서식 항목을 채운다.
     *
     * <p>순서가 중요하다 — 산식 항목({@code CALCULATED})은 앞 섹션의 값을 참조하므로
     * 선언 순서대로 돌면서 그때까지 계산된 값만 쓴다. 어긋나면
     * {@link FormulaEvaluator} 가 던진다.
     */
    private Map<String, Object> buildFields(TaxFilingDraft draft,
                                            TaxFormTemplate template,
                                            Map<String, CategoryTotal> byCategory,
                                            SettlementTotal settlements) {
        Map<String, Object> mapping = template.getCategoryMapping();
        Profile profile = profile(draft.getBusinessId());

        // 항목별로 어떤 카테고리가 모이는지 뒤집어 둔다.
        Map<String, List<String>> categoriesByField = new LinkedHashMap<>();
        for (var entry : mapping.entrySet()) {
            String fieldKey = asString(asMap(entry.getValue()).get("fieldKey"));
            if (fieldKey != null) {
                categoriesByField.computeIfAbsent(fieldKey, k -> new ArrayList<>()).add(entry.getKey());
            }
        }

        // 항목 금액에 넣지 않고 「얼마가 왜 빠졌는지」만 보여 줄 것들.
        // 설비·비품(EQUIPMENT)이 그렇다 — 자산 등록·감가상각 검토 대상이라 경비로 바로
        // 세지 않는다. 화면에 아무것도 안 뜨면 사장님은 그 돈이 누락된 줄 안다.
        Map<String, Pending> pendingByField = new LinkedHashMap<>();
        for (var entry : mapping.entrySet()) {
            Map<String, Object> rule = asMap(entry.getValue());
            String target = asString(rule.get("pendingFor"));
            CategoryTotal total = byCategory.get(entry.getKey());
            if (target == null || total == null) {
                continue;
            }
            pendingByField.merge(target,
                    new Pending(total.name(), total.count(), total.amount()),
                    Pending::plus);
        }

        Map<String, Object> fields = new LinkedHashMap<>();
        Map<String, BigDecimal> numeric = new LinkedHashMap<>();

        for (Map<String, Object> section : template.getSections()) {
            for (Object raw : asList(section.get("fields"))) {
                Map<String, Object> definition = asMap(raw);
                String fieldKey = asString(definition.get("fieldKey"));
                String source = asString(definition.get("source"));

                Map<String, Object> field = new LinkedHashMap<>();
                field.put("source", source);

                switch (source) {
                    // 사용자가 직접 입력하고 서버에 저장하지 않는다. 키만 두고 값은 항상 null.
                    case "USER_SENSITIVE" -> {
                        field.put("value", null);
                        field.put("sensitive", true);
                        field.put("status", "EMPTY");
                    }
                    case "AUTO_PROFILE" -> {
                        Object value = profile.get(fieldKey);
                        field.put("value", value);
                        field.put("status", value == null ? "EMPTY" : "FILLED");
                        if ("BIZ_REG_NO".equals(fieldKey)) {
                            field.put("reviewNote", "마스킹된 값입니다. 신고서에는 전체 번호를 적으세요.");
                        }
                    }
                    case "REFERENCE_HOMETAX" -> {
                        Object value = hometax(draft, fieldKey);
                        field.put("value", value);
                        field.put("status", value == null ? "EMPTY" : "FILLED");
                    }
                    case "CALCULATED" -> {
                        if (definition.containsKey("fixedValue")) {
                            field.put("value", definition.get("fixedValue"));
                            field.put("status", "FILLED");
                        } else if (definition.containsKey("formula")) {
                            BigDecimal value = FormulaEvaluator.evaluate(
                                    asString(definition.get("formula")), numeric);
                            value = money(value);
                            numeric.put(fieldKey, value);
                            field.put("value", value);
                            field.put("status", "FILLED");
                        } else {
                            // TAX_PERIOD 처럼 산식이 아닌 계산값.
                            Object value = calculated(draft, fieldKey);
                            field.put("value", value);
                            field.put("status", value == null ? "EMPTY" : "FILLED");
                        }
                    }
                    // 자동 계산하지 않는다. 기본값(세무조정 0)이 있으면 그것을 넣는다.
                    case "USER_CONFIRM" -> {
                        Object value = definition.get("defaultValue");
                        numeric.put(fieldKey, toDecimal(value));
                        field.put("value", value);
                        field.put("status", value == null ? "EMPTY" : "FILLED");
                        copyNote(definition, field);
                    }
                    case "AUTO_SETTLEMENT", "AUTO_TRANSACTION" ->
                            fillFromTransactions(fieldKey, field, numeric,
                                    categoriesByField.getOrDefault(fieldKey, List.of()),
                                    mapping, byCategory, settlements);
                    default -> throw new IllegalStateException(
                            "서식 정의에 모르는 출처가 있습니다: " + source + " (" + fieldKey + ")");
                }

                attachPending(fieldKey, field, pendingByField.get(fieldKey));
                fields.put(fieldKey, field);
            }
        }
        return fields;
    }

    /**
     * 거래·정산에서 항목 금액을 만든다.
     *
     * <p><b>정산 보정이 여기서 일어난다.</b> ⑪ 매출액은 정산의 총매출에서 취소를 뺀 값이고,
     * 그 정산의 수수료는 거래로 존재하지 않으므로 ㉝ 지급수수료에 더해 준다 — 명세가
     * 「정산 대사(14.1)의 <b>총매출·수수료</b>로 집계」라고 적은 것이 이것이다. 수수료를
     * 빠뜨리면 경비가 그만큼 적어 소득금액이 과대 계상된다.
     *
     * <p>{@code EQUIPMENT} 처럼 {@code handling} 이 붙은 카테고리는 여기 오지 않는다
     * ({@code fieldKey} 가 없어 {@code categoriesByField} 에 안 들어간다).
     */
    private void fillFromTransactions(String fieldKey,
                                      Map<String, Object> field,
                                      Map<String, BigDecimal> numeric,
                                      List<String> categories,
                                      Map<String, Object> mapping,
                                      Map<String, CategoryTotal> byCategory,
                                      SettlementTotal settlements) {
        BigDecimal amount = BigDecimal.ZERO;
        int txCount = 0;
        int unreviewed = 0;
        int withEvidence = 0;
        boolean needsReview = false;
        List<String> notes = new ArrayList<>();
        boolean settlementPreferred = false;

        for (String category : categories) {
            Map<String, Object> rule = asMap(mapping.get(category));
            if (Boolean.TRUE.equals(rule.get("settlementPreferred"))) {
                settlementPreferred = true;
            }
            CategoryTotal total = byCategory.get(category);
            if (total == null) {
                continue;
            }
            amount = amount.add(total.amount());
            txCount += total.count();
            unreviewed += total.unreviewed();
            withEvidence += total.withEvidence();
            if (Boolean.TRUE.equals(rule.get("needsReview"))) {
                needsReview = true;
            }
            String note = asString(rule.get("note"));
            if (note != null && !notes.contains(note)) {
                notes.add(note);
            }
        }

        Map<String, Object> basis = new LinkedHashMap<>();
        basis.put("transactionCount", txCount);
        basis.put("evidenceCount", withEvidence);
        basis.put("unreviewedCount", unreviewed);

        if (settlementPreferred && settlements.count() > 0) {
            // 매출액 = 총매출 − 취소. 연결된 입금 거래는 조회에서 이미 빠져 있다.
            amount = amount.add(settlements.gross().subtract(settlements.cancellations()));
            basis.put("settlementCount", settlements.count());
            if (settlements.unmatched() > 0) {
                basis.put("unmatchedSettlementCount", settlements.unmatched());
                notes.add("대사가 맞지 않은 정산 %d건이 포함돼 있습니다. 입금 확인이 필요합니다."
                        .formatted(settlements.unmatched()));
            }
        } else if (settlementPreferred && txCount > 0) {
            notes.add("정산 대사가 없어 입금액으로 집계했습니다. 수수료 차감 전 금액인지 확인하세요.");
            needsReview = true;
        }

        // 정산 수수료는 출금 거래로 찍히지 않는다. 총매출을 매출로 잡았으면 여기서 더해야
        // 경비가 맞는다.
        if ("COMMISSION".equals(fieldKey) && settlements.count() > 0) {
            amount = amount.add(settlements.fees());
            basis.put("settlementFeeIncluded", true);
            notes.add("정산에서 차감된 수수료가 포함돼 있습니다. 출금 거래로는 찍히지 않는 금액입니다.");
        }

        amount = money(amount);
        numeric.put(fieldKey, amount);
        field.put("value", amount);
        field.put("status", needsReview ? "NEEDS_REVIEW" : "FILLED");
        field.put("basisSummary", basis);
        if (!notes.isEmpty()) {
            field.put("reviewNote", String.join(" ", notes));
        }
    }

    /**
     * 항목 금액에서 뺀 금액을 그 항목에 붙여 준다 (14.3 {@code pendingAmount}).
     *
     * <p><b>금액을 더하지 않는다.</b> 설비 구입을 경비로 바로 세면 감가상각을 건너뛴 신고가
     * 되고, 아무 데도 안 보여 주면 사장님은 그 돈이 누락된 줄 안다. 그 사이에 두는 칸이다.
     */
    private void attachPending(String fieldKey, Map<String, Object> field, Pending pending) {
        if (pending == null) {
            return;
        }
        field.put("pendingAmount", money(pending.amount()));
        String note = "%s %d건(%,d원)은 자산 등록·감가상각 검토 대상이라 항목 금액에 넣지 않았습니다."
                .formatted(pending.categoryName(), pending.count(), pending.amount().longValue());
        Object existing = field.get("reviewNote");
        field.put("reviewNote", existing == null ? note : note + " " + existing);
        // 상태는 건드리지 않는다. 보류 금액은 덧붙는 정보이지 항목의 상태가 아니다 —
        // ㉛ 감가상각비는 USER_CONFIRM 이라 이미 EMPTY 이고, 그것이 명세가 보여 주는 모양이다.
    }

    private void copyNote(Map<String, Object> definition, Map<String, Object> field) {
        String note = asString(definition.get("note"));
        if (note != null) {
            field.put("reviewNote", note);
        }
    }

    private Object calculated(TaxFilingDraft draft, String fieldKey) {
        if ("TAX_PERIOD".equals(fieldKey)) {
            return draft.getPeriodStart() + " ~ " + draft.getPeriodEnd();
        }
        return null;
    }

    private Object hometax(TaxFilingDraft draft, String fieldKey) {
        Map<String, Object> reference = draft.getHometaxReference();
        if (reference == null) {
            return null;
        }
        return "INDUSTRY_CODE".equals(fieldKey) ? reference.get("industryCode") : null;
    }

    /**
     * 사업장·사용자 프로필.
     *
     * <p><b>사업자등록번호는 마스킹 값을 쓴다.</b> 원본은 암호문으로 저장돼 있고, 풀어서
     * 초안 행에 적으면 평문이 DB 에 남는다 — 저장소가 그 칸을 일부러 암호화한 취지에서
     * 벗어난다. 신고서에 적을 전체 번호는 사장님이 알고 있고 홈택스도 이미 갖고 있다.
     */
    private Profile profile(Long businessId) {
        return jdbc.queryForObject("""
                SELECT b.registration_number_masked AS reg_no,
                       b.industry_code              AS industry,
                       u.name                       AS user_name,
                       u.phone                      AS phone
                  FROM core.business b
                  JOIN core.users u ON u.id = b.owner_user_id
                 WHERE b.id = ?
                """, (rs, i) -> new Profile(
                rs.getString("reg_no"), rs.getString("industry"),
                rs.getString("user_name"), rs.getString("phone")), businessId);
    }

    // --- 잡동사니 -------------------------------------------------------------

    /**
     * 금액을 원 단위 정수로 다듬는다.
     *
     * <p>{@code NUMERIC(19,4)} 합계는 {@code 3400000.0000} 으로 나온다. 그대로 내보내면
     * 화면과 내보내기 시트에 소수점이 찍힌다 — 신고서에 적을 금액은 원 단위 정수다.
     * {@code stripTrailingZeros} 만 쓰면 {@code 3.4E+6} 이 되므로 음수 스케일을 되돌린다.
     */
    private static BigDecimal money(BigDecimal value) {
        if (value == null) {
            return null;
        }
        BigDecimal stripped = value.stripTrailingZeros();
        return stripped.scale() < 0 ? stripped.setScale(0) : stripped;
    }

    private static BigDecimal toDecimal(Object value) {
        if (value == null) {
            return BigDecimal.ZERO;
        }
        return value instanceof BigDecimal decimal ? decimal : new BigDecimal(value.toString());
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

    private record CategoryTotal(String name, int count, BigDecimal amount,
                                 int unreviewed, int withEvidence) {
    }

    private record SettlementTotal(int count, BigDecimal gross, BigDecimal cancellations,
                                   BigDecimal fees, int unmatched) {
    }

    private record Pending(String categoryName, int count, BigDecimal amount) {

        Pending plus(Pending other) {
            return new Pending(categoryName + "·" + other.categoryName(),
                    count + other.count(), amount.add(other.amount()));
        }
    }

    private record Profile(String registrationNumberMasked, String industryCode,
                           String userName, String phone) {

        Object get(String fieldKey) {
            return switch (fieldKey) {
                case "BIZ_REG_NO" -> registrationNumberMasked;
                case "INDUSTRY" -> industryCode;
                case "NAME" -> userName;
                case "PHONE" -> phone;
                default -> null;
            };
        }
    }
}
