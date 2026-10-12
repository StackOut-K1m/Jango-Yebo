package com.jangoyebo.core.ai;

import org.springframework.http.HttpStatusCode;

/**
 * AI 워커 호출 실패.
 *
 * <p>{@link #permanent} 여부가 Job 의 운명을 정한다 — 4xx 는 재시도해도 같은 결과이므로
 * 즉시 실패로 확정하고, 5xx·전송 오류는 Job 을 {@code PROCESSING} 에 남겨 스위퍼가
 * 회수하게 한다.
 */
public class AiWorkerException extends RuntimeException {

    private final HttpStatusCode status;
    private final String workerErrorCode;

    public AiWorkerException(HttpStatusCode status, String workerErrorCode, String message) {
        super(message);
        this.status = status;
        this.workerErrorCode = workerErrorCode;
    }

    public AiWorkerException(String message, Throwable cause) {
        super(message, cause);
        this.status = null;
        this.workerErrorCode = null;
    }

    /** 4xx 만 영구 실패다. 전송 오류(status 없음)는 일시적으로 본다. */
    public boolean isPermanent() {
        return status != null && status.is4xxClientError();
    }

    public HttpStatusCode status() {
        return status;
    }

    /** 워커가 보낸 {@code error.code}. 없을 수 있다. */
    public String workerErrorCode() {
        return workerErrorCode;
    }
}
