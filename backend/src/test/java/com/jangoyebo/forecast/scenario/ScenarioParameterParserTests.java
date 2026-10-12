package com.jangoyebo.forecast.scenario;

import com.jangoyebo.common.error.BusinessException;
import com.jangoyebo.common.error.ErrorCode;
import com.jangoyebo.forecast.scenario.ScenarioDtos.ParameterDto;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.function.UnaryOperator;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 파라미터 검증 (API 11.1).
 *
 * <p><b>Docker 가 필요 없다.</b> 파서가 DB 도 기준일도 보지 않는 순수 함수라, 「무엇을
 * 거절하는가」를 컨테이너 없이 전부 증명할 수 있다.
 *
 * <p>여기서 지키려는 것은 <b>화면이 어느 조건 카드를 고쳐야 하는지 알 수 있는가</b>다.
 * 시나리오는 조건을 여러 장 쌓아 보내므로 "값이 잘못됐습니다"만으로는 쓸모가 없다.
 */
class ScenarioParameterParserTests {

    /** 이 테스트에서 정규화는 소문자·공백 제거만 확인하면 된다 — 규칙 자체는 core 의 몫이다. */
    private static final UnaryOperator<String> KEY_OF = s -> s.replace(" ", "").toLowerCase();

    @Test
    void 모르는_종류는_지원하지_않는다고_말한다() {
        assertThatThrownBy(() -> parse(dto(b -> b.type = "MATERIAL_COST_CHANGE")))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("parameters[0].type")
                .hasMessageContaining("MATERIAL_COST_CHANGE")
                .extracting(e -> ((BusinessException) e).errorCode())
                .isEqualTo(ErrorCode.SCENARIO_PARAM_INVALID);
    }

    /**
     * 본문을 못 읽는 것과 파라미터가 틀린 것은 다른 오류다.
     *
     * <p>{@code type} 을 enum 으로 받으면 Jackson 이 파싱 단계에서 터지고 전역 핸들러가
     * {@code VALIDATION_FAILED "요청 본문을 읽을 수 없습니다"} 로 뭉갠다 — 그러면 명세가
     * 11.1 에 약속한 코드가 나가지 않는다. String 으로 받는 이유가 이것이다.
     */
    @Test
    void 모르는_종류도_본문_오류가_아니라_파라미터_오류다() {
        assertThatThrownBy(() -> parse(dto(b -> b.type = "NOPE")))
                .extracting(e -> ((BusinessException) e).errorCode())
                .isNotEqualTo(ErrorCode.VALIDATION_FAILED);
    }

    @Test
    void 몇_번째_파라미터인지_자리를_알려_준다() {
        BusinessException e = catchParse(
                dto(b -> { b.type = "SALES_CHANGE"; b.rate = new BigDecimal("-0.05"); }),
                dto(b -> { b.type = "DEPOSIT_DECREASE"; b.rate = new BigDecimal("2"); }));

        assertThat(e).hasMessageContaining("parameters[1].rate");
        assertThat(e.detail()).containsEntry("index", 1).containsEntry("field", "rate")
                .containsEntry("type", "DEPOSIT_DECREASE");
    }

    @Test
    void 정산_지연은_1일부터_90일까지다() {
        assertThat(parse(delay(1))).hasSize(1);
        assertThat(parse(delay(90))).hasSize(1);
        assertThatThrownBy(() -> parse(delay(0))).hasMessageContaining("parameters[0].delayDays");
        assertThatThrownBy(() -> parse(delay(91))).hasMessageContaining("parameters[0].delayDays");
    }

    @Test
    void 입금_감소율은_0_초과_1_이하다() {
        assertThat(parse(dto(b -> { b.type = "DEPOSIT_DECREASE"; b.rate = new BigDecimal("1"); })))
                .hasSize(1);
        assertThatThrownBy(() -> parse(dto(b -> {
            b.type = "DEPOSIT_DECREASE";
            b.rate = BigDecimal.ZERO;
        }))).hasMessageContaining("0 보다 크고");
    }

    /** 매출 변화만 음수를 받는다 — 11.1 이 두 파라미터를 표로 갈라 둔 지점이다. */
    @Test
    void 매출_변화는_음수를_받는다() {
        List<ScenarioParameter> parsed = parse(dto(b -> {
            b.type = "SALES_CHANGE";
            b.rate = new BigDecimal("-0.05");
        }));

        assertThat(parsed).singleElement()
                .isInstanceOfSatisfying(ScenarioParameter.SalesChange.class,
                        p -> assertThat(p.rate()).isEqualByComparingTo("-0.05"));
    }

    @Test
    void 비율은_저장_자리수인_소수_넷째까지로_맞춘다() {
        List<ScenarioParameter> parsed = parse(dto(b -> {
            b.type = "DEPOSIT_DECREASE";
            b.rate = new BigDecimal("0.123456");
        }));

        // percentage_value 가 NUMERIC(7,4) 다. 저장한 값과 에코한 값이 달라지면 안 된다.
        assertThat(((ScenarioParameter.DepositDecrease) parsed.get(0)).rate())
                .isEqualByComparingTo("0.1235");
    }

    @Test
    void 추가_지출은_금액과_제목이_있어야_한다() {
        assertThatThrownBy(() -> parse(dto(b -> {
            b.type = "EXTRA_EXPENSE";
            b.date = LocalDate.of(2026, 9, 10);
            b.amount = new BigDecimal("1500000");
        }))).hasMessageContaining("parameters[0].title");

        assertThatThrownBy(() -> parse(dto(b -> {
            b.type = "EXTRA_EXPENSE";
            b.date = LocalDate.of(2026, 9, 10);
            b.amount = BigDecimal.ZERO;
            b.title = "머신 교체";
        }))).hasMessageContaining("0 보다 커야");
    }

    /**
     * 유형이 쓰지 않는 필드를 무시하지 않는다.
     *
     * <p>무시하면 사용자가 넣은 조건이 조용히 사라진다 — 곡선은 지연만 반영한 값인데
     * 화면은 감소율도 반영됐다고 읽는다. 숫자만 보는 화면에서는 드러나지 않는 차이다.
     */
    @Test
    void 쓰지_않는_필드가_섞이면_거절한다() {
        assertThatThrownBy(() -> parse(dto(b -> {
            b.type = "SETTLEMENT_DELAY";
            b.delayDays = 2;
            b.rate = new BigDecimal("0.2");
        }))).hasMessageContaining("parameters[0].rate")
                .hasMessageContaining("SETTLEMENT_DELAY");
    }

    @Test
    void 같은_예정_거래를_두_번_옮길_수_없다() {
        assertThatThrownBy(() -> parse(
                shift(41L, LocalDate.of(2026, 9, 20)),
                shift(41L, LocalDate.of(2026, 9, 25))))
                .hasMessageContaining("parameters[1].plannedTransactionId")
                .hasMessageContaining("두 번");
    }

    @Test
    void 상품_매출_변화는_변화율이_하나는_있어야_한다() {
        assertThatThrownBy(() -> parse(dto(b -> {
            b.type = "PRODUCT_SALES_CHANGE";
            b.productKey = "카페라떼";
        }))).hasMessageContaining("quantityRate 나 priceRate");
    }

    @Test
    void 상품_키는_정규화해서_들고_원문은_따로_남긴다() {
        List<ScenarioParameter> parsed = parse(dto(b -> {
            b.type = "PRODUCT_SALES_CHANGE";
            b.productKey = "카페 라떼";
            b.priceRate = new BigDecimal("0.10");
        }));

        // 경고 문장은 사용자가 적은 글자로 돌려줘야 읽힌다. 대조는 정규화 키로 한다.
        assertThat(parsed).singleElement()
                .isInstanceOfSatisfying(ScenarioParameter.ProductSalesChange.class, p -> {
                    assertThat(p.productKey()).isEqualTo("카페라떼");
                    assertThat(p.productName()).isEqualTo("카페 라떼");
                });
    }

    /** 11.1 ① — 판매량 −5%, 단가 +10% 면 상품 배율은 1.045 다. */
    @Test
    void 상품_배율은_판매량과_단가를_곱한_값이다() {
        ScenarioParameter.ProductSalesChange p = (ScenarioParameter.ProductSalesChange) parse(dto(b -> {
            b.type = "PRODUCT_SALES_CHANGE";
            b.productKey = "카페라떼";
            b.quantityRate = new BigDecimal("-0.05");
            b.priceRate = new BigDecimal("0.10");
        })).get(0);

        assertThat(p.productMultiplier()).isEqualByComparingTo("1.045");
    }

    /**
     * 금리를 퍼센트로 보내면 막는다.
     *
     * <p>{@code 4.5} 를 그대로 보내면 연 450% 로 계산돼 곡선이 무너진다. 공통규약 2.2 가
     * 비율로 정해 두었고 16.1·16.3 응답도 비율이다.
     */
    @Test
    void 대출_금리는_퍼센트가_아니라_비율이다() {
        assertThat(parse(loan("0.045"))).hasSize(1);
        assertThatThrownBy(() -> parse(loan("4.5")))
                .hasMessageContaining("parameters[0].interestRate")
                .hasMessageContaining("0.045");
    }

    @Test
    void 대출_상환_방식은_세_가지뿐이다() {
        assertThat(parse(dto(b -> {
            b.type = "LOAN_EXECUTION";
            b.amount = new BigDecimal("10000000");
            b.interestRate = new BigDecimal("0.045");
            b.loanPeriodDays = 365;
            b.repaymentCycle = "INTEREST_MONTHLY";
            b.disbursementDate = LocalDate.of(2026, 9, 5);
        }))).hasSize(1);

        assertThatThrownBy(() -> parse(dto(b -> {
            b.type = "LOAN_EXECUTION";
            b.amount = new BigDecimal("10000000");
            b.interestRate = new BigDecimal("0.045");
            b.loanPeriodDays = 365;
            b.repaymentCycle = "WEEKLY";
            b.disbursementDate = LocalDate.of(2026, 9, 5);
        }))).hasMessageContaining("parameters[0].repaymentCycle");
    }

    // --- helpers ------------------------------------------------------------------

    private static List<ScenarioParameter> parse(ParameterDto... dtos) {
        return ScenarioParameterParser.parse(List.of(dtos), KEY_OF);
    }

    private static BusinessException catchParse(ParameterDto... dtos) {
        try {
            parse(dtos);
        } catch (BusinessException e) {
            return e;
        }
        throw new AssertionError("예외가 나지 않았다");
    }

    private static ParameterDto delay(int days) {
        return dto(b -> {
            b.type = "SETTLEMENT_DELAY";
            b.delayDays = days;
        });
    }

    private static ParameterDto shift(long plannedTransactionId, LocalDate newDate) {
        return dto(b -> {
            b.type = "EXPENSE_DATE_SHIFT";
            b.plannedTransactionId = plannedTransactionId;
            b.newDate = newDate;
        });
    }

    private static ParameterDto loan(String interestRate) {
        return dto(b -> {
            b.type = "LOAN_EXECUTION";
            b.amount = new BigDecimal("10000000");
            b.interestRate = new BigDecimal(interestRate);
            b.loanPeriodDays = 365;
            b.repaymentCycle = "DAILY";
            b.disbursementDate = LocalDate.of(2026, 9, 5);
        });
    }

    /** 필드가 열여섯 개라 위치 인자로 쓰면 어느 자리가 무엇인지 읽을 수 없다. */
    private static ParameterDto dto(java.util.function.Consumer<Builder> fill) {
        Builder b = new Builder();
        fill.accept(b);
        return new ParameterDto(b.type, b.patternId, b.delayDays, b.rate, b.date, b.amount,
                b.title, b.plannedTransactionId, b.newDate, b.productKey, b.quantityRate,
                b.priceRate, b.interestRate, b.loanPeriodDays, b.repaymentCycle, b.disbursementDate);
    }

    private static final class Builder {
        private String type;
        private Long patternId;
        private Integer delayDays;
        private BigDecimal rate;
        private LocalDate date;
        private BigDecimal amount;
        private String title;
        private Long plannedTransactionId;
        private LocalDate newDate;
        private String productKey;
        private BigDecimal quantityRate;
        private BigDecimal priceRate;
        private BigDecimal interestRate;
        private Integer loanPeriodDays;
        private String repaymentCycle;
        private LocalDate disbursementDate;
    }
}
