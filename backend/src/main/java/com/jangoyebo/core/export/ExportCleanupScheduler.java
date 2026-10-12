package com.jangoyebo.core.export;

import com.jangoyebo.core.job.Job;
import com.jangoyebo.core.job.JobRepository;
import com.jangoyebo.core.job.JobStatus;
import com.jangoyebo.core.job.JobType;
import com.jangoyebo.core.storage.UploadStorage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;

/**
 * 만료된 준비자료 파일 정리 (API 14.2).
 *
 * <p><b>파일만 지우고 Job 행은 남긴다.</b> 그 행이 곧 내보내기 이력이라(ERD §1.4 — 전용
 * 테이블을 {@code JOB} 으로 일반화했다) 지우면 화면의 목록에서 과거 기록이 통째로 사라진다.
 * 만료 뒤 다운로드는 {@code EXPORT_EXPIRED} 로 답하고 화면이 재생성을 권한다.
 *
 * <p>개인정보가 든 파일을 기한 없이 두지 않는 것이 목적이라, 지우다 한 건이 실패해도 나머지를
 * 계속 지운다 — 한 건 때문에 전체가 남는 쪽이 더 나쁘다.
 */
@Component
public class ExportCleanupScheduler {

    private static final Logger log = LoggerFactory.getLogger(ExportCleanupScheduler.class);

    /**
     * 만료 시각을 지나도 <b>하루 더 기다렸다가</b> 지운다. {@code expiresAt} 은 결과에 적힌
     * 값이고 정리는 배치라, 경계에서 바로 지우면 화면이 「받을 수 있다」고 보여 주는 동안
     * 파일이 사라져 있을 수 있다.
     */
    private static final int GRACE_DAYS = 1;

    private final JobRepository jobs;
    private final UploadStorage storage;
    private final Clock clock;

    public ExportCleanupScheduler(JobRepository jobs, UploadStorage storage, Clock clock) {
        this.jobs = jobs;
        this.storage = storage;
        this.clock = clock;
    }

    @Scheduled(cron = "${jango.export.cleanup-cron:0 40 4 * * *}", zone = "Asia/Seoul")
    @Transactional(readOnly = true)
    public void deleteExpiredFiles() {
        Instant now = clock.instant();
        Instant completedBefore = now.minus(
                ExportService.RETENTION_DAYS + (long) GRACE_DAYS, ChronoUnit.DAYS);

        List<Job> candidates = jobs.findByJobTypeAndStatusAndCompletedAtBefore(
                JobType.EXPORT_GENERATION, JobStatus.COMPLETED, completedBefore);

        int deleted = 0;
        for (Job job : candidates) {
            Map<String, Object> result = job.getResult();
            Object key = result == null ? null : result.get("storageKey");
            if (key != null && storage.deleteIfExists(key.toString())) {
                deleted++;
            }
        }
        if (deleted > 0) {
            log.info("만료된 준비자료 정리 — {}건 삭제 (보관 {}일)", deleted, ExportService.RETENTION_DAYS);
        }
    }
}
