package com.jangoyebo.core.ai;

import com.jangoyebo.common.error.BusinessException;
import com.jangoyebo.common.error.ErrorCode;
import com.jangoyebo.core.ai.ProductSalesOcrHandler.ProductSalesRequest;
import com.jangoyebo.core.job.Job;
import com.jangoyebo.core.job.JobHandler;
import com.jangoyebo.core.job.JobType;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.client.RestClient;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 매출 인쇄물 추출 핸들러 검증 (명세 20.9).
 *
 * <p><b>Spring 컨텍스트도 Docker 도 쓰지 않는다.</b> 검증할 것이 "params 를 어떻게 읽고
 * 워커 실패를 어떻게 Job 운명으로 옮기는가"뿐이다.
 */
class ProductSalesOcrHandlerTests {

    @Test
    void sendsRequestBuiltFromJobParamsAndAwaitsCallback() {
        var sent = new AtomicReference<Object>();
        var handler = new ProductSalesOcrHandler(clientThat((path, jobId, body) -> {
            assertThat(path).isEqualTo("/internal/v1/ai/product-sales-extraction");
            sent.set(body);
        }));

        JobHandler.Outcome outcome = handler.handle(job(Map.of(
                "captureType", "PRINTOUT",
                "fileObjectKeys", List.of("product-sales/11/9401-1.jpg", "product-sales/11/9401-2.jpg"),
                "knownProducts", List.of("카페라떼", "아메리카노"))));

        assertThat(outcome).isEqualTo(JobHandler.Outcome.AWAITING_CALLBACK);
        ProductSalesRequest request = (ProductSalesRequest) sent.get();
        assertThat(request.businessId())
                .as("콜백에는 businessId 가 없지만(합의 ⑦) 요청에는 계약이 요구한다")
                .isEqualTo(11L);
        assertThat(request.captureType()).isEqualTo("PRINTOUT");
        assertThat(request.fileObjectKeys()).hasSize(2);
        assertThat(request.knownProducts()).containsExactly("카페라떼", "아메리카노");
    }

    /** 첫 등록이면 스냅할 대상이 없다. 그래도 추출은 된다. */
    @Test
    void allowsAnEmptyKnownProductList() {
        var sent = new AtomicReference<Object>();
        var handler = new ProductSalesOcrHandler(clientThat((path, jobId, body) -> sent.set(body)));

        handler.handle(job(Map.of("captureType", "SCREEN",
                "fileObjectKeys", List.of("product-sales/11/1-1.png"))));

        assertThat(((ProductSalesRequest) sent.get()).knownProducts()).isEmpty();
    }

    /** 큐에 넣은 쪽의 버그다. 워커를 부르지 않고 즉시 실패로 확정한다. */
    @Test
    void failsWhenRequiredParamIsMissing() {
        var handler = new ProductSalesOcrHandler(clientThat((path, jobId, body) -> {
            throw new AssertionError("params 가 없으면 워커를 부르지 않아야 한다");
        }));

        assertThatThrownBy(() -> handler.handle(job(Map.of("captureType", "PRINTOUT"))))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("fileObjectKeys");

        assertThatThrownBy(() -> handler.handle(job(Map.of(
                "captureType", "PRINTOUT", "fileObjectKeys", List.of()))))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("fileObjectKeys");

        assertThatThrownBy(() -> handler.handle(job(Map.of(
                "fileObjectKeys", List.of("k")))))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("captureType");
    }

    /**
     * 워커가 파일을 못 찾으면 400 이다(계약 v1.17 — 202 를 주기 전에 거절한다).
     * 재시도해도 같으므로 즉시 실패로 확정해 사용자가 스위퍼 임계값을 기다리지 않게 한다.
     */
    @Test
    void permanentWorkerFailureFailsTheJob() {
        var handler = new ProductSalesOcrHandler(clientThat((path, jobId, body) -> {
            throw new AiWorkerException(HttpStatus.BAD_REQUEST,
                    "VALIDATION_FAILED", "파일을 찾을 수 없습니다");
        }));

        assertThatThrownBy(() -> handler.handle(job(Map.of(
                "captureType", "PRINTOUT", "fileObjectKeys", List.of("k")))))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).errorCode())
                .isEqualTo(ErrorCode.AI_ANALYSIS_FAILED);
    }

    /** 5xx·연결 실패는 종결하지 않는다. PROCESSING 으로 남겨 스위퍼가 회수한다. */
    @Test
    void temporaryWorkerFailureSendsTheJobBackToTheQueue() {
        var handler = new ProductSalesOcrHandler(clientThat((path, jobId, body) -> {
            throw new AiWorkerException("연결 실패", new RuntimeException("connection refused"));
        }));

        assertThat(handler.handle(job(Map.of(
                "captureType", "PRINTOUT", "fileObjectKeys", List.of("k")))))
                .isEqualTo(JobHandler.Outcome.NOT_ACCEPTED);
    }

    // --- helpers -------------------------------------------------------------

    private interface Call {
        void accept(String path, Long jobId, Object body);
    }

    private AiWorkerClient clientThat(Call call) {
        return new AiWorkerClient(RestClient.create()) {
            @Override
            public void requestAsync(String path, Long jobId, Object requestBody) {
                call.accept(path, jobId, requestBody);
            }
        };
    }

    private Job job(Map<String, Object> params) {
        return Job.queue(11L, 1L, JobType.PRODUCT_SALES_OCR, params);
    }
}
