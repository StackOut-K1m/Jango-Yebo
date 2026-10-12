package com.jangoyebo.forecast.run;

import com.jangoyebo.core.CashGapOutlook;
import com.jangoyebo.forecast.engine.BalanceCurveCalculator.DailyPoint;
import com.jangoyebo.forecast.engine.CashGapDetector;
import com.jangoyebo.forecast.engine.CashGapDetector.CashGapSegment;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * {@link CashGapOutlook} 구현 — 지원 제도 추천(17.3)에 가장 가까운 공백 하나를 준다.
 *
 * <h2>{@code ForecastQueryService.cashGaps} 를 부르지 않는다</h2>
 *
 * <p>그쪽은 {@link ForecastWindowReader#latest} 를 타는데, 완료된 실행이 없으면 <b>예외를
 * 던지는 데 그치지 않고 예측 재계산을 예약한다.</b> 조회가 예측을 돌리면 공통규약 2.6 이
 * 막은 경로가 되고({@code SafetyLineOutlook} 이 같은 이유로 같은 선택을 했다), 지원 제도
 * 화면을 여는 것만으로 워커가 깨어난다.
 *
 * <p>게다가 그 예외를 여기서 잡으면 <b>바깥 트랜잭션이 rollback-only 로 표시돼</b> 호출한
 * 쪽이 {@code UnexpectedRollbackException} 으로 500 을 받는다. 실제로 그렇게 만들었다가
 * 테스트 일곱 개가 한꺼번에 500 을 냈다 — {@code SupportProgramStructuringService} 주석이
 * 적어 둔 것과 같은 함정이다.
 *
 * <p>그래서 <b>곡선을 직접 읽는다.</b> 없으면 조용히 {@link Optional#empty()} 이고, 17.3 은
 * 공백 없이 추천을 낸다.
 */
@Service
public class CashGapOutlookService implements CashGapOutlook {

    private final ForecastRunRepository runs;
    private final DailyBalanceForecastRepository dailyRows;

    public CashGapOutlookService(ForecastRunRepository runs,
                                 DailyBalanceForecastRepository dailyRows) {
        this.runs = runs;
        this.dailyRows = dailyRows;
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<NearestGap> nearestGap(Long businessId, int days) {
        ForecastRun run = runs.findFirstByBusinessIdAndStatusOrderByBaseAtDesc(
                businessId, ForecastRunStatus.COMPLETED).orElse(null);
        if (run == null) {
            return Optional.empty();
        }
        List<DailyBalanceForecast> rows = dailyRows.findByForecastRunIdOrderByForecastDateAsc(
                run.getId());
        if (rows.isEmpty()) {
            return Optional.empty();
        }

        List<DailyPoint> points = new ArrayList<>(rows.size());
        for (DailyBalanceForecast row : rows) {
            if (points.size() >= days) {
                break;
            }
            points.add(row.toPoint());
        }

        // 판정은 10.2 와 같은 검출기를 쓴다. 여기서 「음수면 공백」을 다시 짜면 같은 화면의
        // 두 숫자가 서로 다른 규칙으로 계산되는 날이 온다.
        List<CashGapSegment> segments = CashGapDetector.detect(points);
        if (segments.isEmpty()) {
            return Optional.empty();
        }

        CashGapSegment earliest = segments.get(0);
        for (CashGapSegment segment : segments) {
            if (segment.firstShortfallDate().isBefore(earliest.firstShortfallDate())) {
                earliest = segment;
            }
        }
        // 원 단위로 맞춘다 — 10.2 응답과 같은 눈금이어야 화면 두 곳의 금액이 안 어긋난다.
        BigDecimal shortfall = earliest.maxShortfallAmount() == null
                ? null
                : earliest.maxShortfallAmount().setScale(0, RoundingMode.HALF_UP);
        return Optional.of(
                new NearestGap(run.getBaseAt(), earliest.firstShortfallDate(), shortfall));
    }
}
