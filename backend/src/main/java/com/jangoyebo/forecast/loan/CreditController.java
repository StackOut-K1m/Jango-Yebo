package com.jangoyebo.forecast.loan;

import com.jangoyebo.forecast.pattern.ConfidenceBand;
import com.jangoyebo.common.response.ApiResponse;
import com.jangoyebo.common.tenancy.CurrentBusiness;
import com.jangoyebo.core.CreditAssets;
import com.jangoyebo.core.CreditAssets.GradeCriterion;
import com.jangoyebo.core.CreditAssets.Snapshot;
import com.jangoyebo.forecast.engine.ConfidencePolicy;
import com.jangoyebo.forecast.engine.CreditGradeProjection;
import com.jangoyebo.forecast.engine.CreditGradeProjection.Band;
import com.jangoyebo.forecast.engine.CreditGradeProjection.MonthlyEstimate;
import com.jangoyebo.forecast.loan.CreditDtos.AssetBreakdown;
import com.jangoyebo.forecast.loan.CreditDtos.CreditResponse;
import com.jangoyebo.forecast.loan.CreditDtos.GradeCriterionResponse;
import com.jangoyebo.forecast.loan.CreditDtos.ProjectionBasis;
import com.jangoyebo.forecast.run.CreditCurveReader;
import com.jangoyebo.forecast.run.CreditCurveReader.CurveWindow;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.List;

/**
 * 신용등급 현황·예측 (API 16.2, P1-14).
 *
 * <p><b>{@code forecast} 에 있다.</b> 현재 등급과 자산은 {@code core} 가 금융망에서 받아 주지만
 * ({@link CreditAssets}), 예측은 <b>저장된 잔액 곡선을 자산 곡선으로 환산</b>하는 일이라
 * 곡선이 있는 쪽에 있어야 한다. 의존 방향은 {@code forecast → core} 그대로다.
 *
 * <h2>등급 예측에 별도 모델을 쓰지 않는다</h2>
 *
 * <p>이미 계산해 둔 곡선을 환산할 뿐이다. 새 모델을 붙이면 <b>같은 사업장에 서로 다른 미래가
 * 둘 생긴다</b> — 잔액 화면은 오르는데 등급 화면은 떨어지는 식이다. 하나에서 파생시키면
 * 그 모순이 생길 수 없다.
 */
@RestController
@RequestMapping("/api/v1")
public class CreditController {

    /** 명세 16.2 의 {@code basis.referenceCurveDays}. 곡선이 그보다 짧으면 있는 만큼만 쓴다. */
    private static final int REFERENCE_CURVE_DAYS = 60;

    private static final String GRADE_BASIS =
            "SSAFY 금융망 자산 기반 신용등급 (수시입출금 + 예·적금 합산)";

    private static final String METHOD =
            "잔액 예측 곡선을 자산 곡선으로 환산해 금융망 등급 구간(gradeCriteria)에 대입";

    private static final String DISCLAIMER =
            "예측 등급은 추정치이며 실제 심사 결과와 다를 수 있습니다.";

    /**
     * 환산에 깔린 가정. <b>화면에 같이 내보낸다</b> — 이 값이 틀릴 수 있는 이유가 여기 있고,
     * 숨기면 사장님이 확정된 예측으로 읽는다(도메인 규칙 1).
     */
    private static final String ASSUMPTION =
            "이 사업장 흐름만 반영합니다. 예·적금과 연결하지 않은 계좌는 변하지 않는다고 봅니다.";

    private final CreditAssets creditAssets;
    private final CreditCurveReader curves;

    public CreditController(CreditAssets creditAssets, CreditCurveReader curves) {
        this.creditAssets = creditAssets;
        this.curves = curves;
    }

    @GetMapping("/businesses/{businessId}/credit")
    @Transactional
    public ApiResponse<CreditResponse> credit(@CurrentBusiness Long businessId) {
        Snapshot snapshot = creditAssets.current(businessId);
        List<GradeCriterion> criteria = creditAssets.criteria();
        List<Band> bands = criteria.stream()
                .map(c -> new Band(c.grade(), c.minAssetValue()))
                .toList();

        CurveWindow window = curves.forCredit(businessId, REFERENCE_CURVE_DAYS);

        List<MonthlyEstimate> projection = window == null ? List.of()
                : CreditGradeProjection.project(window.points(), window.openingBalance(),
                        snapshot.total(), bands);

        List<String> warnings = new ArrayList<>();
        if (window == null) {
            // 없는 것을 지어내지 않는다. 화면이 「아직 예측이 없습니다」를 보여줄 근거다.
            warnings.add("잔액 예측이 아직 없어 등급 변화를 추정하지 못했습니다.");
        }

        return ApiResponse.ok(new CreditResponse(
                snapshot.assessedAt(),
                snapshot.grade(),
                criteria.stream().map(GradeCriterion::grade).toList(),
                GRADE_BASIS,
                new AssetBreakdown(snapshot.demandDeposit(), snapshot.depositSavings(), snapshot.total()),
                criteria.stream()
                        .map(c -> new GradeCriterionResponse(c.grade(), c.minAssetValue()))
                        .toList(),
                projection,
                projectionConfidence(window),
                new ProjectionBasis(METHOD, REFERENCE_CURVE_DAYS, ASSUMPTION),
                DISCLAIMER,
                warnings));
    }

    /**
     * 곡선 신뢰도에서 <b>한 단계 내린다.</b>
     *
     * <p>환산이 「예보 밖의 돈은 안 움직인다」는 가정을 하나 더 얹기 때문이다 — 곡선이
     * 맞아도 이 추정은 틀릴 수 있다. 곡선이 없으면 추정 자체가 없으므로
     * {@code UNPREDICTABLE} 이다.
     */
    private static ConfidenceBand projectionConfidence(CurveWindow window) {
        return window == null
                ? ConfidenceBand.UNPREDICTABLE
                : ConfidencePolicy.degrade(window.confidence());
    }
}
