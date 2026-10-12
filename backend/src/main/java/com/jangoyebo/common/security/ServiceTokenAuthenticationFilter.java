package com.jangoyebo.common.security;

import com.jangoyebo.common.error.ErrorCode;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.List;

/**
 * {@code /internal/**} 의 서비스 간 인증 (명세 20.1 · 21장).
 *
 * <p>{@link JwtAuthenticationFilter} 와 달리 <b>토큰이 없어도 통과시키지 않는다.</b> 사용자
 * API 는 경로별 설정이 접근 통제를 결정하지만, 내부 체인의 자격증명은 서비스 토큰 하나뿐이라
 * 없으면 그 자리에서 401 이다.
 *
 * <p><b>빈으로 등록하지 않는다.</b> {@code Filter} 타입 빈은 Spring Boot 가 서블릿 컨테이너에도
 * 자동 등록해 기동이 NPE 로 실패하고({@code JwtAuthenticationFilter} 주석 참고), 여기엔 이유가
 * 하나 더 있다 — 컨테이너에 등록되면 {@code /api/v1/**} 에도 걸려 일반 API 가 서비스 토큰을
 * 요구하게 된다. {@link InternalSecurityConfig} 가 직접 생성한다.
 */
public class ServiceTokenAuthenticationFilter extends OncePerRequestFilter {

    private static final String HEADER = "Authorization";
    private static final String PREFIX = "Bearer ";

    /**
     * principal 을 {@code AuthUser} 가 아닌 문자열로 둔다. 콜백 경로에서 실수로
     * {@code BusinessGuard.currentUser()} 를 호출하면 {@code instanceof AuthUser} 가 실패해
     * 즉시 드러난다 — 조용히 통과해 사업장 범위 검증을 건너뛰는 것보다 낫다.
     */
    private static final String PRINCIPAL = "internal-service";

    private final byte[] expectedToken;
    private final ObjectMapper objectMapper;

    public ServiceTokenAuthenticationFilter(String serviceToken, ObjectMapper objectMapper) {
        this.expectedToken = serviceToken.getBytes(StandardCharsets.UTF_8);
        this.objectMapper = objectMapper;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {

        String header = request.getHeader(HEADER);
        if (header == null || !header.startsWith(PREFIX)) {
            deny(response);
            return;
        }

        // 워커의 hmac.compare_digest 와 같은 성질 — 비교 시간으로 토큰을 흘리지 않는다.
        byte[] presented = header.substring(PREFIX.length()).trim().getBytes(StandardCharsets.UTF_8);
        if (!MessageDigest.isEqual(presented, expectedToken)) {
            deny(response);
            return;
        }

        var authentication = new UsernamePasswordAuthenticationToken(
                PRINCIPAL, null, List.of(new SimpleGrantedAuthority("ROLE_SERVICE")));
        SecurityContextHolder.getContext().setAuthentication(authentication);
        try {
            chain.doFilter(request, response);
        } finally {
            SecurityContextHolder.clearContext();
        }
    }

    /** 토큰 값을 로그·응답 어디에도 남기지 않는다. */
    private void deny(HttpServletResponse response) throws IOException {
        SecurityContextHolder.clearContext();
        ErrorResponseWriter.write(response, objectMapper, ErrorCode.INTERNAL_AUTH_FAILED);
    }
}
