package com.jangoyebo.core.evidence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.math.BigDecimal;
import java.util.Map;

/**
 * 증빙에서 읽어낸 품목 행 (ERD {@code EVIDENCE_ITEM}, API 13.2, P0-10 ②).
 *
 * <p><b>에누리를 음수 행으로 만들지 않는다</b>(합의 ②). 인쇄된 금액을 {@link #amount} 에 그대로
 * 두고 할인은 {@link #discountAmount} 에 <b>양수</b>로 담는다. 순액은
 * {@code amount - COALESCE(discountAmount, 0)} 이고 계산은 백엔드가 한다.
 * 음수 행을 허용하면 수량×단가와 금액의 관계가 행마다 달라져 검산이 성립하지 않는다.
 *
 * <p><b>⚠ {@code V6} 주석 두 줄이 낡았다. 여기가 현재 판정이다.</b> 마이그레이션은 서버에
 * 적용돼 동결이고 Flyway 체크섬이 주석까지 포함하므로 한 글자도 고칠 수 없다 —
 * {@code V5} 가 {@code V1} 헤더를 두고 같은 판단을 했다. 아래 둘은 그 정정이다.
 *
 * <p><b>{@link #supplyAmount}·{@link #vatAmount} 는 항상 null 이다.</b> {@code V6} 는 "세금계산서·
 * 거래명세서용" 이라 적었지만 <b>계약의 {@code items[]} 에 그 필드가 아예 없다</b> — 문서 종류와
 * 무관하게 안 온다. 부록 A 가 "당장은 NULL 로 남긴다" 고 권한 그대로이고, {@code OCR_PIPELINE}
 * 공급자를 붙여 행별 공급가·세액을 뽑게 되는 날 채워진다. <b>문서가 세금계산서면 채워진다고
 * 읽으면 안 된다</b> — 그 전제로 화면을 만들면 빈 칸만 그리게 된다.
 *
 * <p><b>{@link #sourceCoordinates} 도 항상 null 이다.</b> {@code V6} 는 "{@code STUB} 만 채운다"
 * 고 적었지만 <b>스텁도 null 로 맞췄다</b>(AI 2번 회신, 2026-09-10) — live·stub 모두 null 이다.
 * VLM 좌표를 실측했더니 중심 적중 33% · IoU≥0.5 9% 라 쓸 수 없었다(값 정확도는 99%).
 * 틀린 하이라이트는 없는 것보다 나쁘다. 컬럼을 남긴 것은 {@code OCR_PIPELINE} 공급자를 붙이면
 * 실제 값이 들어오기 때문이다(합의 ⑥ — 완료 기준은 팀 판단 대기).
 */
@Entity
@Table(name = "evidence_item", schema = "core")
public class EvidenceItem {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "extraction_id", nullable = false, updatable = false)
    private Long extractionId;

    /** 품목 마스터 매핑. 정규화(20.5)가 붙기 전이거나 못 붙였으면 null. */
    @Column(name = "item_id")
    private Long itemId;

    @Column(name = "raw_name", nullable = false, length = 255)
    private String rawName;

    @Column
    private BigDecimal quantity;

    @Column(length = 20)
    private String unit;

    @Column(name = "unit_price")
    private BigDecimal unitPrice;

    /** 인쇄된 행 금액(할인 전). */
    @Column
    private BigDecimal amount;

    /** 행 에누리. 양수다. */
    @Column(name = "discount_amount")
    private BigDecimal discountAmount;

    @Column(name = "supply_amount")
    private BigDecimal supplyAmount;

    @Column(name = "vat_amount")
    private BigDecimal vatAmount;

    /**
     * {@code RULE} · {@code SIMILARITY} · {@code LLM}. 미매칭은 null.
     *
     * <p>기술스택 §6.5 의 <b>"규칙이 90% 이상을 처리한다"</b> 는 발표 수치가 이 값의 집계다.
     * 로그로 세지 않는 이유는 명세 20.1 이 품목명을 로그에 남기지 못하게 해서 재집계·검증이
     * 불가능하기 때문이다(합의 ④).
     */
    @Column(name = "matched_by", length = 12)
    private String matchedBy;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "source_coordinates")
    private Map<String, Object> sourceCoordinates;

    protected EvidenceItem() {
    }

    private EvidenceItem(Long extractionId, String rawName, BigDecimal quantity, String unit,
                         BigDecimal unitPrice, BigDecimal amount, BigDecimal discountAmount,
                         BigDecimal supplyAmount, BigDecimal vatAmount,
                         Map<String, Object> sourceCoordinates) {
        this.extractionId = extractionId;
        this.rawName = rawName;
        this.quantity = quantity;
        this.unit = unit;
        this.unitPrice = unitPrice;
        this.amount = amount;
        this.discountAmount = discountAmount;
        this.supplyAmount = supplyAmount;
        this.vatAmount = vatAmount;
        this.sourceCoordinates = sourceCoordinates;
    }

    public static EvidenceItem of(Long extractionId, String rawName, BigDecimal quantity,
                                  String unit, BigDecimal unitPrice, BigDecimal amount,
                                  BigDecimal discountAmount, BigDecimal supplyAmount,
                                  BigDecimal vatAmount, Map<String, Object> sourceCoordinates) {
        return new EvidenceItem(extractionId, rawName, quantity, unit, unitPrice, amount,
                discountAmount, supplyAmount, vatAmount, sourceCoordinates);
    }

    /**
     * 사용자가 검토 화면에서 이 행을 고쳤다 (13.3).
     *
     * <p><b>null 은 "안 보냄"이지 "지움"이 아니다.</b> PATCH 는 보낸 필드만 바꾼다 —
     * 화면이 품목 표에서 수량만 고쳐 보내는 것이 정상 동선이고, 나머지를 null 로 읽으면
     * 모델이 제대로 읽은 값이 저장에서 지워진다.
     *
     * <p>{@link #sourceCoordinates} 는 손대지 않는다. 사용자가 값을 고쳐도 모델이 가리켰던
     * 자리는 그대로 남겨야 나중에 좌표 품질을 되짚을 수 있다.
     */
    public void applyUserEdit(String rawName, BigDecimal quantity, String unit,
                              BigDecimal unitPrice, BigDecimal amount, BigDecimal discountAmount) {
        if (rawName != null && !rawName.isBlank()) this.rawName = rawName;
        if (quantity != null) this.quantity = quantity;
        if (unit != null) this.unit = unit;
        if (unitPrice != null) this.unitPrice = unitPrice;
        if (amount != null) this.amount = amount;
        if (discountAmount != null) this.discountAmount = discountAmount;
    }

    /** 품목 정규화(20.5)가 매핑을 확정했다. */
    public void matchTo(Long itemId, String matchedBy) {
        this.itemId = itemId;
        this.matchedBy = matchedBy;
    }

    /** 순액. 화면과 세무 집계가 쓰는 값이라 여기서 한 번만 정의한다. */
    public BigDecimal netAmount() {
        if (amount == null) {
            return null;
        }
        return discountAmount == null ? amount : amount.subtract(discountAmount);
    }

    public Long getId() { return id; }
    public Long getExtractionId() { return extractionId; }
    public Long getItemId() { return itemId; }
    public String getRawName() { return rawName; }
    public BigDecimal getQuantity() { return quantity; }
    public String getUnit() { return unit; }
    public BigDecimal getUnitPrice() { return unitPrice; }
    public BigDecimal getAmount() { return amount; }
    public BigDecimal getDiscountAmount() { return discountAmount; }
    public BigDecimal getSupplyAmount() { return supplyAmount; }
    public BigDecimal getVatAmount() { return vatAmount; }
    public String getMatchedBy() { return matchedBy; }
    public Map<String, Object> getSourceCoordinates() { return sourceCoordinates; }
}
