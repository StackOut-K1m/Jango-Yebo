package com.jangoyebo.forecast.scenario;

import com.jangoyebo.common.domain.FlowType;
import com.jangoyebo.core.Loans.RepaymentCycle;
import com.jangoyebo.forecast.engine.BalanceCurveCalculator.PlannedItem;
import com.jangoyebo.forecast.scenario.ScenarioLedgerTransformer.Context;
import com.jangoyebo.forecast.scenario.ScenarioLedgerTransformer.LedgerItem;
import com.jangoyebo.forecast.scenario.ScenarioLedgerTransformer.ProductShare;
import com.jangoyebo.forecast.scenario.ScenarioLedgerTransformer.Result;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Layer 3 — 원장 스냅샷의 결정론적 변환 (API 11.1, P0-09·P0-13).
 *
 * <p><b>Docker 가 필요 없다.</b> 변환기가 순수 함수라 DB 없이 돈다. 기준일을 인자로 받으므로
 * 날짜가 흘러도 결과가 바뀌지 않는다.
 *
 * <p>여기서 증명하려는 것은 <b>무엇이 대상이고 무엇이 아닌가</b>다. 배율과 날짜 이동 자체는
 * 단순하지만, 대상을 잘못 고르면 「구현은 맞는데 곡선이 안 움직인다」거나 그 반대가 된다.
 */
class ScenarioLedgerTransformerTests {

    private static final LocalDate BASE = LocalDate.of(2026, 8, 31);
    private static final int HORIZON = 30;

    /** 카드 정산 · 현금 매출. 실제 id 는 시드가 정하므로 여기서는 임의 값이면 된다. */
    private static final long CARD_SETTLEMENT = 10L;
    private static final long CASH_SALES = 30L;
    private static final long LOAN_DISBURSEMENT = 50L;

    @Test
    void 정산_지연은_예정일을_그만큼_뒤로_민다() {
        List<LedgerItem> ledger = List.of(patternDeposit(1L, 7L, day(3), "800000", CARD_SETTLEMENT));

        Result result = transform(ledger, new ScenarioParameter.SettlementDelay(null, 2));

        assertThat(result.planned()).singleElement()
                .extracting(PlannedItem::date).isEqualTo(day(5));
    }

    /**
     * 「입금 패턴 전체」에 사용자가 직접 넣은 입금은 들어가지 않는다.
     *
     * <p>정산 지연은 거래처가 늦게 주는 상황이다. 사장님이 넣기로 한 돈이나 대출 실행금은
     * 그 사정과 무관하므로 대상이 아니다(11.1 — 대상은 「입금 패턴」).
     */
    @Test
    void 패턴이_만들지_않은_입금은_지연_대상이_아니다() {
        List<LedgerItem> ledger = List.of(
                patternDeposit(1L, 7L, day(3), "800000", CARD_SETTLEMENT),
                userDeposit(2L, day(3), "500000", LOAN_DISBURSEMENT));

        Result result = transform(ledger, new ScenarioParameter.SettlementDelay(null, 2));

        assertThat(dateOf(result, 1L)).isEqualTo(day(5));
        assertThat(dateOf(result, 2L)).isEqualTo(day(3));
    }

    @Test
    void 패턴을_지정하면_그_패턴만_민다() {
        List<LedgerItem> ledger = List.of(
                patternDeposit(1L, 7L, day(3), "800000", CARD_SETTLEMENT),
                patternDeposit(2L, 8L, day(3), "400000", CARD_SETTLEMENT));

        Result result = transform(ledger, new ScenarioParameter.SettlementDelay(8L, 3));

        assertThat(dateOf(result, 1L)).isEqualTo(day(3));
        assertThat(dateOf(result, 2L)).isEqualTo(day(6));
    }

    /** 뒤에 쌓은 가정이 앞엣것을 지우면 안 된다 — 「2일 늦고 또 3일 늦는다」는 5일이다. */
    @Test
    void 지연을_두_번_걸면_더한다() {
        List<LedgerItem> ledger = List.of(patternDeposit(1L, 7L, day(3), "800000", CARD_SETTLEMENT));

        Result result = transform(ledger,
                new ScenarioParameter.SettlementDelay(null, 2),
                new ScenarioParameter.SettlementDelay(null, 3));

        assertThat(dateOf(result, 1L)).isEqualTo(day(8));
    }

    @Test
    void 기간_밖으로_밀린_입금은_사라지고_경고가_남는다() {
        List<LedgerItem> ledger = List.of(patternDeposit(1L, 7L, day(29), "800000", CARD_SETTLEMENT));

        Result result = transform(ledger, new ScenarioParameter.SettlementDelay(null, 10));

        // 그 돈이 이번 기간에 들어오지 않는다는 것이 곧 시뮬레이션의 결과다.
        assertThat(result.planned()).isEmpty();
        assertThat(result.warnings()).anyMatch(w -> w.contains("예측 기간 밖으로 밀렸습니다"));
    }

    @Test
    void 입금_감소는_세_금액에_모두_곱한다() {
        List<LedgerItem> ledger = List.of(new LedgerItem(
                new PlannedItem(1L, day(3), FlowType.DEPOSIT,
                        new BigDecimal("800000"), new BigDecimal("1000000"),
                        new BigDecimal("1200000"), BigDecimal.ONE),
                CARD_SETTLEMENT, 7L));

        Result result = transform(ledger,
                new ScenarioParameter.DepositDecrease(new BigDecimal("0.2000"), null));

        PlannedItem item = result.planned().get(0);
        assertThat(item.min()).isEqualByComparingTo("640000");
        assertThat(item.expected()).isEqualByComparingTo("800000");
        assertThat(item.max()).isEqualByComparingTo("960000");
    }

    /**
     * 매출 변화의 대상은 <b>카테고리</b>다 — 출처를 보지 않는다.
     *
     * <p>11.1 이 카드 정산·플랫폼 정산·현금 매출·계좌이체 매출 넷을 못박았고, 그 넷을
     * 가려내는 값은 카테고리다. 사장님이 직접 등록한 현금 매출도 매출이고, 대출 실행금은
     * 카테고리가 달라 저절로 빠진다.
     */
    @Test
    void 매출_변화는_매출_카테고리_입금만_건드린다() {
        List<LedgerItem> ledger = List.of(
                patternDeposit(1L, 7L, day(3), "1000000", CARD_SETTLEMENT),
                userDeposit(2L, day(4), "500000", CASH_SALES),
                userDeposit(3L, day(5), "3000000", LOAN_DISBURSEMENT));

        Result result = transform(ledger, new ScenarioParameter.SalesChange(new BigDecimal("-0.1000")));

        assertThat(expectedOf(result, 1L)).isEqualByComparingTo("900000");
        assertThat(expectedOf(result, 2L)).isEqualByComparingTo("450000");
        assertThat(expectedOf(result, 3L)).isEqualByComparingTo("3000000");
    }

    /**
     * 분류가 안 된 입금은 대상이 0건이 된다 — 그리고 그것을 말한다.
     *
     * <p>패턴의 카테고리는 탐지할 때 거래에서 복사되므로, 분류가 나중에 돌았다면 이미
     * 만들어진 패턴은 비어 있다. 구현이 틀린 것이 아니라 재탐지가 필요한 상태이고,
     * 경고가 없으면 사용자는 「왜 곡선이 안 움직이지」에서 멈춘다.
     */
    @Test
    void 매출로_분류된_입금이_없으면_반영하지_않고_알린다() {
        List<LedgerItem> ledger = List.of(
                new LedgerItem(new PlannedItem(1L, day(3), FlowType.DEPOSIT,
                        new BigDecimal("1000000"), new BigDecimal("1000000"),
                        new BigDecimal("1000000"), BigDecimal.ONE), null, 7L));

        Result result = transform(ledger, new ScenarioParameter.SalesChange(new BigDecimal("-0.1000")));

        assertThat(expectedOf(result, 1L)).isEqualByComparingTo("1000000");
        assertThat(result.warnings()).anyMatch(w -> w.contains("반복 패턴을 다시 탐지"));
    }

    /** 11.1 ②~③ — 구성비 24.2% 인 상품이 4.5% 오르면 총매출은 1.09% 오른다. */
    @Test
    void 상품_매출_변화는_구성비로_총매출_배율을_환산한다() {
        List<LedgerItem> ledger = List.of(patternDeposit(1L, 7L, day(3), "1000000", CARD_SETTLEMENT));
        Context context = context(Map.of("카페라떼", new ProductShare(
                "카페라떼", "카페라떼", new BigDecimal("0.242"),
                LocalDate.of(2026, 8, 1), LocalDate.of(2026, 8, 31))));

        Result result = ScenarioLedgerTransformer.transform(ledger, List.of(
                new ScenarioParameter.ProductSalesChange("카페라떼", "카페라떼",
                        new BigDecimal("-0.05"), new BigDecimal("0.10"))), context);

        // 1 + 0.242 × 0.045 = 1.010890.
        // 명세가 적은 1.0109 는 읽기 좋게 반올림한 표기다 — 배율을 그 자리에서 반올림하면
        // 금액이 10원 어긋난다. 중간 단계에서 반올림하지 않는다(ERD 11장).
        assertThat(expectedOf(result, 1L)).isEqualByComparingTo("1010890.0000");
    }

    /** 명세가 요구하는 문장이다 — 계절성 때문에 어느 기간 구성비인지 항상 밝혀야 한다(11.1). */
    @Test
    void 상품_매출_변화는_쓴_구성비와_기간을_경고로_밝힌다() {
        List<LedgerItem> ledger = List.of(patternDeposit(1L, 7L, day(3), "1000000", CARD_SETTLEMENT));
        Context context = context(Map.of("카페라떼", new ProductShare(
                "카페라떼", "카페 라떼", new BigDecimal("0.242"),
                LocalDate.of(2026, 8, 1), LocalDate.of(2026, 8, 31))));

        Result result = ScenarioLedgerTransformer.transform(ledger, List.of(
                new ScenarioParameter.ProductSalesChange("카페라떼", "카페 라떼",
                        null, new BigDecimal("0.10"))), context);

        assertThat(result.warnings()).anyMatch(w ->
                w.contains("카페 라떼") && w.contains("24.2%") && w.contains("2026-08-01~2026-08-31"));
    }

    @Test
    void 배율이_여럿이면_곱한다() {
        List<LedgerItem> ledger = List.of(patternDeposit(1L, 7L, day(3), "1000000", CARD_SETTLEMENT));

        Result result = transform(ledger,
                new ScenarioParameter.DepositDecrease(new BigDecimal("0.2000"), null),
                new ScenarioParameter.SalesChange(new BigDecimal("-0.1000")));

        // 0.8 × 0.9 = 0.72
        assertThat(expectedOf(result, 1L)).isEqualByComparingTo("720000");
    }

    @Test
    void 지출일_변경은_그_항목의_날짜만_바꾼다() {
        List<LedgerItem> ledger = List.of(
                withdrawal(41L, day(5), "2600000"),
                withdrawal(42L, day(6), "900000"));

        Result result = transform(ledger, new ScenarioParameter.ExpenseDateShift(41L, day(20)));

        assertThat(dateOf(result, 41L)).isEqualTo(day(20));
        assertThat(dateOf(result, 42L)).isEqualTo(day(6));
    }

    @Test
    void 스냅샷에_없는_예정_거래는_반영하지_않고_알린다() {
        List<LedgerItem> ledger = List.of(withdrawal(41L, day(5), "2600000"));

        Result result = transform(ledger, new ScenarioParameter.ExpenseDateShift(99L, day(20)));

        assertThat(dateOf(result, 41L)).isEqualTo(day(5));
        assertThat(result.warnings()).anyMatch(w -> w.contains("#99"));
    }

    @Test
    void 추가_지출은_확정_금액으로_한_건_더해진다() {
        Result result = transform(List.of(), new ScenarioParameter.ExtraFlow(
                ScenarioAdjustmentType.EXTRA_EXPENSE, day(10), new BigDecimal("1500000"), "머신 교체"));

        assertThat(result.planned()).singleElement().satisfies(item -> {
            assertThat(item.plannedTransactionId()).isNull();
            assertThat(item.flowType()).isEqualTo(FlowType.WITHDRAWAL);
            assertThat(item.min()).isEqualByComparingTo("1500000");
            assertThat(item.max()).isEqualByComparingTo("1500000");
            assertThat(item.itemConfidence()).isEqualByComparingTo("1");
        });
    }

    @Test
    void 대출_실행은_실행일_입금과_그_뒤_상환_회차를_만든다() {
        Result result = transform(List.of(), new ScenarioParameter.LoanExecution(
                new BigDecimal("10000000"), new BigDecimal("0.045"), 100,
                RepaymentCycle.DAILY, day(1)));

        List<PlannedItem> deposits = result.planned().stream()
                .filter(i -> i.flowType() == FlowType.DEPOSIT).toList();
        List<PlannedItem> repayments = result.planned().stream()
                .filter(i -> i.flowType() == FlowType.WITHDRAWAL).toList();

        assertThat(deposits).singleElement().satisfies(item -> {
            assertThat(item.date()).isEqualTo(day(1));
            assertThat(item.expected()).isEqualByComparingTo("10000000");
        });
        // 실행일 당일에는 빠지지 않는다. 창(30일) 안에 드는 회차만 항목이 된다.
        assertThat(repayments).hasSize(29);
        assertThat(repayments.get(0).date()).isEqualTo(day(2));

        // **상환액을 못박는다.** 이 줄이 없어서 2026-09-16 에 이자 식이 365배 틀린 채로
        // 초록불이었다(S15P21C105-221). 회차 수·날짜만 보면 금액이 통째로 바뀌어도 안 걸린다.
        //
        // 1,000만 · 4.5% · 100일을 금융망 실측 식 P·(1 + r·d²/365) 로 풀면
        // 이자 12,328,767 · 총액 22,328,767 이고 100 회차로 나누면 223,287 원이다.
        // **정상 단리였다면 101,232 원**이다 — 두 식이 이만큼 갈리므로 이 한 줄이
        // 「DAILY 가지가 FIN_NETWORK 를 탄다」를 지킨다.
        assertThat(repayments)
                .as("DAILY 대출이 금융망 실측 식을 타야 한다. 101,232 가 나오면 정상 단리로 돌아간 것이다")
                .allSatisfy(item -> assertThat(item.expected()).isEqualByComparingTo("223287"));
    }

    /**
     * 만기가 창 밖인 이자 전용 대출은 <b>공짜처럼 보인다.</b>
     *
     * <p>원금이 들어오고 이자만 조금씩 빠지는 구간만 보이기 때문이다. 그 오해를 만들지
     * 않으려면 원금이 언제 나가는지 말해야 한다.
     */
    @Test
    void 이자만_내는_대출은_만기가_창_밖이면_그_사실을_알린다() {
        Result result = transform(List.of(), new ScenarioParameter.LoanExecution(
                new BigDecimal("10000000"), new BigDecimal("0.06"), 365,
                RepaymentCycle.INTEREST_MONTHLY, day(1)));

        assertThat(result.warnings()).anyMatch(w -> w.contains("만기") && w.contains("원금"));
    }

    @Test
    void 파라미터가_없으면_원장이_그대로다() {
        List<LedgerItem> ledger = List.of(
                patternDeposit(1L, 7L, day(3), "800000", CARD_SETTLEMENT),
                withdrawal(41L, day(5), "2600000"));

        Result result = ScenarioLedgerTransformer.transform(ledger, List.of(), context(Map.of()));

        assertThat(result.planned()).containsExactlyElementsOf(
                ledger.stream().map(LedgerItem::item).toList());
        assertThat(result.warnings()).isEmpty();
    }

    // --- helpers ------------------------------------------------------------------

    private static Result transform(List<LedgerItem> ledger, ScenarioParameter... parameters) {
        return ScenarioLedgerTransformer.transform(ledger, List.of(parameters), context(Map.of()));
    }

    private static Context context(Map<String, ProductShare> shares) {
        return new Context(BASE, HORIZON, Set.of(CARD_SETTLEMENT, CASH_SALES), shares);
    }

    private static LocalDate day(int offset) {
        return BASE.plusDays(offset);
    }

    private static LedgerItem patternDeposit(long id, long patternId, LocalDate date,
                                             String amount, Long categoryId) {
        return new LedgerItem(item(id, date, FlowType.DEPOSIT, amount), categoryId, patternId);
    }

    private static LedgerItem userDeposit(long id, LocalDate date, String amount, Long categoryId) {
        return new LedgerItem(item(id, date, FlowType.DEPOSIT, amount), categoryId, null);
    }

    private static LedgerItem withdrawal(long id, LocalDate date, String amount) {
        return new LedgerItem(item(id, date, FlowType.WITHDRAWAL, amount), null, null);
    }

    private static PlannedItem item(long id, LocalDate date, FlowType flowType, String amount) {
        BigDecimal value = new BigDecimal(amount);
        return new PlannedItem(id, date, flowType, value, value, value, BigDecimal.ONE);
    }

    private static LocalDate dateOf(Result result, long plannedTransactionId) {
        return find(result, plannedTransactionId).date();
    }

    private static BigDecimal expectedOf(Result result, long plannedTransactionId) {
        return find(result, plannedTransactionId).expected();
    }

    private static PlannedItem find(Result result, long plannedTransactionId) {
        return result.planned().stream()
                .filter(i -> Long.valueOf(plannedTransactionId).equals(i.plannedTransactionId()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("항목 #%d 가 없다".formatted(plannedTransactionId)));
    }
}
