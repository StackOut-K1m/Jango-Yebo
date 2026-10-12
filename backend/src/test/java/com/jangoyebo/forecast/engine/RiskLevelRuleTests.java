package com.jangoyebo.forecast.engine;

import com.jangoyebo.common.domain.RiskLevel;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 위험 등급 판정 (API 2.6). Docker 불필요.
 */
class RiskLevelRuleTests {

    private static final BigDecimal SAFETY_LINE = new BigDecimal("2000000");

    @Test
    void 안전선_이상이면_안전이다() {
        assertThat(RiskLevelRule.of(new BigDecimal("2000000"), SAFETY_LINE)).isEqualTo(RiskLevel.SAFE);
        assertThat(RiskLevelRule.of(new BigDecimal("5000000"), SAFETY_LINE)).isEqualTo(RiskLevel.SAFE);
    }

    @Test
    void 안전선_미만이면_주의다() {
        assertThat(RiskLevelRule.of(new BigDecimal("1999999"), SAFETY_LINE)).isEqualTo(RiskLevel.CAUTION);
        assertThat(RiskLevelRule.of(new BigDecimal("500000"), SAFETY_LINE)).isEqualTo(RiskLevel.CAUTION);
    }

    @Test
    void 안전선의_사분의_일_미만이면_위험이다() {
        assertThat(RiskLevelRule.of(new BigDecimal("500000"), SAFETY_LINE)).isEqualTo(RiskLevel.CAUTION);
        assertThat(RiskLevelRule.of(new BigDecimal("499999"), SAFETY_LINE)).isEqualTo(RiskLevel.DANGER);
        assertThat(RiskLevelRule.of(BigDecimal.ZERO, SAFETY_LINE)).isEqualTo(RiskLevel.DANGER);
    }

    @Test
    void 영원_미만이면_공백이다() {
        assertThat(RiskLevelRule.of(new BigDecimal("-1"), SAFETY_LINE)).isEqualTo(RiskLevel.GAP);
        assertThat(RiskLevelRule.of(new BigDecimal("-420000"), SAFETY_LINE)).isEqualTo(RiskLevel.GAP);
    }

    @Test
    void 안전선이_없으면_영원_기준만_판정한다() {
        // 미설정은 오류가 아니라 정상 상태다. 비교선이 없는데 CAUTION 을 붙이면 근거 없는 경보다.
        assertThat(RiskLevelRule.of(new BigDecimal("1"), null)).isEqualTo(RiskLevel.SAFE);
        assertThat(RiskLevelRule.of(BigDecimal.ZERO, null)).isEqualTo(RiskLevel.SAFE);
        assertThat(RiskLevelRule.of(new BigDecimal("-1"), null)).isEqualTo(RiskLevel.GAP);
    }

    @Test
    void 안전선이_영이면_영원_기준과_같아진다() {
        // 0 과 「설정 안 함」은 다르지만(SafetyBalancePolicy) 판정 결과는 같다.
        assertThat(RiskLevelRule.of(BigDecimal.ZERO, BigDecimal.ZERO)).isEqualTo(RiskLevel.SAFE);
        assertThat(RiskLevelRule.of(new BigDecimal("-1"), BigDecimal.ZERO)).isEqualTo(RiskLevel.GAP);
    }

    @Test
    void 소수점이_있어도_판정이_흔들리지_않는다() {
        // DB 는 NUMERIC(19,4) 라 소수 네 자리가 온다.
        assertThat(RiskLevelRule.of(new BigDecimal("2000000.0000"), SAFETY_LINE)).isEqualTo(RiskLevel.SAFE);
        assertThat(RiskLevelRule.of(new BigDecimal("-0.0001"), SAFETY_LINE)).isEqualTo(RiskLevel.GAP);
    }
}
