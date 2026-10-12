package com.jangoyebo.forecast.planned;

import com.jangoyebo.NeedsDocker;
import com.jangoyebo.common.security.JwtTokenProvider;
import com.jangoyebo.forecast.calendar.BusinessCalendarService;
import com.jangoyebo.forecast.calendar.BusinessCalendarDtos.DayRequest;
import com.jangoyebo.forecast.calendar.CalendarSource;
import com.jangoyebo.forecast.calendar.DayType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 이미 금융망에 나간 예정 거래를 지키는 장치들 (API 9.6).
 *
 * <p>돈이 나간 뒤에는 그 행을 지우거나 날짜·금액을 바꿀 수 없다. 바꿔도 나간 돈이 돌아오지
 * 않으므로, 화면의 계획과 통장이 갈리기만 한다.
 */
@NeedsDocker
@SpringBootTest
@AutoConfigureMockMvc
class PlannedExecutionGuardTests {

    private static final LocalDate TODAY = LocalDate.now(ZoneId.of("Asia/Seoul"));

    @Autowired private MockMvc mockMvc;
    @Autowired private JwtTokenProvider tokenProvider;
    @Autowired private PlannedTransactionRepository planned;
    @Autowired private BusinessCalendarService calendar;
    @Autowired private JdbcTemplate jdbc;

    private Long businessId;
    private Long accountId;
    private Long patternId;
    private String token;

    @BeforeEach
    void setUp() {
        String email = "guard-" + System.nanoTime() + "@test.local";
        Long userId = jdbc.queryForObject("""
                INSERT INTO core.users (email, password_hash, name) VALUES (?, 'x', '사장') RETURNING id
                """, Long.class, email);
        token = tokenProvider.createAccessToken(userId, email);
        businessId = jdbc.queryForObject("""
                INSERT INTO core.business (owner_user_id, name) VALUES (?, '카페 한잔') RETURNING id
                """, Long.class, userId);
        accountId = jdbc.queryForObject("""
                INSERT INTO core.account (business_id, bank_code, account_number_encrypted,
                                          account_number_hash, account_number_masked, name, status)
                VALUES (?, '999', 'enc', ?, '999-**-1234', '주계좌', 'CONNECTED') RETURNING id
                """, Long.class, businessId, "hash-" + System.nanoTime());
        patternId = jdbc.queryForObject("""
                INSERT INTO forecast.recurring_pattern
                       (business_id, account_id, name, flow_type, cadence_type,
                        expected_amount_min, expected_amount_max, confidence, status)
                VALUES (?, ?, '김영자', 'WITHDRAWAL', 'MONTHLY', 900000, 900000, 0.9, 'CONFIRMED')
                RETURNING id
                """, Long.class, businessId, accountId);
    }

    /**
     * <b>달력을 고쳐도 이미 나간 회차는 살아남는다.</b>
     *
     * <p>휴무일을 등록하면 {@code refreshPlanned} 가 미래 예정 거래를 지우고 다시 만든다.
     * 원장이 가리키는 행이 그때 사라지면 외래키가 깨져 <b>달력 저장이 500 이 되고</b>, 새로
     * 만들어진 행에는 원장이 없어 같은 회차가 한 번 더 나간다.
     */
    @Test
    void 달력을_고쳐도_이미_반영된_예정거래는_남는다() {
        Long executed = plannedRow(TODAY.plusDays(3));
        Long untouched = plannedRow(TODAY.plusDays(10));
        ledger(executed, TODAY.plusDays(3));

        calendar.upsert(businessId,
                List.of(new DayRequest(TODAY.plusDays(5), DayType.CLOSED, "휴업")), CalendarSource.USER, TODAY);

        assertThat(planned.findById(executed)).as("원장이 가리키는 행은 지우지 않는다").isPresent();
        assertThat(planned.findById(untouched)).as("나머지는 평소대로 다시 만든다").isEmpty();
    }

    @Test
    void 이미_반영된_예정거래는_취소할_수_없다() throws Exception {
        Long plannedId = plannedRow(TODAY.plusDays(3));
        ledger(plannedId, TODAY.plusDays(3));

        mockMvc.perform(patch("/api/v1/planned-transactions/" + plannedId)
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"CANCELLED\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("PLANNED_TX_NOT_DELETABLE"));
    }

    @Test
    void 이미_반영된_예정거래는_금액을_바꿀_수_없다() throws Exception {
        Long plannedId = plannedRow(TODAY.plusDays(3));
        ledger(plannedId, TODAY.plusDays(3));

        mockMvc.perform(patch("/api/v1/planned-transactions/" + plannedId)
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"amount\":500000}"))
                .andExpect(status().isConflict());
    }

    /** 표시만 바꾸는 것은 막지 않는다 — 통장과 갈릴 것이 없다. */
    @Test
    void 이미_반영됐어도_메모는_바꿀_수_있다() throws Exception {
        Long plannedId = plannedRow(TODAY.plusDays(3));
        ledger(plannedId, TODAY.plusDays(3));

        mockMvc.perform(patch("/api/v1/planned-transactions/" + plannedId)
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"memo\":\"임대료\"}"))
                .andExpect(status().isOk());
    }

    /** 사용자 등록분이라 원래는 지울 수 있지만, 나간 뒤에는 아니다. */
    @Test
    void 이미_반영된_사용자_예정거래는_지울_수_없다() throws Exception {
        Long plannedId = jdbc.queryForObject("""
                INSERT INTO forecast.planned_transaction
                       (business_id, account_id, source_type, name, expected_date,
                        original_expected_date, flow_type, amount_expected, amount_min, amount_max, status)
                VALUES (?, ?, 'USER', '머신 교체', ?, ?, 'WITHDRAWAL', 1500000, 1500000, 1500000, 'CONFIRMED')
                RETURNING id
                """, Long.class, businessId, accountId, TODAY.plusDays(3), TODAY.plusDays(3));
        jdbc.update("""
                INSERT INTO forecast.planned_execution
                       (planned_transaction_id, business_id, account_id, due_date, flow_type,
                        amount, summary, status, executed_at)
                VALUES (?, ?, ?, ?, 'WITHDRAWAL', 1500000, '머신 교체', 'EXECUTED', now())
                """, plannedId, businessId, accountId, TODAY.plusDays(3));

        mockMvc.perform(delete("/api/v1/planned-transactions/" + plannedId)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("PLANNED_TX_NOT_DELETABLE"));
    }

    // --- 픽스처 --------------------------------------------------------------

    private Long plannedRow(LocalDate date) {
        return jdbc.queryForObject("""
                INSERT INTO forecast.planned_transaction
                       (business_id, account_id, source_type, recurring_pattern_id, name,
                        expected_date, original_expected_date, flow_type,
                        amount_expected, amount_min, amount_max, status)
                VALUES (?, ?, 'PATTERN', ?, '김영자', ?, ?, 'WITHDRAWAL', 900000, 900000, 900000, 'EXPECTED')
                RETURNING id
                """, Long.class, businessId, accountId, patternId, date, date);
    }

    private void ledger(Long plannedId, LocalDate due) {
        jdbc.update("""
                INSERT INTO forecast.planned_execution
                       (planned_transaction_id, business_id, account_id, recurring_pattern_id,
                        due_date, flow_type, amount, summary, status, executed_at)
                VALUES (?, ?, ?, ?, ?, 'WITHDRAWAL', 900000, '김영자', 'EXECUTED', now())
                """, plannedId, businessId, accountId, patternId, due);
    }
}
