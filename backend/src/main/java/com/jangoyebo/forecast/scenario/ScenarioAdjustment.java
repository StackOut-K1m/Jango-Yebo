package com.jangoyebo.forecast.scenario;

import com.jangoyebo.common.domain.FlowType;
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

/**
 * 저장한 시나리오의 파라미터 한 건 (ERD {@code SCENARIO_ADJUSTMENT}).
 *
 * <p><b>{@code BaseEntity} 를 상속하지 않는다.</b> DDL 에 {@code updated_at} 이 없어
 * {@code ddl-auto: validate} 가 부팅을 막는다 — 조정은 한 번 쓰고 고치지 않는 값이라
 * 갱신 시각이 필요 없다({@code ForecastRun} 과 같은 사정).
 *
 * <p><b>대상 외래키는 {@code ON DELETE SET NULL} 이다</b>({@code V32}). 사용자가 예정
 * 거래를 지우거나 패턴을 고쳐 회차가 재생성되면 여기 컬럼이 비는데, 그때도
 * {@link Details} 에 남긴 원래 id 로 「대상이 삭제되어 반영하지 않았다」를 말할 수 있다.
 * 조정 행 자체를 지우면(CASCADE) 저장한 파라미터가 소리 없이 사라진다.
 */
@Entity
@Table(name = "scenario_adjustment", schema = "forecast")
public class ScenarioAdjustment {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "scenario_id", nullable = false, updatable = false)
    private Long scenarioId;

    @Enumerated(EnumType.STRING)
    @Column(name = "adjustment_type", nullable = false, length = 40)
    private ScenarioAdjustmentType adjustmentType;

    /** {@code EXPENSE_DATE_SHIFT} 의 대상. 예정 거래가 지워지면 비워진다({@code V32}). */
    @Column(name = "target_planned_transaction_id")
    private Long targetPlannedTransactionId;

    /** {@code SETTLEMENT_DELAY}·{@code DEPOSIT_DECREASE} 의 대상. 없으면 「패턴 전체」다. */
    @Column(name = "target_pattern_id")
    private Long targetPatternId;

    /** {@code EXTRA_*} 의 방향. */
    @Enumerated(EnumType.STRING)
    @Column(name = "flow_type", length = 20)
    private FlowType flowType;

    /** 절대 금액 — 추가 거래액·대출 원금. */
    @Column(name = "amount_value")
    private BigDecimal amountValue;

    /** 증감률. {@code NUMERIC(7,4)} 이고 {@code SALES_CHANGE} 때문에 음수를 받는다. */
    @Column(name = "percentage_value")
    private BigDecimal percentageValue;

    @Column(name = "delay_days")
    private Integer delayDays;

    /** 변경·발생일 — 추가 거래일·새 지출일·대출 실행일. */
    @Column(name = "changed_date")
    private LocalDate changedDate;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column
    private Details details;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected ScenarioAdjustment() {
    }

    ScenarioAdjustment(Long scenarioId, ScenarioAdjustmentType adjustmentType,
                       Long targetPlannedTransactionId, Long targetPatternId, FlowType flowType,
                       BigDecimal amountValue, BigDecimal percentageValue, Integer delayDays,
                       LocalDate changedDate, Details details) {
        this.scenarioId = scenarioId;
        this.adjustmentType = adjustmentType;
        this.targetPlannedTransactionId = targetPlannedTransactionId;
        this.targetPatternId = targetPatternId;
        this.flowType = flowType;
        this.amountValue = amountValue;
        this.percentageValue = percentageValue;
        this.delayDays = delayDays;
        this.changedDate = changedDate;
        this.details = details;
    }

    /**
     * 전용 컬럼이 없는 값들.
     *
     * <p><b>{@code Map<String,Object>} 가 아니라 타입 있는 record 다.</b> 여기 담긴 비율과
     * 금액은 나중에 <b>다시 계산에 들어가는 숫자</b>인데 {@code Map} 역직렬화는 숫자를
     * {@code Integer}·{@code Double} 로 준다({@code ForecastBasis} 가 같은 이유로 record 다).
     *
     * @param targetPatternId            {@link #targetPatternId} 의 사본. 외래키가 비워져도
     *                                   「원래 대상이 있었다」와 「처음부터 전체였다」를
     *                                   구분해야 한다 — 구분하지 못하면 대상이 사라진 시나리오가
     *                                   조용히 <b>패턴 전체</b>로 넓어진다
     * @param targetPlannedTransactionId 같은 이유의 사본
     */
    public record Details(String title,
                          String productKey,
                          String productName,
                          BigDecimal quantityRate,
                          BigDecimal priceRate,
                          BigDecimal interestRate,
                          Integer loanPeriodDays,
                          String repaymentCycle,
                          Long targetPatternId,
                          Long targetPlannedTransactionId) {
    }

    public Long getId() { return id; }
    public Long getScenarioId() { return scenarioId; }
    public ScenarioAdjustmentType getAdjustmentType() { return adjustmentType; }
    public Long getTargetPlannedTransactionId() { return targetPlannedTransactionId; }
    public Long getTargetPatternId() { return targetPatternId; }
    public FlowType getFlowType() { return flowType; }
    public BigDecimal getAmountValue() { return amountValue; }
    public BigDecimal getPercentageValue() { return percentageValue; }
    public Integer getDelayDays() { return delayDays; }
    public LocalDate getChangedDate() { return changedDate; }
    public Details getDetails() { return details; }
    public Instant getCreatedAt() { return createdAt; }
}
