package com.jangoyebo.core.export;

import com.jangoyebo.core.transaction.TaxReviewStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 거래에서 나오는 시트 네 장 (API 14.2 — 02·03·06·07).
 *
 * <p>네 장이 한 클래스에 있는 것은 <b>모두 같은 덩어리를 다르게 자르기 때문이다</b> —
 * 기간 안의 거래와 그 증빙 연결. 자르는 기준만 다르다.
 *
 * <p><b>검토 상태는 한글 라벨로 쓴다.</b> 받는 쪽이 세무대리인이라
 * {@code BUSINESS_USE_UNCLEAR} 를 그대로 내보낼 수 없다.
 */
@Component
public class TransactionSheetBuilder {

    private static final ZoneId KST = ZoneId.of("Asia/Seoul");
    private static final DateTimeFormatter STAMP =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(KST);

    /** 모든 시트 아래에 붙는다 — 명세가 기준일·대상 기간·개인정보 고지를 요구한다. */
    private static final String NOTE =
            "이 자료는 신고 준비용이며 세액을 계산하거나 신고를 대신하지 않습니다. "
                    + "개인정보가 포함되어 있으니 전달 대상과 보관 기간을 확인하십시오.";

    private final JdbcTemplate jdbc;

    public TransactionSheetBuilder(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public List<TableDocument> build(Long businessId, LocalDate from, LocalDate to,
                                     ZonedDateTime generatedAt) {
        String subtitle = "대상 기간 %s ~ %s · 기준일 %s (KST)"
                .formatted(from, to, STAMP.format(generatedAt));
        return List.of(
                accountLedger(businessId, from, to, subtitle),
                expenseCandidates(businessId, from, to, subtitle),
                gaps(businessId, from, to, subtitle),
                evidenceLinks(businessId, from, to, subtitle));
    }

    // --- 02 사업용계좌 거래내역 -----------------------------------------------

    /** 거를 것이 없다. 분류도 검토도 보지 않고 기간 안 거래를 전부 적는다. */
    private TableDocument accountLedger(Long businessId, LocalDate from, LocalDate to,
                                        String subtitle) {
        List<List<Object>> rows = jdbc.query("""
                SELECT t.transaction_date, a.name AS account_name, a.account_number_masked,
                       t.flow_type, t.amount, t.description, t.counterparty_name,
                       c.name AS category_name, t.tax_review_status,
                       EXISTS (SELECT 1 FROM core.transaction_evidence te
                                WHERE te.transaction_id = t.id) AS has_evidence
                  FROM core.transaction t
                  JOIN core.account a ON a.id = t.account_id
                  LEFT JOIN core.transaction_category c ON c.id = t.category_id
                 WHERE t.business_id = ? AND t.transaction_date BETWEEN ? AND ?
                 ORDER BY t.transaction_date, t.id
                """, (rs, i) -> List.<Object>of(
                        rs.getString("transaction_date"),
                        "%s (%s)".formatted(rs.getString("account_name"),
                                rs.getString("account_number_masked")),
                        flow(rs.getString("flow_type")),
                        rs.getBigDecimal("amount"),
                        text(rs.getString("description")),
                        text(rs.getString("counterparty_name")),
                        text(rs.getString("category_name")),
                        TaxReviewStatus.labelOf(rs.getString("tax_review_status")),
                        rs.getBoolean("has_evidence") ? "연결됨" : ""),
                businessId, from, to);

        return new TableDocument("02_사업용계좌_거래내역.xlsx", "거래내역",
                "사업용계좌 거래내역", subtitle,
                List.of(TableDocument.Column.of("거래일자", 12),
                        TableDocument.Column.of("계좌", 24),
                        TableDocument.Column.of("구분", 8),
                        TableDocument.Column.money("금액"),
                        TableDocument.Column.of("적요", 34),
                        TableDocument.Column.of("거래처", 20),
                        TableDocument.Column.of("분류", 16),
                        TableDocument.Column.of("검토 상태", 20),
                        TableDocument.Column.of("증빙", 10)),
                List.of(TableDocument.Section.of(null, null, rows)), NOTE);
    }

    // --- 03 필요경비 후보 명세서 ---------------------------------------------

    /**
     * <b>이름을 「후보」로 고정한다.</b> 「필요경비 명세서」라고 쓰면 우리가 경비를 확정한 것이
     * 된다 — 계좌에서 나갔다는 사실만으로 인정 여부는 정해지지 않고 그 판단은 범위 밖이다
     * (SRS 1.3 · 도메인 규칙 4).
     *
     * <p><b>개인 사용 의심분을 지우지 않는다.</b> 빼는 대신 구획을 갈라 남긴다 — 삭제하면
     * 세무대리인이 그 판단을 다시 볼 근거가 사라진다.
     *
     * <p><b>출금 한 건은 반드시 네 구획 중 하나에 들어간다.</b> 하나의 {@code CASE} 로 갈라
     * 어디에도 못 끼는 거래가 생기지 않게 한다 — 조건을 따로 쓰면 {@code ASSET_REVIEW} 처럼
     * 아무도 안 쓰는 값 하나가 시트에서 통째로 사라진다.
     */
    private TableDocument expenseCandidates(Long businessId, LocalDate from, LocalDate to,
                                            String subtitle) {
        Map<String, List<List<Object>>> byBucket = new LinkedHashMap<>();
        jdbc.query("""
                SELECT CASE
                         WHEN t.tax_review_status = 'PERSONAL_SUSPECTED' OR c.code = 'PERSONAL'
                              THEN 'PERSONAL'
                         WHEN t.classification_status <> 'CONFIRMED' THEN 'UNCLASSIFIED'
                         WHEN t.tax_review_status IN
                              ('EVIDENCE_CONFIRMED', 'EVIDENCE_MISSING', 'UNREVIEWED')
                              THEN 'CANDIDATE'
                         ELSE 'NEEDS_REVIEW'
                       END                                             AS bucket,
                       t.transaction_date, t.amount, t.description, t.counterparty_name,
                       c.name AS category_name, t.tax_review_status,
                       EXISTS (SELECT 1 FROM core.transaction_evidence te
                                WHERE te.transaction_id = t.id)        AS has_evidence
                  FROM core.transaction t
                  LEFT JOIN core.transaction_category c ON c.id = t.category_id
                 WHERE t.business_id = ? AND t.transaction_date BETWEEN ? AND ?
                   AND t.flow_type = 'WITHDRAWAL'
                 ORDER BY t.transaction_date, t.id
                """, rs -> {
                    byBucket.computeIfAbsent(rs.getString("bucket"), key -> new ArrayList<>())
                            .add(List.of(
                                    rs.getString("transaction_date"),
                                    text(rs.getString("category_name")),
                                    rs.getBigDecimal("amount"),
                                    text(rs.getString("description")),
                                    text(rs.getString("counterparty_name")),
                                    rs.getBoolean("has_evidence") ? "연결됨" : "",
                                    TaxReviewStatus.labelOf(rs.getString("tax_review_status"))));
                }, businessId, from, to);

        List<TableDocument.Section> sections = List.of(
                section(byBucket, "CANDIDATE", "필요경비 후보",
                        "증빙 확인 완료·증빙 누락·미검토 거래입니다. 인정 여부는 확정되지 않았습니다."),
                section(byBucket, "NEEDS_REVIEW", "확인 필요 — 후보 금액에 넣지 않음",
                        "사업 관련성·중복 등록·전문가 확인이 필요하다고 표시된 거래입니다."),
                section(byBucket, "PERSONAL", "개인 사용 가능성 — 경비에서 제외",
                        "지우지 않고 남깁니다. 판단 근거를 확인할 수 있어야 합니다."),
                section(byBucket, "UNCLASSIFIED", "미분류 — 집계에 넣지 않음",
                        "분류가 확정되지 않아 어느 항목에도 넣지 않았습니다. 0원이라는 뜻이 아닙니다."));

        return new TableDocument("03_필요경비_후보명세서.xlsx", "필요경비 후보",
                "필요경비 후보 명세서", subtitle,
                List.of(TableDocument.Column.of("거래일자", 12),
                        TableDocument.Column.of("분류", 16),
                        TableDocument.Column.money("금액"),
                        TableDocument.Column.of("적요", 34),
                        TableDocument.Column.of("거래처", 20),
                        TableDocument.Column.of("증빙", 10),
                        TableDocument.Column.of("검토 상태", 20)),
                sections, NOTE);
    }

    // --- 06 증빙누락·확인필요 -------------------------------------------------

    /** 내보내기 전에 사장님이 손댈 곳만 모은다. SC-07-6 의 「미확인」이 가리키는 것도 이쪽이다. */
    private TableDocument gaps(Long businessId, LocalDate from, LocalDate to, String subtitle) {
        String columns = """
                       t.transaction_date, t.amount, t.description, t.counterparty_name,
                       c.name AS category_name, t.tax_review_status
                """;
        List<List<Object>> missing = jdbc.query("""
                SELECT %s
                  FROM core.transaction t
                  LEFT JOIN core.transaction_category c ON c.id = t.category_id
                 WHERE t.business_id = ? AND t.transaction_date BETWEEN ? AND ?
                   AND t.flow_type = 'WITHDRAWAL'
                   AND NOT EXISTS (SELECT 1 FROM core.transaction_evidence te
                                    WHERE te.transaction_id = t.id)
                 ORDER BY t.amount DESC, t.id
                """.formatted(columns), this::gapRow, businessId, from, to);

        List<List<Object>> needsReview = jdbc.query("""
                SELECT %s
                  FROM core.transaction t
                  LEFT JOIN core.transaction_category c ON c.id = t.category_id
                 WHERE t.business_id = ? AND t.transaction_date BETWEEN ? AND ?
                   AND t.classification_status = 'CONFIRMED'
                   AND t.tax_review_status IN
                       ('BUSINESS_USE_UNCLEAR', 'DUPLICATE_SUSPECTED', 'EXPERT_REVIEW')
                 ORDER BY t.amount DESC, t.id
                """.formatted(columns), this::gapRow, businessId, from, to);

        return new TableDocument("06_증빙누락_확인필요.xlsx", "증빙누락·확인필요",
                "증빙 누락 · 확인 필요 거래", subtitle,
                List.of(TableDocument.Column.of("거래일자", 12),
                        TableDocument.Column.of("분류", 16),
                        TableDocument.Column.money("금액"),
                        TableDocument.Column.of("적요", 34),
                        TableDocument.Column.of("거래처", 20),
                        TableDocument.Column.of("검토 상태", 20)),
                List.of(TableDocument.Section.of("증빙 누락",
                                "증빙이 연결되지 않은 출금입니다. 금액이 큰 것부터 적었습니다.", missing),
                        TableDocument.Section.of("확인 필요",
                                "사업 관련성·중복 등록·전문가 확인이 필요하다고 표시된 거래입니다.",
                                needsReview)),
                NOTE);
    }

    private List<Object> gapRow(java.sql.ResultSet rs, int index) throws java.sql.SQLException {
        return List.of(rs.getString("transaction_date"),
                text(rs.getString("category_name")),
                rs.getBigDecimal("amount"),
                text(rs.getString("description")),
                text(rs.getString("counterparty_name")),
                TaxReviewStatus.labelOf(rs.getString("tax_review_status")));
    }

    // --- 07 거래증빙 연결목록 -------------------------------------------------

    /**
     * <b>지운 증빙은 빼고 거래는 남긴다.</b> 소프트 삭제된 증빙(V18)을 목록에 넣으면 세무대리인이
     * 없는 파일을 찾는다. 거래에 증빙이 둘이면 두 줄이 되는데, 여기는 합계를 내지 않으므로
     * 그래야 맞다 — 집계하는 쪽(14.3)은 반대로 거래당 한 번만 센다.
     */
    private TableDocument evidenceLinks(Long businessId, LocalDate from, LocalDate to,
                                        String subtitle) {
        List<List<Object>> rows = jdbc.query("""
                SELECT t.transaction_date, t.amount, t.description, t.tax_review_status,
                       e.evidence_type, e.original_filename, e.issued_on, te.match_status
                  FROM core.transaction_evidence te
                  JOIN core.transaction t ON t.id = te.transaction_id
                  JOIN core.evidence e    ON e.id = te.evidence_id
                 WHERE t.business_id = ? AND t.transaction_date BETWEEN ? AND ?
                   AND e.deleted_at IS NULL
                 ORDER BY t.transaction_date, t.id, e.id
                """, (rs, i) -> List.<Object>of(
                        rs.getString("transaction_date"),
                        rs.getBigDecimal("amount"),
                        text(rs.getString("description")),
                        evidenceType(rs.getString("evidence_type")),
                        text(rs.getString("original_filename")),
                        text(rs.getString("issued_on")),
                        TaxReviewStatus.labelOf(rs.getString("tax_review_status"))),
                businessId, from, to);

        return new TableDocument("07_거래증빙_연결목록.xlsx", "거래증빙 연결",
                "거래 · 증빙 연결 목록", subtitle,
                List.of(TableDocument.Column.of("거래일자", 12),
                        TableDocument.Column.money("금액"),
                        TableDocument.Column.of("적요", 34),
                        TableDocument.Column.of("증빙 종류", 16),
                        TableDocument.Column.of("증빙 파일", 30),
                        TableDocument.Column.of("증빙 발행일", 14),
                        TableDocument.Column.of("검토 상태", 20)),
                List.of(TableDocument.Section.of(null, null, rows)), NOTE);
    }

    // --- 공통 ----------------------------------------------------------------

    private static TableDocument.Section section(Map<String, List<List<Object>>> byBucket,
                                                 String bucket, String label, String note) {
        return TableDocument.Section.of(label, note,
                byBucket.getOrDefault(bucket, List.of()));
    }

    private static String flow(String flowType) {
        return "DEPOSIT".equals(flowType) ? "입금" : "출금";
    }

    private static String text(String value) {
        return value == null ? "" : value;
    }

    private static String evidenceType(String code) {
        return switch (code) {
            case "RECEIPT" -> "영수증";
            case "TAX_INVOICE" -> "세금계산서";
            case "INVOICE" -> "계산서";
            case "CASH_RECEIPT" -> "현금영수증";
            case "SETTLEMENT_STATEMENT" -> "정산 명세서";
            case "CONTRACT" -> "계약서";
            case "PAYROLL" -> "급여 대장";
            default -> "기타";
        };
    }
}
