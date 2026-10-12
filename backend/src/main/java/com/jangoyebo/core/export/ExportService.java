package com.jangoyebo.core.export;

import com.jangoyebo.common.error.BusinessException;
import com.jangoyebo.common.error.ErrorCode;
import com.jangoyebo.common.tenancy.BusinessGuard;
import com.jangoyebo.core.job.Job;
import com.jangoyebo.core.job.JobRepository;
import com.jangoyebo.core.job.JobService;
import com.jangoyebo.core.job.JobStatus;
import com.jangoyebo.core.job.JobType;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 신고 준비자료 내보내기 (API 14.2 · P0-12 부속 산출물).
 *
 * <p><b>전용 테이블이 없다.</b> ERD 가 {@code EXPORT_JOB} 을 {@code JOB} 으로 일반화했고,
 * 결과는 {@code JOB.result} 의 {@code storageKey}·{@code expiresAt}·요약이다. 그래서
 * <b>{@code exportId} 는 {@code jobId} 와 같은 값이다</b> — 명세 예시는 둘을 다른 번호로
 * 적었지만 그 번호를 따로 두려면 테이블이 하나 더 필요하고, 그것이 ERD 가 없앤 것이다.
 * 응답에는 두 키를 다 넣어 화면이 명세대로 읽을 수 있게 한다.
 *
 * <p><b>{@code enqueueIfIdle} 을 쓰지 않는다.</b> 그쪽은 (사업장, 종류)로만 중복을 막아서,
 * 1~8월 패키지를 만드는 중에 1~12월을 요청하면 <b>기간이 다른데도 앞의 것을 돌려준다.</b>
 * 내보내기는 매 요청이 다른 파일이고 이력이 화면의 목적이므로 늘 새로 만든다.
 */
@Service
public class ExportService {

    /** 다운로드 보관 기간. 명세가 값을 정하지 않아 팀 결정으로 7일을 둔다. */
    static final int RETENTION_DAYS = 7;

    private final JobService jobs;
    private final JobRepository repository;
    private final BusinessGuard guard;

    public ExportService(JobService jobs, JobRepository repository, BusinessGuard guard) {
        this.jobs = jobs;
        this.repository = repository;
        this.guard = guard;
    }

    @Transactional
    public Job request(Long businessId, Long requestedBy, ExportType exportType,
                       LocalDate periodStart, LocalDate periodEnd, boolean includeEvidenceFiles) {
        if (periodEnd.isBefore(periodStart)) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED,
                    "대상 종료일이 시작일보다 앞섭니다.");
        }

        Map<String, Object> params = new LinkedHashMap<>();
        params.put("exportType", exportType.name());
        params.put("periodStart", periodStart.toString());
        params.put("periodEnd", periodEnd.toString());
        params.put("includeEvidenceFiles", includeEvidenceFiles);
        return jobs.enqueue(businessId, requestedBy, JobType.EXPORT_GENERATION, params);
    }

    @Transactional(readOnly = true)
    public Map<String, Object> detail(Long exportId) {
        return describe(owned(exportId));
    }

    @Transactional(readOnly = true)
    public List<Map<String, Object>> list(Long businessId) {
        guard.assertOwnsBusiness(businessId);
        return repository
                .findByBusinessIdAndJobTypeOrderByIdDesc(businessId, JobType.EXPORT_GENERATION)
                .stream()
                .map(this::describe)
                .toList();
    }

    /**
     * 내려받을 파일. <b>만료를 「아직 안 됐다」로 말하지 않는다</b> — 끝났다가 사라진 것이라
     * 기다리면 안 되고 다시 생성해야 한다.
     */
    @Transactional(readOnly = true)
    public Downloadable downloadable(Long exportId) {
        Job job = owned(exportId);
        if (job.getStatus() != JobStatus.COMPLETED) {
            throw new BusinessException(ErrorCode.EXPORT_NOT_READY);
        }
        Map<String, Object> result = job.getResult() == null ? Map.of() : job.getResult();
        Instant expiresAt = instant(result.get("expiresAt"));
        if (expiresAt != null && !expiresAt.isAfter(Instant.now())) {
            throw new BusinessException(ErrorCode.EXPORT_EXPIRED);
        }
        String storageKey = (String) result.get("storageKey");
        if (storageKey == null) {
            // 완료로 닫혔는데 결과가 비었다 — 핸들러가 바뀌었거나 행이 손상된 것이다.
            throw new BusinessException(ErrorCode.INTERNAL_ERROR, "내보내기 결과가 비어 있습니다.");
        }
        return new Downloadable(storageKey, (String) result.get("fileName"));
    }

    /**
     * 직접 경로 조회라 소유권을 명시적으로 본다. 남의 것이면 <b>403</b> 이다 —
     * {@code GET /jobs/{id}} 가 404 인 것과 다른데, 저쪽은 존재 자체를 숨기는 범용 경로이고
     * 여기는 사업장 자원이라 {@code /tax-drafts/{id}} 와 같은 규칙을 따른다.
     */
    private Job owned(Long exportId) {
        Job job = repository.findById(exportId)
                .orElseThrow(() -> new BusinessException(ErrorCode.EXPORT_NOT_FOUND));
        if (job.getJobType() != JobType.EXPORT_GENERATION || job.getBusinessId() == null) {
            // 다른 종류의 Job 번호로 내보내기를 조회한 것이다. 그 작업이 있다는 사실을
            // 알려 줄 이유가 없으므로 「없다」로 답한다.
            throw new BusinessException(ErrorCode.EXPORT_NOT_FOUND);
        }
        guard.assertOwned(job);
        return job;
    }

    private Map<String, Object> describe(Job job) {
        Map<String, Object> params = job.getParams() == null ? Map.of() : job.getParams();
        Map<String, Object> result = job.getResult() == null ? Map.of() : job.getResult();

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("exportId", job.getId());
        body.put("jobId", job.getId());
        body.put("exportType", params.get("exportType"));
        body.put("status", statusOf(job));
        body.put("periodStart", params.get("periodStart"));
        body.put("periodEnd", params.get("periodEnd"));
        body.put("requestedAt", job.getCreatedAt());

        // 진행 중이면 아래 칸들은 키가 없다. 빈 문자열이나 0 으로 채우면 화면이
        // 「크기 0바이트짜리 파일이 있다」로 읽는다(구현현황 3.3).
        putIfPresent(body, "fileName", result.get("fileName"));
        putIfPresent(body, "fileSize", result.get("fileSize"));
        putIfPresent(body, "downloadExpiresAt", result.get("expiresAt"));
        putIfPresent(body, "summary", result.get("summary"));
        putIfPresent(body, "generatedAt", job.getCompletedAt());
        putIfPresent(body, "errorCode", job.getErrorCode());
        putIfPresent(body, "errorMessage", job.getErrorMessage());
        return body;
    }

    /**
     * Job 상태를 명세 14.2 의 어휘로 옮긴다. 대기열에 있는 것과 처리 중인 것을 화면이 갈라
     * 볼 이유가 없어 둘 다 {@code PROCESSING} 이다.
     */
    private static String statusOf(Job job) {
        return switch (job.getStatus()) {
            case COMPLETED -> "COMPLETED";
            case FAILED -> "FAILED";
            case QUEUED, PROCESSING -> "PROCESSING";
        };
    }

    private static void putIfPresent(Map<String, Object> body, String key, Object value) {
        if (value != null) {
            body.put(key, value);
        }
    }

    /** JSONB 를 거쳐 오면 {@code Instant} 가 아니라 ISO 문자열이다. */
    private static Instant instant(Object raw) {
        return raw == null ? null : Instant.parse(raw.toString());
    }

    public record Downloadable(String storageKey, String fileName) {
    }
}
