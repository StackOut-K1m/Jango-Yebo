package com.jangoyebo.core.account;

import com.jangoyebo.common.domain.FlowType;
import com.jangoyebo.common.error.BusinessException;
import com.jangoyebo.common.error.ErrorCode;
import com.jangoyebo.common.security.FieldEncryptor;
import com.jangoyebo.core.AccountExecutions;
import com.jangoyebo.core.business.Business;
import com.jangoyebo.core.business.BusinessRepository;
import com.jangoyebo.core.fin.DemandDepositClient;
import com.jangoyebo.core.fin.DemandDepositClient.TransactionRec;
import com.jangoyebo.core.fin.DemandDepositClient.TransferRec;
import com.jangoyebo.core.fin.FinApiException;
import com.jangoyebo.core.fin.FinResponseCode;
import com.jangoyebo.core.fin.FinUserKeyService;
import com.jangoyebo.core.job.JobService;
import com.jangoyebo.core.job.JobType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * {@link AccountExecutions} 구현 — 예정 거래를 금융망에 실제로 반영한다 (API 9.6).
 *
 * <p><b>클래스에 {@code @Transactional} 을 걸지 않는다.</b> {@link #execute} 와
 * {@link #transactions} 는 금융망 왕복이라 트랜잭션 안에 두면 커넥션을 수 초간 쥔다
 * ({@code AccountSyncHandler} 와 같은 판단). 짧은 읽기만 각각 트랜잭션을 연다.
 *
 * <h2>로그에 남기지 않는 것</h2>
 *
 * <p>계좌번호 원문과 {@code userKey} 는 어떤 수준에서도 찍지 않는다 — {@code userKey} 는
 * 사실상 계좌 접근권이다({@code FinOpenApiClient} 머리말). 남기는 것은 계좌 id·예정일·방향·
 * 금액·결과·금융망 코드뿐이고, 그 정도면 무엇이 왜 실패했는지 추적된다.
 */
@Service
public class AccountExecutionService implements AccountExecutions {

    private static final Logger log = LoggerFactory.getLogger(AccountExecutionService.class);

    private static final DateTimeFormatter YMD = DateTimeFormatter.BASIC_ISO_DATE;

    /** 금융망 거래구분 — 응답의 {@code 1}/{@code 2} 는 요청의 {@code M}/{@code D} 와 다른 코드계다. */
    private static final String DEPOSIT_CODE = "1";

    private final AccountRepository accounts;
    private final BusinessRepository businesses;
    private final FieldEncryptor encryptor;
    private final FinUserKeyService finUserKeyService;
    private final DemandDepositClient demandDeposit;
    private final JobService jobService;

    public AccountExecutionService(AccountRepository accounts,
                                   BusinessRepository businesses,
                                   FieldEncryptor encryptor,
                                   FinUserKeyService finUserKeyService,
                                   DemandDepositClient demandDeposit,
                                   JobService jobService) {
        this.accounts = accounts;
        this.businesses = businesses;
        this.encryptor = encryptor;
        this.finUserKeyService = finUserKeyService;
        this.demandDeposit = demandDeposit;
        this.jobService = jobService;
    }

    @Override
    @Transactional(readOnly = true)
    public List<ExecutableAccount> autoExecuteAccounts() {
        return accounts.findByAutoExecutePlannedTrueAndStatusOrderByIdAsc(Account.CONNECTED).stream()
                .map(a -> new ExecutableAccount(a.getId(), a.getBusinessId()))
                .toList();
    }

    @Override
    public List<LedgerEntry> transactions(Long accountId, LocalDate from, LocalDate to) {
        Target target = target(accountId);
        List<TransactionRec> recs = demandDeposit.inquireTransactionHistory(
                target.userKey(), target.accountNo(), YMD.format(from), YMD.format(to));

        List<LedgerEntry> entries = new ArrayList<>(recs.size());
        for (TransactionRec rec : recs) {
            LedgerEntry entry = toEntry(rec);
            if (entry != null) {
                entries.add(entry);
            }
        }
        return entries;
    }

    /**
     * 읽을 수 없는 행은 <b>버리지 않고 건너뛴다.</b>
     *
     * <p>이 목록은 「보내도 되는가」를 판정하는 근거다. 한 행이 이상하다고 예외를 던지면 그
     * 계좌가 통째로 멈추고, 반대로 조용히 0건으로 접으면 <b>이미 나간 거래를 못 보고 또
     * 보낸다.</b> 그래서 개별 행만 건너뛰고 로그를 남긴다.
     */
    private static LedgerEntry toEntry(TransactionRec rec) {
        try {
            FlowType flowType = DEPOSIT_CODE.equals(trim(rec.transactionType()))
                    ? FlowType.DEPOSIT : FlowType.WITHDRAWAL;
            return new LedgerEntry(
                    rec.transactionUniqueNo(),
                    LocalDate.parse(rec.transactionDate(), YMD),
                    flowType,
                    new BigDecimal(rec.transactionBalance().trim()),
                    rec.transactionSummary() == null ? "" : rec.transactionSummary().trim());
        } catch (RuntimeException e) {
            log.warn("금융망 거래 한 줄을 읽지 못해 건너뛴다 — 거래 {}: {}",
                    rec.transactionUniqueNo(), e.toString());
            return null;
        }
    }

    @Override
    public ExecutionResult execute(ExecutionRequest request) {
        if (request.amount() == null || request.amount().scale() > 0 || request.amount().signum() <= 0) {
            // 금융망은 금액을 문자열로 받고 소수점이 있으면 Q1001 이다. 나가기 전에 막는다.
            throw new IllegalArgumentException("실행 금액은 양의 정수 원이어야 한다: " + request.amount());
        }

        Target target;
        try {
            target = target(request.accountId());
        } catch (RuntimeException e) {
            // userKey 를 못 구했거나 계좌를 못 읽었다. 아직 아무것도 나가지 않았다.
            log.warn("계좌 {} 실행 준비 실패 — 이번에는 보내지 않는다: {}", request.accountId(), e.toString());
            return new ExecutionResult(Outcome.TRANSIENT, null, null, null, null, e.toString());
        }

        String uniqueNo = ExecutionUniqueNo.derive(target.accountNo(), request.dueDate(),
                request.flowType(), request.amount(), request.summary(), request.ordinal());
        String amountWon = request.amount().toPlainString();

        try {
            TransferRec rec = request.flowType() == FlowType.DEPOSIT
                    ? demandDeposit.deposit(target.userKey(), target.accountNo(), amountWon,
                            request.summary(), uniqueNo)
                    : demandDeposit.withdraw(target.userKey(), target.accountNo(), amountWon,
                            request.summary(), uniqueNo);

            log.info("예정 거래 반영 — 계좌 {} / {} {} / {}원 / 적요 [{}]",
                    request.accountId(), request.dueDate(), request.flowType(), amountWon,
                    request.summary());
            return new ExecutionResult(Outcome.EXECUTED, uniqueNo,
                    rec == null ? null : rec.transactionUniqueNo(),
                    finDate(rec), FinResponseCode.SUCCESS, null);

        } catch (FinApiException e) {
            Outcome outcome = outcomeOf(e);
            log.warn("예정 거래 반영 실패 — 계좌 {} / {} {} / {}원 / {} ({})",
                    request.accountId(), request.dueDate(), request.flowType(), amountWon,
                    outcome, e.finCode());
            return new ExecutionResult(outcome, uniqueNo, null, null, e.finCode(), e.getMessage());
        }
    }

    /**
     * 금융망 코드를 실행 결과로 옮긴다 (API 9.6 표).
     *
     * <p>⚠ <b>{@code FinApiException#isRetryable} 을 그대로 쓰지 않는다.</b> 그 판정은
     * {@code H1007} 을 「새 번호로 다시 부르면 된다」로 읽는데, 결정적 채번에서는 정반대다 —
     * 중복은 <b>이미 나갔다</b>는 뜻이고 새 번호로 다시 부르면 돈이 두 번 나간다.
     */
    private static Outcome outcomeOf(FinApiException e) {
        String code = e.finCode();
        if (code == null) {
            // 금융망이 답을 준 적이 없다(전송 오류·타임아웃). 나갔는지 모른다.
            return Outcome.TRANSIENT;
        }
        if (FinResponseCode.DUPLICATE_TRANSACTION_NO.equals(code)) {
            return Outcome.DUPLICATE_UNIQUE_NO;
        }
        if (FinResponseCode.INSUFFICIENT_BALANCE.equals(code)) {
            return Outcome.INSUFFICIENT_BALANCE;
        }
        if (FinResponseCode.TRANSMISSION_DATE_INVALID.equals(code)
                || FinResponseCode.CLOCK_SKEW.equals(code)) {
            // 우리 시계 문제다. 요청이 처리되기 전에 거절됐으므로 돈은 움직이지 않았고,
            // 시각을 맞추면 같은 요청이 그대로 통한다.
            return Outcome.TRANSIENT;
        }
        if (e.status() != null && e.status().is5xxServerError()) {
            return Outcome.TRANSIENT;
        }
        return Outcome.REJECTED;
    }

    private static LocalDate finDate(TransferRec rec) {
        if (rec == null || rec.transactionDate() == null || rec.transactionDate().isBlank()) {
            return null;
        }
        try {
            return LocalDate.parse(rec.transactionDate().trim(), YMD);
        } catch (RuntimeException e) {
            return null;
        }
    }

    /**
     * 동기화를 접수한다.
     *
     * <p><b>{@code requestedBy} 를 사업장 소유자로 채운다.</b> 동기화 핸들러가 그 사용자의
     * {@code userKey} 로 금융망을 부르므로 비워 두면 거기서 던진다
     * ({@code AccountSyncHandler#requester} 의 오류 메시지가 이 자리를 가리킨다).
     *
     * <p>이미 도는 동기화가 있으면 새로 넣지 않는다 — 한 계좌에서 여러 건을 반영해도
     * 가져올 것은 같고, 같은 구간을 두 번 읽어 봐야 {@code ON CONFLICT} 가 흡수할 뿐이다.
     */
    @Override
    @Transactional
    public void requestSync(Long accountId) {
        Account account = accounts.findById(accountId)
                .orElseThrow(() -> new BusinessException(ErrorCode.ACCOUNT_NOT_FOUND));
        Long ownerUserId = owner(account.getBusinessId());

        jobService.enqueueIfIdle(account.getBusinessId(), ownerUserId, JobType.ACCOUNT_SYNC,
                        Map.of("accountId", accountId))
                .ifPresentOrElse(
                        job -> log.info("반영분 동기화 접수 — 계좌 {} / jobId {}", accountId, job.getId()),
                        () -> log.debug("동기화가 이미 돌고 있다 — 계좌 {}", accountId));
    }

    /** 금융망을 부르는 데 필요한 것. <b>둘 다 로그에 남기지 않는다.</b> */
    private record Target(String accountNo, String userKey) {
    }

    /**
     * 계좌번호 원문과 {@code userKey} 를 함께 푼다.
     *
     * <p>{@code CONNECTED} 를 다시 확인하는 이유는 <b>목록을 만든 뒤 상태가 바뀔 수 있기</b>
     * 때문이다 — 사용자가 그사이 연동을 해제했으면 보내면 안 된다.
     */
    private Target target(Long accountId) {
        Account account = accounts.findById(accountId)
                .orElseThrow(() -> new BusinessException(ErrorCode.ACCOUNT_NOT_FOUND));
        if (!account.isConnected()) {
            throw new BusinessException(ErrorCode.ACCOUNT_NOT_VERIFIED);
        }
        return new Target(encryptor.decrypt(account.getAccountNumberEncrypted()),
                finUserKeyService.resolve(owner(account.getBusinessId())));
    }

    private Long owner(Long businessId) {
        return businesses.findById(businessId)
                .map(Business::getOwnerUserId)
                .orElseThrow(() -> new BusinessException(ErrorCode.BUSINESS_NOT_FOUND));
    }

    private static String trim(String value) {
        return value == null ? "" : value.trim();
    }
}
