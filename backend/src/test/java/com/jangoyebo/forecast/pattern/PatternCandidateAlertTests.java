package com.jangoyebo.forecast.pattern;

import com.jangoyebo.NeedsDocker;
import com.jangoyebo.common.security.JwtTokenProvider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 반복 후보 알림 (P0-04 → P0-08 · API 10.3 {@code PATTERN_CANDIDATE}).
 *
 * <p><b>파이프라인이 사람 앞에서 끊겨 있던 자리다.</b> {@code AlertType.PATTERN_CANDIDATE} 는
 * enum 과 명세에 있었는데 만드는 코드가 한 곳도 없었다. 탐지가 돌아 후보가 쌓여도 알리지
 * 않으니 승인 화면에 갈 이유가 없고, 승인이 없으면 예정 거래가 안 생기고, 그러면 예상 잔액
 * 곡선이 빈다.
 *
 * <p><b>Docker가 필요하다.</b>
 */
@NeedsDocker
@SpringBootTest
@AutoConfigureMockMvc
class PatternCandidateAlertTests {

    private static final LocalDate TODAY = LocalDate.of(2026, 9, 17);

    @Autowired private MockMvc mockMvc;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private JwtTokenProvider tokenProvider;
    @Autowired private RecurringPatternService service;

    private Long userId;
    private Long businessId;
    private Long accountId;
    private String token;

    @BeforeEach
    void setUp() {
        String unique = String.valueOf(System.nanoTime());
        String email = "patternalert-" + unique + "@test.local";
        userId = jdbc.queryForObject("""
                INSERT INTO core.users (email, password_hash, name) VALUES (?, 'x', '정유선')
                RETURNING id
                """, Long.class, email);
        token = tokenProvider.createAccessToken(userId, email);
        businessId = jdbc.queryForObject("""
                INSERT INTO core.business (owner_user_id, name) VALUES (?, '유선상회') RETURNING id
                """, Long.class, userId);
        accountId = jdbc.queryForObject("""
                INSERT INTO core.account
                    (business_id, bank_code, account_number_encrypted, account_number_hash,
                     account_number_masked, name, status)
                VALUES (?, '088', 'enc', ?, '***1234', '주거래', 'CONNECTED') RETURNING id
                """, Long.class, businessId, "hash-" + unique);
    }

    // --- 픽스처 --------------------------------------------------------------

    /** 매달 같은 날 같은 금액이 나가는 거래를 넣어 탐지가 후보로 잡게 한다. */
    private void monthly(String description, String amount, int day, int months) {
        for (int i = 1; i <= months; i++) {
            LocalDate date = TODAY.minusMonths(i).withDayOfMonth(day);
            jdbc.update("""
                    INSERT INTO core.transaction
                        (business_id, account_id, source_type, occurred_at, transaction_date,
                         flow_type, amount, description, counterparty_name, deduplication_key,
                         classification_status)
                    VALUES (?, ?, 'FIN_API', ?, ?, 'WITHDRAWAL', ?, ?, ?, ?, 'CONFIRMED')
                    """, businessId, accountId, Timestamp.from(Instant.now()), date,
                    new BigDecimal(amount), description, description,
                    "dedup-" + System.nanoTime());
        }
    }

    private ResultActions alerts() throws Exception {
        return mockMvc.perform(get("/api/v1/businesses/" + businessId + "/alerts?status=ACTIVE")
                .header("Authorization", "Bearer " + token));
    }

    private List<Map<String, Object>> activePatternAlerts() {
        return jdbc.queryForList("""
                SELECT title, message,
                       (detail ->> 'pendingCount')::int AS pending_count
                  FROM core.alert
                 WHERE business_id = ? AND alert_type = 'PATTERN_CANDIDATE' AND status = 'ACTIVE'
                """, businessId);
    }

    private List<Long> candidateIds() {
        return jdbc.queryForList("""
                SELECT id FROM forecast.recurring_pattern
                 WHERE business_id = ? AND status = 'CANDIDATE' ORDER BY id
                """, Long.class, businessId);
    }

    // --- 탐지 ----------------------------------------------------------------

    @Test
    void raisesOneAlertWhenCandidatesAppear() throws Exception {
        monthly("스타임대", "1200000", 5, 6);
        monthly("우리보험", "150000", 12, 6);

        service.detectAndUpsert(businessId, TODAY);

        assertThat(candidateIds()).hasSizeGreaterThanOrEqualTo(2);
        // 후보가 여럿이어도 알림은 하나다 — 건마다 올리면 공백·안전선 경고가 밑에 묻힌다.
        assertThat(activePatternAlerts()).hasSize(1);

        alerts().andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items[?(@.type == 'PATTERN_CANDIDATE')]").exists());
    }

    /** 후보가 하나도 없으면 알림을 만들지 않는다 — 「0건이 기다립니다」는 말이 안 된다. */
    @Test
    void staysSilentWhenNothingWasDetected() {
        service.detectAndUpsert(businessId, TODAY);

        assertThat(candidateIds()).isEmpty();
        assertThat(activePatternAlerts()).isEmpty();
    }

    /** 다시 탐지해도 알림이 늘지 않는다. 배치가 매일 도는데 쌓이면 목록이 못 쓰게 된다. */
    @Test
    void doesNotPileUpOnRepeatedDetection() {
        monthly("스타임대", "1200000", 5, 6);

        service.detectAndUpsert(businessId, TODAY);
        service.detectAndUpsert(businessId, TODAY);
        service.detectAndUpsert(businessId, TODAY);

        assertThat(activePatternAlerts()).hasSize(1);
    }

    @Test
    void namesTheCandidatesInTheTitle() {
        monthly("스타임대", "1200000", 5, 6);

        service.detectAndUpsert(businessId, TODAY);

        assertThat(activePatternAlerts().get(0).get("title").toString())
                .as("무엇이 잡혔는지 보이지 않으면 열어 보기 전에는 판단할 수 없다")
                .contains("스타임대");
    }

    /**
     * <b>승인을 시키지 않는다.</b> 실행성 판단은 사용자 몫이라(도메인 규칙 5) 알림은 무엇이
     * 달라지는지만 말한다.
     */
    @Test
    void tellsWhatChangesRatherThanOrderingTheUser() {
        monthly("스타임대", "1200000", 5, 6);

        service.detectAndUpsert(businessId, TODAY);

        String message = activePatternAlerts().get(0).get("message").toString();
        assertThat(message).contains("예상 잔액 곡선");
        assertThat(message).doesNotContain("하세요", "해야");
    }

    // --- 승인·무시 -----------------------------------------------------------

    /**
     * <b>처리한 일이 계속 경고로 남으면 안 된다.</b> 「후보가 생겼다」를 사건으로 적으면 승인을
     * 마쳐도 알림이 남는다. 지금 몇 건이 기다리는지를 다시 세어 0 이면 내린다.
     */
    @Test
    void takesTheAlertDownOnceEveryCandidateIsHandled() throws Exception {
        monthly("스타임대", "1200000", 5, 6);
        monthly("우리보험", "150000", 12, 6);
        service.detectAndUpsert(businessId, TODAY);

        List<Long> candidates = candidateIds();
        assertThat(activePatternAlerts()).hasSize(1);

        for (int i = 0; i < candidates.size(); i++) {
            Long patternId = candidates.get(i);
            if (i == 0) {
                mockMvc.perform(post("/api/v1/recurring-patterns/" + patternId + "/confirm")
                                .header("Authorization", "Bearer " + token)
                                .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                                .content("{}"))
                        .andExpect(status().isOk());
            } else {
                mockMvc.perform(post("/api/v1/recurring-patterns/" + patternId + "/dismiss")
                                .header("Authorization", "Bearer " + token))
                        .andExpect(status().isNoContent());
            }
        }

        assertThat(candidateIds()).isEmpty();
        assertThat(activePatternAlerts()).isEmpty();
    }

    /** 한 건만 처리하면 알림은 남고 건수만 줄어든다. */
    @Test
    void keepsTheAlertWhileSomethingIsStillWaiting() throws Exception {
        monthly("스타임대", "1200000", 5, 6);
        monthly("우리보험", "150000", 12, 6);
        service.detectAndUpsert(businessId, TODAY);
        int before = candidateIds().size();

        mockMvc.perform(post("/api/v1/recurring-patterns/" + candidateIds().get(0) + "/dismiss")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isNoContent());

        assertThat(candidateIds()).hasSize(before - 1);
        assertThat(activePatternAlerts()).hasSize(1);
        // 글자로 맞추지 않는다 — JSONB 는 「"pendingCount": 1」처럼 공백을 넣어 찍는다.
        assertThat(activePatternAlerts().get(0).get("pending_count")).isEqualTo(before - 1);
    }
}
