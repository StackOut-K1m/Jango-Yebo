package com.jangoyebo.core.ai;

import com.jangoyebo.common.error.BusinessException;
import com.jangoyebo.common.error.ErrorCode;
import com.jangoyebo.core.job.Job;
import com.jangoyebo.core.job.JobHandler;
import com.jangoyebo.core.job.JobType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * 매출현황 인쇄물 추출 작업을 AI 워커에 접수한다 (명세 20.9, P1-22).
 *
 * <p>{@link EvidenceExtractionHandler} 와 같은 모양이다 — 접수만 하고 결과는 기다리지 않는다.
 * <b>비동기인 이유</b>는 여러 장 곱하기 80행이면 동기 10초 타임아웃 안에 끝난다고 볼 수 없어서다
 * (계약 v1.7 에서 동기 → 비동기로 바뀌었다).
 *
 * <p>{@code Job.params} 에 {@code captureType}·{@code fileObjectKeys} 가 있어야 한다 —
 * {@code ProductSalesOcrService} 가 넣는다. {@code knownProducts} 는 없어도 된다(첫 등록).
 */
@Component
public class ProductSalesOcrHandler implements JobHandler {

    private static final Logger log = LoggerFactory.getLogger(ProductSalesOcrHandler.class);
    private static final String PATH = "/internal/v1/ai/product-sales-extraction";

    private final AiWorkerClient client;

    public ProductSalesOcrHandler(AiWorkerClient client) {
        this.client = client;
    }

    @Override
    public JobType jobType() {
        return JobType.PRODUCT_SALES_OCR;
    }

    @Override
    public Outcome handle(Job job) {
        var request = new ProductSalesRequest(
                job.getId(),
                // 콜백에는 businessId 가 없지만(합의 ⑦) 요청에는 계약이 요구한다.
                job.getBusinessId(),
                requiredString(job, "captureType"),
                requiredStrings(job, "fileObjectKeys"),
                strings(job, "knownProducts"));

        try {
            client.requestAsync(PATH, job.getId(), request);
            return Outcome.AWAITING_CALLBACK;
        } catch (AiWorkerException e) {
            if (e.isPermanent()) {
                // 워커가 파일을 못 찾으면 400 이다(계약 v1.17 — 202 를 주기 전에 거절한다).
                // 재시도해도 같으므로 즉시 실패로 확정한다.
                throw new BusinessException(ErrorCode.AI_ANALYSIS_FAILED, e.getMessage());
            }
            // 워커가 이 작업을 받지 못했으므로 대기열로 되돌린다 — 외부에 일이 떠 있지 않아
            // 다시 보내도 중복이 없고, 배포·재시작 중이면 수 초 뒤 살아난다.
            log.warn("작업 {} 워커 접수 실패 — 대기열로 되돌린다: {}",
                    job.getId(), e.getMessage());
            return Outcome.NOT_ACCEPTED;
        }
    }

    /**
     * 워커 요청 본문 (명세 20.9 · {@code ai/app/ocr/schemas.py} 의
     * {@code ProductSalesExtractionRequest}).
     *
     * @param knownProducts 이미 등록된 상품명. 워커가 이 목록에 <b>스냅되도록 유도</b>해 표기
     *                      흔들림과 오인식을 줄인다. 다만 목록에 없는 신규 상품도 그대로
     *                      반환한다 — 임의로 가장 가까운 이름으로 바꾸지 않는다
     */
    record ProductSalesRequest(Long jobId,
                               Long businessId,
                               String captureType,
                               List<String> fileObjectKeys,
                               List<String> knownProducts) {
    }

    private String requiredString(Job job, String key) {
        Object value = param(job, key);
        if (value instanceof String text && !text.isBlank()) {
            return text;
        }
        throw missing(job, key);
    }

    private List<String> requiredStrings(Job job, String key) {
        List<String> values = strings(job, key);
        if (values.isEmpty()) {
            throw missing(job, key);
        }
        return values;
    }

    /** JSONB 배열은 {@code List<Object>} 로 온다. */
    private List<String> strings(Job job, String key) {
        Object value = param(job, key);
        if (!(value instanceof List<?> raw)) {
            return List.of();
        }
        return raw.stream().filter(String.class::isInstance).map(String.class::cast).toList();
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
