package com.jangoyebo.forecast.run;

import com.jangoyebo.core.SafetyLineOutlook;
import com.jangoyebo.forecast.engine.BalanceCurveCalculator.DailyPoint;
import com.jangoyebo.forecast.engine.CashGapDetector;
import com.jangoyebo.forecast.engine.RiskLevelRule;
import com.jangoyebo.forecast.engine.SafetyLineDetector;
import com.jangoyebo.forecast.engine.SafetyLineDetector.SafetyLineSegment;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * {@link SafetyLineOutlook} 구현 — API 12.1 의 {@code current} 블록.
 *
 * <p><b>{@code forecast} 가 구현하고 {@code core} 가 쓴다.</b> 방향이 반대인 이유는 포트
 * javadoc 에 있다.
 *
 * <p>{@link ForecastQueryService} 와 같은 규칙으로 읽는다 — <b>계산하지 않고</b> 최신
 * {@code COMPLETED} 실행을 읽기만 한다(공통규약 2.6). 다만 실행이 없을 때 409 를 내거나
 * 재계산을 예약하지는 않는다. 안전선 조회는 설정을 보러 온 것이라 곡선이 없으면 그 부분만
 * 비우면 된다.
 */
@Service
public class SafetyLineOutlookService implements SafetyLineOutlook {

    private final ForecastRunRepository runs;
    private final DailyBalanceForecastRepository dailyRows;

    public SafetyLineOutlookService(ForecastRunRepository runs, DailyBalanceForecastRepository dailyRows) {
        this.runs = runs;
        this.dailyRows = dailyRows;
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<Current> of(Long businessId, BigDecimal safetyLine) {
        ForecastRun run = runs.findFirstByBusinessIdAndStatusOrderByBaseAtDesc(
                businessId, ForecastRunStatus.COMPLETED).orElse(null);
        if (run == null) {
            return Optional.empty();
        }
        List<DailyBalanceForecast> rows = dailyRows.findByForecastRunIdOrderByForecastDateAsc(run.getId());
        if (rows.isEmpty()) {
            return Optional.empty();
        }

        List<DailyPoint> points = new ArrayList<>(rows.size());
        for (DailyBalanceForecast row : rows) {
            points.add(row.toPoint());
        }

        DailyPoint lowest = points.get(0);
        for (DailyPoint p : points) {
            // 동률이면 빠른 날을 남긴다 (API 10.1 lowestPoint 와 같은 규칙).
            if (p.balanceExpected().compareTo(lowest.balanceExpected()) < 0) {
                lowest = p;
            }
        }

        BigDecimal currentBalance = run.openingBalanceTotal();

        // 하회가 없으면 셋 다 비운다 — 0 으로 채우면 「딱 안전선에 붙어 있다」로 읽힌다.
        BigDecimal shortfall = null;
        java.time.LocalDate firstBelow = null;
        List<Cause> causes = List.of();

        List<SafetyLineSegment> segments = SafetyLineDetector.segments(points, safetyLine);
        if (!segments.isEmpty()) {
            SafetyLineSegment first = segments.get(0);
            firstBelow = first.firstBelowDate();
            // 부족액은 30일 최저 기준이다(12.1 예시 — 안전선 200만, 최저 120만, 부족 80만).
            // 첫 구간이 가장 깊은 구간이 아닐 수 있어 최저점으로 다시 뺀다.
            shortfall = safetyLine.subtract(lowest.balanceExpected());
            causes = causesOf(first, run.getBasis());
        }

        return Optional.of(new Current(
                won(currentBalance),
                RiskLevelRule.of(currentBalance, safetyLine),
                won(lowest.balanceExpected()),
                lowest.date(),
                won(shortfall),
                firstBelow,
                causes));
    }

    /**
     * 첫 하회 구간의 원인. {@link ForecastQueryService} 의 공백 원인과 같은 문구 규칙이다 —
     * 두 화면이 같은 예정 거래를 다르게 부르면 사용자가 다른 거래로 읽는다.
     */
    private static List<Cause> causesOf(SafetyLineSegment segment, ForecastBasis basis) {
        List<Cause> causes = new ArrayList<>();
        for (CashGapDetector.Cause cause : SafetyLineDetector.causes(segment, basis.ledger())) {
            if (cause.isDepositDrought()) {
                causes.add(new Cause(cause.date(), "해당 기간 예정 입금 없음", null));
                continue;
            }
            causes.add(new Cause(cause.date(),
                    "%s %,d원 출금".formatted(basis.nameOf(cause.plannedTransactionId()),
                            won(cause.amount()).longValue()),
                    cause.plannedTransactionId()));
        }
        return causes;
    }

    /** 응답 금액은 정수 원이다 (ERD 11장 — 출력 직전에 한 번만 반올림). */
    private static BigDecimal won(BigDecimal value) {
        return value == null ? null : value.setScale(0, RoundingMode.HALF_UP);
    }
}
