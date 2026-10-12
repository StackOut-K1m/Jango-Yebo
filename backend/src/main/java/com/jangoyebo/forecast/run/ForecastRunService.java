package com.jangoyebo.forecast.run;

import com.jangoyebo.core.AccountBalances;
import com.jangoyebo.core.CashGapAlerts;
import com.jangoyebo.core.SafetyLineAlerts;
import com.jangoyebo.core.SafetyLines;
import com.jangoyebo.core.TransactionHistory;
import com.jangoyebo.forecast.engine.BalanceCurveCalculator;
import com.jangoyebo.forecast.engine.BalanceCurveCalculator.Curve;
import com.jangoyebo.forecast.engine.BalanceCurveCalculator.DailyPoint;
import com.jangoyebo.forecast.engine.BalanceCurveCalculator.PlannedItem;
import com.jangoyebo.forecast.engine.CashGapDetector;
import com.jangoyebo.forecast.engine.CashGapDetector.CashGapSegment;
import com.jangoyebo.forecast.engine.ConfidencePolicy;
import com.jangoyebo.forecast.engine.ResidualSource;
import com.jangoyebo.forecast.engine.SafetyLineDetector;
import com.jangoyebo.forecast.engine.SafetyLineDetector.SafetyLineSegment;
import com.jangoyebo.forecast.pattern.PatternStatus;
import com.jangoyebo.forecast.pattern.RecurringPattern;
import com.jangoyebo.forecast.pattern.RecurringPatternRepository;
import com.jangoyebo.forecast.pattern.RecurringPatternTransactionRepository;
import com.jangoyebo.forecast.planned.PlannedMatchService;
import com.jangoyebo.forecast.planned.PlannedStatus;
import com.jangoyebo.forecast.planned.PlannedTransaction;
import com.jangoyebo.forecast.planned.PlannedTransactionRepository;
import com.jangoyebo.forecast.residual.ResidualSeries;
import com.jangoyebo.forecast.residual.ResidualSeriesAggregator;
import com.jangoyebo.forecast.run.ForecastRun.OpeningBalance;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 예측 실행의 <b>짧은 트랜잭션들</b>.
 *
 * <p>{@code AccountSyncService} 와 같은 이유로 {@link ForecastRunWorker} 에서 갈라냈다 —
 * 워커는 트랜잭션 밖에서 돌고(잔여 모델 호출이 15초까지 간다), 같은 클래스 안에서
 * {@code @Transactional} 메서드를 자기가 부르면 프록시를 안 타 트랜잭션이 열리지 않는다.
 *
 * <p><b>계산은 여기 없다.</b> 읽고({@link #loadInputs}) 저장한다({@link #complete}).
 * 계산은 {@link BalanceCurveCalculator} 가 순수 함수로 한다.
 */
@Service
public class ForecastRunService {

    private static final Logger log = LoggerFactory.getLogger(ForecastRunService.class);

    /**
     * 잔여 집계·매칭이 훑을 과거 범위의 상한. 워커의 {@code LOOKBACK} 과 같은 값이다(20.11).
     * P0 에서는 {@code basis.residual.historyDays} 를 채우는 데만 쓴다.
     */
    static final int LOOKBACK_DAYS = 365;

    private final ForecastRunRepository runs;
    private final DailyBalanceForecastRepository dailyRows;
    private final PlannedTransactionRepository planned;
    private final RecurringPatternRepository patterns;
    private final RecurringPatternTransactionRepository patternTransactions;
    private final AccountBalances accountBalances;
    private final CashGapAlerts cashGapAlerts;
    private final SafetyLineAlerts safetyLineAlerts;
    private final SafetyLines safetyLines;
    private final ForecastProperties properties;
    private final PlannedMatchService matchService;
    private final TransactionHistory history;

    public ForecastRunService(ForecastRunRepository runs,
                              DailyBalanceForecastRepository dailyRows,
                              PlannedTransactionRepository planned,
                              RecurringPatternRepository patterns,
                              AccountBalances accountBalances,
                              CashGapAlerts cashGapAlerts,
                              SafetyLineAlerts safetyLineAlerts,
                              SafetyLines safetyLines,
                              ForecastProperties properties,
                              PlannedMatchService matchService,
                              TransactionHistory history,
                              RecurringPatternTransactionRepository patternTransactions) {
        this.runs = runs;
        this.dailyRows = dailyRows;
        this.planned = planned;
        this.patterns = patterns;
        this.patternTransactions = patternTransactions;
        this.accountBalances = accountBalances;
        this.cashGapAlerts = cashGapAlerts;
        this.safetyLineAlerts = safetyLineAlerts;
        this.safetyLines = safetyLines;
        this.properties = properties;
        this.matchService = matchService;
        this.history = history;
    }

    /**
     * 잔여 집계 결과.
     *
     * @param series      거래가 하나도 없으면 {@code null}
     * @param matchedIds  이번 실행에서 뺀 거래. 진단용
     * @param historyDays 워커 하한(30일) 판정과 {@code basis.residual.historyDays} 의 값
     */
    public record ResidualInputs(ResidualSeries series, Set<Long> matchedIds, int historyDays) {
    }

    /**
     * 곡선 계산에 필요한 것을 한 번에 읽는다.
     *
     * <p>예정 거래는 {@code EXPECTED}·{@code CONFIRMED} 만 본다. {@code MATCHED} 는 실제로
     * 일어나 이미 잔액에 들어 있고, {@code CANCELLED}·{@code MISSED} 는 일어나지 않는다 —
     * 넣으면 이중 계상이다.
     *
     * <p><b>기준일 당일부터 읽는다.</b> 당일자는 아직 일어나지 않아 시작 잔액에 없다 —
     * 여기서 빼면 곡선 어디에도 남지 않아 전 구간이 그 금액만큼 높아진다. 계산기가 첫 점에
     * 합산한다. 이미 일어난 것은 앞서 돈 매처가 {@code MATCHED} 로 바꿔 여기서 걸러지므로
     * 창을 넓혀도 이중 계상은 생기지 않는다.
     *
     * <p>신뢰도는 패턴에서 <b>전파</b>한다. {@code PLANNED_TRANSACTION} 에 그 컬럼이 없다.
     */
    @Transactional(readOnly = true)
    public CurveInputs loadInputs(Long businessId, LocalDate baseDate) {
        List<AccountBalances.AccountBalance> balances = accountBalances.connectedBalances(businessId);

        List<PlannedTransaction> items = planned.findByBusinessIdAndStatusInAndExpectedDateBetween(
                businessId,
                List.of(PlannedStatus.EXPECTED, PlannedStatus.CONFIRMED),
                baseDate,
                baseDate.plusDays(properties.horizonDays()));

        Map<Long, BigDecimal> patternConfidence = patternConfidence(items);

        List<PlannedItem> ledger = new ArrayList<>(items.size());
        List<ForecastBasis.PlannedSnapshot> snapshots = new ArrayList<>(items.size());
        for (PlannedTransaction item : items) {
            BigDecimal confidence = confidenceOf(item, patternConfidence);
            ledger.add(new PlannedItem(item.getId(), item.getExpectedDate(), item.getFlowType(),
                    item.getAmountMin(), item.getAmountExpected(), item.getAmountMax(), confidence));
            snapshots.add(new ForecastBasis.PlannedSnapshot(item.getId(), item.getName(),
                    item.getSourceType().name(), item.getFlowType().name(), item.getCategoryId(),
                    item.getOriginalExpectedDate(), item.getExpectedDate(),
                    item.getAmountMin(), item.getAmountExpected(), item.getAmountMax(), confidence));
        }

        return new CurveInputs(balances, ledger, snapshots, summaryOf(items));
    }

    /**
     * 잔여 시계열을 집계한다 (설계노트 M-1).
     *
     * <p><b>매처를 먼저 돌린다.</b> 매칭이 비면 잔여가 「실제 거래 전부」가 되어 예정 거래를
     * 두 번 센다 — 이 순서가 그 사고를 막는 유일한 장치다.
     *
     * <p>거래는 {@code TransactionHistory} 로 한 번만 읽는다. 그 SQL 이
     * {@code is_excluded = FALSE} 로 걸러 주므로 이식 규칙 하나가 공짜다.
     *
     * <p>창은 첫 거래일부터 기준일까지이고 최대 365일이다. <b>거래가 30일치도 없으면
     * 그대로 짧은 시계열을 낸다</b> — 잔여를 쓸지 말지는 그다음 단계가 정한다.
     */
    @Transactional
    public ResidualInputs loadResidual(Long businessId, LocalDate baseDate) {
        Set<Long> matchedIds = new HashSet<>(matchService.matchAndCollect(businessId, baseDate));
        matchedIds.addAll(explainedByConfirmedPatterns(businessId));

        LocalDate from = baseDate.minusDays(LOOKBACK_DAYS - 1L);
        List<TransactionHistory.HistoricalTransaction> transactions =
                history.findForPatternDetection(businessId, from, baseDate);
        if (transactions.isEmpty()) {
            return new ResidualInputs(null, matchedIds, 0);
        }

        // 첫 거래보다 앞을 0 으로 채우면 「거래가 없던 날」과 「계좌가 없던 날」이 섞인다.
        LocalDate start = transactions.get(0).date();
        if (start.isBefore(from)) {
            start = from;
        }
        Set<Long> accountIds = connectedAccountIds(businessId);
        ResidualSeries series = ResidualSeriesAggregator.aggregate(
                transactions, matchedIds, accountIds, start, baseDate);

        log.debug("잔여 집계 — 사업장 {} / {}일 / 예정거래가 설명한 비율 {}",
                businessId, series.historyDays(),
                ResidualSeriesAggregator.explainedRatio(transactions, matchedIds));
        return new ResidualInputs(series, matchedIds, series.historyDays());
    }

    /**
     * <b>확정된 패턴이 설명하는 과거 거래</b> (S15P21C105-151).
     *
     * <p>잔여는 {@code 실제 − 예정 거래에 매칭된 것}인데, 예정 거래는 승인 시점부터
     * <b>앞으로만</b> 만들어진다. 과거에는 뺄 대상이 없어서 갓 승인한 패턴의 과거 거래가
     * 잔여에 통째로 남고, 그것이 예정 투영과 겹쳐 <b>이중 계상</b>된다(설계노트 M-1).
     *
     * <p>실측(하노식당 · 2026-09-11): 거래 313건 중 310건이 패턴으로 설명되는데 매칭은 0건이라
     * 잔여가 313건 전부였다. 그 입력으로 모델이 낸 30일 출금이 430,549원인데, 제대로 뺀
     * 잔여(3건)를 주면 18,908원이다 — <b>23배</b>를 예정 투영과 두 번 뺐다.
     *
     * <p><b>확정된 것만 뺀다.</b> 후보는 곡선에 안 들어가므로 그 거래는 잔여에 남아야 한다.
     * 빼면 반대로 곡선이 아래로 기운다.
     *
     * <p>탐지 창이 180일이던 동안은 <b>그보다 오래된 거래를 못 뺐다.</b> 이제 탐지도 365일을
     * 훑어({@code RecurringPatternService.LOOKBACK_DAYS}) 잔여 창 전체를 덮는다. 두 상수는
     * <b>같이 움직여야 한다</b> — 탐지가 짧아지면 그 앞 구간이 다시 안 빠진다.
     */
    private Set<Long> explainedByConfirmedPatterns(Long businessId) {
        List<Long> confirmed = patterns
                .findByBusinessIdAndStatusOrderByConfidenceDesc(businessId, PatternStatus.CONFIRMED)
                .stream().map(RecurringPattern::getId).toList();
        return confirmed.isEmpty() ? Set.of() : patternTransactions.findTransactionIds(confirmed);
    }

    private Set<Long> connectedAccountIds(Long businessId) {
        Set<Long> ids = new HashSet<>();
        for (AccountBalances.AccountBalance balance : accountBalances.connectedBalances(businessId)) {
            ids.add(balance.accountId());
        }
        return ids;
    }

    /**
     * 실행과 곡선을 저장하고 공백 알림을 맞춘다. <b>한 트랜잭션</b>이다 — 곡선은 저장됐는데
     * 알림이 옛 상태로 남으면 화면의 두 영역이 서로 다른 말을 한다.
     *
     * <p>30행이라 JPA {@code saveAll} 로 충분하다. 대량 삽입기를 쓰지 않는 이유는 같은
     * 테이블에 JPA 와 raw JDBC 쓰기 경로를 섞으면 플러시 순서가 어긋나기 때문이다
     * ({@code TransactionBulkWriter} 의 경고).
     */
    @Transactional
    public ForecastRun complete(Long businessId, Instant baseAt, LocalDate baseDate,
                                CurveInputs inputs, Curve curve, ForecastBasis basis,
                                String modelVersion, Map<String, Object> appliedOptions,
                                ResidualSource source, int historyDays) {
        ForecastRun run = runs.save(ForecastRun.completed(businessId, baseAt,
                baseDate.plusDays(1), baseDate.plusDays(properties.horizonDays()),
                modelVersion, appliedOptions, openingBalances(inputs), basis));

        List<DailyBalanceForecast> rows = new ArrayList<>(curve.points().size());
        int horizon = curve.points().size();
        for (int i = 0; i < horizon; i++) {
            DailyPoint point = curve.points().get(i);
            rows.add(DailyBalanceForecast.of(run.getId(), point,
                    ConfidencePolicy.daily(i + 1, horizon, point.plannedConfidence(), source, historyDays)));
        }
        dailyRows.saveAll(rows);

        syncCashGapAlerts(businessId, run, curve, inputs.ledger());
        syncSafetyLineAlerts(businessId, run.getId(), curve.points(), inputs.ledger());
        return run;
    }

    /**
     * 연결된 계좌가 하나도 남지 않은 사업장의 저장된 곡선과 알림을 걷어낸다.
     *
     * <p><b>왜 지우는가.</b> 계좌가 0개면 워커는 새 실행을 만들 수 없다 — 시작 잔액이 없고,
     * 0에서 출발하는 곡선은 없는 공백을 만든다. 그래서 그냥 건너뛰었는데, 그러면 <b>마지막
     * 계좌를 해제·삭제하기 직전에 계산된 실행이 그대로 남아</b> 조회에 계속 나간다. 사용자는
     * 지운 계좌의 잔액이 들어간 총잔액과 그 계좌의 거래로 만든 공백 경보를 본다.
     *
     * <p>해제 한 건은 원래 {@code TransactionsChanged} 가 재계산을 걸어 덮어쓴다. 그 방식이
     * 통하지 않는 자리는 <b>마지막 하나</b>가 빠질 때뿐이고, 여기가 그 자리다.
     *
     * <p>알림도 함께 해소한다. 알림은 {@code ALERT} 행이라 곡선을 지우는 것만으로는 사라지지
     * 않는다 — 근거가 없어진 「자금 부족」이 목록에 남으면 사용자는 아직 유효한 경고로 읽는다.
     */
    @Transactional
    public void clearForBusiness(Long businessId) {
        int removed = runs.deleteByBusinessId(businessId);
        cashGapAlerts.resolveExcept(businessId, Set.of());
        safetyLineAlerts.resolveExcept(businessId, Set.of());
        if (removed > 0) {
            log.info("연결된 계좌가 없어 저장된 예측 {}건과 알림을 걷어냈다 — 사업장 {}", removed, businessId);
        }
    }

    /**
     * 실패도 남긴다 — 없으면 "왜 곡선이 그대로인가"를 나중에 설명할 수 없다.
     *
     * <p>{@code REQUIRES_NEW} 인 이유: 실패 원인이 트랜잭션 롤백일 수 있어 같은 트랜잭션에
     * 쓰면 이 기록도 함께 사라진다.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void fail(Long businessId, Instant baseAt, LocalDate baseDate, String reason) {
        runs.save(ForecastRun.failed(businessId, baseAt,
                baseDate.plusDays(1), baseDate.plusDays(properties.horizonDays()),
                engineOnlyVersion(), List.of(), reason));
    }

    /**
     * 이번 곡선의 공백과 알림을 맞춘다.
     *
     * <p>사라진 공백을 내리지 않으면 <b>해결한 것이 계속 경고로 남는다.</b> 그래서 올리는 것과
     * 내리는 것을 한 번에 한다.
     */
    private void syncCashGapAlerts(Long businessId, ForecastRun run, Curve curve, List<PlannedItem> ledger) {
        List<CashGapSegment> segments = CashGapDetector.detect(curve.points());
        Set<String> keys = new HashSet<>();

        for (CashGapSegment segment : segments) {
            String key = CashGapDetector.dedupKey(segment);
            keys.add(key);

            Map<String, Object> detail = new HashMap<>();
            detail.put("maxShortfallAmount", segment.maxShortfallAmount());
            detail.put("worstCaseShortfall", segment.worstCaseShortfall());
            detail.put("maxShortfallDate", segment.maxShortfallDate().toString());
            detail.put("durationDays", segment.durationDays());
            if (segment.expectedRecoveryDate() != null) {
                detail.put("expectedRecoveryDate", segment.expectedRecoveryDate().toString());
            }
            detail.put("causeCount", CashGapDetector.causes(segment, ledger).size());

            cashGapAlerts.raise(businessId, key,
                    CashGapMessages.title(segment), CashGapMessages.message(segment),
                    Map.of("forecastRunId", run.getId(),
                            "firstShortfallDate", segment.firstShortfallDate().toString()),
                    detail);
        }
        cashGapAlerts.resolveExcept(businessId, keys);

        if (!segments.isEmpty()) {
            log.info("예측 실행 {} — 사업장 {} 공백 {}건", run.getId(), businessId, segments.size());
        }
    }

    /**
     * 이번 곡선의 안전선 하회 구간과 알림을 맞춘다 (P1-08 · API 12.1).
     *
     * <p><b>안전선을 여기서 읽는다.</b> 계산 입력({@link CurveInputs})에 넣지 않았다 —
     * 곡선은 안전선에 의존하지 않고(공통규약 2.6), 의존하게 만들면 안전선을 바꿀 때마다
     * 재계산이 필요해진다. 알림만 그것을 본다.
     *
     * <p><b>미설정이면 {@code segments} 가 비고, 그러면 {@code resolveExcept} 가 살아 있는
     * 하회 알림을 전부 내린다.</b> 안전선을 지우는 경로는 없지만, 판정할 선이 없는데
     * 경고가 남아 있으면 근거 없는 경보가 된다.
     */
    private void syncSafetyLineAlerts(Long businessId, Long runId,
                                      List<DailyPoint> points, List<PlannedItem> ledger) {
        BigDecimal safetyLine = safetyLines.currentAmount(businessId).orElse(null);
        List<SafetyLineSegment> segments = SafetyLineDetector.alertable(points, safetyLine);
        Set<String> keys = new HashSet<>();

        for (SafetyLineSegment segment : segments) {
            String key = SafetyLineDetector.dedupKey(segment);
            keys.add(key);

            Map<String, Object> detail = new HashMap<>();
            detail.put("safetyLine", safetyLine);
            detail.put("shortfall", segment.shortfall());
            detail.put("worstShortfall", segment.worstShortfall());
            detail.put("lowestBalance", segment.lowestBalance());
            detail.put("lowestDate", segment.lowestDate().toString());
            detail.put("durationDays", segment.durationDays());
            if (segment.expectedRecoveryDate() != null) {
                detail.put("expectedRecoveryDate", segment.expectedRecoveryDate().toString());
            }
            detail.put("causeCount", SafetyLineDetector.causes(segment, ledger).size());

            safetyLineAlerts.raise(businessId, segment.riskLevel(), key,
                    SafetyLineMessages.title(segment), SafetyLineMessages.message(segment, safetyLine),
                    Map.of("forecastRunId", runId,
                            "firstBelowLineDate", segment.firstBelowDate().toString()),
                    detail);
        }
        safetyLineAlerts.resolveExcept(businessId, keys);

        if (!segments.isEmpty()) {
            log.info("예측 실행 {} — 사업장 {} 안전선 하회 {}건", runId, businessId, segments.size());
        }
    }

    /**
     * 저장된 곡선으로 하회 알림만 다시 맞춘다 — <b>재계산하지 않는다</b>
     * ({@link com.jangoyebo.core.SafetyLineChanged} 의 처리 경로).
     *
     * <p>공통규약 2.6 이 「안전선 변경」을 실행 트리거에서 뻐다. 그 판단은 그대로고,
     * 여기서 하는 일은 이미 있는 곡선을 새 안전선으로 <b>다시 읽어</b> 알림을 맞추는 것뿐이다.
     *
     * <p>실행이 아직 없으면 비교할 곡선이 없다 — 그때도 살아 있는 하회 알림은 내린다.
     * 계좌 연동을 전부 해제한 사업장에 옵 경고가 영원히 남는 것을 막는다.
     *
     * <p><b>{@code REQUIRES_NEW} 인 이유.</b> 부르는 쪽이
     * {@code @TransactionalEventListener(AFTER_COMMIT)} 라 <b>이미 커밋된 트랜잭션이
     * 아직 정리되지 않은</b> 상태다. {@code REQUIRED} 로 두면 그것에 합류해
     * <b>쓰기가 조용히 사라진다</b> — 알림이 갱신된 줄 알았는데 DB 에 안 남는다.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void resyncSafetyLineAlerts(Long businessId) {
        ForecastRun run = runs.findFirstByBusinessIdAndStatusOrderByBaseAtDesc(
                businessId, ForecastRunStatus.COMPLETED).orElse(null);
        List<DailyBalanceForecast> rows = run == null
                ? List.of()
                : dailyRows.findByForecastRunIdOrderByForecastDateAsc(run.getId());
        if (rows.isEmpty()) {
            safetyLineAlerts.resolveExcept(businessId, Set.of());
            return;
        }

        List<DailyPoint> points = new ArrayList<>(rows.size());
        for (DailyBalanceForecast row : rows) {
            points.add(row.toPoint());
        }
        syncSafetyLineAlerts(businessId, run.getId(), points, run.getBasis().ledger());
    }

    private Map<Long, BigDecimal> patternConfidence(List<PlannedTransaction> items) {
        Set<Long> patternIds = new HashSet<>();
        for (PlannedTransaction item : items) {
            if (item.getRecurringPatternId() != null) {
                patternIds.add(item.getRecurringPatternId());
            }
        }
        if (patternIds.isEmpty()) {
            return Map.of();
        }
        Map<Long, BigDecimal> byId = new HashMap<>();
        for (RecurringPattern pattern : patterns.findAllById(patternIds)) {
            byId.put(pattern.getId(), pattern.getConfidence());
        }
        return byId;
    }

    /**
     * 사용자가 확정한 것은 1.0 이다 — 사람이 "이건 일어난다"고 말한 것이라 탐지 신뢰도를
     * 덮어쓰지 않는다. 패턴이 만든 미확정 건만 그 패턴의 신뢰도를 따른다.
     */
    private static BigDecimal confidenceOf(PlannedTransaction item, Map<Long, BigDecimal> patternConfidence) {
        if (item.getStatus() == PlannedStatus.CONFIRMED || item.getRecurringPatternId() == null) {
            return BigDecimal.ONE;
        }
        return patternConfidence.getOrDefault(item.getRecurringPatternId(), BigDecimal.ONE);
    }

    private ForecastBasis.Summary summaryOf(List<PlannedTransaction> items) {
        int confirmed = 0;
        int expected = 0;
        int userAdded = 0;
        for (PlannedTransaction item : items) {
            if (item.getStatus() == PlannedStatus.CONFIRMED) {
                confirmed++;
            } else {
                expected++;
            }
            if (item.getSourceType() == com.jangoyebo.forecast.planned.PlannedSourceType.USER) {
                userAdded++;
            }
        }
        return new ForecastBasis.Summary(confirmed, expected, userAdded, referencePeriod());
    }

    private String referencePeriod() {
        LocalDate today = LocalDate.now();
        return today.minusDays(LOOKBACK_DAYS) + "~" + today;
    }

    private static List<OpeningBalance> openingBalances(CurveInputs inputs) {
        List<OpeningBalance> list = new ArrayList<>(inputs.balances().size());
        for (AccountBalances.AccountBalance balance : inputs.balances()) {
            list.add(new OpeningBalance(balance.accountId(), balance.balance(),
                    balance.asOf(), balance.source().name()));
        }
        return list;
    }

    static String engineOnlyVersion() {
        return "engine-" + BalanceCurveCalculator.ENGINE_VERSION + "/none";
    }

    /** 한 번의 읽기로 모은 계산 입력. 워커가 트랜잭션 밖으로 들고 나간다. */
    public record CurveInputs(List<AccountBalances.AccountBalance> balances,
                              List<PlannedItem> ledger,
                              List<ForecastBasis.PlannedSnapshot> snapshots,
                              ForecastBasis.Summary summary) {

        /** 알 수 없는 잔액은 빼고 더한다. 0 으로 치면 없는 공백이 생긴다. */
        public BigDecimal openingBalance() {
            BigDecimal total = BigDecimal.ZERO;
            for (AccountBalances.AccountBalance balance : balances) {
                if (balance.isKnown()) {
                    total = total.add(balance.balance());
                }
            }
            return total;
        }

        public boolean allBalancesKnown() {
            return balances.stream().allMatch(AccountBalances.AccountBalance::isKnown);
        }

        public boolean hasConnectedAccount() {
            return !balances.isEmpty();
        }
    }
}
