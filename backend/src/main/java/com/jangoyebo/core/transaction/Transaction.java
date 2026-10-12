package com.jangoyebo.core.transaction;

import com.jangoyebo.common.domain.FlowType;
import com.jangoyebo.common.entity.BaseEntity;
import com.jangoyebo.common.tenancy.BusinessScoped;
import jakarta.persistence.*;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

/**
 * 실제 거래 원본 (ERD {@code TRANSACTION}).
 *
 * <p>수집 경로가 둘이고 <b>중복 방지 키가 다르다</b>:
 * 금융망은 {@code externalTransactionId}(계좌 내 유일한 거래고유번호),
 * CSV는 {@code deduplicationKey}(거래일시·금액·적요 해시). DB에 부분 유일 인덱스가 각각 걸려 있다.
 *
 * <p>물리 삭제하지 않는다. 제외는 {@code isExcluded} 로 표시한다(ERD 11장).
 */
@Entity
@Table(name = "transaction", schema = "core")
public class Transaction extends BaseEntity implements BusinessScoped {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "business_id", nullable = false, updatable = false)
    private Long businessId;

    @Column(name = "account_id", nullable = false, updatable = false)
    private Long accountId;

    @Column(name = "category_id")
    private Long categoryId;

    @Column(name = "external_transaction_id", updatable = false, length = 64)
    private String externalTransactionId;

    @Column(name = "deduplication_key", updatable = false, length = 128)
    private String deduplicationKey;

    @Enumerated(EnumType.STRING)
    @Column(name = "source_type", nullable = false, updatable = false, length = 20)
    private SourceType sourceType;

    @Column(name = "occurred_at", nullable = false, updatable = false)
    private Instant occurredAt;

    @Column(name = "transaction_date", nullable = false, updatable = false)
    private LocalDate transactionDate;

    @Enumerated(EnumType.STRING)
    @Column(name = "flow_type", nullable = false, updatable = false, length = 20)
    private FlowType flowType;

    /** 항상 양수다. 방향은 {@link #flowType} 이 표현한다. */
    @Column(nullable = false)
    private BigDecimal amount;

    @Column(name = "balance_after")
    private BigDecimal balanceAfter;

    /** 금융망 {@code transactionSummary}. 분류의 사실상 유일한 근거다. */
    @Column(length = 255)
    private String description;

    @Column(name = "counterparty_name", length = 100)
    private String counterpartyName;

    @Column(name = "counterparty_account_no", length = 32)
    private String counterpartyAccountNo;

    @Enumerated(EnumType.STRING)
    @Column(name = "classification_status", nullable = false, length = 20)
    private ClassificationStatus classificationStatus;

    @Column(name = "classification_confidence")
    private BigDecimal classificationConfidence;

    @Column(name = "needs_confirmation", nullable = false)
    private boolean needsConfirmation;

    /**
     * 마지막으로 분류한 주체 (API 8.2 {@code classification.source}).
     *
     * <p>정본은 이력 테이블이고 이것은 <b>지금 상태</b> 한 줄이다 — 목록 응답이 행마다 이력을
     * 조인하지 않게 두었다(V13). 「어떻게 바뀌어 왔는가」는 이력에만 있다.
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "classification_source", length = 20)
    private ClassifierType classificationSource;

    /** LLM 이 준 설명. 사용자가 분류를 바꾸면 비운다 — 바뀐 분류에 옛 근거가 붙지 않게. */
    @Column(name = "classification_reason")
    private String classificationReason;

    @Column(name = "is_excluded", nullable = false)
    private boolean excluded;

    /**
     * 종합소득세 검토 상태 (API 8.7). {@link #excluded} 와 독립이다 — 예측 제외와 경비 제외는
     * 다른 판단이다.
     *
     * <p>P0-12 착수 전이라 <b>쓰는 경로가 아직 없다.</b> 여기 매핑해 두는 것은 조회·필터를
     * 먼저 열기 위해서다(API 8.2). 값은 DB 기본값 {@code UNREVIEWED} 로 들어온다.
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "tax_review_status", nullable = false, length = 30)
    private TaxReviewStatus taxReviewStatus;

    /** 세무 검토 메모. 현금흐름용 {@link #memo} 와 분리한다. */
    @Column(name = "tax_review_note")
    private String taxReviewNote;

    /** 사용자가 검토 상태를 확정한 시각. 자동 판정분은 null 이다. */
    @Column(name = "tax_reviewed_at")
    private Instant taxReviewedAt;

    @Column
    private String memo;

    protected Transaction() {
    }

    private Transaction(Long businessId, Long accountId, SourceType sourceType,
                        Instant occurredAt, LocalDate transactionDate,
                        FlowType flowType, BigDecimal amount) {
        this.businessId = businessId;
        this.accountId = accountId;
        this.sourceType = sourceType;
        this.occurredAt = occurredAt;
        this.transactionDate = transactionDate;
        this.flowType = flowType;
        this.amount = amount;
        this.classificationStatus = ClassificationStatus.UNCLASSIFIED;
        this.needsConfirmation = false;
        this.excluded = false;
        // DB default 와 같은 값이지만 여기서도 채운다 — 저장 직후 응답이 null 로 나가지 않게.
        this.taxReviewStatus = TaxReviewStatus.UNREVIEWED;
    }

    /** CSV 업로드로 수집한 거래. */
    public static Transaction fromCsv(Long businessId, Long accountId, Instant occurredAt,
                                      LocalDate transactionDate, FlowType flowType, BigDecimal amount,
                                      String description, BigDecimal balanceAfter, String deduplicationKey) {
        Transaction tx = new Transaction(businessId, accountId, SourceType.CSV,
                occurredAt, transactionDate, flowType, amount);
        tx.description = description;
        tx.balanceAfter = balanceAfter;
        tx.deduplicationKey = deduplicationKey;
        return tx;
    }

    public void classifyByUser(Long categoryId, String memo, Boolean excluded) {
        if (categoryId != null) {
            // 근거를 지울지는 바꾸기 전에 정해야 한다.
            boolean changedCategory = !categoryId.equals(this.categoryId);
            this.categoryId = categoryId;
            this.classificationStatus = ClassificationStatus.CONFIRMED;
            this.classificationConfidence = null;
            this.needsConfirmation = false;
            this.classificationSource = ClassifierType.USER;
            if (changedCategory) {
                // 모델이 준 근거는 모델이 고른 분류에만 유효하다. 남겨 두면 사용자가 임차료로
                // 고친 거래에 "배민 정산 계좌명입니다" 가 붙어 있게 된다.
                //
                // 반대로 제안을 그대로 수락한 것은(8.4) 분류가 그대로라 근거도 그대로 유효하다 —
                // 사장님이 나중에 "이건 왜 임차료였지" 를 볼 자료가 그 문장뿐이다.
                this.classificationReason = null;
            }
        }
        if (memo != null) this.memo = memo;
        if (excluded != null) this.excluded = excluded;
    }

    /**
     * 거래처 사전이 분류했다 (명세 8.5 "규칙 → 거래처 사전 → LLM" 의 사전 단계).
     *
     * <p><b>사전이 잡은 건은 묻지 않고 확정한다.</b> 사전에 들어오는 경로는 둘뿐이고 둘 다
     * 이미 확정 기준을 넘은 것이다 — 사장님이 8.3·8.4 로 확정한 짝이거나, {@code
     * CounterpartyService.rememberSuggestion} 이 담은 짝인데 <b>후자는 신뢰도 0.85 이상인
     * 판단만 담긴다</b>({@code TransactionClassificationService.apply} 의 분기). 그러니 여기서
     * 다시 묻는 것은 {@link #classifyByLlm} 이 방금 확정한 것을 되묻는 셈이다.
     *
     * <p><b>처음에는 제안으로 두었다.</b> "확정한 것은 지난번 거래이지 이 거래가 아니고, 사람이
     * 한 번도 보지 않은 행을 사람이 확정한 것과 같은 상태로 두면 나중에 「내가 이걸
     * 확정했던가」를 가릴 수 없다"는 이유였다. 그 우려는 상태가 아니라
     * {@code classificationSource} 가 답하게 되면서 풀렸다 ({@code DICTIONARY} vs {@code USER}).
     *
     * <p><b>제안으로 두면 한 실행 안에서 같은 적요가 갈린다.</b> 워커가 20건씩 끊어 이어달리므로
     * 첫 배치의 `신한카드 매출대금` 은 LLM 이 확정하고, 둘째 배치부터는 사전이 먼저 잡아
     * 제안이 된다 — 같은 적요가 배치 경계로 갈라진다. 안전장치 구실도 못 한다: 첫 배치는 이미
     * 확정돼 나간 뒤라 틀린 판단은 이미 살아 있고, 묻는 시점만 자의적이다.
     *
     * <p><b>대가</b> — 틀린 0.85 판단이 그 적요 전체로 조용히 퍼진다. 막는 자리는 여기가 아니라
     * 임계값이고, 그 값은 실측으로 정했다({@link #classifyByLlm} 의 구간별 정확도).
     *
     * <p>신뢰도는 비운다. 사전은 모델이 아니라 지난 판단의 재사용이고, 거기에 확률을 붙이면
     * 없는 측정치를 지어내는 것이 된다.
     *
     * @return 적용했으면 {@code true}. 이미 확정된 거래라 건너뛰었으면 {@code false}
     */
    public boolean classifyByDictionary(Long categoryId, String reason) {
        if (this.classificationStatus == ClassificationStatus.CONFIRMED) {
            return false;
        }
        this.categoryId = categoryId;
        this.classificationStatus = ClassificationStatus.CONFIRMED;
        this.classificationConfidence = null;
        this.needsConfirmation = false;
        this.classificationSource = ClassifierType.DICTIONARY;
        this.classificationReason = reason;
        return true;
    }

    /**
     * LLM 이 분류했다 (API 20.3 콜백).
     *
     * <p><b>사용자가 확정한 거래는 덮지 않는다.</b> 워커 요청 하나가 400건 × 15초로 5분 넘게
     * 도는 동안 사용자가 같은 거래를 8.3 으로 확정할 수 있다. 그때 콜백이 도착해 제안으로
     * 되돌리면 <b>방금 사람이 한 판단이 지워진다.</b> 도메인 규칙 4 와 같은 취지이고,
     * {@code Evidence.applyExtraction} 에서 같은 계열의 실수를 한 번 했다.
     *
     * <p><b>신뢰도가 기준 이상이면 바로 확정한다.</b> 612건을 분류해 보니 전부 제안으로 떨어져
     * 사장님이 612번을 눌러야 했다 — 그 수락은 화면을 넘기는 동작일 뿐 판단이 아니고,
     * 그렇게 만든 "확인 완료"는 아무것도 보장하지 않는다. 기준선은 실측으로 정했다(기준
     * 0.85, 설정값 {@code classification.confidence-threshold}):
     *
     * <pre>
     *   신뢰도 구간      건수    정확도
     *   0.95 이상         23     1.0000
     *   0.90 ~ 0.95      198     0.9848
     *   0.85 ~ 0.90       —      1.0000
     *   0.70 ~ 0.80       —      0.9450   ← 절벽
     *   0.70 미만         —      0.8069
     * </pre>
     *
     * <p>기준을 0.90 으로 올리면 정확도가 <b>떨어진다</b>(0.9927 → 0.9864). 오답이 몰린 곳은
     * 0.90~0.95 구간이라, 기준을 올려도 그 오답은 그대로 확정되고 깨끗한 0.85~0.90 구간만
     * 확인 대기로 밀려나기 때문이다. 0.85 는 절벽(0.80) 위에 안전폭을 두고 선 값이다.
     *
     * <p><b>확정해도 사람이 한 것과는 구분된다.</b> {@code classificationSource} 가 {@code LLM}
     * 으로 남는다 — "내가 이걸 확정했던가"는 상태가 아니라 출처로 답한다. 사전에 담는 것도
     * 여전히 {@code CounterpartyService.rememberSuggestion} 쪽이다. 도메인 규칙 3 이 말하는
     * "확정된 매핑"은 사람이 확정한 것이어야 하고, 모델이 스스로 확정한 것을 거기 섞으면
     * 규칙이 자기 자신을 근거로 삼는다.
     *
     * <p><b>대가</b> — 위의 방어 조건 때문에 <b>여기서 확정한 거래는 재실행이 다시 분류하지
     * 않는다.</b> 틀린 확정은 8.3 으로 사람이 고쳐야 풀린다. 재분류로 저절로 낫지 않는다는
     * 뜻이라, 기준을 낮출 때는 이 비용을 같이 본다.
     *
     * <p>{@code needsConfirmation} 을 여기서 판정하지 않는 이유: 임계값이 설정값이고
     * (기본 0.85) 엔티티가 설정을 읽으면 안 된다. 판정은 호출부가 한다.
     *
     * @param confidence 모델 신뢰도. {@code null} 이면 모델이 주지 않은 것이다 — 0 으로
     *                   접으면 "신뢰도 0"과 구분되지 않는다
     * @return 적용했으면 {@code true}. 이미 확정된 거래라 건너뛰었으면 {@code false}
     */
    public boolean classifyByLlm(Long categoryId, BigDecimal confidence, boolean needsConfirmation,
                                 String reason) {
        if (this.classificationStatus == ClassificationStatus.CONFIRMED) {
            return false;
        }
        this.categoryId = categoryId;
        // 확인이 필요 없다는 판정이 곧 확정이다. 둘을 따로 두면 화면에 "제안됨인데 확인은
        // 필요 없는" 세 번째 상태가 생기고, 사용자는 그걸로 무엇을 해야 할지 알 수 없다.
        this.classificationStatus = needsConfirmation
                ? ClassificationStatus.SUGGESTED
                : ClassificationStatus.CONFIRMED;
        this.classificationConfidence = confidence;
        this.needsConfirmation = needsConfirmation;
        this.classificationSource = ClassifierType.LLM;
        this.classificationReason = reason;
        return true;
    }

    public Long getId() { return id; }
    @Override public Long getBusinessId() { return businessId; }
    public Long getAccountId() { return accountId; }
    public Long getCategoryId() { return categoryId; }
    public String getExternalTransactionId() { return externalTransactionId; }
    public SourceType getSourceType() { return sourceType; }
    public Instant getOccurredAt() { return occurredAt; }
    public LocalDate getTransactionDate() { return transactionDate; }
    public FlowType getFlowType() { return flowType; }
    public BigDecimal getAmount() { return amount; }
    public BigDecimal getBalanceAfter() { return balanceAfter; }
    public String getDescription() { return description; }
    public String getCounterpartyName() { return counterpartyName; }
    public String getCounterpartyAccountNo() { return counterpartyAccountNo; }
    public ClassificationStatus getClassificationStatus() { return classificationStatus; }
    public BigDecimal getClassificationConfidence() { return classificationConfidence; }
    public boolean isNeedsConfirmation() { return needsConfirmation; }
    public ClassifierType getClassificationSource() { return classificationSource; }
    public String getClassificationReason() { return classificationReason; }
    public boolean isExcluded() { return excluded; }

    /**
     * 사용자가 검토 상태를 지정한다 (8.7).
     *
     * <p><b>자동 판정 두 값은 여기로 들어오지 않는다.</b> {@code EVIDENCE_CONFIRMED}·
     * {@code EVIDENCE_MISSING} 은 증빙 연결 여부로 기계적으로 정해지는 사실이라 사람이
     * 찍을 값이 아니다 — 손으로 찍게 두면 증빙이 없는데도 「확인 완료」가 되어 내보내기의
     * 검토 상태 열이 거짓말을 한다. 막는 곳은 서비스다.
     *
     * <p>메모는 보낸 대로 덮어쓴다. {@code null} 이면 지운다 — 상태가 바뀌었는데 옛 메모가
     * 남으면 다음 사람이 그 메모를 새 상태의 근거로 읽는다.
     */
    public void reviewForTax(TaxReviewStatus status, String note, Instant at) {
        this.taxReviewStatus = status;
        this.taxReviewNote = note;
        this.taxReviewedAt = at;
    }

    /**
     * 증빙 연결 여부로 자동 판정한다 (8.7).
     *
     * <p><b>사용자가 지정한 값은 덮어쓰지 않는다.</b> 증빙이 뒤늦게 연결돼도
     * {@code EXPERT_REVIEW} 는 유지된다 — 사람이 「세무사 확인 필요」라고 해 둔 것을 서버가
     * 「증빙 확인 완료」로 바꾸면, 확인받지 않은 건이 확인된 것으로 신고 자료에 나간다.
     *
     * <p>그래서 {@code UNREVIEWED} 와 자동 판정 두 값에서만 움직인다.
     */
    public void applyEvidenceJudgement(boolean hasEvidence, Instant at) {
        if (!isAutoJudgeable()) {
            return;
        }
        TaxReviewStatus next = hasEvidence
                ? TaxReviewStatus.EVIDENCE_CONFIRMED
                : TaxReviewStatus.EVIDENCE_MISSING;
        if (this.taxReviewStatus == next) {
            return;
        }
        this.taxReviewStatus = next;
        this.taxReviewedAt = at;
    }

    /** 서버가 건드려도 되는 상태인가. 사람이 손댄 값이면 아니다. */
    private boolean isAutoJudgeable() {
        return taxReviewStatus == null
                || taxReviewStatus == TaxReviewStatus.UNREVIEWED
                || taxReviewStatus == TaxReviewStatus.EVIDENCE_CONFIRMED
                || taxReviewStatus == TaxReviewStatus.EVIDENCE_MISSING;
    }

    public TaxReviewStatus getTaxReviewStatus() { return taxReviewStatus; }
    public String getTaxReviewNote() { return taxReviewNote; }
    public Instant getTaxReviewedAt() { return taxReviewedAt; }
    public String getMemo() { return memo; }
}
