package com.jangoyebo.forecast.engine;

import com.jangoyebo.common.domain.FlowType;
import com.jangoyebo.forecast.engine.BalanceCurveCalculator.CurveInput;
import com.jangoyebo.forecast.engine.BalanceCurveCalculator.Curve;
import com.jangoyebo.forecast.engine.BalanceCurveCalculator.DailyPoint;
import com.jangoyebo.forecast.engine.BalanceCurveCalculator.PlannedItem;
import com.jangoyebo.forecast.engine.BalanceCurveCalculator.Quantile;
import com.jangoyebo.forecast.engine.BalanceCurveCalculator.ResidualQuantiles;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Random;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Layer 1 회계 항등식 (P0-07).
 *
 * <p><b>Docker 가 필요 없다.</b> 계산 엔진이 순수 함수라 DB 없이 돈다 — 아키텍처 §6 이
 * 계산 엔진을 순수 함수로 유지하기로 한 이유가 이것이다. 기준일을 인자로 받으므로
 * <b>날짜가 흘러도 결과가 바뀌지 않는다.</b>
 */
class BalanceCurveCalculatorTests {

    private static final LocalDate BASE = LocalDate.of(2026, 8, 31);
    private static final BigDecimal OPENING = new BigDecimal("5000000");

    @Test
    void 곡선은_기준일_다음날부터_시작한다() {
        Curve curve = BalanceCurveCalculator.compute(input(30, List.of(), Optional.empty()));

        assertThat(curve.points()).hasSize(30);
        assertThat(curve.points().get(0).date()).isEqualTo(LocalDate.of(2026, 9, 1));
        assertThat(curve.points().get(29).date()).isEqualTo(LocalDate.of(2026, 9, 30));
    }

    /**
     * 기준일 당일자는 첫 점이 받는다.
     *
     * <p>시작 잔액은 이미 일어난 거래만 담는다. 당일자 예정 거래를 여기서도 빼면 곡선
     * 어디에도 남지 않아 <b>전 구간이 그 금액만큼 높아진다</b> — 공백 경보가 울려야 할
     * 자리에서 울리지 않는다.
     */
    @Test
    void 기준일_당일자_예정거래는_첫_점에_합산된다() {
        List<PlannedItem> planned = List.of(
                withdrawal(1L, BASE, "8000000"),
                withdrawal(2L, LocalDate.of(2026, 9, 2), "300000"));

        Curve curve = BalanceCurveCalculator.compute(input(5, planned, Optional.empty()));

        // 8/31 자 800만이 9/1 점에 실린다 — 딥은 하루 늦게 찍히고 레벨은 맞는다.
        assertThat(curve.points().get(0).date()).isEqualTo(LocalDate.of(2026, 9, 1));
        assertThat(curve.points().get(0).expectedWithdrawal()).isEqualByComparingTo("8000000");
        assertThat(curve.points().get(0).balanceExpected()).isEqualByComparingTo("-3000000");
        assertThat(curve.points().get(1).balanceExpected()).isEqualByComparingTo("-3300000");
        // 두 번 세지 않는다 — 이후 구간이 그대로다.
        assertThat(curve.points().get(4).balanceExpected()).isEqualByComparingTo("-3300000");
    }

    @Test
    void 예정거래가_없으면_잔액이_시작잔액_그대로다() {
        Curve curve = BalanceCurveCalculator.compute(input(7, List.of(), Optional.empty()));

        assertThat(curve.points()).allSatisfy(p -> {
            assertThat(p.balanceExpected()).isEqualByComparingTo(OPENING);
            assertThat(p.balanceMin()).isEqualByComparingTo(OPENING);
            assertThat(p.balanceMax()).isEqualByComparingTo(OPENING);
        });
    }

    @Test
    void 입금과_출금이_날짜별로_누적된다() {
        List<PlannedItem> planned = List.of(
                deposit(1L, LocalDate.of(2026, 9, 1), "800000"),
                withdrawal(2L, LocalDate.of(2026, 9, 3), "300000"));

        Curve curve = BalanceCurveCalculator.compute(input(5, planned, Optional.empty()));

        // 9/1 +80만 → 9/2 유지 → 9/3 −30만 → 이후 유지
        assertThat(curve.points().get(0).balanceExpected()).isEqualByComparingTo("5800000");
        assertThat(curve.points().get(1).balanceExpected()).isEqualByComparingTo("5800000");
        assertThat(curve.points().get(2).balanceExpected()).isEqualByComparingTo("5500000");
        assertThat(curve.points().get(4).balanceExpected()).isEqualByComparingTo("5500000");
    }

    @Test
    void 범위는_가장_나쁜_조합과_가장_좋은_조합이다() {
        List<PlannedItem> planned = List.of(
                new PlannedItem(1L, LocalDate.of(2026, 9, 1), FlowType.DEPOSIT,
                        new BigDecimal("700000"), new BigDecimal("800000"), new BigDecimal("900000"), BigDecimal.ONE),
                new PlannedItem(2L, LocalDate.of(2026, 9, 1), FlowType.WITHDRAWAL,
                        new BigDecimal("100000"), new BigDecimal("150000"), new BigDecimal("200000"), BigDecimal.ONE));

        DailyPoint day = BalanceCurveCalculator.compute(input(1, planned, Optional.empty())).points().get(0);

        assertThat(day.balanceExpected()).isEqualByComparingTo("5650000");

        // 누적 폭은 「입금 하한 − 출금 상한」과 「입금 상한 − 출금 하한」이다.
        //   하한 5,500,000 = 500만 + 70만 − 20만        반폭 150,000
        //   상한 5,800,000 = 500만 + 90만 − 10만        반폭 150,000
        // 내보낼 때 커버리지 배율이 걸린다 (1일차 → 0.42).
        BigDecimal half = new BigDecimal("150000").multiply(BalanceCurveCalculator.coverageShrink(1));
        assertThat(day.balanceMin()).isEqualByComparingTo(new BigDecimal("5650000").subtract(half));
        assertThat(day.balanceMax()).isEqualByComparingTo(new BigDecimal("5650000").add(half));
    }

    @Test
    void 금액_순서가_뒤집힌_입력은_정렬해서_받는다() {
        // 시나리오(P0-09)가 만든 입력은 DB 제약을 거치지 않는다.
        PlannedItem item = new PlannedItem(1L, LocalDate.of(2026, 9, 1), FlowType.DEPOSIT,
                new BigDecimal("900000"), new BigDecimal("800000"), new BigDecimal("700000"), BigDecimal.ONE);

        assertThat(item.min()).isEqualByComparingTo("700000");
        assertThat(item.max()).isEqualByComparingTo("900000");
    }

    @Test
    void 잔여를_주면_알파로_대표값을_접는다() {
        // α=0 이면 q50 을 그대로 쓴다.
        ResidualQuantiles residual = flatResidual(5, "10000", "50000", "90000", "5000", "20000", "60000");

        DailyPoint day = BalanceCurveCalculator
                .compute(input(5, List.of(), Optional.of(residual), BigDecimal.ZERO))
                .points().get(0);

        assertThat(day.residualDeposit()).isEqualByComparingTo("50000");
        assertThat(day.residualWithdrawal()).isEqualByComparingTo("20000");
        assertThat(day.balanceExpected()).isEqualByComparingTo("5030000");   // 500만 + 5만 − 2만
    }

    @Test
    void 알파를_올리면_입금은_하한쪽_출금은_상한쪽으로_간다() {
        ResidualQuantiles residual = flatResidual(1, "10000", "50000", "90000", "5000", "20000", "60000");

        DailyPoint day = BalanceCurveCalculator
                .compute(input(1, List.of(), Optional.of(residual), BigDecimal.ONE))
                .points().get(0);

        // α=1 → 입금은 q10, 출금은 q90
        assertThat(day.residualDeposit()).isEqualByComparingTo("10000");
        assertThat(day.residualWithdrawal()).isEqualByComparingTo("60000");
    }

    @Test
    void 알파가_범위를_벗어나면_잘라서_쓴다() {
        ResidualQuantiles residual = flatResidual(1, "10000", "50000", "90000", "5000", "20000", "60000");

        DailyPoint clampedHigh = BalanceCurveCalculator
                .compute(input(1, List.of(), Optional.of(residual), new BigDecimal("3"))).points().get(0);
        DailyPoint clampedLow = BalanceCurveCalculator
                .compute(input(1, List.of(), Optional.of(residual), new BigDecimal("-1"))).points().get(0);

        assertThat(clampedHigh.residualDeposit()).isEqualByComparingTo("10000");   // α=1 과 같다
        assertThat(clampedLow.residualDeposit()).isEqualByComparingTo("50000");    // α=0 과 같다
    }

    @Test
    void 잔여는_합계에_이미_포함된_내역이다() {
        // ERD: residual_* 를 expected_* 에 더하면 이중 계상이다.
        ResidualQuantiles residual = flatResidual(1, "10000", "50000", "90000", "0", "0", "0");
        List<PlannedItem> planned = List.of(deposit(1L, LocalDate.of(2026, 9, 1), "800000"));

        DailyPoint day = BalanceCurveCalculator
                .compute(input(1, planned, Optional.of(residual), BigDecimal.ZERO)).points().get(0);

        assertThat(day.expectedDeposit()).isEqualByComparingTo("850000");   // 원장 80만 + 잔여 5만
        assertThat(day.residualDeposit()).isEqualByComparingTo("50000");
    }

    @Test
    void 잔여_시계열_길이가_기간과_다르면_거부한다() {
        ResidualQuantiles residual = flatResidual(7, "0", "0", "0", "0", "0", "0");

        assertThatThrownBy(() -> BalanceCurveCalculator.compute(input(30, List.of(), Optional.of(residual))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("길이");
    }

    @Test
    void 최저점은_동률이면_빠른_날이다() {
        List<PlannedItem> planned = List.of(
                withdrawal(1L, LocalDate.of(2026, 9, 2), "1000000"),
                deposit(2L, LocalDate.of(2026, 9, 4), "1000000"),
                withdrawal(3L, LocalDate.of(2026, 9, 5), "1000000"));

        Curve curve = BalanceCurveCalculator.compute(input(6, planned, Optional.empty()));

        // 9/2~9/3 과 9/5~9/6 이 똑같이 400만이다.
        assertThat(curve.lowestPoint().balanceExpected()).isEqualByComparingTo("4000000");
        assertThat(curve.lowestPoint().date()).isEqualTo(LocalDate.of(2026, 9, 2));
    }

    @Test
    void 같은_입력은_항상_같은_결과다() {
        // 인수 조건: 동일 입력 + 동일 modelVersion 에 대해 재현된다 (SRS 13장).
        CurveInput in = input(30, sampleLedger(), Optional.of(flatResidual(30, "10000", "50000", "90000", "5000", "20000", "60000")));

        Curve first = BalanceCurveCalculator.compute(in);
        Curve second = BalanceCurveCalculator.compute(in);

        assertThat(first).isEqualTo(second);
    }

    @Test
    void 무작위_입력_천건에서_범위_불변식이_깨지지_않는다() {
        // ck_dbf_range(min ≤ expected ≤ max)가 INSERT 에서 터지지 않는다는 것을 여기서 증명한다.
        Random random = new Random(20260910L);

        for (int trial = 0; trial < 1000; trial++) {
            int horizon = 1 + random.nextInt(30);
            List<PlannedItem> planned = randomLedger(random, horizon);
            Optional<ResidualQuantiles> residual = random.nextBoolean()
                    ? Optional.of(randomResidual(random, horizon))
                    : Optional.empty();
            BigDecimal alpha = new BigDecimal(random.nextInt(101)).movePointLeft(2);

            Curve curve = BalanceCurveCalculator.compute(
                    new CurveInput(BASE, horizon, OPENING, planned, residual, alpha));

            assertThat(curve.points()).hasSize(horizon);
            assertThat(curve.points()).allSatisfy(p -> {
                assertThat(p.balanceMin()).isLessThanOrEqualTo(p.balanceExpected());
                assertThat(p.balanceExpected()).isLessThanOrEqualTo(p.balanceMax());
            });
        }
    }

    // --- 밴드 커버리지 보정 -----------------------------------------------------

    /**
     * <b>이 작업의 핵심 단정이다.</b> 밴드를 좁히는 것이 판정을 건드리면 안 된다 —
     * 대표선이 한 값이라도 움직이면 공백 날짜가 따라 움직이고, 그러면 모델 채택 게이트가
     * 재 둔 숫자가 전부 무효가 된다.
     */
    @Test
    void 밴드를_좁혀도_대표선이_안_바뀐다() {
        Random random = new Random(20260914);
        int horizon = 30;
        List<PlannedItem> planned = randomLedger(random, horizon);
        ResidualQuantiles residual = randomResidual(random, horizon);

        Curve curve = BalanceCurveCalculator.compute(input(horizon, planned, Optional.of(residual)));

        // 대표선은 min·max 와 무관하게 누적된다. 손으로 다시 세어 같은지 본다.
        BigDecimal running = OPENING;
        for (int i = 0; i < horizon; i++) {
            LocalDate date = BASE.plusDays(i + 1L);
            BigDecimal deposit = BigDecimal.ZERO;
            BigDecimal withdrawal = BigDecimal.ZERO;
            for (PlannedItem item : planned) {
                boolean counts = item.date().equals(date) || (i == 0 && !item.date().isAfter(date));
                if (!counts) {
                    continue;
                }
                if (item.flowType() == FlowType.DEPOSIT) {
                    deposit = deposit.add(item.expected());
                } else {
                    withdrawal = withdrawal.add(item.expected());
                }
            }
            // α = 0 이라 잔여 대표값은 q50 이다.
            running = running.add(deposit).subtract(withdrawal)
                    .add(residual.deposit().get(i).q50())
                    .subtract(residual.withdrawal().get(i).q50());
            assertThat(curve.points().get(i).balanceExpected())
                    .as("%s 대표선", date)
                    .isEqualByComparingTo(running);
        }
    }

    @Test
    void 밴드가_대표선을_감싼다() {
        Random random = new Random(11);
        int horizon = 30;
        Curve curve = BalanceCurveCalculator.compute(
                input(horizon, randomLedger(random, horizon), Optional.of(randomResidual(random, horizon))));

        for (DailyPoint p : curve.points()) {
            assertThat(p.balanceMin()).as("%s 하한", p.date()).isLessThanOrEqualTo(p.balanceExpected());
            assertThat(p.balanceMax()).as("%s 상한", p.date()).isGreaterThanOrEqualTo(p.balanceExpected());
        }
    }

    /** 과잉이 지평에 따라 커지므로(2.4 → 4.3배) 배율은 뒤로 갈수록 작아야 한다. */
    @Test
    void 지평이_길수록_더_좁힌다() {
        assertThat(BalanceCurveCalculator.coverageShrink(1))
                .isEqualByComparingTo(BalanceCurveCalculator.coverageShrink(7));
        assertThat(BalanceCurveCalculator.coverageShrink(7))
                .isGreaterThan(BalanceCurveCalculator.coverageShrink(14));
        assertThat(BalanceCurveCalculator.coverageShrink(14))
                .isGreaterThan(BalanceCurveCalculator.coverageShrink(30));
        // 창 상한 뒤로는 더 줄이지 않는다 — 잰 구간이 30일까지다.
        assertThat(BalanceCurveCalculator.coverageShrink(31))
                .isEqualByComparingTo(BalanceCurveCalculator.coverageShrink(30));
    }

    /**
     * 배율은 <b>내보낼 때만</b> 걸려야 한다. 누적 변수에서 좁히면 다음 날이 좁혀진 값에서
     * 다시 출발해 매일 복리로 줄어든다 — 30일차 폭이 배율 한 번 적용한 것보다 훨씬 작아진다.
     */
    @Test
    void 좁히는_것이_다음_날_누적에_스며들지_않는다() {
        int horizon = 30;
        // 하루 10만원씩 폭이 벌어지는 원장. 누적 폭은 날마다 정확히 10만원씩 는다.
        List<PlannedItem> planned = new ArrayList<>();
        for (int i = 1; i <= horizon; i++) {
            planned.add(new PlannedItem((long) i, BASE.plusDays(i), FlowType.WITHDRAWAL,
                    BigDecimal.ZERO, new BigDecimal("50000"), new BigDecimal("100000"), BigDecimal.ONE));
        }
        Curve curve = BalanceCurveCalculator.compute(input(horizon, planned, Optional.empty()));

        DailyPoint last = curve.points().get(horizon - 1);
        BigDecimal rawHalf = new BigDecimal("50000").multiply(BigDecimal.valueOf(horizon)); // 보정 전 반폭
        BigDecimal expectedHalf = rawHalf.multiply(BalanceCurveCalculator.coverageShrink(horizon));

        assertThat(last.balanceExpected().subtract(last.balanceMin()))
                .isEqualByComparingTo(expectedHalf);
        assertThat(last.balanceMax().subtract(last.balanceExpected()))
                .isEqualByComparingTo(expectedHalf);
    }

    // --- 헬퍼 -----------------------------------------------------------------

    private static CurveInput input(int horizon, List<PlannedItem> planned, Optional<ResidualQuantiles> residual) {
        return input(horizon, planned, residual, BigDecimal.ZERO);
    }

    private static CurveInput input(int horizon, List<PlannedItem> planned,
                                    Optional<ResidualQuantiles> residual, BigDecimal alpha) {
        return new CurveInput(BASE, horizon, OPENING, planned, residual, alpha);
    }

    private static PlannedItem deposit(long id, LocalDate date, String amount) {
        BigDecimal value = new BigDecimal(amount);
        return new PlannedItem(id, date, FlowType.DEPOSIT, value, value, value, BigDecimal.ONE);
    }

    private static PlannedItem withdrawal(long id, LocalDate date, String amount) {
        BigDecimal value = new BigDecimal(amount);
        return new PlannedItem(id, date, FlowType.WITHDRAWAL, value, value, value, BigDecimal.ONE);
    }

    private static ResidualQuantiles flatResidual(int days, String d10, String d50, String d90,
                                                  String w10, String w50, String w90) {
        List<Quantile> deposit = new ArrayList<>();
        List<Quantile> withdrawal = new ArrayList<>();
        for (int i = 0; i < days; i++) {
            deposit.add(new Quantile(new BigDecimal(d10), new BigDecimal(d50), new BigDecimal(d90)));
            withdrawal.add(new Quantile(new BigDecimal(w10), new BigDecimal(w50), new BigDecimal(w90)));
        }
        return new ResidualQuantiles(BASE.plusDays(1), deposit, withdrawal);
    }

    private static List<PlannedItem> sampleLedger() {
        return List.of(
                deposit(1L, LocalDate.of(2026, 9, 1), "850000"),
                withdrawal(2L, LocalDate.of(2026, 9, 14), "3000000"),
                deposit(3L, LocalDate.of(2026, 9, 20), "1200000"));
    }

    private static List<PlannedItem> randomLedger(Random random, int horizon) {
        List<PlannedItem> items = new ArrayList<>();
        int count = random.nextInt(10);
        for (int i = 0; i < count; i++) {
            // 0 은 기준일 당일 — 첫 점이 끌어안는 구간도 불변식에 걸어 둔다.
            LocalDate date = BASE.plusDays(random.nextInt(horizon + 1));
            BigDecimal lo = BigDecimal.valueOf(random.nextInt(1_000_000));
            BigDecimal mid = lo.add(BigDecimal.valueOf(random.nextInt(500_000)));
            BigDecimal hi = mid.add(BigDecimal.valueOf(random.nextInt(500_000)));
            FlowType flow = random.nextBoolean() ? FlowType.DEPOSIT : FlowType.WITHDRAWAL;
            items.add(new PlannedItem((long) i, date, flow, lo, mid, hi,
                    BigDecimal.valueOf(random.nextInt(101)).movePointLeft(2)));
        }
        return items;
    }

    private static ResidualQuantiles randomResidual(Random random, int horizon) {
        List<Quantile> deposit = new ArrayList<>();
        List<Quantile> withdrawal = new ArrayList<>();
        for (int i = 0; i < horizon; i++) {
            deposit.add(randomQuantile(random));
            withdrawal.add(randomQuantile(random));
        }
        return new ResidualQuantiles(BASE.plusDays(1), deposit, withdrawal);
    }

    private static Quantile randomQuantile(Random random) {
        BigDecimal q10 = BigDecimal.valueOf(random.nextInt(100_000));
        BigDecimal q50 = q10.add(BigDecimal.valueOf(random.nextInt(100_000)));
        BigDecimal q90 = q50.add(BigDecimal.valueOf(random.nextInt(100_000)));
        return new Quantile(q10, q50, q90);
    }
}
