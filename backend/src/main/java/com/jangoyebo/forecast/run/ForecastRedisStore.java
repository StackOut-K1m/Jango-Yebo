package com.jangoyebo.forecast.run;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * 재계산 대기 표시(dirty)와 사업장 단위 실행 락 (공통규약 2.6).
 *
 * <p><b>모든 키에 TTL 이 있다.</b> Redis 가 {@code maxmemory 512mb} + {@code volatile-lru} 라
 * TTL 없는 키는 축출되지 않고 상한에 닿으면 쓰기가 거부된다(공통규약 23장).
 *
 * <p><b>Redis 가 죽으면 열어 준다(degrade-open).</b> {@code RequestIdStore} 와 같은 판단이다 —
 * 예측은 재계산해도 결과가 같은 <b>멱등 연산</b>이라, 락을 못 잡아 두 번 도는 것이
 * 아예 안 도는 것보다 낫다. 마지막 실행이 최신 데이터를 보므로 결과도 어긋나지 않는다.
 */
@Component
public class ForecastRedisStore {

    private static final Logger log = LoggerFactory.getLogger(ForecastRedisStore.class);

    private static final String DIRTY_PREFIX = "forecast:dirty:";
    private static final String LOCK_PREFIX = "forecast:lock:";

    private final StringRedisTemplate redis;

    public ForecastRedisStore(StringRedisTemplate redis) {
        this.redis = redis;
    }

    /** 이 사업장의 곡선이 낡았다고 표시한다. 여러 번 불려도 한 번과 같다. */
    public void markDirty(Long businessId, Duration ttl) {
        try {
            redis.opsForValue().set(DIRTY_PREFIX + businessId, "1", ttl);
        } catch (DataAccessException e) {
            log.warn("재계산 표시를 남기지 못했다 — 사업장 {}: {}", businessId, e.getMessage());
        }
    }

    /**
     * 표시를 <b>읽으면서 지운다.</b> 실행 직전에 부르므로, 실행 중에 새 변경이 들어오면
     * 표시가 다시 켜지고 다음 회차가 그것을 집는다 — 변경이 유실되지 않는다.
     */
    public boolean clearDirty(Long businessId) {
        try {
            return Boolean.TRUE.equals(redis.delete(DIRTY_PREFIX + businessId));
        } catch (DataAccessException e) {
            log.warn("재계산 표시를 지우지 못했다 — 사업장 {}: {}", businessId, e.getMessage());
            return true;   // 표시가 있었다고 보고 실행한다. 안 도는 것보다 낫다
        }
    }

    /** 실행을 선점한다. 이미 돌고 있으면 {@code false}. */
    public boolean tryLock(Long businessId, Duration ttl) {
        try {
            return Boolean.TRUE.equals(redis.opsForValue().setIfAbsent(LOCK_PREFIX + businessId, "1", ttl));
        } catch (DataAccessException e) {
            log.warn("실행 락을 잡지 못했다 — 사업장 {} (락 없이 진행): {}", businessId, e.getMessage());
            return true;
        }
    }

    public void unlock(Long businessId) {
        try {
            redis.delete(LOCK_PREFIX + businessId);
        } catch (DataAccessException e) {
            log.warn("실행 락을 풀지 못했다 — 사업장 {} (TTL 로 풀린다): {}", businessId, e.getMessage());
        }
    }
}
