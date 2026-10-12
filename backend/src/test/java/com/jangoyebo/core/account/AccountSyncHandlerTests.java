package com.jangoyebo.core.account;

import com.jangoyebo.common.error.BusinessException;
import com.jangoyebo.common.error.ErrorCode;
import com.jangoyebo.core.account.AccountSyncService.SyncContext;
import com.jangoyebo.core.fin.DemandDepositClient;
import com.jangoyebo.core.fin.DemandDepositClient.TransactionRec;
import com.jangoyebo.core.fin.FinApiException;
import com.jangoyebo.core.fin.FinOpenApiProperties;
import com.jangoyebo.core.fin.FinUserKeyService;
import com.jangoyebo.core.job.Job;
import com.jangoyebo.core.job.JobHandler;
import com.jangoyebo.core.job.JobType;
import com.jangoyebo.core.transaction.FinTransactionRow;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import java.io.IOException;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 동기화 핸들러 검증.
 *
 * <p><b>Spring 컨텍스트도 Docker 도 쓰지 않는다.</b> 확인할 것이 "실패를 Job 의 운명으로 어떻게
 * 옮기는가"와 "계좌 상태를 언제 남기는가"뿐이라 협력자만 대역으로 바꾸면 된다
 * ({@code EvidenceExtractionHandlerTests} 와 같은 구조 — 이 저장소는 Mockito 를 쓰지 않는다).
 *
 * <p>가장 중요한 단정은 <b>{@code markFailed} 가 불렸는가</b>다. {@code JobWorker} 는 Job 만
 * 건드리므로, 핸들러가 안 남기면 계좌는 영원히 {@code RUNNING} 에 갇힌다.
 */
class AccountSyncHandlerTests {

    private static final Instant CREATED_AT = Instant.now().minus(3, ChronoUnit.DAYS);
    private static final String AUTH_TEXT = "잔고예보";

    /** 호출 흔적을 모으는 대역. 무엇이 불렸는지가 이 테스트의 전부다. */
    private static class RecordingSyncService extends AccountSyncService {
        final List<FinTransactionRow> persisted = new ArrayList<>();
        final AtomicBoolean succeeded = new AtomicBoolean();
        final AtomicBoolean failed = new AtomicBoolean();
        final AtomicBoolean balanceApplied = new AtomicBoolean();
        BusinessException beginFailure;

        RecordingSyncService() {
            // 이 스텁은 상위 메서드를 전부 재정의하므로 협력자를 쓰지 않는다.
            // 이벤트 발행자까지 null 인 이유도 같다 — succeed() 를 아래에서 가로챈다.
            super(null, null, null, null);
        }

        @Override
        public SyncContext begin(Long accountId, Long jobBusinessId) {
            if (beginFailure != null) {
                throw beginFailure;
            }
            return new SyncContext(7L, "0000000000000000", CREATED_AT, null);
        }

        @Override
        public int persistChunk(List<FinTransactionRow> rows) {
            persisted.addAll(rows);
            return rows.size();          // 전부 신규라고 본다
        }

        @Override
        public void applyBalance(Long accountId, BigDecimal balance, Instant asOf) {
            balanceApplied.set(true);
        }

        @Override
        public void succeed(Long accountId, Instant syncedAt) {
            succeeded.set(true);
        }

        @Override
        public void markFailed(Long accountId) {
            failed.set(true);
        }
    }

    private static DemandDepositClient clientThat(History history, Balance balance) {
        return new DemandDepositClient(null) {
            @Override
            public List<TransactionRec> inquireTransactionHistory(
                    String userKey, String accountNo, String startYmd, String endYmd) {
                return history.fetch(startYmd, endYmd);
            }

            @Override
            public Optional<BigDecimal> inquireBalance(String userKey, String accountNo) {
                return balance.fetch();
            }
        };
    }

    @FunctionalInterface
    private interface History {
        List<TransactionRec> fetch(String startYmd, String endYmd);
    }

    @FunctionalInterface
    private interface Balance {
        Optional<BigDecimal> fetch();
    }

    private static FinUserKeyService userKeyThat(String key) {
        return new FinUserKeyService(null, null, null) {
            @Override
            public String resolve(Long userId) {
                return key;
            }
        };
    }

    private static FinOpenApiProperties properties() {
        return new FinOpenApiProperties("https://fin.test", "k", "00100", "001",
                new FinOpenApiProperties.Client(Duration.ofSeconds(3), Duration.ofSeconds(15)),
                new FinOpenApiProperties.AccountAuth(AUTH_TEXT, Duration.ofMinutes(10), 3));
    }

    /** 재우지 않는다 — 백오프가 실제로 자면 테스트가 4초 걸린다. */
    private static AccountSyncHandler handler(RecordingSyncService sync, DemandDepositClient client) {
        return new AccountSyncHandler(sync, client, userKeyThat("user-key"), properties()) {
            @Override
            void sleepQuietly(Duration duration) {
                // 의도적으로 아무것도 하지 않는다
            }
        };
    }

    private static Job job(Map<String, Object> params) {
        return Job.queue(7L, 11L, JobType.ACCOUNT_SYNC, params);
    }

    private static TransactionRec rec(String no, String type, String amount, String summary) {
        return new TransactionRec(no, "20260908", "101010", type, amount, "0", summary, "");
    }

    @Test
    void collectsEveryWindowAndReportsTheCounts() {
        var sync = new RecordingSyncService();
        var handler = handler(sync, clientThat(
                (start, end) -> List.of(rec("1", "1", "1000", "정산"), rec("2", "2", "300", "월세")),
                () -> Optional.of(new BigDecimal("5000"))));

        JobHandler.Outcome outcome = handler.handle(job(Map.of("accountId", 42)));

        assertThat(outcome).isInstanceOf(JobHandler.Outcome.Completed.class);
        Map<String, Object> result = ((JobHandler.Outcome.Completed) outcome).result();
        assertThat(result).containsEntry("accountId", 42L).containsEntry("duplicateCount", 0);
        assertThat((Integer) result.get("importedCount")).isPositive();
        assertThat(sync.succeeded).isTrue();
        assertThat(sync.failed).isFalse();
        assertThat(sync.balanceApplied).isTrue();
    }

    /** 계좌 연결마다 최소 1건씩 쌓인다. 안 거르면 잔액 곡선에까지 들어간다. */
    @Test
    void dropsTheOneWonAuthDepositsItFinds() {
        var sync = new RecordingSyncService();
        var handler = handler(sync, clientThat(
                (start, end) -> List.of(
                        rec("1", "1", "1", "잔고예보 4653"),
                        rec("2", "1", "1", "잔고예보 5020"),
                        rec("3", "1", "250000", "배민 정산")),
                Optional::empty));

        handler.handle(job(Map.of("accountId", 42)));

        assertThat(sync.persisted).hasSize(1);
        assertThat(sync.persisted.getFirst().description()).isEqualTo("배민 정산");
    }

    /**
     * <b>걸러낸 1원을 중복으로 세면 안 된다.</b> {@code duplicateCount} 의 기준은 수신 건수가
     * 아니라 넣으려 한 건수다. 실기동에서 잡힌 결함이라 여기 못 박는다 —
     * 인증 1원만 있는 계좌를 동기화하면 "중복 5건"이라는 거짓말이 프론트로 나갔다.
     */
    @Test
    void doesNotCountFilteredAuthDepositsAsDuplicates() {
        var sync = new RecordingSyncService();
        var handler = handler(sync, clientThat(
                (start, end) -> List.of(
                        rec("1", "1", "1", "잔고예보 4653"),
                        rec("2", "1", "1", "잔고예보 5020"),
                        rec("3", "1", "250000", "배민 정산")),
                Optional::empty));

        var result = ((JobHandler.Outcome.Completed) handler.handle(job(Map.of("accountId", 42)))).result();

        assertThat(result).containsEntry("importedCount", 1).containsEntry("duplicateCount", 0);
    }

    /** 진짜 중복은 센다 — 삽입기가 넣지 못한 만큼이다. */
    @Test
    void countsRowsTheWriterRefusedAsDuplicates() {
        var sync = new RecordingSyncService() {
            @Override
            public int persistChunk(List<FinTransactionRow> rows) {
                super.persistChunk(rows);
                return rows.size() - 1;      // 한 건은 이미 있었다
            }
        };
        var handler = handler(sync, clientThat(
                (start, end) -> List.of(rec("1", "1", "1000", "정산"), rec("2", "1", "2000", "정산")),
                Optional::empty));

        var result = ((JobHandler.Outcome.Completed) handler.handle(job(Map.of("accountId", 42)))).result();

        assertThat(result).containsEntry("importedCount", 1).containsEntry("duplicateCount", 1);
    }

    /**
     * 영구 실패 — Job 을 즉시 끝내고 <b>계좌에도 실패를 남긴다.</b>
     * {@code JobWorker} 는 Job 만 건드리므로 여기서 안 남기면 계좌가 {@code RUNNING} 에 갇힌다.
     */
    @Test
    void marksTheAccountFailedBeforeThrowingOnPermanentFailure() {
        var sync = new RecordingSyncService();
        var handler = handler(sync, clientThat((start, end) -> {
            throw new FinApiException("A1003", HttpStatus.BAD_REQUEST, "계좌번호가 유효하지 않습니다.");
        }, Optional::empty));

        assertThatThrownBy(() -> handler.handle(job(Map.of("accountId", 42))))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).errorCode())
                .isEqualTo(ErrorCode.ACCOUNT_NO_INVALID);

        assertThat(sync.failed).isTrue();
        assertThat(sync.succeeded).isFalse();
    }

    /**
     * 일시 실패 — Job 을 종결하지 않고 스위퍼에 맡긴다. <b>계좌는 {@code RUNNING} 그대로 둔다</b>
     * — 다음 시도가 이어받아야 하므로 실패로 표시하면 안 된다.
     */
    @Test
    void leavesTheJobUnsettledOnTransientFailure() {
        var sync = new RecordingSyncService();
        var attempts = new AtomicInteger();
        var handler = handler(sync, clientThat((start, end) -> {
            attempts.incrementAndGet();
            throw new FinApiException("연결 실패", new IOException());
        }, Optional::empty));

        assertThat(handler.handle(job(Map.of("accountId", 42))))
                .isEqualTo(JobHandler.Outcome.AWAITING_CALLBACK);

        assertThat(sync.failed).as("일시 실패는 계좌 상태를 건드리지 않는다").isFalse();
        assertThat(attempts.get()).as("백오프만큼 재조회한다").isEqualTo(3);
    }

    /** 깜빡임 뒤 성공하면 그대로 진행한다 — 스위퍼까지 가지 않는다. */
    @Test
    void recoversWhenTheRetrySucceeds() {
        var sync = new RecordingSyncService();
        var attempts = new AtomicInteger();
        var handler = handler(sync, clientThat((start, end) -> {
            if (attempts.incrementAndGet() == 1) {
                throw new FinApiException("연결 실패", new IOException());
            }
            return List.of(rec("1", "1", "1000", "정산"));
        }, Optional::empty));

        assertThat(handler.handle(job(Map.of("accountId", 42))))
                .isInstanceOf(JobHandler.Outcome.Completed.class);
        assertThat(sync.succeeded).isTrue();
    }

    /**
     * 잔액 실패는 동기화를 죽이지 않는다. 본체는 거래 수집이고 잔액은 곁다리라, 여기서
     * 치명적으로 다루면 <b>미검증 apiName 하나가 모든 동기화를 죽인다.</b>
     */
    @Test
    void completesEvenWhenTheBalanceInquiryFails() {
        var sync = new RecordingSyncService();
        var handler = handler(sync, clientThat(
                (start, end) -> List.of(rec("1", "1", "1000", "정산")),
                () -> {
                    throw new FinApiException("H1009", HttpStatus.BAD_REQUEST, "USER_KEY가 유효하지 않습니다.");
                }));

        assertThat(handler.handle(job(Map.of("accountId", 42))))
                .isInstanceOf(JobHandler.Outcome.Completed.class);
        assertThat(sync.succeeded).isTrue();
        assertThat(sync.balanceApplied).isFalse();
    }

    /** 인증 전 계좌 — {@code begin} 이 막는다. {@code sync_status} 는 건드리지 않아야 한다. */
    @Test
    void refusesAccountsThatAreNotVerifiedYet() {
        var sync = new RecordingSyncService();
        sync.beginFailure = new BusinessException(ErrorCode.ACCOUNT_NOT_VERIFIED);
        var handler = handler(sync, clientThat((start, end) -> List.of(), Optional::empty));

        assertThatThrownBy(() -> handler.handle(job(Map.of("accountId", 42))))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).errorCode())
                .isEqualTo(ErrorCode.ACCOUNT_NOT_VERIFIED);

        assertThat(sync.failed)
                .as("begin 이 막았으면 RUNNING 을 쓴 적도 없다")
                .isFalse();
    }

    @Test
    void failsWhenAccountIdIsMissing() {
        var handler = handler(new RecordingSyncService(),
                clientThat((start, end) -> List.of(), Optional::empty));

        assertThatThrownBy(() -> handler.handle(job(Map.of())))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("accountId");
    }

    /** 스케줄러를 붙이면 {@code requestedBy} 가 null 이 된다. 조용히 넘어가면 안 된다. */
    @Test
    void failsWhenTheJobHasNoRequester() {
        var handler = handler(new RecordingSyncService(),
                clientThat((start, end) -> List.of(), Optional::empty));
        Job unattended = Job.queue(7L, null, JobType.ACCOUNT_SYNC, Map.of("accountId", 42));

        assertThatThrownBy(() -> handler.handle(unattended))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("소유자");
    }
}
