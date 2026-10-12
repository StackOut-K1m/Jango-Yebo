package com.jangoyebo.common.idempotency;

import com.jangoyebo.NeedsDocker;
import com.jangoyebo.common.idempotency.RequestIdStore.Reservation;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 멱등 저장소 검증.
 *
 * <p>핵심은 <b>모든 경로에서 TTL 이 살아 있는지</b>다. TTL 없는 키는
 * {@code maxmemory 512mb} + {@code volatile-lru} 에서 축출되지 않아 상한에 닿으면
 * 쓰기가 거부된다(공통규약 23장). 한 번 새면 Redis 전체가 멈춘다.
 *
 * <p><b>Docker가 필요하다.</b> Testcontainers 로 {@code redis:7-alpine} 을 띄운다.
 */
@NeedsDocker
@SpringBootTest
class RequestIdStoreTests {

    private static final Duration CLAIM_TTL = Duration.ofMinutes(1);
    private static final Duration DONE_TTL = Duration.ofHours(24);

    @Autowired
    private RequestIdStore store;

    @Autowired
    private StringRedisTemplate redis;

    @Test
    void firstCallerReservesAndSecondSeesInProgress() {
        String scope = scope();

        assertThat(store.reserve(scope, "req-1", CLAIM_TTL)).isEqualTo(Reservation.RESERVED);
        assertThat(store.reserve(scope, "req-1", CLAIM_TTL)).isEqualTo(Reservation.IN_PROGRESS);
    }

    @Test
    void markDoneMakesLaterCallersSeeDone() {
        String scope = scope();
        store.reserve(scope, "req-1", CLAIM_TTL);

        store.markDone(scope, "req-1", DONE_TTL);

        assertThat(store.reserve(scope, "req-1", CLAIM_TTL)).isEqualTo(Reservation.DONE);
    }

    /** 실패 보상. 해제하지 않으면 그 요청의 재시도가 claim-ttl 동안 막힌다. */
    @Test
    void releaseAllowsRetry() {
        String scope = scope();
        store.reserve(scope, "req-1", CLAIM_TTL);

        store.release(scope, "req-1");

        assertThat(store.reserve(scope, "req-1", CLAIM_TTL)).isEqualTo(Reservation.RESERVED);
    }

    /** 회수 후 재실행분을 받으려면 DONE 키를 다시 잡을 수 있어야 한다. */
    @Test
    void takeOverReclaimsDoneKey() {
        String scope = scope();
        store.reserve(scope, "req-1", CLAIM_TTL);
        store.markDone(scope, "req-1", DONE_TTL);

        store.takeOver(scope, "req-1", CLAIM_TTL);

        assertThat(store.reserve(scope, "req-1", CLAIM_TTL))
                .as("재선점 후에는 DONE 이 아니라 처리 중이어야 한다")
                .isEqualTo(Reservation.IN_PROGRESS);
    }

    /** 다른 작업의 키를 건드리면 안 된다. scope 에 jobId 를 넣는 이유다. */
    @Test
    void scopesAreIsolated() {
        String first = scope();
        String second = scope();
        store.reserve(first, "same-id", CLAIM_TTL);

        assertThat(store.reserve(second, "same-id", CLAIM_TTL)).isEqualTo(Reservation.RESERVED);
    }

    @Test
    void everyWriteLeavesTtlOnTheKey() {
        String scope = scope();
        String key = "idem:" + scope + ":req-1";

        store.reserve(scope, "req-1", CLAIM_TTL);
        assertThat(redis.getExpire(key)).as("선점 키에 TTL 이 없다").isPositive();

        store.markDone(scope, "req-1", DONE_TTL);
        assertThat(redis.getExpire(key)).as("완료 키에 TTL 이 없다").isPositive();

        store.takeOver(scope, "req-1", CLAIM_TTL);
        assertThat(redis.getExpire(key)).as("재선점 키에 TTL 이 없다").isPositive();
    }

    /** 테스트 간 키 충돌을 피한다. Redis 는 컨테이너 하나를 공유한다. */
    private String scope() {
        return "test:" + System.nanoTime();
    }
}
