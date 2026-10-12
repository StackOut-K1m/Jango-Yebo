package com.jangoyebo.core.account;

import com.jangoyebo.common.error.BusinessException;
import com.jangoyebo.common.error.ErrorCode;
import com.jangoyebo.common.security.FieldEncryptor;
import com.jangoyebo.core.TransactionsChanged;
import com.jangoyebo.core.transaction.FinTransactionRow;
import com.jangoyebo.core.transaction.TransactionBulkWriter;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/**
 * 동기화의 <b>짧은 트랜잭션</b>들. {@link AccountSyncHandler} 가 단계마다 부른다.
 *
 * <p><b>왜 핸들러에서 갈라냈는가</b> — 두 가지 이유가 있고 둘 다 강제다.
 *
 * <p>하나, 핸들러는 트랜잭션 밖에서 돈다({@code JobWorker} 규약). 금융망 왕복이 구간 수만큼
 * 이어지는데 그 전체를 트랜잭션으로 감싸면 Hikari 10 중 워커 몫 2~3 을 수 분간 쥐고,
 * {@code connection-timeout} 3초인 웹 티어가 굶는다.
 *
 * <p>둘, 핸들러 안에 {@code @Transactional} 메서드를 두고 자기가 부르면 <b>프록시를 안 타
 * 트랜잭션이 아예 안 열린다.</b> 클래스를 가르는 것은 취향이 아니다.
 *
 * <p>구간마다 커밋하는 것이 안전한 이유는 불변식 하나다 — <b>{@code lastSyncedAt} 은 전 구간
 * 성공 시에만 올린다.</b> 부분 커밋이 남아도 다음 실행이 시작일을 똑같이 계산해 같은 범위를
 * 다시 덮고, {@code ON CONFLICT} 가 흡수한다. 빠지는 거래가 원리적으로 없다.
 */
@Service
public class AccountSyncService {

    private final AccountRepository accountRepository;
    private final TransactionBulkWriter bulkWriter;
    private final FieldEncryptor encryptor;
    private final ApplicationEventPublisher events;

    public AccountSyncService(AccountRepository accountRepository,
                              TransactionBulkWriter bulkWriter,
                              FieldEncryptor encryptor,
                              ApplicationEventPublisher events) {
        this.accountRepository = accountRepository;
        this.bulkWriter = bulkWriter;
        this.encryptor = encryptor;
        this.events = events;
    }

    /**
     * 동기화에 필요한 값을 한 번에 읽고 {@code RUNNING} 으로 표시한다.
     *
     * <p><b>검증을 먼저 하고 그 다음에 {@code RUNNING} 을 쓴다.</b> 인증 미완료로 끝날 계좌에
     * {@code RUNNING} 을 남기면 되돌릴 사람이 없다.
     *
     * <p>워커에는 {@code SecurityContext} 가 없어 {@code BusinessGuard} 를 탈 수 없다. 대신
     * Job 이 들고 있는 {@code businessId}(enqueue 시점에 이미 검증된 값)와 대조한다.
     */
    @Transactional
    public SyncContext begin(Long accountId, Long jobBusinessId) {
        Account account = accountRepository.findById(accountId)
                .orElseThrow(() -> new BusinessException(ErrorCode.ACCOUNT_NOT_FOUND));

        if (!account.getBusinessId().equals(jobBusinessId)) {
            throw new BusinessException(ErrorCode.BUSINESS_ACCESS_DENIED);
        }
        if (!account.isConnected()) {
            // enqueue 뒤에 연동이 해제됐을 수 있다. sync_status 는 건드리지 않는다.
            throw new BusinessException(ErrorCode.ACCOUNT_NOT_VERIFIED);
        }

        account.markSyncRunning();
        return new SyncContext(account.getBusinessId(),
                encryptor.decrypt(account.getAccountNumberEncrypted()),
                account.getCreatedAt(), account.getLastSyncedAt());
    }

    /** 구간 하나치를 넣는다. 밀리초 단위로 끝나야 하므로 여기서만 트랜잭션을 연다. */
    @Transactional
    public int persistChunk(List<FinTransactionRow> rows) {
        return bulkWriter.insertFinRows(rows);
    }

    @Transactional
    public void applyBalance(Long accountId, BigDecimal balance, Instant asOf) {
        accountRepository.findById(accountId).ifPresent(account -> account.updateBalance(balance, asOf));
    }

    /**
     * 전 구간 성공. <b>예측 재계산 트리거가 여기 붙는다</b>(공통규약 2.6).
     *
     * <p>핸들러({@code AccountSyncHandler})가 아니라 여기서 발행하는 이유는 핸들러가
     * 트랜잭션 밖에서 돌기 때문이다 — 듣는 쪽이 {@code AFTER_COMMIT} 이라 거기서 발행하면
     * 이벤트가 조용히 버려진다. 잔액은 {@link #applyBalance} 가 앞서 커밋했고
     * {@code lastSyncedAt} 은 이 트랜잭션에서 커밋되므로, 커밋 직후 예측이 읽는 값이
     * 전부 확정돼 있다.
     */
    @Transactional
    public void succeed(Long accountId, Instant syncedAt) {
        accountRepository.findById(accountId).ifPresent(account -> {
            account.markSyncSucceeded(syncedAt);
            events.publishEvent(TransactionsChanged.bySync(account.getBusinessId(), accountId));
        });
    }

    /**
     * 실패를 계좌에 남긴다. {@code JobWorker} 는 Job 만 건드리므로 <b>핸들러가 예외를 던지기
     * 전에</b> 이걸 불러야 한다.
     */
    @Transactional
    public void markFailed(Long accountId) {
        accountRepository.findById(accountId).ifPresent(Account::markSyncFailed);
    }

    /**
     * 트랜잭션 밖으로 들고 나가는 값. 엔티티를 detached 로 넘기면 밖에서 지연 로딩이 터진다
     * ({@code open-in-view: false}).
     *
     * @param accountNo 복호화된 원문. 금융망 호출에 필요하다 — <b>로그에 남기지 않는다.</b>
     */
    public record SyncContext(Long businessId, String accountNo,
                              Instant accountCreatedAt, Instant lastSyncedAt) {
    }
}
