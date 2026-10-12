package com.jangoyebo.core.loan;

import com.jangoyebo.core.job.JobService;
import com.jangoyebo.core.job.JobType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * 일일 대출 동기화 배치 (API 16.1 · P1-13).
 *
 * <p><b>그동안 수동 호출만 있었다.</b> {@code POST /businesses/{id}/loans/sync} 를 누군가
 * 누르지 않으면 대출 목록이 영영 그 자리였다 — 화면은 도는데 데이터가 늙어 간다. 대출은
 * 상환 회차가 예정 거래로 들어가 곡선을 깎으므로, 늙은 대출은 <b>곡선을 낙관적으로 만든다.</b>
 *
 * <p><b>03:30 인 이유.</b> 예측 재계산이 04:00 이다({@code ForecastBatchScheduler}).
 * 대출을 먼저 맞춰 놓아야 그날 곡선이 새 상환 회차를 반영한다 — 순서가 반대면 하루 늦는다.
 *
 * <h2>스케줄러 스레드는 하나다</h2>
 *
 * <p>Spring 기본 풀 크기가 1이고 {@code JobSweeper}·예측 배치가 같은 풀을 쓴다. 그래서 이
 * 메서드는 <b>Job 을 큐에 넣기만 하고 즉시 반환한다</b> — 금융망 왕복은 워커 스레드가 한다.
 * 여기서 동기화까지 하면 그동안 스위퍼가 멈춰 갇힌 Job 이 회수되지 않는다.
 */
@Component
public class LoanSyncScheduler {

    private static final Logger log = LoggerFactory.getLogger(LoanSyncScheduler.class);

    private final LoanSyncTargetReader targets;
    private final JobService jobs;

    public LoanSyncScheduler(LoanSyncTargetReader targets, JobService jobs) {
        this.targets = targets;
        this.jobs = jobs;
    }

    /**
     * 대상 사업장마다 {@code LOAN_SYNC} 를 하나씩 건다.
     *
     * <p><b>{@code enqueueIfIdle} 다.</b> 어제 것이 아직 안 끝났으면 걸지 않는다 — 금융망이
     * 느리거나 막힌 날 같은 사업장 Job 이 매일 한 건씩 쌓이면, 풀리는 날 그만큼을 한꺼번에
     * 왕복한다.
     *
     * <p><b>한 건이 실패해도 나머지를 계속 건다.</b> 트랜잭션을 통째로 열지 않는 이유가
     * 이것이다 — 사업장 하나 때문에 그날 동기화가 통째로 없어지는 쪽이 훨씬 나쁘다.
     */
    @Scheduled(cron = "${jango.loan.sync-cron:0 30 3 * * *}", zone = "Asia/Seoul")
    public void scheduleDailySync() {
        var all = targets.findAll();
        int queued = 0;
        int skipped = 0;
        int failed = 0;

        for (LoanSyncTargetReader.Target target : all) {
            try {
                boolean enqueued = jobs.enqueueIfIdle(target.businessId(), target.ownerUserId(),
                        JobType.LOAN_SYNC, Map.of()).isPresent();
                if (enqueued) {
                    queued++;
                } else {
                    skipped++;
                }
            } catch (RuntimeException e) {
                failed++;
                log.warn("대출 동기화를 걸지 못했다 — 사업장 {}", target.businessId(), e);
            }
        }

        log.info("일일 대출 동기화 예약 — 대상 {}곳 · 접수 {} · 진행 중이라 건너뜀 {} · 실패 {}",
                all.size(), queued, skipped, failed);
    }
}
