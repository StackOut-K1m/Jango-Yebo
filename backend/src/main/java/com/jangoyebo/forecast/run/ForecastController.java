package com.jangoyebo.forecast.run;

import com.jangoyebo.common.response.ApiResponse;
import com.jangoyebo.common.tenancy.CurrentBusiness;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;


/**
 * 예상 잔액 곡선·정산 공백 (API 10.1·10.2 — P0-07·08).
 *
 * <p>{@code @CurrentBusiness} 가 경로의 사업장을 해석하고 소유를 검증한다.
 *
 * <p><b>계좌별 곡선({@code accountId})은 열지 않았다.</b> {@code FORECAST_RUN} 에
 * {@code account_id} 가 없어 계좌별 실행을 저장할 수 없다 — 마이그레이션이 필요한 P1 이다.
 * 파라미터를 조용히 무시하면 사용자가 계좌별 값을 본다고 착각하므로 {@code 400} 으로 막는다.
 */
@RestController
@RequestMapping("/api/v1/businesses/{businessId}")
public class ForecastController {

    private final ForecastQueryService queryService;
    private final ForecastRunWorker worker;
    private final ForecastSettingsService settingsService;
    private final ApplicationEventPublisher events;

    public ForecastController(ForecastQueryService queryService,
                              ForecastRunWorker worker,
                              ForecastSettingsService settingsService,
                              ApplicationEventPublisher events) {
        this.queryService = queryService;
        this.worker = worker;
        this.settingsService = settingsService;
        this.events = events;
    }

    /**
     * @param days 7·14·30 중 하나. 실행은 항상 30일이고 여기서 자른다
     */
    @GetMapping("/forecast/balance")
    public ApiResponse<ForecastDtos.BalanceForecastResponse> balance(
            @CurrentBusiness Long businessId,
            @RequestParam(defaultValue = "30") int days,
            @RequestParam(required = false) Long accountId) {
        rejectAccountId(accountId);
        return ApiResponse.ok(queryService.balance(businessId, days));
    }

    @GetMapping("/forecast/cash-gaps")
    public ApiResponse<ForecastDtos.CashGapsResponse> cashGaps(
            @CurrentBusiness Long businessId,
            @RequestParam(defaultValue = "30") int days) {
        return ApiResponse.ok(queryService.cashGaps(businessId, days));
    }

    /**
     * 수동 새로고침 — <b>명세에 없는 임시 엔드포인트다.</b>
     *
     * <p>재계산은 원래 트리거(공통규약 2.6)가 걸지만 화면에 「지금 다시 계산」 버튼이 필요하고,
     * {@code 409 FORECAST_NOT_READY} 에서 벗어날 진입점도 있어야 한다. 반복 패턴의
     * {@code /detect} 와 같은 성격이다 — 트리거가 다 붙으면 사라질 수 있다.
     *
     * <p><b>{@code 202} 다.</b> 실제 계산은 5초 디바운스 뒤 사업장 락 아래에서 돈다. 연타해도
     * 실행은 한 번이며, 화면은 잠시 뒤 곡선을 다시 조회한다.
     */
    @PostMapping("/forecast/refresh")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public ApiResponse<ForecastDtos.RefreshAcceptedResponse> refresh(@CurrentBusiness Long businessId) {
        worker.requestRecalculation(businessId);
        return ApiResponse.ok(ForecastDtos.RefreshAcceptedResponse.accepted(businessId));
    }

    /**
     * 예측 옵션 조회 (API 10.7).
     *
     * <p>한 번도 건드리지 않았으면 기본값이 온다 — 404 가 아니다.
     */
    @GetMapping("/forecast-settings")
    public ApiResponse<ForecastDtos.ForecastSettingsResponse> settings(@CurrentBusiness Long businessId) {
        return ApiResponse.ok(toResponse(settingsService.get(businessId)));
    }

    /**
     * 예측 옵션 수정 (API 10.7). <b>부분 수정이다</b> — 보내지 않은 필드는 그대로 둔다.
     *
     * <p>{@code applyResidualModel} 이 바뀌면 재계산을 건다. 화면구성도 SC-03-1 이
     * <b>「모델을 껐다 켜면 밴드 폭과 부족일이 달라지는 것이 곡선에서 그대로 보인다」</b>를
     * 요구하는데, 재계산을 안 걸면 다음 트리거가 올 때까지 옛 곡선이 남는다.
     *
     * <p>P1 옵션 둘은 저장은 되지만 곡선에 반영되지 않는다 — 해당 기능이 아직 없다.
     */
    @PatchMapping("/forecast-settings")
    @Transactional
    public ApiResponse<ForecastDtos.ForecastSettingsResponse> patchSettings(
            @CurrentBusiness Long businessId,
            @RequestBody ForecastDtos.ForecastSettingsPatchRequest request) {
        var patched = settingsService.patch(businessId,
                request.applySeasonality(), request.applyVatReserve(), request.applyResidualModel(),
                request.peakMonths(), request.offMonths());

        if (patched.curveChanged()) {
            // 커밋 뒤에 돈다. 여기서 워커를 직접 부르면 저장 전 값으로 계산할 수 있다.
            events.publishEvent(ForecastInputsChanged.of(
                    businessId, ForecastInputsChanged.Reason.SETTINGS_CHANGED));
        }
        return ApiResponse.ok(toResponse(patched.setting()));
    }

    private static ForecastDtos.ForecastSettingsResponse toResponse(ForecastSetting setting) {
        return new ForecastDtos.ForecastSettingsResponse(
                setting.isApplySeasonality(), setting.isApplyVatReserve(), setting.isApplyResidualModel(),
                setting.getPeakMonths(), setting.getOffMonths());
    }

    private static void rejectAccountId(Long accountId) {
        if (accountId != null) {
            throw new com.jangoyebo.common.error.BusinessException(
                    com.jangoyebo.common.error.ErrorCode.VALIDATION_FAILED,
                    "계좌별 곡선은 아직 지원하지 않습니다. 사업장 전체 합산만 조회할 수 있습니다.",
                    java.util.Map.of("accountId", accountId));
        }
    }
}
