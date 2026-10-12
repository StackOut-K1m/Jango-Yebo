package com.jangoyebo.core.transaction;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 분류가 바뀐 이력 (ERD {@code TRANSACTION_CLASSIFICATION_HISTORY}).
 *
 * <p><b>append-only 다.</b> {@link com.jangoyebo.common.entity.BaseEntity} 를 상속하지 않는
 * 이유가 이것이다 — 수정되지 않는 것이 설계 의도이므로 {@code updated_at} 이 없다.
 *
 * <p>두 가지 용도가 있다.
 *
 * <ol>
 *   <li><b>few-shot 원천</b> — {@code classifier_type = 'USER'} 행이 LLM 프롬프트의 예시가 된다
 *       (API 20.3). 부분 인덱스 {@code ix_tch_fewshot} 가 그 조회를 받친다.</li>
 *   <li><b>정확도 추적</b> — LLM 이 제안한 것을 사용자가 어떻게 고쳤는지가 여기 남는다.</li>
 * </ol>
 *
 * <p>{@code reason} 이 JSONB 라 <b>모델이 준 부가 정보를 여기 함께 담는다.</b> 특히
 * {@code merchantName} 은 아직 저장할 테이블이 없는데({@code MERCHANT} 는 P1-01 2단계)
 * {@code JOB.result} 는 90일 정리 대상이라(공통규약 23장) 거기 두면 <b>영구 손실</b>이다.
 * LLM 이력은 어차피 남으므로 키 하나 더 넣는 비용이 0 이고, 나중에 거래처 사전을 채울
 * 원천이 된다(합의 ③).
 */
@Entity
@Table(name = "transaction_classification_history", schema = "core")
public class TransactionClassificationHistory {

    /** {@code reason} JSONB 안에서 모델이 준 설명이 들어가는 키. */
    public static final String KEY_TEXT = "text";

    /** 거래처 사전(P1-01 2단계)을 채울 원천. */
    public static final String KEY_MERCHANT_NAME = "merchantName";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "transaction_id", nullable = false, updatable = false)
    private Long transactionId;

    @Column(name = "previous_category_id", updatable = false)
    private Long previousCategoryId;

    @Column(name = "new_category_id", updatable = false)
    private Long newCategoryId;

    /** 시스템 변경(규칙·사전·LLM)이면 null. */
    @Column(name = "changed_by", updatable = false)
    private Long changedBy;

    @Enumerated(EnumType.STRING)
    @Column(name = "classifier_type", nullable = false, updatable = false, length = 20)
    private ClassifierType classifierType;

    @Enumerated(EnumType.STRING)
    @Column(name = "previous_status", nullable = false, updatable = false, length = 20)
    private ClassificationStatus previousStatus;

    @Enumerated(EnumType.STRING)
    @Column(name = "new_status", nullable = false, updatable = false, length = 20)
    private ClassificationStatus newStatus;

    @Column(updatable = false)
    private BigDecimal confidence;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(updatable = false)
    private Map<String, Object> reason;

    @org.hibernate.annotations.CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected TransactionClassificationHistory() {
    }

    /**
     * LLM 이 분류했다 (API 20.3 콜백).
     *
     * <p><b>{@code newStatus} 를 받는다 — 여기서 정하지 않는다.</b> 신뢰도가 기준 이상이면
     * 거래가 {@code CONFIRMED} 로 가므로(신뢰도 기준은 설정값이다) 이 클래스가 상태를 고정하면
     * <b>이력이 실제와 다른 값을 기록한다.</b> 이 표는 "무엇이 어떻게 바뀌었나"의 정본이라,
     * 틀리면 나중에 되짚을 근거가 없어진다. 호출부가 엔티티를 바꾼 <b>뒤</b>에 읽어서 넘긴다.
     *
     * @param reasonText   모델이 준 설명. 없으면 키를 넣지 않는다
     * @param merchantName 모델이 추정한 거래처. 없으면 키를 넣지 않는다 —
     *                     {@code null} 을 담으면 "추정하지 못함"과 "키가 없음"이 섞인다
     */
    public static TransactionClassificationHistory byLlm(Long transactionId,
                                                         Long previousCategoryId,
                                                         Long newCategoryId,
                                                         ClassificationStatus previousStatus,
                                                         ClassificationStatus newStatus,
                                                         BigDecimal confidence,
                                                         String reasonText,
                                                         String merchantName) {
        TransactionClassificationHistory h = new TransactionClassificationHistory();
        h.transactionId = transactionId;
        h.previousCategoryId = previousCategoryId;
        h.newCategoryId = newCategoryId;
        // 시스템 변경이라 changedBy 는 null 이다.
        h.classifierType = ClassifierType.LLM;
        h.previousStatus = previousStatus;
        h.newStatus = newStatus;
        h.confidence = confidence;
        h.reason = reasonOf(reasonText, merchantName);
        return h;
    }

    /**
     * 거래처 사전이 분류했다 (명세 8.5 사전 단계).
     *
     * <p>{@code changedBy} 도 {@code confidence} 도 없다 — 시스템이 한 일이고 사전은 모델이
     * 아니다. 남기는 이유는 <b>이 분류가 어디서 왔는지</b>를 나중에 가릴 수 있어야 해서다.
     * few-shot 예시로는 쓰이지 않는다({@code classifier_type = 'USER'} 만 간다).
     *
     * <p>{@code newStatus} 를 받는 이유는 {@link #byLlm} 과 같다 — 사용자 확정 출처 항목이
     * 분류하면 {@code CONFIRMED}, 모델 출처면 {@code SUGGESTED} 다.
     */
    public static TransactionClassificationHistory byDictionary(Long transactionId,
                                                                 Long previousCategoryId,
                                                                 Long newCategoryId,
                                                                 ClassificationStatus previousStatus,
                                                                 ClassificationStatus newStatus,
                                                                 String reasonText) {
        TransactionClassificationHistory h = new TransactionClassificationHistory();
        h.transactionId = transactionId;
        h.previousCategoryId = previousCategoryId;
        h.newCategoryId = newCategoryId;
        h.classifierType = ClassifierType.DICTIONARY;
        h.previousStatus = previousStatus;
        h.newStatus = newStatus;
        h.reason = reasonOf(reasonText, null);
        return h;
    }

    /**
     * 사용자가 분류를 확정·정정했다 (API 8.3).
     *
     * <p>이 행이 곧 few-shot 예시의 원천이다 — <b>남기지 않으면 LLM 이 같은 거래처를 계속
     * 틀린다.</b>
     */
    public static TransactionClassificationHistory byUser(Long transactionId,
                                                          Long previousCategoryId,
                                                          Long newCategoryId,
                                                          ClassificationStatus previousStatus,
                                                          Long userId) {
        TransactionClassificationHistory h = new TransactionClassificationHistory();
        h.transactionId = transactionId;
        h.previousCategoryId = previousCategoryId;
        h.newCategoryId = newCategoryId;
        h.changedBy = userId;
        h.classifierType = ClassifierType.USER;
        h.previousStatus = previousStatus;
        h.newStatus = ClassificationStatus.CONFIRMED;
        // 사용자 확정에 신뢰도는 없다. 0 으로 두면 "확신도 0%로 확정"과 구분되지 않는다.
        return h;
    }

    private static Map<String, Object> reasonOf(String text, String merchantName) {
        Map<String, Object> reason = new LinkedHashMap<>();
        if (text != null && !text.isBlank()) {
            reason.put(KEY_TEXT, text);
        }
        if (merchantName != null && !merchantName.isBlank()) {
            reason.put(KEY_MERCHANT_NAME, merchantName);
        }
        return reason.isEmpty() ? null : reason;
    }

    public Long getId() { return id; }
    public Long getTransactionId() { return transactionId; }
    public Long getPreviousCategoryId() { return previousCategoryId; }
    public Long getNewCategoryId() { return newCategoryId; }
    public Long getChangedBy() { return changedBy; }
    public ClassifierType getClassifierType() { return classifierType; }
    public ClassificationStatus getPreviousStatus() { return previousStatus; }
    public ClassificationStatus getNewStatus() { return newStatus; }
    public BigDecimal getConfidence() { return confidence; }
    public Map<String, Object> getReason() { return reason; }
    public Instant getCreatedAt() { return createdAt; }
}
