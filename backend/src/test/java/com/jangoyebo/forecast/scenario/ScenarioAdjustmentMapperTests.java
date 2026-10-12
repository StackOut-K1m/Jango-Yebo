package com.jangoyebo.forecast.scenario;

import com.jangoyebo.common.domain.FlowType;
import com.jangoyebo.core.Loans.RepaymentCycle;
import com.jangoyebo.forecast.scenario.ScenarioAdjustment.Details;
import com.jangoyebo.forecast.scenario.ScenarioAdjustmentMapper.Restored;
import com.jangoyebo.forecast.scenario.ScenarioDtos.ParameterDto;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 파라미터 ↔ 저장 행 ↔ 응답 변환.
 *
 * <p><b>Docker 가 필요 없다.</b>
 *
 * <p>왕복이 성립해야 <b>저장한 시나리오를 열었을 때 같은 곡선</b>이 나온다. 저장할 때와
 * 되살릴 때의 자리가 어긋나면 사용자는 「정산 2일 지연」으로 저장한 것이 다른 값으로
 * 열리는 것을 보게 되는데, 숫자만 보는 화면에서는 그 사실이 드러나지 않는다.
 */
class ScenarioAdjustmentMapperTests {

    private static final LocalDate DATE = LocalDate.of(2026, 9, 10);

    @Test
    void 정산_지연은_왕복한다() {
        assertRoundTrip(new ScenarioParameter.SettlementDelay(31L, 2));
        assertRoundTrip(new ScenarioParameter.SettlementDelay(null, 5));
    }

    @Test
    void 입금_감소는_왕복한다() {
        assertRoundTrip(new ScenarioParameter.DepositDecrease(new BigDecimal("0.1000"), 31L));
    }

    @Test
    void 추가_지출입금은_방향과_제목까지_왕복한다() {
        assertRoundTrip(new ScenarioParameter.ExtraFlow(ScenarioAdjustmentType.EXTRA_EXPENSE,
                DATE, new BigDecimal("1500000"), "머신 교체"));
        assertRoundTrip(new ScenarioParameter.ExtraFlow(ScenarioAdjustmentType.EXTRA_DEPOSIT,
                DATE, new BigDecimal("700000"), "보증금 반환"));
    }

    @Test
    void 지출일_변경은_왕복한다() {
        assertRoundTrip(new ScenarioParameter.ExpenseDateShift(41L, DATE));
    }

    @Test
    void 매출_변화는_음수도_왕복한다() {
        assertRoundTrip(new ScenarioParameter.SalesChange(new BigDecimal("-0.0500")));
    }

    @Test
    void 상품_매출_변화는_전용_컬럼이_없어_details_로_왕복한다() {
        assertRoundTrip(new ScenarioParameter.ProductSalesChange("카페라떼", "카페 라떼",
                new BigDecimal("-0.0500"), new BigDecimal("0.1000")));
    }

    @Test
    void 대출_실행은_조건_전부가_왕복한다() {
        assertRoundTrip(new ScenarioParameter.LoanExecution(new BigDecimal("10000000"),
                new BigDecimal("0.045"), 365, RepaymentCycle.MONTHLY, DATE));
    }

    @Test
    void 추가_지출의_방향은_컬럼으로_남는다() {
        ScenarioAdjustment row = ScenarioAdjustmentMapper.toEntity(1L,
                new ScenarioParameter.ExtraFlow(ScenarioAdjustmentType.EXTRA_DEPOSIT,
                        DATE, new BigDecimal("700000"), "보증금 반환"));

        assertThat(row.getFlowType()).isEqualTo(FlowType.DEPOSIT);
        assertThat(row.getChangedDate()).isEqualTo(DATE);
        assertThat(row.getDetails().title()).isEqualTo("보증금 반환");
    }

    /**
     * 대상이 삭제된 조정은 계산에서 빠지고 이유가 남는다 ({@code V32} 의 {@code SET NULL}).
     *
     * <p>조정 행 자체를 지우지 않는 이유가 여기서 드러난다 — 행이 남아 있어야 「무엇이 왜
     * 빠졌는지」를 말할 수 있다.
     */
    @Test
    void 대상_예정_거래가_지워진_조정은_반영하지_않고_이유를_남긴다() {
        ScenarioAdjustment row = new ScenarioAdjustment(1L,
                ScenarioAdjustmentType.EXPENSE_DATE_SHIFT, null, null, null, null, null, null,
                DATE, details(41L, null));

        Restored restored = ScenarioAdjustmentMapper.restore(row);

        assertThat(restored.parameter()).isNull();
        assertThat(restored.warning()).contains("삭제되어");
    }

    /**
     * 「대상이 지워졌다」와 「처음부터 전체였다」를 구분한다.
     *
     * <p>컬럼만 보면 둘 다 {@code null} 이다. 구분하지 못하면 대상 패턴이 사라진 시나리오가
     * 조용히 <b>입금 패턴 전체</b>로 넓어져, 저장할 때 본 것보다 훨씬 큰 변화가 나타난다.
     */
    @Test
    void 처음부터_전체였던_지연은_그대로_되살아난다() {
        ScenarioAdjustment row = ScenarioAdjustmentMapper.toEntity(1L,
                new ScenarioParameter.SettlementDelay(null, 2));

        Restored restored = ScenarioAdjustmentMapper.restore(row);

        assertThat(restored.warning()).isNull();
        assertThat(restored.parameter())
                .isEqualTo(new ScenarioParameter.SettlementDelay(null, 2));
    }

    @Test
    void 대상_패턴이_지워진_지연은_전체로_넓어지지_않는다() {
        ScenarioAdjustment row = new ScenarioAdjustment(1L,
                ScenarioAdjustmentType.SETTLEMENT_DELAY, null, null, null, null, null, 2,
                null, details(null, 31L));

        Restored restored = ScenarioAdjustmentMapper.restore(row);

        assertThat(restored.parameter()).isNull();
        assertThat(restored.warning()).contains("입금 패턴");
    }

    /** 수기 INSERT 로 들어온 행처럼 {@code details} 가 없어도 터지지 않는다. */
    @Test
    void details_가_없는_행은_이유를_남기고_건너뛴다() {
        ScenarioAdjustment row = new ScenarioAdjustment(1L,
                ScenarioAdjustmentType.PRODUCT_SALES_CHANGE, null, null, null, null,
                new BigDecimal("0.1000"), null, null, null);

        Restored restored = ScenarioAdjustmentMapper.restore(row);

        assertThat(restored.parameter()).isNull();
        assertThat(restored.warning()).contains("읽을 수 없어");
    }

    /**
     * 반영하지 못한 조정도 목록에는 남는다.
     *
     * <p>저장한 조건이 응답에서 사라지면 사용자는 결과가 왜 달라졌는지 알 수 없다.
     * 빠졌다는 사실은 {@code warnings} 가 따로 말한다.
     */
    @Test
    void 대상이_지워져도_저장한_조건은_그대로_보여_준다() {
        ScenarioAdjustment row = new ScenarioAdjustment(1L,
                ScenarioAdjustmentType.EXPENSE_DATE_SHIFT, null, null, null, null, null, null,
                DATE, details(41L, null));

        ParameterDto dto = ScenarioAdjustmentMapper.toDto(row);

        assertThat(dto.type()).isEqualTo("EXPENSE_DATE_SHIFT");
        assertThat(dto.plannedTransactionId()).isEqualTo(41L);
        assertThat(dto.newDate()).isEqualTo(DATE);
    }

    // --- helpers ------------------------------------------------------------------

    private static void assertRoundTrip(ScenarioParameter parameter) {
        ScenarioAdjustment row = ScenarioAdjustmentMapper.toEntity(1L, parameter);

        assertThat(row.getAdjustmentType()).isEqualTo(parameter.type());
        assertThat(ScenarioAdjustmentMapper.restore(row).parameter()).isEqualTo(parameter);
        // 저장 행에서 만든 응답과 파라미터에서 만든 응답이 같아야 목록과 결과가 어긋나지 않는다.
        assertThat(ScenarioAdjustmentMapper.toDto(row))
                .isEqualTo(ScenarioAdjustmentMapper.toDto(parameter));
    }

    private static Details details(Long plannedTransactionId, Long patternId) {
        return new Details(null, null, null, null, null, null, null, null,
                patternId, plannedTransactionId);
    }
}
