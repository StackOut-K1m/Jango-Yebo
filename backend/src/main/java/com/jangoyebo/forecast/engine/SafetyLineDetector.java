package com.jangoyebo.forecast.engine;

import com.jangoyebo.common.domain.RiskLevel;
import com.jangoyebo.forecast.engine.BalanceCurveCalculator.DailyPoint;
import com.jangoyebo.forecast.engine.BalanceCurveCalculator.PlannedItem;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * 안전선 하회 구간 파생 (P1-08 · API 12.1).
 *
 * <p>{@link CashGapDetector} 와 같은 모양의 순수 함수다. 저장하지 않고 곡선에서 그때그때
 * 계산한다 — 안전선은 조회 시점에 읽는 값이라(ERD 1.3) 저장해 두면 바꾼 순간 낡는다.
 *
 * <h2>{@link #segments} 와 {@link #alertable} 이 다른 이유</h2>
 *
 * <p>{@link #segments} 는 안전선 아래 구간을 <b>전부</b> 낸다. 화면(API 12.1 {@code current})은
 * 0원 아래로 지나가는 구간이어도 「언제부터 안전선 아래인지」를 말해야 하기 때문이다.
 *
 * <p>{@link #alertable} 은 그중 <b>0원 미만인 날이 낀 구간을 뺀다.</b> 그 골짜기는
 * {@link CashGapDetector} 가 {@code CASH_GAP} 으로 이미 경고하고, 같은 골짜기에 두 알림을
 * 띄우면 급한 쪽이 묻힌다. {@link RiskLevelRule} 이 0원 미만에 {@code CAUTION} 을 붙이지 않고
 * {@code GAP} 만 내는 것과 같은 우선순위다.
 *
 * <p>그래서 한 골짜기에 알림은 <b>정확히 하나</b>다 — 공백이면 {@code CASH_GAP}, 아니면
 * {@code SAFETY_LINE}. 안전선 아래로 내려간 날짜 자체는 {@code current} 블록과 곡선의 등급
 * 색이 계속 보여 준다.
 */
public final class SafetyLineDetector {

    private SafetyLineDetector() {
    }

    /**
     * 예상 잔액이 안전선 아래인 연속 구간 전부. 날짜 오름차순이다.
     *
     * @param safetyLine 안전선. <b>{@code null} 이 정상 상태다</b> — 설정하지 않은 사업장이며
     *                   이때는 하회를 판정할 선이 없으므로 빈 목록이다(API 10.1)
     */
    public static List<SafetyLineSegment> segments(List<DailyPoint> window, BigDecimal safetyLine) {
        if (safetyLine == null || safetyLine.signum() <= 0) {
            // 0원 안전선은 「0원 미만」과 같은 선이라 공백과 완전히 겹친다. 하회라 할 것이 없다.
            return List.of();
        }

        List<SafetyLineSegment> segments = new ArrayList<>();
        int start = -1;

        for (int i = 0; i < window.size(); i++) {
            boolean below = window.get(i).balanceExpected().compareTo(safetyLine) < 0;
            if (below && start < 0) {
                start = i;
            } else if (!below && start >= 0) {
                segments.add(segment(window, start, i - 1, safetyLine));
                start = -1;
            }
        }
        if (start >= 0) {
            segments.add(segment(window, start, window.size() - 1, safetyLine));
        }
        return segments;
    }

    /** 알림을 띄울 구간. 공백이 낀 것은 {@code CASH_GAP} 이 가져간다. */
    public static List<SafetyLineSegment> alertable(List<DailyPoint> window, BigDecimal safetyLine) {
        return segments(window, safetyLine).stream().filter(s -> !s.containsGap()).toList();
    }

    private static SafetyLineSegment segment(List<DailyPoint> window, int from, int to, BigDecimal safetyLine) {
        DailyPoint lowest = window.get(from);
        DailyPoint worstCase = window.get(from);
        boolean containsGap = false;
        for (int i = from; i <= to; i++) {
            DailyPoint p = window.get(i);
            containsGap |= p.balanceExpected().signum() < 0;
            // 동률이면 빠른 날을 남긴다 — 사용자가 먼저 대응해야 하는 날이다.
            if (p.balanceExpected().compareTo(lowest.balanceExpected()) < 0) {
                lowest = p;
            }
            if (p.balanceMin().compareTo(worstCase.balanceMin()) < 0) {
                worstCase = p;
            }
        }

        // 회복일은 구간 다음 날이다. 창 끝까지 하회면 창 밖은 모르므로 null 이다 —
        // 「30일 안에 회복하지 않는다」와 「31일째에 회복한다」를 구분할 수 없다.
        LocalDate recovery = to + 1 < window.size() ? window.get(to + 1).date() : null;

        // 하한 부족액이 예상보다 작으면(하한이 예상보다 높을 수는 없지만 반올림 여지가 있다)
        // 예상으로 접는다. 범위 상단이 하단보다 작아지면 문구가 「50만~40만 원」이 된다.
        BigDecimal shortfall = safetyLine.subtract(lowest.balanceExpected());
        BigDecimal worstShortfall = safetyLine.subtract(worstCase.balanceMin()).max(shortfall);

        return new SafetyLineSegment(
                window.get(from).date(),
                window.get(to).date(),
                shortfall,
                lowest.date(),
                lowest.balanceExpected(),
                worstShortfall,
                RiskLevelRule.of(lowest.balanceExpected(), safetyLine),
                containsGap,
                to - from + 1,
                recovery);
    }

    /**
     * 하회의 원인 후보. 공백과 찾는 방식이 같다 — 큰 출금이 몰렸거나 정산이 멈췄거나다.
     *
     * @see CashGapDetector#causes(LocalDate, LocalDate, LocalDate, List)
     */
    public static List<CashGapDetector.Cause> causes(SafetyLineSegment segment, List<PlannedItem> planned) {
        return CashGapDetector.causes(segment.firstBelowDate(), segment.lastBelowDate(),
                segment.lowestDate(), planned);
    }

    /** 같은 하회를 같은 알림에 붙이는 키. 최초 하회일이 바뀌면 다른 구간으로 본다. */
    public static String dedupKey(SafetyLineSegment segment) {
        return "safety-line:" + segment.firstBelowDate();
    }

    /**
     * @param shortfall      예상 기준 최대 부족액(양수). 안전선 − 구간 내 최저 예상 잔액
     * @param lowestBalance  구간 내 최저 예상 잔액. {@code containsGap} 이면 음수일 수 있다
     * @param worstShortfall 하한 기준 최대 부족액. 문구의 범위 상단에만 쓴다 —
     *                       <b>판정에는 쓰지 않는다</b>({@link RiskLevelRule} 의 α 논거)
     * @param riskLevel      구간 최저점의 등급. {@code containsGap} 이면 {@code GAP} 이다
     * @param containsGap    구간 안에 0원 미만인 날이 있나. {@code true} 면 알림을 띄우지 않는다
     * @param expectedRecoveryDate 창 끝까지 하회면 {@code null}
     */
    public record SafetyLineSegment(LocalDate firstBelowDate,
                                    LocalDate lastBelowDate,
                                    BigDecimal shortfall,
                                    LocalDate lowestDate,
                                    BigDecimal lowestBalance,
                                    BigDecimal worstShortfall,
                                    RiskLevel riskLevel,
                                    boolean containsGap,
                                    int durationDays,
                                    LocalDate expectedRecoveryDate) {
    }
}
