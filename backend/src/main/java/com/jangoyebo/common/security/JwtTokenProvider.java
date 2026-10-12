package com.jangoyebo.common.security;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.ExpiredJwtException;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import com.jangoyebo.common.error.BusinessException;
import com.jangoyebo.common.error.ErrorCode;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.UUID;

/**
 * JWT 발급·검증.
 *
 * <p>{@link Clock}을 주입받아 테스트에서 만료를 고정할 수 있게 한다.
 */
@Component
public class JwtTokenProvider {

    private static final String CLAIM_EMAIL = "email";
    private static final String CLAIM_TYPE = "typ";
    private static final String TYPE_ACCESS = "access";
    private static final String TYPE_REFRESH = "refresh";

    private final SecretKey key;
    private final Duration accessTtl;
    private final Duration refreshTtl;
    private final Clock clock;

    public JwtTokenProvider(SecurityProperties properties, Clock clock) {
        this.key = Keys.hmacShaKeyFor(properties.jwt().secret().getBytes(StandardCharsets.UTF_8));
        this.accessTtl = properties.jwt().accessTtl();
        this.refreshTtl = properties.jwt().refreshTtl();
        this.clock = clock;
    }

    public String createAccessToken(Long userId, String email) {
        return build(userId, email, TYPE_ACCESS, accessTtl, null);
    }

    /**
     * Refresh Token. {@code jti}로 Redis에 저장해 회전·무효화한다 —
     * 토큰만으로는 로그아웃을 강제할 수 없기 때문이다.
     */
    public RefreshToken createRefreshToken(Long userId, String email) {
        String tokenId = UUID.randomUUID().toString();
        String token = build(userId, email, TYPE_REFRESH, refreshTtl, tokenId);
        return new RefreshToken(token, tokenId, refreshTtl);
    }

    /** @throws BusinessException 만료 시 {@code AUTH_TOKEN_EXPIRED}, 그 외 {@code AUTH_TOKEN_INVALID} */
    public AuthUser parseAccessToken(String token) {
        Claims claims = parse(token);
        if (!TYPE_ACCESS.equals(claims.get(CLAIM_TYPE, String.class))) {
            throw new BusinessException(ErrorCode.AUTH_TOKEN_INVALID);
        }
        return toAuthUser(claims);
    }

    public ParsedRefreshToken parseRefreshToken(String token) {
        Claims claims = parse(token);
        if (!TYPE_REFRESH.equals(claims.get(CLAIM_TYPE, String.class))) {
            throw new BusinessException(ErrorCode.AUTH_TOKEN_INVALID);
        }
        return new ParsedRefreshToken(toAuthUser(claims), claims.getId());
    }

    private String build(Long userId, String email, String type, Duration ttl, String tokenId) {
        Instant now = clock.instant();
        var builder = Jwts.builder()
                .subject(String.valueOf(userId))
                .claim(CLAIM_EMAIL, email)
                .claim(CLAIM_TYPE, type)
                .issuedAt(Date.from(now))
                .expiration(Date.from(now.plus(ttl)))
                .signWith(key);
        if (tokenId != null) {
            builder.id(tokenId);
        }
        return builder.compact();
    }

    private Claims parse(String token) {
        try {
            return Jwts.parser().verifyWith(key).clock(() -> Date.from(clock.instant()))
                    .build().parseSignedClaims(token).getPayload();
        } catch (ExpiredJwtException e) {
            throw new BusinessException(ErrorCode.AUTH_TOKEN_EXPIRED);
        } catch (JwtException | IllegalArgumentException e) {
            throw new BusinessException(ErrorCode.AUTH_TOKEN_INVALID);
        }
    }

    private AuthUser toAuthUser(Claims claims) {
        return new AuthUser(Long.valueOf(claims.getSubject()), claims.get(CLAIM_EMAIL, String.class));
    }

    public record RefreshToken(String token, String tokenId, Duration ttl) {
    }

    public record ParsedRefreshToken(AuthUser user, String tokenId) {
    }
}
