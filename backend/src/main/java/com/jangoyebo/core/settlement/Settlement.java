package com.jangoyebo.core.settlement;

import com.jangoyebo.common.entity.BaseEntity;
import com.jangoyebo.common.error.BusinessException;
import com.jangoyebo.common.error.ErrorCode;
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
import java.time.LocalDate;

/**
 * 플랫폼 정산 대사 (ERD {@code SETTLEMENT}, API 14.1, P0-11).
 *
 * <p>플랫폼이 알려준 매출과 <b>실제 입금액</b>을 맞춰 본다. 배달 플랫폼은 총매출에서
 * 취소·수수료를 뺀 금액을 며칠 뒤에 넣어 주므로, 사장 입장에서는 "얼마가 들어와야 하는지"가
 * 계좌만 봐서는 보이지 않는다.
 *
 * <p><b>예상 정산액과 차액은 저장하지 않는다.</b> 총매출·취소·수수료에서 결정되는 파생값이라
 * 저장하면 원본과 어긋날 수 있다(ERD 주석). 상태만 저장한다 — 그건 허용 오차라는
 * 별도 기준이 개입하기 때문이다.
 *
 * <p>대사 결과는 P0-12 신고자료의 플랫폼 정산 대사표 원천이 된다.
 */
@Entity
@Table(name = "settlement", schema = "core")
public class Settlement extends BaseEntity implements BusinessScoped {

    /**
     * 차이를 같다고 볼 허용 오차.
     *
     * <p>기본 0원이다(API 14.1). 원 단위로 딱 떨어지는 정산이 정상이고, 1원이라도 다르면
     * 이유가 있다 — 반올림이 아니라 <b>누락되거나 추가 차감된 것</b>이다.
     */
    // --- 조정 가능 지점 ---
    private static final BigDecimal TOLERANCE = BigDecimal.ZERO;

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "business_id", nullable = false, updatable = false)
    private Long businessId;

    @Column(name = "platform_name", nullable = false, length = 50)
    private String platformName;

    @Column(name = "sales_from", nullable = false)
    private LocalDate salesFrom;

    @Column(name = "sales_to", nullable = false)
    private LocalDate salesTo;

    @Column(name = "gross_sales", nullable = false)
    private BigDecimal grossSales;

    @Column(name = "cancellation_amount", nullable = false)
    private BigDecimal cancellationAmount;

    @Column(name = "fee_amount", nullable = false)
    private BigDecimal feeAmount;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private SettlementStatus status;

    protected Settlement() {
    }

    private Settlement(Long businessId, String platformName, LocalDate from, LocalDate to,
                       BigDecimal grossSales, BigDecimal cancellations, BigDecimal fees) {
        this.businessId = businessId;
        this.platformName = platformName;
        this.salesFrom = from;
        this.salesTo = to;
        this.grossSales = grossSales;
        this.cancellationAmount = cancellations;
        this.feeAmount = fees;
        this.status = SettlementStatus.PENDING;
    }

    public static Settlement of(Long businessId, String platformName, LocalDate from, LocalDate to,
                                BigDecimal grossSales, BigDecimal cancellations, BigDecimal fees) {
        Settlement s = new Settlement(businessId, platformName, from, to,
                grossSales, cancellations, fees);
        s.assertPeriod();
        return s;
    }

    /** 전달된 값만 바꾼다. 상태는 매칭 거래와 함께 호출자가 다시 계산한다. */
    public void update(String platformName, LocalDate from, LocalDate to,
                       BigDecimal grossSales, BigDecimal cancellations, BigDecimal fees) {
        if (platformName != null) this.platformName = platformName;
        if (from != null) this.salesFrom = from;
        if (to != null) this.salesTo = to;
        if (grossSales != null) this.grossSales = grossSales;
        if (cancellations != null) this.cancellationAmount = cancellations;
        if (fees != null) this.feeAmount = fees;
        assertPeriod();
    }

    /** {@code grossSales − cancellations − fees}. 저장하지 않고 매번 계산한다(API 14.1). */
    public BigDecimal expectedPayout() {
        return grossSales.subtract(cancellationAmount).subtract(feeAmount);
    }

    public BigDecimal difference(BigDecimal actualDeposit) {
        return actualDeposit.subtract(expectedPayout());
    }

    /**
     * 매칭된 입금 합계로 상태를 다시 판정한다.
     *
     * <p><b>매칭이 없으면 {@code PENDING} 이다.</b> 차이가 예상액만큼 난 것으로 보고
     * {@code MISMATCHED} 로 만들면, 아직 입금 전인 정상 상태가 전부 경고로 뜬다.
     */
    public SettlementStatus recalculate(BigDecimal actualDeposit, boolean hasMatches) {
        if (!hasMatches) {
            this.status = SettlementStatus.PENDING;
        } else {
            BigDecimal gap = difference(actualDeposit).abs();
            this.status = gap.compareTo(TOLERANCE) <= 0
                    ? SettlementStatus.MATCHED : SettlementStatus.MISMATCHED;
        }
        return this.status;
    }

    private void assertPeriod() {
        if (salesFrom.isAfter(salesTo)) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED,
                    "periodStart 가 periodEnd 보다 늦습니다.");
        }
        if (grossSales.signum() < 0 || cancellationAmount.signum() < 0 || feeAmount.signum() < 0) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "금액은 음수일 수 없습니다.");
        }
    }

    public Long getId() { return id; }
    @Override public Long getBusinessId() { return businessId; }
    public String getPlatformName() { return platformName; }
    public LocalDate getSalesFrom() { return salesFrom; }
    public LocalDate getSalesTo() { return salesTo; }
    public BigDecimal getGrossSales() { return grossSales; }
    public BigDecimal getCancellationAmount() { return cancellationAmount; }
    public BigDecimal getFeeAmount() { return feeAmount; }
    public SettlementStatus getStatus() { return status; }
}
