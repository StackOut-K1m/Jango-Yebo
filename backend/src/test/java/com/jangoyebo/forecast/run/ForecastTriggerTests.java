package com.jangoyebo.forecast.run;

import com.jangoyebo.NeedsDocker;
import com.jangoyebo.core.AccountBalances;
import com.jangoyebo.core.TransactionsChanged;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 예측 재계산 트리거와 배치 (S15P21C105-135).
 *
 * <p>실행 스레드는 테스트에서 꺼 두므로({@code FORECAST_WORKER_ENABLED=false})
 * <b>여기서 확인하는 것은 「재계산 표시가 남았는가」까지</b>다. 표시 뒤의 계산은
 * {@code ForecastApiTests} 가 {@code execute()} 를 직접 불러 검증한다.
 *
 * <p>그 경계가 의도적이다 — 5초 디바운스를 기다리면 테스트가 그만큼 느려지고
 * 단정 시점과 경합한다.
 */
@NeedsDocker
@SpringBootTest
class ForecastTriggerTests {

    private static final String DIRTY_PREFIX = "forecast:dirty:";

    @Autowired
    private ApplicationEventPublisher events;

    @Autowired
    private StringRedisTemplate redis;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private TransactionTemplate tx;

    @Autowired
    private ForecastRunRepository runs;

    @Autowired
    private ForecastBatchScheduler scheduler;

    @Autowired
    private AccountBalances accountBalances;

    private Long businessId;

    @BeforeEach
    void setUp() {
        Long userId = jdbc.queryForObject("""
                INSERT INTO core.users (email, password_hash, name, status)
                VALUES (?, 'hash', '김사장', 'ACTIVE') RETURNING id
                """, Long.class, "trigger-" + System.nanoTime() + "@test.local");
        businessId = jdbc.queryForObject("""
                INSERT INTO core.business (owner_user_id, name, taxation_type, status)
                VALUES (?, '온기식당', 'GENERAL', 'ACTIVE') RETURNING id
                """, Long.class, userId);
        redis.delete(DIRTY_PREFIX + businessId);
    }

    @Test
    void 거래가_바뀌면_재계산_표시가_남는다() {
        tx.executeWithoutResult(status ->
                events.publishEvent(TransactionsChanged.bySync(businessId, 11L)));

        assertThat(isDirty()).isTrue();
    }

    @Test
    void CSV_임포트도_같은_표시를_남긴다() {
        tx.executeWithoutResult(status ->
                events.publishEvent(TransactionsChanged.byCsv(businessId, 11L)));

        assertThat(isDirty()).isTrue();
    }

    @Test
    void 예측_입력이_바뀌어도_표시가_남는다() {
        tx.executeWithoutResult(status -> events.publishEvent(
                ForecastInputsChanged.of(businessId, ForecastInputsChanged.Reason.PATTERN_CONFIRMED)));

        assertThat(isDirty()).isTrue();
    }

    @Test
    void 롤백되면_표시가_남지_않는다() {
        // AFTER_COMMIT 이라 커밋되지 않은 변경으로 곡선을 다시 그리지 않는다.
        tx.executeWithoutResult(status -> {
            events.publishEvent(TransactionsChanged.bySync(businessId, 11L));
            status.setRollbackOnly();
        });

        assertThat(isDirty()).isFalse();
    }

    @Test
    void 표시에는_수명이_있다() {
        // Redis 가 volatile-lru 라 TTL 없는 키는 축출되지 않는다(공통규약 23장).
        tx.executeWithoutResult(status ->
                events.publishEvent(TransactionsChanged.bySync(businessId, 11L)));

        Long ttl = redis.getExpire(DIRTY_PREFIX + businessId);
        assertThat(ttl).isNotNull().isGreaterThan(0);
    }

    @Test
    void 여러_번_바뀌어도_표시는_하나다() {
        // CSV 200건 업로드에 200회 재계산이 돌면 안 된다(공통규약 2.6).
        tx.executeWithoutResult(status -> {
            for (int i = 0; i < 50; i++) {
                events.publishEvent(TransactionsChanged.byCsv(businessId, 11L));
            }
        });

        assertThat(isDirty()).isTrue();
        assertThat(redis.keys(DIRTY_PREFIX + businessId)).hasSize(1);
    }

    @Test
    void 트랜잭션_밖에서_발행해도_버리지_않는다() {
        // fallbackExecution = true. 없으면 이런 발행이 조용히 사라진다.
        events.publishEvent(TransactionsChanged.bySync(businessId, 11L));

        assertThat(isDirty()).isTrue();
    }

    @Test
    void 새벽_배치는_연결된_계좌가_있는_사업장만_고른다() {
        // 계좌가 없으면 시작 잔액이 없어 곡선을 만들 수 없다.
        assertThat(accountBalances.businessIdsWithConnectedAccounts()).doesNotContain(businessId);

        connectedAccount();

        assertThat(accountBalances.businessIdsWithConnectedAccounts()).contains(businessId);
    }

    @Test
    void 새벽_배치가_전_사업장에_표시를_남긴다() {
        connectedAccount();

        scheduler.scheduleDailyRecalculation();

        assertThat(isDirty()).isTrue();
    }

    @Test
    void 정리_배치가_오래된_실행을_지우고_최신_하나를_남긴다() {
        Long old1 = insertRun(Instant.now().minus(5, ChronoUnit.DAYS), "COMPLETED");
        Long old2 = insertRun(Instant.now().minus(3, ChronoUnit.DAYS), "COMPLETED");
        backdate(List.of(old1, old2));

        scheduler.cleanUpOldRuns();

        assertThat(runs.findById(old1)).isEmpty();
        assertThat(runs.findById(old2)).isPresent();   // 사업장별 최신 COMPLETED 는 남는다
    }

    @Test
    void 정리_배치가_최근_실행은_건드리지_않는다() {
        Long recent = insertRun(Instant.now(), "COMPLETED");

        scheduler.cleanUpOldRuns();

        assertThat(runs.findById(recent)).isPresent();
    }

    // --- 헬퍼 -----------------------------------------------------------------

    private boolean isDirty() {
        return Boolean.TRUE.equals(redis.hasKey(DIRTY_PREFIX + businessId));
    }

    private void connectedAccount() {
        jdbc.update("""
                INSERT INTO core.account (business_id, provider, bank_code, account_number_encrypted,
                                          account_number_hash, account_number_masked, name,
                                          status, sync_status, current_balance, balance_as_of)
                VALUES (?, 'FINOPENAPI', '004', 'enc', ?, '004-**-1234', '주계좌',
                        'CONNECTED', 'SUCCESS', 5000000, now())
                """, businessId, "hash-" + System.nanoTime());
    }

    private Long insertRun(Instant baseAt, String status) {
        return jdbc.queryForObject("""
                INSERT INTO forecast.forecast_run
                    (business_id, base_at, start_date, end_date, status, model_version,
                     opening_balances, basis, created_at)
                VALUES (?, ?, current_date + 1, current_date + 30, ?, 'engine-1.0/none',
                        '[]'::jsonb, '{}'::jsonb, now())
                RETURNING id
                """, Long.class, businessId, java.sql.Timestamp.from(baseAt), status);
    }

    /** {@code created_at} 은 코드로 못 바꾼다. 정리 대상으로 만든다. */
    private void backdate(List<Long> ids) {
        for (Long id : ids) {
            jdbc.update("UPDATE forecast.forecast_run SET created_at = now() - INTERVAL '40 days' WHERE id = ?", id);
        }
    }
}
