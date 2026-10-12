package com.jangoyebo.forecast.loan;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.math.BigDecimal;
import java.util.List;

/**
 * 대출 상품 추천 응답 (API 16.3).
 */
public final class LoanProductDtos {

    private LoanProductDtos() {
    }

    /**
     * @param recommendation 자격 있는 상품이 하나도 없으면 {@code null} 이다 —
     *                       고를 수 없을 때 아무거나 내미는 것은 추천이 아니다
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record RecommendationResponse(BigDecimal requestedAmount,
                                         String currentGrade,
                                         List<ProductItem> items,
                                         Recommendation recommendation,
                                         String disclaimer) {
    }

    /**
     * 상품 하나.
     *
     * @param repaymentMethod        금융망 원문(「원리금균등상환」). 대조용으로 남긴다
     * @param repaymentMethodDisplay <b>화면에 쓸 문구.</b> 실제는 단리 일할 + 매일 자동이체라
     *                               원문을 그대로 내면 월납으로 오해한다
     * @param dailyPayment           하루 상환액. <b>화면이 제일 먼저 보여줘야 할 숫자다</b>
     * @param gapDaysAfter           상환을 반영해도 안전선 아래인 날 수
     * @param worstBalanceAfter      상환 반영 후 최저 잔액. 곡선이 없으면 {@code null}
     * @param eligibilityNote        지금 신청할 수 없는 이유. 신청할 수 있으면 {@code null}
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record ProductItem(Long productId,
                              String finProductNo,
                              String bankName,
                              String name,
                              String loanTypeName,
                              String minGrade,
                              BigDecimal minLoanBalance,
                              BigDecimal maxLoanBalance,
                              BigDecimal interestRate,
                              int loanPeriodDays,
                              String repaymentMethod,
                              String repaymentMethodDisplay,
                              BigDecimal totalInterest,
                              BigDecimal totalRepayment,
                              BigDecimal dailyPayment,
                              int gapDaysAfter,
                              BigDecimal worstBalanceAfter,
                              boolean isEligibleNow,
                              String eligibilityNote) {
    }

    public record Recommendation(Long productId, String message) {
    }
}
