package com.jangoyebo.core.ai;

import com.jangoyebo.core.ai.AiCallbackDtos.ErrorBody;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.util.Map;

/**
 * 서버 → AI 워커 호출 (명세 20.1).
 *
 * <p>비동기 접수만 다룬다 — 워커는 {@code 202 {"jobId": ...}} 를 주고 결과는 콜백으로 온다
 * ({@link AiCallbackController}). 동기 호출 2건({@code item-normalization},
 * {@code document-assist})은 Circuit Breaker 가 필요해 이 클래스 범위 밖이다.
 */
public class AiWorkerClient {

    private static final Logger log = LoggerFactory.getLogger(AiWorkerClient.class);

    private final RestClient client;

    public AiWorkerClient(RestClient client) {
        this.client = client;
    }

    /**
     * 비동기 작업을 워커에 접수한다.
     *
     * <p>{@code X-Request-Id} 는 워커가 <b>필수로 요구</b>한다 — 없으면 400 이다
     * ({@code ai/app/common/request_id.py}). 콜백의 {@code ai-job-{id}-result} 와 대칭인
     * {@code ai-job-{id}-request} 를 써서 로그에서 한 쌍으로 찾을 수 있게 한다.
     *
     * @throws AiWorkerException 접수 실패. {@code isPermanent()} 로 Job 처리를 나눈다.
     */
    public void requestAsync(String path, Long jobId, Object requestBody) {
        try {
            Accepted accepted = client.post()
                    .uri(path)
                    .header("X-Request-Id", "ai-job-" + jobId + "-request")
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(requestBody)
                    .exchange((request, response) -> {
                        HttpStatusCode status = response.getStatusCode();
                        if (status.isError()) {
                            throw toException(status, response.bodyTo(Envelope.class));
                        }
                        return response.bodyTo(Accepted.class);
                    });

            if (accepted != null && !Boolean.TRUE.equals(accepted.success())) {
                throw new AiWorkerException(null, null, "워커가 success=false 로 접수를 거절했습니다.");
            }
            log.debug("작업 {} 워커 접수 완료 — {}", jobId, path);
        } catch (AiWorkerException e) {
            throw e;
        } catch (RestClientException e) {
            // 연결 실패·타임아웃. 워커가 아직 뜨지 않았을 수도 있다 — 일시적으로 본다.
            throw new AiWorkerException("워커 호출에 실패했습니다: " + path, e);
        }
    }

    /**
     * 동기 호출. <b>실패하면 {@code null} 을 돌려주고 던지지 않는다.</b>
     *
     * <p>이 경로로 부르는 것은 <b>있으면 좋은 값</b>이지 없으면 못 쓰는 값이 아니다 —
     * 검산 재실행(20.4 확장)이 첫 사례이고, 워커가 죽어 있다고 사용자의 검토 저장을 막으면
     * 안 된다. 호출자는 null 을 받으면 있던 값을 그대로 쓴다.
     *
     * <p>읽기 타임아웃은 비동기 접수와 같은 5초를 쓴다. 이 라우트는 모델을 부르지 않아
     * 즉시 응답이므로(비용 0) 늘릴 이유가 없다 — 15초가 필요한 잔여 예측(20.11)이 클라이언트를
     * 따로 둔 것과 반대 사정이다.
     *
     * @return 응답 Envelope 의 {@code data}. 실패·{@code success=false} 면 null
     */
    public Map<String, Object> postSyncOrNull(String path, String requestId, Object requestBody) {
        try {
            Envelope body = client.post()
                    .uri(path)
                    .header("X-Request-Id", requestId)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(requestBody)
                    .retrieve()
                    .body(Envelope.class);
            if (body == null || !Boolean.TRUE.equals(body.success())) {
                log.warn("워커 동기 호출이 success=false 다 — 무시한다. path={}", path);
                return null;
            }
            return body.data();
        } catch (RuntimeException e) {
            log.warn("워커 동기 호출 실패 — 무시한다. path={} {}", path, e.toString());
            return null;
        }
    }

    private AiWorkerException toException(HttpStatusCode status, Envelope body) {
        ErrorBody error = body == null ? null : body.error();
        String code = error == null ? null : error.code();
        String message = error == null || error.message() == null
                ? "워커가 " + status.value() + " 를 반환했습니다."
                : error.message();
        return new AiWorkerException(status, code, message);
    }

    /** 워커 응답은 항상 공통 Envelope 이다(명세 20.1). 모르는 필드에 관대해야 한다. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    private record Envelope(Boolean success, Map<String, Object> data, ErrorBody error) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record Accepted(Boolean success, Map<String, Object> data) {
    }
}
