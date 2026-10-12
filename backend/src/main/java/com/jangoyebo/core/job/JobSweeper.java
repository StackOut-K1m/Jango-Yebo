package com.jangoyebo.core.job;

import com.jangoyebo.common.error.ErrorCode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;

/**
 * 멈춘 작업 회수.
 *
 * <p>{@code jango-ai} 는 요청을 받고 콜백으로 결과를 돌려주는데,
 * <b>콜백이 유실되면 작업이 {@code PROCESSING}에 영구히 남는다.</b>
 * API 명세에 이 경우의 처리가 없어 백엔드에서 보완한 장치다
 * (아키텍처 §7.2).
 *
 * <p>1회는 재시도하고, 그래도 안 되면 실패로 확정해 사용자에게 상태를 보여준다
 * (공통규약 23장 — "10분 초과 시 FAILED 전이 + 재시도 1회").
 *
 * <p><b>역할이 하나 더 있다 — 재배포 회수.</b> 애플리케이션이 종료될 때 처리 중이던 작업은
 * {@code PROCESSING} 으로 남는다. 워커가 종료 시점에 requeue 하지 않는 이유는 완료 직전
 * 핸들러와 경합해 중복 실행을 만들고, 콜백 대기 중인 작업에는 애초에 적용할 수 없기
 * 때문이다. 그 회수도 이 스위퍼가 맡는다. <b>지우지 말 것.</b>
 */
@Component
public class JobSweeper {

    private static final Logger log = LoggerFactory.getLogger(JobSweeper.class);

    private final JobRepository repository;
    private final JobProperties properties;

    public JobSweeper(JobRepository repository, JobProperties properties) {
        this.repository = repository;
        this.properties = properties;
    }

    // fixedDelay 는 컴파일 상수라 프로퍼티로 뺄 수 없다. fixedDelayString 이 유일한 방법이다.
    @Scheduled(fixedDelayString = "${jango.job.sweep-interval}")
    @Transactional
    public void sweepStalledJobs() {
        Instant threshold = Instant.now().minus(properties.stallTimeout());
        List<Job> stalled = repository.findByStatusAndStartedAtBefore(JobStatus.PROCESSING, threshold);
        if (stalled.isEmpty()) {
            return;
        }

        int requeued = 0;
        int failed = 0;
        for (Job job : stalled) {
            if (job.getAttemptCount() >= properties.maxAttempts()) {
                // 상한 소진. 사용자에게 상태를 보여줘야 하므로 실패로 확정한다.
                job.fail(ErrorCode.JOB_TIMEOUT.name(),
                        "%d분 안에 완료되지 않았습니다 (시도 %d/%d)".formatted(
                                properties.stallTimeout().toMinutes(),
                                job.getAttemptCount(), properties.maxAttempts()));
                failed++;
            } else {
                // requeue 하면 startedAt 이 null 이 되므로 다음 스위프에서 다시 잡히지 않고,
                // 워커가 집으면 CLAIM_SQL 이 startedAt 과 attempt_count 를 함께 올린다.
                job.requeue();
                requeued++;
            }
        }

        log.warn("멈춘 작업 {}건 — 재큐 {} · 실패확정 {} (기준 {}분 초과, 시도 상한 {}회)",
                stalled.size(), requeued, failed,
                properties.stallTimeout().toMinutes(), properties.maxAttempts());
    }
}
