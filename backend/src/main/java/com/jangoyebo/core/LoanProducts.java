package com.jangoyebo.core;

import java.math.BigDecimal;
import java.util.List;

/**
 * 추천 후보 대출 상품 — {@code core} 모듈이 밖에 내놓는 공개 API (API 16.3, P1-14).
 *
 * <p>{@link Loans}·{@link CreditAssets} 와 같은 이유로 여기 있다. 순위는 <b>예측 곡선</b>으로
 * 매기므로 {@code forecast} 가 하고, 상품 목록은 금융망에서 오므로 {@code core} 가 준다.
 */
public interface LoanProducts {

    /**
     * 추천에 내보낼 상품.
     *
     * <p><b>{@code recommendable = TRUE} 인 것만 준다.</b> 금융망에 상품 삭제 API 가 없고
     * 팀 {@code apiKey} 를 가진 누구나 상품을 만들 수 있어, 동기화한 목록을 그대로 쓰면
     * 누가 시험 삼아 만든 상품이 사장님에게 「이 대출을 받으세요」로 나간다. 켠 것만 나간다.
     */
    List<RecommendableProduct> recommendable();

    /**
     * @param minGrade 최소 가입 등급. 그 등급 <b>이상</b>이면 가입할 수 있다
     * @param interestRatePercent 연 %. 금융망이 주는 단위 그대로다 — 비율 변환은 응답 경계에서 한다
     */
    record RecommendableProduct(Long productId, String externalId,
                                String bankName, String name, String loanTypeName,
                                String minGrade,
                                BigDecimal minLoanBalance, BigDecimal maxLoanBalance,
                                BigDecimal interestRatePercent, int loanPeriodDays,
                                String repaymentMethod) {
    }
}
