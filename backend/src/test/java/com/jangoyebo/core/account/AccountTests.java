package com.jangoyebo.core.account;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 계좌 상태 전이 검증. Spring 도 Docker 도 쓰지 않는다.
 *
 * <p>DB {@code ck_account_status} 가 최후 방어선이지만 거기서 걸리면 트랜잭션 끝이라
 * 원인을 찾기 어렵다. 전이를 엔티티에 가둔 이유이고, 그 가둔 것이 맞는지 여기서 본다.
 */
class AccountTests {

    private Account linked() {
        return Account.link(1L, "088", "encrypted", "hash", "110-***-***789", "주거래", true);
    }

    /**
     * <b>연결 직후는 인증 대기다.</b> 예전 구현이 {@code CONNECTED} 로 바로 만들었고
     * 구현현황 3.1 이 그걸 "명세와 다른 점"으로 적어 두고 있었다.
     */
    @Test
    void startsAwaitingVerification() {
        Account account = linked();

        assertThat(account.getStatus()).isEqualTo("PENDING_VERIFICATION");
        assertThat(account.isConnected()).isFalse();
        assertThat(account.isAwaitingVerification()).isTrue();
    }

    @Test
    void connectsOnSuccessfulVerification() {
        Account account = linked();
        Instant at = Instant.parse("2026-09-08T02:30:00Z");

        account.markConnected(at);

        assertThat(account.getStatus()).isEqualTo("CONNECTED");
        assertThat(account.getVerifiedAt()).isEqualTo(at);
        assertThat(account.isAwaitingVerification()).isFalse();
    }

    /** 만료도 "아직 인증 대기"의 한 갈래다 — 재발송으로 되돌아올 수 있어야 한다. */
    @Test
    void expiredAccountIsStillAwaitingVerification() {
        Account account = linked();

        account.markVerificationExpired();

        assertThat(account.getStatus()).isEqualTo("VERIFICATION_EXPIRED");
        assertThat(account.isAwaitingVerification()).isTrue();
    }

    @Test
    void resendReturnsExpiredAccountToPending() {
        Account account = linked();
        account.markVerificationExpired();

        account.markPendingVerification();

        assertThat(account.getStatus()).isEqualTo("PENDING_VERIFICATION");
    }

    /**
     * 재발송은 {@code verifiedAt} 을 지운다. 안 지우면 인증 완료 후 해제했다가 다시 연결할 때
     * <b>이전 인증 시각이 그대로 남아</b> 감사 기록이 거짓말을 한다.
     */
    @Test
    void resendClearsPreviousVerificationTimestamp() {
        Account account = linked();
        account.markConnected(Instant.parse("2026-09-08T02:30:00Z"));

        account.markPendingVerification();

        assertThat(account.getVerifiedAt()).isNull();
    }

    // --- 동기화 상태 (인증과 독립된 축) ---------------------------------

    @Test
    void startsWithNeverSynced() {
        assertThat(linked().getSyncStatus()).isEqualTo("NEVER");
        assertThat(linked().getLastSyncedAt()).isNull();
    }

    @Test
    void recordsTheSuccessTimeOnlyOnSuccess() {
        Account account = linked();
        Instant at = Instant.parse("2026-09-08T02:30:00Z");

        account.markSyncRunning();
        assertThat(account.getSyncStatus()).isEqualTo("RUNNING");
        assertThat(account.getLastSyncedAt()).as("아직 성공하지 않았다").isNull();

        account.markSyncSucceeded(at);
        assertThat(account.getSyncStatus()).isEqualTo("SUCCESS");
        assertThat(account.getLastSyncedAt()).isEqualTo(at);
    }

    /**
     * <b>실패해도 마지막 성공 시각을 지우지 않는다.</b> 명세 7.3 이 실패 상태와 함께 그 값을
     * 화면에 표시하라고 요구한다 — 사용자가 "언제까지는 맞는 데이터인가"를 알아야 한다.
     */
    @Test
    void keepsTheLastSuccessTimeWhenSyncFails() {
        Account account = linked();
        Instant at = Instant.parse("2026-09-08T02:30:00Z");
        account.markSyncSucceeded(at);

        account.markSyncFailed();

        assertThat(account.getSyncStatus()).isEqualTo("FAILED");
        assertThat(account.getLastSyncedAt())
                .as("마지막으로 맞았던 시점은 남아야 한다")
                .isEqualTo(at);
    }

    /** 잔액은 last-write-wins 다. 그래서 재실행이 구조적으로 멱등하다. */
    @Test
    void overwritesBalanceOnEveryUpdate() {
        Account account = linked();
        Instant first = Instant.parse("2026-09-08T02:30:00Z");
        Instant second = first.plusSeconds(600);

        account.updateBalance(new BigDecimal("1000"), first);
        account.updateBalance(new BigDecimal("2500"), second);

        assertThat(account.getCurrentBalance()).isEqualByComparingTo("2500");
        assertThat(account.getBalanceAsOf()).isEqualTo(second);
    }

    /** 해제해도 행을 지우지 않는다 — 이미 수집한 거래를 보존해야 한다(API 7.2). */
    @Test
    void disconnectKeepsTheRow() {
        Account account = linked();
        account.markConnected(Instant.parse("2026-09-08T02:30:00Z"));

        account.disconnect();

        assertThat(account.getStatus()).isEqualTo("DISCONNECTED");
        assertThat(account.isConnected()).isFalse();
        assertThat(account.isAwaitingVerification()).isFalse();
    }

    /**
     * <b>해제하면 잔액은 지운다.</b> 남겨 두면 화면이 그것을 총잔액에 계속 더한다 —
     * 연동을 끊었는데 그 계좌의 돈이 합계에 섞여 있으면 사용자는 해제로 무엇을 했는지 알 수 없다.
     *
     * <p>해제 뒤에는 동기화가 돌지 않아 이 값만 영원히 낡는다는 점이 재연결과 같다.
     */
    @Test
    void disconnectDropsTheBalance() {
        Account account = linked();
        account.markConnected(Instant.parse("2026-09-08T02:30:00Z"));
        account.updateBalance(new BigDecimal("1340000"), Instant.parse("2026-09-08T02:40:00Z"));

        account.disconnect();

        assertThat(account.getCurrentBalance()).isNull();
        assertThat(account.getBalanceAsOf()).isNull();
    }

    /**
     * <b>해제한 계좌를 다시 연결할 수 있다.</b> 되기 전에는 계좌번호 해시가 전역 유일이라
     * {@code ACCOUNT_ALREADY_LINKED} 에 막혀 DB 를 직접 고치는 길밖에 없었다(S15P21C105-115).
     */
    @Test
    void relinkReturnsDisconnectedAccountToPending() {
        Account account = linked();
        account.markConnected(Instant.parse("2026-09-08T02:30:00Z"));
        account.disconnect();

        account.relink("088", "encrypted2", "110-***-***789", "주거래", true);

        assertThat(account.getStatus()).isEqualTo("PENDING_VERIFICATION");
        assertThat(account.getVerifiedAt()).isNull();
        assertThat(account.isAwaitingVerification()).isTrue();
    }

    /** 만료도 같은 문으로 들어온다 — 프론트가 인증 화면을 놓치면 여기가 유일한 출구다. */
    @Test
    void relinkAlsoRevivesAnExpiredAccount() {
        Account account = linked();
        account.markVerificationExpired();

        account.relink("088", "encrypted2", "110-***-***789", "주거래", true);

        assertThat(account.getStatus()).isEqualTo("PENDING_VERIFICATION");
    }

    /** 사용자가 다시 채워 보낸 값이 이긴다 — 옛 별칭을 남기면 방금 입력한 이름이 사라진다. */
    @Test
    void relinkOverwritesWhatTheUserReentered() {
        Account account = linked();
        account.disconnect();

        account.relink("004", "encrypted2", "110***789", "배달 정산용", false);

        assertThat(account.getBankCode()).isEqualTo("004");
        assertThat(account.getAccountNumberEncrypted()).isEqualTo("encrypted2");
        assertThat(account.getAccountNumberMasked()).isEqualTo("110***789");
        assertThat(account.getName()).isEqualTo("배달 정산용");
        assertThat(account.isPrimaryAccount()).isFalse();
    }

    /**
     * 마지막 성공 시각은 남기고 잔액은 지운다.
     *
     * <p>거래가 그대로 있으니 "언제까지는 맞는 데이터인가"는 여전히 참이다. 잔액은 시점 값이라
     * 지금도 맞다고 말할 수 없고, 인증이 끝나면 자동 동기화가 다시 채운다.
     */
    @Test
    void relinkKeepsTheSyncHistoryButDropsTheBalance() {
        Account account = linked();
        Instant syncedAt = Instant.parse("2026-09-08T02:30:00Z");
        account.markConnected(syncedAt);
        account.markSyncSucceeded(syncedAt);
        account.updateBalance(new BigDecimal("1500"), syncedAt);
        account.disconnect();

        account.relink("088", "encrypted2", "110-***-***789", "주거래", true);

        assertThat(account.getLastSyncedAt()).isEqualTo(syncedAt);
        assertThat(account.getSyncStatus()).isEqualTo("SUCCESS");
        assertThat(account.getCurrentBalance()).isNull();
        assertThat(account.getBalanceAsOf()).isNull();
    }

    /**
     * 살아 있는 연결은 되살리기 대상이 아니다.
     *
     * <p>{@code PENDING_VERIFICATION} 을 빼는 것이 핵심이다 — 1원이 이미 나가 있어 재발송이 맡을
     * 몫이고, 재발송 상한이 그쪽에만 있어서 여기로 열면 금융망을 상한 없이 두드릴 수 있다.
     */
    @Test
    void onlyDisconnectedOrExpiredAccountsAreRelinkable() {
        Account pending = linked();
        assertThat(pending.isRelinkable()).isFalse();

        Account connected = linked();
        connected.markConnected(Instant.parse("2026-09-08T02:30:00Z"));
        assertThat(connected.isRelinkable()).isFalse();

        Account expired = linked();
        expired.markVerificationExpired();
        assertThat(expired.isRelinkable()).isTrue();

        Account disconnected = linked();
        disconnected.disconnect();
        assertThat(disconnected.isRelinkable()).isTrue();
    }
}
