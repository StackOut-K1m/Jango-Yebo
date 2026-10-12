package com.jangoyebo.forecast.scenario;

import com.jangoyebo.common.domain.FlowType;
import com.jangoyebo.core.Loans.RepaymentCycle;
import com.jangoyebo.forecast.engine.BalanceCurveCalculator.PlannedItem;
import com.jangoyebo.forecast.engine.LoanRepaymentSchedule;
import com.jangoyebo.forecast.engine.LoanRepaymentSchedule.Installment;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 원장 스냅샷에 What-if 가정을 건다 (예측엔진 설계노트 <b>Layer 3</b>).
 *
 * <p><b>순수 함수다.</b> DB·시계·HTTP 를 안 건드린다. 하는 일은 예정 거래 목록을 결정론적으로
 * 변형하는 것뿐이고, 그 결과를 {@code BalanceCurveCalculator.compute} 에 다시 넣으면 곡선이
 * 나온다 — <b>새 계산 규칙이 없다.</b> 설계노트가 "원장 스냅샷에 결정론적 변환 → Layer 1
 * 재실행 (모델 재추론 없음)"이라고 적은 자리가 여기다.
 *
 * <p><b>잔여 흐름 축(Layer 2-B)은 건드리지 않는다.</b> 잔여는 「원장이 설명하지 못한 나머지」로
 * 정의되고 입금·출금 두 숫자뿐이라 분류가 없다. 거기에 매출 배율을 곱하면 대출 입금도 함께
 * 늘어난다 — 재료비 What-if 를 범위에서 뺀 이유와 같은 문제다(SRS v1.11).
 *
 * <p><b>변환한 원장을 {@code CashGapDetector.causes} 에 넘기지 않는다.</b> 가상 항목은
 * {@code plannedTransactionId} 가 {@code null} 인데 그쪽 정렬이 그 값을 그대로 비교한다.
 * 시나리오 응답에는 원인 목록이 없으므로 부를 일도 없다.
 */
public final class ScenarioLedgerTransformer {

    /** 금액 자리수. 저장 원장({@code NUMERIC(19,4)})과 같게 두어 곱셈 오차가 쌓이지 않게 한다. */
    private static final int AMOUNT_SCALE = 4;

    /** 가상 항목의 신뢰도. 사용자가 「있다고 치자」고 못박은 값이라 흔들 근거가 없다. */
    private static final BigDecimal CERTAIN = BigDecimal.ONE;

    private static final BigDecimal HUNDRED = new BigDecimal("100");

    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("yyyy-MM-dd");

    private ScenarioLedgerTransformer() {
    }

    /**
     * 변환 대상 한 건. 계산기가 쓰는 {@link PlannedItem} 에 <b>대상을 고르는 데 필요한
     * 메타</b>를 붙였다.
     *
     * <p>{@code categoryId} 는 {@code SALES_CHANGE} 가, {@code recurringPatternId} 는
     * {@code SETTLEMENT_DELAY}·{@code DEPOSIT_DECREASE} 가 쓴다. 계산기 입력에 이 둘을 넣지
     * 않은 것은 곡선 계산에 필요 없기 때문이고, 여기서만 필요하므로 여기서만 들고 다닌다.
     */
    public record LedgerItem(PlannedItem item, Long categoryId, Long recurringPatternId) {

        FlowType flowType() {
            return item.flowType();
        }
    }

    /**
     * 상품 하나의 구성비와 그 근거 기간.
     *
     * <p>기간을 함께 들고 다니는 이유는 명세가 <b>경고 문장에 기간을 밝히라</b>고 정했기
     * 때문이다(11.1) — 계절성 있는 메뉴는 기간에 따라 구성비가 달라진다.
     */
    public record ProductShare(String productKey, String productName, BigDecimal share,
                               LocalDate periodStart, LocalDate periodEnd) {
    }

    /**
     * @param salesCategoryIds 매출성 입금 카테고리 id — 카드 정산·플랫폼 정산·현금 매출·계좌이체 매출
     * @param productShares    정규화 키 → 구성비. {@code PRODUCT_SALES_CHANGE} 가 없으면 비어 있다
     */
    public record Context(LocalDate baseDate, int horizonDays,
                          Set<Long> salesCategoryIds, Map<String, ProductShare> productShares) {

        /** 곡선의 마지막 날. 곡선은 기준일 <b>다음 날</b>부터 {@code horizonDays} 개다. */
        LocalDate windowEnd() {
            return baseDate.plusDays(horizonDays);
        }
    }

    /**
     * @param planned  변형된 원장. 그대로 {@code CurveInput.planned} 에 넣는다
     * @param warnings 반영하지 못한 것과 그 이유. <b>비어 있는 것이 정상이 아니다</b> —
     *                 대상이 0건인 가정은 여기서만 드러난다
     */
    public record Result(List<PlannedItem> planned, List<String> warnings) {
    }

    public static Result transform(List<LedgerItem> ledger, List<ScenarioParameter> parameters,
                                   Context context) {
        int size = ledger.size();
        BigDecimal[] multiplier = new BigDecimal[size];
        long[] delayDays = new long[size];
        LocalDate[] overrideDate = new LocalDate[size];
        for (int i = 0; i < size; i++) {
            multiplier[i] = BigDecimal.ONE;
        }

        Set<String> warnings = new LinkedHashSet<>();
        List<PlannedItem> extra = new ArrayList<>();
        List<ScenarioParameter.ProductSalesChange> productChanges = new ArrayList<>();

        for (ScenarioParameter parameter : parameters) {
            switch (parameter) {
                case ScenarioParameter.SettlementDelay p -> {
                    int matched = 0;
                    for (int i = 0; i < size; i++) {
                        if (patternTarget(ledger.get(i), p.patternId())) {
                            // 여러 지연이 같은 항목에 걸리면 더한다. 「2일 늦고 또 3일 늦는다」는
                            // 5일이다 — 큰 쪽만 남기면 뒤에 쌓은 가정이 조용히 사라진다.
                            delayDays[i] += p.delayDays();
                            matched++;
                        }
                    }
                    if (matched == 0) {
                        warnings.add("지연할 예정 입금이 예측 기간에 없어 정산 지연을 반영하지 않았습니다.");
                    }
                }
                case ScenarioParameter.DepositDecrease p -> {
                    int matched = 0;
                    for (int i = 0; i < size; i++) {
                        if (patternTarget(ledger.get(i), p.patternId())) {
                            multiplier[i] = multiplier[i].multiply(BigDecimal.ONE.subtract(p.rate()));
                            matched++;
                        }
                    }
                    if (matched == 0) {
                        warnings.add("줄일 예정 입금이 예측 기간에 없어 입금 감소를 반영하지 않았습니다.");
                    }
                }
                case ScenarioParameter.SalesChange p -> {
                    int matched = applyToSales(ledger, multiplier, context,
                            BigDecimal.ONE.add(p.rate()));
                    if (matched == 0) {
                        warnings.add(noSalesTargetWarning());
                    }
                }
                case ScenarioParameter.ProductSalesChange p -> productChanges.add(p);

                case ScenarioParameter.ExpenseDateShift p -> {
                    int found = indexOfPlanned(ledger, p.plannedTransactionId());
                    if (found < 0) {
                        // 기준 실행 이후에 등록됐거나 창 밖이라 스냅샷에 없다. 오류가 아니다 —
                        // 다음 실행이 돌면 잡힌다.
                        warnings.add("예측에 포함되지 않은 예정 거래(#%d)라 지출일 변경을 반영하지 않았습니다."
                                .formatted(p.plannedTransactionId()));
                    } else {
                        overrideDate[found] = p.newDate();
                    }
                }
                case ScenarioParameter.ExtraFlow p -> {
                    extra.add(new PlannedItem(null, p.date(), p.flowType(),
                            p.amount(), p.amount(), p.amount(), CERTAIN));
                    if (p.date().isAfter(context.windowEnd())) {
                        warnings.add("%s 은 예측 기간 밖이라 %s 이 곡선에 나타나지 않습니다."
                                .formatted(p.date().format(DATE), p.title()));
                    }
                }
                case ScenarioParameter.LoanExecution p ->
                        extra.addAll(loanItems(p, context, warnings));
            }
        }

        if (!productChanges.isEmpty()) {
            BigDecimal total = productMultiplier(productChanges, context, warnings);
            int matched = applyToSales(ledger, multiplier, context, total);
            if (matched == 0) {
                warnings.add(noSalesTargetWarning());
            }
        }

        List<PlannedItem> planned = new ArrayList<>(size + extra.size());
        int pushedOut = 0;
        for (int i = 0; i < size; i++) {
            PlannedItem source = ledger.get(i).item();
            LocalDate date = overrideDate[i] != null
                    ? overrideDate[i]
                    : source.date().plusDays(delayDays[i]);
            if (date.isAfter(context.windowEnd())) {
                // 창 밖으로 밀린 돈은 이번 기간에 들어오지 않는다 — 빼는 것이 곧 그 뜻이다.
                if (delayDays[i] > 0) {
                    pushedOut++;
                }
                continue;
            }
            planned.add(scale(source, date, multiplier[i]));
        }
        if (pushedOut > 0) {
            warnings.add("정산 지연으로 입금 %d건이 예측 기간 밖으로 밀렸습니다.".formatted(pushedOut));
        }
        planned.addAll(extra);

        return new Result(List.copyOf(planned), List.copyOf(warnings));
    }

    // --- 대상 고르기 -------------------------------------------------------------

    /**
     * {@code SETTLEMENT_DELAY}·{@code DEPOSIT_DECREASE} 의 대상.
     *
     * <p>{@code patternId} 를 생략하면 <b>패턴이 만든 예정 입금 전체</b>다(11.1 "입금 패턴
     * 전체"). 사용자가 직접 등록한 입금과 대출 실행금은 대상이 아니다 — 「정산이 늦어진다」는
     * 거래처가 늦게 주는 상황이고, 사장님이 넣기로 한 돈이나 은행이 내주는 대출금은 그
     * 사정과 무관하다.
     */
    private static boolean patternTarget(LedgerItem item, Long patternId) {
        if (item.flowType() != FlowType.DEPOSIT || item.recurringPatternId() == null) {
            return false;
        }
        return patternId == null || patternId.equals(item.recurringPatternId());
    }

    /**
     * 매출성 입금에 배율을 건다.
     *
     * <p><b>대상은 카테고리로 고른다 — 출처를 보지 않는다.</b> 11.1 의 표가 카드 정산·플랫폼
     * 정산·현금 매출·계좌이체 매출 넷을 못박았고, 그 넷을 가려내는 값은 카테고리다. 패턴이
     * 만든 것만으로 좁히면 사장님이 직접 등록한 현금 매출이 빠지는데 그것도 매출이다.
     * 반대로 대출 실행금·대표자 자금 투입은 카테고리가 달라 저절로 빠진다.
     *
     * <p>⚠ <b>분류가 안 된 입금은 건드리지 않는다.</b> 패턴의 카테고리는 탐지할 때 거래에서
     * 복사되므로, 분류가 나중에 돌았다면 이미 만들어진 패턴은 비어 있다. 그때 대상이 0건이
     * 되는데 그것은 구현이 틀린 것이 아니라 <b>재탐지가 필요한 상태</b>이고, 호출자가 경고로
     * 그 사실을 화면에 드러낸다.
     *
     * @return 배율이 걸린 항목 수
     */
    private static int applyToSales(List<LedgerItem> ledger, BigDecimal[] multiplier,
                                    Context context, BigDecimal factor) {
        int matched = 0;
        for (int i = 0; i < ledger.size(); i++) {
            LedgerItem item = ledger.get(i);
            if (item.flowType() == FlowType.DEPOSIT && item.categoryId() != null
                    && context.salesCategoryIds().contains(item.categoryId())) {
                multiplier[i] = multiplier[i].multiply(factor);
                matched++;
            }
        }
        return matched;
    }

    private static int indexOfPlanned(List<LedgerItem> ledger, Long plannedTransactionId) {
        for (int i = 0; i < ledger.size(); i++) {
            if (plannedTransactionId.equals(ledger.get(i).item().plannedTransactionId())) {
                return i;
            }
        }
        return -1;
    }

    private static String noSalesTargetWarning() {
        return "매출로 분류된 예정 입금이 없어 매출 변화를 반영하지 않았습니다. "
                + "거래 분류를 실행한 뒤 반복 패턴을 다시 탐지하면 반영됩니다.";
    }

    // --- 상품 매출 배율 ----------------------------------------------------------

    /**
     * 상품별 가정을 <b>총매출 배율 하나</b>로 접는다 (11.1 ①~③).
     *
     * <pre>
     *   상품 배율   m = (1 + quantityRate)(1 + priceRate)
     *   총매출 배율 M = 1 + Σ share × (m − 1)
     * </pre>
     *
     * <p>구성비를 쓰기 때문에 <b>분모가 무엇이든 결과가 보존된다</b> — POS 판매 총액과 계좌
     * 입금액의 기준이 달라도(수수료 차감·정산 지연·현금 미입금) 변화율만 쓰므로 환산이 필요
     * 없다. 이것이 상품 판매 데이터를 예측 원장에 넣지 않고도 시뮬레이션이 성립하는 이유다.
     */
    private static BigDecimal productMultiplier(List<ScenarioParameter.ProductSalesChange> changes,
                                                Context context, Set<String> warnings) {
        BigDecimal total = BigDecimal.ONE;
        for (ScenarioParameter.ProductSalesChange change : changes) {
            ProductShare share = context.productShares().get(change.productKey());
            if (share == null || share.share() == null) {
                // 호출자가 미리 막지만, 막지 못한 경우에 조용히 1 을 곱하지는 않는다.
                warnings.add("%s 의 구성비를 구할 수 없어 반영하지 않았습니다.".formatted(change.productName()));
                continue;
            }
            total = total.add(share.share().multiply(change.productMultiplier().subtract(BigDecimal.ONE)));
            warnings.add("%s 구성비 %s%% (%s~%s 기준)을 적용했습니다.".formatted(
                    change.productName(),
                    share.share().multiply(HUNDRED).stripTrailingZeros().toPlainString(),
                    share.periodStart().format(DATE), share.periodEnd().format(DATE)));
        }
        return total;
    }

    // --- 대출 실행 ---------------------------------------------------------------

    /**
     * 가상의 대출을 항목으로 만든다 — 실행일에 원금이 들어오고 그 뒤 회차가 빠진다.
     *
     * <p>실행일 당일에는 상환이 없다. 금융망도 「개설일 다음날부터」이고
     * {@code LoanRepaymentSchedule} 전체가 그 규칙 위에 있다.
     */
    private static List<PlannedItem> loanItems(ScenarioParameter.LoanExecution loan,
                                               Context context, Set<String> warnings) {
        if (loan.disbursementDate().isAfter(context.windowEnd())) {
            warnings.add("대출 실행일이 예측 기간 밖이라 반영하지 않았습니다.");
            return List.of();
        }

        // 엔진은 퍼센트를 받고 API 는 비율로 받는다(공통규약 2.2). 경계에서 한 번만 바꾼다.
        BigDecimal ratePercent = loan.interestRate().multiply(HUNDRED);
        List<Installment> schedule = switch (loan.repaymentCycle()) {
            case DAILY -> LoanRepaymentSchedule.daily(
                    loan.disbursementDate(), loan.amount(), ratePercent, loan.loanPeriodDays());
            case MONTHLY -> LoanRepaymentSchedule.monthly(
                    loan.disbursementDate(), loan.amount(), ratePercent, loan.loanPeriodDays());
            case INTEREST_MONTHLY -> LoanRepaymentSchedule.interestOnlyMonthly(
                    loan.disbursementDate(), loan.amount(), ratePercent, loan.loanPeriodDays());
        };

        List<PlannedItem> items = new ArrayList<>();
        items.add(new PlannedItem(null, loan.disbursementDate(), FlowType.DEPOSIT,
                loan.amount(), loan.amount(), loan.amount(), CERTAIN));
        for (Installment installment : LoanRepaymentSchedule.within(
                schedule, context.baseDate(), context.windowEnd())) {
            items.add(new PlannedItem(null, installment.dueDate(), FlowType.WITHDRAWAL,
                    installment.amount(), installment.amount(), installment.amount(), CERTAIN));
        }

        LocalDate maturity = loan.disbursementDate().plusDays(loan.loanPeriodDays());
        if (loan.repaymentCycle() == RepaymentCycle.INTEREST_MONTHLY
                && maturity.isAfter(context.windowEnd())) {
            // 이자만 빠지는 구간만 보이면 대출이 공짜처럼 보인다. 원금이 언제 나가는지 말한다.
            warnings.add("만기(%s)가 예측 기간 밖이라 원금 상환은 곡선에 없습니다. 이자만 반영했습니다."
                    .formatted(maturity.format(DATE)));
        }
        return items;
    }

    // --- 항목 재생성 -------------------------------------------------------------

    /**
     * 날짜와 배율을 반영한 새 항목. {@link PlannedItem} 의 컴팩트 생성자가 {@code min ≤ max}
     * 를 다시 맞춰 주므로 배율이 0 이어도 곡선 불변식이 깨지지 않는다.
     */
    private static PlannedItem scale(PlannedItem source, LocalDate date, BigDecimal multiplier) {
        if (multiplier.compareTo(BigDecimal.ONE) == 0) {
            return date.equals(source.date()) ? source : new PlannedItem(
                    source.plannedTransactionId(), date, source.flowType(),
                    source.min(), source.expected(), source.max(), source.itemConfidence());
        }
        return new PlannedItem(source.plannedTransactionId(), date, source.flowType(),
                amount(source.min(), multiplier),
                amount(source.expected(), multiplier),
                amount(source.max(), multiplier),
                source.itemConfidence());
    }

    private static BigDecimal amount(BigDecimal value, BigDecimal multiplier) {
        return value.multiply(multiplier).setScale(AMOUNT_SCALE, RoundingMode.HALF_UP);
    }
}
