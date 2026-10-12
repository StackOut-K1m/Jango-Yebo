package com.jangoyebo.forecast.loan;

import com.jangoyebo.common.error.BusinessException;
import com.jangoyebo.common.error.ErrorCode;
import com.jangoyebo.common.response.ApiResponse;
import com.jangoyebo.common.tenancy.CurrentBusiness;
import com.jangoyebo.core.CreditAssets;
import com.jangoyebo.core.CreditAssets.GradeCriterion;
import com.jangoyebo.core.LoanProducts;
import com.jangoyebo.core.LoanProducts.RecommendableProduct;
import com.jangoyebo.core.SafetyLines;
import com.jangoyebo.forecast.engine.CreditGradeProjection.CurvePoint;
import com.jangoyebo.forecast.engine.LoanProductRanking;
import com.jangoyebo.forecast.engine.LoanProductRanking.Product;
import com.jangoyebo.forecast.engine.LoanProductRanking.Ranked;
import com.jangoyebo.forecast.loan.LoanProductDtos.ProductItem;
import com.jangoyebo.forecast.loan.LoanProductDtos.Recommendation;
import com.jangoyebo.forecast.loan.LoanProductDtos.RecommendationResponse;
import com.jangoyebo.forecast.run.CreditCurveReader;
import com.jangoyebo.forecast.run.CreditCurveReader.CurveWindow;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 대출 상품 추천 (API 16.3, P1-14).
 *
 * <p><b>조회·추천까지만 한다.</b> 명세가 「실행 API 는 정의하지 않는다」고 못박았고,
 * 도메인 규칙 5 가 실행성 액션을 사용자 명시 승인 뒤로 미룬다. 실행 미리 보기는
 * 시나리오 API 의 {@code LOAN_EXECUTION} 이 맡는다.
 *
 * <p>순위는 {@link LoanProductRanking} 이 매긴다 — <b>금리가 아니라 「갚을 수 있는가」가
 * 먼저다.</b> 그 이유는 그 클래스에 적어 뒀다.
 */
@RestController
@RequestMapping("/api/v1")
public class LoanProductController {

    private static final ZoneId KST = ZoneId.of("Asia/Seoul");

    /** 순위를 매길 때 보는 곡선 길이. 16.2 와 같은 값이라 두 화면이 같은 미래를 본다. */
    private static final int REFERENCE_CURVE_DAYS = 60;

    private static final String DISCLAIMER =
            "추천은 참고용이며, 실제 대출 실행은 해당 금융기관에서 직접 진행해야 합니다.";

    private final LoanProducts products;
    private final CreditAssets creditAssets;
    private final CreditCurveReader curves;
    private final SafetyLines safetyLines;
    private final Clock clock;

    public LoanProductController(LoanProducts products, CreditAssets creditAssets,
                                 CreditCurveReader curves, SafetyLines safetyLines, Clock clock) {
        this.products = products;
        this.creditAssets = creditAssets;
        this.curves = curves;
        this.safetyLines = safetyLines;
        this.clock = clock;
    }

    @GetMapping("/businesses/{businessId}/loan-products")
    @Transactional
    public ApiResponse<RecommendationResponse> recommend(@CurrentBusiness Long businessId,
                                                         @RequestParam(required = false)
                                                         BigDecimal amount) {
        // required = true 로 두면 누락이 MissingServletRequestParameterException 이 되는데,
        // 전역 핸들러가 그 타입을 안 잡아 500 으로 나간다(다른 엔드포인트도 같다).
        // 여기서 받아 우리 오류로 바꾼다 — 클라이언트 잘못을 서버 오류로 보고하지 않는다.
        if (amount == null) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "amount 가 필요합니다.");
        }
        if (amount.signum() <= 0) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "amount 는 0보다 커야 합니다.");
        }

        String currentGrade = creditAssets.current(businessId).grade();
        List<String> grades = creditAssets.criteria().stream().map(GradeCriterion::grade).toList();

        List<RecommendableProduct> candidates = products.recommendable();
        Map<Long, RecommendableProduct> byId = new HashMap<>();
        for (RecommendableProduct p : candidates) {
            byId.put(p.productId(), p);
        }

        CurveWindow window = curves.forCredit(businessId, REFERENCE_CURVE_DAYS);
        List<CurvePoint> curve = window == null ? List.of() : window.points();
        BigDecimal safetyLine = safetyLines.currentAmount(businessId).orElse(null);
        LocalDate today = LocalDate.now(clock.withZone(KST));

        List<Ranked> ranked = LoanProductRanking.rank(
                candidates.stream().map(LoanProductController::toRankingProduct).toList(),
                amount, currentGrade, curve, safetyLine, today, grades);

        List<ProductItem> items = ranked.stream()
                .map(r -> toItem(r, byId.get(r.product().productId())))
                .toList();

        return ApiResponse.ok(new RecommendationResponse(
                amount, currentGrade, items, recommendation(ranked, byId, window == null),
                DISCLAIMER));
    }

    private static Product toRankingProduct(RecommendableProduct p) {
        return new Product(p.productId(), p.minGrade(), p.minLoanBalance(), p.maxLoanBalance(),
                p.interestRatePercent(), p.loanPeriodDays());
    }

    private static ProductItem toItem(Ranked r, RecommendableProduct p) {
        return new ProductItem(
                p.productId(), p.externalId(), p.bankName(), p.name(), p.loanTypeName(),
                p.minGrade(), p.minLoanBalance(), p.maxLoanBalance(),
                rate(p.interestRatePercent()), p.loanPeriodDays(),
                // 금융망 원문은 「원리금균등상환」이지만 실제는 단리 일할 + 매일 자동이체다.
                // 화면이 오해하지 않게 표시용 문구를 따로 준다.
                p.repaymentMethod(), "매일 자동이체",
                r.totalInterest(), r.totalRepayment(), r.dailyPayment(),
                r.gapDaysAfter(), r.worstBalanceAfter(),
                r.eligible(), r.note());
    }

    /**
     * 1순위 하나만 고른다.
     *
     * <p>자격이 있는 상품이 없으면 추천하지 않는다 — <b>고를 수 없을 때 아무거나 내미는 것은
     * 추천이 아니다.</b> 화면은 등급 예측(16.2)으로 「언제 열리는지」를 보여준다.
     */
    private static Recommendation recommendation(List<Ranked> ranked,
                                                 Map<Long, RecommendableProduct> byId,
                                                 boolean noCurve) {
        Ranked best = ranked.stream().filter(Ranked::eligible).findFirst().orElse(null);
        if (best == null) {
            return null;
        }
        RecommendableProduct p = byId.get(best.product().productId());
        StringBuilder message = new StringBuilder()
                .append("'%s %s' 이 지금 조건에 가장 맞습니다. ".formatted(p.bankName(), p.name()))
                .append("매일 %s원씩 %d일 동안 갚고, 이자를 더한 총 상환액은 %s원입니다."
                        .formatted(comma(best.dailyPayment()), p.loanPeriodDays(),
                                comma(best.totalRepayment())));
        if (noCurve) {
            message.append(" 잔액 예측이 아직 없어 상환 뒤 잔액 변화는 반영하지 못했습니다.");
        } else if (best.gapDaysAfter() > 0) {
            // 메워 준다고만 말하면 안 된다. 상환이 다음 공백을 만들 수 있다.
            message.append(" 다만 상환을 반영해도 %d일은 안전선 아래로 내려갑니다."
                    .formatted(best.gapDaysAfter()));
        }
        return new Recommendation(p.productId(), message.toString());
    }

    /** 명세 16.3 이 금리를 비율로 적는다(4.5% → 0.045). DB 는 퍼센트로 담는다. */
    private static BigDecimal rate(BigDecimal percent) {
        return percent == null ? null : percent.divide(new BigDecimal("100"), 6, RoundingMode.HALF_UP);
    }

    private static String comma(BigDecimal value) {
        return value == null ? "0" : String.format("%,d", value.toBigInteger());
    }
}
