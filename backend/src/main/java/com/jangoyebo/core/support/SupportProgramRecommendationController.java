package com.jangoyebo.core.support;

import com.jangoyebo.common.response.ApiResponse;
import com.jangoyebo.core.support.SupportProgramRecommendationDtos.Recommendations;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 지원 제도 맞춤·공백 연계 추천 (API 17.3, P1-15).
 *
 * <p><b>여기는 사업장 하위 경로다.</b> 17.1 검색·17.2 상세·17.4 서류 사전이 공용 경로인 것과
 * 다르다 — 점수의 입력(지역·업종·매출·업력)이 사업장에 딸린 값이라 사업장 없이는 낼 수 없다.
 * 17.1 이 {@code sort=MATCH_SCORE} 를 거절하고 이쪽을 가리키는 이유가 그것이다.
 */
@RestController
@RequestMapping("/api/v1/businesses/{businessId}/support-programs")
public class SupportProgramRecommendationController {

    private final SupportProgramRecommendationService recommendations;

    public SupportProgramRecommendationController(
            SupportProgramRecommendationService recommendations) {
        this.recommendations = recommendations;
    }

    /**
     * @param cashGapLinked 가장 가까운 정산 공백을 기준으로 시점을 함께 본다. 기본 {@code true}
     *                      — 이 화면에 오는 이유가 대개 「다음 고비를 넘길 방법」이어서다.
     *                      완료된 예측이 없으면 <b>거절하지 않고</b> {@code cashGap} 키만 빠진다
     */
    @GetMapping("/recommendations")
    public ApiResponse<Recommendations> recommendations(
            @PathVariable Long businessId,
            @RequestParam(required = false, defaultValue = "true") boolean cashGapLinked) {

        return ApiResponse.ok(recommendations.recommend(businessId, cashGapLinked));
    }
}
