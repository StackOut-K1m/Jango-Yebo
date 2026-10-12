package com.jangoyebo.common.security;

import com.jangoyebo.common.error.ErrorCode;
import com.jangoyebo.common.response.ApiResponse;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.MediaType;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

/**
 * 시큐리티 필터·엔트리포인트에서 공통 오류 Envelope 을 직접 쓴다.
 *
 * <p>필터와 엔트리포인트는 예외 처리 체인 <b>밖</b>이라 {@code GlobalExceptionHandler} 가
 * 잡지 못한다. 그래서 각자 직접 써야 하는데, 체인이 둘({@code SecurityConfig} 와
 * {@code InternalSecurityConfig})이 되면서 같은 코드가 두 벌이 되는 것을 막는다.
 */
final class ErrorResponseWriter {

    private ErrorResponseWriter() {
    }

    static void write(HttpServletResponse response, ObjectMapper mapper, ErrorCode code) throws IOException {
        write(response, mapper, code, code.defaultMessage());
    }

    static void write(HttpServletResponse response, ObjectMapper mapper, ErrorCode code, String message)
            throws IOException {
        response.setStatus(code.status().value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        mapper.writeValue(response.getWriter(), ApiResponse.error(code, message, null));
    }
}
