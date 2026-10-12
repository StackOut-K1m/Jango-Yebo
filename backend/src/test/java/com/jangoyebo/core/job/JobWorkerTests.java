package com.jangoyebo.core.job;

import com.jangoyebo.NeedsDocker;
import com.jangoyebo.common.error.BusinessException;
import com.jangoyebo.common.error.ErrorCode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.context.SecurityContextHolder;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNoException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 폴링 워커 검증.
 *
 * <p><b>워커를 테스트가 직접 생성한다.</b> {@code @TestConfiguration} 으로 핸들러 빈을 추가하면
 * 컨텍스트 캐시 키가 달라져 Spring 컨텍스트가 하나 더 뜬다. {@code JobService} 만 주입받아
 * 실제 DB·트랜잭션을 쓰고, 핸들러는 테스트가 만든다.
 *
 * <p>애플리케이션 컨텍스트의 워커는 {@code JOB_WORKER_COUNT=0}(build.gradle)으로 꺼져 있다.
 * 켜 두면 {@code JobServiceTests} 가 큐에 넣은 작업을 가로채 그쪽 단정이 깨진다.
 *
 * <p><b>Docker가 필요하다.</b> Testcontainers 로 {@code postgres:16-alpine} 을 띄운다.
 */
@NeedsDocker
@SpringBootTest
class JobWorkerTests {

    @Autowired
    private JobService jobService;

    @Autowired
    private JobRepository jobRepository;

    @Autowired
    private JdbcTemplate jdbc;

    private Long businessId;
    private Long userId;

    @BeforeEach
    void setUp() {
        jdbc.execute("DELETE FROM core.job");

        userId = jdbc.queryForObject("""
                INSERT INTO core.users (email, password_hash, name)
                VALUES (?, 'x', '워커') RETURNING id
                """, Long.class, "worker-owner-" + System.nanoTime() + "@test.local");
        businessId = jdbc.queryForObject("""
                INSERT INTO core.business (owner_user_id, name) VALUES (?, '워커 사업장') RETURNING id
                """, Long.class, userId);

        // 워커 스레드에는 SecurityContext 가 없다. 그 조건을 그대로 재현한다.
        SecurityContextHolder.clearContext();
    }

    // --- 완결 경로 -----------------------------------------------------------

    @Test
    void completesJobWhenHandlerReturnsResult() {
        JobWorker worker = workerWith(handler(JobType.ACCOUNT_SYNC,
                job -> JobHandler.Outcome.completed(Map.of("syncedCount", 197))));
        Job queued = jobService.enqueue(businessId, userId, JobType.ACCOUNT_SYNC, Map.of());

        assertThat(worker.pollOnce()).isTrue();

        Job reloaded = jobRepository.findById(queued.getId()).orElseThrow();
        assertThat(reloaded.getStatus()).isEqualTo(JobStatus.COMPLETED);
        assertThat(reloaded.getCompletedAt()).isNotNull();
        // JSONB → Map 역직렬화에서 정수는 Integer 로 온다.
        assertThat(reloaded.getResult()).containsEntry("syncedCount", 197);
    }

    /** AI 워커 형태의 계약이다. 콜백이 올 때까지 PROCESSING 에 남아 있어야 한다. */
    @Test
    void leavesJobProcessingWhenHandlerAwaitsCallback() {
        JobWorker worker = workerWith(handler(JobType.EVIDENCE_EXTRACTION,
                job -> JobHandler.Outcome.AWAITING_CALLBACK));
        Job queued = jobService.enqueue(businessId, userId, JobType.EVIDENCE_EXTRACTION, Map.of());

        assertThat(worker.pollOnce()).isTrue();

        Job reloaded = jobRepository.findById(queued.getId()).orElseThrow();
        assertThat(reloaded.getStatus()).isEqualTo(JobStatus.PROCESSING);
        assertThat(reloaded.getStartedAt()).isNotNull();
        assertThat(reloaded.getCompletedAt()).isNull();
    }

    // --- 실패 경로 -----------------------------------------------------------

    /** 예상된 실패는 ErrorCode 를 보존한다 — 프론트가 이 값으로 분기한다(API 2.7). */
    @Test
    void failsJobWithBusinessErrorCode() {
        JobWorker worker = workerWith(handler(JobType.ACCOUNT_SYNC, job -> {
            throw new BusinessException(ErrorCode.FIN_API_FAILED, "금융망 응답이 없습니다.");
        }));
        Job queued = jobService.enqueue(businessId, userId, JobType.ACCOUNT_SYNC, Map.of());

        worker.pollOnce();

        Job reloaded = jobRepository.findById(queued.getId()).orElseThrow();
        assertThat(reloaded.getStatus()).isEqualTo(JobStatus.FAILED);
        assertThat(reloaded.getErrorCode()).isEqualTo("FIN_API_FAILED");
        assertThat(reloaded.getErrorMessage()).isEqualTo("금융망 응답이 없습니다.");
    }

    /** 핸들러 버그는 그 작업 하나만 죽이고 루프는 계속돼야 한다. */
    @Test
    void failsJobWithInternalErrorAndKeepsPolling() {
        JobWorker worker = workerWith(handler(JobType.ACCOUNT_SYNC, job -> {
            throw new IllegalStateException("핸들러 내부 버그");
        }));
        Job first = jobService.enqueue(businessId, userId, JobType.ACCOUNT_SYNC, Map.of());
        Job second = jobService.enqueue(businessId, userId, JobType.ACCOUNT_SYNC, Map.of());

        assertThatNoException().isThrownBy(worker::pollOnce);
        assertThat(jobRepository.findById(first.getId()).orElseThrow().getErrorCode())
                .isEqualTo("INTERNAL_ERROR");

        // 다음 pass 가 정상 동작해야 격리가 된 것이다.
        assertThat(worker.pollOnce()).isTrue();
        assertThat(jobRepository.findById(second.getId()).orElseThrow().getStatus())
                .isEqualTo(JobStatus.FAILED);
    }

    /**
     * 워커 스레드에는 SecurityContext 가 없다. 핸들러가 {@code BusinessGuard} 를 타는
     * 서비스를 부르면 여기서 터진다 — 그 실패 모드를 문서화된 테스트로 굳힌다.
     */
    @Test
    void handlerNeverSeesAuthentication() {
        AtomicBoolean sawAuthentication = new AtomicBoolean(true);
        JobWorker worker = workerWith(handler(JobType.ACCOUNT_SYNC, job -> {
            sawAuthentication.set(SecurityContextHolder.getContext().getAuthentication() != null);
            return JobHandler.Outcome.completed(Map.of());
        }));
        jobService.enqueue(businessId, userId, JobType.ACCOUNT_SYNC, Map.of());

        worker.pollOnce();

        assertThat(sawAuthentication).isFalse();
    }

    // --- 순회 규칙 -----------------------------------------------------------

    /** 핸들러 없는 유형은 아예 조회하지 않는다. TRANSACTION_IMPORT 를 지키는 규칙이다. */
    @Test
    void ignoresJobTypesWithoutRegisteredHandler() {
        JobWorker worker = workerWith(handler(JobType.ACCOUNT_SYNC,
                job -> JobHandler.Outcome.completed(Map.of())));
        Job orphan = jobService.enqueue(businessId, userId, JobType.EXPORT_GENERATION, Map.of());

        assertThat(worker.pollOnce()).isFalse();

        Job reloaded = jobRepository.findById(orphan.getId()).orElseThrow();
        assertThat(reloaded.getStatus()).isEqualTo(JobStatus.QUEUED);
        assertThat(reloaded.getAttemptCount()).isZero();
    }

    /** 바쁜 유형이 다른 유형을 굶기면 안 된다. 한 pass 에서 유형당 1건씩이다. */
    @Test
    void handlesOneJobPerRegisteredTypeInEachPass() {
        List<Long> handled = new ArrayList<>();
        JobWorker worker = workerWith(
                handler(JobType.ACCOUNT_SYNC, job -> {
                    handled.add(job.getId());
                    return JobHandler.Outcome.completed(Map.of());
                }),
                handler(JobType.LOAN_SYNC, job -> {
                    handled.add(job.getId());
                    return JobHandler.Outcome.completed(Map.of());
                }));
        jobService.enqueue(businessId, userId, JobType.ACCOUNT_SYNC, Map.of());
        jobService.enqueue(businessId, userId, JobType.ACCOUNT_SYNC, Map.of());
        jobService.enqueue(businessId, userId, JobType.ACCOUNT_SYNC, Map.of());
        Job loan = jobService.enqueue(businessId, userId, JobType.LOAN_SYNC, Map.of());

        worker.pollOnce();

        assertThat(handled).as("유형당 1건씩만 처리한다").hasSize(2);
        assertThat(handled).as("적게 쌓인 유형도 첫 pass 에서 처리된다").contains(loan.getId());
    }

    /** 어느 핸들러가 이기는지가 빈 순서에 달리면 재현이 안 된다. 부팅에서 멈춘다. */
    @Test
    void rejectsDuplicateHandlerForSameJobType() {
        JobHandler first = handler(JobType.ACCOUNT_SYNC, job -> JobHandler.Outcome.completed(Map.of()));
        JobHandler second = handler(JobType.ACCOUNT_SYNC, job -> JobHandler.Outcome.completed(Map.of()));

        assertThatThrownBy(() -> new JobWorker(jobService, testProperties(1), List.of(first, second)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("ACCOUNT_SYNC");
    }

    // --- 동시성 --------------------------------------------------------------

    /**
     * 워커를 여러 개 돌려도 같은 작업을 두 번 집지 않는다. {@code JobServiceTests} 가
     * {@code claimNext} 수준에서 검증한 성질이 워커 루프를 거쳐도 유지되는지 본다.
     *
     * <p>스레드는 2개다 — Hikari {@code maximum-pool-size: 10} 중 워커 몫이 2~3 이다.
     */
    @Test
    void twoWorkersClaimEachJobExactlyOnce() throws Exception {
        int jobCount = 20;
        int workerCount = 2;
        var handled = new ConcurrentLinkedQueue<Long>();

        for (int i = 0; i < jobCount; i++) {
            jobService.enqueue(businessId, userId, JobType.ACCOUNT_SYNC, Map.of("seq", i));
        }

        JobHandler counting = handler(JobType.ACCOUNT_SYNC, job -> {
            handled.add(job.getId());
            return JobHandler.Outcome.completed(Map.of());
        });
        List<JobWorker> workers = List.of(
                new JobWorker(jobService, testProperties(workerCount), List.of(counting)),
                new JobWorker(jobService, testProperties(workerCount), List.of(counting)));

        var startGate = new CountDownLatch(1);
        var finished = new CountDownLatch(workerCount);
        ExecutorService pool = Executors.newFixedThreadPool(workerCount);
        try {
            for (JobWorker worker : workers) {
                pool.submit(() -> {
                    try {
                        startGate.await();
                        while (worker.pollOnce()) {
                            // 큐가 빌 때까지 계속 집는다
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

        List<Long> all = List.copyOf(handled);
        assertThat(all).as("중복 처리가 없어야 한다").hasSameSizeAs(Set.copyOf(all));
        assertThat(all).as("유실 없이 전부 처리되어야 한다").hasSize(jobCount);
        assertThat(jobRepository.findAll())
                .allSatisfy(job -> assertThat(job.getStatus()).isEqualTo(JobStatus.COMPLETED));
    }

    // --- 기동·종료 -----------------------------------------------------------

    /** 유일하게 실제 스레드를 띄우는 테스트다. 종료 신호가 폴링 대기를 즉시 깨워야 한다. */
    @Test
    void startsAndStopsPromptly() {
        JobWorker worker = workerWith(handler(JobType.ACCOUNT_SYNC,
                job -> JobHandler.Outcome.completed(Map.of())));
        Job queued = jobService.enqueue(businessId, userId, JobType.ACCOUNT_SYNC, Map.of());

        worker.start();
        try {
            assertThat(worker.isRunning()).isTrue();
            awaitCompleted(queued.getId());
        } finally {
            long startedAt = System.currentTimeMillis();
            worker.stop();
            assertThat(System.currentTimeMillis() - startedAt)
                    .as("stop() 이 폴링 간격만큼 기다리면 안 된다")
                    .isLessThan(3_000);
        }
        assertThat(worker.isRunning()).isFalse();
    }

    // --- helpers -------------------------------------------------------------

    private JobWorker workerWith(JobHandler... handlers) {
        return new JobWorker(jobService, testProperties(1), List.of(handlers));
    }

    /** 폴링 간격을 짧게 둬야 기동 테스트가 빨리 끝난다. 상한·타임아웃은 운영값과 같다. */
    private JobProperties testProperties(int workerCount) {
        // 접수 실패 재시도 지연도 짧게 둔다 — 되돌린 작업을 같은 테스트 안에서 다시 집어야 한다.
        return new JobProperties(Duration.ofMinutes(10), 2, workerCount,
                Duration.ofMillis(10), Duration.ofMillis(50));
    }

    private JobHandler handler(JobType type, java.util.function.Function<Job, JobHandler.Outcome> body) {
        return new JobHandler() {
            @Override
            public JobType jobType() {
                return type;
            }

            @Override
            public Outcome handle(Job job) {
                return body.apply(job);
            }
        };
    }

    private void awaitCompleted(Long jobId) {
        for (int i = 0; i < 100; i++) {
            if (jobRepository.findById(jobId).orElseThrow().getStatus() == JobStatus.COMPLETED) {
                return;
            }
            try {
                Thread.sleep(50);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        throw new AssertionError("작업이 5초 안에 완료되지 않았다");
    }
}
