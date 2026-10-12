package com.jangoyebo.forecast.engine;

import com.jangoyebo.forecast.engine.CreditGradeProjection.CurvePoint;
import com.jangoyebo.forecast.engine.LoanProductRanking.Product;
import com.jangoyebo.forecast.engine.LoanProductRanking.Ranked;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 대출 상품 추천 순위 (API 16.3, P1-14). Docker 불필요 — 순수 함수다.
 */
class LoanProductRankingTests {

    private static final LocalDate 오늘 = LocalDate.of(2026, 9, 15);
    private static final List<String> 등급 = List.of("A", "B", "C", "D", "E");

    private static BigDecimal 원(String v) {
        return new BigDecimal(v);
    }

    /**
     * 순위를 비교할 두 상품. <b>금액 범위를 겹쳐 둔다</b> — 안 겹치면 한쪽이 자격에서
     * 걸러져 「무엇으로 순위를 매기나」를 못 본다.
     */
    private static final Product 초단기 =
            new Product(1L, "E", 원("100000"), 원("10000000"), 원("19.5"), 14);
    private static final Product 일년 =
            new Product(2L, "E", 원("100000"), 원("50000000"), 원("8.4"), 365);

    /** 자격 시험용 — C 등급 이상만, 300만원부터. */
    private static final Product 상위등급 =
            new Product(3L, "C", 원("3000000"), 원("50000000"), 원("8.4"), 365);

    /** 하루 {@code 일변화} 씩 움직이는 90일 곡선. */
    private static List<CurvePoint> 곡선(String 시작, String 일변화) {
        List<CurvePoint> points = new ArrayList<>();
        BigDecimal balance = 원(시작);
        for (int i = 0; i < 90; i++) {
            points.add(new CurvePoint(오늘.plusDays(i), balance));
            balance = balance.add(원(일변화));
        }
        return points;
    }

    @Test
    void 이자만_보면_초단기가_싸다() {
        // 금융망 실측 식(P×r×d²/365)에서는 기간이 d² 로 붙어 금리보다 기간이 지배한다.
        // 금리가 2.3배인 쪽이 이자는 293분의 1이다. 금리로 매기면 안 되는 이유.
        assertThat(LoanRepaymentSchedule.interestOf(원("1000000"), 원("19.5"), 14,
                LoanRepaymentSchedule.InterestModel.FIN_NETWORK)).isEqualByComparingTo("104712");
        assertThat(LoanRepaymentSchedule.interestOf(원("1000000"), 원("8.4"), 365,
                LoanRepaymentSchedule.InterestModel.FIN_NETWORK)).isEqualByComparingTo("30660000");
    }

    @Test
    void 갚을_수_있는_쪽을_먼저_올린다() {
        // 순위는 총상환액이 아니라 「상환을 얹은 뒤 공백이 며칠인가」다.
        // 실측 식에서는 1년 대출이 매일 86,739원, 초단기가 78,908원이라 초단기가 먼저다 —
        // 플로우 문서 식으로 매겼을 때(1년 2,970원 · 초단기 71,963원)와 순위가 뒤집힌다.
        // 2026-09-16 실측 전에는 이 테스트가 반대 답을 「정답」으로 못박고 있었다.
        List<Ranked> r = LoanProductRanking.rank(
                List.of(초단기, 일년), 원("1000000"), "C",
                곡선("500000", "-20000"), BigDecimal.ZERO, 오늘, 등급);

        assertThat(r.getFirst().product().productId())
                .as("하루 상환액이 작은 초단기가 공백을 덜 만든다")
                .isEqualTo(1L);
        assertThat(r.getFirst().gapDaysAfter()).isLessThan(r.get(1).gapDaysAfter());
    }

    @Test
    void 하루_상환액을_같이_낸다() {
        // 화면이 제일 먼저 보여줘야 할 숫자다. 「이자」만 보면 초단기가 좋아 보인다.
        List<Ranked> r = LoanProductRanking.rank(
                List.of(초단기), 원("1000000"), "E", List.of(), null, 오늘, 등급);

        // 1,000,000 + 1,000,000 × 0.195 × 14² / 365 = 1,104,712 ; / 14 = 78,908 → 내림
        assertThat(r.getFirst().dailyPayment()).isEqualByComparingTo("78908");
        assertThat(r.getFirst().totalRepayment()).isEqualByComparingTo("1104712");
    }

    @Test
    void 일년_대출은_실측_식에서_공백을_늘린다() {
        // 원금 500만이 실행일에 들어오지만 매일 433,698원이 빠진다(158,300,000 / 365 — 포차 실측).
        // 13일째에 받은 돈을 다 갚고 그 뒤로는 원래 곡선보다 낮다. 「대출이 공백을 메운다」는
        // 플로우 문서 식(하루 14,849원)에서만 참이었다. 추천이 비어 나올 수 있는 이유가 이것이다.
        List<CurvePoint> curve = 곡선("500000", "-20000");   // 26일째부터 음수

        int 대출없음 = (int) curve.stream().filter(p -> p.balanceExpected().signum() < 0).count();
        List<Ranked> r = LoanProductRanking.rank(
                List.of(일년), 원("5000000"), "C", curve, BigDecimal.ZERO, 오늘, 등급);

        assertThat(대출없음).isPositive();
        assertThat(r.getFirst().gapDaysAfter())
                .as("1년 대출은 공백을 메우지 못한다 — 순위 로직이 이것을 그대로 보여 줘야 한다")
                .isGreaterThan(대출없음);
    }

    @Test
    void 짧은_대출을_공백_직전에_받으면_공백이_준다() {
        // 초단기 100만을 공백이 시작되는 날(26일째 직전)에 받으면 14일 동안은 버틴다.
        // 그 뒤 이자 104,712원만큼 낮아지지만, 창 안에서 음수인 날은 64 → 54 로 준다.
        List<CurvePoint> curve = 곡선("500000", "-20000");

        int 대출없음 = (int) curve.stream().filter(p -> p.balanceExpected().signum() < 0).count();
        List<Ranked> r = LoanProductRanking.rank(
                List.of(초단기), 원("1000000"), "C", curve, BigDecimal.ZERO, 오늘.plusDays(25), 등급);

        assertThat(r.getFirst().gapDaysAfter()).isLessThan(대출없음);
    }

    @Test
    void 자격이_없어도_목록에서_빼지_않는다() {
        // 「등급이 오르면 열리는 상품」을 보여줘야 16.2 의 등급 예측과 이어진다.
        List<Ranked> r = LoanProductRanking.rank(
                List.of(초단기, 상위등급), 원("500000"), "E", List.of(), null, 오늘, 등급);

        assertThat(r).hasSize(2);
        // E 등급은 C 상품을 못 쓴다. 자격 있는 것이 앞에 온다.
        assertThat(r.getFirst().product().productId()).isEqualTo(1L);
        assertThat(r.getFirst().eligible()).isTrue();
        assertThat(r.get(1).eligible()).isFalse();
        assertThat(r.get(1).note()).contains("C 등급 이상");
    }

    @Test
    void 금액이_범위를_벗어나면_이유를_말한다() {
        List<Ranked> r = LoanProductRanking.rank(
                List.of(초단기), 원("50000000"), "E", List.of(), null, 오늘, 등급);

        assertThat(r.getFirst().eligible()).isFalse();
        assertThat(r.getFirst().note()).contains("최대 10000000원");
    }

    @Test
    void 등급은_이상이면_된다() {
        // A 등급은 C 상품도 쓸 수 있다. 정확히 그 등급이어야 하는 게 아니다.
        assertThat(LoanProductRanking.ineligibilityNote(상위등급, 원("5000000"), "A", 등급)).isNull();
        assertThat(LoanProductRanking.ineligibilityNote(상위등급, 원("5000000"), "C", 등급)).isNull();
        assertThat(LoanProductRanking.ineligibilityNote(상위등급, 원("5000000"), "D", 등급))
                .contains("C 등급 이상");
    }

    @Test
    void 곡선이_없으면_공백을_세지_않는다() {
        // 없는 것을 지어내지 않는다. 순위는 총상환액으로 갈린다.
        List<Ranked> r = LoanProductRanking.rank(
                List.of(초단기, 일년), 원("1000000"), "C", List.of(), null, 오늘, 등급);

        assertThat(r).allSatisfy(x -> assertThat(x.gapDaysAfter()).isZero());
        assertThat(r).allSatisfy(x -> assertThat(x.worstBalanceAfter()).isNull());
    }
}
