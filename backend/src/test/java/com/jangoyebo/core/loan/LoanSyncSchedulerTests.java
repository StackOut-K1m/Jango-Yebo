package com.jangoyebo.core.loan;

import com.jangoyebo.NeedsDocker;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 일일 대출 동기화 배치 (API 16.1 · P1-13).
 *
 * <p><b>그동안 수동 호출만 있었다.</b> 아무도 버튼을 누르지 않으면 대출 목록이 그 자리였고,
 * 늙은 대출은 상환 회차를 덜 깎아 <b>곡선을 낙관적으로</b> 만든다.
 *
 * <p><b>대상을 고르는 두 조건이 이 배치의 핵심이다.</b> 넓게 잡으면 금융망에 회원을 새로
 * 찍고 다니고, 좁게 잡으면 동기화가 안 도는 사업장이 생긴다.
 *
 * <p><b>Docker가 필요하다.</b>
 */
@NeedsDocker
@SpringBootTest
class LoanSyncSchedulerTests {

    @Autowired private JdbcTemplate jdbc;
    @Autowired private LoanSyncScheduler scheduler;

    private String unique;

    @BeforeEach
    void setUp() {
        unique = String.valueOf(System.nanoTime());
    }

    // --- 픽스처 --------------------------------------------------------------

    private Long user(boolean withFinUserKey) {
        return jdbc.queryForObject("""
                INSERT INTO core.users (email, password_hash, name, fin_user_key)
                VALUES (?, 'x', '정유선', ?) RETURNING id
                """, Long.class, "loanbatch-" + System.nanoTime() + "@test.local",
                withFinUserKey ? "enc-user-key" : null);
    }

    private Long business(Long ownerId) {
        return jdbc.queryForObject("""
                INSERT INTO core.business (owner_user_id, name) VALUES (?, '유선상회') RETURNING id
                """, Long.class, ownerId);
    }

    private void account(Long businessId, String status) {
        jdbc.update("""
                INSERT INTO core.account
                    (business_id, bank_code, account_number_encrypted, account_number_hash,
                     account_number_masked, name, status)
                VALUES (?, '088', 'enc', ?, '***1234', '주거래', ?)
                """, businessId, "hash-" + System.nanoTime(), status);
    }

    /** 소유자에게 userKey 가 있고 연결된 계좌도 있는 — 정상 대상. */
    private Long eligibleBusiness() {
        Long businessId = business(user(true));
        account(businessId, "CONNECTED");
        return businessId;
    }

    private List<Map<String, Object>> loanJobs(Long businessId) {
        return jdbc.queryForList("""
                SELECT id, requested_by, status FROM core.job
                 WHERE business_id = ? AND job_type = 'LOAN_SYNC' ORDER BY id
                """, businessId);
    }

    // --- 대상 고르기 ---------------------------------------------------------

    @Test
    void queuesOneJobPerEligibleBusiness() {
        Long businessId = eligibleBusiness();

        scheduler.scheduleDailySync();

        assertThat(loanJobs(businessId)).hasSize(1);
    }

    /**
     * <b>{@code userKey} 가 없는 사용자는 건드리지 않는다.</b> 핸들러가
     * {@code FinUserKeyService.resolve} 를 부르는데, 그것은 키가 없으면 <b>금융망에 회원을
     * 새로 만든다</b> — 배치가 외부 시스템에 계정을 찍고 다니면 안 된다.
     */
    @Test
    void leavesOutOwnersWhoNeverTouchedTheFinNetwork() {
        Long businessId = business(user(false));
        account(businessId, "CONNECTED");

        scheduler.scheduleDailySync();

        assertThat(loanJobs(businessId)).isEmpty();
    }

    /**
     * <b>연결된 계좌가 없으면 받아 와도 붙일 데가 없다.</b> 대출을 사업장에 배정하는 근거가
     * 출금계좌라, 계좌가 없으면 전부 미배정으로 떨어진다.
     */
    @Test
    void leavesOutBusinessesWithNoConnectedAccount() {
        Long disconnected = business(user(true));
        account(disconnected, "DISCONNECTED");
        Long bare = business(user(true));

        scheduler.scheduleDailySync();

        assertThat(loanJobs(disconnected)).isEmpty();
        assertThat(loanJobs(bare)).isEmpty();
    }

    @Test
    void leavesOutDeletedBusinesses() {
        Long businessId = eligibleBusiness();
        jdbc.update("UPDATE core.business SET deleted_at = now() WHERE id = ?", businessId);

        scheduler.scheduleDailySync();

        assertThat(loanJobs(businessId)).isEmpty();
    }

    // --- Job 모양 ------------------------------------------------------------

    /**
     * <b>요청자를 반드시 채운다.</b> {@link LoanSyncHandler} 가 {@code requested_by} 로
     * {@code userKey} 를 정한다 — 비면 그 Job 은 첫 실행에서 즉시 실패한다.
     */
    @Test
    void stampsTheOwnerAsRequesterSoTheHandlerCanResolveTheUserKey() {
        Long ownerId = user(true);
        Long businessId = business(ownerId);
        account(businessId, "CONNECTED");

        scheduler.scheduleDailySync();

        assertThat(loanJobs(businessId).get(0).get("requested_by")).isEqualTo(ownerId);
    }

    /**
     * <b>어제 것이 안 끝났으면 걸지 않는다.</b> 금융망이 막힌 날 같은 사업장 Job 이 매일 한
     * 건씩 쌓이면, 풀리는 날 그만큼을 한꺼번에 왕복한다.
     */
    @Test
    void doesNotStackUpWhileTheEarlierOneIsStillRunning() {
        Long businessId = eligibleBusiness();

        scheduler.scheduleDailySync();
        scheduler.scheduleDailySync();
        scheduler.scheduleDailySync();

        assertThat(loanJobs(businessId)).hasSize(1);
    }

    /** 앞의 것이 끝났으면 다음 날은 새로 건다 — 한 번 돌고 멈추면 배치가 아니다. */
    @Test
    void queuesAgainOnceTheEarlierOneFinished() {
        Long businessId = eligibleBusiness();
        scheduler.scheduleDailySync();
        jdbc.update("""
                UPDATE core.job SET status = 'COMPLETED', completed_at = now()
                 WHERE business_id = ? AND job_type = 'LOAN_SYNC'
                """, businessId);

        scheduler.scheduleDailySync();

        assertThat(loanJobs(businessId)).hasSize(2);
    }

    /**
     * <b>한 사업장이 실패해도 나머지를 계속 건다.</b> 사업장 하나 때문에 그날 동기화가 통째로
     * 없어지는 쪽이 훨씬 나쁘다 — 그래서 배치 전체를 한 트랜잭션으로 열지 않는다.
     */
    @Test
    void keepsGoingAfterOneBusinessFails() {
        Long first = eligibleBusiness();
        Long second = eligibleBusiness();
        // 앞 사업장에 진행 중인 Job 을 심어 enqueueIfIdle 이 건너뛰게 만든다. 건너뛴 것이
        // 뒤 사업장을 막지 않아야 한다.
        jdbc.update("""
                INSERT INTO core.job (business_id, requested_by, job_type, status)
                VALUES (?, (SELECT owner_user_id FROM core.business WHERE id = ?),
                        'LOAN_SYNC', 'PROCESSING')
                """, first, first);

        scheduler.scheduleDailySync();

        assertThat(loanJobs(first)).hasSize(1);
        assertThat(loanJobs(second)).hasSize(1);
    }
}
