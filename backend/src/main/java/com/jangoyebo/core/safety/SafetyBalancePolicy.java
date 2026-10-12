package com.jangoyebo.core.safety;

import com.jangoyebo.common.entity.BaseEntity;
import com.jangoyebo.common.tenancy.BusinessScoped;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.math.BigDecimal;

/**
 * 운영자금 안전선 — 사업장별 최소 운영 잔액 (API 12.1, ERD {@code SAFETY_BALANCE_POLICY}).
 *
 * <p>위험 등급 4단계 중 {@code CAUTION}·{@code DANGER} 의 기준선이다. 없으면 0원 미만({@code GAP})만
 * 판정할 수 있어 자금 부족 알림(P0-08)이 반쪽이 된다.
 *
 * <p><b>"설정 안 함"은 금액 0 이 아니라 행이 없는 것이다.</b> API 12.1 이 미설정 시
 * {@code amount: null} 로 응답하고 위험 등급을 0원 기준으로만 판정하도록 정했다. 0 을 넣으면
 * "안전선을 0원으로 설정함"이 되어 다른 뜻이 된다.
 *
 * <p>사업장당 한 행이며 {@code uk_sbp_business} 가 그것을 강제한다.
 */
@Entity
@Table(name = "safety_balance_policy", schema = "core")
public class SafetyBalancePolicy extends BaseEntity implements BusinessScoped {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "business_id", nullable = false, updatable = false)
    private Long businessId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private SafetyLineMethod method;

    @Column(nullable = false)
    private BigDecimal amount;

    /**
     * 매월 고정비 집계로 금액을 자동 갱신할지 (API 12.1).
     *
     * <p>P0 범위는 수동 설정만이라 <b>받아서 저장하되 아무도 읽지 않는다.</b> 자동 갱신 배치는
     * 추천값 산출(P1)과 같은 시점에 붙는다. 지금 저장해 두는 것은 사용자가 켜 둔 의사를
     * 잃지 않기 위해서다.
     */
    @Column(name = "auto_update", nullable = false)
    private boolean autoUpdate;

    protected SafetyBalancePolicy() {
    }

    private SafetyBalancePolicy(Long businessId, SafetyLineMethod method,
                                BigDecimal amount, boolean autoUpdate) {
        this.businessId = businessId;
        this.method = method;
        this.amount = amount;
        this.autoUpdate = autoUpdate;
    }

    public static SafetyBalancePolicy of(Long businessId, SafetyLineMethod method,
                                         BigDecimal amount, boolean autoUpdate) {
        return new SafetyBalancePolicy(businessId, method, amount, autoUpdate);
    }

    /** {@code PUT} 은 전체 교체다 — 부분 갱신이 아니므로 세 값을 모두 덮는다. */
    public void replace(SafetyLineMethod method, BigDecimal amount, boolean autoUpdate) {
        this.method = method;
        this.amount = amount;
        this.autoUpdate = autoUpdate;
    }

    public Long getId() { return id; }
    @Override public Long getBusinessId() { return businessId; }
    public SafetyLineMethod getMethod() { return method; }
    public BigDecimal getAmount() { return amount; }
    public boolean isAutoUpdate() { return autoUpdate; }
}
