package com.jangoyebo.core.account;

import com.jangoyebo.common.error.BusinessException;
import com.jangoyebo.common.error.ErrorCode;
import com.jangoyebo.common.security.FieldEncryptor;
import com.jangoyebo.common.tenancy.BusinessGuard;
import com.jangoyebo.core.TransactionsChanged;
import com.jangoyebo.core.fin.AccountAuthClient;
import com.jangoyebo.core.fin.AccountAuthClient.AuthCheck;
import com.jangoyebo.core.fin.FinApiException;
import com.jangoyebo.core.fin.FinOpenApiProperties;
import com.jangoyebo.core.fin.FinResponseCode;
import com.jangoyebo.core.fin.FinUserKeyService;
import com.jangoyebo.core.job.Job;
import com.jangoyebo.core.job.JobService;
import com.jangoyebo.core.job.JobType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * 계좌 연결과 1원 인증 (API 7.2).
 *
 * <p>컨트롤러에서 갈라낸 이유가 둘이다. <b>하나는 강제된다</b> — 뒤이어 붙을
 * {@code ACCOUNT_SYNC} {@code JobHandler} 는 워커 스레드에서 돌아 {@code SecurityContext} 가
 * 없고, 그래서 {@link BusinessGuard} 를 탈 수 없다. 컨트롤러와 핸들러가 같은 로직을 쓰려면
 * 권한 확인이 로직 바깥에 있어야 한다. 다른 하나는 상태머신이 컨트롤러에 있을 물건이 아니라는 것.
 */
@Service
public class AccountService {

    private static final Logger log = LoggerFactory.getLogger(AccountService.class);

    private final AccountRepository accountRepository;
    private final AccountVerificationStore verificationStore;
    private final FinUserKeyService finUserKeyService;
    private final AccountAuthClient accountAuthClient;
    private final FinOpenApiProperties finProperties;
    private final FieldEncryptor encryptor;
    private final BusinessGuard guard;
    private final JobService jobService;
    private final ApplicationEventPublisher events;

    public AccountService(AccountRepository accountRepository,
                          AccountVerificationStore verificationStore,
                          FinUserKeyService finUserKeyService,
                          AccountAuthClient accountAuthClient,
                          FinOpenApiProperties finProperties,
                          FieldEncryptor encryptor,
                          BusinessGuard guard,
                          JobService jobService,
                          ApplicationEventPublisher events) {
        this.accountRepository = accountRepository;
        this.verificationStore = verificationStore;
        this.finUserKeyService = finUserKeyService;
        this.accountAuthClient = accountAuthClient;
        this.finProperties = finProperties;
        this.encryptor = encryptor;
        this.guard = guard;
        this.jobService = jobService;
        this.events = events;
    }

    @Transactional(readOnly = true)
    public List<Account> list(Long businessId) {
        return accountRepository.findByBusinessIdOrderByIdAsc(businessId).stream()
                .peek(this::expireIfIssueRecordGone)
                .toList();
    }

    /**
     * 계좌를 연결하고 1원을 보낸다. 계좌는 {@code PENDING_VERIFICATION} 으로 생성된다.
     *
     * <p><b>금융망 호출이 실패하면 계좌도 남기지 않는다.</b> 예외가 트랜잭션을 되돌리므로
     * 인증을 보낼 수 없는 계좌가 목록에 유령으로 남지 않는다 — 사용자는 그냥 다시 누르면 된다.
     * 되살리기도 같다: 롤백되면 해제 상태로 되돌아간다.
     *
     * <p>같은 계좌번호의 행이 이미 있으면 {@link #linkExisting} 이 세 갈래로 가른다.
     */
    @Transactional
    public Account link(Long businessId, String bankCode, String rawAccountNo,
                        String alias, boolean primary) {
        String normalized = digitsOf(rawAccountNo);
        String hash = sha256(normalized);
        String encrypted = encryptor.encrypt(normalized);
        String masked = mask(rawAccountNo);

        Account account = accountRepository.findByAccountNumberHash(hash)
                .map(existing -> linkExisting(existing, businessId, bankCode, encrypted,
                        masked, alias, primary))
                .orElseGet(() -> accountRepository.save(Account.link(businessId, bankCode,
                        encrypted, hash, masked, alias, primary)));

        sendOneWon(account, normalized);
        return account;
    }

    /**
     * 같은 계좌번호의 행이 이미 있을 때 무엇을 할지 가른다.
     *
     * <table>
     *   <tr><th>행의 정체</th><th>결과</th></tr>
     *   <tr><td>내 사업장의 해제·만료된 행</td>
     *       <td>되살린다 — 새 입력으로 덮어쓰고 1원 재발송</td></tr>
     *   <tr><td>내 사업장의 살아 있는 행 · 내 다른 사업장</td>
     *       <td>{@code 409 ACCOUNT_ALREADY_LINKED}</td></tr>
     *   <tr><td>남의 계좌</td><td>{@code 400 ACCOUNT_NO_INVALID}</td></tr>
     * </table>
     *
     * <p><b>행을 지우고 새로 만들지 않는 이유</b> — {@code account_id} 에 거래 이력이 매달려 있다.
     * {@code uk_account_number} 도 그대로 지킬 수 있어 마이그레이션이 없다. 유니크 인덱스를 부분
     * 인덱스로 바꾸는 길도 있었지만 같은 계좌의 거래가 두 행으로 쪼개져서 택하지 않았다.
     *
     * <p>소유권은 그대로 지킨다. 계좌번호 해시가 <b>전역</b> 유일이라 남이 먼저 등록한 계좌일 수
     * 있고, 그 행을 되살리면 남의 계좌를 빼앗는 셈이 된다.
     */
    private Account linkExisting(Account existing, Long businessId, String bankCode,
                                 String encrypted, String masked, String alias, boolean primary) {
        if (existing.getBusinessId().equals(businessId)) {
            // 내 사업장이다 — @CurrentBusiness 가 주입 시점에 소유를 검증했으므로 다시 묻지 않는다.
            if (!existing.isRelinkable()) {
                throw new BusinessException(ErrorCode.ACCOUNT_ALREADY_LINKED);
            }
            existing.relink(bankCode, encrypted, masked, alias, primary);
            return existing;
        }
        // 다른 사업장의 행이다. 같은 사용자의 다른 사업장이면 중복 연결이고 명세 7.2 가 409 로
        // 정해 두고 있다. 남의 계좌까지 409 를 주면 "누군가 이 계좌를 등록해 두었다"가 새어 나가므로
        // ACCOUNT_NO_INVALID 로 모은다 — finFailure() 가 금융망 A1003 을 뭉개는 것과 같은 판단이다.
        throw guard.ownsBusiness(existing.getBusinessId())
                ? new BusinessException(ErrorCode.ACCOUNT_ALREADY_LINKED)
                : new BusinessException(ErrorCode.ACCOUNT_NO_INVALID);
    }

    /**
     * 사용자가 통장에서 읽은 4자리를 대조한다.
     *
     * <p>코드는 우리가 갖고 있지 않다 — 금융망이 만들어 적요에 찍었고 금융망이 대조한다.
     * 우리가 아는 것은 <b>언제 보냈는지</b>뿐이고 그게 Redis 에 있다.
     */
    @Transactional
    public Account verify(Long accountId, String authCode) {
        Account account = owned(accountId);

        if (account.isConnected()) {
            // 이미 끝난 계좌를 또 인증하려 든다. 금융망을 부를 이유가 없다.
            return account;
        }
        if (!verificationStore.isPending(accountId)) {
            account.markVerificationExpired();
            throw new BusinessException(ErrorCode.ACCOUNT_AUTH_EXPIRED);
        }

        AuthCheck check;
        try {
            check = accountAuthClient.check(userKey(), decrypted(account), authCode);
        } catch (FinApiException e) {
            throw finFailure(e, "1원 인증 검증에 실패했습니다.");
        }

        if (check == AuthCheck.EXPIRED) {
            // 금융망 쪽 시간이 우리보다 먼저 끝났다. 우리 기록도 지워 재발송만이 길이 되게 한다 —
            // 남겨 두면 사용자가 죽은 코드로 금융망을 계속 두드린다.
            account.markVerificationExpired();
            verificationStore.clear(accountId);
            throw new BusinessException(ErrorCode.ACCOUNT_AUTH_EXPIRED);
        }
        if (check == AuthCheck.MISMATCHED) {
            // 틀려도 인증은 살아 있다(실측). 발급 기록을 지우지 않아야 다시 입력할 수 있다.
            throw new BusinessException(ErrorCode.ACCOUNT_AUTH_CODE_MISMATCH);
        }

        account.markConnected(Instant.now());

        // 명세 7.2 — 인증 성공 시 잔액 조회와 최초 동기화가 자동 실행된다.
        // 큐에만 넣는다: verify 응답이 금융망 왕복만큼 느려지지 않고, 동기화가 실패해도 인증은 성공이다.
        Job sync = enqueueSync(account);

        // ⚠ Redis 는 트랜잭션 롤백에 딸려오지 않는다. 비트랜잭션 부작용을 맨 뒤로 민다 —
        // 앞에 두면 enqueue 실패로 롤백됐을 때 계좌는 PENDING 으로 돌아가는데 발급 기록만
        // 사라져, 다음 시도가 재발송 없이는 못 푸는 ACCOUNT_AUTH_EXPIRED 가 된다.
        verificationStore.clear(accountId);

        log.info("계좌 {} 1원 인증 완료 — 동기화 작업 {} 접수", accountId, sync.getId());
        return account;
    }

    /** 재발송. 유효시간도 함께 다시 시작한다 — 안 그러면 재발송이 의미가 없다. */
    @Transactional
    public Account resendVerification(Long accountId) {
        Account account = owned(accountId);

        if (account.isConnected()) {
            throw new BusinessException(ErrorCode.ACCOUNT_ALREADY_LINKED,
                    "이미 인증이 완료된 계좌입니다.");
        }
        if (!account.isAwaitingVerification()) {
            // 해제된 계좌다. 되살리기는 link() 가 맡는다 — 여기서 1원을 보내면 사용자가 새로 넣은
            // 은행·별칭을 반영하지 않은 채 되살아나고, 되살리는 문이 둘이 된다.
            throw new BusinessException(ErrorCode.ACCOUNT_STATE_CONFLICT,
                    "해제된 계좌입니다. 계좌를 다시 연결해 주세요.");
        }
        if (verificationStore.issueCount(accountId) >= finProperties.accountAuth().maxResend()) {
            // 상한을 넘겼다. 기록을 지워 다음 시도가 처음부터 시작하게 두지 않는다 —
            // TTL 이 지나야 풀린다. 무한 재발송으로 금융망을 두드리는 것을 막는 지점이다.
            throw new BusinessException(ErrorCode.ACCOUNT_AUTH_EXPIRED,
                    "재발송 횟수를 초과했습니다. 잠시 후 다시 시도해 주세요.");
        }

        account.markPendingVerification();
        sendOneWon(account, decrypted(account));
        return account;
    }

    /**
     * 사용자가 직접 누른 동기화 (API 7.3).
     *
     * <p>인증 완료 계좌만 받는다 — 인증 전 계좌는 동기화·예측 대상에서 제외된다(API 7.2).
     *
     * @return 접수한 Job 과 <b>지금까지의</b> 마지막 성공 시각(명세 7.3 응답 필드)
     */
    @Transactional
    public SyncAccepted requestSync(Long accountId) {
        Account account = owned(accountId);
        if (!account.isConnected()) {
            throw new BusinessException(ErrorCode.ACCOUNT_NOT_VERIFIED);
        }
        return new SyncAccepted(enqueueSync(account), account.getLastSyncedAt());
    }

    /**
     * {@code requestedBy} 를 Job 컬럼에 넣는다. 핸들러가 그 사용자의 {@code userKey} 로 금융망을
     * 부른다 — {@code params} 에 중복해 넣지 않는다.
     */
    private Job enqueueSync(Account account) {
        return jobService.enqueue(account.getBusinessId(), guard.currentUserId(),
                JobType.ACCOUNT_SYNC, Map.of("accountId", account.getId()));
    }

    /**
     * 연동 해제. 거래는 보존하고 동기화 대상에서만 뺀다(API 7.2).
     *
     * <p><b>재계산을 걸어야 한다.</b> 해제는 거래 행을 하나도 건드리지 않지만 예측이 세는
     * 집합을 바꾼다 — 시작 잔액과 패턴 탐지가 모두 {@code CONNECTED} 계좌만 보기 때문이다.
     * 알리지 않으면 저장된 곡선이 <b>해제한 계좌의 잔액이 들어간 총잔액</b>을 04:00 배치까지
     * 계속 내려준다. 잔액 컬럼을 비우는 것만으로는 그 구멍이 막히지 않는다 — 곡선은
     * {@code FORECAST_RUN} 에 값으로 굳어 있다.
     *
     * <p>연결되는 쪽은 따로 알리지 않는다. {@code /verify} 가 동기화를 접수하고 그것이
     * 성공하면 {@code TransactionsChanged.bySync} 가 같은 일을 한다.
     */
    @Transactional
    public void disconnect(Long accountId) {
        Account account = owned(accountId);
        account.disconnect();
        verificationStore.clear(accountId);
        // 커밋되는 트랜잭션 안에서 발행한다 — 듣는 쪽이 AFTER_COMMIT 이라 밖에서 쏘면 버려진다.
        events.publishEvent(TransactionsChanged.byDisconnect(account.getBusinessId(), accountId));
    }

    /**
     * 예정 거래 자동 반영을 켜고 끈다 (API 9.6).
     *
     * <p><b>계좌 단위 명시 opt-in 이 이 기능의 유일한 안전장치다.</b> 켜는 순간 서버가 예정일마다
     * 이 계좌에서 진짜로 출금·입금한다 — 그래서 화면에 노출하지 않고, 기본값은 꺼짐이며,
     * 인증이 끝난 계좌에서만 켤 수 있다({@code Account#enableAutoExecution}).
     */
    @Transactional
    public Account setAutoExecution(Long accountId, boolean enabled) {
        Account account = owned(accountId);
        if (enabled) {
            account.enableAutoExecution();
        } else {
            account.disableAutoExecution();
        }
        log.info("계좌 {} 예정 거래 자동 반영 {}", accountId, enabled ? "켬" : "끔");
        return account;
    }

    private void sendOneWon(Account account, String plainAccountNo) {
        try {
            accountAuthClient.sendOneWon(userKey(), plainAccountNo);
        } catch (FinApiException e) {
            throw finFailure(e, "1원 인증 송금에 실패했습니다.");
        }
        long issued = verificationStore.recordIssue(account.getId(), finProperties.accountAuth().ttl());
        log.info("계좌 {} 1원 인증 발송 {}회차", account.getId(), issued);
    }

    /**
     * 발급 기록이 사라졌는데 아직 대기 중이면 만료로 옮긴다.
     *
     * <p>조회 시점에 판정하는 것은 <b>만료 배치를 두지 않기 위해서다.</b> Redis TTL 이 이미
     * 시간을 재고 있으므로 같은 일을 두 군데서 하지 않는다.
     */
    private void expireIfIssueRecordGone(Account account) {
        if (account.isAwaitingVerification() && !verificationStore.isPending(account.getId())) {
            account.markVerificationExpired();
        }
    }

    private Account owned(Long accountId) {
        Account account = accountRepository.findById(accountId)
                .orElseThrow(() -> new BusinessException(ErrorCode.ACCOUNT_NOT_FOUND));
        // 직접 경로(/accounts/{id})는 자동 검증이 걸리지 않는다. 여기서 명시적으로 막는다.
        guard.assertOwned(account);
        return account;
    }

    private String userKey() {
        return finUserKeyService.resolve(guard.currentUserId());
    }

    private String decrypted(Account account) {
        return encryptor.decrypt(account.getAccountNumberEncrypted());
    }

    /**
     * 금융망 실패를 우리 오류로 옮긴다.
     *
     * <p>기본은 {@code 502} 지만 <b>사용자 입력 문제는 502 가 아니다.</b> 계좌번호를 잘못 넣은
     * 사람에게 "금융망 연동에 실패했습니다"를 보여주면 자기가 고칠 수 있는 것을 서버 장애로
     * 오해한다(S15P21C105-111 과 같은 종류의 어긋남).
     */
    static BusinessException finFailure(FinApiException e, String message) {
        if (FinResponseCode.ACCOUNT_NO_INVALID.equals(e.finCode())) {
            // 없는 계좌·형식 오류·남의 계좌가 모두 A1003 이다. 구분하지 않는 것이 맞다 —
            // "있지만 당신 것이 아니다"를 알려주면 계좌 존재 여부가 새어 나간다.
            return new BusinessException(ErrorCode.ACCOUNT_NO_INVALID);
        }
        return new BusinessException(ErrorCode.FIN_API_FAILED, message,
                e.finCode() == null ? null : Map.of("finCode", e.finCode()));
    }

    /** 명세 7.3 의 {@code 202} 응답 재료. */
    public record SyncAccepted(Job job, Instant lastSyncedAt) {
    }

    /** {@link AccountNumbers} 로 옮겼다 — 대출 동기화가 같은 규칙으로 계좌를 찾아야 한다. */
    private static String sha256(String value) {
        return AccountNumbers.sha256(value);
    }

    /** {@link AccountNumbers#digitsOf} 로 옮겼다. 구분자는 {@link #mask} 의 표시에만 쓴다. */
    private static String digitsOf(String rawAccountNo) {
        return AccountNumbers.digitsOf(rawAccountNo);
    }

    /**
     * 계좌번호 마스킹. 입력의 구분자를 보존하고 앞 3자리·뒤 3자리만 남긴다
     * — API 7.2 예시({@code 110-***-***789})와 같은 형태다.
     * 응답에는 항상 마스킹된 값만 나간다(API 21장).
     *
     * <p><b>구분자를 보존하므로 결과 길이는 자릿수가 아니라 입력 길이다.</b>
     * {@code account_number_masked} 를 그 기준으로 잡아야 한다(V11).
     *
     * <p>숫자 판정은 {@link #digitsOf} 와 <b>같은 기준</b>이어야 한다. {@code Character.isDigit}
     * 는 전각 {@code ０-９} 를 숫자로 보는데 정규화는 {@code [^0-9]} 로 지운다 — 갈리면 화면에는
     * 숫자로 보이는 자리가 실제 저장된 번호에는 없다. 입력은 {@code LinkRequest} 가 먼저 막지만
     * 이 메서드만 따로 불리는 자리가 생겨도 어긋나지 않게 여기서도 같은 기준을 쓴다.
     */
    static String mask(String rawAccountNo) {
        StringBuilder masked = new StringBuilder();
        int digitCount = digitsOf(rawAccountNo).length();
        int seen = 0;

        for (char c : rawAccountNo.toCharArray()) {
            if (!isDigit(c)) {
                masked.append(c);
                continue;
            }
            seen++;
            boolean visible = seen <= 3 || seen > digitCount - 3;
            masked.append(visible ? c : '*');
        }
        return masked.toString();
    }

    private static boolean isDigit(char c) {
        return c >= '0' && c <= '9';
    }
}
