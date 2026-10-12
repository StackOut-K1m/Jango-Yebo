package com.jangoyebo.common.security;

import com.jangoyebo.common.error.ErrorCode;
import tools.jackson.databind.ObjectMapper;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

import java.time.Clock;

/**
 * 보안 설정 (API 명세 2.1·21장).
 *
 * <p>세션을 쓰지 않는다(STATELESS). Refresh Token은 {@code HttpOnly} 쿠키로 전달하고
 * Redis에 저장해 회전·무효화한다.
 */
@Configuration
@EnableConfigurationProperties(SecurityProperties.class)
public class SecurityConfig {

    /** 인증 없이 접근 가능한 경로 (API 2.1). */
    private static final String[] PUBLIC_PATHS = {
            "/api/v1/auth/**",
            "/api/v1/bank-codes",
            "/actuator/health/**"
    };

    /**
     * 사용자 API 체인. {@code securityMatcher} 가 없어 모든 요청에 매칭되므로
     * <b>반드시 마지막 순서</b>여야 한다 — {@code /internal/**} 은
     * {@link InternalSecurityConfig} 가 {@code @Order(1)} 로 먼저 가져간다.
     */
    @Bean
    @Order(2)
    SecurityFilterChain filterChain(HttpSecurity http,
                                    JwtTokenProvider tokenProvider,
                                    ObjectMapper objectMapper) throws Exception {
        // 필터를 빈으로 두지 않고 여기서 만든다 — 이유는 JwtAuthenticationFilter 주석 참고.
        var jwtFilter = new JwtAuthenticationFilter(tokenProvider, objectMapper);
        return http
                // 토큰 기반이고 쿠키는 SameSite 로 보호하므로 CSRF 토큰을 쓰지 않는다.
                .csrf(csrf -> csrf.disable())
                // 프론트와 API가 같은 오리진(edge nginx 가 / 와 /api/ 모두)이라 CORS 설정이 필요 없다.
                .cors(cors -> cors.disable())
                .formLogin(form -> form.disable())
                .httpBasic(basic -> basic.disable())
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers(PUBLIC_PATHS).permitAll()
                        .anyRequest().authenticated())
                .exceptionHandling(ex -> ex
                        .authenticationEntryPoint((req, res, e) ->
                                ErrorResponseWriter.write(res, objectMapper, ErrorCode.AUTH_TOKEN_INVALID))
                        .accessDeniedHandler((req, res, e) ->
                                ErrorResponseWriter.write(res, objectMapper, ErrorCode.BUSINESS_ACCESS_DENIED)))
                .addFilterBefore(jwtFilter, UsernamePasswordAuthenticationFilter.class)
                .build();
    }

    @Bean
    PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    /** 토큰 만료·예측 기준 시각을 테스트에서 고정할 수 있게 빈으로 둔다. */
    @Bean
    Clock clock() {
        return Clock.systemDefaultZone();
    }

}
