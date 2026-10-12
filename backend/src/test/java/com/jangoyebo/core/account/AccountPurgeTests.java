package com.jangoyebo.core.account;

import com.jangoyebo.NeedsDocker;
import com.jangoyebo.common.security.JwtTokenProvider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 계좌 삭제 (API 7.2 {@code POST /accounts/{accountId}/purge}).
 *
 * <p><b>여기서 보는 것은 「무엇이 사라지고 무엇이 남는가」다.</b> 외래키가 열한 개고
 * {@code ON DELETE CASCADE} 가 하나도 없어, 순서가 한 칸만 어긋나도 제약 위반으로 죽는다.
 * 그런데 그 실패는 <b>거래가 많은 계좌에서만</b> 드러나므로 사람이 눈으로 잡기 어렵다.
 *
 * <p>반대편도 같은 무게다 — 대출·증빙·다른 계좌의 예정 거래는 <b>남아야 한다.</b> 지워서
 * 터지는 것보다 조용히 더 지우는 쪽이 위험하다.
 */
@NeedsDocker
@SpringBootTest
@AutoConfigureMockMvc
class AccountPurgeTests {

    @Autowired private MockMvc mockMvc;
    @Autowired private JwtTokenProvider tokenProvider;
    @Autowired private JdbcTemplate jdbc;

    private Long userId;
    private Long businessId;
    private Long accountId;
    private Long otherAccountId;
    private String token;

    @BeforeEach
    void setUp() {
        String email = "purge-" + System.nanoTime() + "@test.local";
        userId = jdbc.queryForObject("""
                INSERT INTO core.users (email, password_hash, name)
                VALUES (?, 'x', '삭제') RETURNING id
                """, Long.class, email);
        token = tokenProvider.createAccessToken(userId, email);
        businessId = jdbc.queryForObject("""
                INSERT INTO core.business (owner_user_id, name) VALUES (?, '삭제 사업장') RETURNING id
                """, Long.class, userId);
        accountId = insertAccount("110-***-***789", "주거래");
        otherAccountId = insertAccount("220-***-***111", "보조");
    }

    /**
     * 기본은 {@code DISCONNECTED} 다 — 살아 있는 연결은 지울 수 없기 때문이다
     * ({@code AccountPurgeService#assertNotConnected}). 「해제해 둔 계좌를 치운다」가
     * 이 기능의 주 경로이기도 하다.
     */
    private Long insertAccount(String masked, String name) {
        return insertAccount(masked, name, "DISCONNECTED");
    }

    private Long insertAccount(String masked, String name, String status) {
        return jdbc.queryForObject("""
                INSERT INTO core.account
                    (business_id, bank_code, account_number_encrypted, account_number_hash,
                     account_number_masked, name, status)
                VALUES (?, '088', 'enc', ?, ?, ?, ?) RETURNING id
                """, Long.class, businessId, "hash-" + System.nanoTime(), masked, name, status);
    }

    private Long insertTransaction(Long account, String description, int amount) {
        return jdbc.queryForObject("""
                INSERT INTO core.transaction
                    (business_id, account_id, external_transaction_id, source_type, occurred_at,
                     transaction_date, flow_type, amount, description,
                     classification_status, needs_confirmation)
                VALUES (?, ?, ?, 'FIN_API', now(), DATE '2026-09-10', 'DEPOSIT', ?, ?,
                        'CONFIRMED', FALSE) RETURNING id
                """, Long.class, businessId, account, "ext-" + System.nanoTime(), amount, description);
    }

    private Long insertEvidence(String filename) {
        return jdbc.queryForObject("""
                INSERT INTO core.evidence
                    (business_id, uploaded_by, original_filename, storage_key, mime_type, file_size)
                VALUES (?, ?, ?, ?, 'image/jpeg', 1024) RETURNING id
                """, Long.class, businessId, userId, filename, "key-" + System.nanoTime());
    }

    private void link(Long transactionId, Long evidenceId) {
        jdbc.update("""
                INSERT INTO core.transaction_evidence (transaction_id, evidence_id, match_status)
                VALUES (?, ?, 'LINKED')
                """, transactionId, evidenceId);
    }

    private ResultActions purge(Long account, String confirm, boolean orphanEvidence) throws Exception {
        return mockMvc.perform(post("/api/v1/accounts/" + account + "/purge")
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"confirm\":\"%s\",\"purgeOrphanEvidence\":%s}"
                        .formatted(confirm, orphanEvidence)));
    }

    private int count(String sql, Object... args) {
        Integer n = jdbc.queryForObject(sql, Integer.class, args);
        return n == null ? 0 : n;
    }

    // ─────────────────────────────────────────────────────────────────────────

    /**
     * 인수 조건 1 — 거래와 그 자식이 전부 사라지고 dangling 이 남지 않는다.
     *
     * <p>분류 이력·증빙 연결이 함께 걸려 있어야 순서 버그가 드러난다. 거래만 있는 계좌로는
     * 아무 순서로 지워도 통과한다.
     */
    @Test
    void removesAccountAndItsTransactions() throws Exception {
        Long tx = insertTransaction(accountId, "배민 정산", 50000);
        jdbc.update("""
                INSERT INTO core.transaction_classification_history
                    (transaction_id, new_category_id, classifier_type, previous_status, new_status)
                VALUES (?, null, 'RULE', 'UNCLASSIFIED', 'CONFIRMED')
                """, tx);
        link(tx, insertEvidence("영수증.jpg"));

        purge(accountId, "주거래", false)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.transactions").value(1))
                .andExpect(jsonPath("$.data.evidenceLinks").value(1));

        assertThat(count("SELECT count(*) FROM core.account WHERE id = ?", accountId)).isZero();
        assertThat(count("SELECT count(*) FROM core.transaction WHERE account_id = ?", accountId)).isZero();
        assertThat(count("""
                SELECT count(*) FROM core.transaction_classification_history WHERE transaction_id = ?
                """, tx)).isZero();
        assertThat(count("SELECT count(*) FROM core.transaction_evidence WHERE transaction_id = ?", tx))
                .isZero();
    }

    /** 인수 조건 2 — 증빙 행과 파일 참조는 남는다. 기본값에서는 표시조차 하지 않는다. */
    @Test
    void keepsEvidenceRowsByDefault() throws Exception {
        Long tx = insertTransaction(accountId, "식자재", 30000);
        Long evidence = insertEvidence("세금계산서.pdf");
        link(tx, evidence);

        purge(accountId, "주거래", false)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.evidenceMarkedDeleted").value(0));

        assertThat(count("""
                SELECT count(*) FROM core.evidence WHERE id = ? AND deleted_at IS NULL
                """, evidence)).isEqualTo(1);
    }

    /** 인수 조건 2 — 켜면 고아만 지운 것으로 표시한다. 행과 파일은 그대로다. */
    @Test
    void marksOrphanEvidenceWhenAsked() throws Exception {
        Long tx = insertTransaction(accountId, "식자재", 30000);
        Long evidence = insertEvidence("영수증.jpg");
        link(tx, evidence);

        purge(accountId, "주거래", true)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.evidenceMarkedDeleted").value(1));

        assertThat(count("""
                SELECT count(*) FROM core.evidence WHERE id = ? AND deleted_at IS NOT NULL
                """, evidence)).isEqualTo(1);
        // 행 자체는 남아야 한다 — EVIDENCE_CORRECTION 이 여기 매달려 있다(V18).
        assertThat(count("SELECT count(*) FROM core.evidence WHERE id = ?", evidence)).isEqualTo(1);
    }

    /**
     * 인수 조건 3 — 다른 계좌 거래에도 붙은 증빙은 <b>켜도</b> 남는다.
     *
     * <p>증빙 ↔ 거래가 N:M 이라(ERD §658) 생기는 경우다. 여기를 놓치면 계좌 하나를 지우면서
     * <b>살아 있는 계좌의 근거</b>를 날린다.
     */
    @Test
    void keepsEvidenceSharedWithAnotherAccount() throws Exception {
        Long doomed = insertTransaction(accountId, "식자재", 30000);
        Long survivor = insertTransaction(otherAccountId, "식자재 추가", 20000);
        Long shared = insertEvidence("세금계산서.pdf");
        link(doomed, shared);
        link(survivor, shared);

        purge(accountId, "주거래", true)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.evidenceMarkedDeleted").value(0));

        assertThat(count("""
                SELECT count(*) FROM core.evidence WHERE id = ? AND deleted_at IS NULL
                """, shared)).isEqualTo(1);
        // 살아남은 계좌 쪽 연결도 그대로여야 한다.
        assertThat(count("SELECT count(*) FROM core.transaction_evidence WHERE transaction_id = ?",
                survivor)).isEqualTo(1);
    }

    /**
     * 인수 조건 4 — 지운 계좌번호를 다시 등록하면 되살리기가 아니라 새 행이다.
     *
     * <p>{@code account_number_hash} 가 전역 UNIQUE 라, 소프트 삭제였다면 그 해시가 잡혀 있어
     * {@code Account#relink} 가 <b>지워진 계좌를 부활시킨다.</b> 하드 삭제를 고른 이유가 이것이다.
     */
    @Test
    void freesAccountNumberHash() throws Exception {
        String hash = jdbc.queryForObject(
                "SELECT account_number_hash FROM core.account WHERE id = ?", String.class, accountId);

        purge(accountId, "주거래", false).andExpect(status().isOk());

        assertThat(count("SELECT count(*) FROM core.account WHERE account_number_hash = ?", hash))
                .isZero();
    }

    /** 인수 조건 5 — 배분을 전부 잃은 대사는 {@code PENDING} 으로 돌아간다. */
    @Test
    void returnsSettlementToPendingWhenAllocationsVanish() throws Exception {
        Long tx = insertTransaction(accountId, "배민 정산 입금", 100000);
        Long settlement = jdbc.queryForObject("""
                INSERT INTO core.settlement
                    (business_id, platform_name, sales_from, sales_to, gross_sales, status)
                VALUES (?, '배민', DATE '2026-09-01', DATE '2026-09-07', 100000, 'MATCHED')
                RETURNING id
                """, Long.class, businessId);
        jdbc.update("""
                INSERT INTO core.settlement_transaction (settlement_id, transaction_id, allocated_amount)
                VALUES (?, ?, 100000)
                """, settlement, tx);

        purge(accountId, "주거래", false)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.settlementAllocations").value(1));

        // 대사 자체는 사업장 것이라 남고, 상태만 되돌아온다.
        assertThat(jdbc.queryForObject("SELECT status FROM core.settlement WHERE id = ?",
                String.class, settlement)).isEqualTo("PENDING");
    }

    /**
     * 대출은 남고 연결만 끊긴다.
     *
     * <p>빚은 계좌와 함께 사라지지 않는다. 지우면 상환 예정 거래까지 없어져
     * <b>곡선에서 나갈 돈이 빠지고</b> 없는 여유를 있다고 보여준다.
     */
    @Test
    void keepsLoanAndDetachesAccount() throws Exception {
        Long tx = insertTransaction(accountId, "대출 상환", 200000);
        Long loan = jdbc.queryForObject("""
                INSERT INTO core.loan
                    (business_id, withdrawal_account_id, lender_name, product_name,
                     principal_amount, interest_rate, started_on, maturity_date, status)
                VALUES (?, ?, '싸피은행', '운전자금대출', 10000000, 4.5,
                        DATE '2026-01-01', DATE '2027-01-01', 'REPAYING') RETURNING id
                """, Long.class, businessId, accountId);
        jdbc.update("""
                INSERT INTO core.loan_repayment (loan_id, due_date, amount, transaction_id, status)
                VALUES (?, DATE '2026-09-10', 200000, ?, 'SUCCESS')
                """, loan, tx);

        purge(accountId, "주거래", false).andExpect(status().isOk());

        assertThat(count("SELECT count(*) FROM core.loan WHERE id = ?", loan)).isEqualTo(1);
        assertThat(jdbc.queryForObject(
                "SELECT withdrawal_account_id FROM core.loan WHERE id = ?", Long.class, loan)).isNull();
        // 회차도 남는다 — 이미 갚은 것을 미납으로 되돌리면 안 된다.
        assertThat(jdbc.queryForObject(
                "SELECT status FROM core.loan_repayment WHERE loan_id = ?", String.class, loan))
                .isEqualTo("SUCCESS");
        assertThat(jdbc.queryForObject(
                "SELECT transaction_id FROM core.loan_repayment WHERE loan_id = ?", Long.class, loan))
                .isNull();
    }

    /** 인수 조건 7 — 확인 4자리가 틀리면 아무것도 지우지 않는다. */
    @Test
    void rejectsWrongConfirmation() throws Exception {
        insertTransaction(accountId, "배민 정산", 50000);

        purge(accountId, "보조", false).andExpect(status().isBadRequest());

        assertThat(count("SELECT count(*) FROM core.account WHERE id = ?", accountId)).isEqualTo(1);
        assertThat(count("SELECT count(*) FROM core.transaction WHERE account_id = ?", accountId))
                .isEqualTo(1);
    }

    /** 인수 조건 7 — 남의 계좌는 찾을 수 없다. */
    @Test
    void rejectsOtherOwnersAccount() throws Exception {
        String otherEmail = "intruder-" + System.nanoTime() + "@test.local";
        Long otherUser = jdbc.queryForObject("""
                INSERT INTO core.users (email, password_hash, name)
                VALUES (?, 'x', '남') RETURNING id
                """, Long.class, otherEmail);
        String otherToken = tokenProvider.createAccessToken(otherUser, otherEmail);

        mockMvc.perform(post("/api/v1/accounts/" + accountId + "/purge")
                        .header("Authorization", "Bearer " + otherToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"confirm\":\"주거래\"}"))
                .andExpect(status().is4xxClientError());

        assertThat(count("SELECT count(*) FROM core.account WHERE id = ?", accountId)).isEqualTo(1);
    }

    /**
     * 인수 조건 8 — 해제는 그대로다.
     *
     * <p>회귀선이다. 같은 {@code DELETE} 에 삭제를 얹지 않기로 한 결정이 지켜지는지 본다 —
     * 여기가 깨지면 구버전 앱의 「해제」가 하드 삭제가 된다.
     */
    @Test
    void disconnectStillPreservesTransactions() throws Exception {
        Long connected = insertAccount("330-***-***222", "연결된 계좌", "CONNECTED");
        insertTransaction(connected, "배민 정산", 50000);

        mockMvc.perform(delete("/api/v1/accounts/" + connected)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isNoContent());

        assertThat(jdbc.queryForObject("SELECT status FROM core.account WHERE id = ?",
                String.class, connected)).isEqualTo("DISCONNECTED");
        assertThat(count("SELECT count(*) FROM core.transaction WHERE account_id = ?", connected))
                .isEqualTo(1);
    }

    /**
     * 연결된 계좌는 지울 수 없다 ({@code 409 ACCOUNT_STATE_CONFLICT}).
     *
     * <p><b>친절이 아니라 경합을 닫는 장치다.</b> 자동 반영 워커가
     * {@code auto_execute_planned = true AND status = 'CONNECTED'} 로 계좌를 고르므로,
     * 연결된 계좌를 지우는 동안 워커가 <b>금융망에 실제로 출금을 보낼 수 있다.</b>
     * 우리 트랜잭션이 되감겨도 나간 돈은 돌아오지 않는다.
     *
     * <p>화면에서만 막으면 API 를 직접 부르는 쪽에는 창이 그대로 열린다 — 그래서 여기서 본다.
     */
    @Test
    void refusesToPurgeConnectedAccount() throws Exception {
        Long connected = insertAccount("330-***-***222", "연결된 계좌", "CONNECTED");
        insertTransaction(connected, "배민 정산", 50000);

        purge(connected, "연결된 계좌", false).andExpect(status().isConflict());

        assertThat(count("SELECT count(*) FROM core.account WHERE id = ?", connected)).isEqualTo(1);
        assertThat(count("SELECT count(*) FROM core.transaction WHERE account_id = ?", connected))
                .isEqualTo(1);
    }

    /**
     * 인증 대기·만료 계좌는 해제를 거치지 않고 바로 지운다.
     *
     * <p>둘 다 동기화·자동 반영 대상이 아니라(둘 다 {@code CONNECTED} 를 요구한다) 막을 이유가
     * 없다. 계좌번호를 잘못 쳐서 인증 대기에 걸린 계좌에까지 「연결 해제」를 먼저 시키면,
     * <b>연결된 적 없는 계좌의 연결을 끊으라는 말</b>이 된다.
     */
    @Test
    void purgesAccountStuckAwaitingVerification() throws Exception {
        Long pending = insertAccount("440-***-***333", "오타 계좌", "PENDING_VERIFICATION");

        purge(pending, "오타 계좌", false).andExpect(status().isOk());

        assertThat(count("SELECT count(*) FROM core.account WHERE id = ?", pending)).isZero();
    }
}
