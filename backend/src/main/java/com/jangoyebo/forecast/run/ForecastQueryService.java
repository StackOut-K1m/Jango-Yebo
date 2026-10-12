package com.jangoyebo.forecast.run;

import com.jangoyebo.common.domain.RiskLevel;
import com.jangoyebo.common.error.BusinessException;
import com.jangoyebo.common.error.ErrorCode;
import com.jangoyebo.core.CashGapAlerts;
import com.jangoyebo.core.SafetyLines;
import com.jangoyebo.core.SupportProgramSuggestions;
import com.jangoyebo.forecast.engine.BalanceCurveCalculator.DailyPoint;
import com.jangoyebo.forecast.engine.BalanceCurveCalculator.PlannedItem;
import com.jangoyebo.forecast.engine.CashGapDetector;
import com.jangoyebo.forecast.engine.CashGapDetector.CashGapSegment;
import com.jangoyebo.forecast.engine.RiskLevelRule;
import com.jangoyebo.forecast.run.ForecastDtos.BalanceForecastResponse;
import com.jangoyebo.forecast.run.ForecastDtos.CashGapItem;
import com.jangoyebo.forecast.run.ForecastDtos.CashGapsResponse;
import com.jangoyebo.forecast.run.ForecastDtos.CurvePoint;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 예측 조회 (API 10.1·10.2).
 *
 * <p><b>계산하지 않는다.</b> 최신 {@code COMPLETED} 실행을 읽기만 한다(공통규약 2.6) —
 * {@code ix_fr_latest} 가 그 전제로 만든 부분 인덱스다.
 *
 * <p>단 <b>파생값은 여기서 만든다</b> — 위험 등급·최저점·공백 구간·신뢰 등급. 저장하지 않는
 * 이유는 안전선에 의존해서다. 안전선을 바꾸면 재계산 없이 다음 조회에 바로 반영된다.
 */
@Service
public class ForecastQueryService {

    private final ForecastWindowReader windows;
    private final SafetyLines safetyLines;
    private final CashGapAlerts cashGapAlerts;
    private final SupportProgramSuggestions supportPrograms;

    public ForecastQueryService(ForecastWindowReader windows,
                                SafetyLines safetyLines,
                                CashGapAlerts cashGapAlerts,
                                SupportProgramSuggestions supportPrograms) {
        this.windows = windows;
        this.safetyLines = safetyLines;
        this.cashGapAlerts = cashGapAlerts;
        this.supportPrograms = supportPrograms;
    }

    @Transactional(readOnly = true)
    public BalanceForecastResponse balance(Long businessId, int days) {
        ForecastWindow window = windows.latest(businessId, days);
        BigDecimal safetyLine = safetyLines.currentAmount(businessId).orElse(null);

        List<CurvePoint> curve = new ArrayList<>(window.points().size());
        for (DailyBalanceForecast row : window.rows()) {
            curve.add(new CurvePoint(row.getForecastDate(),
                    won(row.getBalanceExpected()), won(row.getBalanceMin()), won(row.getBalanceMax()),
                    won(row.getExpectedDeposit()), won(row.getExpectedWithdrawal()),
                    RiskLevelRule.of(row.getBalanceExpected(), safetyLine)));
        }

        DailyBalanceForecast lowest = window.rows().get(0);
        for (DailyBalanceForecast row : window.rows()) {
            if (row.getBalanceExpected().compareTo(lowest.getBalanceExpected()) < 0) {
                lowest = row;
            }
        }

        ForecastBasis basis = window.run().getBasis();
        return new BalanceForecastResponse(
                window.run().getBaseAt(),
                days,
                won(window.run().openingBalanceTotal()),
                safetyLine == null ? null : won(safetyLine),
                window.run().getAppliedOptions(),
                window.run().getModelVersion(),
                curve,
                new ForecastDtos.LowestPoint(lowest.getForecastDate(), won(lowest.getBalanceExpected())),
                adjustments(basis),
                window.confidence(),
                basis(basis),
                basis.warnings() == null ? List.of() : basis.warnings());
    }

    @Transactional(readOnly = true)
    public CashGapsResponse cashGaps(Long businessId, int days) {
        ForecastWindow window = windows.latest(businessId, days);
        List<CashGapSegment> segments = CashGapDetector.detect(window.points());

        Set<String> keys = new LinkedHashSet<>();
        for (CashGapSegment segment : segments) {
            keys.add(CashGapDetector.dedupKey(segment));
        }
        Map<String, Long> ids = cashGapAlerts.idsByDedupKey(businessId, keys);

        List<CashGapItem> items = new ArrayList<>(segments.size());
        for (CashGapSegment segment : segments) {
            items.add(new CashGapItem(
                    ids.get(CashGapDetector.dedupKey(segment)),
                    segment.firstShortfallDate(),
                    won(segment.maxShortfallAmount()),
                    segment.maxShortfallDate(),
                    segment.durationDays(),
                    segment.expectedRecoveryDate(),
                    RiskLevel.GAP,
                    causes(segment, window),
                    suggestions(businessId, segment.firstShortfallDate())));
        }

        ForecastBasis basis = window.run().getBasis();
        return new CashGapsResponse(window.run().getBaseAt(), items,
                window.confidence(), new ForecastDtos.CashGapBasis(days),
                basis.warnings() == null ? List.of() : basis.warnings());
    }

    /**
     * 공백 하나에 붙일 대응 제안 (API 10.2).
     *
     * <p><b>시나리오는 늘 붙고 지원 제도는 있을 때만 붙는다.</b> 「지원 제도 0건」은
     * 사장님에게 쓸모가 없고, 공고 수집이 아직 안 붙은 환경에서는 그 줄이 매번 나간다.
     *
     * <p>문구가 「빠른」이 아니라 <b>「확인」</b>에서 멈추는 것은 도메인 규칙 1 이다.
     * 지급 시기를 적은 공고가 실측 3%(32건 중 1건)뿐이라, 대개 우리는 <b>언제 들어올지
     * 모른다.</b> 모르는 것을 근거로 「공백이 해결된다」고 말할 수 없다.
     *
     * <p>명세 예시의 {@code LOAN} 제안은 아직 넣지 않는다 — P1-13·14 쪽이다.
     *
     * <p>공백 구간마다 한 번씩 조회한다. 30일 창에서 구간은 보통 한둘이고 많아야 서넛이라
     * 그대로 뒀다 — 미리 합치면 구간별로 다른 {@code firstShortfallDate} 를 잃는다.
     */
    private List<ForecastDtos.Suggestion> suggestions(Long businessId,
                                                      LocalDate firstShortfallDate) {
        List<ForecastDtos.Suggestion> items = new ArrayList<>(2);
        items.add(new ForecastDtos.Suggestion("SCENARIO", "지출일 조정 시나리오로 영향 비교"));
        supportPrograms.forGap(businessId, firstShortfallDate).ifPresent(hint ->
                items.add(new ForecastDtos.Suggestion("SUPPORT_PROGRAM",
                        hint.anyArrivesBeforeGap()
                                ? "공백 전 집행 가능성이 있는 지원 제도 " + hint.count() + "건 확인"
                                : "신청할 수 있는 지원 제도 " + hint.count() + "건 확인")));
        return List.copyOf(items);
    }

    private static List<ForecastDtos.Cause> causes(CashGapSegment segment, ForecastWindow window) {
        // 원인은 실행 시점의 예정 거래 스냅샷에서 만든다 — 원본이 그 뒤 수정돼도 근거가 흔들리지 않는다.
        List<PlannedItem> ledger = window.ledgerSnapshot();
        List<ForecastDtos.Cause> causes = new ArrayList<>();
        for (CashGapDetector.Cause cause : CashGapDetector.causes(segment, ledger)) {
            if (cause.isDepositDrought()) {
                causes.add(new ForecastDtos.Cause(cause.date(), "해당 기간 예정 입금 없음", null));
                continue;
            }
            String name = window.nameOf(cause.plannedTransactionId());
            causes.add(new ForecastDtos.Cause(cause.date(),
                    "%s %,d원 출금".formatted(name, won(cause.amount()).longValue()),
                    cause.plannedTransactionId()));
        }
        return causes;
    }

    private static List<ForecastDtos.Adjustment> adjustments(ForecastBasis basis) {
        if (basis.adjustments() == null) {
            return List.of();
        }
        List<ForecastDtos.Adjustment> list = new ArrayList<>(basis.adjustments().size());
        for (ForecastBasis.Adjustment a : basis.adjustments()) {
            list.add(new ForecastDtos.Adjustment(a.type(), a.description(), a.affectedDates(),
                    a.modelName(), a.modelVersion()));
        }
        return list;
    }

    private static ForecastDtos.Basis basis(ForecastBasis basis) {
        ForecastBasis.Summary summary = basis.summary();
        ForecastBasis.Residual residual = basis.residual();
        return new ForecastDtos.Basis(
                summary == null ? 0 : summary.confirmedPlannedCount(),
                summary == null ? 0 : summary.expectedPlannedCount(),
                summary == null ? 0 : summary.userAddedCount(),
                summary == null ? null : summary.referencePeriod(),
                residual == null ? null : new ForecastDtos.Residual(
                        residual.applied(), residual.source(), residual.modelName(), residual.modelVersion(),
                        residual.historyDays(), residual.judgmentAlpha(),
                        range(residual.totalDeposit()), range(residual.totalWithdrawal())));
    }

    private static ForecastDtos.Range range(ForecastBasis.Range range) {
        return range == null ? null
                : new ForecastDtos.Range(won(range.min()), won(range.expected()), won(range.max()));
    }

    /** 응답 금액은 정수 원이다 (ERD 11장 — 출력 직전에 한 번만 반올림). */
    private static BigDecimal won(BigDecimal value) {
        return value == null ? null : value.setScale(0, RoundingMode.HALF_UP);
    }
}
