package com.jangoyebo.core.ai;

import com.jangoyebo.common.error.BusinessException;
import com.jangoyebo.common.error.ErrorCode;
import com.jangoyebo.core.job.Job;
import com.jangoyebo.core.job.JobHandler;
import com.jangoyebo.core.job.JobType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * 증빙 OCR 추출 작업을 AI 워커에 접수한다 (명세 20.4).
 *
 * <p><b>워커의 첫 핸들러다.</b> 접수만 하고 결과는 기다리지 않는다 —
 * {@code AWAITING_CALLBACK} 을 반환해 Job 을 {@code PROCESSING} 에 남기고,
 * {@link AiCallbackController} 가 콜백을 받아 완결한다.
 *
 * <p><b>명세 20.2 와의 의도적 편차</b>: 명세는 "워커가 접수 전에 거절하면 Job 이
 * {@code PROCESSING} 에 들어가지 않는다"고 하지만, 획득 후 호출하는 구조에서는 이미
 * {@code PROCESSING} 이다. 4xx 를 받는 즉시 실패로 확정해 사용자가 스위퍼 임계값(10분)을
 * 기다리지 않게 하는 것으로 갈음한다.
 *
 * <p>{@code Job.params} 에 {@code evidenceId} 와 {@code fileObjectKey} 가 있어야 한다.
 * 증빙 업로드 API(P0-10)가 붙기 전까지는 테스트가 직접 넣는다.
 */
@Component
public class EvidenceExtractionHandler implements JobHandler {

    private static final Logger log = LoggerFactory.getLogger(EvidenceExtractionHandler.class);
    private static final String PATH = "/internal/v1/ai/evidence-extraction";

    private final AiWorkerClient client;

    public EvidenceExtractionHandler(AiWorkerClient client) {
        this.client = client;
    }

    @Override
    public JobType jobType() {
        return JobType.EVIDENCE_EXTRACTION;
    }

    @Override
    public Outcome handle(Job job) {
        var request = new ExtractionRequest(
                job.getId(),
                requiredLong(job, "evidenceId"),
                requiredString(job, "fileObjectKey"),
                optionalString(job, "evidenceTypeHint", "RECEIPT"),
                // AUTO 는 OCR_PIPELINE 공급자가 선정될 때까지 VLM 이다(99_구현현황 §7).
                optionalString(job, "preferredPipeline", "AUTO"),
                // 재추출에서만 THOROUGH 로 올라간다. 전건에 쓰면 이미지 토큰이 +52% 다.
                optionalString(job, "extractionMode", "STANDARD"));

        try {
            client.requestAsync(PATH, job.getId(), request);
            return Outcome.AWAITING_CALLBACK;
        } catch (AiWorkerException e) {
            if (e.isPermanent()) {
                // 재시도해도 같은 결과다. 즉시 실패로 확정한다.
                throw new BusinessException(ErrorCode.AI_ANALYSIS_FAILED, e.getMessage());
            }
            // 5xx·연결 실패. 워커가 이 작업을 받지 못했으므로 대기열로 되돌린다 —
            // 외부에 일이 떠 있지 않아 다시 보내도 중복이 없고, 배포·재시작 중이면 수 초 뒤
            // 살아난다. 접수된 뒤 콜백이 없는 것과는 위험이 달라 대기도 달라야 한다.
            log.warn("작업 {} 워커 접수 실패 — 대기열로 되돌린다: {}",
                    job.getId(), e.getMessage());
            return Outcome.NOT_ACCEPTED;
        }
    }

    /**
     * 워커 요청 본문 (명세 20.4 · {@code ai/app/ocr/schemas.py} 의
     * {@code EvidenceExtractionRequest}).
     */
    record ExtractionRequest(Long jobId,
                             Long evidenceId,
                             String fileObjectKey,
                             String evidenceTypeHint,
                             String preferredPipeline,
                             String extractionMode) {
    }

    /** JSONB 역직렬화에서 정수는 {@code Integer} 로 온다. */
    private Long requiredLong(Job job, String key) {
        Object value = param(job, key);
        if (value instanceof Number number) {
            return number.longValue();
        }
        throw missing(job, key);
    }

    private String requiredString(Job job, String key) {
        Object value = param(job, key);
        if (value instanceof String text && !text.isBlank()) {
            return text;
        }
        throw missing(job, key);
    }

    private String optionalString(Job job, String key, String fallback) {
        Object value = param(job, key);
        return value instanceof String text && !text.isBlank() ? text : fallback;
    }

    private Object param(Job job, String key) {
        Map<String, Object> params = job.getParams();
        return params == null ? null : params.get(key);
    }

    /** 큐에 넣은 쪽의 버그다. 재시도해도 같으므로 즉시 실패로 확정한다. */
    private BusinessException missing(Job job, String key) {
        return new BusinessException(ErrorCode.VALIDATION_FAILED,
                "작업 %d 의 params 에 %s 가 없습니다.".formatted(job.getId(), key));
    }
}
