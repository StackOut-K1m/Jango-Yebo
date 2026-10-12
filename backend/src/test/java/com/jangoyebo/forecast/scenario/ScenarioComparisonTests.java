package com.jangoyebo.forecast.scenario;

import com.jangoyebo.forecast.engine.BalanceCurveCalculator;
import com.jangoyebo.forecast.engine.BalanceCurveCalculator.Curve;
import com.jangoyebo.forecast.engine.BalanceCurveCalculator.CurveInput;
import com.jangoyebo.forecast.engine.BalanceCurveCalculator.PlannedItem;
import com.jangoyebo.forecast.scenario.ScenarioDtos.Summary;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import static com.jangoyebo.common.domain.FlowType.WITHDRAWAL;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * 비교 지표 (API 11.1 {@code comparison}).
 *
 * <p><b>Docker 가 필요 없다.</b>
 *
 * <p>여기서 지키려는 것은 <b>같은 곡선을 두고 화면마다 다른 날짜를 말하지 않는 것</b>이다.
 * 공백과 안전선 하회는 10.2·12.1 이 쓰는 판정기를 그대로 부르고, 여기서 따로 세지 않는다.
 */
class ScenarioComparisonTests {

    private static final LocalDate BASE = LocalDate.of(2026, 8, 31);
    private static final BigDecimal OPENING = new BigDecimal("3000000");

    @Test
    void 최저점은_동률이면_빠른_날이다() {
        // 9/2 에 300만이 빠져 잔액 0 이 되고 그 뒤로 변화가 없다.
        Curve curve = curve(10, withdrawal(1L, BASE.plusDays(2), "3000000"));

        Summary summary = ScenarioComparison.summarize(curve, null);

        assertThat(summary.lowestBalance()).isEqualByComparingTo("0");
        assertThat(summary.lowestBalanceDate()).isEqualTo(LocalDate.of(2026, 9, 2));
    }

    @Test
    void 부족이_없으면_최초_부족일이_비고_공백_일수는_0이다() {
        Summary summary = ScenarioComparison.summarize(curve(10), null);

        // null 이면 전역 non_null 정책이 키를 통째로 뺀다 — 화면은 그것을 「없음」으로 읽는다.
        assertThat(summary.firstShortfallDate()).isNull();
        // 반대로 0 은 정보다. int 라 키가 빠지지 않는다.
        assertThat(summary.gapDurationDays()).isZero();
    }

    /** 공백은 {@code expectedBalance < 0} 으로 판정한다 (10.2). 하한선으로 재지 않는다. */
    @Test
    void 공백은_예상_잔액이_음수인_날을_센다() {
        Curve curve = curve(10,
                withdrawal(1L, BASE.plusDays(2), "4000000"),
                deposit(2L, BASE.plusDays(5), "2000000"));

        Summary summary = ScenarioComparison.summarize(curve, null);

        // 9/2 ~ 9/4 사흘이 음수, 9/5 에 회복한다.
        assertThat(summary.firstShortfallDate()).isEqualTo(LocalDate.of(2026, 9, 2));
        assertThat(summary.gapDurationDays()).isEqualTo(3);
    }

    @Test
    void 공백이_두_구간이면_일수를_합친다() {
        Curve curve = curve(12,
                withdrawal(1L, BASE.plusDays(1), "4000000"),
                deposit(2L, BASE.plusDays(3), "2000000"),
                withdrawal(3L, BASE.plusDays(6), "1500000"),
                deposit(4L, BASE.plusDays(8), "3000000"));

        Summary summary = ScenarioComparison.summarize(curve, null);

        assertThat(summary.firstShortfallDate()).isEqualTo(LocalDate.of(2026, 9, 1));
        assertThat(summary.gapDurationDays()).isEqualTo(4);
    }

    /**
     * 안전선을 설정하지 않은 것과 하회한 날이 없는 것은 다르다.
     *
     * <p>{@code null} 은 「잴 선이 없다」이고 {@code 0} 은 「재 봤더니 없다」다. 둘을 같은
     * 값으로 내면 화면이 「안전선 아래인 날 0일」이라고 안심시키는데 실제로는 기준이 없다.
     */
    @Test
    void 안전선이_없으면_하회_일수는_0이_아니라_비어_있다() {
        Curve curve = curve(10, withdrawal(1L, BASE.plusDays(2), "2500000"));

        assertThat(ScenarioComparison.summarize(curve, null).belowSafetyLineDays()).isNull();
        assertThat(ScenarioComparison.summarize(curve, new BigDecimal("1000000"))
                .belowSafetyLineDays()).isNotNull();
    }

    @Test
    void 안전선_하회는_그_선_아래인_날을_센다() {
        Curve curve = curve(10,
                withdrawal(1L, BASE.plusDays(2), "2500000"),
                deposit(2L, BASE.plusDays(5), "2000000"));

        Summary summary = ScenarioComparison.summarize(curve, new BigDecimal("1000000"));

        // 잔액 50만인 9/2~9/4 사흘이 안전선 아래고, 9/5 에 250만으로 올라선다.
        assertThat(summary.belowSafetyLineDays()).isEqualTo(3);
        assertThat(summary.gapDurationDays()).isZero();
    }

    @Test
    void 같은_곡선은_같은_지표를_낸다() {
        Curve one = curve(10, withdrawal(1L, BASE.plusDays(2), "2500000"));
        Curve two = curve(10, withdrawal(1L, BASE.plusDays(2), "2500000"));

        assertThat(ScenarioComparison.summarize(one, new BigDecimal("1000000")))
                .isEqualTo(ScenarioComparison.summarize(two, new BigDecimal("1000000")));
    }

    @Test
    void 금액은_정수_원으로_나간다() {
        Curve curve = curve(3, withdrawal(1L, BASE.plusDays(1), "1000000.4900"));

        assertThat(ScenarioComparison.summarize(curve, null).lowestBalance().scale()).isZero();
    }

    // --- helpers ------------------------------------------------------------------

    private static Curve curve(int horizon, PlannedItem... planned) {
        return BalanceCurveCalculator.compute(new CurveInput(
                BASE, horizon, OPENING, List.of(planned), Optional.empty(), BigDecimal.ZERO));
    }

    private static PlannedItem withdrawal(long id, LocalDate date, String amount) {
        BigDecimal value = new BigDecimal(amount);
        return new PlannedItem(id, date, WITHDRAWAL, value, value, value, BigDecimal.ONE);
    }

    private static PlannedItem deposit(long id, LocalDate date, String amount) {
        BigDecimal value = new BigDecimal(amount);
        return new PlannedItem(id, date, com.jangoyebo.common.domain.FlowType.DEPOSIT,
                value, value, value, BigDecimal.ONE);
    }
}
