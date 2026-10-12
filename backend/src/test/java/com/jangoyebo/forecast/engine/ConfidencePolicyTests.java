package com.jangoyebo.forecast.engine;

import com.jangoyebo.forecast.pattern.ConfidenceBand;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 예측 신뢰도 산정 (공통규약 2.5·23장). Docker 불필요.
 *
 * <p>공식이 명세에 없어 2026-09-10 에 정했다. 상수를 바꾸면 여기가 먼저 깨진다.
 */
class ConfidencePolicyTests {

    @Test
    void 첫날은_감쇠가_없고_마지막날은_삼십퍼센트_준다() {
        assertThat(ConfidencePolicy.horizonFactor(1, 30)).isEqualByComparingTo("1");
        assertThat(ConfidencePolicy.horizonFactor(30, 30)).isEqualByComparingTo("0.70");
    }

    @Test
    void 하루짜리_예측은_감쇠하지_않는다() {
        assertThat(ConfidencePolicy.horizonFactor(1, 1)).isEqualByComparingTo("1");
    }

    @Test
    void 잔여_출처가_낮을수록_신뢰도가_낮다() {
        BigDecimal model = ConfidencePolicy.daily(1, 30, BigDecimal.ONE, ResidualSource.MODEL, 365);
        BigDecimal baseline = ConfidencePolicy.daily(1, 30, BigDecimal.ONE, ResidualSource.BASELINE, 365);
        BigDecimal none = ConfidencePolicy.daily(1, 30, BigDecimal.ONE, ResidualSource.NONE, 365);

        assertThat(model).isGreaterThan(baseline);
        assertThat(baseline).isGreaterThan(none);
    }

    @Test
    void 과거가_백팔십일_미만이면_한단계_강등한다() {
        BigDecimal full = ConfidencePolicy.daily(1, 30, BigDecimal.ONE, ResidualSource.MODEL, 200);
        BigDecimal short_ = ConfidencePolicy.daily(1, 30, BigDecimal.ONE, ResidualSource.MODEL, 60);

        assertThat(full).isEqualByComparingTo("1.0000");
        assertThat(short_).isEqualByComparingTo("0.8500");
    }

    @Test
    void 잔여를_안_쓰면_표본_일수는_보지_않는다() {
        // NONE 은 잔여를 아예 안 만든 상태라 표본이 짧아서 나쁜 것이 아니다.
        BigDecimal a = ConfidencePolicy.daily(1, 30, BigDecimal.ONE, ResidualSource.NONE, 10);
        BigDecimal b = ConfidencePolicy.daily(1, 30, BigDecimal.ONE, ResidualSource.NONE, 365);

        assertThat(a).isEqualByComparingTo(b);
    }

    @Test
    void 예정거래_신뢰도가_곱해진다() {
        BigDecimal value = ConfidencePolicy.daily(1, 30, new BigDecimal("0.50"), ResidualSource.MODEL, 365);

        assertThat(value).isEqualByComparingTo("0.5000");
    }

    @Test
    void 일별_값은_영과_일_사이다() {
        assertThat(ConfidencePolicy.daily(1, 30, new BigDecimal("5"), ResidualSource.MODEL, 365))
                .isEqualByComparingTo("1.0000");
        assertThat(ConfidencePolicy.daily(1, 30, new BigDecimal("-1"), ResidualSource.MODEL, 365))
                .isEqualByComparingTo("0.0000");
    }

    @Test
    void 일별_값은_소수_네자리다() {
        // DB 가 NUMERIC(5,4) 다. 자릿수가 넘으면 INSERT 가 반올림하거나 거부한다.
        assertThat(ConfidencePolicy.daily(17, 30, new BigDecimal("0.81"), ResidualSource.BASELINE, 100).scale())
                .isEqualTo(4);
    }

    @Test
    void 등급은_창_평균으로_정한다() {
        assertThat(ConfidencePolicy.level(flat("0.90", 30), ResidualSource.MODEL, 5, true))
                .isEqualTo(ConfidenceBand.HIGH);
        assertThat(ConfidencePolicy.level(flat("0.65", 30), ResidualSource.MODEL, 5, true))
                .isEqualTo(ConfidenceBand.MEDIUM);
        assertThat(ConfidencePolicy.level(flat("0.45", 30), ResidualSource.MODEL, 5, true))
                .isEqualTo(ConfidenceBand.LOW);
        assertThat(ConfidencePolicy.level(flat("0.20", 30), ResidualSource.MODEL, 5, true))
                .isEqualTo(ConfidenceBand.UNPREDICTABLE);
    }

    @Test
    void 짧은_창이_긴_창보다_높게_나올_수_있다() {
        // 기간 계수가 뒤로 갈수록 떨어지므로 7일 조회가 실제로 더 믿을 만하다. 의도된 동작이다.
        List<BigDecimal> thirty = window(30, ResidualSource.NONE, 0);
        List<BigDecimal> seven = thirty.subList(0, 7);

        BigDecimal meanThirty = mean(thirty);
        BigDecimal meanSeven = mean(seven);

        assertThat(meanSeven).isGreaterThan(meanThirty);
    }

    @Test
    void 예정거래도_잔여도_없으면_예측할_수_없다() {
        // 곡선이 수평선이다. 「값이 없다」가 아니라 「근거가 없다」.
        assertThat(ConfidencePolicy.level(flat("0.99", 30), ResidualSource.NONE, 0, true))
                .isEqualTo(ConfidenceBand.UNPREDICTABLE);
    }

    @Test
    void 예정거래가_없어도_잔여가_있으면_예측한다() {
        assertThat(ConfidencePolicy.level(flat("0.90", 30), ResidualSource.MODEL, 0, true))
                .isEqualTo(ConfidenceBand.HIGH);
    }

    @Test
    void 잔액을_모르는_계좌가_있으면_한단계_내린다() {
        assertThat(ConfidencePolicy.level(flat("0.90", 30), ResidualSource.MODEL, 5, false))
                .isEqualTo(ConfidenceBand.MEDIUM);
    }

    @Test
    void 강등은_명세에_있는_네_값_안에서만_움직인다() {
        assertThat(ConfidencePolicy.degrade(ConfidenceBand.HIGH)).isEqualTo(ConfidenceBand.MEDIUM);
        assertThat(ConfidencePolicy.degrade(ConfidenceBand.MEDIUM)).isEqualTo(ConfidenceBand.LOW);
        assertThat(ConfidencePolicy.degrade(ConfidenceBand.LOW)).isEqualTo(ConfidenceBand.UNPREDICTABLE);
        assertThat(ConfidencePolicy.degrade(ConfidenceBand.UNPREDICTABLE)).isEqualTo(ConfidenceBand.UNPREDICTABLE);
    }

    // --- 헬퍼 -----------------------------------------------------------------

    private static List<BigDecimal> flat(String value, int days) {
        List<BigDecimal> list = new ArrayList<>();
        for (int i = 0; i < days; i++) {
            list.add(new BigDecimal(value));
        }
        return list;
    }

    private static List<BigDecimal> window(int days, ResidualSource source, int historyDays) {
        List<BigDecimal> list = new ArrayList<>();
        for (int i = 1; i <= days; i++) {
            list.add(ConfidencePolicy.daily(i, days, BigDecimal.ONE, source, historyDays));
        }
        return list;
    }

    private static BigDecimal mean(List<BigDecimal> values) {
        BigDecimal sum = BigDecimal.ZERO;
        for (BigDecimal v : values) {
            sum = sum.add(v);
        }
        return sum.divide(BigDecimal.valueOf(values.size()), java.math.MathContext.DECIMAL64);
    }
}
