package com.jangoyebo.forecast.run;

import com.jangoyebo.common.tenancy.BusinessScoped;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/**
 * 예측 실행 (P0-07). 기준 시점·기간·옵션별 실행과 결과 곡선의 루트다.
 *
 * <p><b>{@code BaseEntity} 를 상속하지 않는다.</b> DDL 에 {@code updated_at} 이 없어
 * {@code ddl-auto: validate} 가 부팅을 막는다. 실행은 한 번 쓰고 고치지 않는 기록이라
 * 갱신 시각이 필요 없다 — {@code Job} 이 같은 이유로 {@code @CreationTimestamp} 를 직접 쓴다.
 *
 * <p><b>완료 시점에 한 번 저장한다.</b> {@link ForecastRunStatus} 의 javadoc 참조.
 */
@Entity
@Table(name = "forecast_run", schema = "forecast")
public class ForecastRun implements BusinessScoped {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "business_id", nullable = false, updatable = false)
    private Long businessId;

    /** API {@code baseTime}. 이 시각 이후의 거래·수정은 반영되지 않았다. */
    @Column(name = "base_at", nullable = false, updatable = false)
    private Instant baseAt;

    /** 기준일의 <b>다음 날</b>. 기준일 당일은 이미 시작 잔액에 들어 있다. */
    @Column(name = "start_date", nullable = false)
    private LocalDate startDate;

    @Column(name = "end_date", nullable = false)
    private LocalDate endDate;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private ForecastRunStatus status;

    /**
     * {@code engine-<x.y>/<model>-<ver>}. 모델 미사용은 {@code engine-<x.y>/none}.
     *
     * <p>워커가 준 문자열을 <b>가공하지 않고</b> 붙인다 — {@code <ver>} 에 학습 데이터 지문이
     * 들어 있고(`0.3.0+a82bc981`) 그것이 재현성 보증의 일부다(API 10.1).
     */
    @Column(name = "model_version", nullable = false, length = 60)
    private String modelVersion;

    /** 실행 당시 옵션 스냅샷(API 10.7 {@code appliedOptions}). */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "applied_options")
    private Map<String, Object> appliedOptions;

    /** {@code [{accountId, balance, asOf, source}]}. 곡선이 왜 그 값에서 출발했는지의 근거다. */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "opening_balances", nullable = false)
    private List<OpeningBalance> openingBalances;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false)
    private ForecastBasis basis;

    @Column(name = "completed_at")
    private Instant completedAt;

    /**
     * {@code insertable = false} 로 두면 DB default 가 값을 넣지만 엔티티에 반영되지 않는다
     * ({@code Job} 과 같은 이유).
     */
    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected ForecastRun() {
    }

    private ForecastRun(Long businessId, Instant baseAt, LocalDate startDate, LocalDate endDate,
                        ForecastRunStatus status, String modelVersion, Map<String, Object> appliedOptions,
                        List<OpeningBalance> openingBalances, ForecastBasis basis, Instant completedAt) {
        this.businessId = businessId;
        this.baseAt = baseAt;
        this.startDate = startDate;
        this.endDate = endDate;
        this.status = status;
        this.modelVersion = modelVersion;
        this.appliedOptions = appliedOptions;
        this.openingBalances = openingBalances;
        this.basis = basis;
        this.completedAt = completedAt;
    }

    public static ForecastRun completed(Long businessId, Instant baseAt, LocalDate startDate, LocalDate endDate,
                                        String modelVersion, Map<String, Object> appliedOptions,
                                        List<OpeningBalance> openingBalances, ForecastBasis basis) {
        return new ForecastRun(businessId, baseAt, startDate, endDate, ForecastRunStatus.COMPLETED,
                modelVersion, appliedOptions, openingBalances, basis, baseAt);
    }

    /**
     * 실패한 실행도 남긴다 — 없으면 "왜 곡선이 그대로인가"를 나중에 설명할 수 없다.
     * {@code ix_fr_latest} 가 {@code COMPLETED} 만 담는 부분 인덱스라 조회에는 걸리지 않는다.
     */
    public static ForecastRun failed(Long businessId, Instant baseAt, LocalDate startDate, LocalDate endDate,
                                     String modelVersion, List<OpeningBalance> openingBalances, String reason) {
        ForecastBasis basis = new ForecastBasis(null, null, List.of(), List.of(), List.of(reason), null);
        return new ForecastRun(businessId, baseAt, startDate, endDate, ForecastRunStatus.FAILED,
                modelVersion, null, openingBalances, basis, null);
    }

    /**
     * 알 수 없는 잔액은 <b>빼고</b> 더한다. 0 으로 치면 곡선 전체가 조용히 낮아져
     * 없는 공백이 생긴다 — 대신 신뢰도를 한 단계 내린다.
     */
    public BigDecimal openingBalanceTotal() {
        BigDecimal total = BigDecimal.ZERO;
        for (OpeningBalance balance : openingBalances) {
            if (balance.balance() != null) {
                total = total.add(balance.balance());
            }
        }
        return total;
    }

    public Long getId() { return id; }
    @Override public Long getBusinessId() { return businessId; }
    public Instant getBaseAt() { return baseAt; }
    public LocalDate getStartDate() { return startDate; }
    public LocalDate getEndDate() { return endDate; }
    public ForecastRunStatus getStatus() { return status; }
    public String getModelVersion() { return modelVersion; }
    public Map<String, Object> getAppliedOptions() { return appliedOptions; }
    public List<OpeningBalance> getOpeningBalances() { return openingBalances; }
    public ForecastBasis getBasis() { return basis; }
    public Instant getCompletedAt() { return completedAt; }
    public Instant getCreatedAt() { return createdAt; }

    /** @param balance {@code null} 이면 그 계좌의 잔액을 알 수 없었다는 뜻이다 */
    public record OpeningBalance(Long accountId, BigDecimal balance, Instant asOf, String source) {
    }
}
