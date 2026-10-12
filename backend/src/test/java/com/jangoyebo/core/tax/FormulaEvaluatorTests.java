package com.jangoyebo.core.tax;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 서식 산식 평가기.
 *
 * <p>Docker 가 필요 없다 — 컨테이너를 띄우지 않는 순수 계산이다.
 */
class FormulaEvaluatorTests {

    private Map<String, BigDecimal> values(Object... pairs) {
        Map<String, BigDecimal> map = new LinkedHashMap<>();
        for (int i = 0; i < pairs.length; i += 2) {
            map.put((String) pairs[i],
                    pairs[i + 1] == null ? null : new BigDecimal(pairs[i + 1].toString()));
        }
        return map;
    }

    @Test
    void addsAndSubtractsInOrder() {
        assertThat(FormulaEvaluator.evaluate("A + B - C",
                values("A", 100, "B", 30, "C", 50)))
                .isEqualByComparingTo("80");
    }

    @Test
    void readsASingleIdentifier() {
        assertThat(FormulaEvaluator.evaluate("TOTAL", values("TOTAL", 4200)))
                .isEqualByComparingTo("4200");
    }

    /** 사용자가 아직 안 적은 {@code USER_CONFIRM} 항목은 0 으로 본다 — 세무조정 기본값이 그렇다. */
    @Test
    void treatsNullAsZero() {
        assertThat(FormulaEvaluator.evaluate("A - B",
                values("A", 100, "B", null)))
                .isEqualByComparingTo("100");
    }

    /**
     * <b>아직 계산되지 않은 항목을 참조하면 던진다.</b>
     *
     * <p>0 으로 넘어가면 소득금액이 조용히 달라진다. 서식 정의에서 섹션 순서가 어긋났을 때
     * 이 예외가 그것을 드러내는 유일한 신호다.
     */
    @Test
    void refusesToGuessAnUncomputedField() {
        assertThatThrownBy(() -> FormulaEvaluator.evaluate("A + MISSING", values("A", 10)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("MISSING")
                .hasMessageContaining("섹션 순서");
    }

    /**
     * 문법을 좁게 잡았다. 신고서 숫자를 만드는 자리에 임의 식이 들어오면 안 된다.
     */
    @Test
    void refusesAnythingOutsideTheGrammar() {
        assertThat(java.util.List.of("A * B", "A / B", "(A + B)", "A + 100", "a + b", ""))
                .allSatisfy(formula ->
                        assertThatThrownBy(() -> FormulaEvaluator.evaluate(formula, values("A", 1, "B", 2)))
                                .as("허용되면 안 되는 산식: %s", formula)
                                .isInstanceOf(IllegalArgumentException.class));
    }
}
