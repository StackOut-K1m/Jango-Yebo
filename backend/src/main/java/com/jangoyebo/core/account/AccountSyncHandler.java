package com.jangoyebo.core.account;

import com.jangoyebo.common.error.BusinessException;
import com.jangoyebo.common.error.ErrorCode;
import com.jangoyebo.core.account.AccountSyncPolicy.DateWindow;
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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 금융망 거래 동기화 (API 7.3).
 *
 * <p><b>트랜잭션을 열지 않는다.</b> 금융망 왕복이 구간 수만큼 이어지므로 여기에
 * {@code @Transactional} 을 붙이면 커넥션을 수 분간 쥔다 — 짧은 트랜잭션은 전부
 * {@link AccountSyncService} 가 연다.
 *
 * <p><b>{@code AWAITING_CALLBACK} 은 여기서 "콜백을 기다린다"는 뜻이 아니다.</b> 이 작업에는
 * 콜백 주체가 없다. "이 Job 을 지금 종결하지 않는다"는 뜻이고, 회수는 {@code JobSweeper} 가 한다
 * ({@code EvidenceExtractionHandler} 와 같은 관용구).
 *
 * <p>재실행이 안전한 근거 셋 — {@code transactionUniqueNo} 가 계좌 내에서 안정적이라
 * {@code ON CONFLICT} 가 다시 받은 구간을 흡수하고, {@code lastSyncedAt} 을 아직 안 올렸으므로
 * 시작일이 같게 계산되며(건너뛰는 구간이 없다), 잔액은 last-write-wins 다.
 */
@Component
public class AccountSyncHandler implements JobHandler {

    private static final Logger log = LoggerFactory.getLogger(AccountSyncHandler.class);

    /** 금융망은 한국시간 벽시계 날짜를 준다. 서버 시간대에 기대지 않는다. */
    private static final ZoneId KST = ZoneId.of("Asia/Seoul");

    /**
     * 구간 재조회 상한. <b>스위퍼만 믿으면 UX 가 10분 멈춘다</b>(sweep 5분 + stall 10분) —
     * 네트워크 깜빡임 하나를 4초로 줄이는 것이 목적이다.
     * <b>이보다 키우지 않는다.</b> 워커 스레드에서 자는 것이라, 더 필요하면 그건
     * {@code AWAITING_CALLBACK} 으로 넘길 신호다.
     */
    private static final List<Duration> BACKOFF = List.of(Duration.ofSeconds(1), Duration.ofSeconds(3));

    private final AccountSyncService syncService;
    private final DemandDepositClient demandDeposit;
    private final FinUserKeyService finUserKeyService;
    private final FinOpenApiProperties finProperties;

    public AccountSyncHandler(AccountSyncService syncService,
                              DemandDepositClient demandDeposit,
                              FinUserKeyService finUserKeyService,
                              FinOpenApiProperties finProperties) {
        this.syncService = syncService;
        this.demandDeposit = demandDeposit;
        this.finUserKeyService = finUserKeyService;
        this.finProperties = finProperties;
    }

    @Override
    public JobType jobType() {
        return JobType.ACCOUNT_SYNC;
    }

    @Override
    public Outcome handle(Job job) {
        Long accountId = requiredLong(job, "accountId");
        SyncContext context = syncService.begin(accountId, job.getBusinessId());

        try {
            return collect(job, accountId, context);
        } catch (FinApiException e) {
            if (e.isRetryable()) {
                // 종결하지 않고 남긴다. sync_status 는 RUNNING 그대로 — 다음 시도가 이어받는다.
                log.warn("계좌 {} 동기화 일시 실패 — 스위퍼 회수를 기다린다: {}", accountId, e.getMessage());
                return Outcome.AWAITING_CALLBACK;
            }
            syncService.markFailed(accountId);
            throw AccountService.finFailure(e, "거래 동기화에 실패했습니다.");
        } catch (RuntimeException e) {
            // JobWorker 는 Job 만 건드린다. 계좌 상태는 던지기 전에 우리가 남겨야 한다.
            syncService.markFailed(accountId);
            throw e;
        }
    }

    private Outcome collect(Job job, Long accountId, SyncContext context) {
        String userKey = finUserKeyService.resolve(requester(job));
        LocalDate today = LocalDate.now(KST);
        LocalDate from = AccountSyncPolicy.syncFrom(
                context.accountCreatedAt(), context.lastSyncedAt(), today, KST);
        List<DateWindow> windows = AccountSyncPolicy.monthlyWindows(from, today);

        log.info("계좌 {} 동기화 시작 — {} ~ {}, 구간 {}개", accountId, from, today, windows.size());

        int imported = 0;
        int attempted = 0;
        int received = 0;
        for (DateWindow window : windows) {
            List<TransactionRec> recs = fetchWithBackoff(userKey, context.accountNo(), window);
            received += recs.size();

            List<FinTransactionRow> rows = toRows(context.businessId(), accountId, recs);
            // ⚠ duplicateCount 의 기준은 수신 건수가 아니라 <b>넣으려 한</b> 건수다.
            // received 로 세면 걸러낸 1원 인증 입금이 중복으로 보고된다(실기동에서 잡았다).
            attempted += rows.size();
            imported += syncService.persistChunk(rows);

            // §4.1 이 요구한 로그다 — 한 번에 오는 건수로 조회 한계를 파악한다.
            log.info("계좌 {} 구간 {}~{} — 수신 {}건", accountId,
                    window.startYmd(), window.endYmd(), recs.size());
        }

        applyBalanceQuietly(userKey, accountId, context.accountNo());
        syncService.succeed(accountId, Instant.now());

        // TODO(P0-03/P1-01) 명세 7.3 — 동기화 완료 시 반복 패턴 재탐지와 분류 파이프라인을 건다.
        //   둘 다 미구현이라 이번 증분에서는 걸 곳이 없다.

        log.info("계좌 {} 동기화 완료 — 수신 {}건, 저장 시도 {}건, 신규 {}건",
                accountId, received, attempted, imported);
        return Outcome.completed(Map.of(
                "accountId", accountId,
                "importedCount", imported,
                "duplicateCount", attempted - imported));
    }

    /**
     * 매핑하며 저장할 수 없는 행과 1원 인증 입금을 걸러 낸다.
     *
     * <p>걸러낸 1원은 {@code importedCount}·{@code duplicateCount} 어디에도 넣지 않는다 —
     * 명세가 두 숫자만 정의한다. 대신 로그로 남겨 오탐을 사후에 알아볼 수 있게 한다.
     */
    private List<FinTransactionRow> toRows(Long businessId, Long accountId, List<TransactionRec> recs) {
        String authText = finProperties.accountAuth().authText();
        List<FinTransactionRow> rows = new ArrayList<>(recs.size());
        int authDeposits = 0;

        for (TransactionRec rec : recs) {
            FinTransactionRow row = AccountSyncPolicy.toRow(businessId, accountId, rec, KST);
            if (row == null) {
                log.warn("계좌 {} 거래 {} 를 건너뛴다 — 금액이 유효하지 않다",
                        accountId, rec.transactionUniqueNo());
                continue;
            }
            if (AccountSyncPolicy.isOneWonAuthDeposit(row, authText)) {
                authDeposits++;
                log.debug("계좌 {} 1원 인증 입금 제외 — 적요 \"{}\"", accountId, row.description());
                continue;
            }
            rows.add(row);
        }

        if (authDeposits > 0) {
            log.info("계좌 {} 1원 인증 입금 {}건 제외", accountId, authDeposits);
        }
        return rows;
    }

    /**
     * 실패한 <b>구간만</b> 다시 부른다. 동기화 전체를 처음부터 돌리지 않는다.
     *
     * @throws FinApiException 영구 실패이거나 재시도를 소진했을 때
     */
    private List<TransactionRec> fetchWithBackoff(String userKey, String accountNo, DateWindow window) {
        FinApiException last = null;
        for (int attempt = 0; attempt <= BACKOFF.size(); attempt++) {
            try {
                return demandDeposit.inquireTransactionHistory(
                        userKey, accountNo, window.startYmd(), window.endYmd());
            } catch (FinApiException e) {
                if (!e.isRetryable()) {
                    throw e;
                }
                last = e;
                if (attempt < BACKOFF.size()) {
                    sleepQuietly(BACKOFF.get(attempt));
                }
            }
        }
        throw last;
    }

    /**
     * 잔액을 못 받아도 동기화는 성공이다. 본체는 거래 수집이고 잔액은 곁다리라,
     * 여기서 실패를 치명적으로 만들면 <b>미검증 문자열 하나가 모든 동기화를 죽인다.</b>
     */
    private void applyBalanceQuietly(String userKey, Long accountId, String accountNo) {
        try {
            demandDeposit.inquireBalance(userKey, accountNo)
                    .ifPresentOrElse(
                            balance -> syncService.applyBalance(accountId, balance, Instant.now()),
                            () -> log.warn("계좌 {} 잔액이 응답에 없다", accountId));
        } catch (FinApiException e) {
            log.warn("계좌 {} 잔액 조회 실패 code={} — 동기화는 계속한다", accountId, e.finCode());
        }
    }

    /** package-private 인 것은 테스트가 재우지 않고 실패 분기만 보게 하려는 이음매다. */
    void sleepQuietly(Duration duration) {
        try {
            Thread.sleep(duration);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("동기화 재시도 대기 중 중단됐다", e);
        }
    }

    /** JSONB 역직렬화에서 정수는 {@code Integer} 로 온다. {@code params} 자체가 null 일 수 있다. */
    private Long requiredLong(Job job, String key) {
        Map<String, Object> params = job.getParams();
        Object value = params == null ? null : params.get(key);
        if (value instanceof Number number) {
            return number.longValue();
        }
        throw new BusinessException(ErrorCode.VALIDATION_FAILED,
                "작업 %d 의 params 에 %s 가 없습니다.".formatted(job.getId(), key));
    }

    /**
     * {@code userKey} 를 누구 것으로 쓸지. {@code params} 에 중복해 넣지 않고 Job 컬럼을 쓴다.
     *
     * <p>지금은 항상 실사용자다(컨트롤러·{@code verify} 두 경로뿐). <b>스케줄러(09:30·16:30)를
     * 붙이면 여기가 null 이 되므로</b> 그때 사업장 소유자 userId 를 채우는 일이 함께 필요하다 —
     * 오류 메시지에 그 사실을 적어 둔다.
     */
    private Long requester(Job job) {
        Long requestedBy = job.getRequestedBy();
        if (requestedBy == null) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED,
                    "작업 %d 에 요청자가 없어 userKey 를 정할 수 없다 — 스케줄러를 붙일 때는 "
                            .formatted(job.getId()) + "사업장 소유자 userId 를 채운다.");
        }
        return requestedBy;
    }
}
