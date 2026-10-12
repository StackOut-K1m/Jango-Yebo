package com.jangoyebo.core.tax;

import com.jangoyebo.common.error.BusinessException;
import com.jangoyebo.common.error.ErrorCode;
import com.jangoyebo.core.job.Job;
import com.jangoyebo.core.job.JobHandler;
import com.jangoyebo.core.job.JobType;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * 신고서 초안 집계 작업 (API 14.3 · P0-12).
 *
 * <p><b>다른 핸들러와 달리 콜백을 기다리지 않는다.</b> AI 워커를 부르지 않는 P0 경로라
 * (공통규약 23장) 여기서 계산까지 끝내고 {@code Completed} 로 닫는다.
 *
 * <p>결과에는 {@code draftId} 만 남긴다 — 집계 결과는 {@code TAX_FILING_DRAFT.fields} 에
 * 있고, Job 결과에 복사해 두면 둘이 어긋날 자리가 생긴다(ERD).
 */
@Component
public class TaxDraftGenerationHandler implements JobHandler {

    private final TaxDraftAggregator aggregator;
    private final TaxDraftService service;

    public TaxDraftGenerationHandler(TaxDraftAggregator aggregator, TaxDraftService service) {
        this.aggregator = aggregator;
        this.service = service;
    }

    @Override
    public JobType jobType() {
        return JobType.TAX_DRAFT_GENERATION;
    }

    @Override
    public Outcome handle(Job job) {
        Object raw = job.getParams() == null ? null : job.getParams().get("draftId");
        if (!(raw instanceof Number number)) {
            // 큐에 넣은 쪽의 버그다. 재시도해도 같으므로 즉시 실패로 확정한다.
            throw new BusinessException(ErrorCode.VALIDATION_FAILED,
                    "작업 %d 에 draftId 가 없습니다.".formatted(job.getId()));
        }
        long draftId = number.longValue();

        try {
            aggregator.generate(draftId);
        } catch (RuntimeException e) {
            // 초안을 PROCESSING 에 남겨 두면 화면이 영영 「집계 중」을 돈다. 실패를 적는 것은
            // 별도 트랜잭션이라 집계 되감기에 휩쓸리지 않는다.
            service.markFailed(draftId);
            throw e;
        }
        return Outcome.completed(Map.of("draftId", draftId));
    }
}
