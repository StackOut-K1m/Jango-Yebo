package com.jangoyebo.common.response;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.jangoyebo.common.error.ErrorCode;

import java.util.Map;

/**
 * API 공통 응답 Envelope (API 명세 2.4).
 *
 * <p>컨트롤러에서 <b>명시적으로</b> 감싼다. {@code ResponseBodyAdvice} 자동 래핑을 쓰지 않는 이유는
 * 파일 다운로드({@code GET /exports/{id}/download}, 증빙 스트리밍) 때문이다 — JSON으로 감싸면
 * 안 되는 응답이 존재하고, 예외 목록 관리는 빠뜨리면 런타임에야 드러난다.
 *
 * <pre>{@code
 * return ApiResponse.ok(dto);                      // 200
 * return ApiResponse.ok(PageResponse.from(page));  // 목록
 * }</pre>
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ApiResponse<T>(boolean success, T data, ErrorBody error) {

    public static <T> ApiResponse<T> ok(T data) {
        return new ApiResponse<>(true, data, null);
    }

    /** 본문 없는 성공(204 등)에 쓴다. */
    public static ApiResponse<Void> ok() {
        return new ApiResponse<>(true, null, null);
    }

    public static ApiResponse<Void> error(ErrorCode code, String message, Map<String, Object> detail) {
        return new ApiResponse<>(false, null, new ErrorBody(code.name(), message, detail));
    }

    /**
     * @param detail 외부 연동 오류(502)에서 원인 식별에 쓴다. 예: {@code {"finCode": "A1003"}}
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record ErrorBody(String code, String message, Map<String, Object> detail) {
    }
}
