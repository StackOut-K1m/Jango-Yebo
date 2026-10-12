package com.jangoyebo.common.security;

import com.jangoyebo.common.ai.AiProperties;
import com.jangoyebo.common.error.ErrorCode;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import tools.jackson.databind.ObjectMapper;

/**
 * 내부 API 보안 설정 (명세 20.1 · 21장).
 *
 * <p>{@code /internal/**} 은 {@code /api/v1} 하위가 <b>아니다</b> — 별개 경로 트리이고
 * 사용자 JWT 가 아니라 서비스 토큰으로 인증한다.
 *
 * <p>이 체인이 없으면 워커 콜백이 사용자 체인에 걸려 {@code AUTH_TOKEN_INVALID} 를 받는다.
 * 명세가 요구하는 {@code INTERNAL_AUTH_FAILED} 가 아닐뿐더러, 둘 다 401(4xx)이라
 * <b>워커가 재시도 없이 즉시 포기해</b> AI 결과가 유실된다. 코드값만 다른 같은 상태라
 * 잘못됨이 조용히 지나가는 종류의 결함이다.
 *
 * <p>⚠ <b>인프라 의존</b>: 공통규약 2.1 이 "nginx 가 {@code /} 와 {@code /api/} 를 모두
 * 받는다"고 정해 두었다. 그대로면 {@code /internal/**} 이 인터넷에서 직접 닿고 서비스 토큰이
 * 유일한 방어선이 된다. nginx 에서 이 경로를 막아야 한다.
 */
@Configuration
@EnableConfigurationProperties(AiProperties.class)
public class InternalSecurityConfig {

    @Bean
    @Order(1)
    SecurityFilterChain internalFilterChain(HttpSecurity http,
                                            AiProperties properties,
                                            ObjectMapper objectMapper) throws Exception {
        // 필터를 빈으로 두지 않는 이유는 ServiceTokenAuthenticationFilter 주석 참고.
        var serviceTokenFilter =
                new ServiceTokenAuthenticationFilter(properties.serviceToken(), objectMapper);
        return http
                .securityMatcher("/internal/**")
                .csrf(csrf -> csrf.disable())
                .cors(cors -> cors.disable())
                .formLogin(form -> form.disable())
                .httpBasic(basic -> basic.disable())
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth.anyRequest().authenticated())
                // 엔트리포인트와 접근거부 핸들러를 둘 다 덮는다. 하나라도 빠지면 사용자 체인의
                // AUTH_TOKEN_INVALID 가 새어 나가는데, 같은 401 이라 눈에 띄지 않는다.
                .exceptionHandling(ex -> ex
                        .authenticationEntryPoint((req, res, e) ->
                                ErrorResponseWriter.write(res, objectMapper, ErrorCode.INTERNAL_AUTH_FAILED))
                        .accessDeniedHandler((req, res, e) ->
                                ErrorResponseWriter.write(res, objectMapper, ErrorCode.INTERNAL_AUTH_FAILED)))
                .addFilterBefore(serviceTokenFilter, UsernamePasswordAuthenticationFilter.class)
                .build();
    }
}
