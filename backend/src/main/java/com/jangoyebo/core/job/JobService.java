package com.jangoyebo.core.job;

import com.jangoyebo.common.error.BusinessException;
import com.jangoyebo.common.error.ErrorCode;
import com.jangoyebo.common.tenancy.BusinessGuard;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 비동기 작업 생명주기.
 *
 * <p>{@code forecast} 모듈에서도 쓰므로 {@code core}의 공개 API다.
 */
@Service
public class JobService {

    /**
     * 대기 중인 작업 하나를 원자적으로 집는다.
     *
     * <p>{@code FOR UPDATE SKIP LOCKED} 덕분에 워커를 여러 개 돌려도 같은 작업을 두 번 집지 않는다.
     * 조회와 상태 변경이 한 문장이라 그 사이에 다른 워커가 끼어들 틈이 없다.
     *
     * <p>{@code next_attempt_at} 이 미래면 건너뛴다 — 워커가 받지 못해 되돌린 작업이
     * 폴링 간격마다 다시 시도되지 않게 한다(V16 · {@link Job#requeueAfterRejection}).
     *
     * <p>{@code attempt_count} 증가도 <b>이 문장 안에서</b> 한다. 별도 UPDATE 로 빼면
     * 두 워커가 같은 값을 읽어 증가분이 유실되고, 스위퍼의 재시도 상한이 무의미해진다.
     * 최초 획득 후 값은 1 이므로 {@code max-attempts = 2} 가 "재시도 1회"다(공통규약 23장).
     */
    private static final String CLAIM_SQL = """
            UPDATE core.job
               SET status = 'PROCESSING', started_at = now(), attempt_count = attempt_count + 1
             WHERE id = (
                   SELECT id FROM core.job
                    WHERE status = 'QUEUED' AND job_type = ?
                      AND (next_attempt_at IS NULL OR next_attempt_at <= now())
                    ORDER BY created_at
                      FOR UPDATE SKIP LOCKED
                    LIMIT 1)
            RETURNING id
            """;

    private final JobRepository repository;
    private final JdbcTemplate jdbc;
    private final BusinessGuard guard;

    public JobService(JobRepository repository, JdbcTemplate jdbc, BusinessGuard guard) {
        this.repository = repository;
        this.jdbc = jdbc;
        this.guard = guard;
    }

    @Transactional
    public Job enqueue(Long businessId, Long requestedBy, JobType type, Map<String, Object> params) {
        return repository.save(Job.queue(businessId, requestedBy, type, params));
    }

    /** 아직 끝나지 않은 작업의 상태. 종단이 아닌 둘이다({@link JobStatus#isTerminal}). */
    private static final List<JobStatus> UNFINISHED = List.of(JobStatus.QUEUED, JobStatus.PROCESSING);

    /**
     * 같은 사업장에 끝나지 않은 같은 종류의 작업이 없을 때만 넣는다 (거래 분류 합의 ④).
     *
     * <p><b>왜 필요한가.</b> 분류 핸들러는 Job 에 적힌 id 목록이 아니라 <b>그때의 미분류
     * 거래를 조회</b>해서 대상을 정한다. 콜백이 오기 전까지 그 거래들은 계속
     * {@code UNCLASSIFIED} 이므로, 같은 사업장에 Job 이 둘 뜨면 <b>둘 다 같은 거래를 집어
     * LLM 에 두 번 보낸다 — 값은 두 번 나가고 결과는 하나다.</b>
     *
     * <p><b>⚠ 이 판정은 조회와 삽입 사이를 잠그지 않는다.</b> 두 이벤트(동기화 완료·CSV
     * 임포트 완료)가 거의 같은 순간에 끝나면 양쪽이 "없음"을 보고 둘 다 넣을 수 있다.
     * 그러면 한 배치({@code jango.classification.batch-size}, 기본 20건)가 중복 과금된다.
     *
     * <p>알고 그대로 두는 이유는 <b>막는 값이 잃는 값보다 크기 때문</b>이다. 확실히 막으려면
     * {@code JOB} 에 "사업장당 미완료 분류 작업 하나" 부분 유일 인덱스를 걸어야 하는데,
     * 마이그레이션이 하나 늘고 그 제약은 분류 말고 다른 작업 유형에도 걸린다. 사용자당
     * 계좌가 한둘이고 두 수집 경로가 같은 초에 겹칠 일이 드물어, <b>중복이 실제로 관측되면
     * 그때 인덱스를 얹는다.</b> 관측 방법은 같은 사업장에 같은 시각의 분류 Job 두 개다.
     *
     * <p>겹쳐도 <b>손실은 없다</b> — 결과는 거래마다 멱등하게 반영되고(확정된 거래는 덮지
     * 않는다) 늦게 온 콜백은 이미 분류된 거래를 건너뛴다. 잃는 것은 돈뿐이고 그 상한이
     * 배치 하나다.
     *
     * @return 넣었으면 그 작업. 이미 도는 것이 있으면 비어 있다
     */
    @Transactional
    public Optional<Job> enqueueIfIdle(Long businessId, Long requestedBy, JobType type,
                                       Map<String, Object> params) {
        if (unfinished(businessId, type).isPresent()) {
            return Optional.empty();
        }
        return Optional.of(enqueue(businessId, requestedBy, type, params));
    }

    /**
     * {@code AFTER_COMMIT} 리스너에서 부르는 {@link #enqueueIfIdle}.
     *
     * <p><b>{@code REQUIRES_NEW} 여야 한다.</b> {@code AFTER_COMMIT} 콜백은 방금 커밋된
     * 트랜잭션이 <b>아직 정리되지 않은</b> 자리에서 돈다. {@code REQUIRED} 로 두면 그 죽은
     * 트랜잭션에 합류해 <b>INSERT 가 오류 없이 사라진다</b> — 예외도 로그도 없고 Job 만
     * 안 생긴다. 실제로 그랬다: 동기화·CSV 임포트가 끝나도 분류 작업이 한 건도 남지 않아
     * <b>자동 분류가 통째로 돌지 않았다</b>(8.5 "동기화·CSV 업로드 완료 시 자동 실행").
     * {@code LoanPlannedGenerator.regenerate}·{@code ForecastRunService.resyncSafetyLineAlerts}
     * 가 같은 이유로 같은 자리에 서 있다.
     *
     * <p><b>{@link #enqueueIfIdle} 자체를 바꾸지 않는 이유.</b> 그쪽은 콜백 반영
     * ({@code AiResultApplier.chainIfUnfinished})도 부르는데, 거기서는 <b>같은 트랜잭션이어야
     * 한다</b> — 콜백이 롤백되면 이어 갈 이유도 함께 사라져야 하기 때문이다.
     *
     * <p>새 트랜잭션이 이 메서드 안에서 시작하고 끝나므로, 부르는 쪽의 {@code try/catch} 가
     * <b>커밋 실패까지</b> 감싼다. 리스너에 {@code REQUIRES_NEW} 를 걸면 커밋이 프록시
     * 경계에서 일어나 그 바깥으로 새고, {@code AFTER_COMMIT} 에서 던진 예외는 이미 커밋된
     * 호출자를 실패로 만든다.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public Optional<Job> enqueueIfIdleInNewTransaction(Long businessId, Long requestedBy, JobType type,
                                                       Map<String, Object> params) {
        return enqueueIfIdle(businessId, requestedBy, type, params);
    }

    /**
     * 이미 도는 것이 있으면 <b>그것을</b>, 없으면 새로 넣어 돌려준다 (API 8.5 수동 재실행).
     *
     * <p>{@link #enqueueIfIdle} 와 판정은 같고 <b>돌려주는 것만 다르다.</b> 자동 트리거는
     * "이미 돌면 아무것도 안 함"이면 되지만, 버튼을 누른 사용자에게는 돌려줄 {@code jobId} 가
     * 있어야 진행률을 볼 수 있다.
     *
     * <p><b>409 를 주지 않는 이유.</b> 버튼을 두 번 눌러도 사용자가 보려는 것은 같은 진행률이다.
     * 409 면 화면이 "이미 돌고 있음"을 따로 처리해야 하는데 그 끝에 보여줄 것이 결국 같다.
     */
    @Transactional
    public Job enqueueOrCurrent(Long businessId, Long requestedBy, JobType type,
                                Map<String, Object> params) {
        return unfinished(businessId, type)
                .orElseGet(() -> enqueue(businessId, requestedBy, type, params));
    }

    /**
     * 사업장에 <b>끝나지 않은</b> 같은 종류의 작업 하나 (API 8.5.1).
     *
     * <p>{@link #enqueueOrCurrent} 와 판정은 같고 <b>넣지 않는 것만 다르다.</b> 화면이
     * 돌아왔을 때 진행 중인 작업을 알아보려면 조회가 필요한데, 그 자리에서
     * {@code enqueueOrCurrent} 를 부르면 <b>도는 것이 없을 때 새 작업을 만든다</b> —
     * 화면에 들어올 때마다 LLM 값을 치르게 된다.
     *
     * <p>소유권은 부르는 쪽의 {@code @CurrentBusiness} 가 이미 검증했다. 여기서
     * {@code guard} 를 다시 타지 않는 이유는 {@code JobWorker} 도 이 판정을 쓰기 때문이다 —
     * 워커 스레드에는 {@code SecurityContext} 가 없다.
     */
    @Transactional(readOnly = true)
    public Optional<Job> findUnfinished(Long businessId, JobType type) {
        return unfinished(businessId, type);
    }

    private Optional<Job> unfinished(Long businessId, JobType type) {
        return repository.findFirstByBusinessIdAndJobTypeAndStatusInOrderByIdDesc(
                businessId, type, UNFINISHED);
    }

    /** 워커가 호출한다. 집을 작업이 없으면 비어 있다. */
    @Transactional
    public Optional<Job> claimNext(JobType type) {
        return jdbc.query(CLAIM_SQL, rs -> rs.next() ? Optional.of(rs.getLong(1)) : Optional.<Long>empty(),
                        type.name())
                .flatMap(repository::findById);
    }

    /**
     * 워커 콜백 전용 조회.
     *
     * <p>{@link #getOwnedJob} 과 달리 {@code BusinessGuard} 를 타지 않는다 — 콜백 경로는
     * 서비스 토큰으로 인증하므로 SecurityContext 에 {@code AuthUser} 가 없다.
     * 소유권 대신 <b>jobId 를 아는 것</b>이 자격이며, 그 경로는 서비스 토큰이 지킨다.
     *
     * <p>행을 잠근다 — 스위퍼가 같은 작업을 회수하는 것과 겹칠 수 있다.
     */
    @Transactional
    public Job findForCallback(Long jobId) {
        return repository.findWithLockById(jobId)
                .orElseThrow(() -> new BusinessException(ErrorCode.JOB_NOT_FOUND));
    }

    @Transactional
    public void complete(Long jobId, Map<String, Object> result) {
        job(jobId).complete(result);
    }

    /**
     * 워커가 받지 못했다. 대기열로 되돌려 {@code delay} 뒤에 다시 시도한다.
     *
     * <p>이 시도는 재시도 횟수로 세지 않는다 — 이유는 {@link Job#requeueAfterRejection}.
     */
    @Transactional
    public void requeueAfterRejection(Long jobId, java.time.Duration delay) {
        job(jobId).requeueAfterRejection(delay);
    }

    @Transactional
    public void fail(Long jobId, ErrorCode code, String message) {
        job(jobId).fail(code.name(), message);
    }

    /**
     * {@code GET /jobs/{jobId}} 용 조회.
     *
     * <p>본인이 만들지 않은 작업은 <b>403이 아니라 404</b>다(API 2.7) — 남의 작업이
     * 존재한다는 사실 자체를 흘리지 않기 위해서다.
     */
    @Transactional(readOnly = true)
    public Job getOwnedJob(Long jobId) {
        Job job = repository.findById(jobId)
                .orElseThrow(() -> new BusinessException(ErrorCode.JOB_NOT_FOUND));

        // 직접 경로 조회이므로 명시적으로 소유권을 확인한다.
        Long currentUserId = guard.currentUserId();
        boolean ownsBusiness = isOwned(job);
        boolean requestedByMe = currentUserId.equals(job.getRequestedBy());
        if (!ownsBusiness && !requestedByMe) {
            throw new BusinessException(ErrorCode.JOB_NOT_FOUND);
        }
        return job;
    }

    private boolean isOwned(Job job) {
        try {
            guard.assertOwned(job);
            return true;
        } catch (BusinessException e) {
            return false;
        }
    }

    private Job job(Long jobId) {
        return repository.findById(jobId)
                .orElseThrow(() -> new BusinessException(ErrorCode.JOB_NOT_FOUND));
    }
}
