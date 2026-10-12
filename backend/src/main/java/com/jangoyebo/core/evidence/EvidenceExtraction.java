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
import java.time.Instant;
import java.util.Map;

/**
 * AI 추출 1회분 (ERD {@code EVIDENCE_EXTRACTION}, API 20.4, P0-10 ②).
 *
 * <p>여기까지 오기 전에는 워커 결과가 {@code JOB.result} JSONB 에만 들어갔다. Job 은 90일 뒤
 * 정리 대상이라 그대로 두면 <b>추출 결과가 Job 과 함께 사라진다.</b>
 *
 * <p><b>헤더 필드를 컬럼으로 펴지 않는다.</b> {@code headerData} 에 워커 {@code data} 를 그대로
 * 넣는다(합의 ①). 확장 필드가 계속 늘어나는데 — {@code taxableAmount}·{@code taxFreeAmount} 가
 * v1.14 에, {@code validation.vatRatioConsistent} 가 v1.15 에 붙었다 — 그때마다 컬럼을 파면
 * 마이그레이션이 계약 개정마다 따라붙는다. 화면이 쓰는 것만 응답에서 꺼내면 된다.
 *
 * <p>⚠ {@code modelVersion} 은 <b>항상 null 로 온다</b>. Anthropic 응답의 {@code model} 이 이미
 * 고정 식별자라 별도 버전이 없다. "언제부터 정확도가 떨어졌나"에 답하는 값은
 * {@link #promptVersion} 이다 — 프롬프트 v1→v2 에서 상호명 정확도가 80.8% → 97.9% 로 움직였다.
 */
@Entity
@Table(name = "evidence_extraction", schema = "core")
public class EvidenceExtraction {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "evidence_id", nullable = false, updatable = false)
    private Long evidenceId;

    @Column(name = "model_name", nullable = false, length = 100)
    private String modelName;

    @Column(name = "model_version", length = 100)
    private String modelVersion;

    @Column(name = "prompt_version", length = 100)
    private String promptVersion;

    /** {@code OCR_PIPELINE} · {@code VLM} · {@code STUB}. 문자열로 두는 것은 CHECK 이 정본이라서다. */
    @Column(nullable = false, length = 20)
    private String pipeline;

    /**
     * {@code COMPLETED} · {@code PARTIAL}.
     *
     * <p>{@code PARTIAL} 을 실패로 접지 않는다(명세 20.2) — 쓸 수 있는 결과를 버리게 된다.
     */
    @Column(nullable = false, length = 20)
    private String status;

    /**
     * 이 결과를 어느 모드로 뽑았나 (워커 계약 20.4 {@code extractionMode}).
     *
     * <p>콜백 본문에는 없다 — 요청 필드라 Job 파라미터에서 가져온다. {@code null} 은
     * 「모드가 없었다」가 아니라 「V17 이전에 쌓인 행이다」라는 뜻이다.
     */
    @Column(name = "extraction_mode", length = 20)
    private String extractionMode;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "header_data")
    private Map<String, Object> headerData;

    @Column
    private BigDecimal confidence;

    /** 증빙당 하나만 true. 부분 유일 인덱스 {@code uk_extraction_current} 가 강제한다. */
    @Column(name = "is_current", nullable = false)
    private boolean current = true;

    /**
     * 사용자가 확정한 시각. <b>고친 것이 없어도 채운다</b>(합의 ⑦) — 안 채우면
     * "고친 적 없는 추출"과 "아직 안 본 추출"이 구분되지 않아 정확도가 실제보다 높게 나온다.
     */
    @Column(name = "confirmed_at")
    private Instant confirmedAt;

    @Column(name = "confirmed_by")
    private Long confirmedBy;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    protected EvidenceExtraction() {
    }

    private EvidenceExtraction(Long evidenceId, String modelName, String modelVersion,
                               String promptVersion, String pipeline, String status,
                               String extractionMode, Map<String, Object> headerData,
                               BigDecimal confidence) {
        this.evidenceId = evidenceId;
        this.modelName = modelName;
        this.modelVersion = modelVersion;
        this.promptVersion = promptVersion;
        this.pipeline = pipeline;
        this.status = status;
        this.extractionMode = extractionMode;
        this.headerData = headerData;
        this.confidence = confidence;
    }

    public static EvidenceExtraction of(Long evidenceId, String modelName, String modelVersion,
                                        String promptVersion, String pipeline, String status,
                                        String extractionMode, Map<String, Object> headerData,
                                        BigDecimal confidence) {
        return new EvidenceExtraction(evidenceId, modelName, modelVersion, promptVersion,
                pipeline, status, extractionMode, headerData, confidence);
    }

    /**
     * 재추출이 들어왔다. 지우지 않고 내린다 — 이전 결과가 무엇이었는지가
     * 정확도 추적의 재료다(합의 ⑦).
     */
    public void supersede() {
        this.current = false;
    }

    public void confirm(Long userId, Instant at) {
        this.confirmedBy = userId;
        this.confirmedAt = at;
    }

    /**
     * 사용자가 고친 헤더 값과 다시 돌린 검산을 반영한다 (13.3).
     *
     * <p><b>통째로 갈아 끼운다.</b> 워커 {@code data} 를 그대로 담는 칸이라 부분 병합을 하면
     * 어느 키가 모델 값이고 어느 키가 사용자 값인지 이 안에서는 구분되지 않는다 — 그 구분은
     * {@code EVIDENCE_CORRECTION} 이 필드별 before/after 로 가지고 있고, 그쪽이 정본이다.
     */
    public void replaceHeader(Map<String, Object> headerData) {
        this.headerData = headerData;
    }

    public Long getId() { return id; }
    public Long getEvidenceId() { return evidenceId; }
    public String getModelName() { return modelName; }
    public String getModelVersion() { return modelVersion; }
    public String getPromptVersion() { return promptVersion; }
    public String getPipeline() { return pipeline; }
    public String getStatus() { return status; }
    public String getExtractionMode() { return extractionMode; }
    public Map<String, Object> getHeaderData() { return headerData; }
    public BigDecimal getConfidence() { return confidence; }
    public boolean isCurrent() { return current; }
    public Instant getConfirmedAt() { return confirmedAt; }
    public Long getConfirmedBy() { return confirmedBy; }
    public Instant getCreatedAt() { return createdAt; }
}
