package com.jangoyebo.core.job;

import com.jangoyebo.NeedsDocker;
import com.jangoyebo.common.error.BusinessException;
import com.jangoyebo.common.error.ErrorCode;
import com.jangoyebo.common.security.AuthUser;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 비동기 작업 획득·완료·회수 검증.
 *
 * <p>핵심은 {@link #claimsEachJobExactlyOnceUnderConcurrency()} 다. {@code JobService} 의
 * 획득 SQL이 {@code FOR UPDATE SKIP LOCKED} 를 쓰는 <b>유일한 이유</b>가 "워커를 여러 개
 * 돌려도 같은 작업을 두 번 집지 않는다"인데, 그 성질은 동시에 돌려보지 않으면 확인되지 않는다.
 * 단일 스레드 테스트는 이 SQL이 잘못돼 있어도 통과한다.
 *
 * <p>2026-09-01 실행에서 워커 8개가 작업 20개를 3·2·3·3·2·3·2·2로 분산 획득해
 * <b>실제 경합이 발생했음</b>을 확인했다. 한 워커가 전부 집으면 이 테스트는 통과해도
 * 검증한 것이 없으므로, 실패 시에는 분포부터 확인한다.
 *
 * <p><b>Docker가 필요하다.</b> Testcontainers 로 {@code postgres:16-alpine} 을 띄운다.
 */
@NeedsDocker
@SpringBootTest
class JobServiceTests {

    @Autowired
    private JobService jobService;

    @Autowired
    private JobRepository jobRepository;

    @Autowired
    private JobSweeper jobSweeper;

    @Autowired
    private JdbcTemplate jdbc;

    private Long businessId;
    private Long userId;

    @BeforeEach
    void setUp() {
        // 테스트 간 격리. job 은 다른 테이블이 참조하지 않으므로 지워도 안전하다.
        jdbc.execute("DELETE FROM core.job");

        userId = jdbc.queryForObject("""
                INSERT INTO core.users (email, password_hash, name)
                VALUES (?, 'x', '작업자') RETURNING id
                """, Long.class, "job-owner-" + System.nanoTime() + "@test.local");
        businessId = jdbc.queryForObject("""
                INSERT INTO core.business (owner_user_id, name) VALUES (?, '테스트 사업장') RETURNING id
                """, Long.class, userId);

        authenticateAs(userId);
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    // --- 획득 ---------------------------------------------------------------

    /**
     * 워커 8개가 동시에 달려들어도 작업 20개가 <b>정확히 한 번씩</b> 처리되어야 한다.
     *
     * <p>실패하면 두 가지 중 하나다 — 같은 작업이 두 번 집히거나(중복 처리),
     * 일부 작업이 아무에게도 안 잡히거나(유실). 둘 다 운영에서 조용히 발생하는 종류의 사고다.
     */
    @Test
    void claimsEachJobExactlyOnceUnderConcurrency() throws Exception {
        int jobCount = 20;
        int workerCount = 8;

        for (int i = 0; i < jobCount; i++) {
            jobService.enqueue(businessId, userId, JobType.ACCOUNT_SYNC, Map.of("seq", i));
        }

        var claimed = new ConcurrentLinkedQueue<Long>();
        var startGate = new CountDownLatch(1);
        var finished = new CountDownLatch(workerCount);
        ExecutorService pool = Executors.newFixedThreadPool(workerCount);

        try {
            for (int w = 0; w < workerCount; w++) {
                pool.submit(() -> {
                    try {
                        startGate.await();   // 동시에 출발시켜 경합을 실제로 만든다
                        Optional<Job> job;
                        while ((job = jobService.claimNext(JobType.ACCOUNT_SYNC)).isPresent()) {
                            claimed.add(job.get().getId());
                        }
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    } finally {
                        finished.countDown();
                    }
                });
            }

            startGate.countDown();
            assertThat(finished.await(30, TimeUnit.SECONDS))
                    .as("워커가 30초 안에 모두 종료되어야 한다 (교착이면 실패)")
                    .isTrue();
        } finally {
            pool.shutdownNow();
        }

        List<Long> all = List.copyOf(claimed);
        Set<Long> distinct = Set.copyOf(all);

        assertThat(all).as("중복 획득이 없어야 한다").hasSameSizeAs(distinct);
        assertThat(all).as("유실 없이 전부 처리되어야 한다").hasSize(jobCount);
        assertThat(jobRepository.findAll())
                .allSatisfy(job -> assertThat(job.getStatus()).isEqualTo(JobStatus.PROCESSING));
        // CLAIM_SQL 이 획득과 같은 문장에서 올리므로 경합해도 증가분이 유실되지 않는다.
        // 별도 UPDATE 로 빼면 두 워커가 같은 값을 읽어 여기서 깨진다.
        assertThat(jobRepository.findAll())
                .as("8스레드 경합에서도 시도 횟수는 정확히 1 이어야 한다")
                .allSatisfy(job -> assertThat(job.getAttemptCount()).isEqualTo(1));
    }

    /** 상한 판정의 근거값이다. 획득할 때마다 정확히 1씩 올라야 한다. */
    @Test
    void claimIncrementsAttemptCount() {
        Job queued = jobService.enqueue(businessId, userId, JobType.ACCOUNT_SYNC, Map.of());
        assertThat(queued.getAttemptCount()).isZero();

        Job claimed = jobService.claimNext(JobType.ACCOUNT_SYNC).orElseThrow();
        assertThat(claimed.getAttemptCount()).isEqualTo(1);

        backdateStartedAt(claimed.getId(), 11);
        jobSweeper.sweepStalledJobs();

        assertThat(jobService.claimNext(JobType.ACCOUNT_SYNC).orElseThrow().getAttemptCount())
                .as("requeue 는 시도 횟수를 되돌리지 않는다")
                .isEqualTo(2);
    }

    @Test
    void claimMarksJobProcessingAndStampsStartedAt() {
        Job queued = jobService.enqueue(businessId, userId, JobType.EXPORT_GENERATION, Map.of());
        assertThat(queued.getStatus()).isEqualTo(JobStatus.QUEUED);
        assertThat(queued.getStartedAt()).isNull();
        // insertable=false 로 두면 여기서 null 이 된다. CSV 임포트 응답이 이 경로다.
        assertThat(queued.getCreatedAt()).as("저장 직후에도 createdAt 이 채워져야 한다").isNotNull();

        Job claimed = jobService.claimNext(JobType.EXPORT_GENERATION).orElseThrow();

        assertThat(claimed.getId()).isEqualTo(queued.getId());
        assertThat(claimed.getStatus()).isEqualTo(JobStatus.PROCESSING);
        assertThat(claimed.getStartedAt()).as("스위퍼의 경과 시간 기준이 된다").isNotNull();
    }

    /** 획득은 작업 유형별로 독립적이다. 한 유형의 워커가 다른 유형을 집어가면 안 된다. */
    @Test
    void claimIsScopedToJobType() {
        jobService.enqueue(businessId, userId, JobType.ACCOUNT_SYNC, Map.of());

        assertThat(jobService.claimNext(JobType.EVIDENCE_EXTRACTION)).isEmpty();
        assertThat(jobService.claimNext(JobType.ACCOUNT_SYNC)).isPresent();
    }

    @Test
    void claimReturnsEmptyWhenQueueIsDrained() {
        assertThat(jobService.claimNext(JobType.LOAN_SYNC)).isEmpty();
    }

    /** 오래 기다린 작업이 먼저 처리되어야 한다(FIFO). */
    @Test
    void claimsOldestFirst() {
        Job first = jobService.enqueue(businessId, userId, JobType.TRANSACTION_IMPORT, Map.of("n", 1));
        Job second = jobService.enqueue(businessId, userId, JobType.TRANSACTION_IMPORT, Map.of("n", 2));
        // created_at 이 같은 밀리초면 순서가 흔들리므로 벌려 둔다.
        jdbc.update("UPDATE core.job SET created_at = created_at - INTERVAL '1 minute' WHERE id = ?",
                first.getId());

        assertThat(jobService.claimNext(JobType.TRANSACTION_IMPORT).orElseThrow().getId())
                .isEqualTo(first.getId());
        assertThat(jobService.claimNext(JobType.TRANSACTION_IMPORT).orElseThrow().getId())
                .isEqualTo(second.getId());
    }

    // --- 완료 · 실패 ---------------------------------------------------------

    @Test
    void completeStoresResultReference() {
        Job job = jobService.enqueue(businessId, userId, JobType.TRANSACTION_IMPORT, Map.of());

        jobService.complete(job.getId(), Map.of("importedCount", 197, "duplicateCount", 2));

        Job reloaded = jobRepository.findById(job.getId()).orElseThrow();
        assertThat(reloaded.getStatus()).isEqualTo(JobStatus.COMPLETED);
        assertThat(reloaded.getCompletedAt()).isNotNull();
        // JSONB → Map 역직렬화 시 정수는 Integer 로 온다(2026-09-01 실측).
        assertThat(reloaded.getResult()).containsEntry("importedCount", 197);
    }

    @Test
    void failStoresErrorCodeFromEnum() {
        Job job = jobService.enqueue(businessId, userId, JobType.ACCOUNT_SYNC, Map.of());

        jobService.fail(job.getId(), ErrorCode.FIN_API_FAILED, "금융망 응답 없음");

        Job reloaded = jobRepository.findById(job.getId()).orElseThrow();
        assertThat(reloaded.getStatus()).isEqualTo(JobStatus.FAILED);
        // 프론트가 받는 error.code 와 같은 문자열이어야 한다(API 2.7).
        assertThat(reloaded.getErrorCode()).isEqualTo("FIN_API_FAILED");
        assertThat(reloaded.getErrorMessage()).isEqualTo("금융망 응답 없음");
    }

    // --- 스위퍼 -------------------------------------------------------------

    /**
     * AI 워커의 콜백이 유실되면 작업이 {@code PROCESSING} 에 영구히 남는다.
     * 스위퍼가 이를 회수해 다시 처리 가능한 상태로 되돌려야 한다(아키텍처 §7.2).
     */
    @Test
    void sweeperRequeuesStalledJobs() {
        jobService.enqueue(businessId, userId, JobType.TRANSACTION_CLASSIFICATION, Map.of());
        Job claimed = jobService.claimNext(JobType.TRANSACTION_CLASSIFICATION).orElseThrow();

        // 11분 전에 시작한 것처럼 만든다(임계값 10분).
        // Instant 를 그대로 바인딩하면 드라이버가 SQL 타입을 추론하지 못한다.
        // OffsetDateTime 은 PostgreSQL 드라이버가 timestamptz 로 직접 매핑한다.
        jdbc.update("UPDATE core.job SET started_at = ? WHERE id = ?",
                OffsetDateTime.ofInstant(Instant.now().minus(11, ChronoUnit.MINUTES), ZoneOffset.UTC),
                claimed.getId());

        jobSweeper.sweepStalledJobs();

        Job reloaded = jobRepository.findById(claimed.getId()).orElseThrow();
        assertThat(reloaded.getStatus()).isEqualTo(JobStatus.QUEUED);
        assertThat(reloaded.getStartedAt())
                .as("startedAt 이 비워져야 다음 스위프에서 다시 잡히지 않는다")
                .isNull();
        // 회수 후에는 워커가 다시 집을 수 있어야 한다.
        assertThat(jobService.claimNext(JobType.TRANSACTION_CLASSIFICATION)).isPresent();
    }

    /** 아직 임계값을 넘지 않은 작업은 건드리지 않는다. 정상 처리 중인 작업을 뺏으면 중복 실행이 된다. */
    @Test
    void sweeperLeavesRecentlyStartedJobsAlone() {
        jobService.enqueue(businessId, userId, JobType.EVIDENCE_EXTRACTION, Map.of());
        Job claimed = jobService.claimNext(JobType.EVIDENCE_EXTRACTION).orElseThrow();

        jobSweeper.sweepStalledJobs();

        assertThat(jobRepository.findById(claimed.getId()).orElseThrow().getStatus())
                .isEqualTo(JobStatus.PROCESSING);
    }

    /**
     * 상한을 쓴 작업은 실패로 확정한다. 이것이 없으면 영구히 실패하는 작업이
     * 5분마다 큐를 돌며 워커를 갉아먹는다(공통규약 23장 — 재시도 1회).
     */
    @Test
    void sweeperFailsJobAfterMaxAttempts() {
        jobService.enqueue(businessId, userId, JobType.EXPORT_GENERATION, Map.of());

        // 1회차 — 상한(2) 미달이므로 회수된다.
        Job first = jobService.claimNext(JobType.EXPORT_GENERATION).orElseThrow();
        backdateStartedAt(first.getId(), 11);
        jobSweeper.sweepStalledJobs();
        assertThat(jobRepository.findById(first.getId()).orElseThrow().getStatus())
                .isEqualTo(JobStatus.QUEUED);

        // 2회차 — 상한 도달. 실패로 확정된다.
        Job second = jobService.claimNext(JobType.EXPORT_GENERATION).orElseThrow();
        backdateStartedAt(second.getId(), 11);
        jobSweeper.sweepStalledJobs();

        Job reloaded = jobRepository.findById(second.getId()).orElseThrow();
        assertThat(reloaded.getStatus()).isEqualTo(JobStatus.FAILED);
        assertThat(reloaded.getErrorCode()).isEqualTo("JOB_TIMEOUT");
        assertThat(reloaded.getCompletedAt()).isNotNull();
        assertThat(jobService.claimNext(JobType.EXPORT_GENERATION))
                .as("실패 확정된 작업은 다시 집히지 않는다")
                .isEmpty();
    }

    /**
     * 스위퍼 임계값을 넘긴 것처럼 {@code started_at} 을 과거로 민다.
     *
     * <p>{@code Instant} 를 그대로 바인딩하면 드라이버가 SQL 타입을 추론하지 못한다.
     * {@code OffsetDateTime} 은 PostgreSQL 드라이버가 {@code timestamptz} 로 직접 매핑한다.
     */
    private void backdateStartedAt(Long jobId, int minutesAgo) {
        jdbc.update("UPDATE core.job SET started_at = ? WHERE id = ?",
                OffsetDateTime.ofInstant(Instant.now().minus(minutesAgo, ChronoUnit.MINUTES), ZoneOffset.UTC),
                jobId);
    }

    // --- 조회 권한 -----------------------------------------------------------

    @Test
    void ownerCanReadOwnJob() {
        Job job = jobService.enqueue(businessId, userId, JobType.ACCOUNT_SYNC, Map.of());

        assertThat(jobService.getOwnedJob(job.getId()).getId()).isEqualTo(job.getId());
    }

    /**
     * 남의 작업은 <b>403이 아니라 404</b>다(API 2.7).
     * 403을 주면 "그 ID의 작업이 존재한다"는 사실이 새어 나간다.
     */
    @Test
    void otherUsersJobLooksLikeItDoesNotExist() {
        Job job = jobService.enqueue(businessId, userId, JobType.ACCOUNT_SYNC, Map.of());

        Long strangerId = jdbc.queryForObject("""
                INSERT INTO core.users (email, password_hash, name)
                VALUES (?, 'x', '남') RETURNING id
                """, Long.class, "stranger-" + System.nanoTime() + "@test.local");
        authenticateAs(strangerId);

        assertThatThrownBy(() -> jobService.getOwnedJob(job.getId()))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).errorCode())
                .isEqualTo(ErrorCode.JOB_NOT_FOUND);
    }

    @Test
    void missingJobReturnsNotFound() {
        assertThatThrownBy(() -> jobService.getOwnedJob(9_999_999L))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).errorCode())
                .isEqualTo(ErrorCode.JOB_NOT_FOUND);
    }

    // --- 접수 실패 되돌리기 (AI 담당 요청 2026-09-14) -------------------------

    /**
     * 워커가 받지 못한 작업은 <b>대기열로 돌아가고, 잠시 뒤 다시 집힌다.</b>
     *
     * <p>실측으로 이 경로가 없어서 분류가 20분 멈췄다 — 접수 실패한 Job 이 PROCESSING 에
     * 갇혀 스위퍼의 10분을 기다렸고, 그동안 8.5 재실행까지 그 Job 을 돌려주며 막혔다.
     */
    @Test
    void sendsARejectedJobBackToTheQueue() {
        Job job = jobService.enqueue(businessId, userId, JobType.TRANSACTION_CLASSIFICATION, Map.of());
        Long jobId = jobService.claimNext(JobType.TRANSACTION_CLASSIFICATION).orElseThrow().getId();

        jobService.requeueAfterRejection(jobId, Duration.ofMillis(50));

        Job requeued = jobRepository.findById(jobId).orElseThrow();
        assertThat(requeued.getStatus()).isEqualTo(JobStatus.QUEUED);
        assertThat(requeued.getNextAttemptAt()).isAfter(Instant.now().minusSeconds(1));
        assertThat(requeued.getAttemptCount())
                .as("접수조차 안 된 시도는 재시도 횟수로 세지 않는다")
                .isZero();
        assertThat(job.getId()).isEqualTo(jobId);
    }

    /**
     * 되돌린 직후에는 집히지 않는다.
     *
     * <p>없으면 워커가 죽어 있는 동안 폴링 간격마다 재시도가 돌아 로그만 쌓인다.
     */
    @Test
    void doesNotClaimBeforeTheRetryTime() {
        jobService.enqueue(businessId, userId, JobType.TRANSACTION_CLASSIFICATION, Map.of());
        Long jobId = jobService.claimNext(JobType.TRANSACTION_CLASSIFICATION).orElseThrow().getId();

        jobService.requeueAfterRejection(jobId, Duration.ofMinutes(5));

        assertThat(jobService.claimNext(JobType.TRANSACTION_CLASSIFICATION))
                .as("재시도 시각 전에는 대기열에 있어도 건너뛴다")
                .isEmpty();
    }

    /** 재시도 시각이 지나면 다시 집는다 — 워커가 살아나면 곧바로 이어진다. */
    @Test
    void claimsAgainOnceTheRetryTimePassed() {
        jobService.enqueue(businessId, userId, JobType.TRANSACTION_CLASSIFICATION, Map.of());
        Long jobId = jobService.claimNext(JobType.TRANSACTION_CLASSIFICATION).orElseThrow().getId();

        // 이미 지난 시각으로 되돌린다 — 대기 없이 같은 것을 확인한다.
        jobService.requeueAfterRejection(jobId, Duration.ofSeconds(-1));

        assertThat(jobService.claimNext(JobType.TRANSACTION_CLASSIFICATION))
                .map(Job::getId)
                .contains(jobId);
    }

    // --- helpers ------------------------------------------------------------

    private void authenticateAs(Long id) {
        var principal = new AuthUser(id, "user-" + id + "@test.local");
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(principal, null, List.of()));
    }
}
