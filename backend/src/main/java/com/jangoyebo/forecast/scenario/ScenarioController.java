package com.jangoyebo.forecast.scenario;

import com.jangoyebo.common.response.ApiResponse;
import com.jangoyebo.common.tenancy.CurrentBusiness;
import com.jangoyebo.forecast.scenario.ScenarioDtos.CreateRequest;
import com.jangoyebo.forecast.scenario.ScenarioDtos.RenameRequest;
import com.jangoyebo.forecast.scenario.ScenarioDtos.SavedScenarioItem;
import com.jangoyebo.forecast.scenario.ScenarioDtos.ScenarioListResponse;
import com.jangoyebo.forecast.scenario.ScenarioDtos.ScenarioResultResponse;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * What-if 시나리오 (API 11.1·11.2, P0-09·P0-13).
 *
 * <p><b>동기 계산이다.</b> Job 을 쓰지 않는다 — 저장된 실행을 읽어 원장을 변형하고 순수
 * 함수를 두 번 부르는 것이 전부라 요청 안에서 끝난다(공통규약 2.7 의 비동기 대상 목록에도
 * 시나리오가 없다).
 *
 * <p><b>「적용」이 없다.</b> 시나리오는 원본 예측을 절대 바꾸지 않는다(11.1). 실제 반영은
 * 사용자가 예정 거래를 직접 고치는 것뿐이며, 그것이 도메인 규칙 5(실행성 액션은 명시 승인
 * 후에만)가 이 화면에서 지켜지는 방식이다.
 */
@RestController
@RequestMapping("/api/v1")
public class ScenarioController {

    private final ScenarioService service;

    public ScenarioController(ScenarioService service) {
        this.service = service;
    }

    /**
     * 시나리오 계산 (11.1).
     *
     * <p>{@code save} 에 따라 상태 코드가 갈려 {@code ResponseEntity} 로 낸다 —
     * {@code @ResponseStatus} 는 고정값이라 쓸 수 없다. 저장했으면 {@code 201} 과
     * {@code scenarioId}, 아니면 {@code 200} 과 결과만이다.
     */
    @PostMapping("/businesses/{businessId}/scenarios")
    public ResponseEntity<ApiResponse<ScenarioResultResponse>> create(
            @CurrentBusiness Long businessId,
            @Valid @RequestBody CreateRequest request) {

        ScenarioResultResponse result = service.create(businessId, request);
        HttpStatus status = result.scenarioId() == null ? HttpStatus.OK : HttpStatus.CREATED;
        return ResponseEntity.status(status).body(ApiResponse.ok(result));
    }

    /** 저장 목록 (11.2). 페이징 없이 {@code items} 만 낸다. */
    @GetMapping("/businesses/{businessId}/scenarios")
    public ApiResponse<ScenarioListResponse> list(@CurrentBusiness Long businessId) {
        return ApiResponse.ok(service.list(businessId));
    }

    /**
     * 저장한 시나리오의 <b>현재 데이터 기준</b> 결과 (11.2).
     *
     * <p>직접 경로라 {@code @CurrentBusiness} 를 거치지 않는다 — 소유권은 서비스가 명시적으로
     * 확인한다. 없는 id 는 {@code 404}, 남의 것은 {@code 403} 이다.
     */
    @GetMapping("/scenarios/{scenarioId}")
    public ApiResponse<ScenarioResultResponse> get(@PathVariable Long scenarioId) {
        return ApiResponse.ok(service.recompute(scenarioId));
    }

    /** 이름 수정 (11.2). 결과를 다시 계산하지 않는다. */
    @PatchMapping("/scenarios/{scenarioId}")
    public ApiResponse<SavedScenarioItem> rename(@PathVariable Long scenarioId,
                                                 @Valid @RequestBody RenameRequest request) {
        return ApiResponse.ok(service.rename(scenarioId, request.name()));
    }

    @DeleteMapping("/scenarios/{scenarioId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable Long scenarioId) {
        service.delete(scenarioId);
    }
}
