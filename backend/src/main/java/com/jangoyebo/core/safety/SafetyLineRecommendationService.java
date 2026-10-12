package com.jangoyebo.core.safety;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 안전선 추천값 (API 12.1 · P1-08).
 *
 * <p>사장님이 「얼마로 두어야 하나」를 정할 때 쓸 두 후보를 낸다 — 최근 30일 고정비 합계와,
 * 그중 반드시 나가는 둘(급여·임차료)만 추린 값이다.
 *
 * <p><b>0 원을 추천하지 않는다.</b> 근거가 될 거래가 하나도 없으면 그 후보의 키를 <b>아예
 * 뺀다</b>. 0 으로 내보내면 화면이 「안전선 0 원이면 됩니다」로 읽는데, 그건 「모른다」와
 * 전혀 다른 말이다(구현현황 3.3 — 없는 필드는 키가 없다).
 *
 * <p><b>확정 분류만 센다.</b> 제안 상태의 분류가 틀려 있으면 그만큼 안전선이 부풀고, 사장님은
 * 필요 이상으로 자주 경고를 받는다. 신뢰도 0.85 이상은 자동 확정되므로 대부분은 여기 들어온다.
 *
 * <p><b>예측에서 뺀 거래({@code is_excluded})는 제외한다.</b> 신고서 초안이 그 칸을 보지 않는
 * 것과 반대인데 이유가 있다 — 저쪽은 세무상 경비 판단이고, 여기는 <b>앞으로 나갈 돈</b>의
 * 바닥선이다. 곡선에서 뺀 일회성 지출이 바닥선을 올리면 안 된다.
 */
@Service
public class SafetyLineRecommendationService {

    /** 최근 30일. 오늘을 포함해 센다. */
    private static final int WINDOW_DAYS = 30;

    /** 고정비 — 매달 같은 자리에서 나가는 것들. */
    private static final List<String> FIXED_COST =
            List.of("RENT", "PAYROLL", "INSURANCE", "SUBSCRIPTION");

    /** 그중 미루기가 사실상 불가능한 둘. */
    private static final List<String> ESSENTIAL = List.of("PAYROLL", "RENT");

    private final JdbcTemplate jdbc;

    public SafetyLineRecommendationService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Transactional(readOnly = true)
    public Map<String, Object> recommend(Long businessId) {
        LocalDate to = LocalDate.now();
        LocalDate from = to.minusDays(WINDOW_DAYS - 1L);

        Map<String, Object> body = new LinkedHashMap<>();
        candidate(businessId, from, to, FIXED_COST,
                "최근 30일 고정비(임차료·급여·보험료·구독료) 합계")
                .ifPresent(value -> body.put("fixedCostBased", value));
        candidate(businessId, from, to, ESSENTIAL, "월 급여 + 임차료 합계")
                .ifPresent(value -> body.put("essentialExpenseBased", value));
        body.put("referencePeriod", from + "~" + to);
        return body;
    }

    private java.util.Optional<Map<String, Object>> candidate(Long businessId, LocalDate from,
                                                              LocalDate to, List<String> categories,
                                                              String basis) {
        String placeholders = String.join(",", categories.stream().map(c -> "?").toList());
        Object[] args = new Object[categories.size() + 3];
        args[0] = businessId;
        args[1] = from;
        args[2] = to;
        for (int i = 0; i < categories.size(); i++) {
            args[i + 3] = categories.get(i);
        }

        Map<String, Object> row = jdbc.queryForObject("""
                SELECT COUNT(*) AS n, COALESCE(SUM(t.amount), 0) AS amount
                  FROM core.transaction t
                  JOIN core.transaction_category c ON c.id = t.category_id
                 WHERE t.business_id = ?
                   AND t.transaction_date BETWEEN ? AND ?
                   AND t.flow_type = 'WITHDRAWAL'
                   AND t.classification_status = 'CONFIRMED'
                   AND t.is_excluded = FALSE
                   AND c.code IN (%s)
                """.formatted(placeholders),
                (rs, i) -> Map.of("n", rs.getInt("n"), "amount", rs.getBigDecimal("amount")),
                args);

        if (row == null || (int) row.get("n") == 0) {
            return java.util.Optional.empty();
        }
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("amount", money((BigDecimal) row.get("amount")));
        value.put("basis", basis);
        value.put("transactionCount", row.get("n"));
        return java.util.Optional.of(value);
    }

    /** {@code NUMERIC(19,4)} 합계를 원 단위 정수로. 화면에 소수점이 찍히지 않게 한다. */
    private static BigDecimal money(BigDecimal value) {
        BigDecimal stripped = value.stripTrailingZeros();
        return stripped.scale() < 0 ? stripped.setScale(0) : stripped;
    }
}
