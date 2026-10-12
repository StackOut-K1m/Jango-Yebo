package com.jangoyebo.core.job;

import com.jangoyebo.common.error.BusinessException;
import com.jangoyebo.common.error.ErrorCode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Collections;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * {@code QUEUED} 작업을 집어 {@link JobHandler} 에게 넘기는 폴링 워커.
 *
 * <p>{@code jango-api} 안의 스레드다(별도 프로세스가 아니다). {@code @Scheduled} 가 아니라
 * 전용 풀을 쓰는 이유는 두 가지다 — {@code fixedDelay} 는 같은 메서드의 동시 실행을 막아
 * 동시성을 프로퍼티로 노출할 수 없고, 스케줄러 기본 스레드가 1개라
 * {@link JobSweeper} 와 서로를 막는다.
 *
 * <p><b>트랜잭션을 걸지 않는다.</b> 획득(짧은 트랜잭션) → 핸들러(트랜잭션 밖) → 완결(짧은
 * 트랜잭션) 이라 핸들러가 HTTP 로 몇 초를 써도 커넥션을 쥐지 않는다. 여기에
 * {@code @Transactional} 을 붙이면 Hikari 10 중 워커 몫 2~3 예산을 즉시 초과한다.
 *
 * <p><b>등록된 유형만 순회한다.</b> {@code JobType.values()} 를 돌면 핸들러 없는 유형에
 * 획득 쿼리를 낭비하고, 더 나쁘게 {@code TRANSACTION_IMPORT} 를 집으려 시도한다 —
 * CSV 임포트는 동기로 처리하고 완료된 Job 을 반환하므로 워커가 건드리면 안 된다.
 * 한 pass 에서 유형당 최대 1건만 잡아 바쁜 유형이 다른 유형을 굶기지 않는다.
 */
@Component
public class JobWorker implements SmartLifecycle {

    private static final Logger log = LoggerFactory.getLogger(JobWorker.class);

    /**
     * 루프 자체가 터졌을 때(DB 단절·풀 고갈) 로그 폭주를 막는 간격.
     * 개별 작업 실패와 달리 운영자가 조정할 이유가 없어 상수로 둔다.
     */
    private static final Duration ERROR_BACKOFF = Duration.ofSeconds(10);

    /**
     * 종료 시 실행 중 작업을 기다리는 시간.
     *
     * <p><b>프로퍼티로 열지 않는다.</b> Docker 의 기본 stop grace 가 10초다. 여기를 30초로
     * 올리면 웹서버 드레인이 끝나기 전에 SIGKILL 이 떨어진다. 5초를 쓰면 나머지 5초가
     * 드레인과 컨텍스트 종료에 남는다.
     */
    private static final Duration SHUTDOWN_GRACE = Duration.ofSeconds(5);

    private final JobService jobService;
    private final JobProperties properties;
    private final Map<JobType, JobHandler> handlers;
    private final AtomicInteger threadSeq = new AtomicInteger();

    private volatile boolean running;
    private volatile CountDownLatch stopSignal;
    private ExecutorService pool;

    public JobWorker(JobService jobService, JobProperties properties, List<JobHandler> handlers) {
        this.jobService = jobService;
        this.properties = properties;

        Map<JobType, JobHandler> byType = new EnumMap<>(JobType.class);
        for (JobHandler handler : handlers) {
            JobHandler previous = byType.put(handler.jobType(), handler);
            if (previous != null) {
                throw new IllegalStateException(
                        "JobType %s 의 핸들러가 둘이다: %s, %s".formatted(
                                handler.jobType(), previous.getClass().getName(), handler.getClass().getName()));
            }
        }
        this.handlers = Collections.unmodifiableMap(byType);

        // 기동 여부를 부팅 로그에서 바로 알 수 있게 남긴다. 핸들러가 없으면
        // isAutoStartup() 이 false 라 start() 가 아예 불리지 않아 다른 로그가 없다.
        if (this.handlers.isEmpty()) {
            log.info("등록된 JobHandler 가 없어 Job 워커는 대기 상태다 (핸들러를 @Component 로 추가하면 기동한다)");
        } else if (properties.workerCount() == 0) {
            log.info("jango.job.worker-count = 0 이라 Job 워커를 기동하지 않는다 — 담당 유형 {}",
                    this.handlers.keySet());
        }
    }

    // --- SmartLifecycle -----------------------------------------------------

    /**
     * {@code getPhase()} 를 재정의하지 않는다. 기본값 {@code Integer.MAX_VALUE} 가 가장 먼저
     * 정지되는 phase 라, 종료가 시작되는 순간 새 작업 획득을 멈춘다 — 원하는 동작이 기본값이다.
     */
    @Override
    public boolean isAutoStartup() {
        return properties.workerCount() > 0 && !handlers.isEmpty();
    }

    @Override
    public synchronized void start() {
        if (running) {
            return;
        }
        if (handlers.isEmpty()) {
            log.warn("등록된 JobHandler 가 없어 워커를 기동하지 않는다");
            return;
        }

        int count = properties.workerCount();
        stopSignal = new CountDownLatch(1);
        pool = Executors.newFixedThreadPool(count, runnable -> {
            Thread thread = new Thread(runnable, "job-worker-" + threadSeq.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        });
        running = true;
        for (int i = 0; i < count; i++) {
            pool.submit(this::loop);
        }
        log.info("Job 워커 {}개 기동 — 담당 유형 {}, 폴링 간격 {}",
                count, handlers.keySet(), properties.pollInterval());
    }

    /**
     * 처리 중이던 작업은 {@code PROCESSING} 으로 남긴다. requeue 하지 않는 이유는
     * 완료 직전 핸들러와 경합해 중복 실행을 만들고, 콜백 대기 중인 작업에는 애초에 적용할 수
     * 없기 때문이다(외부 워커는 이미 일을 시작했다). 회수는 {@link JobSweeper} 가 맡는다.
     */
    @Override
    public synchronized void stop() {
        if (!running) {
            return;
        }
        running = false;
        stopSignal.countDown();
        pool.shutdown();
        try {
            if (!pool.awaitTermination(SHUTDOWN_GRACE.toMillis(), TimeUnit.MILLISECONDS)) {
                log.warn("워커가 {}초 안에 끝나지 않아 인터럽트한다 — 처리 중이던 작업은 "
                                + "PROCESSING 으로 남고 스위퍼가 {}분 후 회수한다",
                        SHUTDOWN_GRACE.toSeconds(), properties.stallTimeout().toMinutes());
                pool.shutdownNow();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            pool.shutdownNow();
        }
        log.info("Job 워커 종료");
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    // --- 루프 ----------------------------------------------------------------

    private void loop() {
        while (running) {
            try {
                if (!pollOnce() && awaitOrStop(properties.pollInterval())) {
                    return;
                }
            } catch (Throwable t) {
                // 획득 자체가 터진 경우 = DB 단절·풀 고갈이다. 개별 작업 문제가 아니다.
                // back-off 하지 않으면 초당 수천 줄을 찍으며 hot-spin 한다.
                // Error 까지 잡는 이유는 스레드를 살려 두기 위해서다.
                log.error("워커 루프 오류 — {}초 후 재시도", ERROR_BACKOFF.toSeconds(), t);
                if (awaitOrStop(ERROR_BACKOFF)) {
                    return;
                }
            }
        }
    }

    /**
     * 등록된 유형을 한 바퀴 돌며 각 유형에서 최대 1건을 처리한다.
     *
     * <p>테스트가 직접 호출한다 — 그래야 스레드를 띄우지 않고 처리 로직만 검증할 수 있다.
     *
     * @return 한 건이라도 처리했으면 {@code true}. 전부 비었으면 {@code false}(=대기해도 된다)
     */
    boolean pollOnce() {
        boolean worked = false;
        for (Map.Entry<JobType, JobHandler> entry : handlers.entrySet()) {
            Optional<Job> claimed = jobService.claimNext(entry.getKey());
            if (claimed.isPresent()) {
                settle(claimed.get(), entry.getValue());
                worked = true;
            }
        }
        return worked;
    }

    /**
     * 작업 1건의 실패를 그 작업 하나에 가둔다.
     *
     * <p>{@code fail()} 자체가 실패하면(DB 단절) 예외가 루프로 올라가 back-off 되고,
     * Job 은 {@code PROCESSING} 으로 남아 스위퍼가 회수한다 — 최후의 안전망이 이미 있다.
     */
    private void settle(Job job, JobHandler handler) {
        try {
            switch (handler.handle(job)) {
                // sealed interface 라 exhaustive 다. default 를 두지 않으므로 Outcome 이
                // 늘어나면 컴파일이 깨져 워커 수정을 강제한다.
                case JobHandler.Outcome.Completed(Map<String, Object> result) ->
                        jobService.complete(job.getId(), result);
                case JobHandler.Outcome.AwaitingCallback ignored ->
                        log.debug("작업 {} 외부 응답 대기 — PROCESSING 유지", job.getId());
                // 외부가 받지 못했다. PROCESSING 에 가둬 두면 스위퍼의 10분을 기다리는 동안
                // 8.5 같은 재실행 문까지 그 작업을 돌려주며 막힌다(실측 20분 정지).
                case JobHandler.Outcome.NotAccepted ignored -> {
                    jobService.requeueAfterRejection(job.getId(), properties.rejectionRetryDelay());
                    log.info("작업 {} 접수되지 않음 — {} 뒤 다시 시도한다(재시도 횟수에 세지 않음)",
                            job.getId(), properties.rejectionRetryDelay());
                }
            }
        } catch (BusinessException e) {
            // 예상된 실패. 프론트가 받는 error.code 를 그대로 보존한다(API 2.7).
            log.warn("작업 {} 실패 code={} msg={}", job.getId(), e.errorCode(), e.getMessage());
            jobService.fail(job.getId(), e.errorCode(), e.getMessage());
        } catch (Exception e) {
            // 핸들러 버그. 내부 메시지를 사용자에게 노출하지 않는다.
            log.error("작업 {} 처리 중 예상 못 한 오류", job.getId(), e);
            jobService.fail(job.getId(), ErrorCode.INTERNAL_ERROR, ErrorCode.INTERNAL_ERROR.defaultMessage());
        }
    }

    /**
     * {@code Thread.sleep} 대신 종료 신호를 기다린다. {@code stop()} 이 latch 를 내리면
     * 즉시 깨어나므로 종료가 폴링 간격만큼 늦지 않는다.
     *
     * @return 종료해야 하면 {@code true}
     */
    private boolean awaitOrStop(Duration duration) {
        try {
            return stopSignal.await(duration.toMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return true;
        }
    }
}
