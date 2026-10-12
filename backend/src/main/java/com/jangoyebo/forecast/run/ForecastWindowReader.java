package com.jangoyebo.forecast.run;

import com.jangoyebo.common.error.BusinessException;
import com.jangoyebo.common.error.ErrorCode;
import com.jangoyebo.core.AccountBalances;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 최신 완료 실행을 읽어 조회 창으로 자른다 (공통규약 23장 캐시 전략).
 *
 * <p><b>계산하지 않는다.</b> 실행이 없으면 만들지 않고 알린다 — 연결된 계좌가 없으면 애초에
 * 곡선을 만들 수 없으므로 {@code 422}, 계좌는 있는데 실행만 없으면 재계산을 예약하고
 * {@code 409} 다. 조회가 직접 계산하면 공통규약이 막은 경로가 된다.
 *
 * <p>{@link ForecastQueryService} 의 private 메서드였던 것을 꺼냈다. 시나리오(11장)가
 * <b>같은 규칙</b>으로 같은 실행을 읽어야 하기 때문이다. 두 벌로 갈라지면 대시보드는
 * 곡선을 보여 주는데 시나리오는 「아직 준비되지 않았다」고 말하는 상태가 생긴다.
 */
@Service
public class ForecastWindowReader {

    /** API 10.1 — 7·14·30 중 하나. 시나리오의 {@code days} 도 같은 집합이다(11.1·ERD). */
    private static final Set<Integer> ALLOWED_DAYS = Set.of(7, 14, 30);

    private final ForecastRunRepository runs;
    private final DailyBalanceForecastRepository dailyRows;
    private final AccountBalances accountBalances;
    private final ForecastRunWorker worker;

    public ForecastWindowReader(ForecastRunRepository runs,
                                DailyBalanceForecastRepository dailyRows,
                                AccountBalances accountBalances,
                                ForecastRunWorker worker) {
        this.runs = runs;
        this.dailyRows = dailyRows;
        this.accountBalances = accountBalances;
        this.worker = worker;
    }

    @Transactional(readOnly = true)
    public ForecastWindow latest(Long businessId, int days) {
        requireAllowedDays(days);

        // 계좌부터 본다. 저장된 실행이 있어도 연결된 계좌가 없으면 그 곡선은 사라진 계좌로
        // 계산된 값이다 — 실행 유무를 먼저 물으면 그 경우가 200 으로 빠져나간다.
        // 워커가 지우고 가지만(ForecastRunService.clearForBusiness) 디바운스만큼 틈이 있고,
        // 그 틈에 나가는 것이 하필 총잔액이라 읽는 쪽에서도 막는다.
        if (accountBalances.connectedBalances(businessId).isEmpty()) {
            throw new BusinessException(ErrorCode.FORECAST_INSUFFICIENT_DATA);
        }

        ForecastRun run = runs.findFirstByBusinessIdAndStatusOrderByBaseAtDesc(
                businessId, ForecastRunStatus.COMPLETED).orElse(null);
        if (run == null) {
            // 첫 방문이 첫 실행을 건다. 화면은 잠시 뒤 다시 부른다(화면구성도 SC-03-1).
            worker.requestRecalculation(businessId);
            throw new BusinessException(ErrorCode.FORECAST_NOT_READY);
        }

        List<DailyBalanceForecast> rows = dailyRows.findByForecastRunIdOrderByForecastDateAsc(run.getId());
        if (rows.isEmpty()) {
            throw new BusinessException(ErrorCode.FORECAST_NOT_READY);
        }
        return new ForecastWindow(run, rows.subList(0, Math.min(days, rows.size())));
    }

    /** 창 길이 검증만 따로 쓰는 자리가 있다 — 본문 검증을 실행 조회보다 먼저 하기 위해서다. */
    public static void requireAllowedDays(int days) {
        if (!ALLOWED_DAYS.contains(days)) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED,
                    "days 는 7·14·30 중 하나여야 합니다.", Map.of("days", days));
        }
    }
}
