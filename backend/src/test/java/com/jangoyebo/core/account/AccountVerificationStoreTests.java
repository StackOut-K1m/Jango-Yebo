package com.jangoyebo.core.account;

import com.jangoyebo.NeedsDocker;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.time.Duration;
import java.util.concurrent.ThreadLocalRandom;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 1원 인증 발급 기록 검증.
 *
 * <p>이 저장소가 {@code ACCOUNT} 에 없는 두 가지를 대신 들고 있다 — <b>발급 시각</b>(TTL)과
 * <b>재발송 횟수</b>. 그래서 두 가지가 중요하다: TTL 이 모든 경로에 걸리는가
 * ({@code volatile-lru} 에서 TTL 없는 키는 축출되지 않는다), 그리고 횟수가 정확한가.
 *
 * <p><b>Docker가 필요하다.</b> Testcontainers 로 {@code redis:7-alpine} 을 띄운다.
 */
@NeedsDocker
@SpringBootTest
class AccountVerificationStoreTests {

    private static final Duration TTL = Duration.ofMinutes(10);

    @Autowired
    private AccountVerificationStore store;

    @Autowired
    private StringRedisTemplate redis;

    /** 테스트끼리 계좌 ID 가 겹치면 횟수가 섞인다. */
    private Long accountId() {
        return ThreadLocalRandom.current().nextLong(1_000_000, 9_999_999);
    }

    @Test
    void recordsFirstIssueAndMarksPending() {
        Long id = accountId();

        assertThat(store.isPending(id)).isFalse();
        assertThat(store.issueCount(id)).isZero();

        assertThat(store.recordIssue(id, TTL)).isEqualTo(1L);
        assertThat(store.isPending(id)).isTrue();
        assertThat(store.issueCount(id)).isEqualTo(1L);
    }

    @Test
    void countsResends() {
        Long id = accountId();

        assertThat(store.recordIssue(id, TTL)).isEqualTo(1L);
        assertThat(store.recordIssue(id, TTL)).isEqualTo(2L);
        assertThat(store.recordIssue(id, TTL)).isEqualTo(3L);
        assertThat(store.issueCount(id)).isEqualTo(3L);
    }

    /**
     * <b>TTL 이 한 번이라도 새면 Redis 전체가 멈춘다</b>({@code maxmemory 512mb} +
     * {@code volatile-lru} 에서 TTL 없는 키는 축출되지 않는다). 최초 발급과 재발송 양쪽을 본다.
     */
    @Test
    void everyIssuePathSetsTtl() {
        Long id = accountId();

        store.recordIssue(id, TTL);
        assertThat(redis.getExpire("account:auth:" + id)).isPositive();

        store.recordIssue(id, TTL);
        assertThat(redis.getExpire("account:auth:" + id)).isPositive();
    }

    /**
     * 재발송은 유효시간을 다시 시작한다. 안 그러면 사용자가 방금 받은 코드를 넣을 시간이 없어
     * 재발송 자체가 무의미해진다.
     */
    @Test
    void resendRestartsTheValidityWindow() {
        Long id = accountId();
        store.recordIssue(id, Duration.ofSeconds(30));
        assertThat(redis.getExpire("account:auth:" + id)).isLessThanOrEqualTo(30);

        store.recordIssue(id, TTL);

        assertThat(redis.getExpire("account:auth:" + id)).isGreaterThan(30);
    }

    /** 남겨 두면 재발송 횟수가 다음 인증까지 따라와 두 번째 연결이 곧바로 상한에 걸린다. */
    @Test
    void clearResetsEverything() {
        Long id = accountId();
        store.recordIssue(id, TTL);
        store.recordIssue(id, TTL);

        store.clear(id);

        assertThat(store.isPending(id)).isFalse();
        assertThat(store.issueCount(id)).isZero();
        assertThat(store.recordIssue(id, TTL)).isEqualTo(1L);
    }
}
