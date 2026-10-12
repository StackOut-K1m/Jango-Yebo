package com.jangoyebo.forecast.residual;

import com.jangoyebo.forecast.engine.BalanceCurveCalculator.Quantile;
import com.jangoyebo.forecast.engine.BalanceCurveCalculator.ResidualQuantiles;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * 통계 베이스라인 — 요일별 분위수 (설계노트 M-1 · S15P21C105-67).
 *
 * <p>{@code ai/app/forecast/baseline.py} 의 이식본이다. <b>영구 폴백이자 모델 채택 게이트의
 * 기준선</b>이라 두 역할을 겸한다 — 모델이 이것을 유의하게 넘지 못하면 쓰지 않는다.
 *
 * <p>순수 함수다. 과거 잔여 시계열을 요일로 묶어 각 요일의 q10·q50·q90 을 구하고, 예보일의
 * 요일로 조회한다. 카드 정산이 요일마다 금액이 다르고 주말에 멈추는 것을 그대로 담는다.
 *
 * <h2>참조 구현과 숫자가 갈리면 안 된다</h2>
 *
 * <p>모델과 통계를 비교한 게이트가 이 값을 기준선으로 삼았다. 여기서 다른 값이 나오면
 * 「모델이 통계보다 낫다」는 판정이 우리 코드에서는 성립하지 않는다. 그래서 두 가지를
 * 참조 구현 그대로 옮겼다.
 *
 * <ul>
 *   <li><b>선형 보간 분위수.</b> {@code k = (n−1)·p} 로 위치를 잡고 양옆을 가중 평균한다
 *       (numpy 기본값과 같은 방식)
 *   <li><b>보간할 때만 정수 원으로 반올림한다.</b> 위치가 정확히 맞아떨어지면 원래 값을
 *       그대로 쓴다. ERD 의 「중간 반올림 금지」와 어긋나 보이지만, 여기서는 <b>참조 구현과의
 *       동일성이 우선</b>이다 — 이 값은 곡선의 중간 계산이 아니라 모델을 대신하는 산출물이다
 * </ul>
 */
public final class WeekdayMedianBaseline {

    /** 이보다 짧으면 태우지 않는다. 워커의 {@code features.MIN_HISTORY} 와 같은 값이다. */
    public static final int MIN_SAMPLE_DAYS = 30;

    /** 이보다 짧으면 값을 내되 신뢰도를 한 단계 내린다(설계노트 「표본 부족 처리」). */
    public static final int DEGRADED_SAMPLE_DAYS = 180;

    private static final BigDecimal Q10 = new BigDecimal("0.10");
    private static final BigDecimal Q50 = new BigDecimal("0.50");
    private static final BigDecimal Q90 = new BigDecimal("0.90");

    private WeekdayMedianBaseline() {
    }

    /**
     * 잔여 입금·출금 예보를 만든다.
     *
     * @param forecastFrom 예보 첫날. 곡선의 첫날과 같아야 한다(기준일 + 1)
     * @return 표본이 {@link #MIN_SAMPLE_DAYS} 미만이면 빈 값. 근거 없는 숫자를 내느니
     *         「모른다」를 내는 편이 낫다 — 곡선은 예정 거래만으로도 나온다
     */
    public static ResidualQuantiles forecast(ResidualSeries series, LocalDate forecastFrom, int horizonDays) {
        if (series == null || series.historyDays() < MIN_SAMPLE_DAYS) {
            return null;
        }
        return new ResidualQuantiles(forecastFrom,
                axis(series.deposit(), series.startDate(), forecastFrom, horizonDays),
                axis(series.withdrawal(), series.startDate(), forecastFrom, horizonDays));
    }

    private static List<Quantile> axis(List<BigDecimal> values, LocalDate seriesStart,
                                       LocalDate forecastFrom, int horizonDays) {
        Map<DayOfWeek, Quantile> table = new EnumMap<>(DayOfWeek.class);
        Map<DayOfWeek, List<BigDecimal>> buckets = new EnumMap<>(DayOfWeek.class);
        for (DayOfWeek day : DayOfWeek.values()) {
            buckets.put(day, new ArrayList<>());
        }
        for (int i = 0; i < values.size(); i++) {
            buckets.get(seriesStart.plusDays(i).getDayOfWeek()).add(values.get(i));
        }
        for (Map.Entry<DayOfWeek, List<BigDecimal>> entry : buckets.entrySet()) {
            List<BigDecimal> sorted = entry.getValue().stream().sorted().toList();
            table.put(entry.getKey(), new Quantile(
                    percentile(sorted, Q10), percentile(sorted, Q50), percentile(sorted, Q90)));
        }

        List<Quantile> out = new ArrayList<>(horizonDays);
        for (int i = 0; i < horizonDays; i++) {
            out.add(table.get(forecastFrom.plusDays(i).getDayOfWeek()));
        }
        return out;
    }

    /**
     * 선형 보간 분위수. {@code sorted} 는 오름차순이어야 한다.
     *
     * <p>표본이 없으면 0 이다 — 그 요일에 거래가 한 번도 없었다는 뜻이고, 잔여 정의상
     * 0 이 맞다.
     */
    static BigDecimal percentile(List<BigDecimal> sorted, BigDecimal p) {
        if (sorted.isEmpty()) {
            return BigDecimal.ZERO;
        }
        if (sorted.size() == 1) {
            return sorted.get(0);
        }
        BigDecimal k = BigDecimal.valueOf(sorted.size() - 1L).multiply(p);
        int lo = k.setScale(0, RoundingMode.FLOOR).intValue();
        int hi = k.setScale(0, RoundingMode.CEILING).intValue();
        if (lo == hi) {
            return sorted.get(lo);
        }
        BigDecimal weight = k.subtract(BigDecimal.valueOf(lo));
        return sorted.get(lo).multiply(BigDecimal.ONE.subtract(weight))
                .add(sorted.get(hi).multiply(weight))
                .setScale(0, RoundingMode.HALF_UP);
    }
}
