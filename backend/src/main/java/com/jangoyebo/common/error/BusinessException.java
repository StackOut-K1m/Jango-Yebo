package com.jangoyebo.common.error;

import java.util.Map;

/**
 * 업무 규칙 위반. {@link GlobalExceptionHandler}가 {@link ErrorCode}의 HTTP 상태로 변환한다.
 *
 * <pre>{@code
 * throw new BusinessException(ErrorCode.ACCOUNT_NOT_VERIFIED);
 * throw new BusinessException(ErrorCode.FIN_API_FAILED, Map.of("finCode", "A1003"));
 * }</pre>
 */
public class BusinessException extends RuntimeException {

    private final ErrorCode errorCode;
    private final transient Map<String, Object> detail;

    public BusinessException(ErrorCode errorCode) {
        this(errorCode, errorCode.defaultMessage(), null);
    }

    /** 외부 연동 오류(502)에서 원인 코드를 함께 전달할 때 쓴다. */
    public BusinessException(ErrorCode errorCode, Map<String, Object> detail) {
        this(errorCode, errorCode.defaultMessage(), detail);
    }

    public BusinessException(ErrorCode errorCode, String message) {
        this(errorCode, message, null);
    }

    public BusinessException(ErrorCode errorCode, String message, Map<String, Object> detail) {
        super(message);
        this.errorCode = errorCode;
        this.detail = detail;
    }

    public ErrorCode errorCode() {
        return errorCode;
    }

    public Map<String, Object> detail() {
        return detail;
    }
}
