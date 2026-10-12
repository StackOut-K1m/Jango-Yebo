package com.jangoyebo.forecast.run;

import com.jangoyebo.forecast.pattern.ConfidenceBand;
import com.jangoyebo.forecast.engine.CreditGradeProjection.CurvePoint;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;

/**
 * 등급 예측(16.2)이 쓰는 곡선 읽기.
 *
 * <p>{@link ForecastQueryService} 와 갈라 둔 이유는 <b>없을 때의 행동이 다르기 때문</b>이다.
 * 잔액 화면은 곡선이 없으면 {@code FORECAST_NOT_READY} 로 막고 재계산을 건다 — 그 화면의
 * 본체가 곡선이라서다. 등급 화면은 <b>현재 등급만으로도 쓸모가 있다</b> — 예측이 없으면
 * 그 칸만 비우고 경고를 남긴다. 여기서 던지면 등급을 볼 방법이 사라진다.
 */
@Service
public class CreditCurveReader {

    private final ForecastRunRepository runs;
    private final DailyBalanceForecastRepository dailyRows;

    public CreditCurveReader(ForecastRunRepository runs, DailyBalanceForecastRepository dailyRows) {
        this.runs = runs;
        this.dailyRows = dailyRows;
    }

    /**
     * 가장 최근 완료된 실행의 곡선.
     *
     * @param maxDays 앞에서부터 이만큼만 쓴다. 곡선이 짧으면 있는 만큼
     * @return 완료된 실행이 없거나 점이 없으면 {@code null}
     */
    @Transactional(readOnly = true)
    public CurveWindow forCredit(Long businessId, int maxDays) {
        ForecastRun run = runs.findFirstByBusinessIdAndStatusOrderByBaseAtDesc(
                businessId, ForecastRunStatus.COMPLETED).orElse(null);
        if (run == null) {
            return null;
        }
        List<DailyBalanceForecast> rows =
                dailyRows.findByForecastRunIdOrderByForecastDateAsc(run.getId());
        if (rows.isEmpty()) {
            return null;
        }

        List<CurvePoint> points = rows.stream()
                .limit(maxDays)
                .map(r -> new CurvePoint(r.getForecastDate(), r.getBalanceExpected()))
                .toList();

        return new CurveWindow(points, run.openingBalanceTotal(), ConfidenceBand.of(
                rows.stream().map(DailyBalanceForecast::getConfidence)
                        .reduce(BigDecimal.ZERO, BigDecimal::add)
                        .divide(BigDecimal.valueOf(rows.size()), java.math.MathContext.DECIMAL64)));
    }

    /**
     * @param openingBalance 기준일 잔액. <b>변화분을 재는 기준</b>이라 곡선과 같은 실행에서 와야 한다
     */
    public record CurveWindow(List<CurvePoint> points, BigDecimal openingBalance,
                              ConfidenceBand confidence) {
    }
}
