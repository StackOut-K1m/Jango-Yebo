package com.jangoyebo.forecast.scenario;

import com.jangoyebo.common.domain.FlowType;
import com.jangoyebo.common.error.BusinessException;
import com.jangoyebo.common.error.ErrorCode;
import com.jangoyebo.common.tenancy.BusinessGuard;
import com.jangoyebo.core.CategoryCatalog;
import com.jangoyebo.core.ProductSalesShares;
import com.jangoyebo.core.ProductSalesShares.ShareSnapshot;
import com.jangoyebo.core.SafetyLines;
import com.jangoyebo.forecast.engine.BalanceCurveCalculator;
import com.jangoyebo.forecast.engine.BalanceCurveCalculator.Curve;
import com.jangoyebo.forecast.engine.BalanceCurveCalculator.DailyPoint;
import com.jangoyebo.forecast.engine.BalanceCurveCalculator.PlannedItem;
import com.jangoyebo.forecast.pattern.PatternStatus;
import com.jangoyebo.forecast.pattern.RecurringPattern;
import com.jangoyebo.forecast.pattern.RecurringPatternRepository;
import com.jangoyebo.forecast.planned.PlannedStatus;
import com.jangoyebo.forecast.planned.PlannedTransaction;
import com.jangoyebo.forecast.planned.PlannedTransactionRepository;
import com.jangoyebo.forecast.run.ForecastBasis;
import com.jangoyebo.forecast.run.ForecastWindow;
import com.jangoyebo.forecast.run.ForecastWindowReader;
import com.jangoyebo.forecast.scenario.ScenarioDtos.CreateRequest;
import com.jangoyebo.forecast.scenario.ScenarioDtos.CurvePoint;
import com.jangoyebo.forecast.scenario.ScenarioDtos.ParameterDto;
import com.jangoyebo.forecast.scenario.ScenarioDtos.SavedScenarioItem;
import com.jangoyebo.forecast.scenario.ScenarioDtos.ScenarioListResponse;
import com.jangoyebo.forecast.scenario.ScenarioDtos.ScenarioResultResponse;
import com.jangoyebo.forecast.scenario.ScenarioLedgerTransformer.Context;
import com.jangoyebo.forecast.scenario.ScenarioLedgerTransformer.LedgerItem;
import com.jangoyebo.forecast.scenario.ScenarioLedgerTransformer.ProductShare;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * What-if 시나리오 (API 11.1·11.2, P0-09·P0-13).
 *
 * <p><b>예측을 새로 돌리지 않는다.</b> 최신 완료 실행의 스냅샷을 그대로 가져와 예정 거래만
 * 변형하고 같은 계산기를 다시 부른다. 그래서 {@code originalCurve} 는 대시보드(10.1)가
 * 보여 주는 곡선과 같은 값이고, 「원본 대비 얼마나 나빠지나」라는 비교가 성립한다.
 *
 * <p><b>결과를 저장하지 않는다.</b> 파라미터만 남기고 열 때마다 다시 계산한다 — 어제 만든
 * 시나리오를 오늘 열면 어제 예측과 비교하게 되기 때문이다(기술스택 §4.6).
 *
 * <h2>생성과 재계산은 틀린 것을 다르게 다룬다</h2>
 *
 * <p>생성({@code POST})에서 없는 패턴 id 는 <b>사용자가 방금 잘못 보낸 것</b>이라 {@code 400}
 * 이다. 재계산({@code GET})에서 같은 일이 생기면 그 사이에 세상이 바뀐 것이고, 저장해 둔
 * 시나리오를 열었을 뿐인 사용자에게 오류를 주는 것은 맞지 않다 — 반영하지 못한 것만
 * {@code warnings} 로 말하고 나머지로 곡선을 낸다.
 */
@Service
public class ScenarioService {

    /** 매출성 입금 카테고리 (11.1 — {@code SALES_CHANGE} 대상표). 비매출 입금은 여기 없다. */
    private static final List<String> SALES_CATEGORY_CODES =
            List.of("CARD_SETTLEMENT", "PLATFORM_SETTLEMENT", "CASH_SALES", "TRANSFER_SALES");

    /** 곡선에 올릴 수 있는 예정 거래 상태. 매칭·취소된 것은 실행에도 안 들어간다. */
    private static final Set<PlannedStatus> SHIFTABLE =
            Set.of(PlannedStatus.EXPECTED, PlannedStatus.CONFIRMED);

    private final ScenarioRepository scenarios;
    private final ScenarioAdjustmentRepository adjustments;
    private final ForecastWindowReader windows;
    private final PlannedTransactionRepository planned;
    private final RecurringPatternRepository patterns;
    private final CategoryCatalog categories;
    private final ProductSalesShares productShares;
    private final SafetyLines safetyLines;
    private final BusinessGuard guard;

    public ScenarioService(ScenarioRepository scenarios,
                           ScenarioAdjustmentRepository adjustments,
                           ForecastWindowReader windows,
                           PlannedTransactionRepository planned,
                           RecurringPatternRepository patterns,
                           CategoryCatalog categories,
                           ProductSalesShares productShares,
                           SafetyLines safetyLines,
                           BusinessGuard guard) {
        this.scenarios = scenarios;
        this.adjustments = adjustments;
        this.windows = windows;
        this.planned = planned;
        this.patterns = patterns;
        this.categories = categories;
        this.productShares = productShares;
        this.safetyLines = safetyLines;
        this.guard = guard;
    }

    // --- 11.1 생성·계산 -----------------------------------------------------------

    @Transactional
    public ScenarioResultResponse create(Long businessId, CreateRequest request) {
        // 순서가 중요하다 — 본문이 틀렸을 때 예측 재계산이 예약되면 안 된다.
        ForecastWindowReader.requireAllowedDays(request.days());
        List<ScenarioParameter> parameters =
                ScenarioParameterParser.parse(request.parameters(), productShares::keyOf);

        ForecastWindow window = windows.latest(businessId, request.days());
        Resolved resolved = resolve(businessId, window, parameters, true);

        Scenario saved = null;
        if (Boolean.TRUE.equals(request.save())) {
            saved = scenarios.save(Scenario.of(businessId, guard.currentUserId(),
                    request.name(), request.days()));
            List<ScenarioAdjustment> rows = new ArrayList<>(parameters.size());
            for (ScenarioParameter parameter : parameters) {
                rows.add(ScenarioAdjustmentMapper.toEntity(saved.getId(), parameter));
            }
            adjustments.saveAll(rows);
        }

        List<ParameterDto> echo = parameters.stream().map(ScenarioAdjustmentMapper::toDto).toList();
        return compute(businessId, window, resolved, echo,
                saved == null ? null : saved.getId(),
                saved == null ? request.name() : saved.getName());
    }

    // --- 11.2 저장·조회·삭제 -------------------------------------------------------

    @Transactional(readOnly = true)
    public ScenarioListResponse list(Long businessId) {
        List<Scenario> rows = scenarios.findByBusinessIdOrderByCreatedAtDesc(businessId);
        if (rows.isEmpty()) {
            return new ScenarioListResponse(List.of());
        }
        // 시나리오마다 따로 읽으면 N+1 이다. 한 번에 가져와 나눈다.
        Map<Long, List<ParameterDto>> byScenario = new HashMap<>();
        for (ScenarioAdjustment row : adjustments.findByScenarioIdInOrderByIdAsc(
                rows.stream().map(Scenario::getId).toList())) {
            byScenario.computeIfAbsent(row.getScenarioId(), id -> new ArrayList<>())
                    .add(ScenarioAdjustmentMapper.toDto(row));
        }

        List<SavedScenarioItem> items = new ArrayList<>(rows.size());
        for (Scenario scenario : rows) {
            items.add(new SavedScenarioItem(scenario.getId(), scenario.getName(),
                    scenario.getCreatedAt(),
                    byScenario.getOrDefault(scenario.getId(), List.of())));
        }
        return new ScenarioListResponse(items);
    }

    /**
     * 저장한 시나리오를 <b>지금 데이터로 다시 계산한다</b> (11.2).
     *
     * <p>저장 당시의 스냅샷이 아니다. 응답의 {@code baseTime} 이 어느 실행 위에서 계산했는지
     * 말하고, 며칠 뒤 다시 열면 그 값이 바뀐다.
     */
    @Transactional(readOnly = true)
    public ScenarioResultResponse recompute(Long scenarioId) {
        Scenario scenario = find(scenarioId);
        List<ScenarioAdjustment> rows = adjustments.findByScenarioIdOrderByIdAsc(scenarioId);

        List<ScenarioParameter> parameters = new ArrayList<>(rows.size());
        List<String> lost = new ArrayList<>();
        for (ScenarioAdjustment row : rows) {
            ScenarioAdjustmentMapper.Restored restored = ScenarioAdjustmentMapper.restore(row);
            if (restored.parameter() == null) {
                lost.add(restored.warning());
            } else {
                parameters.add(restored.parameter());
            }
        }

        ForecastWindow window = windows.latest(scenario.getBusinessId(), scenario.getDays());
        Resolved resolved = resolve(scenario.getBusinessId(), window, parameters, false);
        resolved.warnings().addAll(0, lost);

        // 반영하지 못한 조정도 목록에는 남긴다 — 저장한 조건이 사라지면 사용자는 결과가
        // 왜 달라졌는지 알 수 없다. 빠졌다는 사실은 warnings 가 말한다.
        List<ParameterDto> echo = rows.stream().map(ScenarioAdjustmentMapper::toDto).toList();
        return compute(scenario.getBusinessId(), window, resolved, echo,
                scenario.getId(), scenario.getName());
    }

    @Transactional
    public SavedScenarioItem rename(Long scenarioId, String name) {
        Scenario scenario = find(scenarioId);
        scenario.rename(name);
        return new SavedScenarioItem(scenario.getId(), scenario.getName(), scenario.getCreatedAt(),
                adjustments.findByScenarioIdOrderByIdAsc(scenarioId).stream()
                        .map(ScenarioAdjustmentMapper::toDto).toList());
    }

    @Transactional
    public void delete(Long scenarioId) {
        // 조정 행은 fk_sa_scenario 의 ON DELETE CASCADE 가 가져간다.
        scenarios.delete(find(scenarioId));
    }

    private Scenario find(Long scenarioId) {
        Scenario scenario = scenarios.findById(scenarioId)
                .orElseThrow(() -> new BusinessException(ErrorCode.SCENARIO_NOT_FOUND));
        // 직접 경로다 — @CurrentBusiness 를 거치지 않으므로 명시 검증이 필요하다.
        guard.assertOwned(scenario);
        return scenario;
    }

    // --- 계산 ---------------------------------------------------------------------

    private ScenarioResultResponse compute(Long businessId, ForecastWindow window, Resolved resolved,
                                           List<ParameterDto> echo, Long scenarioId, String name) {
        List<LedgerItem> ledger = enrich(window);
        ScenarioLedgerTransformer.Result transformed = ScenarioLedgerTransformer.transform(
                ledger, resolved.parameters(), resolved.context());

        List<PlannedItem> original = ledger.stream().map(LedgerItem::item).toList();
        // 원본도 같은 함수로 다시 낸다. 저장된 행을 직접 읽으면 파라미터가 아무것도 바꾸지
        // 않은 시나리오에서도 두 곡선이 1원 갈릴 수 있다.
        Curve originalCurve = BalanceCurveCalculator.compute(window.toCurveInput(original));
        Curve modifiedCurve = BalanceCurveCalculator.compute(window.toCurveInput(transformed.planned()));

        BigDecimal safetyLine = safetyLines.currentAmount(businessId).orElse(null);
        Set<String> warnings = new LinkedHashSet<>(window.warnings());
        warnings.addAll(resolved.warnings());
        warnings.addAll(transformed.warnings());
        if (safetyLine == null) {
            warnings.add("안전선이 설정되지 않아 안전선 하회 일수는 계산하지 않았습니다.");
        }

        return new ScenarioResultResponse(
                scenarioId,
                name,
                window.baseTime(),
                window.rows().size(),
                echo,
                new ScenarioDtos.Comparison(
                        ScenarioComparison.summarize(originalCurve, safetyLine),
                        ScenarioComparison.summarize(modifiedCurve, safetyLine)),
                points(originalCurve),
                points(modifiedCurve),
                window.confidence(),
                List.copyOf(warnings));
    }

    private static List<CurvePoint> points(Curve curve) {
        List<CurvePoint> points = new ArrayList<>(curve.points().size());
        for (DailyPoint point : curve.points()) {
            points.add(new CurvePoint(point.date(), ScenarioComparison.won(point.balanceExpected())));
        }
        return points;
    }

    /**
     * 스냅샷 원장에 <b>대상을 고르는 데 필요한 값</b>을 붙인다.
     *
     * <p>{@code categoryId} 는 실행 스냅샷에 있지만 {@code recurringPatternId} 는 없다. 예정
     * 거래를 한 번에 읽어 채우되 <b>실행 이후 지워진 행은 그냥 빠진다</b> — 그 항목은 패턴
     * 대상에서 제외될 뿐이고, 곡선 자체는 스냅샷 그대로라 흔들리지 않는다.
     */
    private List<LedgerItem> enrich(ForecastWindow window) {
        ForecastBasis basis = window.run().getBasis();
        List<PlannedItem> items = window.ledgerSnapshot();
        List<ForecastBasis.PlannedSnapshot> snapshots =
                basis.items() == null ? List.of() : basis.items();

        List<Long> ids = items.stream()
                .map(PlannedItem::plannedTransactionId).filter(java.util.Objects::nonNull).toList();
        Map<Long, Long> patternIdByPlanned = new HashMap<>();
        for (PlannedTransaction row : planned.findAllById(ids)) {
            if (row.getRecurringPatternId() != null) {
                patternIdByPlanned.put(row.getId(), row.getRecurringPatternId());
            }
        }

        List<LedgerItem> ledger = new ArrayList<>(items.size());
        for (int i = 0; i < items.size(); i++) {
            PlannedItem item = items.get(i);
            // basis.ledger() 가 basis.items() 를 순서대로 옮긴 것이라 색인이 맞는다.
            Long categoryId = i < snapshots.size() ? snapshots.get(i).categoryId() : null;
            ledger.add(new LedgerItem(item, categoryId,
                    patternIdByPlanned.get(item.plannedTransactionId())));
        }
        return ledger;
    }

    // --- 참조·날짜 검증 -------------------------------------------------------------

    /** @param warnings 호출자가 더 담을 수 있도록 가변 목록이다 */
    private record Resolved(List<ScenarioParameter> parameters, Context context, List<String> warnings) {
    }

    /**
     * 파라미터가 <b>이 사업장의 실제 대상</b>을 가리키는지 본다.
     *
     * <p>⚠ <b>남의 것과 없는 것을 같은 문구로 답한다.</b> 「그 패턴은 남의 것입니다」는 그
     * 패턴이 존재한다는 사실을 흘린다. 계좌번호 검증({@code ACCOUNT_NO_INVALID})이 같은
     * 이유로 뭉뚱그려져 있다.
     *
     * @param strict {@code true} 면 던지고({@code POST}), {@code false} 면 빼고 경고한다({@code GET})
     */
    private Resolved resolve(Long businessId, ForecastWindow window,
                             List<ScenarioParameter> parameters, boolean strict) {
        LocalDate baseDate = window.baseDate();
        List<ScenarioParameter> usable = new ArrayList<>(parameters.size());
        List<String> warnings = new ArrayList<>();
        Map<String, ProductShare> shares = new LinkedHashMap<>();
        ShareSnapshot snapshot = null;
        boolean snapshotLoaded = false;

        for (int i = 0; i < parameters.size(); i++) {
            ScenarioParameter parameter = parameters.get(i);
            String problem = null;

            switch (parameter) {
                case ScenarioParameter.SettlementDelay p ->
                        problem = checkPattern(businessId, p.patternId());
                case ScenarioParameter.DepositDecrease p ->
                        problem = checkPattern(businessId, p.patternId());
                case ScenarioParameter.ExtraFlow p ->
                        problem = checkNotPast(baseDate, p.date(), "날짜");
                case ScenarioParameter.ExpenseDateShift p -> {
                    problem = checkShiftTarget(businessId, p.plannedTransactionId());
                    if (problem == null) {
                        problem = checkNotPast(baseDate, p.newDate(), "새 날짜");
                    }
                }
                case ScenarioParameter.SalesChange p -> problem = null;
                case ScenarioParameter.ProductSalesChange p -> {
                    if (!snapshotLoaded) {
                        snapshot = productShares.latest(businessId).orElse(null);
                        snapshotLoaded = true;
                    }
                    if (snapshot == null) {
                        if (strict) {
                            throw new BusinessException(ErrorCode.PRODUCT_SALES_NOT_REGISTERED);
                        }
                        problem = "상품별 매출 구성비가 등록되어 있지 않습니다.";
                    } else {
                        Optional<ProductSalesShares.ProductShare> found = snapshot.find(p.productKey());
                        if (found.isEmpty() || found.get().share() == null) {
                            problem = "등록된 상품이 아닙니다: " + p.productName();
                        } else {
                            shares.put(p.productKey(), new ProductShare(
                                    p.productKey(), p.productName(), found.get().share(),
                                    snapshot.periodStart(), snapshot.periodEnd()));
                        }
                    }
                }
                case ScenarioParameter.LoanExecution p ->
                        problem = checkNotPast(baseDate, p.disbursementDate(), "실행일");
            }

            if (problem == null) {
                usable.add(parameter);
            } else if (strict) {
                throw ScenarioParameterParser.invalid(i, parameter.type().name(),
                        fieldOf(parameter), problem);
            } else {
                warnings.add("%s: %s 반영하지 않았습니다.".formatted(parameter.type(), problem));
            }
        }

        return new Resolved(usable,
                new Context(baseDate, window.rows().size(), salesCategoryIds(), shares),
                warnings);
    }

    private String checkPattern(Long businessId, Long patternId) {
        if (patternId == null) {
            return null;
        }
        RecurringPattern pattern = patterns.findById(patternId).orElse(null);
        if (pattern == null || !businessId.equals(pattern.getBusinessId())
                || pattern.getFlowType() != FlowType.DEPOSIT
                || pattern.getStatus() == PatternStatus.DISMISSED) {
            return "이 사업장의 입금 패턴이 아닙니다.";
        }
        return null;
    }

    private String checkShiftTarget(Long businessId, Long plannedTransactionId) {
        PlannedTransaction target = planned.findById(plannedTransactionId).orElse(null);
        if (target == null || !businessId.equals(target.getBusinessId())) {
            return "이 사업장의 예정 거래가 아닙니다.";
        }
        if (target.getFlowType() != FlowType.WITHDRAWAL) {
            return "지출 예정 거래만 날짜를 옮길 수 있습니다.";
        }
        if (!SHIFTABLE.contains(target.getStatus())) {
            // 이미 실제 거래와 맞춰졌거나 취소된 것은 곡선에 없다 — 옮길 대상이 아니다.
            return "이미 처리된 예정 거래라 옮길 수 없습니다.";
        }
        return null;
    }

    /** 기준일 이전은 곡선이 닿지 않는 구간이다 — 넣어도 나타나지 않는다(9.4 와 같은 규칙). */
    private static String checkNotPast(LocalDate baseDate, LocalDate date, String label) {
        return date.isBefore(baseDate) ? "%s가 예측 기준일(%s) 이전입니다.".formatted(label, baseDate) : null;
    }

    private static String fieldOf(ScenarioParameter parameter) {
        return switch (parameter) {
            case ScenarioParameter.SettlementDelay p -> "patternId";
            case ScenarioParameter.DepositDecrease p -> "patternId";
            case ScenarioParameter.ExtraFlow p -> "date";
            case ScenarioParameter.ExpenseDateShift p -> "plannedTransactionId";
            case ScenarioParameter.SalesChange p -> "rate";
            case ScenarioParameter.ProductSalesChange p -> "productKey";
            case ScenarioParameter.LoanExecution p -> "disbursementDate";
        };
    }

    private Set<Long> salesCategoryIds() {
        Set<Long> ids = new LinkedHashSet<>();
        for (String code : SALES_CATEGORY_CODES) {
            categories.idByCode(code).ifPresent(ids::add);
        }
        return ids;
    }
}
