package com.jangoyebo.common.security;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;

/**
 * 보안 설정. 빈 값이면 <b>부팅에 실패한다</b>(인프라 제약 §4.1 #6).
 *
 * <p>이전 프로젝트에서 빈 {@code JWT_SECRET}으로 컨테이너가 크래시 루프에 빠졌을 때
 * 원인 파악이 늦어졌다. 잘못된 상태로 뜨는 것보다 부팅에서 즉시 멈추는 편이 낫다.
 */
@Validated
@ConfigurationProperties(prefix = "jango.security")
public record SecurityProperties(@NotNull Jwt jwt, @NotBlank String encryptionKey) {

    /**
     * @param secret     HMAC-SHA256 키. 최소 32바이트여야 한다(jjwt가 검증한다).
     * @param accessTtl  Access Token 만료 (API 2.1 — 30분)
     * @param refreshTtl Refresh Token 만료 (API 2.1 — 14일)
     */
    public record Jwt(@NotBlank String secret, @NotNull Duration accessTtl, @NotNull Duration refreshTtl) {
    }
}
