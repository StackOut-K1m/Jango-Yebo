package com.jangoyebo.forecast.engine;

import com.jangoyebo.common.domain.FlowType;
import com.jangoyebo.forecast.engine.BalanceCurveCalculator.DailyPoint;
import com.jangoyebo.forecast.engine.BalanceCurveCalculator.PlannedItem;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * 정산 공백 구간 파생 (P0-08 · API 10.2).
 *
 * <p>순수 함수다. 공백은 <b>저장하지 않는다</b> — 곡선에서 조회할 때마다 계산한다
 * (ERD 1.3, 공통규약 2.6). 결정적이라 실행 시점과 조회 시점이 같은 구간을 낸다.
 *
 * <p><b>판정선은 {@code balanceExpected} 다.</b> 근거는 {@link RiskLevelRule} 의 javadoc.
 *
 * <p><b>주어진 창 안에서만 계산한다.</b> 30일 곡선을 7일로 잘라 조회하면 7일 안의 구간만
 * 나오고, 창 끝까지 음수면 회복일은 {@code null} 이다. 창 밖을 들여다보면
 * {@code basis.referenceCurveDays} 가 말하는 범위와 응답이 어긋난다.
 */
public final class CashGapDetector {

    /** {@code causes[]} 에 담을 출금 예정 거래 수. 화면(SC-03-2)이 상위 2건을 쓴다. */
    static final int MAX_CAUSES = 3;

    /** 원인으로 볼 출금을 구간 시작 며칠 전부터 찾을지. 급여·임대료가 하루 이틀 앞서 빠진다. */
    static final int CAUSE_LOOKBACK_DAYS = 3;

    /** 「이 기간에 예정 입금이 없다」를 판정하는 창. 카드 정산 주기(주 단위)를 덮는다. */
    static final int DEPOSIT_DROUGHT_LOOKBACK_DAYS = 7;

    private CashGapDetector() {
    }

    /**
     * 예상 잔액이 음수인 연속 구간을 자른다.
     *
     * @param window 조회 {@code days} 로 자른 일별 곡선. 날짜 오름차순
     */
    public static List<CashGapSegment> detect(List<DailyPoint> window) {
        List<CashGapSegment> segments = new ArrayList<>();
        int start = -1;

        for (int i = 0; i < window.size(); i++) {
            boolean shortfall = window.get(i).balanceExpected().signum() < 0;
            if (shortfall && start < 0) {
                start = i;
            } else if (!shortfall && start >= 0) {
                segments.add(segment(window, start, i - 1));
                start = -1;
            }
        }
        if (start >= 0) {
            segments.add(segment(window, start, window.size() - 1));
        }
        return segments;
    }

    private static CashGapSegment segment(List<DailyPoint> window, int from, int to) {
        DailyPoint worst = window.get(from);
        DailyPoint worstCase = window.get(from);
        for (int i = from; i <= to; i++) {
            DailyPoint p = window.get(i);
            // 동률이면 빠른 날을 남긴다 — 사용자가 먼저 대응해야 하는 날이다.
            if (p.balanceExpected().compareTo(worst.balanceExpected()) < 0) {
                worst = p;
            }
            if (p.balanceMin().compareTo(worstCase.balanceMin()) < 0) {
                worstCase = p;
            }
        }

        // 회복일은 구간 다음 날이다. 창 끝까지 음수면 창 밖은 모르므로 null 이다 —
        // 「30일 안에 회복하지 않는다」와 「31일째에 회복한다」를 구분할 수 없다.
        LocalDate recovery = to + 1 < window.size() ? window.get(to + 1).date() : null;

        return new CashGapSegment(
                window.get(from).date(),
                window.get(to).date(),
                worst.balanceExpected().negate(),
                worst.date(),
                worstCase.balanceMin().negate(),
                to - from + 1,
                recovery);
    }

    /**
     * 공백의 원인 후보.
     *
     * <p>구간이 시작되기 직전부터 최대 부족일까지의 <b>출금 예정 거래</b>를 금액순으로 고르고,
     * 그 기간에 입금 예정이 하나도 없으면 그 사실을 한 줄 덧붙인다. 후자가 중요한 이유는
     * 대상 매장의 공백이 대개 「큰 지출」이 아니라 <b>「연휴로 정산이 멈춘 것」</b>에서 오기
     * 때문이다 — 그때 출금만 보여 주면 원인이 설명되지 않는다.
     *
     * <p>연휴 자체를 원인으로 지목하는 것은 공휴일 캘린더가 필요해 P1-04 다. 여기서는
     * 「예정 입금 없음」이라는 관측 사실까지만 말한다.
     */
    public static List<Cause> causes(CashGapSegment segment, List<PlannedItem> planned) {
        return causes(segment.firstShortfallDate(), segment.lastShortfallDate(),
                segment.maxShortfallDate(), planned);
    }

    /**
     * 날짜만 받는 형태. 안전선 하회 구간({@link SafetyLineDetector})도 이것을 쓴다 — 원인을
     * 찾는 방식은 공백이든 하회이든 다르지 않고, 두 벌로 두면 한쪽만 고쳐질 자리다.
     *
     * @param firstDate 구간 시작일. 출금은 이 날의 {@link #CAUSE_LOOKBACK_DAYS}일 전부터 본다
     * @param lastDate  구간 종료일. 「예정 입금 없음」 판정의 끝
     * @param worstDate 구간 내 최저일. 출금을 찾는 창의 끝
     */
    public static List<Cause> causes(LocalDate firstDate, LocalDate lastDate, LocalDate worstDate,
                                     List<PlannedItem> planned) {
        LocalDate withdrawalFrom = firstDate.minusDays(CAUSE_LOOKBACK_DAYS);
        List<Cause> causes = planned.stream()
                .filter(p -> p.flowType() == FlowType.WITHDRAWAL)
                .filter(p -> !p.date().isBefore(withdrawalFrom) && !p.date().isAfter(worstDate))
                .sorted(Comparator.comparing(PlannedItem::expected).reversed()
                        .thenComparing(PlannedItem::date)
                        .thenComparing(PlannedItem::plannedTransactionId))
                .limit(MAX_CAUSES)
                .map(p -> new Cause(p.date(), p.expected(), p.plannedTransactionId()))
                .collect(java.util.stream.Collectors.toCollection(ArrayList::new));

        LocalDate depositFrom = firstDate.minusDays(DEPOSIT_DROUGHT_LOOKBACK_DAYS);
        boolean noDeposit = planned.stream()
                .filter(p -> p.flowType() == FlowType.DEPOSIT)
                .noneMatch(p -> !p.date().isBefore(depositFrom) && !p.date().isAfter(lastDate));
        if (noDeposit) {
            causes.add(new Cause(firstDate, null, null));
        }
        return causes;
    }

    /** 같은 공백을 같은 알림에 붙이는 키. 최초 부족일이 바뀌면 다른 공백으로 본다. */
    public static String dedupKey(CashGapSegment segment) {
        return "cash-gap:" + segment.firstShortfallDate();
    }

    /**
     * @param maxShortfallAmount 예상 기준 최대 부족액(양수). API 10.2 의 값
     * @param worstCaseShortfall 하한 기준 최대 부족액(양수). 알림 문구의 범위 상단에만 쓴다 —
     *                           <b>판정에는 쓰지 않는다</b>
     * @param expectedRecoveryDate 창 끝까지 음수면 {@code null}
     */
    public record CashGapSegment(LocalDate firstShortfallDate,
                                 LocalDate lastShortfallDate,
                                 BigDecimal maxShortfallAmount,
                                 LocalDate maxShortfallDate,
                                 BigDecimal worstCaseShortfall,
                                 int durationDays,
                                 LocalDate expectedRecoveryDate) {
    }

    /**
     * @param amount               출금 예정액. 「예정 입금 없음」 원인은 {@code null}
     * @param plannedTransactionId 위와 같다. {@code null} 이면 응답에서 키가 빠진다
     */
    public record Cause(LocalDate date, BigDecimal amount, Long plannedTransactionId) {

        public boolean isDepositDrought() {
            return plannedTransactionId == null;
        }
    }
}
