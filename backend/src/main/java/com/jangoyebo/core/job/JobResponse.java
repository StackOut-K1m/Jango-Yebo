package com.jangoyebo.core.job;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Map;

/** {@code GET /jobs/{jobId}} 응답 (API 명세 2.7). */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record JobResponse(
        Long jobId,
        JobType jobType,
        JobStatus status,
        BigDecimal progress,
        Map<String, Object> resultRef,
        ErrorBody error,
        Instant createdAt,
        Instant completedAt
) {

    public static JobResponse from(Job job) {
        return new JobResponse(
                job.getId(),
                job.getJobType(),
                job.getStatus(),
                job.getProgress(),
                job.getResult(),
                job.getErrorCode() == null ? null : new ErrorBody(job.getErrorCode(), job.getErrorMessage()),
                job.getCreatedAt(),
                job.getCompletedAt());
    }

    /** 실패한 작업의 오류는 공통 Envelope 과 같은 모양이다. */
    public record ErrorBody(String code, String message) {
    }
}
