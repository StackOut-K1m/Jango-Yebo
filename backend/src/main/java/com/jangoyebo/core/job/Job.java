package com.jangoyebo.core.job;

import com.jangoyebo.common.tenancy.BusinessScoped;
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

import java.time.Instant;
import java.util.Map;

/**
 * 비동기 작업 (API 명세 2.7, ERD {@code JOB}).
 *
 * <p>동기화·CSV 임포트·내보내기·AI 요청의 <b>상태를 담는 단일 기준</b>이다.
 * Redis 큐를 두지 않는 이유가 여기 있다 — 상태가 두 곳에 존재하면 불일치 디버깅에 시간을 쓴다.
 *
 * <p>연관 엔티티 대신 ID만 들고 있다. 모듈 경계를 넘는 참조를 만들지 않기 위해서다.
 */
@Entity
@Table(name = "job", schema = "core")
public class Job implements BusinessScoped {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /**
     * 사업장 단위 작업이면 그 사업장, <b>전역 작업이면 null</b> (V21).
     *
     * <p>공고 구조화({@link JobType#PROGRAM_STRUCTURING})는 사업장이 없다 — 공고가 사업장
     * 공용 데이터라 한 번 읽으면 모두가 쓴다. 아무 사업장이나 골라 넣으면 그 사장님의 Job
     * 목록에 남의 작업이 뜬다.
     *
     * <p><b>null 이면 {@link com.jangoyebo.common.tenancy.BusinessGuard#assertOwned} 가 403 을
     * 던진다</b> — 소유권 판정이 {@code EXISTS (... WHERE id = ?)} 라 NULL 은 행을 못 찾는다.
     * 전역 작업이 {@code GET /jobs/{jobId}} 로 아무에게도 보이지 않는다는 뜻이고, 시스템 배치
     * 작업에는 그게 맞는 기본값이다.
     *
     * <p>⚠ {@link JobService#enqueueIfIdle} 의 중복 방지는 <b>전역 작업에 걸리지 않는다.</b>
     * {@code business_id = null} 비교가 SQL 에서 아무것도 매칭하지 않아 항상 새로 넣는다.
     * 전역 작업의 중복은 대상(공고 id)으로 막아야 한다.
     */
    @Column(name = "business_id", updatable = false)
    private Long businessId;

    /** 시스템 배치가 만든 작업이면 null. */
    @Column(name = "requested_by", updatable = false)
    private Long requestedBy;

    @Enumerated(EnumType.STRING)
    @Column(name = "job_type", nullable = false, updatable = false, length = 40)
    private JobType jobType;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private JobStatus status;

    /** 0.0 ~ 1.0 */
    @Column
    private java.math.BigDecimal progress;

    /**
     * 작업 입력. <b>만든 뒤에는 바뀌지 않는 것이 원칙</b>이고, 예외는
     * {@link #assignParams} 하나다.
     *
     * <p>⚠ {@code updatable = false} 를 걸어 두면 그 예외가 <b>예외를 던지는 대신 조용히
     * 무시된다.</b> 컬럼을 열고 {@code assignParams} 가 {@code QUEUED} 검사로 지킨다 —
     * {@code Evidence.storageKey} 가 같은 사정으로 같은 선택을 했다.
     */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column
    private Map<String, Object> params;

    /** 완료 후 결과 리소스를 다시 조회할 수 있는 식별자(API {@code resultRef}). */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column
    private Map<String, Object> result;

    @Column(name = "error_code", length = 50)
    private String errorCode;

    @Column(name = "error_message")
    private String errorMessage;

    /**
     * {@code insertable = false} 로 두면 DB default 가 값을 넣지만 <b>엔티티에는 반영되지 않아</b>
     * 저장 직후 응답에서 항상 null 이 된다(CSV 임포트 응답이 이 경로다).
     * Hibernate 가 삽입 시점에 채우도록 {@code @CreationTimestamp} 를 쓴다.
     */
    @org.hibernate.annotations.CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    /** 타임아웃 스위퍼의 경과 시간 기준. {@code PROCESSING} 전이 시각이다. */
    @Column(name = "started_at")
    private Instant startedAt;

    /**
     * 획득 시도 횟수. {@link JobService} 의 {@code CLAIM_SQL} 이 획득 UPDATE 와
     * <b>같은 문장에서</b> 증가시킨다 — 그래야 {@code FOR UPDATE SKIP LOCKED} 의
     * 원자성이 시도 횟수에도 적용된다.
     *
     * <p>{@code updatable = false} 인 이유: 소유자가 그 네이티브 SQL 한 곳뿐이라는 사실을
     * 매핑으로 강제한다. Hibernate 가 {@code requeue()}·{@code complete()} UPDATE 에
     * 이 컬럼을 함께 실으면 시도 횟수가 되돌아간다. 삽입은 포함되므로 신규 저장 시 0 이 들어간다.
     */
    @Column(name = "attempt_count", nullable = false)
    private int attemptCount;

    /**
     * 이 시각 전에는 워커가 집지 않는다 (V16).
     *
     * <p>{@link #requeueAfterRejection} 만 채운다. 보통은 {@code null} 이라 즉시 대상이다.
     */
    @Column(name = "next_attempt_at")
    private Instant nextAttemptAt;

    @Column(name = "completed_at")
    private Instant completedAt;

    protected Job() {
    }

    private Job(Long businessId, Long requestedBy, JobType jobType, Map<String, Object> params) {
        this.businessId = businessId;
        this.requestedBy = requestedBy;
        this.jobType = jobType;
        this.params = params;
        this.status = JobStatus.QUEUED;
    }

    public static Job queue(Long businessId, Long requestedBy, JobType jobType, Map<String, Object> params) {
        return new Job(businessId, requestedBy, jobType, params);
    }

    /**
     * 아직 {@code QUEUED} 인 작업의 파라미터를 확정한다.
     *
     * <p><b>id 가 있어야 만들 수 있는 값 때문에 있다.</b> 매출 인쇄물 업로드(14.7)의 파일 키가
     * {@code product-sales/{businessId}/{jobId}-{n}.{ext}} 라(합의 ⑧-1) INSERT 전에는 키를
     * 만들 수 없다. 그래서 나머지를 먼저 넣어 저장하고, id 를 받아 키를 만든 뒤 여기서 채운다.
     * {@code Evidence.assignStorageKey} 와 같은 사정이다.
     *
     * <p><b>{@code QUEUED} 일 때만 허용한다.</b> 워커가 집어 간 뒤에 파라미터가 바뀌면 지금
     * 돌고 있는 처리와 기록이 어긋난다. 그리고 이 메서드는 <b>같은 트랜잭션 안에서 한 번</b>
     * 쓰라고 있는 것이지, 나중에 작업 내용을 고치는 통로가 아니다.
     *
     * <p>맵을 제자리에서 고치지 않고 통째로 바꾼다 — 같은 인스턴스를 수정하면 Hibernate 의
     * 더티 체킹이 JSONB 변경을 못 알아채 <b>조용히 저장되지 않는다.</b>
     */
    public void assignParams(Map<String, Object> params) {
        if (this.status != JobStatus.QUEUED) {
            throw new IllegalStateException(
                    "이미 %s 인 작업의 파라미터는 바꾸지 않는다: %d".formatted(this.status, this.id));
        }
        this.params = params;
    }

    public void complete(Map<String, Object> result) {
        this.status = JobStatus.COMPLETED;
        this.result = result;
        this.progress = java.math.BigDecimal.ONE;
        this.completedAt = Instant.now();
    }

    public void fail(String errorCode, String errorMessage) {
        this.status = JobStatus.FAILED;
        this.errorCode = errorCode;
        this.errorMessage = errorMessage;
        this.completedAt = Instant.now();
    }

    /**
     * 재시도를 위해 대기열로 되돌린다. 스위퍼가 콜백 유실을 감지했을 때 쓴다.
     *
     * <p>{@code attemptCount} 는 건드리지 않는다 — 되돌리면 상한이 무의미해진다.
     * 이전 실패 흔적은 지운다. 그러지 않으면 강제로 재큐된 작업의 낡은 오류가
     * {@code JobResponse.error} 로 새어 나간다.
     */
    public void requeue() {
        this.status = JobStatus.QUEUED;
        this.startedAt = null;
        this.progress = null;
        this.errorCode = null;
        this.errorMessage = null;
    }

    /**
     * 워커가 받지 못한 작업을 대기열로 되돌린다 (접수 실패 · AI 담당 요청 2026-09-14).
     *
     * <p><b>이 시도는 세지 않는다.</b> {@code attemptCount} 를 도로 내린다 — 재시도 상한이
     * 지키려는 것은 "같은 일을 외부에 몇 번 시켰나" 인데, 연결조차 안 된 요청은 외부가 아무
     * 일도 하지 않았다. 세면 워커가 잠깐 꺼진 것만으로 상한에 닿아, 정작 워커가 살아난 뒤
     * 스위퍼가 그 작업을 즉시 {@code FAILED} 로 접는다.
     *
     * <p><b>다시 집을 시각을 미룬다.</b> 되돌리자마자 집으면 워커가 죽어 있는 동안 폴링 간격마다
     * 재시도가 돌아 로그만 쌓인다.
     */
    public void requeueAfterRejection(java.time.Duration delay) {
        requeue();
        this.attemptCount = Math.max(0, this.attemptCount - 1);
        this.nextAttemptAt = Instant.now().plus(delay);
    }

    public void updateProgress(java.math.BigDecimal progress) {
        this.progress = progress;
    }

    /**
     * 결과에 요약 수치를 더한다 (API 8.5 {@code resultRef}).
     *
     * <p>워커 본문은 그대로 둔다 — 도메인 테이블에 옮긴 뒤에도 원본이 남아 있어야 나중에 다시
     * 밀어 넣거나 무엇이 왔는지 확인할 수 있다({@code AiResultApplier.persist} 와 같은 판단).
     *
     * <p><b>맵을 제자리에서 고치지 않는다.</b> 같은 인스턴스를 수정하면 Hibernate 의 더티 체킹이
     * JSONB 변경을 못 알아채 <b>조용히 저장되지 않는다</b>({@link #assignParams} 와 같은 사정).
     */
    public void addResult(Map<String, Object> extra) {
        if (extra == null || extra.isEmpty()) {
            return;
        }
        Map<String, Object> merged = new java.util.LinkedHashMap<>();
        if (this.result != null) {
            merged.putAll(this.result);
        }
        merged.putAll(extra);
        this.result = merged;
    }

    public Long getId() {
        return id;
    }

    @Override
    public Long getBusinessId() {
        return businessId;
    }

    public Long getRequestedBy() {
        return requestedBy;
    }

    public JobType getJobType() {
        return jobType;
    }

    public JobStatus getStatus() {
        return status;
    }

    public int getAttemptCount() {
        return attemptCount;
    }

    public Instant getNextAttemptAt() {
        return nextAttemptAt;
    }

    public java.math.BigDecimal getProgress() {
        return progress;
    }

    public Map<String, Object> getParams() {
        return params;
    }

    public Map<String, Object> getResult() {
        return result;
    }

    public String getErrorCode() {
        return errorCode;
    }

    public String getErrorMessage() {
        return errorMessage;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getStartedAt() {
        return startedAt;
    }

    public Instant getCompletedAt() {
        return completedAt;
    }
}
