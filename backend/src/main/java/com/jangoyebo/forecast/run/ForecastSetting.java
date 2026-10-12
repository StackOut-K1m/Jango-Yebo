package com.jangoyebo.forecast.run;

import com.jangoyebo.common.entity.BaseEntity;
import com.jangoyebo.common.tenancy.BusinessScoped;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.util.List;

/**
 * 사업장별 예측 옵션 (API 10.7).
 *
 * <p><b>행이 없는 것이 정상이다.</b> 사용자가 옵션을 한 번도 건드리지 않은 상태이며 조회는
 * 기본값을 돌려준다 — {@code SafetyBalancePolicy} 와 같은 모양이다.
 *
 * <p><b>{@code FORECAST_RUN.applied_options} 와 역할이 다르다.</b> 이 테이블이 <b>현재
 * 의사</b>이고 저쪽은 <b>실행 당시 사본</b>이다. 토글을 바꿔도 이미 저장된 실행의
 * {@code appliedOptions} 는 변하지 않는다(10.7).
 *
 * <p>P0 에서 곡선을 실제로 바꾸는 것은 {@code applyResidualModel} 하나다. 나머지 둘은
 * 해당 P1 기능이 서기 전까지 저장만 되고 {@code appliedOptions} 에는 {@code false} 로 나간다.
 */
@Entity
@Table(name = "forecast_setting", schema = "forecast")
public class ForecastSetting extends BaseEntity implements BusinessScoped {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "business_id", nullable = false, updatable = false)
    private Long businessId;

    @Column(name = "apply_seasonality", nullable = false)
    private boolean applySeasonality;

    @Column(name = "apply_vat_reserve", nullable = false)
    private boolean applyVatReserve;

    @Column(name = "apply_residual_model", nullable = false)
    private boolean applyResidualModel = true;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "peak_months")
    private List<Integer> peakMonths;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "off_months")
    private List<Integer> offMonths;

    protected ForecastSetting() {
    }

    private ForecastSetting(Long businessId) {
        this.businessId = businessId;
    }

    /** 기본값 — {@code applyResidualModel} 만 켜져 있다(10.7). */
    public static ForecastSetting defaults(Long businessId) {
        return new ForecastSetting(businessId);
    }

    /**
     * {@code PATCH} 는 부분 수정이다 — {@code null} 인 필드는 건드리지 않는다.
     *
     * @return 곡선에 영향을 주는 값이 실제로 바뀌었는가. 재계산을 걸지 판단하는 데 쓴다
     */
    public boolean patch(Boolean seasonality, Boolean vatReserve,
                         Boolean residualModel, List<Integer> peak, List<Integer> off) {
        boolean curveChanged = residualModel != null && residualModel != applyResidualModel;

        if (seasonality != null) {
            this.applySeasonality = seasonality;
        }
        if (vatReserve != null) {
            this.applyVatReserve = vatReserve;
        }
        if (residualModel != null) {
            this.applyResidualModel = residualModel;
        }
        if (peak != null) {
            this.peakMonths = peak;
        }
        if (off != null) {
            this.offMonths = off;
        }
        return curveChanged;
    }

    public Long getId() { return id; }
    @Override public Long getBusinessId() { return businessId; }
    public boolean isApplySeasonality() { return applySeasonality; }
    public boolean isApplyVatReserve() { return applyVatReserve; }
    public boolean isApplyResidualModel() { return applyResidualModel; }
    public List<Integer> getPeakMonths() { return peakMonths == null ? List.of() : peakMonths; }
    public List<Integer> getOffMonths() { return offMonths == null ? List.of() : offMonths; }
}
