package com.jangoyebo.core.export;

import com.jangoyebo.common.error.BusinessException;
import com.jangoyebo.common.error.ErrorCode;
import com.jangoyebo.core.business.BusinessRepository;
import com.jangoyebo.core.job.Job;
import com.jangoyebo.core.job.JobHandler;
import com.jangoyebo.core.job.JobType;
import com.jangoyebo.core.storage.UploadStorage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 신고 준비자료 패키지 생성 작업 (API 14.2 · P0-12 부속 산출물).
 *
 * <p><b>콜백을 기다리지 않는다.</b> AI 워커를 부르지 않는 P0 경로라 여기서 파일까지 만들고
 * 닫는다 — 14.3 초안 집계와 같은 모양이다.
 *
 * <p><b>{@code stall-timeout}(10분) 안에 끝나야 한다.</b> 넘기면 아직 쓰는 중인데 스위퍼가
 * 회수해 다른 워커가 같은 키에 겹쳐 쓴다. 지금은 안내문 한 장이라 여유가 크지만, 증빙 원본을
 * 담기 시작하면 이 줄을 다시 봐야 한다.
 */
@Component
public class ExportGenerationHandler implements JobHandler {

    private static final Logger log = LoggerFactory.getLogger(ExportGenerationHandler.class);
    private static final ZoneId KST = ZoneId.of("Asia/Seoul");

    private final ExportPackageWriter writer;
    private final ExportSummaryReader summaries;
    private final BusinessRepository businesses;
    private final UploadStorage storage;
    private final Clock clock;

    public ExportGenerationHandler(ExportPackageWriter writer, ExportSummaryReader summaries,
                                   BusinessRepository businesses, UploadStorage storage,
                                   Clock clock) {
        this.writer = writer;
        this.summaries = summaries;
        this.businesses = businesses;
        this.storage = storage;
        this.clock = clock;
    }

    @Override
    public JobType jobType() {
        return JobType.EXPORT_GENERATION;
    }

    @Override
    public Outcome handle(Job job) {
        Map<String, Object> params = job.getParams() == null ? Map.of() : job.getParams();
        LocalDate from = date(params.get("periodStart"), job.getId());
        LocalDate to = date(params.get("periodEnd"), job.getId());
        boolean includeEvidenceFiles = Boolean.TRUE.equals(params.get("includeEvidenceFiles"));

        Long businessId = job.getBusinessId();
        // 워커 스레드에는 SecurityContext 가 없어 BusinessGuard 를 탈 수 없다. Job 이 들고
        // 있는 businessId 가 범위이며, 그 범위는 접수할 때 이미 검증했다.
        String businessName = businesses.findById(businessId)
                .orElseThrow(() -> new BusinessException(ErrorCode.BUSINESS_NOT_FOUND))
                .getName();

        Instant now = clock.instant();
        String storageKey = storage.exportKey(businessId, job.getId());
        long size = writer.write(storageKey, businessId, businessName, from, to, now.atZone(KST),
                includeEvidenceFiles);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("storageKey", storageKey);
        result.put("fileName", writer.fileNameOf(businessName, from, to));
        result.put("fileSize", size);
        result.put("expiresAt",
                now.plus(ExportService.RETENTION_DAYS, ChronoUnit.DAYS).toString());
        result.put("summary", summaries.read(businessId, from, to));

        log.info("신고 준비자료 생성 완료 — 작업 {} · 사업장 {} · 기간 {}~{} · {}바이트",
                job.getId(), businessId, from, to, size);
        return Outcome.completed(result);
    }

    private static LocalDate date(Object raw, Long jobId) {
        if (raw == null) {
            // 큐에 넣은 쪽의 버그다. 재시도해도 같으므로 즉시 실패로 확정한다.
            throw new BusinessException(ErrorCode.VALIDATION_FAILED,
                    "작업 %d 에 대상 기간이 없습니다.".formatted(jobId));
        }
        return LocalDate.parse(raw.toString());
    }
}
