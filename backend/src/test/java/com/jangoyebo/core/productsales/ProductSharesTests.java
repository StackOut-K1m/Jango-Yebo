package com.jangoyebo.core.productsales;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 구성비·증감 계산 (API 14.5).
 *
 * <p>Docker 가 필요 없다 — 순수 함수다. 예측 엔진과 같은 이유로 그렇게 뒀다: 숫자 규칙은
 * 컨테이너 없이 증명돼야 하고, 월별 리포트(15.3)와 What-if(11.1)가 같은 계산을 재사용한다.
 */
class ProductSharesTests {

    private static ProductShares.Line line(String key, String gross, String billed) {
        return new ProductShares.Line(key, key, null,
                gross == null ? null : new BigDecimal(gross), null, null, null,
                billed == null ? null : new BigDecimal(billed));
    }

    /**
     * <b>POS 화면 실측이 이 테스트의 근거다.</b> 인쇄되는 점유율은 합계(총매출 − 할인) 기준이고,
     * 총매출 기준으로 잡으면 화면과 어긋난다. 분모를 잘못 고르면 우리 숫자가 사장님이 보는
     * 인쇄물과 달라진다.
     */
    @Test
    void sharesMatchThePosPrintoutWhenBilledIsUsed() {
        List<ProductShares.Line> lines = List.of(
                line("카페라떼", "163000", "161950"),
                line("카페모카", "70000", "69650"),
                line("나머지", "457000", "438850"));   // 합계 670,450 을 맞추는 잔여

        List<ProductShares.Metrics> metrics = ProductShares.metrics(lines, List.of());

        // 161,950 / 670,450 = 24.2% — POS 화면과 같다
        assertThat(metrics.get(0).share()).isEqualByComparingTo("0.242");
        // 69,650 / 670,450 = 10.4% — 교차 검증
        assertThat(metrics.get(1).share()).isEqualByComparingTo("0.104");
    }

    /** 총매출 기준이면 화면과 어긋난다. 분모 선택이 취향이 아니라는 것을 남겨 둔다. */
    @Test
    void grossBasisWouldDisagreeWithThePrintout() {
        List<ProductShares.Line> lines = List.of(
                line("카페라떼", "163000", null),
                line("나머지", "527000", null));      // 총매출 690,000

        List<ProductShares.Metrics> metrics = ProductShares.metrics(lines, List.of());

        assertThat(metrics.get(0).share())
                .as("163,000/690,000 = 23.6% 로 화면의 24.2% 와 다르다")
                .isEqualByComparingTo("0.236");
    }

    /** 결제금액이 없는 행이 섞이면 기준을 총매출로 내린다 — 두 종류 금액을 더한 분모는 기준이 없다. */
    @Test
    void fallsBackToGrossWhenAnyRowLacksBilled() {
        assertThat(ProductShares.basisOf(List.of(line("가", "100", "90"), line("나", "200", "180"))))
                .isEqualTo(ProductShares.Basis.BILLED);
        assertThat(ProductShares.basisOf(List.of(line("가", "100", "90"), line("나", "200", null))))
                .isEqualTo(ProductShares.Basis.GROSS);
    }

    /** 58mm 인쇄물은 결제금액만 있다. 분모가 그 값으로 서야 구성비가 나온다. */
    @Test
    void worksWhenOnly58mmColumnsArePresent() {
        List<ProductShares.Metrics> metrics = ProductShares.metrics(
                List.of(line("가", null, "300"), line("나", null, "100")), List.of());

        assertThat(metrics.get(0).share()).isEqualByComparingTo("0.75");
        assertThat(metrics.get(0).grossAmount()).isNull();
    }

    /**
     * <b>없는 열을 0 으로 채우지 않는다</b>(계약 요구 3). 전부 null 일 때만 합계가 null 이다.
     */
    @Test
    void keepsMissingColumnsNullInsteadOfZero() {
        ProductShares.Line total = ProductShares.totals(
                List.of(line("가", null, "300"), line("나", null, "100")));

        assertThat(total.grossAmount()).as("총매출 0원인 상품을 만들면 안 된다").isNull();
        assertThat(total.billedAmount()).isEqualByComparingTo("400");
    }

    /** 같은 상품이 여러 기간에 걸리면 합친다. 표시 이름은 마지막에 들어온 것이다. */
    @Test
    void mergesTheSameKeyAcrossPeriods() {
        List<ProductShares.Line> merged = ProductShares.merge(List.of(
                new ProductShares.Line("카페라떼", "카페 라떼", new BigDecimal("10"),
                        null, null, null, null, new BigDecimal("100")),
                new ProductShares.Line("카페라떼", "카페라떼", new BigDecimal("5"),
                        null, null, null, null, new BigDecimal("50"))));

        assertThat(merged).hasSize(1);
        assertThat(merged.get(0).quantity()).isEqualByComparingTo("15");
        assertThat(merged.get(0).billedAmount()).isEqualByComparingTo("150");
        assertThat(merged.get(0).productName()).isEqualTo("카페라떼");
    }

    /**
     * <b>직전 기간에 등록이 없으면 증감을 말하지 않는다.</b> 비교 대상이 없는 것을
     * "전부 신규 성장"으로 말하면 첫 달 리포트가 통째로 거짓이 된다.
     */
    @Test
    void saysNothingAboutChangeWhenThereIsNoPriorPeriod() {
        List<ProductShares.Metrics> metrics =
                ProductShares.metrics(List.of(line("가", null, "100")), List.of());

        assertThat(metrics.get(0).changeRate()).isNull();
        assertThat(metrics.get(0).contributionToChange()).isNull();
    }

    /** 직전 기간은 있는데 그 상품만 없었다 — 기여도는 금액 전부고, 증감률은 0 으로 못 나눈다. */
    @Test
    void treatsANewProductAsFullContributionButNoRate() {
        List<ProductShares.Metrics> metrics = ProductShares.metrics(
                List.of(line("신상", null, "100"), line("기존", null, "100")),
                List.of(line("기존", null, "80")));

        ProductShares.Metrics fresh = metrics.get(0);
        assertThat(fresh.contributionToChange()).isEqualByComparingTo("100");
        assertThat(fresh.changeRate()).as("0 으로 나눈 값을 0 으로 표시하면 거짓말이다").isNull();

        ProductShares.Metrics existing = metrics.get(1);
        assertThat(existing.contributionToChange()).isEqualByComparingTo("20");
        assertThat(existing.changeRate()).isEqualByComparingTo("0.25");
    }

    /** 줄어든 상품은 기여도가 음수다 — "이번 달 매출이 왜 줄었나"에 답하는 지표다. */
    @Test
    void reportsNegativeContributionForShrinkingProducts() {
        List<ProductShares.Metrics> metrics = ProductShares.metrics(
                List.of(line("가", null, "86000")),
                List.of(line("가", null, "100000")));

        assertThat(metrics.get(0).contributionToChange()).isEqualByComparingTo("-14000");
        assertThat(metrics.get(0).changeRate()).isEqualByComparingTo("-0.14");
    }

    /** 할인율은 총매출이 있어야 나온다. 58mm 인쇄물에서는 null 이다. */
    @Test
    void discountRateNeedsGross() {
        ProductShares.Line withGross = new ProductShares.Line("가", "가", null,
                new BigDecimal("163000"), new BigDecimal("1050"), null, null, new BigDecimal("161950"));
        ProductShares.Line without = new ProductShares.Line("나", "나", null,
                null, new BigDecimal("1050"), null, null, new BigDecimal("100"));

        List<ProductShares.Metrics> metrics = ProductShares.metrics(List.of(withGross, without), List.of());

        assertThat(metrics.get(0).discountRate()).isEqualByComparingTo("0.006");
        assertThat(metrics.get(1).discountRate()).isNull();
    }

    /** 지수 표기를 만들지 않는다 — {@code 1E+1} 이 나가면 화면이 못 읽는다. */
    @Test
    void neverEmitsScientificNotation() {
        List<ProductShares.Metrics> metrics = ProductShares.metrics(
                List.of(line("가", null, "1000")), List.of(line("가", null, "100")));

        assertThat(metrics.get(0).changeRate().toPlainString())
                .isEqualTo(metrics.get(0).changeRate().toString());
    }
}
