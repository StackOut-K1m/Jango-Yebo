package com.jangoyebo.core.account;

import com.jangoyebo.NeedsDocker;
import com.jangoyebo.common.error.BusinessException;
import com.jangoyebo.common.error.ErrorCode;
import com.jangoyebo.common.security.AuthUser;
import com.jangoyebo.core.fin.AccountAuthClient;
import com.jangoyebo.core.fin.FinUserKeyService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 계좌 등록이 이미 있는 행을 만났을 때의 갈래 (API 7.2).
 *
 * <p><b>Docker 가 필요하다.</b> {@code AccountTests} 가 엔티티 전이를 덮고 있는데도 이 테스트가
 * 따로 있는 이유는 <b>DB 가 있어야만 확인되는 주장</b>이 이 변경의 핵심이기 때문이다 —
 * 되살리기는 행을 지우고 새로 만들지 않으므로 {@code uk_account_number} 를 건드리지 않고
 * {@code accountId} 가 유지된다. 목으로는 그 둘 다 보여줄 수 없다.
 *
 * <p>소유권 갈래도 여기서만 볼 수 있다. "남의 계좌면 계좌 존재를 흘리지 않는다"는 판정이
 * {@code BusinessGuard} → 실제 {@code business.owner_user_id} 조회에 걸려 있어, 사용자와 사업장이
 * 진짜로 두 벌 있어야 한다.
 *
 * <p>금융망만 대역으로 바꾼다({@link FinStubs}). 1원 송금은 이 테스트의 관심사가 아니고,
 * <b>몇 번 나갔는지</b>만 센다 — 되살리기가 인증을 다시 시작한다는 것이 그것으로 드러난다.
 * 이 저장소는 Mockito 를 쓰지 않으므로 손으로 만든 대역을 쓴다({@code AccountSyncHandlerTests}).
 */
@NeedsDocker
@Import(AccountLinkTests.FinStubs.class)
@SpringBootTest
class AccountLinkTests {

    @Autowired
    private AccountService accountService;

    @Autowired
    private AccountRepository accountRepository;

    @Autowired
    private StubAuthClient authClient;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private StringRedisTemplate redis;

    private Long ownerId;
    private Long businessId;

    /** 이 테스트가 쓸 계좌번호. 매번 새로 만든다 — 아래 {@link #freshAccountNo()} 참고. */
    private String accountNo;

    @BeforeEach
    void setUp() {
        authClient.sent.clear();

        ownerId = insertUser("owner");
        businessId = insertBusiness(ownerId, "카페 한잔");
        accountNo = freshAccountNo();

        authenticateAs(ownerId);
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    // --- 행이 없을 때 -------------------------------------------------------

    @Test
    void createsANewRowWhenTheNumberIsUnseen() {
        Account account = accountService.link(businessId, "088", accountNo, "주거래", true);

        assertThat(account.getId()).isNotNull();
        assertThat(account.getStatus()).isEqualTo("PENDING_VERIFICATION");
        assertThat(authClient.sent).hasSize(1);
    }

    /**
     * 하이픈을 넣어 등록하면 <b>구분자를 보존한 마스킹 값</b>이 그대로 들어간다.
     *
     * <p>DB 를 거쳐야만 보이는 주장이라 여기 둔다 — {@code account_number_masked} 는 V1 에서
     * 숫자만 남긴 계좌번호 기준(32)으로 잡혀 있었고, 실제로 담는 값은 사용자가 친 구분자만큼
     * 더 길다. 여유가 0 이라 입력 상한을 올리는 순간 {@code value too long for type character
     * varying(32)} 로 500 이 났다. V11 이 컬럼을 넓혔다.
     *
     * <p>계좌를 가리키는 값은 구분자와 무관하다는 것도 함께 본다 — 1원은 숫자만 남긴 번호로 나간다.
     */
    @Test
    void storesTheMaskedValueWithTheSeparatorsTheUserTyped() {
        String withSeparators = accountNo.substring(0, 4) + "-" + accountNo.substring(4, 8)
                + "-" + accountNo.substring(8, 12) + "-" + accountNo.substring(12);

        Account account = accountService.link(businessId, "088", withSeparators, "주거래", true);

        String stored = jdbc.queryForObject(
                "SELECT account_number_masked FROM core.account WHERE id = ?", String.class, account.getId());
        assertThat(stored).hasSize(withSeparators.length())
                .isEqualTo(AccountService.mask(withSeparators));
        assertThat(authClient.sent).containsExactly(accountNo);
    }

    // --- 되살리기 ----------------------------------------------------------

    /**
     * <b>이 테스트가 이 클래스의 이유다.</b>
     *
     * <p>고치기 전에는 두 번째 {@code link} 가 {@code 409 ACCOUNT_ALREADY_LINKED} 로 막혔고,
     * 검사만 풀었다면 {@code uk_account_number} 위반으로 {@code 500} 이 났다. 같은 행을 되살리는
     * 것이 맞다는 주장은 <b>행이 하나뿐이고 id 가 그대로</b>라는 것으로만 증명된다 — 그래야
     * {@code account_id} 에 매달린 거래 이력이 이어진다.
     */
    @Test
    void revivesTheDisconnectedRowInsteadOfCreatingASecond() {
        Long firstId = accountService.link(businessId, "088", accountNo, "주거래", true).getId();
        accountService.disconnect(firstId);

        Account revived = accountService.link(businessId, "088", accountNo, "주거래", true);

        assertThat(revived.getId()).isEqualTo(firstId);
        assertThat(revived.getStatus()).isEqualTo("PENDING_VERIFICATION");
        assertThat(accountRowsInBusiness()).isEqualTo(1);
        // 되살리기는 인증을 처음부터 다시 시작한다 — 등록 때 1회, 되살릴 때 1회.
        assertThat(authClient.sent).hasSize(2);
    }

    /** 만료도 같은 문으로 들어온다. 사용자가 유효시간을 넘기기만 해도 닿는 갈래다. */
    @Test
    void revivesAnExpiredRowToo() {
        Long firstId = accountService.link(businessId, "088", accountNo, "주거래", true).getId();
        forceStatus(firstId, "VERIFICATION_EXPIRED");

        Account revived = accountService.link(businessId, "088", accountNo, "주거래", true);

        assertThat(revived.getId()).isEqualTo(firstId);
        assertThat(revived.getStatus()).isEqualTo("PENDING_VERIFICATION");
        assertThat(accountRowsInBusiness()).isEqualTo(1);
    }

    /** 사용자가 폼을 다시 채워 보냈으므로 그것이 최신 의사다. 옛 별칭이 남으면 입력이 버려진다. */
    @Test
    void overwritesTheReenteredValuesOnRevive() {
        Long firstId = accountService.link(businessId, "088", accountNo, "주거래 사업용", true).getId();
        accountService.disconnect(firstId);

        Account revived = accountService.link(businessId, "004", accountNo, "배달 정산용", false);

        assertThat(revived.getBankCode()).isEqualTo("004");
        assertThat(revived.getName()).isEqualTo("배달 정산용");
        assertThat(revived.isPrimaryAccount()).isFalse();
    }

    /**
     * 마지막 성공 시각은 남기고 잔액은 지운다.
     *
     * <p>거래가 그대로 있으니 "언제까지는 맞는 데이터인가"는 여전히 참이다(명세 7.3 이 실패 상태와
     * 함께 그 시각을 보여주라고 요구하는 것과 같은 이유). 잔액은 시점 값이라 지금도 맞다고 말할 수
     * 없고, 인증이 끝나면 자동 동기화가 다시 채운다.
     */
    @Test
    void keepsTheSyncHistoryAndClearsTheBalanceOnRevive() {
        Long firstId = accountService.link(businessId, "088", accountNo, "주거래", true).getId();
        Instant syncedAt = Instant.now().truncatedTo(ChronoUnit.SECONDS).minus(2, ChronoUnit.DAYS);
        jdbc.update("""
                UPDATE core.account
                   SET sync_status = 'SUCCESS', last_synced_at = ?, current_balance = 1500, balance_as_of = ?
                 WHERE id = ?
                """, java.sql.Timestamp.from(syncedAt), java.sql.Timestamp.from(syncedAt), firstId);
        accountService.disconnect(firstId);

        Account revived = accountService.link(businessId, "088", accountNo, "주거래", true);

        assertThat(revived.getSyncStatus()).isEqualTo("SUCCESS");
        assertThat(revived.getLastSyncedAt()).isEqualTo(syncedAt);
        assertThat(revived.getCurrentBalance()).isNull();
        assertThat(revived.getBalanceAsOf()).isNull();
    }

    // --- 되살리지 않는 갈래 -------------------------------------------------

    /** 살아 있는 연결을 되살리면 남의(내 것이라도) 쓰는 계좌를 빼앗는 셈이다. */
    @Test
    void rejectsARowThatIsStillConnected() {
        Long firstId = accountService.link(businessId, "088", accountNo, "주거래", true).getId();
        forceStatus(firstId, "CONNECTED");

        assertThatThrownBy(() -> accountService.link(businessId, "088", accountNo, "주거래", true))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).errorCode())
                .isEqualTo(ErrorCode.ACCOUNT_ALREADY_LINKED);
    }

    /**
     * 인증 대기도 되살리기 대상이 아니다.
     *
     * <p>1원이 이미 나가 있어 재발송({@code /verification})이 맡을 몫이고, <b>재발송 상한이
     * 그쪽에만 있다</b> — 등록 쪽으로 열면 폼을 다시 눌러 상한 없이 금융망을 두드릴 수 있다.
     */
    @Test
    void rejectsARowAwaitingVerification() {
        accountService.link(businessId, "088", accountNo, "주거래", true);

        assertThatThrownBy(() -> accountService.link(businessId, "088", accountNo, "주거래", true))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).errorCode())
                .isEqualTo(ErrorCode.ACCOUNT_ALREADY_LINKED);
        assertThat(authClient.sent).hasSize(1);   // 막힌 요청은 1원을 보내지 않는다
    }

    /** 명세 7.2 가 정해 둔 원래의 중복 연결. 같은 사용자가 사업장을 건너 같은 계좌를 붙이는 경우다. */
    @Test
    void rejectsAnotherBusinessOfTheSameOwner() {
        Long otherBusinessId = insertBusiness(ownerId, "카페 두잔");
        Long firstId = accountService.link(otherBusinessId, "088", accountNo, "주거래", true).getId();
        accountService.disconnect(firstId);   // 해제해 둬도 남의 사업장 행은 되살리지 않는다

        assertThatThrownBy(() -> accountService.link(businessId, "088", accountNo, "주거래", true))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).errorCode())
                .isEqualTo(ErrorCode.ACCOUNT_ALREADY_LINKED);
    }

    /**
     * <b>남의 계좌는 409 가 아니다.</b>
     *
     * <p>{@code ACCOUNT_ALREADY_LINKED} 를 주면 "누군가 이 계좌를 등록해 두었다"가 새어 나간다.
     * 계좌번호 해시가 전역 유일이라 이 상황이 실제로 생기며, 타인 계좌를 금융망 {@code A1003} 과
     * 같은 {@code ACCOUNT_NO_INVALID} 로 뭉개는 기존 방침(명세 7.2)에 맞춘다.
     */
    @Test
    void hidesSomeoneElsesAccountBehindAccountNoInvalid() {
        Long strangerId = insertUser("stranger");
        Long strangerBusinessId = insertBusiness(strangerId, "남의 가게");

        authenticateAs(strangerId);
        accountService.link(strangerBusinessId, "088", accountNo, "남의 주거래", true);

        authenticateAs(ownerId);
        assertThatThrownBy(() -> accountService.link(businessId, "088", accountNo, "주거래", true))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).errorCode())
                .isEqualTo(ErrorCode.ACCOUNT_NO_INVALID);
    }

    // --- 재발송 문 닫기 -----------------------------------------------------

    /**
     * 해제된 계좌에는 재발송하지 않는다.
     *
     * <p>고치기 전에는 이 엔드포인트가 해제된 계좌를 그대로 받아 되살렸다 — 상태도에 없는 전이였고,
     * 사용자가 새로 입력한 은행·별칭이 반영되지 않은 채 살아났다. 되살리는 문은 등록 쪽 하나다.
     */
    @Test
    void rejectsResendOnADisconnectedAccount() {
        Long accountId = accountService.link(businessId, "088", accountNo, "주거래", true).getId();
        accountService.disconnect(accountId);
        authClient.sent.clear();

        assertThatThrownBy(() -> accountService.resendVerification(accountId))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).errorCode())
                .isEqualTo(ErrorCode.ACCOUNT_STATE_CONFLICT);
        assertThat(authClient.sent).isEmpty();
    }

    // --- 해제가 곡선을 낡게 만든다 -------------------------------------------

    /**
     * <b>해제하면 예측 재계산이 걸린다.</b>
     *
     * <p>해제는 거래 행을 하나도 건드리지 않는다. 그런데 시작 잔액과 패턴 탐지가 모두
     * {@code CONNECTED} 계좌만 보므로 그 순간 저장된 곡선이 낡는다 — 알리지 않으면
     * {@code FORECAST_RUN} 에 값으로 굳어 있는 곡선이 <b>해제한 계좌의 잔액이 들어간
     * 총잔액</b>을 04:00 배치까지 계속 내려준다. 잔액 컬럼을 비우는 것으로는 막히지 않는
     * 구멍이라 여기서 못박는다.
     *
     * <p>표시(Redis)까지만 본다 — 실행 스레드는 테스트에서 꺼져 있다
     * ({@code ForecastTriggerTests} 와 같은 경계).
     */
    @Test
    void disconnectMarksTheForecastDirty() {
        Long accountId = accountService.link(businessId, "088", accountNo, "주거래", true).getId();
        redis.delete("forecast:dirty:" + businessId);

        accountService.disconnect(accountId);

        assertThat(redis.hasKey("forecast:dirty:" + businessId))
                .as("해제가 재계산 표시를 남겨야 한다")
                .isTrue();
    }

    // --- 고정물 -------------------------------------------------------------

    /**
     * 계좌번호를 테스트마다 새로 만든다.
     *
     * <p>{@code uk_account_number} 가 <b>전역</b> 유일이라 고정 번호를 쓰면 앞선 테스트가 남긴 행과
     * 부딪힌다. 번호를 갈면 계좌 행을 지우는 정리도 필요 없다 — {@code core.account} 는 거래·반복
     * 패턴·예정 거래가 참조하므로 일괄 삭제가 FK 에 걸릴 수 있다.
     */
    private String freshAccountNo() {
        return "0014" + String.format("%012d", System.nanoTime() % 1_000_000_000_000L);
    }

    /** 이 사업장의 계좌 행 수. 되살리기가 행을 늘리지 않았음을 보는 눈이다. */
    private int accountRowsInBusiness() {
        return accountRepository.findByBusinessIdOrderByIdAsc(businessId).size();
    }

    /**
     * 상태를 SQL 로 밀어 넣는다.
     *
     * <p>전이를 엔티티에 가둔 것은 <b>운영 코드</b>를 막기 위한 것이고, 여기서는 특정 상태의 행이
     * 필요할 뿐이다. {@code ck_account_status} 가 값은 계속 검사한다.
     */
    private void forceStatus(Long accountId, String status) {
        jdbc.update("UPDATE core.account SET status = ? WHERE id = ?", status, accountId);
    }

    private Long insertUser(String tag) {
        return jdbc.queryForObject("""
                INSERT INTO core.users (email, password_hash, name) VALUES (?, 'x', ?) RETURNING id
                """, Long.class, tag + "-" + System.nanoTime() + "@test.local", tag);
    }

    private Long insertBusiness(Long userId, String name) {
        return jdbc.queryForObject("""
                INSERT INTO core.business (owner_user_id, name) VALUES (?, ?) RETURNING id
                """, Long.class, userId, name);
    }

    private void authenticateAs(Long id) {
        var principal = new AuthUser(id, "user-" + id + "@test.local");
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(principal, null, List.of()));
    }

    // --- 금융망 대역 --------------------------------------------------------

    /** 1원 송금 흔적만 모은다. 몇 번 나갔는지가 "인증을 다시 시작했는가"를 말해 준다. */
    static class StubAuthClient extends AccountAuthClient {
        final List<String> sent = new ArrayList<>();

        StubAuthClient() {
            super(null, null);
        }

        @Override
        public void sendOneWon(String userKey, String accountNo) {
            sent.add(accountNo);
        }
    }

    /** {@code userKey} 발급은 금융망 왕복이다. 이 테스트의 관심사가 아니라 고정값을 준다. */
    static class StubUserKeyService extends FinUserKeyService {
        StubUserKeyService() {
            super(null, null, null);
        }

        @Override
        public String resolve(Long userId) {
            return "test-user-key";
        }
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class FinStubs {

        @Bean
        @Primary
        StubAuthClient stubAuthClient() {
            return new StubAuthClient();
        }

        @Bean
        @Primary
        StubUserKeyService stubUserKeyService() {
            return new StubUserKeyService();
        }
    }
}
