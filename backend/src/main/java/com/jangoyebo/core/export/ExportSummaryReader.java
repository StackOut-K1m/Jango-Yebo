package com.jangoyebo.core.export;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 패키지 요약 (API 14.2 {@code summary}).
 *
 * <p>화면(SC-07-6)이 완료 카드에 그대로 찍는 값이다 — 「거래 1,204건 · 증빙 연결률 72% ·
 * 미확인 96건/821만 원 · 검토 확정 92%」.
 *
 * <p><b>정의를 신고서 초안(14.3)과 같게 맞춘다.</b> 두 화면이 같은 기간을 보면서 다른 숫자를
 * 말하면 사장님은 어느 쪽을 믿어야 할지 모른다. 그래서
 *
 * <ul>
 *   <li>{@code taxReviewRate} 는 {@code TaxDraftAggregator.reviewRate} 와 같은 식이다 —
 *       확정 분류 거래 중 {@code UNREVIEWED} 가 아닌 비율이고, 셀 것이 없으면 1 이다.
 *   <li>{@code unverified*} 는 {@code excluded.needsReview} 와 같은 세 상태다 —
 *       「확인 필요」로 분리되는 것들이고, 그 카드를 누르면 가는 화면(SC-07-7)이 고치는 것도
 *       정확히 이 셋이다.
 * </ul>
 *
 * <p><b>분모가 0 이면 비율 키를 뺀다.</b> 출금이 한 건도 없는 기간에 「증빙 연결률 0%」를
 * 내보내면 화면은 「증빙을 하나도 안 붙였다」로 읽는다 — 붙일 것이 없었던 것과 다른 말이다
 * (구현현황 3.3 · 안전선 추천값과 같은 원칙).
 */
@Component
public class ExportSummaryReader {

    private final JdbcTemplate jdbc;

    public ExportSummaryReader(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Map<String, Object> read(Long businessId, LocalDate from, LocalDate to) {
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("transactionCount", transactionCount(businessId, from, to));

        Counts withdrawals = withdrawalEvidence(businessId, from, to);
        if (withdrawals.total() > 0) {
            summary.put("evidenceLinkRate", ratio(withdrawals.linked(), withdrawals.total()));
        }

        Counts unverified = unverified(businessId, from, to);
        summary.put("unverifiedCount", unverified.total());
        summary.put("unverifiedAmount", money(unverified.amount()));

        Counts reviewed = reviewed(businessId, from, to);
        summary.put("taxReviewedCount", reviewed.linked());
        summary.put("taxReviewRate", reviewed.total() == 0
                ? BigDecimal.ONE.setScale(4)
                : ratio(reviewed.linked(), reviewed.total()));
        return summary;
    }

    /** 02 계좌 거래내역 시트에 들어갈 행 수와 같다 — 분류 여부를 가리지 않는다. */
    private int transactionCount(Long businessId, LocalDate from, LocalDate to) {
        Integer count = jdbc.queryForObject("""
                SELECT COUNT(*) FROM core.transaction
                 WHERE business_id = ? AND transaction_date BETWEEN ? AND ?
                """, Integer.class, businessId, from, to);
        return count == null ? 0 : count;
    }

    /**
     * 증빙 연결률의 분모는 <b>출금뿐이다.</b> 증빙은 필요경비 근거라 입금에는 붙지 않는데,
     * 입금을 분모에 넣으면 매출이 많은 사업장일수록 연결률이 낮게 보인다.
     */
    private Counts withdrawalEvidence(Long businessId, LocalDate from, LocalDate to) {
        return jdbc.queryForObject("""
                SELECT COUNT(*)                                  AS total,
                       COUNT(*) FILTER (WHERE te.transaction_id IS NOT NULL) AS linked,
                       0                                         AS amount
                  FROM core.transaction t
                  LEFT JOIN (SELECT DISTINCT transaction_id FROM core.transaction_evidence) te
                         ON te.transaction_id = t.id
                 WHERE t.business_id = ? AND t.transaction_date BETWEEN ? AND ?
                   AND t.flow_type = 'WITHDRAWAL'
                """, Counts.MAPPER, businessId, from, to);
    }

    /** 14.3 {@code excluded.needsReview} 와 같은 세 상태. */
    private Counts unverified(Long businessId, LocalDate from, LocalDate to) {
        return jdbc.queryForObject("""
                SELECT COUNT(*)                     AS total,
                       0                            AS linked,
                       COALESCE(SUM(amount), 0)     AS amount
                  FROM core.transaction
                 WHERE business_id = ? AND transaction_date BETWEEN ? AND ?
                   AND classification_status = 'CONFIRMED'
                   AND tax_review_status IN
                       ('BUSINESS_USE_UNCLEAR', 'DUPLICATE_SUSPECTED', 'EXPERT_REVIEW')
                """, Counts.MAPPER, businessId, from, to);
    }

    /** 14.3 {@code basedOnReviewRate} 와 같은 식. {@code linked} 가 검토를 마친 건수다. */
    private Counts reviewed(Long businessId, LocalDate from, LocalDate to) {
        return jdbc.queryForObject("""
                SELECT COUNT(*)                                                       AS total,
                       COUNT(*) FILTER (WHERE tax_review_status <> 'UNREVIEWED')      AS linked,
                       0                                                              AS amount
                  FROM core.transaction
                 WHERE business_id = ? AND transaction_date BETWEEN ? AND ?
                   AND classification_status = 'CONFIRMED'
                """, Counts.MAPPER, businessId, from, to);
    }

    private static BigDecimal ratio(int part, int total) {
        return BigDecimal.valueOf(part)
                .divide(BigDecimal.valueOf(total), 4, RoundingMode.HALF_UP);
    }

    /**
     * {@code NUMERIC(19,4)} 합계를 원 단위 정수로. 그냥 내보내면 {@code 8210000.0000} 이
     * 되어 화면에 소수점이 찍힌다.
     */
    private static BigDecimal money(BigDecimal value) {
        BigDecimal stripped = value.stripTrailingZeros();
        return stripped.scale() < 0 ? stripped.setScale(0) : stripped;
    }

    private record Counts(int total, int linked, BigDecimal amount) {

        static final org.springframework.jdbc.core.RowMapper<Counts> MAPPER =
                (rs, i) -> new Counts(rs.getInt("total"), rs.getInt("linked"),
                        rs.getBigDecimal("amount"));
    }
}
