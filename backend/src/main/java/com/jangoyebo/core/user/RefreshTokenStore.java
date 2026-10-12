package com.jangoyebo.core.user;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * Refresh Token 저장소.
 *
 * <p>토큰 자체만으로는 로그아웃을 강제할 수 없다. {@code jti}를 Redis에 두고
 * <b>여기 있는 것만 유효</b>로 취급해 회전·폐기가 가능하게 한다.
 *
 * <p>키에 TTL이 반드시 있어야 한다 — Redis가 {@code maxmemory 512mb} + {@code volatile-lru}라
 * TTL 없는 키는 축출되지 않고 상한에 닿으면 쓰기가 거부된다(인프라 제약 §4.1 #3).
 */
@Component
public class RefreshTokenStore {

    private static final String KEY_PREFIX = "auth:refresh:";

    private final StringRedisTemplate redis;

    public RefreshTokenStore(StringRedisTemplate redis) {
        this.redis = redis;
    }

    public void save(String tokenId, Long userId, Duration ttl) {
        redis.opsForValue().set(key(tokenId), String.valueOf(userId), ttl);
    }

    public boolean isValid(String tokenId, Long userId) {
        String stored = redis.opsForValue().get(key(tokenId));
        return stored != null && stored.equals(String.valueOf(userId));
    }

    /** 로그아웃·회전 시 이전 토큰을 즉시 무효화한다. */
    public void revoke(String tokenId) {
        redis.delete(key(tokenId));
    }

    private String key(String tokenId) {
        return KEY_PREFIX + tokenId;
    }
}
