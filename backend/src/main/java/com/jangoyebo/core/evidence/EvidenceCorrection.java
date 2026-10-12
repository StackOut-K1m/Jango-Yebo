package com.jangoyebo.core.evidence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

/**
 * 사용자가 추출값을 고친 기록 (ERD {@code EVIDENCE_CORRECTION}, API 13.3, 합의 ⑦).
 *
 * <p><b>실사용 정확도를 얻는 유일한 방법이다.</b> 실험 세트 정확도(KORIE 359장)는 <i>우리가 고른
 * 이미지</i>에 대한 값이라, 상호명 91.3%가 모델 탓인지 라벨 노이즈 탓인지 그 안에서는 판정되지
 * 않는다. 사장님이 실제로 무엇을 고쳤는지는 여기서만 나온다.
 *
 * <p>{@link #field} 는 <b>계약 필드 키</b>다 — {@code totalAmount}·{@code supplierName} 처럼
 * 13.2 응답의 이름을 그대로 쓰고, 품목은 {@code items[3].amount} 처럼 <b>0-based</b> 행 인덱스를
 * 포함한다. 워커 분석기({@code experiments/analyze_corrections.py})가 그 형태를 전제로
 * {@code items[N].*} 를 {@code items} 하나로 묶어 채점하므로 <b>이름을 바꾸면 분석이 깨진다.</b>
 *
 * <p><b>분자만 여기 쌓인다.</b> 분모는 {@link EvidenceExtraction#getConfirmedAt()} 이다 —
 * 고친 것이 없어도 확인은 기록해야 "고칠 것이 없던 추출"과 "아직 안 본 추출"이 구분된다.
 * 그 구분이 없으면 정확도가 실제보다 높게 나온다.
 */
@Entity
@Table(name = "evidence_correction", schema = "core")
public class EvidenceCorrection {

    /** {@code field VARCHAR(100)}. 품목 인덱스가 붙어도 넘지 않지만 자르는 쪽이 안전하다. */
    private static final int FIELD_LIMIT = 100;

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "extraction_id", nullable = false, updatable = false)
    private Long extractionId;

    @Column(nullable = false, length = FIELD_LIMIT)
    private String field;

    /** null 이면 모델이 못 읽은 것, {@link #afterValue} 가 null 이면 사용자가 지운 것이다. */
    @Column(name = "before_value", columnDefinition = "text")
    private String beforeValue;

    @Column(name = "after_value", columnDefinition = "text")
    private String afterValue;

    @Column(name = "corrected_at", nullable = false, updatable = false)
    private Instant correctedAt = Instant.now();

    protected EvidenceCorrection() {
    }

    private EvidenceCorrection(Long extractionId, String field, String before, String after) {
        this.extractionId = extractionId;
        this.field = field.length() <= FIELD_LIMIT ? field : field.substring(0, FIELD_LIMIT);
        this.beforeValue = before;
        this.afterValue = after;
    }

    /**
     * 바뀐 것만 만든다.
     *
     * @return 값이 같으면 {@code null}. <b>안 바뀐 것을 넣으면 분자가 부풀어 정확도가 실제보다
     *         낮게 나온다</b> — DB 의 {@code ck_correction_changed} 도 같은 것을 막지만,
     *         거기까지 가면 검토 저장 전체가 실패한다
     */
    public static EvidenceCorrection changed(Long extractionId, String field,
                                             String before, String after) {
        if (java.util.Objects.equals(before, after)) {
            return null;
        }
        return new EvidenceCorrection(extractionId, field, before, after);
    }

    public Long getId() { return id; }
    public Long getExtractionId() { return extractionId; }
    public String getField() { return field; }
    public String getBeforeValue() { return beforeValue; }
    public String getAfterValue() { return afterValue; }
    public Instant getCorrectedAt() { return correctedAt; }
}
