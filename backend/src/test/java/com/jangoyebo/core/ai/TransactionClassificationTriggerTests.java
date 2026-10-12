package com.jangoyebo.core.ai;

import com.jangoyebo.NeedsDocker;
import com.jangoyebo.core.TransactionsChanged;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 거래 분류 자동 실행 (API 8.5 — "동기화·CSV 업로드 완료 시 자동 실행", 합의 ④).
 *
 * <p>이벤트를 직접 발행해 확인한다. 리스너가 {@code fallbackExecution = true} 라 트랜잭션
 * 밖에서 발행해도 같은 스레드에서 바로 돈다 — 수집 경로 전체를 태우지 않아도 <b>트리거의
 * 판단</b>만 떼어 볼 수 있다.
 *
 * <p>⚠ <b>그 편의가 구멍을 만들었다.</b> 트랜잭션 밖 발행은 {@code fallbackExecution} 경로라
 * 리스너가 <b>새 트랜잭션을 연다</b>. 운영은 반대다 — {@code AccountSyncService.succeed} 가
 * 커밋되는 트랜잭션 안에서 발행하고, 그때 {@code AFTER_COMMIT} 콜백은 <b>방금 커밋된(아직
 * 정리되지 않은) 트랜잭션</b> 위에서 돈다. 거기서 {@code REQUIRED} 로 열면 죽은 트랜잭션에
 * 합류해 <b>INSERT 가 예외도 로그도 없이 사라진다.</b> 아래 테스트들은 전부 초록인데
 * 실제로는 분류가 한 번도 돌지 않고 있었다. {@link #커밋되는_트랜잭션_안에서_발행해도_작업이_남는다}
 * 가 그 자리를 지킨다.
 *
 * <p><b>자동 실행을 켜고 본다.</b> 기본값은 꺼짐이라(시드를 넣고 빼는 동안 토큰이 나간다)
 * 그대로 두면 아래가 전부 「안 넣는다」가 되어 <b>트리거의 판단을 아무것도 검사하지 못한다.</b>
 * 꺼진 쪽은 {@link TransactionClassificationTriggerDisabledTests} 가 본다.
 *
 * <p><b>Docker가 필요하다.</b>
 */
@NeedsDocker
@SpringBootTest(properties = "jango.classification.auto-trigger=true")
class TransactionClassificationTriggerTests {

    @Autowired private ApplicationEventPublisher events;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private TransactionTemplate transactions;

    private Long userId;
    private Long businessId;
    private Long accountId;

    @BeforeEach
    void setUp() {
        String unique = String.valueOf(System.nanoTime());
        userId = jdbc.queryForObject("""
                INSERT INTO core.users (email, password_hash, name)
                VALUES (?, 'x', '트리거') RETURNING id
                """, Long.class, "trigger-" + unique + "@test.local");
        businessId = jdbc.queryForObject("""
                INSERT INTO core.business (owner_user_id, name) VALUES (?, '트리거 사업장') RETURNING id
                """, Long.class, userId);
        accountId = jdbc.queryForObject("""
                INSERT INTO core.account
                    (business_id, bank_code, account_number_encrypted, account_number_hash,
                     account_number_masked, name, status)
                VALUES (?, '088', 'enc', ?, '***1234', '주거래', 'CONNECTED') RETURNING id
                """, Long.class, businessId, "hash-" + unique);
    }

    private List<Map<String, Object>> classificationJobs() {
        return jdbc.queryForList("""
                SELECT id, status, requested_by FROM core.job
                 WHERE business_id = ? AND job_type = 'TRANSACTION_CLASSIFICATION'
                 ORDER BY id
                """, businessId);
    }

    private void job(String status) {
        jdbc.update("""
                INSERT INTO core.job (business_id, job_type, status)
                VALUES (?, 'TRANSACTION_CLASSIFICATION', ?)
                """, businessId, status);
    }

    /** 기준선 — CSV 임포트가 끝나면 분류가 스스로 시작된다. */
    @Test
    void enqueuesClassificationWhenCsvImportFinishes() {
        events.publishEvent(TransactionsChanged.byCsv(businessId, accountId));

        assertThat(classificationJobs()).singleElement().satisfies(job -> {
            assertThat(job.get("status")).isEqualTo("QUEUED");
            // 시스템이 넣은 작업이라 요청한 사용자가 없다.
            assertThat(job.get("requested_by")).isNull();
        });
    }

    /**
     * <b>운영 경로다.</b> 발행이 커밋되는 트랜잭션 안에서 일어나고, 그 뒤에 넣은 Job 이
     * <b>실제로 커밋돼 있어야</b> 한다.
     *
     * <p>위 테스트들과 한 줄 차이인데 그 한 줄이 전부다 — 트랜잭션 밖 발행은
     * {@code fallbackExecution} 경로로 빠져 문제가 되는 자리를 지나가지 않는다.
     * 여기가 깨지면 <b>거래가 영영 미분류로 남고</b>, 반복 패턴의 분류와 시나리오의
     * 매출 변화(11.1)가 줄줄이 조용히 틀린다.
     */
    @Test
    void 커밋되는_트랜잭션_안에서_발행해도_작업이_남는다() {
        transactions.executeWithoutResult(status ->
                events.publishEvent(TransactionsChanged.bySync(businessId, accountId)));

        assertThat(classificationJobs())
                .as("AFTER_COMMIT 에서 넣은 Job 이 커밋돼 있어야 한다")
                .hasSize(1);
    }

    /** 금융망 동기화도 같다. */
    @Test
    void enqueuesClassificationWhenSyncFinishes() {
        events.publishEvent(TransactionsChanged.bySync(businessId, accountId));

        assertThat(classificationJobs()).hasSize(1);
    }

    /**
     * <b>이미 도는 작업이 있으면 넣지 않는다</b> (합의 ④).
     *
     * <p>핸들러가 그때의 미분류 거래를 조회하므로, 콜백 전에 Job 이 둘 뜨면 둘 다 같은 거래를
     * 집어 LLM 에 두 번 보낸다 — 값은 두 번 나가고 결과는 하나다.
     */
    @Test
    void doesNotEnqueueWhileAnotherIsUnfinished() {
        job("QUEUED");

        events.publishEvent(TransactionsChanged.byCsv(businessId, accountId));

        assertThat(classificationJobs()).hasSize(1);
    }

    /** 워커가 집어 간 뒤(=콜백 대기)도 마찬가지다. 그때가 오히려 중복 과금이 나는 구간이다. */
    @Test
    void doesNotEnqueueWhileAnotherIsProcessing() {
        job("PROCESSING");

        events.publishEvent(TransactionsChanged.bySync(businessId, accountId));

        assertThat(classificationJobs()).hasSize(1);
    }

    /** 끝난 작업은 막지 않는다 — 다음 수집은 새로 분류해야 한다. */
    @Test
    void enqueuesAgainAfterThePreviousOneFinished() {
        job("COMPLETED");
        job("FAILED");

        events.publishEvent(TransactionsChanged.byCsv(businessId, accountId));

        assertThat(classificationJobs()).hasSize(3);
    }

    /**
     * 계좌 해제로는 분류를 걸지 않는다.
     *
     * <p>거래 행이 하나도 바뀌지 않은 사건이다. 오히려 그 계좌의 거래는 이제 분류 대상에서
     * 빠지므로({@code TransactionRepository#findUnclassified}) 분류할 것이 없는 Job 만 뜬다.
     */
    @Test
    void ignoresAccountDisconnected() {
        events.publishEvent(TransactionsChanged.byDisconnect(businessId, accountId));

        assertThat(classificationJobs()).isEmpty();
    }

    /** 다른 사업장의 작업은 가드에 걸리지 않는다 — 가드는 사업장 단위다. */
    @Test
    void guardsPerBusiness() {
        Long otherBusinessId = jdbc.queryForObject("""
                INSERT INTO core.business (owner_user_id, name) VALUES (?, '다른 사업장') RETURNING id
                """, Long.class, userId);
        jdbc.update("""
                INSERT INTO core.job (business_id, job_type, status)
                VALUES (?, 'TRANSACTION_CLASSIFICATION', 'PROCESSING')
                """, otherBusinessId);

        events.publishEvent(TransactionsChanged.byCsv(businessId, accountId));

        assertThat(classificationJobs()).hasSize(1);
    }
}
