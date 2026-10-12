package com.jangoyebo.forecast.scenario;

import com.jangoyebo.forecast.engine.BalanceCurveCalculator.Curve;
import com.jangoyebo.forecast.engine.BalanceCurveCalculator.DailyPoint;
import com.jangoyebo.forecast.engine.CashGapDetector;
import com.jangoyebo.forecast.engine.CashGapDetector.CashGapSegment;
import com.jangoyebo.forecast.engine.SafetyLineDetector;
import com.jangoyebo.forecast.engine.SafetyLineDetector.SafetyLineSegment;
import com.jangoyebo.forecast.scenario.ScenarioDtos.Summary;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;

/**
 * 곡선 하나를 비교 지표로 접는다 (API 11.1 {@code comparison}).
 *
 * <p><b>순수 함수이며 새 판정 규칙을 만들지 않는다.</b> 공백은 {@link CashGapDetector}, 안전선
 * 하회는 {@link SafetyLineDetector} 가 판정한다 — 10.2 의 공백 구간과 12.1 의 알림이 쓰는
 * 바로 그 함수들이다. 여기서 따로 세면 <b>같은 곡선을 두고 화면마다 다른 날짜를 말하게 된다.</b>
 *
 * <p>원본과 변경에 같은 함수를 쓰는 것이 요점이다. 원본 지표를 저장된 곡선에서 읽고 변경만
 * 다시 계산하면, 파라미터가 아무것도 바꾸지 않는 시나리오에서도 두 값이 1원 갈릴 수 있다.
 */
public final class ScenarioComparison {

    private ScenarioComparison() {
    }

    /**
     * @param safetyLine 안전선. <b>{@code null} 이 정상 상태다</b> — 설정하지 않은 사업장이며
     *                   그때 {@code belowSafetyLineDays} 는 {@code null} 이다. 0 과 다르다:
     *                   「하회한 날이 없다」가 아니라 「잴 선이 없다」다
     */
    public static Summary summarize(Curve curve, BigDecimal safetyLine) {
        List<DailyPoint> points = curve.points();
        DailyPoint lowest = curve.lowestPoint();

        List<CashGapSegment> gaps = CashGapDetector.detect(points);
        int gapDays = 0;
        for (CashGapSegment gap : gaps) {
            gapDays += gap.durationDays();
        }

        return new Summary(
                won(lowest.balanceExpected()),
                lowest.date(),
                // 부족이 없으면 null 이고, 전역 non_null 정책이 키를 통째로 뺀다.
                gaps.isEmpty() ? null : gaps.get(0).firstShortfallDate(),
                gapDays,
                belowSafetyLineDays(points, safetyLine));
    }

    private static Integer belowSafetyLineDays(List<DailyPoint> points, BigDecimal safetyLine) {
        if (safetyLine == null) {
            return null;
        }
        int days = 0;
        for (SafetyLineSegment segment : SafetyLineDetector.segments(points, safetyLine)) {
            days += segment.durationDays();
        }
        return days;
    }

    /**
     * 응답 금액은 정수 원이다 (ERD 11장 — 출력 직전에 <b>한 번만</b> 반올림).
     *
     * <p>곡선 점도 같은 함수를 쓴다. 지표와 점이 다른 자리에서 반올림되면
     * 「최저 잔액」이 곡선의 어느 점과도 같지 않은 값으로 나올 수 있다.
     */
    static BigDecimal won(BigDecimal value) {
        return value == null ? null : value.setScale(0, RoundingMode.HALF_UP);
    }
}
