package com.jangoyebo.core.export;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;

/**
 * 매출·정산·고정비 시트 세 장 (API 14.2 — 01·04·05).
 *
 * <p><b>매출의 기준을 14.3 신고서 초안과 같게 맞춘다.</b> 두 산출물이 같은 기간에 다른
 * 매출을 말하면 세무대리인은 우리 집계를 통째로 버린다.
 *
 * <ul>
 *   <li><b>매출액 = 정산 총매출 − 취소·환불.</b> 수수료는 빼지 않는다 — 그것은 매출 차감이
 *       아니라 필요경비(지급수수료)다(신고서 초안 ⑪·지급수수료 항목).
 *   <li><b>정산에 연결된 입금은 따로 세지 않는다.</b> 그 입금액은 수수료를 뗀 뒤의 돈이라
 *       총매출과 함께 더하면 매출이 두 번 계상된다.
 *   <li><b>정산의 귀속 기준일은 종료일({@code sales_to})이다</b>(2026-09-16 결정). 기간에
 *       걸친 정산을 날짜로 쪼개 배분하지 않는다.
 * </ul>
 */
@Component
public class SummarySheetBuilder {

    private static final ZoneId KST = ZoneId.of("Asia/Seoul");
    private static final DateTimeFormatter STAMP =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(KST);

    private static final String NOTE =
            "이 자료는 신고 준비용이며 세액을 계산하거나 신고를 대신하지 않습니다. "
                    + "개인정보가 포함되어 있으니 전달 대상과 보관 기간을 확인하십시오.";

    /** 매출로 보는 입금 분류. 대출 실행·대표자 자금 투입·기타 입금은 매출이 아니다. */
    private static final String SALES_CATEGORIES =
            "'CARD_SETTLEMENT', 'PLATFORM_SETTLEMENT', 'CASH_SALES', 'TRANSFER_SALES'";

    /**
     * 고정비로 보는 분류.
     *
     * <p><b>안전선 추천값(12.1)의 네 종과 일부러 다르다.</b> 저쪽은 「당장 못 미루는 돈」의
     * 바닥선이라 급여·임차료 중심이고, 여기는 세무대리인이 훑어볼 <b>매달 같은 자리에서 나가는
     * 경비</b>다.
     *
     * <p><b>대출 상환은 넣지 않는다.</b> 매달 나가지만 원금 상환은 경비가 아니다 — 경비가 되는
     * 것은 이자뿐이고 우리 분류는 둘을 가르지 않는다. 넣으면 세무대리인이 원금까지 경비로
     * 볼 위험이 있다.
     */
    private static final String FIXED_COST_CATEGORIES =
            "'RENT', 'PAYROLL', 'SOCIAL_INSURANCE', 'INSURANCE', 'UTILITIES', "
                    + "'TELECOM', 'SUBSCRIPTION'";

    private final JdbcTemplate jdbc;

    public SummarySheetBuilder(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public List<TableDocument> build(Long businessId, LocalDate from, LocalDate to,
                                     ZonedDateTime generatedAt) {
        String subtitle = "대상 기간 %s ~ %s · 기준일 %s (KST)"
                .formatted(from, to, STAMP.format(generatedAt));
        return List.of(
                salesSummary(businessId, from, to, subtitle),
                settlementReconciliation(businessId, from, to, subtitle),
                fixedCosts(businessId, from, to, subtitle));
    }

    // --- 01 연간 매출요약 -----------------------------------------------------

    private TableDocument salesSummary(Long businessId, LocalDate from, LocalDate to,
                                       String subtitle) {
        Map<String, BigDecimal[]> byMonth = new LinkedHashMap<>();
        TreeSet<String> months = new TreeSet<>();

        jdbc.query("""
                SELECT to_char(sales_to, 'YYYY-MM')          AS month,
                       COALESCE(SUM(gross_sales), 0)         AS gross,
                       COALESCE(SUM(cancellation_amount), 0) AS cancellations,
                       COALESCE(SUM(fee_amount), 0)          AS fees
                  FROM core.settlement
                 WHERE business_id = ? AND sales_to BETWEEN ? AND ?
                 GROUP BY 1
                """, rs -> {
                    String month = rs.getString("month");
                    months.add(month);
                    slot(byMonth, month)[0] = rs.getBigDecimal("gross");
                    slot(byMonth, month)[1] = rs.getBigDecimal("cancellations");
                    slot(byMonth, month)[2] = rs.getBigDecimal("fees");
                }, businessId, from, to);

        jdbc.query("""
                SELECT to_char(t.transaction_date, 'YYYY-MM') AS month,
                       COALESCE(SUM(t.amount), 0)             AS amount
                  FROM core.transaction t
                  JOIN core.transaction_category c ON c.id = t.category_id
                 WHERE t.business_id = ? AND t.transaction_date BETWEEN ? AND ?
                   AND t.flow_type = 'DEPOSIT'
                   AND c.code IN (%s)
                   AND NOT EXISTS (SELECT 1 FROM core.settlement_transaction st
                                    WHERE st.transaction_id = t.id)
                 GROUP BY 1
                """.formatted(SALES_CATEGORIES), rs -> {
                    String month = rs.getString("month");
                    months.add(month);
                    slot(byMonth, month)[3] = rs.getBigDecimal("amount");
                }, businessId, from, to);

        List<List<Object>> rows = new ArrayList<>();
        BigDecimal[] total = new BigDecimal[4];
        java.util.Arrays.fill(total, BigDecimal.ZERO);
        for (String month : months) {
            BigDecimal[] values = slot(byMonth, month);
            for (int i = 0; i < 4; i++) {
                total[i] = total[i].add(values[i]);
            }
            rows.add(monthRow(month, values));
        }
        if (!rows.isEmpty()) {
            rows.add(monthRow("합계", total));
        }

        return new TableDocument("01_연간_매출요약.xlsx", "매출요약",
                "연간 매출 요약", subtitle,
                List.of(TableDocument.Column.of("월", 12),
                        TableDocument.Column.money("정산 총매출"),
                        TableDocument.Column.money("취소·환불"),
                        TableDocument.Column.money("정산 미연결 입금"),
                        TableDocument.Column.money("매출액"),
                        TableDocument.Column.money("정산 수수료")),
                List.of(TableDocument.Section.of(null,
                        "매출액 = 정산 총매출 − 취소·환불 + 정산 미연결 입금입니다. "
                                + "정산 수수료는 매출에서 빼지 않고 필요경비(지급수수료)로 갑니다 "
                                + "— 신고서 초안과 같은 기준입니다.", rows)),
                NOTE);
    }

    private static List<Object> monthRow(String label, BigDecimal[] values) {
        BigDecimal sales = values[0].subtract(values[1]).add(values[3]);
        return List.of(label, values[0], values[1], values[3], sales, values[2]);
    }

    private static BigDecimal[] slot(Map<String, BigDecimal[]> byMonth, String month) {
        return byMonth.computeIfAbsent(month, key -> {
            BigDecimal[] values = new BigDecimal[4];
            java.util.Arrays.fill(values, BigDecimal.ZERO);
            return values;
        });
    }

    // --- 04 플랫폼별 정산대사표 ----------------------------------------------

    /**
     * <b>대사가 안 맞은 정산도 적는다.</b> 맞지 않았다는 것은 입금 확인이 덜 됐다는 뜻이지
     * 매출이 없었다는 뜻이 아니다 — 빼면 매출이 과소 계상된다(14.3 과 같은 판단).
     */
    private TableDocument settlementReconciliation(Long businessId, LocalDate from, LocalDate to,
                                                   String subtitle) {
        List<List<Object>> rows = jdbc.query("""
                SELECT s.platform_name, s.sales_from, s.sales_to, s.gross_sales,
                       s.cancellation_amount, s.fee_amount, s.status,
                       COALESCE((SELECT SUM(st.allocated_amount)
                                   FROM core.settlement_transaction st
                                  WHERE st.settlement_id = s.id), 0) AS deposited
                  FROM core.settlement s
                 WHERE s.business_id = ? AND s.sales_to BETWEEN ? AND ?
                 ORDER BY s.sales_to, s.id
                """, (rs, i) -> {
                    BigDecimal expected = rs.getBigDecimal("gross_sales")
                            .subtract(rs.getBigDecimal("cancellation_amount"))
                            .subtract(rs.getBigDecimal("fee_amount"));
                    BigDecimal deposited = rs.getBigDecimal("deposited");
                    return List.<Object>of(
                            rs.getString("platform_name"),
                            "%s ~ %s".formatted(rs.getString("sales_from"), rs.getString("sales_to")),
                            rs.getBigDecimal("gross_sales"),
                            rs.getBigDecimal("cancellation_amount"),
                            rs.getBigDecimal("fee_amount"),
                            expected,
                            deposited,
                            deposited.subtract(expected),
                            settlementStatus(rs.getString("status")));
                }, businessId, from, to);

        return new TableDocument("04_플랫폼별_정산대사표.xlsx", "정산대사",
                "플랫폼별 정산 대사표", subtitle,
                List.of(TableDocument.Column.of("플랫폼", 18),
                        TableDocument.Column.of("판매 기간", 24),
                        TableDocument.Column.money("총매출"),
                        TableDocument.Column.money("취소·환불"),
                        TableDocument.Column.money("수수료"),
                        TableDocument.Column.money("받아야 할 돈"),
                        TableDocument.Column.money("실제 입금"),
                        TableDocument.Column.money("차이"),
                        TableDocument.Column.of("상태", 16)),
                List.of(TableDocument.Section.of(null,
                        "받아야 할 돈 = 총매출 − 취소·환불 − 수수료입니다. "
                                + "대사가 맞지 않은 건도 뺐다가 매출이 과소 계상되지 않도록 그대로 적습니다.",
                        rows)),
                NOTE);
    }

    private static String settlementStatus(String status) {
        return switch (status) {
            case "MATCHED" -> "일치";
            case "MISMATCHED" -> "불일치";
            default -> "입금 미확인";
        };
    }

    // --- 05 고정비·반복지출 ---------------------------------------------------

    /**
     * <b>반복 패턴(예측 산출물)을 쓰지 않는다.</b> 세무대리인이 보는 것은 실제로 나간 돈이지
     * 우리가 추정한 다음 예정일이 아니다. 그리고 그 표는 {@code forecast} 스키마라
     * {@code core} 가 직접 읽으면 모듈 경계를 넘는다.
     *
     * <p><b>확정 분류만 센다.</b> 제안 상태의 분류가 틀려 있으면 고정비가 부풀고, 그 숫자가
     * 신고 자료로 나간다.
     */
    private TableDocument fixedCosts(Long businessId, LocalDate from, LocalDate to,
                                     String subtitle) {
        record Line(String month, String category, int count, BigDecimal amount) {
        }
        List<Line> lines = jdbc.query("""
                SELECT to_char(t.transaction_date, 'YYYY-MM') AS month,
                       c.name                                 AS category_name,
                       COUNT(*)                               AS n,
                       COALESCE(SUM(t.amount), 0)             AS amount
                  FROM core.transaction t
                  JOIN core.transaction_category c ON c.id = t.category_id
                 WHERE t.business_id = ? AND t.transaction_date BETWEEN ? AND ?
                   AND t.flow_type = 'WITHDRAWAL'
                   AND t.classification_status = 'CONFIRMED'
                   AND t.tax_review_status <> 'PERSONAL_SUSPECTED'
                   AND c.code IN (%s)
                 GROUP BY 1, 2
                 ORDER BY 1, 2
                """.formatted(FIXED_COST_CATEGORIES),
                (rs, i) -> new Line(rs.getString("month"), rs.getString("category_name"),
                        rs.getInt("n"), rs.getBigDecimal("amount")), businessId, from, to);

        Map<String, int[]> counts = new LinkedHashMap<>();
        Map<String, BigDecimal> totals = new LinkedHashMap<>();
        List<List<Object>> monthly = new ArrayList<>();
        for (Line line : lines) {
            counts.computeIfAbsent(line.category(), key -> new int[1])[0] += line.count();
            totals.merge(line.category(), line.amount(), BigDecimal::add);
            monthly.add(List.of(line.month(), line.category(), line.count(), line.amount()));
        }

        long months = java.time.temporal.ChronoUnit.MONTHS.between(
                from.withDayOfMonth(1), to.withDayOfMonth(1)) + 1;
        List<List<Object>> summary = new ArrayList<>();
        totals.forEach((category, amount) -> summary.add(List.of(
                category, counts.get(category)[0], amount,
                amount.divide(BigDecimal.valueOf(months), 0, java.math.RoundingMode.HALF_UP))));

        return new TableDocument("05_고정비_반복지출.xlsx", "고정비",
                "고정비 · 반복지출", subtitle,
                List.of(TableDocument.Column.of("분류", 18),
                        TableDocument.Column.of("건수", 10),
                        TableDocument.Column.money("합계"),
                        TableDocument.Column.money("월평균")),
                List.of(new TableDocument.Section("분류별 합계",
                                "임차료·급여·4대보험·보험료·공과금·통신비·구독료입니다. "
                                        + "대출 상환은 원금이 경비가 아니라서 넣지 않았습니다.",
                                null, summary),
                        new TableDocument.Section("월별 내역", null,
                                List.of(TableDocument.Column.of("월", 12),
                                        TableDocument.Column.of("분류", 18),
                                        TableDocument.Column.of("건수", 10),
                                        TableDocument.Column.money("합계")),
                                monthly)),
                NOTE);
    }
}
