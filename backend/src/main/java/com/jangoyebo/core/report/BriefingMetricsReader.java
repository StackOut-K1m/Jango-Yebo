package com.jangoyebo.core.report;

import com.jangoyebo.core.CashGapOutlook;
import com.jangoyebo.core.SafetyLineOutlook;
import com.jangoyebo.core.UpcomingPayments;
import com.jangoyebo.core.safety.SafetyBalancePolicyRepository;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 브리핑 지표 (API 15.2 {@code metrics}).
 *
 * <p><b>여기가 이 기능의 전부다.</b> 문장은 이 값들을 읽어 옮길 뿐이고, LLM 은 여기 없는
 * 수치를 쓸 수 없다(도메인 규칙 2 · 20.7). 지표가 빈약하면 문장도 빈약하다.
 *
 * <p><b>모든 지표에 근거가 붙는다</b>({@code sourceRef}). 명세가 「{@code metrics} 의 모든
 * 수치는 근거 데이터와 연결된다」고 요구한다 — 화면이 숫자를 눌러 원본으로 갈 수 있어야 한다.
 *
 * <p><b>없는 지표는 넣지 않는다.</b> 공백이 없으면 「공백 0건」이 아니라 그 줄이 없다. 0 으로
 * 채우면 LLM 이 「공백이 0원입니다」 같은 문장을 쓴다 — 안전선 추천값·패턴 알림과 같은 원칙이다.
 */
@Component
class BriefingMetricsReader {

    /** 예측 지표를 볼 창. 10.1 의 기본 조회 창과 같다. */
    private static final int FORECAST_DAYS = 30;

    private final JdbcTemplate jdbc;
    private final SafetyLineOutlook safetyLine;
    private final CashGapOutlook cashGap;
    private final SafetyBalancePolicyRepository policies;
    private final UpcomingPayments upcomingPayments;

    BriefingMetricsReader(JdbcTemplate jdbc, SafetyLineOutlook safetyLine,
                          CashGapOutlook cashGap, SafetyBalancePolicyRepository policies,
                          UpcomingPayments upcomingPayments) {
        this.jdbc = jdbc;
        this.safetyLine = safetyLine;
        this.cashGap = cashGap;
        this.policies = policies;
        this.upcomingPayments = upcomingPayments;
    }

    /**
     * 순서가 의미를 갖는다 — 워커가 <b>받은 순서를 지킨다</b>({@code prompts.py}). 중요한 것을
     * 앞에 둔다: 이번 기간에 무슨 일이 있었나 → 앞으로 무엇이 오나.
     */
    List<Map<String, Object>> read(Long businessId, LocalDate from, LocalDate to) {
        List<Map<String, Object>> metrics = new ArrayList<>();

        long span = to.toEpochDay() - from.toEpochDay() + 1;
        LocalDate previousTo = from.minusDays(1);
        LocalDate previousFrom = previousTo.minusDays(span - 1);

        Flow current = flow(businessId, from, to);
        Flow previous = flow(businessId, previousFrom, previousTo);

        if (current.deposit().signum() > 0 || previous.deposit().signum() > 0) {
            metrics.add(metric("기간 입금 합계", current.deposit(),
                    changeRate(current.deposit(), previous.deposit()),
                    sourceRef("TRANSACTION", Map.of("from", from.toString(), "to", to.toString()))));
        }
        if (current.withdrawal().signum() > 0 || previous.withdrawal().signum() > 0) {
            metrics.add(metric("기간 출금 합계", current.withdrawal(),
                    changeRate(current.withdrawal(), previous.withdrawal()),
                    sourceRef("TRANSACTION", Map.of("from", from.toString(), "to", to.toString()))));
        }

        topExpense(businessId, from, to).ifPresent(metrics::add);

        // 예측 쪽은 포트로 읽는다. core 는 forecast 를 참조할 수 없고, 무엇보다 조회가
        // 예측을 돌리면 안 된다(공통규약 2.6) — 실행이 없으면 그냥 지표가 빈다.
        safetyLine.of(businessId, policies.findByBusinessId(businessId)
                        .map(policy -> policy.getAmount()).orElse(null))
                .ifPresent(current30 -> {
                    metrics.add(metric("향후 30일 최저잔액", money(current30.next30dLowestBalance()),
                            null, sourceRef("FORECAST",
                                    Map.of("date", String.valueOf(current30.next30dLowestDate())))));
                    if (current30.firstBelowLineDate() != null) {
                        metrics.add(metric("안전선 최초 하회일", null, null,
                                sourceRef("FORECAST",
                                        Map.of("date", current30.firstBelowLineDate().toString()))));
                    }
                });

        cashGap.nearestGap(businessId, FORECAST_DAYS).ifPresent(gap ->
                metrics.add(metric("가장 가까운 정산 공백", money(gap.maxShortfallAmount()), null,
                        sourceRef("FORECAST",
                                Map.of("date", gap.firstShortfallDate().toString())))));

        // 예정 거래도 forecast 스키마다 — 포트로 읽는다. 없으면 지표가 빠진다.
        upcomingPayments.largestUpcoming(businessId, to, FORECAST_DAYS).ifPresent(payment ->
                metrics.add(metric("다음 대형 지출 — " + payment.name(), money(payment.amount()),
                        null, sourceRef("PLANNED", Map.of(
                                "plannedTransactionId", payment.plannedTransactionId(),
                                "date", payment.expectedDate().toString())))));
        return metrics;
    }

    // --- 거래 --------------------------------------------------------------

    private Flow flow(Long businessId, LocalDate from, LocalDate to) {
        return jdbc.queryForObject("""
                SELECT COALESCE(SUM(amount) FILTER (WHERE flow_type = 'DEPOSIT'), 0)    AS deposit,
                       COALESCE(SUM(amount) FILTER (WHERE flow_type = 'WITHDRAWAL'), 0) AS withdrawal
                  FROM core.transaction
                 WHERE business_id = ? AND transaction_date BETWEEN ? AND ?
                   AND is_excluded = FALSE
                """, (rs, i) -> new Flow(rs.getBigDecimal("deposit"), rs.getBigDecimal("withdrawal")),
                businessId, from, to);
    }

    /** 가장 큰 지출 분류. 확정 분류만 본다 — 제안 상태가 틀리면 엉뚱한 분류를 1위로 말한다. */
    private java.util.Optional<Map<String, Object>> topExpense(Long businessId, LocalDate from,
                                                               LocalDate to) {
        List<Map<String, Object>> rows = jdbc.query("""
                SELECT c.name AS category_name, SUM(t.amount) AS amount
                  FROM core.transaction t
                  JOIN core.transaction_category c ON c.id = t.category_id
                 WHERE t.business_id = ? AND t.transaction_date BETWEEN ? AND ?
                   AND t.flow_type = 'WITHDRAWAL'
                   AND t.classification_status = 'CONFIRMED'
                   AND t.is_excluded = FALSE
                 GROUP BY c.name
                 ORDER BY 2 DESC
                 LIMIT 1
                """, (rs, i) -> metric("가장 큰 지출 — " + rs.getString("category_name"),
                        money(rs.getBigDecimal("amount")), null,
                        sourceRef("TRANSACTION",
                                Map.of("from", from.toString(), "to", to.toString()))),
                businessId, from, to);
        return rows.stream().findFirst();
    }

    // --- 조립 --------------------------------------------------------------

    /**
     * 지표 한 줄.
     *
     * <p>{@code value} 가 {@code null} 인 줄이 있다 — 「안전선 최초 하회일」처럼 날짜만 있는
     * 지표다. 워커는 값이 없으면 라벨과 근거만 읽는다.
     */
    private static Map<String, Object> metric(String label, BigDecimal value, BigDecimal changeRate,
                                              Map<String, Object> sourceRef) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("label", label);
        if (value != null) {
            row.put("value", value);
        }
        if (changeRate != null) {
            row.put("changeRate", changeRate);
        }
        row.put("sourceRef", sourceRef);
        return row;
    }

    private static Map<String, Object> sourceRef(String type, Map<String, Object> fields) {
        Map<String, Object> ref = new LinkedHashMap<>();
        ref.put("type", type);
        ref.putAll(fields);
        return ref;
    }

    /**
     * 증감률.
     *
     * <p><b>직전 기간이 0 이면 비율을 내지 않는다.</b> 0 으로 나눌 수 없고, 「무한대로 늘었다」는
     * 사장님에게 아무 말도 아니다 — 그때는 절대액만 읽힌다.
     */
    private static BigDecimal changeRate(BigDecimal now, BigDecimal before) {
        if (before == null || before.signum() == 0) {
            return null;
        }
        return now.subtract(before).divide(before, 4, RoundingMode.HALF_UP);
    }

    /** {@code NUMERIC(19,4)} 를 원 단위로. 그냥 두면 {@code 748000.0000} 이 문장에 들어간다. */
    private static BigDecimal money(BigDecimal value) {
        if (value == null) {
            return null;
        }
        BigDecimal stripped = value.stripTrailingZeros();
        return stripped.scale() < 0 ? stripped.setScale(0) : stripped;
    }

    private record Flow(BigDecimal deposit, BigDecimal withdrawal) {
    }
}
