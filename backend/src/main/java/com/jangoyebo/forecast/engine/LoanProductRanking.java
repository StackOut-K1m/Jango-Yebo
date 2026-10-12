package com.jangoyebo.forecast.engine;

import com.jangoyebo.forecast.engine.CreditGradeProjection.CurvePoint;
import com.jangoyebo.forecast.engine.LoanRepaymentSchedule.Installment;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * 대출 상품 추천 순위 (API 16.3, P1-14). <b>순수 함수다.</b>
 *
 * <h2>순위는 「갚을 수 있는가」가 먼저다</h2>
 *
 * <p>금리로 매기면 안 된다는 것은 쉽게 보인다 — 금융망 이자는 <b>단리 일할</b>이라 기간이
 * 짧으면 금리가 높아도 이자가 적다.
 *
 * <pre>
 *   100만 · 14일 · 19.5%   →  이자    7,479원
 *   100만 · 365일 · 8.4%   →  이자   84,000원   (11배)
 * </pre>
 *
 * <p><b>그런데 총상환액으로 매겨도 틀린다.</b> 같은 두 상품의 하루 상환액은
 *
 * <pre>
 *   14일짜리   매일  71,963원
 *   365일짜리  매일   2,970원   (24분의 1)
 * </pre>
 *
 * <p>공백이 난 사장님에게 <b>매일 7만원을 갚으라는 것은 답이 아니다.</b> 이자가 11배 비싸도
 * 365일짜리가 맞다. 그래서 1순위는 <b>상환을 얹은 뒤에도 공백이 남는가</b>이고, 총상환액은
 * 그다음이다. 금리는 정렬 기준이 아니라 화면에 적는 값이다.
 *
 * <pre>
 *   1. 상환 반영 후 공백 일수    적을수록
 *   2. 상환 반영 후 최저 잔액    높을수록
 *   3. 총상환액                 적을수록
 *   4. 금리                     같을 때만
 * </pre>
 *
 * <h2>대출이 곡선을 어떻게 바꾸나</h2>
 *
 * <p>실행일에 원금이 들어오고, <b>다음날부터 매일</b> {@code (원금+이자)/기간} 이 빠진다
 * ({@link LoanRepaymentSchedule}). 그래서 곡선은 하루 솟았다가 완만히 내려온다 — 공백을
 * 메우지만 <b>다음 공백을 만들 수도 있다</b>.
 */
public final class LoanProductRanking {

    private LoanProductRanking() {
    }

    /**
     * 후보 상품 하나. {@code core} 의 상품 행에서 필요한 값만 옮겨 담는다.
     *
     * @param minGrade 최소 가입 등급. 그 등급 <b>이상</b>이면 가입할 수 있다
     */
    public record Product(Long productId, String minGrade,
                          BigDecimal minLoanBalance, BigDecimal maxLoanBalance,
                          BigDecimal interestRatePercent, int loanPeriodDays) {
    }

    /**
     * 매긴 결과.
     *
     * @param totalInterest    단리 일할 이자
     * @param totalRepayment   원금 + 이자
     * @param dailyPayment     하루 상환액. <b>화면이 제일 먼저 봐야 할 숫자다</b>
     * @param gapDaysAfter     상환을 얹은 뒤 잔액이 안전선 아래인 날 수
     * @param worstBalanceAfter 상환을 얹은 뒤 지평 안 최저 잔액
     * @param eligible         지금 신청할 수 있는가(등급·금액)
     * @param note             못 하는 이유. 할 수 있으면 {@code null}
     */
    public record Ranked(Product product,
                         BigDecimal totalInterest,
                         BigDecimal totalRepayment,
                         BigDecimal dailyPayment,
                         int gapDaysAfter,
                         BigDecimal worstBalanceAfter,
                         boolean eligible,
                         String note) {
    }

    /**
     * 자격을 보고 순위를 매긴다.
     *
     * <p><b>자격이 없는 상품도 돌려준다.</b> 화면이 「등급이 오르면 열리는 상품」을 보여줘야
     * 하고, 그것이 등급 예측(16.2)과 이어지는 지점이다 — 걸러 버리면 그 이야기를 할 수 없다.
     * 자격 있는 것이 앞에 오고, 그 안에서 위 순서로 정렬한다.
     *
     * @param curve       상환 전 곡선(오름차순). 비어 있으면 공백 항목이 0 으로 채워진다
     * @param safetyLine  안전선. {@code null} 이면 0 원
     * @param executeOn   실행일 — 원금이 들어오는 날. 보통 오늘
     * @param grades      등급 순서. 자산이 큰 등급부터(A, B, C, D, E)
     */
    public static List<Ranked> rank(List<Product> products,
                                    BigDecimal amount,
                                    String currentGrade,
                                    List<CurvePoint> curve,
                                    BigDecimal safetyLine,
                                    LocalDate executeOn,
                                    List<String> grades) {
        BigDecimal line = safetyLine == null ? BigDecimal.ZERO : safetyLine;
        List<Ranked> ranked = new ArrayList<>(products.size());

        for (Product product : products) {
            String note = ineligibilityNote(product, amount, currentGrade, grades);
            // 금융망이 실제로 청구하는 식(FIN_NETWORK)이다. 플로우 문서 식으로 매기면 하루 상환액이
            // 기간(일)배 작게 나와 「갚을 수 있는가」 판정이 뒤집힌다 — 포차 500만·365일이 14,849원으로
            // 보였는데 실물은 433,698원이었다(2026-09-16 실측).
            BigDecimal interest = LoanRepaymentSchedule.interestOf(
                    amount, product.interestRatePercent(), product.loanPeriodDays(),
                    LoanRepaymentSchedule.InterestModel.FIN_NETWORK);
            BigDecimal total = amount.add(interest);
            BigDecimal daily = product.loanPeriodDays() < 1 ? BigDecimal.ZERO
                    : total.divide(BigDecimal.valueOf(product.loanPeriodDays()), 0,
                            java.math.RoundingMode.DOWN);

            After after = simulate(curve, amount, total, product.loanPeriodDays(), executeOn, line);
            ranked.add(new Ranked(product, interest, total, daily,
                    after.gapDays(), after.worst(), note == null, note));
        }

        ranked.sort(Comparator
                .comparing(Ranked::eligible).reversed()
                .thenComparingInt(Ranked::gapDaysAfter)
                // 곡선이 없으면 worst 가 null 이다 — 그때는 이 기준을 건너뛴다.
                .thenComparing(Ranked::worstBalanceAfter,
                        Comparator.nullsLast(Comparator.reverseOrder()))
                .thenComparing(Ranked::totalRepayment)
                .thenComparing(r -> r.product().interestRatePercent()));
        return List.copyOf(ranked);
    }

    /**
     * 상환을 얹은 곡선을 그려 공백을 센다.
     *
     * <p>곡선 자체를 돌려주지 않는 이유는 <b>미리 보기는 시나리오 API 의 몫</b>이기 때문이다
     * (명세 16.3 — {@code LOAN_EXECUTION}). 여기서는 순위를 매길 두 숫자만 낸다.
     */
    private static After simulate(List<CurvePoint> curve, BigDecimal amount, BigDecimal total,
                                  int periodDays, LocalDate executeOn, BigDecimal safetyLine) {
        if (curve == null || curve.isEmpty() || periodDays < 1) {
            return new After(0, null);
        }

        List<Installment> schedule = LoanRepaymentSchedule.dailyFromTotal(executeOn, total, periodDays);

        int gapDays = 0;
        BigDecimal worst = null;
        int next = 0;
        BigDecimal paid = BigDecimal.ZERO;

        for (CurvePoint point : curve) {
            // 그날까지 빠져나간 것을 누적한다. **다 갚은 뒤에도 누적은 그대로 남는다** —
            // 날짜별 지도에서 꺼내면 상환이 끝난 다음날 0 으로 되돌아가, 갚은 돈을
            // 돌려받은 것처럼 곡선이 솟는다. 기간이 짧은 상품일수록 크게 틀린다.
            while (next < schedule.size() && !schedule.get(next).dueDate().isAfter(point.date())) {
                paid = paid.add(schedule.get(next).amount());
                next++;
            }
            // 실행일부터 원금이 들어와 있다. 그 전날까지는 원래 곡선 그대로다.
            BigDecimal balance = point.balanceExpected();
            if (!point.date().isBefore(executeOn)) {
                balance = balance.add(amount).subtract(paid);
            }
            if (balance.compareTo(safetyLine) < 0) {
                gapDays++;
            }
            if (worst == null || balance.compareTo(worst) < 0) {
                worst = balance;
            }
        }
        return new After(gapDays, worst);
    }

    /**
     * 지금 신청할 수 없는 이유. 신청할 수 있으면 {@code null}.
     *
     * <p>등급은 <b>이상</b>이면 된다 — 자산이 큰 등급부터 정렬된 목록에서 내 등급의 자리가
     * 상품 최소 등급의 자리보다 앞이거나 같으면 자격이 있다.
     */
    static String ineligibilityNote(Product product, BigDecimal amount,
                                    String currentGrade, List<String> grades) {
        int mine = grades.indexOf(currentGrade);
        int required = grades.indexOf(product.minGrade());
        if (mine < 0 || required < 0) {
            return "등급을 확인할 수 없습니다.";
        }
        if (mine > required) {
            return "%s 등급 이상만 신청할 수 있습니다.".formatted(product.minGrade());
        }
        if (amount.compareTo(product.minLoanBalance()) < 0) {
            return "최소 %s원부터 신청할 수 있습니다.".formatted(product.minLoanBalance().toBigInteger());
        }
        if (amount.compareTo(product.maxLoanBalance()) > 0) {
            return "최대 %s원까지 신청할 수 있습니다.".formatted(product.maxLoanBalance().toBigInteger());
        }
        return null;
    }

    private record After(int gapDays, BigDecimal worst) {
    }
}
