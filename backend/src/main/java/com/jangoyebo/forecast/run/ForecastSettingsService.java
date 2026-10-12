package com.jangoyebo.forecast.run;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * 예측 옵션 조회·수정 (API 10.7).
 *
 * <p>행이 없으면 기본값을 돌려준다 — 404 가 아니다. 옵션을 한 번도 건드리지 않은 것은
 * 오류가 아니라 정상 상태다.
 */
@Service
public class ForecastSettingsService {

    private final ForecastSettingRepository repository;

    public ForecastSettingsService(ForecastSettingRepository repository) {
        this.repository = repository;
    }

    /**
     * 잔여 모델을 쓸지. 실행이 매번 부른다.
     *
     * <p>행이 없으면 {@code true} 다 — 기본이 켜짐이고, 그래야 아무것도 설정하지 않은
     * 사업장이 모델의 혜택을 받는다(10.7).
     */
    @Transactional(readOnly = true)
    public boolean applyResidualModel(Long businessId) {
        return repository.findByBusinessId(businessId)
                .map(ForecastSetting::isApplyResidualModel)
                .orElse(true);
    }

    @Transactional(readOnly = true)
    public ForecastSetting get(Long businessId) {
        return repository.findByBusinessId(businessId)
                .orElseGet(() -> ForecastSetting.defaults(businessId));
    }

    /**
     * 부분 수정. 없으면 기본값에서 시작해 만든다.
     *
     * @return 곡선에 영향을 주는 값이 바뀌었는가. 바뀌었으면 호출자가 재계산을 건다 —
     *         화면구성도 SC-03-1 이 "껐다 켜면 밴드 폭과 부족일이 달라지는 것이 보인다"를
     *         요구한다
     */
    @Transactional
    public Patched patch(Long businessId, Boolean seasonality,
                         Boolean vatReserve, Boolean residualModel,
                         List<Integer> peakMonths, List<Integer> offMonths) {
        ForecastSetting setting = repository.findByBusinessId(businessId)
                .orElseGet(() -> repository.save(ForecastSetting.defaults(businessId)));
        boolean curveChanged = setting.patch(
                seasonality, vatReserve, residualModel, peakMonths, offMonths);
        return new Patched(setting, curveChanged);
    }

    public record Patched(ForecastSetting setting, boolean curveChanged) {
    }
}
