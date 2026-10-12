package com.jangoyebo.common.security;

import com.jangoyebo.common.error.BusinessException;
import com.jangoyebo.common.error.ErrorCode;
import com.jangoyebo.common.response.ApiResponse;
import tools.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * {@code Authorization: Bearer} 헤더를 검증해 {@link AuthUser}를 SecurityContext에 넣는다.
 *
 * <p>토큰이 없으면 통과시킨다 — 접근 통제는 {@link SecurityConfig}가 경로별로 결정한다.
 * 토큰이 <b>있는데 잘못된</b> 경우에만 여기서 401을 낸다. 필터는 예외 처리 체인 밖이라
 * {@code GlobalExceptionHandler}가 잡지 못하므로 직접 Envelope을 쓴다.
 *
 * <p><b>빈으로 등록하지 않는다.</b> {@link SecurityConfig}가 직접 생성해 시큐리티 체인에만 넣는다.
 * {@code Filter} 타입 빈은 Spring Boot 가 서블릿 컨테이너에도 자동 등록해서, 시큐리티 체인과
 * 톰캣 양쪽에 이중 등록된다. 그러면 톰캣이 {@code Filter.init()} 을 직접 호출하는데
 * 이 경로에서 {@code GenericFilterBean.logger} 가 null 이라 기동이 NPE 로 실패한다.
 * 되살리려면 {@code FilterRegistrationBean.setEnabled(false)} 를 함께 둬야 한다.
 */
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    private static final String HEADER = "Authorization";
    private static final String PREFIX = "Bearer ";

    private final JwtTokenProvider tokenProvider;
    private final ObjectMapper objectMapper;

    public JwtAuthenticationFilter(JwtTokenProvider tokenProvider, ObjectMapper objectMapper) {
        this.tokenProvider = tokenProvider;
        this.objectMapper = objectMapper;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {

        String header = request.getHeader(HEADER);
        if (header == null || !header.startsWith(PREFIX)) {
            chain.doFilter(request, response);
            return;
        }

        try {
            AuthUser user = tokenProvider.parseAccessToken(header.substring(PREFIX.length()));
            var authentication = new UsernamePasswordAuthenticationToken(user, null, List.of());
            SecurityContextHolder.getContext().setAuthentication(authentication);
        } catch (BusinessException e) {
            SecurityContextHolder.clearContext();
            writeError(response, e.errorCode());
            return;
        }

        chain.doFilter(request, response);
    }

    private void writeError(HttpServletResponse response, ErrorCode code) throws IOException {
        response.setStatus(code.status().value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        objectMapper.writeValue(response.getWriter(),
                ApiResponse.error(code, code.defaultMessage(), null));
    }
}
