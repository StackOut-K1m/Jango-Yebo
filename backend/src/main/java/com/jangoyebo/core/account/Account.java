package com.jangoyebo.core.account;

import com.jangoyebo.common.entity.BaseEntity;
import com.jangoyebo.common.error.BusinessException;
import com.jangoyebo.common.error.ErrorCode;
import com.jangoyebo.common.tenancy.BusinessScoped;
import jakarta.persistence.*;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * 사업장 계좌 (ERD {@code ACCOUNT}).
 *
 * <p>상태 전이는 <b>여기서만</b> 일어난다. 서비스가 {@code status} 문자열을 직접 넣지 못하게
 * 막아 두는 것이 목적이다 — DB {@code ck_account_status} 가 최후 방어선이지만, 거기서 걸리면
 * 이미 트랜잭션 끝이라 원인을 찾기 어렵다.
 *
 * <pre>
 * PENDING_VERIFICATION ──/verify 성공──→ CONNECTED
 *          │  ▲                              │
 *   유효시간 만료│  └──/verification 재발송──┐      │ DELETE
 *          ▼                            │      ▼
 * VERIFICATION_EXPIRED ──────────────────┘   DISCONNECTED
 *
 * 재연결(POST /businesses/{id}/accounts) — VERIFICATION_EXPIRED · DISCONNECTED
 *   ──→ PENDING_VERIFICATION ({@link #relink})
 * </pre>
 *
 * <p>인증 완료 전 계좌는 동기화·예측 대상에서 제외된다(API 7.2).
 *
 * <p>{@code syncStatus} 는 위와 <b>독립된 축</b>이다. {@code CONNECTED} 가 된 뒤에야 움직인다.
 *
 * <pre>
 * NEVER ──동기화 시작──→ RUNNING ──전 구간 성공──→ SUCCESS
 *                          └──────실패──────→ FAILED   (lastSyncedAt 은 유지)
 * </pre>
 */
@Entity
@Table(name = "account", schema = "core")
public class Account extends BaseEntity implements BusinessScoped {

    static final String PENDING_VERIFICATION = "PENDING_VERIFICATION";
    static final String CONNECTED = "CONNECTED";
    static final String VERIFICATION_EXPIRED = "VERIFICATION_EXPIRED";
    static final String DISCONNECTED = "DISCONNECTED";

    static final String SYNC_NEVER = "NEVER";
    static final String SYNC_RUNNING = "RUNNING";
    static final String SYNC_SUCCESS = "SUCCESS";
    static final String SYNC_FAILED = "FAILED";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "business_id", nullable = false, updatable = false)
    private Long businessId;

    @Column(nullable = false, length = 20)
    private String provider;

    @Column(name = "bank_code", nullable = false, length = 10)
    private String bankCode;

    /** 금융망 호출에 원문이 필요하므로 암호화해 보관한다. */
    @Column(name = "account_number_encrypted", nullable = false, length = 512)
    private String accountNumberEncrypted;

    /** 암호문은 매번 달라져 비교할 수 없으므로 중복 판정용 해시를 따로 둔다. */
    @Column(name = "account_number_hash", nullable = false, unique = true, length = 128)
    private String accountNumberHash;

    /** 마스킹은 사용자가 친 구분자를 보존하므로 길이 기준은 자릿수가 아니라 입력 길이다(V11). */
    @Column(name = "account_number_masked", nullable = false, length = 64)
    private String accountNumberMasked;

    @Column(nullable = false, length = 100)
    private String name;

    @Column(name = "is_primary", nullable = false)
    private boolean primaryAccount;

    @Column(nullable = false, length = 30)
    private String status;

    @Column(name = "verified_at")
    private Instant verifiedAt;

    @Column(name = "current_balance")
    private BigDecimal currentBalance;

    @Column(name = "balance_as_of")
    private Instant balanceAsOf;

    @Column(name = "last_synced_at")
    private Instant lastSyncedAt;

    @Column(name = "sync_status", nullable = false, length = 20)
    private String syncStatus;

    /**
     * 예정 거래를 금융망에 실제로 반영할지 (API 9.6).
     *
     * <p><b>기본은 꺼짐이고 화면에 노출하지 않는다.</b> 켜면 서버가 이 계좌에서 진짜로 돈을
     * 빼고 넣는다 — 실사용자 계좌에 걸면 자동 실행 금지 원칙(공통규약 23장)을 어긴다.
     * 켜는 주체는 시드 도구와 계좌 소유자뿐이고, 대상은 우리가 만든 싸피은행 시연 계좌다.
     */
    @Column(name = "auto_execute_planned", nullable = false)
    private boolean autoExecutePlanned;

    protected Account() {
    }

    private Account(Long businessId, String bankCode, String encrypted, String hash,
                    String masked, String name, boolean primaryAccount) {
        this.businessId = businessId;
        this.provider = "SSAFY_FIN";
        this.bankCode = bankCode;
        this.accountNumberEncrypted = encrypted;
        this.accountNumberHash = hash;
        this.accountNumberMasked = masked;
        this.name = name;
        this.primaryAccount = primaryAccount;
        this.status = PENDING_VERIFICATION;
        this.syncStatus = SYNC_NEVER;
    }

    public static Account link(Long businessId, String bankCode, String encrypted, String hash,
                               String masked, String name, boolean primaryAccount) {
        return new Account(businessId, bankCode, encrypted, hash, masked, name, primaryAccount);
    }

    /** 1원 인증 성공. 여기서부터 동기화·예측 대상이 된다. */
    void markConnected(Instant at) {
        this.status = CONNECTED;
        this.verifiedAt = at;
    }

    /**
     * 유효시간이 지났다. 재발송하면 {@link #markPendingVerification()} 으로 되돌아온다.
     *
     * <p>배치가 부르지 않는다 — 발급 기록(Redis)이 사라진 것을 조회 시점에 확인하고 옮긴다.
     */
    void markVerificationExpired() {
        this.status = VERIFICATION_EXPIRED;
    }

    /** 재발송. 만료됐던 계좌도 다시 대기 상태로 돌아온다(API 7.2). */
    void markPendingVerification() {
        this.status = PENDING_VERIFICATION;
        this.verifiedAt = null;
    }

    /**
     * 연동 해제. 이미 수집된 거래는 보존하고 이후 동기화 대상에서만 뺀다(API 7.2) —
     * 그래서 행을 지우지 않는다.
     *
     * <p><b>잔액은 지운다.</b> {@link #relink} 와 같은 이유다 — 시점 값이라 지금도 맞다고 말할
     * 수 없다. 게다가 해제한 뒤로는 동기화가 돌지 않아 이 값만 영원히 낡는다. 남겨 두면
     * 화면이 그것을 합계에 더해 <b>연동을 끊은 계좌의 돈이 총잔액에 계속 섞인다.</b>
     *
     * <p>정보가 사라지지는 않는다. 마지막 잔액은 거래의 {@code balance_after} 에 남아 있고,
     * 재연결하면 {@code AccountBalancesService} 가 그것으로 시작 잔액을 메운다.
     */
    void disconnect() {
        this.status = DISCONNECTED;
        this.currentBalance = null;
        this.balanceAsOf = null;
        // 해제한 계좌에 자동 반영이 남아 있으면 안 된다. 다시 연결할 때 명시적으로 켠다.
        this.autoExecutePlanned = false;
    }

    /**
     * 해제·만료된 계좌를 다시 연결한다. <b>행을 그대로 되살린다</b> — {@code account_id} 에
     * 매달린 거래 이력이 이어지고, {@code uk_account_number} 를 건드리지 않아 마이그레이션도 없다.
     *
     * <p><b>새로 입력한 값이 덮어쓴다.</b> 사용자가 은행·별칭을 다시 채워 보냈으므로 그것이
     * 최신 의사다 — 옛 값을 남기면 방금 입력한 이름이 조용히 버려진다. 계좌번호 원문도 다시 넣는다:
     * 해시가 같아 같은 번호임은 확실하지만 마스킹은 사용자가 넣은 구분자에 따라 달라진다.
     *
     * <p>{@code lastSyncedAt}·{@code syncStatus} 는 <b>남긴다.</b> 거래가 그대로 있으니
     * "언제까지는 맞는 데이터인가"가 여전히 참이다({@link #markSyncFailed()} 와 같은 이유).
     * 반면 잔액은 지운다 — 시점 값이라 지금도 맞다고 말할 수 없고, 인증이 끝나면 자동 동기화가
     * 다시 채운다.
     */
    void relink(String bankCode, String encrypted, String masked,
                String name, boolean primaryAccount) {
        this.bankCode = bankCode;
        this.accountNumberEncrypted = encrypted;
        this.accountNumberMasked = masked;
        this.name = name;
        this.primaryAccount = primaryAccount;
        this.currentBalance = null;
        this.balanceAsOf = null;
        // 되살린 계좌도 자동 반영은 꺼진 채로 시작한다 — 켜는 것은 언제나 명시적 행위다.
        this.autoExecutePlanned = false;
        markPendingVerification();
    }

    /**
     * 자동 반영을 켠다 (API 9.6).
     *
     * <p><b>인증이 끝난 계좌에서만</b> 켤 수 있다. 인증 전 계좌는 우리가 소유를 확인하지 못한
     * 계좌라 거기에 돈을 넣고 빼면 안 된다 — 동기화·예측 대상에서 빼는 것과 같은 기준이다.
     */
    void enableAutoExecution() {
        if (!isConnected()) {
            throw new BusinessException(ErrorCode.ACCOUNT_NOT_VERIFIED);
        }
        this.autoExecutePlanned = true;
    }

    void disableAutoExecution() {
        this.autoExecutePlanned = false;
    }

    /** 동기화 시작. <b>인증 완료 검증을 통과한 뒤에만</b> 부른다 — 되돌릴 사람이 없다. */
    void markSyncRunning() {
        this.syncStatus = SYNC_RUNNING;
    }

    /** 전 구간 성공. {@code lastSyncedAt} 은 <b>여기서만</b> 올라간다. */
    void markSyncSucceeded(Instant at) {
        this.syncStatus = SYNC_SUCCESS;
        this.lastSyncedAt = at;
    }

    /**
     * 동기화 실패.
     *
     * <p><b>{@code lastSyncedAt} 을 건드리지 않는다.</b> 명세 7.3 이 실패 상태와 함께
     * <b>마지막 성공 시각</b>을 화면에 그대로 표시하라고 요구한다 — 사용자가 "언제까지는
     * 맞는 데이터인가"를 알아야 한다.
     */
    void markSyncFailed() {
        this.syncStatus = SYNC_FAILED;
    }

    /** 잔액은 last-write-wins 다. 구조적으로 멱등이라 재실행이 안전하다. */
    void updateBalance(BigDecimal balance, Instant asOf) {
        this.currentBalance = balance;
        this.balanceAsOf = asOf;
    }

    public boolean isConnected() {
        return CONNECTED.equals(status);
    }

    public boolean isAwaitingVerification() {
        return PENDING_VERIFICATION.equals(status) || VERIFICATION_EXPIRED.equals(status);
    }

    /**
     * 되살릴 수 있는가. 해제됐거나 인증 시간이 지난 계좌다 — 둘 다 <b>지금은 쓰이지 않는</b> 행이라
     * 되살려도 살아 있는 연결을 빼앗지 않는다. {@code PENDING_VERIFICATION} 은 여기 없다:
     * 1원이 이미 나가 있어 재발송({@code /verification})이 맡을 몫이고, 그쪽에만 재발송 상한이 있다.
     */
    boolean isRelinkable() {
        return DISCONNECTED.equals(status) || VERIFICATION_EXPIRED.equals(status);
    }

    public Long getId() { return id; }
    /** 금융망 호출에 원문이 필요하다. 복호화는 호출부가 한다. */
    public String getAccountNumberEncrypted() { return accountNumberEncrypted; }
    @Override public Long getBusinessId() { return businessId; }
    public String getBankCode() { return bankCode; }
    public String getAccountNumberMasked() { return accountNumberMasked; }
    public String getName() { return name; }
    public boolean isPrimaryAccount() { return primaryAccount; }
    public String getStatus() { return status; }
    public Instant getVerifiedAt() { return verifiedAt; }
    public BigDecimal getCurrentBalance() { return currentBalance; }
    public Instant getBalanceAsOf() { return balanceAsOf; }
    public Instant getLastSyncedAt() { return lastSyncedAt; }
    public String getSyncStatus() { return syncStatus; }
    public boolean isAutoExecutePlanned() { return autoExecutePlanned; }
}
