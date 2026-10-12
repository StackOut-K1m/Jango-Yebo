package com.jangoyebo.forecast.loan;

import com.jangoyebo.forecast.pattern.ConfidenceBand;
import com.jangoyebo.forecast.engine.CreditGradeProjection.MonthlyEstimate;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/**
 * 신용등급 현황·예측 응답 (API 16.2).
 *
 * <p><b>퍼센트를 내보내지 않는다.</b> 신뢰도는 등급({@link ConfidenceBand})으로만 말한다 —
 * 도메인 규칙 1 이고, 적중률 숫자는 P1-05 가 실측한 뒤에 나온다.
 */
public final class CreditDtos {

    private CreditDtos() {
    }

    /**
     * @param currentGrade 지금 등급. <b>대표자 단위다</b> — 사업장이 여러 개여도 하나다
     * @param gradeBasis   무엇으로 매긴 등급인지. 화면이 「보유 자산 기준 참고 등급」으로 쓰게 하는 근거
     * @param projection   월별 추정. 곡선이 없으면 빈 목록이다
     * @param confidence   <b>곡선 신뢰도보다 한 단계 낮다</b> — 환산이 가정을 하나 더 얹는다
     */
    public record CreditResponse(Instant baseTime,
                                 String currentGrade,
                                 List<String> gradeScale,
                                 String gradeBasis,
                                 AssetBreakdown assets,
                                 List<GradeCriterionResponse> gradeCriteria,
                                 List<MonthlyEstimate> projection,
                                 ConfidenceBand confidence,
                                 ProjectionBasis basis,
                                 String disclaimer,
                                 List<String> warnings) {
    }

    /** 자산 3분류. 금융망 {@code LOAN_04} 가 주는 그대로다. */
    public record AssetBreakdown(BigDecimal demandDeposit, BigDecimal depositSavings, BigDecimal total) {
    }

    public record GradeCriterionResponse(String grade, BigDecimal minAssetValue) {
    }

    /**
     * @param assumption 환산에 깔린 가정. <b>숨기지 않는다</b> — 이 값이 틀릴 수 있는 이유다
     */
    public record ProjectionBasis(String method, int referenceCurveDays, String assumption) {
    }
}
