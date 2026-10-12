package com.jangoyebo.common.idempotency;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * {@code X-Request-Id} 중복 처리 방지 (명세 2.8 · 20.1).
 *
 * <p>내부 API 의 모든 변경 요청은 {@code X-Request-Id} 가 필수이며 동일 ID 재시도는 중복
 * 저장하지 않는다. 처리 결과는 Redis 에 단기 보관한다.
 *
 * <p>키에 TTL 이 반드시 있어야 한다 — Redis 가 {@code maxmemory 512mb} + {@code volatile-lru}
 * 라 TTL 없는 키는 축출되지 않고 상한에 닿으면 쓰기가 거부된다(인프라 제약 §4.1 #3).
 * {@code RefreshTokenStore} 와 같은 이유이고, TTL 을 호출자가 넘기는 방식도 같다.
 *
 * <p><b>{@code setIfAbsent}(SET NX EX)를 쓴다.</b> {@code RefreshTokenStore.save} 의 무조건
 * {@code set} 은 원자적 선점이 아니라 멱등에 쓸 수 없다 — 동시에 도착한 재시도 두 건이 모두
 * 통과한다.
 *
 * <p><b>Redis 는 최종 권위가 아니다.</b> 여기서 막는 것은 동시 도착한 중복이고, 진짜 멱등
 * 판정은 호출자가 도메인 상태(예: Job 이 이미 종단 상태인지)로 한다. 그래서 Redis 장애 시
 * 예외를 밖으로 던지지 않고 통과시킨다(degrade-open) — Redis 가 흔들려 결과를 잃는 것보다
 * 이중 적용이 상태 가드에 막히는 쪽이 낫다.
 */
@Component
public class RequestIdStore {

    private static final Logger log = LoggerFactory.getLogger(RequestIdStore.class);

    private static final String KEY_PREFIX = "idem:";
    private static final String IN_PROGRESS = "IN_PROGRESS";
    private static final String DONE = "DONE";

    private final StringRedisTemplate redis;

    public RequestIdStore(StringRedisTemplate redis) {
        this.redis = redis;
    }

    /** 선점 결과. {@code RESERVED} 만 처리를 진행해도 되는 상태다. */
    public enum Reservation {
        /** 이번 호출이 선점했다. 처리하고 {@link #markDone} 으로 마무리한다. */
        RESERVED,
        /** 같은 요청이 아직 처리 중이다. 결과를 아직 모르므로 재시도를 유도해야 한다. */
        IN_PROGRESS,
        /** 같은 요청이 이미 처리됐다. */
        DONE
    }

    /**
     * @param claimTtl 처리 중 선점 TTL. <b>짧게</b> 준다 — 선점 직후 프로세스가 죽으면 이
     *                 시간만큼 같은 요청이 막힌다. 회수 장치(스위퍼 등)의 임계값보다 짧아야
     *                 재실행이 가능하다.
     */
    public Reservation reserve(String scope, String requestId, Duration claimTtl) {
        String key = key(scope, requestId);
        try {
            if (Boolean.TRUE.equals(redis.opsForValue().setIfAbsent(key, IN_PROGRESS, claimTtl))) {
                return Reservation.RESERVED;
            }
            return DONE.equals(redis.opsForValue().get(key)) ? Reservation.DONE : Reservation.IN_PROGRESS;
        } catch (DataAccessException e) {
            log.warn("멱등 키 선점 실패 — Redis 없이 진행한다. 중복은 도메인 상태 가드가 막는다. key={}", key, e);
            return Reservation.RESERVED;
        }
    }

    /**
     * 이미 {@code DONE} 인 키를 다시 잡는다.
     *
     * <p>도메인 상태가 "아직 안 끝났다"고 말하는데 키만 {@code DONE} 인 경우가 있다 — 회수
     * 장치가 작업을 되돌려 재실행된 경우다. 그 키를 그대로 두면 재실행 결과를 영영 받을 수
     * 없으므로 다시 선점한다.
     */
    public void takeOver(String scope, String requestId, Duration claimTtl) {
        execute(() -> redis.opsForValue().set(key(scope, requestId), IN_PROGRESS, claimTtl),
                "멱등 키 재선점 실패", scope, requestId);
    }

    /** @param doneTtl 처리 완료 기록 보관 기간. 공통규약 23장 — 24시간. */
    public void markDone(String scope, String requestId, Duration doneTtl) {
        execute(() -> redis.opsForValue().set(key(scope, requestId), DONE, doneTtl),
                "멱등 키 완료 표시 실패", scope, requestId);
    }

    /**
     * 선점을 되돌린다. 처리가 실패했을 때 <b>반드시</b> 불러야 한다 — 그러지 않으면 그 요청의
     * 재시도가 {@code claimTtl} 동안 막힌다.
     */
    public void release(String scope, String requestId) {
        execute(() -> redis.delete(key(scope, requestId)),
                "멱등 키 해제 실패", scope, requestId);
    }

    private void execute(Runnable action, String failureMessage, String scope, String requestId) {
        try {
            action.run();
        } catch (DataAccessException e) {
            log.warn("{} — scope={} requestId={}", failureMessage, scope, requestId, e);
        }
    }

    private String key(String scope, String requestId) {
        return KEY_PREFIX + scope + ":" + requestId;
    }
}
