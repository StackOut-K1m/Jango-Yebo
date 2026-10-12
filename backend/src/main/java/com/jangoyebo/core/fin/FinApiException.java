package com.jangoyebo.core.fin;

import org.springframework.http.HttpStatusCode;

/**
 * 금융망 호출 실패.
 *
 * <p>{@link #isRetryable()} 이 호출부의 판단 근거다 — {@code ACCOUNT_SYNC} 핸들러는 이 값으로
 * Job 을 즉시 {@code FAILED} 로 접을지, 스위퍼가 회수하게 둘지 정한다.
 *
 * <p>사용자에게 나가는 것은 {@code 502 FIN_API_FAILED}(API 7.2) 하나뿐이다. 금융망 코드
 * 214종을 그대로 노출하지 않는다 — 대신 {@link #finCode()} 를 로그에 남겨 원인을 좁힌다.
 */
public class FinApiException extends RuntimeException {

    private final String finCode;
    private final HttpStatusCode status;

    public FinApiException(String finCode, HttpStatusCode status, String message) {
        super(message);
        this.finCode = finCode;
        this.status = status;
    }

    /** 연결 실패·타임아웃. 금융망이 답을 준 적이 없으므로 코드가 없다. */
    public FinApiException(String message, Throwable cause) {
        super(message, cause);
        this.finCode = null;
        this.status = null;
    }

    /**
     * 같은 요청을 다시 보낼 가치가 있는가.
     *
     * <p>전송 오류와 5xx 는 일시적으로 본다. 금융망 코드 중에서는 {@code H1007}(채번 중복)만
     * 재시도가 의미 있고 — <b>이때는 반드시 새 번호로 채번해야 한다</b> — 나머지는 요청 자체가
     * 잘못됐거나 상태가 안 맞는 것이라 다시 보내도 같은 답이 온다.
     */
    public boolean isRetryable() {
        if (finCode != null) {
            return FinResponseCode.isRetryable(finCode);
        }
        return status == null || status.is5xxServerError();
    }

    /** 금융망 {@code responseCode}. 전송 오류면 {@code null} 이다. */
    public String finCode() {
        return finCode;
    }

    public HttpStatusCode status() {
        return status;
    }
}
