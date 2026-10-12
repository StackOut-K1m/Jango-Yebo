package com.jangoyebo.core.account;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * 1원 인증 발급 기록.
 *
 * <p><b>왜 Redis 인가</b> — {@code ACCOUNT} 에는 {@code verified_at}(완료 시각)뿐이고
 * <b>발급 시각·재발송 횟수 컬럼이 없다.</b> 그런데 API 7.2 의 {@code ACCOUNT_AUTH_EXPIRED} 는
 * "만료 · <b>발급 기록 없음</b>"이라 기록이 있어야 한다. 컬럼을 늘리는 대신 Redis 를 골랐다:
 * 마이그레이션이 없고, <b>TTL 이 곧 유효시간</b>이며, {@code VERIFICATION_EXPIRED} 를
 * 키 부재로 판정하면 만료 배치도 필요 없다.
 *
 * <p>인증코드 자체는 담지 않는다. 금융망이 검증하므로 <b>우리가 알 필요가 없다.</b>
 * 값에는 재발송 횟수만 들어간다.
 *
 * <p>Redis 가 유실되면 인증 대기 계좌가 전부 만료된다. 재발송으로 복구되고, 이 상태의 계좌는
 * 아직 아무 데이터도 들고 있지 않아 잃을 것이 없다.
 *
 * <p>키에 TTL 이 반드시 있어야 한다 — {@code maxmemory 512mb} + {@code volatile-lru} 라
 * TTL 없는 키는 축출되지 않고 상한에 닿으면 쓰기가 거부된다({@code RefreshTokenStore} 와 같은 제약).
 */
@Component
public class AccountVerificationStore {

    private static final String KEY_PREFIX = "account:auth:";

    private final StringRedisTemplate redis;

    public AccountVerificationStore(StringRedisTemplate redis) {
        this.redis = redis;
    }

    /**
     * 발급을 기록한다. 최초 발급이면 1, 재발송이면 이전 횟수 + 1 이 남는다.
     *
     * <p><b>TTL 을 매번 새로 건다.</b> 재발송은 유효시간도 함께 다시 시작하는 것이 맞다 —
     * 사용자가 방금 받은 코드를 넣을 시간을 주지 않으면 재발송이 의미가 없다.
     *
     * @return 이번 발급이 몇 번째인가 (최초 = 1)
     */
    public long recordIssue(Long accountId, Duration ttl) {
        String key = key(accountId);
        Long count = redis.opsForValue().increment(key);
        redis.expire(key, ttl);
        return count == null ? 1L : count;
    }

    /** 발급 기록이 살아 있는가. 없으면 만료됐거나 애초에 발급하지 않은 것이다. */
    public boolean isPending(Long accountId) {
        return Boolean.TRUE.equals(redis.hasKey(key(accountId)));
    }

    /** 지금까지 몇 번 보냈는가. 기록이 없으면 0. */
    public long issueCount(Long accountId) {
        String value = redis.opsForValue().get(key(accountId));
        return value == null ? 0L : Long.parseLong(value);
    }

    /** 인증 완료·연동 해제 시 기록을 지운다. 남겨 두면 재발송 횟수가 다음 인증까지 따라온다. */
    public void clear(Long accountId) {
        redis.delete(key(accountId));
    }

    private String key(Long accountId) {
        return KEY_PREFIX + accountId;
    }
}
