package com.jangoyebo.core.settlement;

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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 정산 수동 대사 (API 14.1, P0-11).
 *
 * <p>계산식은 단순하지만 <b>상태 판정과 알림 수</b>가 틀리기 쉽다 — 아직 입금 전인 정상
 * 상태를 불일치로 만들거나, 금액을 고쳐 해결한 뒤에도 알림이 남는 쪽이다.
 */
@NeedsDocker
@SpringBootTest
@AutoConfigureMockMvc
class SettlementTests {

    @Autowired private MockMvc mockMvc;
    @Autowired private JwtTokenProvider tokenProvider;
    @Autowired private JdbcTemplate jdbc;

    private Long businessId;
    private Long accountId;
    private String token;

    @BeforeEach
    void setUp() {
        String email = "settle-" + System.nanoTime() + "@test.local";
        Long userId = jdbc.queryForObject("""
                INSERT INTO core.users (email, password_hash, name)
                VALUES (?, 'x', '정산') RETURNING id
                """, Long.class, email);
        token = tokenProvider.createAccessToken(userId, email);
        businessId = jdbc.queryForObject("""
                INSERT INTO core.business (owner_user_id, name) VALUES (?, '가게') RETURNING id
                """, Long.class, userId);
        accountId = jdbc.queryForObject("""
                INSERT INTO core.account
                    (business_id, bank_code, account_number_encrypted, account_number_hash,
                     account_number_masked, name)
                VALUES (?, '088', 'enc', ?, '***1234', '주거래') RETURNING id
                """, Long.class, businessId, "hash-" + System.nanoTime());
    }

    /** 명세 예시 그대로 — 100만 − 5만 − 10만 = 85만, 입금 85만이면 MATCHED. */
    @Test
    void matchesWhenDepositEqualsExpectedPayout() throws Exception {
        long txId = insertDeposit(850000);

        create("""
                {"platformName":"배달의민족","periodStart":"2026-08-18","periodEnd":"2026-08-24",
                 "grossSales":1000000,"cancellations":50000,"fees":100000,
                 "matchedTransactionIds":[%d]}
                """.formatted(txId))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.expectedPayout").value(850000))
                .andExpect(jsonPath("$.data.actualDeposit").value(850000))
                .andExpect(jsonPath("$.data.difference").value(0))
                .andExpect(jsonPath("$.data.status").value("MATCHED"));

        assertThat(countAlerts("ACTIVE")).isZero();
    }

    /**
     * 매칭 거래가 없으면 PENDING 이다.
     *
     * <p><b>MISMATCHED 가 아니다.</b> 아직 입금 전인 정상 상태를 불일치로 만들면
     * 정산 주기 안의 모든 대사가 경고로 뜬다.
     */
    @Test
    void staysPendingWithoutMatches() throws Exception {
        create("""
                {"platformName":"쿠팡이츠","periodStart":"2026-08-18","periodEnd":"2026-08-24",
                 "grossSales":500000,"fees":50000}
                """)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.expectedPayout").value(450000))
                .andExpect(jsonPath("$.data.actualDeposit").value(0))
                .andExpect(jsonPath("$.data.status").value("PENDING"));

        assertThat(countAlerts("ACTIVE")).isZero();
    }

    /** 차이가 있으면 MISMATCHED 이고 알림이 하나 생긴다 (API 14.1). */
    @Test
    void raisesAlertOnMismatch() throws Exception {
        long txId = insertDeposit(800000);

        create("""
                {"platformName":"배달의민족","periodStart":"2026-08-18","periodEnd":"2026-08-24",
                 "grossSales":1000000,"cancellations":50000,"fees":100000,
                 "matchedTransactionIds":[%d]}
                """.formatted(txId))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.difference").value(-50000))
                .andExpect(jsonPath("$.data.status").value("MISMATCHED"));

        mockMvc.perform(get("/api/v1/businesses/" + businessId + "/alerts?status=ACTIVE")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items.length()").value(1))
                .andExpect(jsonPath("$.data.items[0].type").value("SETTLEMENT_MISMATCH"))
                .andExpect(jsonPath("$.data.items[0].relatedRef.settlementId").exists())
                // 원인을 단정하지 않고 확인할 항목을 안내한다 — 도메인 규칙 1
                .andExpect(jsonPath("$.data.items[0].message")
                        .value(org.hamcrest.Matchers.containsString("확인해 보세요")));
    }

    /**
     * 같은 대사를 여러 번 고쳐도 알림은 하나다 (API 10.3).
     *
     * <p>{@code dedupKey} 가 대사 id 라 갱신만 된다. 없으면 수정할 때마다 쌓인다.
     */
    @Test
    void doesNotDuplicateAlertOnRepeatedUpdates() throws Exception {
        long settlementId = createMismatched();

        for (int i = 0; i < 3; i++) {
            patchSettlement(settlementId, """
                    {"fees":%d}
                    """.formatted(100000 + i * 1000))
                    .andExpect(status().isOk());
        }

        assertThat(countAlerts("ACTIVE")).isEqualTo(1);
    }

    /**
     * 금액을 고쳐 차이가 0 이 되면 알림이 내려간다.
     *
     * <p><b>내리는 쪽이 더 중요하다.</b> 해결한 것이 계속 경고로 남으면 사용자는 알림을
     * 신뢰하지 않게 된다.
     */
    @Test
    void resolvesAlertWhenDifferenceGoesToZero() throws Exception {
        long settlementId = createMismatched();
        assertThat(countAlerts("ACTIVE")).isEqualTo(1);

        // 입금 80만에 맞춰 수수료를 15만으로 올리면 예상액도 80만이 된다.
        patchSettlement(settlementId, "{\"fees\":150000}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("MATCHED"));

        assertThat(countAlerts("ACTIVE")).isZero();
    }

    /** 대사를 지우면 그 대사가 만든 알림도 근거가 사라진다. */
    @Test
    void resolvesAlertOnDelete() throws Exception {
        long settlementId = createMismatched();

        mockMvc.perform(delete("/api/v1/settlements/" + settlementId)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isNoContent());

        assertThat(countAlerts("ACTIVE")).isZero();
    }

    /** 출금을 정산 입금으로 매칭하면 부호가 뒤집혀 차액이 두 배로 벌어진다. */
    @Test
    void rejectsWithdrawalAsMatch() throws Exception {
        long txId = insertTransaction(500000, "WITHDRAWAL");

        create("""
                {"platformName":"배달의민족","periodStart":"2026-08-18","periodEnd":"2026-08-24",
                 "grossSales":500000,"matchedTransactionIds":[%d]}
                """.formatted(txId))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"));
    }

    /** 남의 사업장 거래를 붙이면 그 사업장 입금액이 이쪽 대사에 섞인다. */
    @Test
    void rejectsForeignTransactionAsMatch() throws Exception {
        Long otherOwner = jdbc.queryForObject("""
                INSERT INTO core.users (email, password_hash, name) VALUES (?, 'x', '남') RETURNING id
                """, Long.class, "other-" + System.nanoTime() + "@test.local");
        Long otherBusiness = jdbc.queryForObject("""
                INSERT INTO core.business (owner_user_id, name) VALUES (?, '남의 가게') RETURNING id
                """, Long.class, otherOwner);
        Long otherAccount = jdbc.queryForObject("""
                INSERT INTO core.account
                    (business_id, bank_code, account_number_encrypted, account_number_hash,
                     account_number_masked, name)
                VALUES (?, '088', 'enc', ?, '***9999', '남의 계좌') RETURNING id
                """, Long.class, otherBusiness, "hash-" + System.nanoTime());
        Long foreignTx = jdbc.queryForObject("""
                INSERT INTO core.transaction
                    (business_id, account_id, external_transaction_id, source_type, occurred_at,
                     transaction_date, flow_type, amount, description, classification_status,
                     needs_confirmation)
                VALUES (?, ?, ?, 'FIN_API', now(), DATE '2026-08-25', 'DEPOSIT', 850000, '남의 입금',
                        'CONFIRMED', FALSE) RETURNING id
                """, Long.class, otherBusiness, otherAccount, "ext-" + System.nanoTime());

        create("""
                {"platformName":"배달의민족","periodStart":"2026-08-18","periodEnd":"2026-08-24",
                 "grossSales":850000,"matchedTransactionIds":[%d]}
                """.formatted(foreignTx))
                .andExpect(status().isForbidden());
    }

    @Test
    void rejectsInvertedPeriod() throws Exception {
        create("""
                {"platformName":"배달의민족","periodStart":"2026-08-24","periodEnd":"2026-08-18",
                 "grossSales":100000}
                """)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"));
    }

    /** 매칭 목록을 안 보낸 PATCH 는 기존 매칭을 유지한다 — 금액만 고치는 흔한 경로다. */
    @Test
    void keepsMatchesWhenPatchOmitsThem() throws Exception {
        long settlementId = createMismatched();

        patchSettlement(settlementId, "{\"platformName\":\"배민\"}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.platformName").value("배민"))
                .andExpect(jsonPath("$.data.actualDeposit").value(800000));
    }

    @Test
    void acknowledgesAlert() throws Exception {
        createMismatched();
        Long alertId = jdbc.queryForObject(
                "SELECT id FROM core.alert WHERE business_id = ?", Long.class, businessId);

        mockMvc.perform(post("/api/v1/alerts/" + alertId + "/ack")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isNoContent());

        assertThat(countAlerts("ACTIVE")).isZero();
        assertThat(countAlerts("ACKNOWLEDGED")).isEqualTo(1);
    }

    @Test
    void returns404ForMissingSettlement() throws Exception {
        mockMvc.perform(get("/api/v1/settlements/999999")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("SETTLEMENT_NOT_FOUND"));
    }

    // --- helpers -------------------------------------------------------------

    /** 예상 85만 / 입금 80만 → 차이 −5만. */
    private long createMismatched() throws Exception {
        long txId = insertDeposit(800000);
        create("""
                {"platformName":"배달의민족","periodStart":"2026-08-18","periodEnd":"2026-08-24",
                 "grossSales":1000000,"cancellations":50000,"fees":100000,
                 "matchedTransactionIds":[%d]}
                """.formatted(txId)).andExpect(status().isCreated());
        return jdbc.queryForObject(
                "SELECT id FROM core.settlement WHERE business_id = ? ORDER BY id DESC LIMIT 1",
                Long.class, businessId);
    }

    private ResultActions create(String body) throws Exception {
        return mockMvc.perform(post("/api/v1/businesses/" + businessId + "/settlements")
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON).content(body));
    }

    private ResultActions patchSettlement(long settlementId, String body) throws Exception {
        return mockMvc.perform(patch("/api/v1/settlements/" + settlementId)
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON).content(body));
    }

    private long insertDeposit(int amount) {
        return insertTransaction(amount, "DEPOSIT");
    }

    private long insertTransaction(int amount, String flow) {
        return jdbc.queryForObject("""
                INSERT INTO core.transaction
                    (business_id, account_id, external_transaction_id, source_type, occurred_at,
                     transaction_date, flow_type, amount, description, classification_status,
                     needs_confirmation)
                VALUES (?, ?, ?, 'FIN_API', now(), DATE '2026-08-25', ?, ?, '정산 입금',
                        'CONFIRMED', FALSE) RETURNING id
                """, Long.class, businessId, accountId, "ext-" + System.nanoTime(), flow, amount);
    }

    private int countAlerts(String status) {
        return jdbc.queryForObject("""
                SELECT count(*) FROM core.alert WHERE business_id = ? AND status = ?
                """, Integer.class, businessId, status);
    }
}
