package com.jangoyebo.core.evidence;

import com.jangoyebo.common.entity.BaseEntity;
import com.jangoyebo.common.tenancy.BusinessScoped;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.time.LocalDate;

/**
 * 업로드된 증빙 파일 (ERD {@code EVIDENCE}, API 13.1, P0-10).
 *
 * <p>SRS 가 이 기능을 P0 에 둔 이유가 시점에 있다 — <b>신고 직전에 몰아서 모으면
 * 누락·중복이 생기므로 평소에 축적한다.</b> 그래서 업로드는 거래 연결 없이도 되고,
 * 연결은 나중에(또는 AI 추천으로) 붙인다.
 *
 * <p><b>파일 자체는 DB 에 두지 않는다.</b> {@code storageKey} 로 컨테이너 내부 고정 경로
 * ({@code /data/uploads})의 파일을 가리킨다. 호스트 경로를 코드가 알면 인프라가 마운트
 * 지점을 바꿀 수 없다(인프라 제약 §4.1 #9).
 *
 * <p>{@code uk_evidence_storage} 가 {@code storageKey} 의 유일성을 강제한다 — 같은 키를
 * 두 행이 가리키면 한쪽을 지울 때 다른 쪽의 파일이 사라진다.
 */
@Entity
@Table(name = "evidence", schema = "core")
public class Evidence extends BaseEntity implements BusinessScoped {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "business_id", nullable = false, updatable = false)
    private Long businessId;

    @Column(name = "uploaded_by", nullable = false, updatable = false)
    private Long uploadedBy;

    @Enumerated(EnumType.STRING)
    @Column(name = "evidence_type", nullable = false, length = 30)
    private EvidenceType evidenceType;

    /** 사용자가 올린 이름. 화면 표시용이며 저장 경로와 무관하다. */
    @Column(name = "original_filename", nullable = false, length = 255)
    private String originalFilename;

    /** {@code /data/uploads} 기준 상대 경로. AI 워커에 {@code fileObjectKey} 로 넘어간다. */
    /**
     * {@code /data/uploads} 아래 상대 경로. 워커가 같은 키로 읽는다.
     *
     * <p>{@code updatable} 을 열어 둔 것은 {@link #assignStorageKey} 하나 때문이다 —
     * 키에 {@code evidenceId} 가 들어가는데(합의 ⑧-1) 그 값이 INSERT 뒤에야 나온다.
     */
    @Column(name = "storage_key", nullable = false, length = 512)
    private String storageKey;

    @Column(name = "mime_type", nullable = false, length = 100)
    private String mimeType;

    @Column(name = "file_size", nullable = false)
    private long fileSize;

    @Enumerated(EnumType.STRING)
    @Column(name = "processing_status", nullable = false, length = 20)
    private EvidenceStatus processingStatus;

    /**
     * 증빙에 적힌 발행일. 거래일과 다를 수 있다.
     *
     * <p>업로드 시점에는 모르고 추출(P1-02) 후에 채워진다. 매칭 후보를 좁히는 근거이자
     * 귀속연도 판정의 기준이라 <b>거래일로 대체하면 안 된다</b> — 12월 말 영수증이
     * 1월에 결제되는 경우가 있다.
     */
    @Column(name = "issued_on")
    private LocalDate issuedOn;

    /**
     * 현재 추출의 검산이 걸렸나 — {@code validation.needsReview} 의 사본 (13.2).
     *
     * <p><b>상태가 아니다.</b> 합의 ③ 이 "{@code ocrStatus} 에 {@code REVIEW} 를 만들지
     * 않는다"고 정했고 그대로다 — 모든 추출은 {@code EXTRACTED}(사용자 확인 대기)를 거치고
     * 어떤 흐름도 이 값으로 갈라지지 않는다. <b>목록의 정렬·필터 키</b>일 뿐이다.
     *
     * <p>정본은 {@code evidence_extraction.header_data} 안의 값이고 여기는 사본이다.
     * 한 번 더 두는 이유는 {@link #issuedOn} 과 같다 — JSONB 안에 있으면
     * {@code ORDER BY}·{@code WHERE} 의 키가 되지 못한다.
     *
     * <p>{@code null} 은 <b>"아직 추출 결과가 없다"</b> 이고 {@code false}(검산 통과)와 다르다.
     * 업로드 직후의 증빙이 "검산을 통과했다"로 읽히면 안 된다.
     */
    @Column(name = "needs_review")
    private Boolean needsReview;

    /**
     * 지운 시각. {@code null} 이면 살아 있다 (13.5).
     *
     * <p><b>행을 지우지 않는 이유</b>는 {@code EVIDENCE_CORRECTION} 이다 — 모델이 읽은 값을
     * 사장님이 얼마나 고쳤나가 거기 쌓이고, 그것이 실사용 정확도를 얻는 유일한 원천이다.
     * 증빙을 지우면 그 이력이 함께 사라지거나 가리킬 곳을 잃는다.
     */
    @Column(name = "deleted_at")
    private Instant deletedAt;

    @Column(name = "deleted_by")
    private Long deletedBy;

    protected Evidence() {
    }

    private Evidence(Long businessId, Long uploadedBy, EvidenceType type,
                     String originalFilename, String storageKey, String mimeType, long fileSize) {
        this.businessId = businessId;
        this.uploadedBy = uploadedBy;
        this.evidenceType = type;
        this.originalFilename = originalFilename;
        this.storageKey = storageKey;
        this.mimeType = mimeType;
        this.fileSize = fileSize;
        this.processingStatus = EvidenceStatus.QUEUED;
    }

    /**
     * @param type 업로드 시 힌트. null 이면 {@link EvidenceType#OTHER} 로 두고 추출이 추정한다
     */
    public static Evidence uploaded(Long businessId, Long uploadedBy, EvidenceType type,
                                    String originalFilename, String storageKey,
                                    String mimeType, long fileSize) {
        return new Evidence(businessId, uploadedBy,
                type == null ? EvidenceType.OTHER : type,
                originalFilename, storageKey, mimeType, fileSize);
    }

    /** 키를 확정하기 전에 쓰는 값. {@code uk_evidence_storage} 를 만족해야 해서 유일하다. */
    public static String pendingKey(String token) {
        return PENDING_PREFIX + token;
    }

    private static final String PENDING_PREFIX = "pending/";

    /**
     * 저장 키를 확정한다.
     *
     * <p>키가 {@code evidences/{businessId}/{evidenceId}.{ext}} 라(합의 ⑧-1) {@code evidenceId}
     * 없이는 만들 수 없다. 그래서 임시 키로 INSERT 하고 같은 트랜잭션 안에서 바꾼다.
     *
     * <p><b>두 번째 호출은 막는다.</b> 파일은 이미 그 키로 쓰였고, 키만 바꾸면 아무도 가리키지
     * 않는 파일이 디스크에 남는다.
     */
    public void assignStorageKey(String key) {
        if (this.storageKey == null || !this.storageKey.startsWith(PENDING_PREFIX)) {
            throw new IllegalStateException("저장 키는 한 번만 정한다: " + this.storageKey);
        }
        this.storageKey = key;
    }

    /**
     * 추출 워커가 결과를 돌려줬다. 종류와 발행일은 추출로 채워질 수 있다.
     *
     * <p><b>종류는 사용자가 안 골랐을 때만 모델 값을 쓴다.</b> 처음에는 {@code type != null} 이면
     * 무조건 덮어썼는데, 그러면 업로드에서 {@code TAX_INVOICE} 를 고른 증빙에 모델이
     * {@code RECEIPT} 를 내면 <b>사용자 선택이 지워진다.</b> 도메인 규칙 4 — 세무 값을 자동으로
     * 확정하지 않는다. 판별 정확도도 아직 측정되지 않았다(AI 2번 회신, 2026-09-10).
     *
     * <p>사용자가 안 고르면 업로드가 {@link EvidenceType#OTHER} 로 저장하므로, 그 값이 곧
     * "안 골랐다"는 신호다. 별도 플래그를 두지 않는 이유가 이것이다.
     */
    public void applyExtraction(EvidenceType type, LocalDate issuedOn, Boolean needsReview) {
        if (type != null && this.evidenceType == EvidenceType.OTHER) {
            this.evidenceType = type;
        }
        if (issuedOn != null) this.issuedOn = issuedOn;
        this.needsReview = needsReview;
        this.processingStatus = EvidenceStatus.EXTRACTED;
    }

    /**
     * 사용자가 추출값을 확정했다.
     *
     * <p>추출 완료({@code EXTRACTED})와 확정({@code CONFIRMED})을 나누는 것은
     * 도메인 규칙 4 — <b>세무 값을 자동 확정하지 않는다</b>. 추출이 끝났다고 경비로
     * 인정된 것은 아니다.
     */
    public void confirm() {
        this.processingStatus = EvidenceStatus.CONFIRMED;
    }

    /**
     * 사용자가 검토 화면에서 발행일을 바로잡았다 (13.3).
     *
     * <p>{@code issued_on} 컬럼까지 따라가야 하는 이유는 <b>기간 필터가 JSONB 가 아니라 이
     * 컬럼을 보기 때문</b>이다. 추출값만 고치고 여기를 두면 12월 영수증이 목록의 1월에 남는다.
     */
    public void correctIssuedOn(LocalDate issuedOn) {
        if (issuedOn != null) this.issuedOn = issuedOn;
    }

    /**
     * 검산을 다시 돌린 결과를 정렬 키에 반영한다 (13.3).
     *
     * <p><b>사용자가 금액을 바로잡았는데 목록이 여전히 "확인 필요"로 정렬하면 안 된다.</b>
     * 반대로 워커가 없어 재검산을 못 했으면 {@code null} 이 오고, 그때는 <b>건드리지 않는다</b> —
     * 판정하지 못한 것을 "통과"로 바꾸면 검토가 필요한 건이 목록에서 내려간다.
     */
    public void updateReviewFlag(Boolean needsReview) {
        if (needsReview != null) this.needsReview = needsReview;
    }

    public void markProcessing() {
        this.processingStatus = EvidenceStatus.PROCESSING;
    }

    public void markFailed() {
        this.processingStatus = EvidenceStatus.FAILED;
    }

    /** 추출 대상이 아니다. 실패와 구분해야 화면이 "다시 시도"를 잘못 권하지 않는다. */
    public void markSkipped() {
        this.processingStatus = EvidenceStatus.SKIPPED;
    }

    /**
     * 삭제 표시 (13.5). 파일도 지우지 않는다 — 내보낸 신고 자료의 근거가 사라지면
     * 그 자료를 나중에 설명할 수 없다(명세 13.5 「아카이브에 남는다」).
     *
     * <p>이미 지워진 것을 또 지우면 처음 시각을 지킨다. 두 번 눌렀다고 기록이 바뀌면 안 된다.
     */
    public void softDelete(Long userId, Instant at) {
        if (this.deletedAt != null) {
            return;
        }
        this.deletedAt = at;
        this.deletedBy = userId;
    }

    public boolean isDeleted() {
        return deletedAt != null;
    }

    public Instant getDeletedAt() { return deletedAt; }

    public void changeType(EvidenceType type) {
        if (type != null) this.evidenceType = type;
    }

    public Long getId() { return id; }
    @Override public Long getBusinessId() { return businessId; }
    public Long getUploadedBy() { return uploadedBy; }
    public EvidenceType getEvidenceType() { return evidenceType; }
    public String getOriginalFilename() { return originalFilename; }
    public String getStorageKey() { return storageKey; }
    public String getMimeType() { return mimeType; }
    public long getFileSize() { return fileSize; }
    public EvidenceStatus getProcessingStatus() { return processingStatus; }
    public LocalDate getIssuedOn() { return issuedOn; }
    public Boolean getNeedsReview() { return needsReview; }
}
