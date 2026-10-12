package com.jangoyebo.core.job;

import com.jangoyebo.common.response.ApiResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 비동기 작업 폴링 (API 명세 2.7).
 *
 * <p>계좌 동기화·CSV 업로드·내보내기 등이 {@code 202}와 함께 jobId 를 주면
 * 클라이언트는 이 엔드포인트를 폴링한다. 별도 푸시 채널은 두지 않는다.
 */
@RestController
@RequestMapping("/api/v1/jobs")
public class JobController {

    private final JobService jobService;

    public JobController(JobService jobService) {
        this.jobService = jobService;
    }

    @GetMapping("/{jobId}")
    public ApiResponse<JobResponse> get(@PathVariable Long jobId) {
        return ApiResponse.ok(JobResponse.from(jobService.getOwnedJob(jobId)));
    }
}
