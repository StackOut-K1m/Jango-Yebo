package com.jangoyebo.core.user;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.time.Instant;

/** 인증·사용자 API의 요청·응답 (API 명세 6장). */
public final class AuthDtos {

    private AuthDtos() {
    }

    /** 비밀번호 8~64자, 영문·숫자·특수문자 포함 (API 6.1). */
    private static final String PASSWORD_RULE =
            "^(?=.*[A-Za-z])(?=.*[0-9])(?=.*[^A-Za-z0-9]).{8,64}$";

    public record SignUpRequest(
            @NotBlank @Email String email,
            @NotBlank @Pattern(regexp = PASSWORD_RULE,
                    message = "비밀번호는 8~64자이며 영문·숫자·특수문자를 포함해야 합니다.")
            String password,
            @NotBlank @Size(max = 100) String name,
            @Size(max = 20) String phone) {
    }

    public record SignUpResponse(Long userId, String email, String name) {
        public static SignUpResponse from(User user) {
            return new SignUpResponse(user.getId(), user.getEmail(), user.getName());
        }
    }

    public record LoginRequest(@NotBlank @Email String email, @NotBlank String password) {
    }

    /**
     * Refresh Token 을 본문으로 주고받는 요청 (갱신·로그아웃).
     *
     * <p>앱은 쿠키 저장소가 없어 본문으로 보낸다. 웹은 본문 없이 보내고 브라우저가 쿠키를 싣는다 —
     * 그래서 {@code refreshToken} 은 비어 있을 수 있다.
     */
    public record RefreshRequest(String refreshToken) {
    }

    /**
     * 웹은 {@code refreshToken}·{@code refreshExpiresIn} 이 <b>없다</b> —
     * Refresh Token 이 {@code HttpOnly} 쿠키로 나가기 때문이다(API 2.1).
     * 앱({@code X-Client-Type: app})만 두 필드를 본문으로 받는다.
     * 둘 다 {@code null} 이면 {@code non_null} 정책에 따라 키 자체가 빠진다.
     */
    public record LoginResponse(String accessToken, long expiresIn, UserSummary user,
                                String refreshToken, Long refreshExpiresIn) {

        public LoginResponse(String accessToken, long expiresIn, UserSummary user) {
            this(accessToken, expiresIn, user, null, null);
        }
    }

    public record UserSummary(Long userId, String name, Long defaultBusinessId) {
        public static UserSummary from(User user) {
            return new UserSummary(user.getId(), user.getName(), user.getDefaultBusinessId());
        }
    }

    public record MeResponse(
            Long userId, String email, String name, String phone,
            Long defaultBusinessId, long businessCount, Instant createdAt) {
    }

    public record UpdateMeRequest(
            @Size(max = 100) String name,
            @Size(max = 20) String phone,
            Long defaultBusinessId) {
    }
}
