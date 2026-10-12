package com.jangoyebo.core.ai;

import com.jangoyebo.common.error.BusinessException;
import com.jangoyebo.common.error.ErrorCode;
import com.jangoyebo.core.ai.EvidenceExtractionHandler.ExtractionRequest;
import com.jangoyebo.core.job.Job;
import com.jangoyebo.core.job.JobHandler;
import com.jangoyebo.core.job.JobType;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.client.RestClient;

import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 증빙 추출 핸들러 검증.
 *
 * <p><b>Spring 컨텍스트도 Docker 도 쓰지 않는다.</b> 검증할 것이 "params 를 어떻게 읽고,
 * 워커 실패를 어떻게 Job 운명으로 옮기는가"뿐이어서 워커 클라이언트만 대역으로 바꾸면 된다.
 */
class EvidenceExtractionHandlerTests {

    @Test
    void sendsRequestBuiltFromJobParamsAndAwaitsCallback() {
        var sent = new AtomicReference<Object>();
        var handler = new EvidenceExtractionHandler(clientThat((path, jobId, body) -> {
            assertThat(path).isEqualTo("/internal/v1/ai/evidence-extraction");
            sent.set(body);
        }));

        JobHandler.Outcome outcome = handler.handle(job(Map.of(
                "evidenceId", 501,
                "fileObjectKey", "evidence/2026/09/abc.jpg",
                "evidenceTypeHint", "TAX_INVOICE")));

        assertThat(outcome).isEqualTo(JobHandler.Outcome.AWAITING_CALLBACK);
        assertThat(sent.get()).isInstanceOf(ExtractionRequest.class);
        ExtractionRequest request = (ExtractionRequest) sent.get();
        // JSONB 에서 정수는 Integer 로 오므로 Long 변환이 필요하다.
        assertThat(request.evidenceId()).isEqualTo(501L);
        assertThat(request.fileObjectKey()).isEqualTo("evidence/2026/09/abc.jpg");
        assertThat(request.evidenceTypeHint()).isEqualTo("TAX_INVOICE");
        assertThat(request.preferredPipeline())
                .as("생략하면 AUTO — OCR_PIPELINE 공급자 선정 전까지 VLM 이다")
                .isEqualTo("AUTO");
    }

    @Test
    void defaultsEvidenceTypeHintToReceipt() {
        var sent = new AtomicReference<Object>();
        var handler = new EvidenceExtractionHandler(clientThat((path, jobId, body) -> sent.set(body)));

        handler.handle(job(Map.of("evidenceId", 1, "fileObjectKey", "k")));

        assertThat(((ExtractionRequest) sent.get()).evidenceTypeHint()).isEqualTo("RECEIPT");
    }

    /** 큐에 넣은 쪽의 버그다. 재시도해도 같으므로 즉시 실패로 확정한다. */
    @Test
    void failsWhenRequiredParamIsMissing() {
        var handler = new EvidenceExtractionHandler(clientThat((path, jobId, body) -> {
            throw new AssertionError("params 가 없으면 워커를 부르지 않아야 한다");
        }));

        assertThatThrownBy(() -> handler.handle(job(Map.of("fileObjectKey", "k"))))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("evidenceId");

        assertThatThrownBy(() -> handler.handle(job(Map.of("evidenceId", 1))))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("fileObjectKey");
    }

    @Test
    void failsWhenParamsAreNull() {
        var handler = new EvidenceExtractionHandler(clientThat((path, jobId, body) -> {
        }));

        assertThatThrownBy(() -> handler.handle(job(null)))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).errorCode())
                .isEqualTo(ErrorCode.VALIDATION_FAILED);
    }

    /** 4xx 는 재시도해도 같은 결과다. 사용자가 스위퍼 임계값을 기다리지 않게 즉시 끝낸다. */
    @Test
    void permanentWorkerFailureFailsTheJob() {
        var handler = new EvidenceExtractionHandler(clientThat((path, jobId, body) -> {
            throw new AiWorkerException(HttpStatus.UNPROCESSABLE_CONTENT,
                    "AI_ANALYSIS_FAILED", "PIPELINE_UNAVAILABLE");
        }));

        assertThatThrownBy(() -> handler.handle(job(Map.of("evidenceId", 1, "fileObjectKey", "k"))))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).errorCode())
                .isEqualTo(ErrorCode.AI_ANALYSIS_FAILED);
    }

    /**
     * 5xx·연결 실패는 Job 을 종결하지 않는다. <b>대기열로 되돌린다</b> — 워커가 이 작업을
     * 받지 못했으므로 다시 보내도 중복이 없고, 배포·재시작 중이면 수 초 뒤 살아난다.
     */
    @Test
    void temporaryWorkerFailureSendsTheJobBackToTheQueue() {
        var handler = new EvidenceExtractionHandler(clientThat((path, jobId, body) -> {
            throw new AiWorkerException("연결 실패", new RuntimeException("connection refused"));
        }));

        assertThat(handler.handle(job(Map.of("evidenceId", 1, "fileObjectKey", "k"))))
                .isEqualTo(JobHandler.Outcome.NOT_ACCEPTED);
    }

    // --- helpers -------------------------------------------------------------

    private interface Call {
        void accept(String path, Long jobId, Object body);
    }

    /** {@code requestAsync} 만 갈아끼운다. RestClient 는 쓰이지 않는다. */
    private AiWorkerClient clientThat(Call call) {
        return new AiWorkerClient(RestClient.create()) {
            @Override
            public void requestAsync(String path, Long jobId, Object requestBody) {
                call.accept(path, jobId, requestBody);
            }
        };
    }

    private Job job(Map<String, Object> params) {
        return Job.queue(1L, 1L, JobType.EVIDENCE_EXTRACTION, params);
    }
}
