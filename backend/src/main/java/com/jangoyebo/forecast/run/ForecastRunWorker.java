package com.jangoyebo.forecast.run;

import com.jangoyebo.forecast.engine.BalanceCurveCalculator;
import com.jangoyebo.forecast.planned.PlannedExecutionReconciler;
import com.jangoyebo.forecast.engine.BalanceCurveCalculator.Curve;
import com.jangoyebo.forecast.engine.BalanceCurveCalculator.CurveInput;
import com.jangoyebo.forecast.residual.ResidualProvider;
import com.jangoyebo.forecast.run.ForecastRunService.CurveInputs;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * 예측 실행 스레드 (공통규약 2.6).
 *
 * <p><b>Job 인프라를 쓰지 않는다.</b> {@code forecast} 는 {@code core.job} 을 볼 수 없고
 * ({@code core} 가 CLOSED), 예측은 결과를 폴링할 대상이 아니라 <b>다음 조회에 반영되면 되는</b>
 * 배경 작업이다. {@code JobType} 을 늘리지 않아 마이그레이션도 붙지 않는다.
 *
 * <p><b>스레드를 빈으로 등록하지 않는다.</b> Boot 의 기본 {@code taskScheduler}·
 * {@code applicationTaskExecutor} 가 {@code @ConditionalOnMissingBean} 이라, 풀 빈을 하나라도
 * 추가하면 기본 빈이 사라져 {@code JobSweeper} 가 우리 풀로 옮겨 탄다. {@code JobWorker} 가
 * 같은 이유로 {@code SmartLifecycle} 로 자기 풀을 소유한다.
 *
 * <h2>왜 5초 뒤에 도는가</h2>
 *
 * <p>CSV 200건을 올리면 변경 알림이 200번 온다. 즉시 돌면 재계산이 200회다(공통규약 2.6).
 * 표시만 남기고 5초 뒤 한 번 도는 것이 같은 결과를 훨씬 싸게 낸다. 사용자가 「지금 다시
 * 계산」을 눌러도 마찬가지라, 응답은 {@code 202} 이고 화면은 잠시 뒤 다시 조회한다.
 */
@Component
public class ForecastRunWorker implements SmartLifecycle {

    private static final Logger log = LoggerFactory.getLogger(ForecastRunWorker.class);

    /**
     * 기준일을 정하는 시간대.
     *
     * <p>앱 공용 {@code Clock} 은 {@code systemDefaultZone()} 이라 컨테이너 TZ 에 의존한다.
     * 곡선의 날짜는 사업장의 벽시계 날짜여야 하므로 {@code AccountSyncHandler} 처럼
     * 여기서 KST 를 명시한다 — 서버 시간대에 기대지 않는다.
     */
    private static final ZoneId KST = ZoneId.of("Asia/Seoul");

    private static final int SHUTDOWN_GRACE_SECONDS = 5;

    /**
     * 잔여를 추정할 수 있는 과거 데이터 하한.
     *
     * <p>워커({@code features.MIN_HISTORY})와 통계 베이스라인({@code baseline.MIN_SAMPLE_DAYS})이
     * 같은 값을 쓴다 — 2026-09-09 에 90 → 30 으로 내렸다. 싸피 금융망 계좌 기록이 30~60일이라
     * <b>서비스가 실제로 도는 구간이 바로 여기다.</b>
     */
    static final int RESIDUAL_MIN_HISTORY_DAYS = 30;

    private final ForecastRunService runService;
    private final ForecastRedisStore store;
    private final ForecastProperties properties;
    private final ResidualProvider residualProvider;
    private final ForecastSettingsService settings;
    private final PlannedExecutionReconciler executionReconciler;
    private final Clock clock;

    /** 사업장별 예약. 새 요청이 오면 이전 예약을 취소해 하나로 합친다. */
    private final Map<Long, ScheduledFuture<?>> pending = new ConcurrentHashMap<>();

    private volatile boolean running;
    private ScheduledExecutorService executor;

    public ForecastRunWorker(ForecastRunService runService, ForecastRedisStore store,
                             ForecastProperties properties, ResidualProvider residualProvider,
                             ForecastSettingsService settings,
                             PlannedExecutionReconciler executionReconciler, Clock clock) {
        this.runService = runService;
        this.store = store;
        this.properties = properties;
        this.residualProvider = residualProvider;
        this.settings = settings;
        this.executionReconciler = executionReconciler;
        this.clock = clock;
    }

    /**
     * 이 사업장의 곡선을 다시 계산해야 한다고 알린다.
     *
     * <p>여러 번 불려도 한 번 돈다. <b>호출자를 막지 않는다</b> — 요청 스레드는 표시만 남긴다.
     */
    public void requestRecalculation(Long businessId) {
        store.markDirty(businessId, properties.dirtyTtl());
        if (!running) {
            // 테스트나 워커를 끈 환경. 표시는 남았으니 배치가 나중에 집는다.
            return;
        }
        schedule(businessId, properties.debounce().toMillis());
    }

    private void schedule(Long businessId, long delayMillis) {
        pending.compute(businessId, (id, previous) -> {
            if (previous != null) {
                previous.cancel(false);
            }
            return executor.schedule(() -> {
                pending.remove(id);
                execute(id);
            }, delayMillis, TimeUnit.MILLISECONDS);
        });
    }

    /**
     * 한 사업장의 곡선을 계산해 저장한다.
     *
     * <p>테스트가 스레드를 띄우지 않고 직접 부른다({@code JobWorker.pollOnce} 와 같은 방식).
     * {@code public} 인 것은 <b>다른 패키지의 API 테스트</b>가 기준 실행을 동기로 만들어야
     * 하기 때문이다 — 시나리오(11장)는 완료된 실행 위에서만 계산되므로 그것 없이는 어떤
     * 시나리오 테스트도 {@code 409} 만 받는다.
     *
     * <p><b>운영 코드는 이것을 부르지 않는다.</b> 재계산이 필요하면
     * {@link #requestRecalculation} 으로 표시만 남긴다 — 그쪽이 디바운스와 잠금을 거친다.
     */
    public void execute(Long businessId) {
        if (!store.tryLock(businessId, properties.lockTtl())) {
            // 이미 돌고 있다. dirty 는 남아 있으므로 그쪽이 끝나고 다시 집는다.
            return;
        }
        LocalDate baseDate = LocalDate.now(clock.withZone(KST));
        Instant baseAt = clock.instant();
        try {
            store.clearDirty(businessId);
            runOnce(businessId, baseDate, baseAt);
        } catch (RuntimeException e) {
            log.warn("예측 실행 실패 — 사업장 {}: {}", businessId, e.toString());
            safeFail(businessId, baseAt, baseDate, e);
        } finally {
            store.unlock(businessId);
        }
    }

    private void runOnce(Long businessId, LocalDate baseDate, Instant baseAt) {
        // ⓪ 자동 반영한 거래를 먼저 정확히 붙인다(API 9.6). 순서가 뒤집히면 ① 의 매처가
        //    이 거래를 엉뚱한 예정 거래에 붙이거나 닻 재조정의 입력으로 써서, 서버가 꺼져
        //    있어 늦게 나간 것 때문에 앞으로의 일정이 통째로 밀린다.
        reconcileExecutionsQuietly(businessId, baseDate);

        // ① 매처를 먼저 돌린다. 순서가 뒤집히면 방금 매칭된 예정 거래가 아래 loadInputs 에
        //    그대로 잡혀 곡선에 남고, 그 거래는 이미 잔액에 반영돼 있으므로 이중 계상이 된다.
        //    잔여 집계도 여기서 함께 한다 — 매칭이 비면 잔여가 「실제 거래 전부」가 된다.
        ForecastRunService.ResidualInputs residual = runService.loadResidual(businessId, baseDate);

        // ② 매칭이 반영된 뒤의 원장을 읽는다. MATCHED 는 여기서 빠진다.
        CurveInputs inputs = runService.loadInputs(businessId, baseDate);
        if (!inputs.hasConnectedAccount()) {
            // 계좌가 없으면 시작 잔액이 없다. 0에서 출발하는 곡선은 없는 공백을 만든다.
            //
            // 새로 만들지 않는 것만으로는 부족하다 — 그냥 돌아가면 마지막 계좌를 해제하기
            // 직전에 계산된 실행이 남아 조회에 계속 나간다(사라진 계좌의 잔액이 들어간
            // 총잔액과 그 계좌로 만든 공백 경보다). 지우고 간다.
            log.debug("연결된 계좌가 없어 예측을 건너뛴다 — 사업장 {}", businessId);
            runService.clearForBusiness(businessId);
            return;
        }

        // ③ 잔여를 태운다. 모델 → 통계 → 미적용 순으로 내려가며, 어디로 가든 곡선은 나온다.
        LocalDate forecastFrom = baseDate.plusDays(1);
        ResidualProvider.Result provided = residualProvider.provide(
                residual.series(), forecastFrom, properties.horizonDays(),
                residualMode(businessId), properties.judgmentAlpha(), businessId);

        Curve curve = BalanceCurveCalculator.compute(new CurveInput(
                baseDate, properties.horizonDays(), inputs.openingBalance(),
                inputs.ledger(), provided.quantiles(), provided.alpha()));

        ForecastBasis basis = new ForecastBasis(
                inputs.summary(),
                residualBasis(provided, curve),
                inputs.snapshots(),
                adjustments(provided, curve),
                warnings(inputs, provided),
                new ForecastBasis.Engine(BalanceCurveCalculator.ENGINE_VERSION, inputs.allBalancesKnown()));

        ForecastRun run = runService.complete(businessId, baseAt, baseDate, inputs, curve, basis,
                modelVersion(provided), appliedOptions(provided),
                provided.source(), provided.historyDays());

        log.info("예측 실행 {} 완료 — 사업장 {} / 예정 {}건 / 시작잔액 {} / 잔여 {}",
                run.getId(), businessId, inputs.ledger().size(), inputs.openingBalance(),
                provided.source());
    }

    /**
     * 정확 매칭이 실패해도 곡선은 낸다.
     *
     * <p>붙이는 것은 정확도를 올리는 일이지 곡선을 만드는 조건이 아니다 — 여기서 던지면
     * <b>예보가 통째로 안 나온다.</b> 다음 실행이 다시 시도한다({@code PlannedMatchService}
     * 가 매칭 실패를 삼키는 것과 같은 판단).
     */
    private void reconcileExecutionsQuietly(Long businessId, LocalDate baseDate) {
        try {
            executionReconciler.reconcile(businessId, baseDate);
        } catch (RuntimeException e) {
            log.warn("자동 반영 거래 정확 매칭을 건너뛴다 — 사업장 {}: {}", businessId, e.toString());
        }
    }

    /**
     * 사용자가 사유를 볼 수 있어야 한다 — 「예보가 갱신되지 않는다」만으로는 진단이 안 된다.
     *
     * <p>잔여 쪽 사유는 {@link ResidualProvider} 가 이미 담아 왔다. 여기서는 원장 쪽만 더한다.
     */
    private static List<String> warnings(CurveInputs inputs, ResidualProvider.Result residual) {
        List<String> warnings = new java.util.ArrayList<>(residual.warnings());
        if (!inputs.allBalancesKnown()) {
            warnings.add("일부 계좌의 잔액을 확인하지 못해 신뢰 수준을 낮췄습니다.");
        }
        if (inputs.ledger().isEmpty()) {
            warnings.add("예측 기간에 예정된 거래가 없어 잔액이 현재 수준으로 유지된다고 보았습니다.");
        }
        return warnings;
    }

    /**
     * 사용자 토글이 상한을 정한다 (API 10.7).
     *
     * <p>끄면 {@code NONE} 이고 곡선은 예정 거래만으로 난다 — 인수 조건이 그것을 요구한다.
     * 켜면 서버 설정({@code jango.forecast.residual-mode})이 상한이고, 실제 경로는
     * 컨텍스트 길이와 워커 상태가 정한다.
     */
    private ResidualProvider.Mode residualMode(Long businessId) {
        if (!settings.applyResidualModel(businessId)) {
            return ResidualProvider.Mode.NONE;
        }
        return switch (properties.residualMode()) {
            case NONE -> ResidualProvider.Mode.NONE;
            case BASELINE -> ResidualProvider.Mode.BASELINE;
            case MODEL -> ResidualProvider.Mode.MODEL;
        };
    }

    /**
     * {@code engine-<x.y>/<model>-<ver>}. 모델 미사용은 {@code /none}.
     *
     * <p>워커가 준 문자열을 <b>가공하지 않는다</b> — {@code <ver>} 의 학습 데이터 지문이
     * 재현성 보증의 일부다(API 10.1).
     */
    private static String modelVersion(ResidualProvider.Result residual) {
        if (!residual.applied()) {
            return ForecastRunService.engineOnlyVersion();
        }
        return "engine-%s/%s-%s".formatted(BalanceCurveCalculator.ENGINE_VERSION,
                residual.modelName(), residual.modelVersion());
    }

    private static ForecastBasis.Residual residualBasis(ResidualProvider.Result residual, Curve curve) {
        Integer historyDays = residual.historyDays() == 0 ? null : residual.historyDays();
        if (!residual.applied()) {
            return ForecastBasis.Residual.notApplied(
                    residual.source().name(), historyDays, residual.alpha());
        }
        return new ForecastBasis.Residual(true, residual.source().name(),
                residual.modelName(), residual.modelVersion(), historyDays, residual.alpha(),
                range(curve.residualTotals().residualDeposit()),
                range(curve.residualTotals().residualWithdrawal()));
    }

    /**
     * 30일 합계.
     *
     * <p>곡선이 α 를 적용한 대표값만 누적하므로 여기서 {@code min}·{@code max} 를 따로 낼 수
     * 없다. 세 값을 같게 두는 것이 <b>없는 정밀도를 지어내지 않는</b> 쪽이다 — 분위수 합계가
     * 필요해지면 {@code Totals} 에 축을 더한다.
     */
    private static java.math.BigDecimal won(java.math.BigDecimal value) {
        return value.setScale(0, java.math.RoundingMode.HALF_UP);
    }

    private static ForecastBasis.Range range(java.math.BigDecimal total) {
        java.math.BigDecimal rounded = won(total);
        return new ForecastBasis.Range(rounded, rounded, rounded);
    }

    /** P0 에서 채우는 것은 {@code MODEL_RESIDUAL} 하나다. 나머지 5종은 P1 기능과 함께다. */
    private static List<ForecastBasis.Adjustment> adjustments(ResidualProvider.Result residual, Curve curve) {
        if (!residual.applied()) {
            return List.of();
        }
        String description = "반복 패턴으로 설명되지 않는 잔여 현금흐름 반영 — %d일 합계 입금 %,d원 · 지출 %,d원"
                .formatted(curve.points().size(),
                        won(curve.residualTotals().residualDeposit()).longValue(),
                        won(curve.residualTotals().residualWithdrawal()).longValue());
        return List.of(new ForecastBasis.Adjustment("MODEL_RESIDUAL", description, List.of(),
                residual.modelName(), residual.modelVersion()));
    }

    /** P1 옵션 둘은 해당 기능이 서기 전까지 항상 {@code false} 다(API 10.7). */
    private static Map<String, Object> appliedOptions(ResidualProvider.Result residual) {
        return Map.of(
                "applySeasonality", false,
                "applyVatReserve", false,
                "applyResidualModel", residual.applied());
    }

    private void safeFail(Long businessId, Instant baseAt, LocalDate baseDate, RuntimeException cause) {
        try {
            runService.fail(businessId, baseAt, baseDate, reasonOf(cause));
        } catch (RuntimeException nested) {
            // 실패 기록까지 실패하면 로그가 마지막 수단이다. 원래 예외를 삼키지 않는다.
            log.error("예측 실패 기록도 남기지 못했다 — 사업장 {}", businessId, nested);
        }
    }

    private static String reasonOf(RuntimeException cause) {
        String message = cause.getMessage();
        return message == null || message.isBlank() ? cause.getClass().getSimpleName() : message;
    }

    // --- SmartLifecycle -----------------------------------------------------

    @Override
    public boolean isAutoStartup() {
        return properties.enabled();
    }

    @Override
    public synchronized void start() {
        if (running) {
            return;
        }
        executor = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "forecast-worker");
            thread.setDaemon(true);
            return thread;
        });
        running = true;
        log.info("예측 실행 스레드 기동 — 디바운스 {}, 기간 {}일, 잔여 {}",
                properties.debounce(), properties.horizonDays(), properties.residualMode());
    }

    @Override
    public synchronized void stop() {
        if (!running) {
            return;
        }
        running = false;
        pending.values().forEach(future -> future.cancel(false));
        pending.clear();
        executor.shutdown();
        try {
            if (!executor.awaitTermination(SHUTDOWN_GRACE_SECONDS, TimeUnit.SECONDS)) {
                executor.shutdownNow();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            executor.shutdownNow();
        }
        log.info("예측 실행 스레드 종료");
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    /** 남은 예약 수. 테스트가 디바운스 합쳐짐을 확인한다. */
    int pendingCount() {
        return pending.size();
    }
}
