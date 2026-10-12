package com.jangoyebo.core.productsales;

import com.jangoyebo.common.entity.BaseEntity;
import com.jangoyebo.common.tenancy.BusinessScoped;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * 기간·상품별 매출 집계 (ERD {@code PRODUCT_SALES_PERIOD}, API 14.4, P1-22).
 *
 * <p><b>이 테이블은 구성비 산출용이다.</b> 매출 금액의 단일 출처는 계좌 입금
 * ({@code TRANSACTION})이고, 여기 저장한 금액은 <b>상품 간 비율을 구하기 위한 분자</b>로만
 * 쓴다. 세무 집계(14.3)·부가세(P1-11)·잔액 예측에는 들어가지 않는다.
 *
 * <p><b>금액 열이 전부 Optional 인 것은 실측 결과다.</b> 58/80mm 감열지에 8열이 한 줄로
 * 들어가지 않아 실제 인쇄물은 열이 줄거나 접힌다 — 58mm 는 상품명·수량·결제금액·구성비
 * 4열만 남고 <b>총매출 열이 통째로 빠진다</b>(합성 90표본 중 30표본 전부, 행 33.3%).
 * 대신 {@code ck_ps_period_share_basis} 가 <b>구성비의 분모가 될 값이 하나는 있도록</b>
 * 강제한다 — 이 테이블의 존재 이유가 구성비이므로 그쪽이 진짜 불변식이다.
 *
 * <p><b>양수 제약을 걸지 않는다.</b> 집계 데이터라 취소·반품이 상계돼 음수가 될 수 있고,
 * {@code TRANSACTION} 의 {@code amount > 0} 을 복사하면 취소 데이터가 통째로 거부된다.
 */
@Entity
@Table(name = "product_sales_period", schema = "core")
public class ProductSalesPeriod extends BaseEntity implements BusinessScoped {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "business_id", nullable = false, updatable = false)
    private Long businessId;

    /** 어느 추출에서 온 행인가. 수기 입력({@code MANUAL})이면 null. */
    @Column(name = "extraction_id")
    private Long extractionId;

    @Column(name = "period_start", nullable = false, updatable = false)
    private LocalDate periodStart;

    @Column(name = "period_end", nullable = false, updatable = false)
    private LocalDate periodEnd;

    /** 인쇄물의 상품명 원문. 화면 표시용이고 집계는 {@link #productKey} 로 한다. */
    @Column(name = "product_name", nullable = false, length = 255)
    private String productName;

    /**
     * 정규화한 집계 키. {@link ProductKeys} 가 만든다.
     *
     * <p>{@code uk_ps_period} 의 일부라 <b>바뀌면 다른 행이 된다</b> — 수정은 같은 키로만 한다.
     */
    @Column(name = "product_key", nullable = false, updatable = false, length = 255)
    private String productKey;

    /** 취소·반품 상계 후 값이라 음수 가능. 수량 열이 없는 인쇄물이 있어 null 가능. */
    @Column
    private BigDecimal quantity;

    /** 총매출. <b>58mm 인쇄물에는 이 열이 없다.</b> */
    @Column(name = "gross_amount")
    private BigDecimal grossAmount;

    @Column(name = "discount_amount")
    private BigDecimal discountAmount;

    @Column(name = "net_amount")
    private BigDecimal netAmount;

    /** <b>세무 계산에 쓰지 않는다.</b> 부가세 예수금은 과세 매출 입금의 10/110 한 방식만 유지한다. */
    @Column(name = "vat_amount")
    private BigDecimal vatAmount;

    /** 합계(총매출 − 총할인). 손님이 실제 낸 금액이고, 있으면 <b>구성비의 기준</b>이다. */
    @Column(name = "billed_amount")
    private BigDecimal billedAmount;

    /** {@code OCR} · {@code OCR_CORRECTED} · {@code MANUAL}. */
    @Column(name = "source_type", nullable = false, length = 16)
    private String sourceType;

    /**
     * {@code MATCHED} · {@code MISMATCHED} · {@code UNAVAILABLE}.
     *
     * <p>⚠ <b>{@code MISMATCHED} 는 오류가 아니다.</b> 라벨 90개 중 20개(22.2%)가 그렇고 그것이
     * 정상이다 — 인쇄물이 여러 장으로 잘리면 합계 행은 기간 전체를 담고 촬영된 장에는 일부
     * 행만 보인다. 저장을 막지 않고 조회에서 {@code incomplete} 로 표시한다.
     */
    @Column(name = "checksum_status", nullable = false, length = 12)
    private String checksumStatus;

    protected ProductSalesPeriod() {
    }

    private ProductSalesPeriod(Long businessId, Long extractionId, LocalDate periodStart,
                               LocalDate periodEnd, String productName, String productKey) {
        this.businessId = businessId;
        this.extractionId = extractionId;
        this.periodStart = periodStart;
        this.periodEnd = periodEnd;
        this.productName = productName;
        this.productKey = productKey;
    }

    public static ProductSalesPeriod of(Long businessId, Long extractionId, LocalDate periodStart,
                                        LocalDate periodEnd, String productName, String productKey) {
        return new ProductSalesPeriod(businessId, extractionId, periodStart, periodEnd,
                productName, productKey);
    }

    /**
     * 금액·수량과 출처를 채운다. 재등록(upsert)에서도 같은 메서드를 쓴다.
     *
     * <p><b>null 을 그대로 덮어쓴다.</b> 13.3 의 부분 수정과 반대인데, 이쪽은 인쇄물 한 장이
     * 기간 전체를 통째로 다시 말하는 입력이라 "이번 인쇄물에 그 열이 없었다"가 곧 null 이다.
     * 남겨 두면 <b>지난번 인쇄물의 열이 이번 기간에 섞인다.</b>
     */
    public void fill(BigDecimal quantity, BigDecimal grossAmount, BigDecimal discountAmount,
                     BigDecimal netAmount, BigDecimal vatAmount, BigDecimal billedAmount,
                     String productName, String sourceType, String checksumStatus,
                     Long extractionId) {
        this.quantity = quantity;
        this.grossAmount = grossAmount;
        this.discountAmount = discountAmount;
        this.netAmount = netAmount;
        this.vatAmount = vatAmount;
        this.billedAmount = billedAmount;
        this.productName = productName;
        this.sourceType = sourceType;
        this.checksumStatus = checksumStatus;
        this.extractionId = extractionId;
    }

    /**
     * 표기만 바꾼다 (14.6). 금액·수량·기간은 그대로다.
     *
     * <p>{@code productKey} 는 건드리지 않는다 — 집계 키가 바뀌면 다른 행이고, 그건
     * {@code UK} 가 달라진다는 뜻이라 행을 옮기거나 합쳐야 한다(서비스가 한다).
     * 여기는 <b>키가 같은데 보이는 글자만 다른</b> 경우다.
     *
     * <p>사람이 손으로 고친 것이므로 출처를 {@code OCR_CORRECTED} 로 올린다 — 실사용
     * 정확도를 볼 때 "모델이 읽은 그대로"와 "사람이 고친 것"이 구분돼야 한다.
     * {@code MANUAL} 은 애초에 사람이 넣은 것이라 그대로 둔다.
     */
    public void rename(String productName) {
        this.productName = productName;
        if ("OCR".equals(this.sourceType)) {
            this.sourceType = "OCR_CORRECTED";
        }
    }

    /** 구성비의 분모가 될 값 (ERD §1.9 — {@code COALESCE(billed, gross)}). */
    public BigDecimal shareBasisAmount() {
        return billedAmount != null ? billedAmount : grossAmount;
    }

    public Long getId() { return id; }
    @Override public Long getBusinessId() { return businessId; }
    public Long getExtractionId() { return extractionId; }
    public LocalDate getPeriodStart() { return periodStart; }
    public LocalDate getPeriodEnd() { return periodEnd; }
    public String getProductName() { return productName; }
    public String getProductKey() { return productKey; }
    public BigDecimal getQuantity() { return quantity; }
    public BigDecimal getGrossAmount() { return grossAmount; }
    public BigDecimal getDiscountAmount() { return discountAmount; }
    public BigDecimal getNetAmount() { return netAmount; }
    public BigDecimal getVatAmount() { return vatAmount; }
    public BigDecimal getBilledAmount() { return billedAmount; }
    public String getSourceType() { return sourceType; }
    public String getChecksumStatus() { return checksumStatus; }
}
