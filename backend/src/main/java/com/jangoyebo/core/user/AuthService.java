package com.jangoyebo.core.user;

import com.jangoyebo.common.error.BusinessException;
import com.jangoyebo.common.error.ErrorCode;
import com.jangoyebo.common.security.AuthUser;
import com.jangoyebo.common.security.JwtTokenProvider;
import com.jangoyebo.common.security.SecurityProperties;
import com.jangoyebo.core.user.AuthDtos.LoginRequest;
import com.jangoyebo.core.user.AuthDtos.LoginResponse;
import com.jangoyebo.core.user.AuthDtos.SignUpRequest;
import com.jangoyebo.core.user.AuthDtos.SignUpResponse;
import com.jangoyebo.core.user.AuthDtos.UserSummary;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Duration;

/**
 * 회원가입·로그인·토큰 갱신·로그아웃 (API 명세 6장).
 *
 * <p>소셜 로그인·비밀번호 재설정은 범위 밖이다(API 1.2).
 */
@Service
public class AuthService {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtTokenProvider tokenProvider;
    private final RefreshTokenStore refreshTokenStore;
    private final Duration accessTtl;
    private final Clock clock;

    public AuthService(UserRepository userRepository,
                       PasswordEncoder passwordEncoder,
                       JwtTokenProvider tokenProvider,
                       RefreshTokenStore refreshTokenStore,
                       SecurityProperties securityProperties,
                       Clock clock) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.tokenProvider = tokenProvider;
        this.refreshTokenStore = refreshTokenStore;
        this.accessTtl = securityProperties.jwt().accessTtl();
        this.clock = clock;
    }

    @Transactional
    public SignUpResponse signUp(SignUpRequest request) {
        if (userRepository.existsByEmail(request.email())) {
            throw new BusinessException(ErrorCode.AUTH_EMAIL_DUPLICATED);
        }

        User user = User.signUp(
                request.email(),
                passwordEncoder.encode(request.password()),
                request.name(),
                request.phone());

        // 금융망 사용자 계정 생성(MEMBER_01)은 여기서 하지 않는다.
        // 가입 흐름을 외부 API 가용성에 묶지 않기 위해서이며, ERD가 fin_user_key 를
        // nullable 로 두고 "계좌 연결 시 재시도"로 설계해 둔 것과 같은 판단이다(API 6.1).
        return SignUpResponse.from(userRepository.save(user));
    }

    @Transactional
    public TokenBundle login(LoginRequest request) {
        User user = userRepository.findByEmail(request.email())
                // 이메일이 없는 경우와 비밀번호가 틀린 경우를 구분해 주지 않는다.
                // 구분하면 가입 여부를 알아내는 통로가 된다.
                .orElseThrow(() -> new BusinessException(ErrorCode.AUTH_INVALID_CREDENTIALS));

        if (!passwordEncoder.matches(request.password(), user.getPasswordHash())) {
            throw new BusinessException(ErrorCode.AUTH_INVALID_CREDENTIALS);
        }

        user.recordLogin(clock.instant());
        return issueTokens(user);
    }

    /** Refresh Token 회전 — 쓴 토큰은 즉시 폐기하고 새로 발급한다. */
    @Transactional
    public TokenBundle refresh(String refreshToken) {
        var parsed = tokenProvider.parseRefreshToken(refreshToken);

        if (!refreshTokenStore.isValid(parsed.tokenId(), parsed.user().userId())) {
            // 이미 폐기된 토큰이다. 서명이 유효해도 거부한다.
            throw new BusinessException(ErrorCode.AUTH_TOKEN_INVALID);
        }
        refreshTokenStore.revoke(parsed.tokenId());

        User user = userRepository.findById(parsed.user().userId())
                .orElseThrow(() -> new BusinessException(ErrorCode.AUTH_TOKEN_INVALID));
        return issueTokens(user);
    }

    /**
     * 이미 만료·폐기된 토큰이어도 조용히 성공시킨다. 로그아웃은 실패할 이유가 없다.
     *
     * <p>쿠키와 본문 양쪽에서 불릴 수 있다 — 둘 다 있으면 각각 폐기한다.
     */
    public void logout(String refreshToken) {
        if (refreshToken == null || refreshToken.isBlank()) {
            return;
        }
        try {
            refreshTokenStore.revoke(tokenProvider.parseRefreshToken(refreshToken).tokenId());
        } catch (BusinessException ignored) {
            // 손상된 토큰이라도 로그아웃 자체는 성공으로 처리한다.
        }
    }

    private TokenBundle issueTokens(User user) {
        AuthUser principal = new AuthUser(user.getId(), user.getEmail());
        String accessToken = tokenProvider.createAccessToken(principal.userId(), principal.email());
        var refresh = tokenProvider.createRefreshToken(principal.userId(), principal.email());

        refreshTokenStore.save(refresh.tokenId(), user.getId(), refresh.ttl());

        return new TokenBundle(
                new LoginResponse(accessToken, accessTtl.toSeconds(), UserSummary.from(user)),
                refresh.token(),
                refresh.ttl());
    }

    /**
     * 컨트롤러가 Refresh Token 을 쿠키로 내보내야 하므로 본문과 분리해 전달한다.
     *
     * <p>어느 쪽으로 내보낼지는 컨트롤러가 정한다 — 서비스는 채널을 모른다.
     */
    public record TokenBundle(LoginResponse body, String refreshToken, Duration refreshTtl) {

        /** 앱 — 쿠키 저장소가 없어 Refresh Token 을 본문에 실어 보낸다(API 2.1). */
        public LoginResponse bodyWithRefreshToken() {
            return new LoginResponse(body.accessToken(), body.expiresIn(), body.user(),
                    refreshToken, refreshTtl.toSeconds());
        }
    }
}
