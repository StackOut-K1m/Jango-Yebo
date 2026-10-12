package com.jangoyebo.forecast.pattern;

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

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 반복 패턴 API (9.2~9.3).
 *
 * <p>탐지 <b>규칙</b>은 {@code RecurringPatternDetectorTests}(Docker 불필요)가 검증한다.
 * 여기는 <b>저장·상태 전이·예정 거래 생성</b>이 실제로 도는지를 본다.
 */
@NeedsDocker
@SpringBootTest
@AutoConfigureMockMvc
class RecurringPatternApiTests {

    @Autowired private MockMvc mockMvc;
    @Autowired private JwtTokenProvider tokenProvider;
    @Autowired private JdbcTemplate jdbc;

    private Long businessId;
    private Long accountId;
    private String token;

    @BeforeEach
    void setUp() {
        String email = "pattern-" + System.nanoTime() + "@test.local";
        Long userId = jdbc.queryForObject("""
                INSERT INTO core.users (email, password_hash, name)
                VALUES (?, 'x', '패턴') RETURNING id
                """, Long.class, email);
        token = tokenProvider.createAccessToken(userId, email);
        businessId = jdbc.queryForObject("""
                INSERT INTO core.business (owner_user_id, name) VALUES (?, '가게') RETURNING id
                """, Long.class, userId);
        // CONNECTED 여야 한다 — 탐지는 연결된 계좌의 거래만 읽는다(API 7.2,
        // TransactionHistoryService). 인증 전 계좌로 두면 후보가 하나도 안 나온다.
        accountId = jdbc.queryForObject("""
                INSERT INTO core.account
                    (business_id, bank_code, account_number_encrypted, account_number_hash,
                     account_number_masked, name, status)
                VALUES (?, '088', 'enc', ?, '***1234', '주거래', 'CONNECTED') RETURNING id
                """, Long.class, businessId, "hash-" + System.nanoTime());
    }

    /** 월세 4건을 넣고 탐지하면 MONTHLY 후보가 생긴다. */
    @Test
    void detectsAndListsCandidate() throws Exception {
        LocalDate base = LocalDate.now().minusMonths(4).withDayOfMonth(5);
        for (int i = 0; i < 4; i++) {
            insertTransaction("월세 이체", "WITHDRAWAL", 1200000, base.plusMonths(i));
        }

        detect().andExpect(status().isAccepted())
                .andExpect(jsonPath("$.data.createdCount").value(1));

        list("?status=CANDIDATE")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items.length()").value(1))
                .andExpect(jsonPath("$.data.items[0].cycle.type").value("MONTHLY"))
                .andExpect(jsonPath("$.data.items[0].cycle.dayOfMonth").value(5))
                .andExpect(jsonPath("$.data.items[0].status").value("CANDIDATE"))
                // 숫자가 아니라 구간이다 — 도메인 규칙 1
                .andExpect(jsonPath("$.data.items[0].confidence")
                        .value(org.hamcrest.Matchers.in(java.util.List.of("HIGH", "MEDIUM", "LOW"))));
    }

    /** 두 번 돌려도 후보가 늘지 않아야 한다 — 안 그러면 배치마다 같은 것이 쌓인다. */
    @Test
    void reDetectionUpdatesInsteadOfDuplicating() throws Exception {
        LocalDate base = LocalDate.now().minusMonths(4).withDayOfMonth(5);
        for (int i = 0; i < 4; i++) {
            insertTransaction("월세 이체", "WITHDRAWAL", 1200000, base.plusMonths(i));
        }

        detect().andExpect(jsonPath("$.data.createdCount").value(1));
        detect().andExpect(jsonPath("$.data.createdCount").value(0));

        assertThat(countPatterns()).isEqualTo(1);
    }

    /** 무시한 것은 재탐지에서 되살아나지 않는다 (API 9.3). */
    @Test
    void dismissedPatternStaysDismissed() throws Exception {
        long patternId = detectOneMonthly();

        mockMvc.perform(post("/api/v1/recurring-patterns/" + patternId + "/dismiss")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isNoContent());

        detect().andExpect(jsonPath("$.data.createdCount").value(0));

        assertThat(statusOf(patternId)).isEqualTo("DISMISSED");
        assertThat(countPatterns()).isEqualTo(1);
    }

    /** 후보가 아니면 승인·무시가 409 다. */
    @Test
    void rejectsConfirmingNonCandidate() throws Exception {
        long patternId = detectOneMonthly();
        confirm(patternId, "{}").andExpect(status().isOk());

        confirm(patternId, "{}")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("PATTERN_STATE_CONFLICT"));
    }

    /**
     * 승인하면 향후 90일치 예정 거래가 생긴다 — 이 시점부터 예측에 들어간다.
     */
    @Test
    void confirmGeneratesPlannedTransactions() throws Exception {
        long patternId = detectOneMonthly();

        confirm(patternId, """
                {"category":"RENT","amountMin":1150000,"amountMax":1250000}
                """)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("CONFIRMED"))
                .andExpect(jsonPath("$.data.category").value("RENT"))
                .andExpect(jsonPath("$.data.amountRange.min").value(1150000))
                // 대표값은 범위의 중앙이다.
                .andExpect(jsonPath("$.data.amountRange.expected").value(1200000));

        Integer generated = jdbc.queryForObject("""
                SELECT count(*) FROM forecast.planned_transaction
                 WHERE recurring_pattern_id = ? AND source_type = 'PATTERN' AND status = 'EXPECTED'
                """, Integer.class, patternId);
        // 90일 안에 월 단위면 3~4건이다.
        assertThat(generated).isBetween(2, 4);
    }

    /** 승인 본문 없이도 된다 — 전부 선택값이다(API 9.3). */
    @Test
    void confirmsWithoutBody() throws Exception {
        long patternId = detectOneMonthly();

        mockMvc.perform(post("/api/v1/recurring-patterns/" + patternId + "/confirm")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("CONFIRMED"));
    }

    @Test
    void rejectsInvertedAmountRange() throws Exception {
        long patternId = detectOneMonthly();

        confirm(patternId, """
                {"amountMin":2000000,"amountMax":1000000}
                """)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"));
    }

    /**
     * PAUSED 로 바꾸면 미래 EXPECTED 예정 거래를 걷어낸다.
     *
     * <p>패턴만 멈추고 예정 거래를 두면 <b>중지했는데 곡선은 그대로</b>가 된다.
     *
     * <p><b>취소가 아니라 삭제다.</b> {@code CANCELLED} 로 남기면 사용자가 직접 끈 회차와
     * 섞여, 되돌릴 때 무엇을 되살려야 하는지 알 수 없다({@code S15P21C105-180}).
     */
    @Test
    void pausingRemovesFuturePlanned() throws Exception {
        long patternId = detectOneMonthly();
        confirm(patternId, "{}").andExpect(status().isOk());

        assertThat(countPlanned(patternId, "EXPECTED")).isPositive();

        patchPattern(patternId, "{\"status\":\"PAUSED\"}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("PAUSED"));

        assertThat(countPlanned(patternId, "EXPECTED")).isZero();
        assertThat(countPlanned(patternId, "CANCELLED"))
                .as("중지가 만든 취소 행이 남으면 사용자가 끈 회차와 구별되지 않는다")
                .isZero();
    }

    /**
     * <b>되돌리면 예정 거래가 다시 생긴다</b> (S15P21C105-180).
     *
     * <p>여기가 비어 있었다. 목록은 「확정」인데 곡선에는 그 돈이 없었고, 확정된 패턴의 과거
     * 거래는 잔여에서도 빠지므로({@code explainedByConfirmedPatterns}) <b>예정과 잔여 양쪽에서
     * 동시에</b> 사라졌다.
     */
    @Test
    void resumingRegeneratesPlanned() throws Exception {
        long patternId = detectOneMonthly();
        confirm(patternId, "{}").andExpect(status().isOk());
        int before = countPlanned(patternId, "EXPECTED");

        patchPattern(patternId, "{\"status\":\"PAUSED\"}").andExpect(status().isOk());
        assertThat(countPlanned(patternId, "EXPECTED")).isZero();

        patchPattern(patternId, "{\"status\":\"CONFIRMED\"}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("CONFIRMED"));

        assertThat(countPlanned(patternId, "EXPECTED"))
                .as("되돌린 자리에서 같은 회차가 다시 만들어져야 한다")
                .isEqualTo(before);
    }

    /**
     * 금액만 고쳐도 예정 거래를 다시 만든다.
     *
     * <p>안 그러면 이미 만들어진 예정 거래가 옛 금액 그대로라 <b>고쳤는데 곡선은 그대로</b>다.
     */
    @Test
    void editingAmountRebuildsPlanned() throws Exception {
        long patternId = detectOneMonthly();
        confirm(patternId, "{}").andExpect(status().isOk());
        int before = countPlanned(patternId, "EXPECTED");

        patchPattern(patternId, "{\"amountMin\":1000000,\"amountMax\":1100000}")
                .andExpect(status().isOk());

        assertThat(countPlanned(patternId, "EXPECTED")).isEqualTo(before);
        // MONTHLY·WITHDRAWAL 이라 휴무일 감쇄가 안 걸린다 — 대표값은 범위의 중앙 그대로다.
        assertThat(distinctExpectedAmounts(patternId))
                .containsExactly(new java.math.BigDecimal("1050000.0000"));
    }

    /**
     * <b>사용자가 끈 회차는 다시 만들지 않는다.</b>
     *
     * <p>패턴 생성분은 삭제가 막혀 있어({@code PLANNED_TX_NOT_DELETABLE}) 사용자에게는 취소가
     * 곧 삭제다. 재생성이 그 날짜에 새 {@code EXPECTED} 를 하나 더 만들면 끈 것이 되살아나고,
     * 자동 반영을 켠 계좌라면 실제로 나간다(도메인 규칙 5).
     */
    @Test
    void regenerationKeepsUserCancelledOccurrence() throws Exception {
        long patternId = detectOneMonthly();
        confirm(patternId, "{}").andExpect(status().isOk());
        int before = countPlanned(patternId, "EXPECTED");

        long plannedId = earliestPlannedId(patternId);
        LocalDate cancelled = plannedDateOf(plannedId);
        mockMvc.perform(patch("/api/v1/planned-transactions/" + plannedId)
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"CANCELLED\"}"))
                .andExpect(status().isOk());

        // 금액을 고쳐 재생성을 태운다.
        patchPattern(patternId, "{\"amountMin\":1000000,\"amountMax\":1100000}")
                .andExpect(status().isOk());

        assertThat(countPlannedOn(patternId, cancelled, "EXPECTED"))
                .as("끈 회차 자리에 새 예정 거래가 들어서면 안 된다")
                .isZero();
        assertThat(countPlannedOn(patternId, cancelled, "CANCELLED")).isEqualTo(1);
        assertThat(countPlanned(patternId, "EXPECTED")).isEqualTo(before - 1);
    }

    /** CANDIDATE 에서 PAUSED 로 건너뛸 수 없다 — 승인 없이 중지할 것이 없다. */
    @Test
    void rejectsIllegalStatusTransition() throws Exception {
        long patternId = detectOneMonthly();

        mockMvc.perform(patch("/api/v1/recurring-patterns/" + patternId)
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"PAUSED\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("PATTERN_STATE_CONFLICT"));
    }

    /** 직접 경로라 guard.assertOwned 가 유일한 방어선이다. */
    @Test
    void rejectsOtherBusinessPattern() throws Exception {
        Long otherOwner = jdbc.queryForObject("""
                INSERT INTO core.users (email, password_hash, name) VALUES (?, 'x', '남') RETURNING id
                """, Long.class, "other-" + System.nanoTime() + "@test.local");
        Long otherBusiness = jdbc.queryForObject("""
                INSERT INTO core.business (owner_user_id, name) VALUES (?, '남의 가게') RETURNING id
                """, Long.class, otherOwner);
        Long foreign = jdbc.queryForObject("""
                INSERT INTO forecast.recurring_pattern
                    (business_id, name, flow_type, cadence_type, expected_amount_min,
                     expected_amount_max, confidence)
                VALUES (?, '남의 패턴', 'DEPOSIT', 'MONTHLY', 1, 2, 0.9) RETURNING id
                """, Long.class, otherBusiness);

        mockMvc.perform(get("/api/v1/recurring-patterns/" + foreign)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isForbidden());
    }

    @Test
    void returns404ForMissingPattern() throws Exception {
        mockMvc.perform(get("/api/v1/recurring-patterns/999999")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("PATTERN_NOT_FOUND"));
    }

    /** 예측 제외 거래는 후보로 올라오지 않는다 — 사용자가 이미 "일회성"이라고 표시한 것이다. */
    @Test
    void ignoresExcludedTransactions() throws Exception {
        LocalDate base = LocalDate.now().minusMonths(4).withDayOfMonth(5);
        for (int i = 0; i < 4; i++) {
            insertTransaction("제외된 거래", "WITHDRAWAL", 500000, base.plusMonths(i));
        }
        jdbc.update("UPDATE core.transaction SET is_excluded = TRUE WHERE business_id = ?", businessId);

        detect().andExpect(jsonPath("$.data.createdCount").value(0));
    }

    /**
     * <b>해제된 계좌의 거래는 후보가 되지 않는다.</b> 그 계좌 잔액은 시작 잔액에서 빠져 있다
     * ({@code AccountBalancesService} 는 {@code CONNECTED} 만 본다). 여기서 패턴을 만들면
     * 시작 잔액에는 없는 계좌의 반복 지출이 곡선을 계속 깎아 <b>없는 정산 공백</b>이 생긴다.
     */
    @Test
    void ignoresTransactionsOfDisconnectedAccounts() throws Exception {
        Long disconnected = jdbc.queryForObject("""
                INSERT INTO core.account
                    (business_id, bank_code, account_number_encrypted, account_number_hash,
                     account_number_masked, name, status)
                VALUES (?, '004', 'enc', ?, '***9999', '옛 계좌', 'DISCONNECTED') RETURNING id
                """, Long.class, businessId, "hash-" + System.nanoTime());

        LocalDate base = LocalDate.now().minusMonths(4).withDayOfMonth(11);
        for (int i = 0; i < 4; i++) {
            insertTransaction("옛 계좌 월세", "WITHDRAWAL", 900000, base.plusMonths(i), disconnected);
        }

        detect().andExpect(jsonPath("$.data.createdCount").value(0));
    }

    // --- helpers -------------------------------------------------------------

    private long detectOneMonthly() throws Exception {
        LocalDate base = LocalDate.now().minusMonths(4).withDayOfMonth(5);
        for (int i = 0; i < 4; i++) {
            insertTransaction("월세 이체", "WITHDRAWAL", 1200000, base.plusMonths(i));
        }
        detect().andExpect(status().isAccepted());
        return jdbc.queryForObject(
                "SELECT id FROM forecast.recurring_pattern WHERE business_id = ?", Long.class, businessId);
    }

    private ResultActions detect() throws Exception {
        return mockMvc.perform(post("/api/v1/businesses/" + businessId + "/recurring-patterns/detect")
                .header("Authorization", "Bearer " + token));
    }

    private ResultActions list(String query) throws Exception {
        return mockMvc.perform(get("/api/v1/businesses/" + businessId + "/recurring-patterns" + query)
                .header("Authorization", "Bearer " + token));
    }

    private ResultActions confirm(long patternId, String body) throws Exception {
        return mockMvc.perform(post("/api/v1/recurring-patterns/" + patternId + "/confirm")
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body));
    }

    private ResultActions patchPattern(long patternId, String body) throws Exception {
        return mockMvc.perform(patch("/api/v1/recurring-patterns/" + patternId)
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body));
    }

    private void insertTransaction(String description, String flow, int amount, LocalDate date) {
        insertTransaction(description, flow, amount, date, accountId);
    }

    private void insertTransaction(String description, String flow, int amount, LocalDate date, Long account) {
        jdbc.update("""
                INSERT INTO core.transaction
                    (business_id, account_id, external_transaction_id, source_type, occurred_at,
                     transaction_date, flow_type, amount, description, classification_status,
                     needs_confirmation)
                VALUES (?, ?, ?, 'FIN_API', now(), ?, ?, ?, ?, 'CONFIRMED', FALSE)
                """, businessId, account, "ext-" + System.nanoTime(), date, flow, amount, description);
    }

    private int countPatterns() {
        return jdbc.queryForObject(
                "SELECT count(*) FROM forecast.recurring_pattern WHERE business_id = ?",
                Integer.class, businessId);
    }

    private String statusOf(long patternId) {
        return jdbc.queryForObject(
                "SELECT status FROM forecast.recurring_pattern WHERE id = ?", String.class, patternId);
    }

    private int countPlanned(long patternId, String status) {
        return jdbc.queryForObject("""
                SELECT count(*) FROM forecast.planned_transaction
                 WHERE recurring_pattern_id = ? AND status = ?
                """, Integer.class, patternId, status);
    }

    private int countPlannedOn(long patternId, LocalDate date, String status) {
        return jdbc.queryForObject("""
                SELECT count(*) FROM forecast.planned_transaction
                 WHERE recurring_pattern_id = ? AND expected_date = ? AND status = ?
                """, Integer.class, patternId, date, status);
    }

    private long earliestPlannedId(long patternId) {
        return jdbc.queryForObject("""
                SELECT id FROM forecast.planned_transaction
                 WHERE recurring_pattern_id = ? ORDER BY expected_date LIMIT 1
                """, Long.class, patternId);
    }

    private LocalDate plannedDateOf(long plannedId) {
        return jdbc.queryForObject(
                "SELECT expected_date FROM forecast.planned_transaction WHERE id = ?",
                LocalDate.class, plannedId);
    }

    private java.util.List<java.math.BigDecimal> distinctExpectedAmounts(long patternId) {
        return jdbc.queryForList("""
                SELECT DISTINCT amount_expected FROM forecast.planned_transaction
                 WHERE recurring_pattern_id = ? AND status = 'EXPECTED'
                """, java.math.BigDecimal.class, patternId);
    }
}
