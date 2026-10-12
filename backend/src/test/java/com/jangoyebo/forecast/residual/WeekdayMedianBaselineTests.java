package com.jangoyebo.forecast.residual;

import com.jangoyebo.forecast.engine.BalanceCurveCalculator.ResidualQuantiles;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 통계 베이스라인 (S15P21C105-137). Docker 불필요.
 *
 * <p><b>참조 구현({@code ai/app/forecast/baseline.py})과 숫자가 갈리면 안 된다.</b> 모델과
 * 통계를 비교한 게이트가 이 값을 기준선으로 삼았다 — 여기서 다른 값이 나오면 「모델이
 * 통계보다 낫다」는 판정이 우리 코드에서는 성립하지 않는다.
 */
class WeekdayMedianBaselineTests {

    /** 2026-06-01 은 월요일이다. 요일 묶음을 셈하기 쉽게 여기서 시작한다. */
    private static final LocalDate MONDAY = LocalDate.of(2026, 6, 1);

    @Test
    void 표본이_하나면_그_값이다() {
        assertThat(WeekdayMedianBaseline.percentile(values("100"), new BigDecimal("0.50")))
                .isEqualByComparingTo("100");
    }

    @Test
    void 표본이_없으면_영이다() {
        // 그 요일에 거래가 한 번도 없었다는 뜻이고, 잔여 정의상 0 이 맞다.
        assertThat(WeekdayMedianBaseline.percentile(List.of(), new BigDecimal("0.50")))
                .isEqualByComparingTo("0");
    }

    @Test
    void 위치가_맞아떨어지면_원래_값을_쓴다() {
        // k = (n−1)·p 가 정수면 보간하지 않는다. 반올림도 하지 않는다.
        List<BigDecimal> v = values("10", "20", "30");
        assertThat(WeekdayMedianBaseline.percentile(v, new BigDecimal("0.50"))).isEqualByComparingTo("20");
        assertThat(WeekdayMedianBaseline.percentile(v, new BigDecimal("0.00"))).isEqualByComparingTo("10");
        assertThat(WeekdayMedianBaseline.percentile(v, new BigDecimal("1.00"))).isEqualByComparingTo("30");
    }

    @Test
    void 위치가_사이에_있으면_선형_보간한다() {
        // n=4, p=0.5 → k=1.5 → (20 + 30)/2 = 25
        assertThat(WeekdayMedianBaseline.percentile(values("10", "20", "30", "40"), new BigDecimal("0.50")))
                .isEqualByComparingTo("25");
        // n=4, p=0.10 → k=0.3 → 10·0.7 + 20·0.3 = 13
        assertThat(WeekdayMedianBaseline.percentile(values("10", "20", "30", "40"), new BigDecimal("0.10")))
                .isEqualByComparingTo("13");
        // n=4, p=0.90 → k=2.7 → 30·0.3 + 40·0.7 = 37
        assertThat(WeekdayMedianBaseline.percentile(values("10", "20", "30", "40"), new BigDecimal("0.90")))
                .isEqualByComparingTo("37");
    }

    @Test
    void 보간할_때만_정수_원으로_반올림한다() {
        // n=2, p=0.5 → k=0.5 → (11 + 12)/2 = 11.5 → 12 (HALF_UP)
        assertThat(WeekdayMedianBaseline.percentile(values("11", "12"), new BigDecimal("0.50")))
                .isEqualByComparingTo("12");
    }

    @Test
    void 표본이_삼십일_미만이면_아무것도_안_낸다() {
        // 근거 없는 숫자를 내느니 「모른다」를 내는 편이 낫다. 곡선은 예정 거래만으로도 나온다.
        ResidualSeries series = flatSeries(29, "1000");

        assertThat(WeekdayMedianBaseline.forecast(series, MONDAY.plusDays(29), 30)).isNull();
    }

    @Test
    void 삼십일이면_값을_낸다() {
        ResidualQuantiles q = WeekdayMedianBaseline.forecast(flatSeries(30, "1000"), MONDAY.plusDays(30), 30);

        assertThat(q).isNotNull();
        assertThat(q.deposit()).hasSize(30);
        assertThat(q.withdrawal()).hasSize(30);
    }

    @Test
    void 요일마다_다른_값을_낸다() {
        // 카드 정산이 요일마다 금액이 다르고 주말에 멈추는 것을 담는 것이 이 방식의 요점이다.
        List<BigDecimal> deposit = new ArrayList<>();
        for (int i = 0; i < 70; i++) {
            DayOfWeek day = MONDAY.plusDays(i).getDayOfWeek();
            boolean weekend = day == DayOfWeek.SATURDAY || day == DayOfWeek.SUNDAY;
            deposit.add(weekend ? BigDecimal.ZERO : new BigDecimal("500000"));
        }
        ResidualSeries series = new ResidualSeries(MONDAY, deposit, zeros(70));

        LocalDate nextMonday = MONDAY.plusDays(70);
        ResidualQuantiles q = WeekdayMedianBaseline.forecast(series, nextMonday, 7);

        assertThat(q.deposit().get(0).q50()).isEqualByComparingTo("500000");   // 월
        assertThat(q.deposit().get(5).q50()).isEqualByComparingTo("0");        // 토
        assertThat(q.deposit().get(6).q50()).isEqualByComparingTo("0");        // 일
    }

    @Test
    void 분위수_순서가_뒤집히지_않는다() {
        // 곡선의 min ≤ expected ≤ max 가 여기에 기댄다.
        List<BigDecimal> deposit = new ArrayList<>();
        for (int i = 0; i < 90; i++) {
            deposit.add(new BigDecimal(String.valueOf((i * 37) % 500000)));
        }
        ResidualQuantiles q = WeekdayMedianBaseline.forecast(
                new ResidualSeries(MONDAY, deposit, zeros(90)), MONDAY.plusDays(90), 30);

        assertThat(q.deposit()).allSatisfy(v -> {
            assertThat(v.q10()).isLessThanOrEqualTo(v.q50());
            assertThat(v.q50()).isLessThanOrEqualTo(v.q90());
            assertThat(v.q10()).isGreaterThanOrEqualTo(BigDecimal.ZERO);
        });
    }

    @Test
    void 예보는_주어진_첫날부터_시작한다() {
        // 달력이 하루 밀리면 요일이 통째로 어긋난다.
        LocalDate from = MONDAY.plusDays(60);
        ResidualQuantiles q = WeekdayMedianBaseline.forecast(flatSeries(60, "1000"), from, 14);

        assertThat(q.startDate()).isEqualTo(from);
        assertThat(q.deposit()).hasSize(14);
    }

    @Test
    void 같은_입력은_항상_같은_결과다() {
        ResidualSeries series = flatSeries(60, "1000");
        LocalDate from = MONDAY.plusDays(60);

        assertThat(WeekdayMedianBaseline.forecast(series, from, 30))
                .isEqualTo(WeekdayMedianBaseline.forecast(series, from, 30));
    }

    // --- 헬퍼 -----------------------------------------------------------------

    private static List<BigDecimal> values(String... raw) {
        List<BigDecimal> out = new ArrayList<>(raw.length);
        for (String r : raw) {
            out.add(new BigDecimal(r));
        }
        return out;
    }

    private static List<BigDecimal> zeros(int n) {
        List<BigDecimal> out = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            out.add(BigDecimal.ZERO);
        }
        return out;
    }

    private static ResidualSeries flatSeries(int days, String amount) {
        List<BigDecimal> deposit = new ArrayList<>(days);
        for (int i = 0; i < days; i++) {
            deposit.add(new BigDecimal(amount));
        }
        return new ResidualSeries(MONDAY, deposit, zeros(days));
    }
}
