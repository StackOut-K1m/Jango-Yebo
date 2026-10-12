package com.jangoyebo.core.user;

import com.jangoyebo.common.response.ApiResponse;
import com.jangoyebo.core.user.AuthDtos.LoginRequest;
import com.jangoyebo.core.user.AuthDtos.LoginResponse;
import com.jangoyebo.core.user.AuthDtos.RefreshRequest;
import com.jangoyebo.core.user.AuthDtos.SignUpRequest;
import com.jangoyebo.core.user.AuthDtos.SignUpResponse;
import com.jangoyebo.core.user.AuthService.TokenBundle;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.Duration;

/** 인증 (API 명세 6.1~6.2). 이 경로만 인증 없이 접근 가능하다. */
@RestController
@RequestMapping("/api/v1/auth")
public class AuthController {

    /**
     * Refresh Token 쿠키.
     *
     * <p>{@code HttpOnly} 라 JS가 읽지 못하고, 프론트와 API가 같은 오리진(edge nginx가
     * {@code /} 와 {@code /api/} 를 모두 받는다)이라 {@code SameSite=Lax} 로 충분하다.
     * {@code Path} 를 갱신·로그아웃 경로로 좁혀 일반 API 요청에는 실려 가지 않게 한다.
     */
    private static final String REFRESH_COOKIE = "refreshToken";
    private static final String REFRESH_COOKIE_PATH = "/api/v1/auth";

    /** 앱은 쿠키 저장소가 없어 Refresh Token 을 본문으로 주고받는다(API 2.1). */
    private static final String CLIENT_TYPE_HEADER = "X-Client-Type";
    private static final String CLIENT_TYPE_APP = "app";

    private final AuthService authService;

    public AuthController(AuthService authService) {
        this.authService = authService;
    }

    @PostMapping("/signup")
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<SignUpResponse> signUp(@Valid @RequestBody SignUpRequest request) {
        return ApiResponse.ok(authService.signUp(request));
    }

    /**
     * 로그인만 헤더로 채널을 가른다. 비밀번호를 아는 쪽에만 답하므로 탈취 경로가 아니다 —
     * 갱신은 반대로 <b>받은 곳</b>으로만 돌려준다({@link #refresh}).
     */
    @PostMapping("/login")
    public ApiResponse<LoginResponse> login(
            @Valid @RequestBody LoginRequest request,
            @RequestHeader(name = CLIENT_TYPE_HEADER, required = false) String clientType,
            HttpServletResponse response) {
        var bundle = authService.login(request);
        return ApiResponse.ok(respond(bundle, CLIENT_TYPE_APP.equalsIgnoreCase(clientType), response));
    }

    /**
     * 토큰 갱신. 본문을 쿠키보다 먼저 본다.
     *
     * <p><b>채널은 헤더가 아니라 토큰이 온 곳으로 정한다.</b> {@code X-Client-Type} 만 보고
     * 본문에 토큰을 실어 주면, 웹에 XSS가 생겼을 때 스크립트가 그 헤더를 붙여 쿠키로 갱신을
     * 요청하고 <b>Refresh Token 을 본문으로 빼 갈 수 있다</b>. 그러면 {@code HttpOnly} 쿠키를
     * 쓴 의미가 없다. 규칙은 「받은 곳으로 돌려준다」 — 쿠키로 받았으면 쿠키로, 본문으로
     * 받았으면 본문으로.
     *
     * <p>본문 토큰을 먼저 보는 것은 안전하다. 쿠키를 읽지 못하는 공격자가 본문에 넣을 수 있는
     * 것은 자기 토큰뿐이고, 그 대가로 받는 것도 자기 토큰이다.
     */
    @PostMapping("/refresh")
    public ApiResponse<LoginResponse> refresh(
            @CookieValue(name = REFRESH_COOKIE, required = false) String cookieToken,
            @RequestBody(required = false) RefreshRequest request,
            HttpServletResponse response) {
        String bodyToken = bodyToken(request);
        boolean viaBody = bodyToken != null;

        var bundle = authService.refresh(viaBody ? bodyToken : cookieToken);
        return ApiResponse.ok(respond(bundle, viaBody, response));
    }

    /** 쿠키와 본문에 각각 토큰이 있으면 둘 다 폐기한다. 아무것도 없어도 조용히 성공한다. */
    @PostMapping("/logout")
    public ResponseEntity<Void> logout(
            @CookieValue(name = REFRESH_COOKIE, required = false) String cookieToken,
            @RequestBody(required = false) RefreshRequest request,
            HttpServletResponse response) {
        authService.logout(cookieToken);
        authService.logout(bodyToken(request));
        setRefreshCookie(response, "", Duration.ZERO);
        return ResponseEntity.noContent().build();
    }

    /** 본문으로 주면 쿠키를 내리지 않는다 — 한 번에 한 채널만 쓴다. */
    private LoginResponse respond(TokenBundle bundle, boolean viaBody, HttpServletResponse response) {
        if (viaBody) {
            return bundle.bodyWithRefreshToken();
        }
        setRefreshCookie(response, bundle.refreshToken(), bundle.refreshTtl());
        return bundle.body();
    }

    private static String bodyToken(RefreshRequest request) {
        if (request == null || request.refreshToken() == null || request.refreshToken().isBlank()) {
            return null;
        }
        return request.refreshToken();
    }

    private void setRefreshCookie(HttpServletResponse response, String value, Duration ttl) {
        ResponseCookie cookie = ResponseCookie.from(REFRESH_COOKIE, value)
                .httpOnly(true)
                .secure(true)
                .sameSite("Lax")
                .path(REFRESH_COOKIE_PATH)
                .maxAge(ttl)
                .build();
        response.addHeader(HttpHeaders.SET_COOKIE, cookie.toString());
    }
}
