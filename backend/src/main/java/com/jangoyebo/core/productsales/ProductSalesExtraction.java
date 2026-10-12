package com.jangoyebo.core.productsales;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/**
 * 매출현황 인쇄물 추출 1회분 (API 20.9, P1-22, 합의 ④).
 *
 * <p><b>상품 행은 여기 없다.</b> 14.7 이 "OCR 결과는 후보이며 저장하지 않는다"고 못박았고 그
 * 결정은 그대로다 — 행은 사용자가 검토·확정한 뒤 14.4 가 {@code PRODUCT_SALES_PERIOD} 에
 * 쓴다. 여기 남는 것은 <b>모델이 무엇을 어떻게 읽었나</b>(기간·읽은 열·합계 행·검산·모델 메타)다.
 *
 * <p>그래도 남기는 이유는 수명이다. 추출 결과는 {@code JOB.result} JSONB 에만 있었는데 Job 은
 * 90일 뒤 정리 대상이라(공통규약 23장) <b>검산 근거가 Job 과 함께 사라진다.</b> 사용자가 저장한
 * 행이 나중에 틀린 것으로 드러났을 때 "모델이 그때 무엇을 읽었고 검산이 무엇을 걸렀나"에
 * 답할 수 있어야 한다.
 *
 * <p><b>{@link #validation} 은 {@code checksum_status} 와 다른 검사다.</b> 하나를 다른 하나로
 * 대신할 수 없다.
 *
 * <table>
 *   <tr><th></th><th>무엇을 보나</th><th>누가</th></tr>
 *   <tr><td>{@code checksum_status}</td><td>Σ항목 vs 합계 행 — <b>행이 빠졌나</b></td><td>백엔드</td></tr>
 *   <tr><td>{@link #validation}</td><td><b>행 안의</b> 산술 4식 + 합계 행 자체의 정합</td><td>워커(v1.18)</td></tr>
 * </table>
 *
 * <p>워커 검산에 {@code Σ항목 = 합계 행} 이 <b>없는 것은 의도</b>다 — 실측 90표본 중 20개(22.2%)가
 * 어긋나고 그것이 정상이기 때문이다(인쇄물이 여러 장으로 잘리면 합계 행은 기간 전체를 담고
 * 촬영된 장에는 일부 행만 보인다). 넣었으면 정상 인쇄물의 22%를 틀렸다고 잡았을 것이다.
 */
@Entity
@Table(name = "product_sales_extraction", schema = "core")
public class ProductSalesExtraction {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /**
     * 사업장. <b>콜백에는 이 값이 없다</b>(합의 ⑦) — {@code PRODUCT_SALES_OCR} Job 이 사업장
     * 단위로 만들어지므로 Job 이 곧 사업장이다. 증빙 콜백이 {@code evidenceId} 를 싣는 것과
     * 비대칭이지만, 워커가 되돌려 보내는 값은 어차피 검증에 쓰이지 않는다.
     */
    @Column(name = "business_id", nullable = false, updatable = false)
    private Long businessId;

    @Column(name = "job_id", updatable = false)
    private Long jobId;

    /**
     * 인쇄물 헤더에서 읽은 집계 기간. <b>없으면 null 이고 그것이 정상 동작이다</b>(요구 1).
     *
     * <p>촬영 시각으로 추론하지 않는다 — 12월 중순에 뽑은 반달치를 그 달 전체로 저장하면
     * <b>오류 없이 틀린 답</b>이 나온다. 검토 화면이 인식된 기간을 가장 크게 보여주고 확인받는다.
     */
    @Column(name = "period_start")
    private LocalDate periodStart;

    @Column(name = "period_end")
    private LocalDate periodEnd;

    /** {@code HEADER} 또는 null. 워커는 추론하지 않으므로 {@code USER} 는 여기 오지 않는다. */
    @Column(name = "period_source", length = 10)
    private String periodSource;

    /** {@code SCREEN} · {@code PRINTOUT}. 프롬프트와 기대 정확도가 다르다. */
    @Column(name = "capture_type", nullable = false, length = 10)
    private String captureType;

    @Column(name = "model_name", nullable = false, length = 100)
    private String modelName;

    /** 워커는 항상 null 을 보낸다. 재현성의 실제 키는 {@link #promptVersion} 이다. */
    @Column(name = "model_version", length = 100)
    private String modelVersion;

    @Column(name = "prompt_version", length = 100)
    private String promptVersion;

    /** {@code OCR_PIPELINE} · {@code VLM} · {@code STUB}. */
    @Column(nullable = false, length = 20)
    private String pipeline;

    /** {@code COMPLETED} · {@code PARTIAL}. {@code PARTIAL} 을 실패로 접지 않는다(20.2). */
    @Column(nullable = false, length = 20)
    private String status;

    /**
     * 인쇄물에 실제로 있던 열 (요구 3).
     *
     * <p><b>없는 열을 0 으로 채우지 않기 위한 근거다.</b> 58mm 감열지는 상품명·수량·결제금액·
     * 구성비 4열만 남고 총매출 열이 통째로 빠진다(실측 30표본 전부).
     */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "columns_detected")
    private List<String> columnsDetected;

    /** 합계 행 원본 (요구 2). 14.4 가 항목 합과 대조해 {@code checksumStatus} 를 만든다. */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "printed_total")
    private Map<String, Object> printedTotal;

    /** 워커 {@code data.validation} 을 가공 없이. 클래스 주석의 표를 볼 것. */
    @JdbcTypeCode(SqlTypes.JSON)
    private Map<String, Object> validation;

    @JdbcTypeCode(SqlTypes.JSON)
    private List<String> warnings;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    protected ProductSalesExtraction() {
    }

    private ProductSalesExtraction(Long businessId, Long jobId, LocalDate periodStart,
                                   LocalDate periodEnd, String periodSource, String captureType,
                                   String modelName, String modelVersion, String promptVersion,
                                   String pipeline, String status, List<String> columnsDetected,
                                   Map<String, Object> printedTotal,
                                   Map<String, Object> validation, List<String> warnings) {
        this.businessId = businessId;
        this.jobId = jobId;
        this.periodStart = periodStart;
        this.periodEnd = periodEnd;
        this.periodSource = periodSource;
        this.captureType = captureType;
        this.modelName = modelName;
        this.modelVersion = modelVersion;
        this.promptVersion = promptVersion;
        this.pipeline = pipeline;
        this.status = status;
        this.columnsDetected = columnsDetected;
        this.printedTotal = printedTotal;
        this.validation = validation;
        this.warnings = warnings;
    }

    public static ProductSalesExtraction of(Long businessId, Long jobId, LocalDate periodStart,
                                            LocalDate periodEnd, String periodSource,
                                            String captureType, String modelName,
                                            String modelVersion, String promptVersion,
                                            String pipeline, String status,
                                            List<String> columnsDetected,
                                            Map<String, Object> printedTotal,
                                            Map<String, Object> validation,
                                            List<String> warnings) {
        return new ProductSalesExtraction(businessId, jobId, periodStart, periodEnd, periodSource,
                captureType, modelName, modelVersion, promptVersion, pipeline, status,
                columnsDetected, printedTotal, validation, warnings);
    }

    /** 검산이 걸렸나. 검토 화면의 <b>정렬·강조</b> 신호이며 진입 게이트가 아니다(합의 ③). */
    public boolean needsReview() {
        return validation != null && Boolean.TRUE.equals(validation.get("needsReview"));
    }

    public Long getId() { return id; }
    public Long getBusinessId() { return businessId; }
    public Long getJobId() { return jobId; }
    public LocalDate getPeriodStart() { return periodStart; }
    public LocalDate getPeriodEnd() { return periodEnd; }
    public String getPeriodSource() { return periodSource; }
    public String getCaptureType() { return captureType; }
    public String getModelName() { return modelName; }
    public String getModelVersion() { return modelVersion; }
    public String getPromptVersion() { return promptVersion; }
    public String getPipeline() { return pipeline; }
    public String getStatus() { return status; }
    public List<String> getColumnsDetected() { return columnsDetected; }
    public Map<String, Object> getPrintedTotal() { return printedTotal; }
    public Map<String, Object> getValidation() { return validation; }
    public List<String> getWarnings() { return warnings; }
    public Instant getCreatedAt() { return createdAt; }
}
