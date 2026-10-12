package com.jangoyebo.forecast.run;

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

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.ZoneId;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 계좌가 사라진 뒤에도 옛 곡선이 나가지 않는가.
 *
 * <p><b>이것이 막으려는 사고.</b> 마지막 계좌를 해제하거나 지우면 워커는 시작 잔액이 없어
 * 새 실행을 만들지 못한다. 그런데 직전에 계산된 실행은 그대로 남아 있어서, 조회가 그것을
 * 찾아 <b>사라진 계좌의 잔액이 들어간 총잔액</b>을 {@code 200} 으로 내려주고 그 계좌의 거래로
 * 만든 공백 경보까지 함께 보여 준다. 돈을 세는 화면에 틀린 숫자가 오류 없이 뜬다.
 *
 * <p>계좌가 여럿일 때 하나를 빼는 것은 원래 잘 돈다 — {@code TransactionsChanged} 가 재계산을
 * 걸고 새 실행이 옛것을 덮는다. <b>덮을 것이 만들어지지 않는 「마지막 하나」가 구멍이었다.</b>
 *
 * <p>「연결된 계좌」의 기준이 행의 존재가 아니라 {@code status = 'CONNECTED'} 라서
 * <b>해제만으로도</b> 같은 일이 일어난다. 그래서 삭제가 아니라 해제로 시험한다.
 */
@NeedsDocker
@SpringBootTest
@AutoConfigureMockMvc
class ForecastWithoutAccountsTests {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JwtTokenProvider tokenProvider;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private ForecastRunWorker worker;

    private static final LocalDate TODAY = LocalDate.now(ZoneId.of("Asia/Seoul"));

    private Long businessId;
    private String token;

    @BeforeEach
    void setUp() {
        String email = "no-accounts-" + System.nanoTime() + "@test.local";
        Long userId = jdbc.queryForObject("""
                INSERT INTO core.users (email, password_hash, name, status)
                VALUES (?, 'hash', '김사장', 'ACTIVE') RETURNING id
                """, Long.class, email);
        businessId = jdbc.queryForObject("""
                INSERT INTO core.business (owner_user_id, name, taxation_type, status)
                VALUES (?, '온기식당', 'GENERAL', 'ACTIVE') RETURNING id
                """, Long.class, userId);
        token = tokenProvider.createAccessToken(userId, email);
    }

    @Test
    void 마지막_계좌를_해제하면_잔액_곡선이_옛_값을_내지_않는다() throws Exception {
        readyWithCurve();

        disconnectAllAccounts();
        worker.execute(businessId);

        mockMvc.perform(get(url("/forecast/balance?days=30")).header("Authorization", bearer()))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error.code").value("FORECAST_INSUFFICIENT_DATA"));
    }

    /** 공백 경보는 근거가 사라져도 남는다 — 사용자는 아직 유효한 경고로 읽는다. */
    @Test
    void 마지막_계좌를_해제하면_정산_공백도_옛_값을_내지_않는다() throws Exception {
        readyWithCurve();

        disconnectAllAccounts();
        worker.execute(businessId);

        mockMvc.perform(get(url("/forecast/cash-gaps?days=30")).header("Authorization", bearer()))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error.code").value("FORECAST_INSUFFICIENT_DATA"));
    }

    /**
     * 시나리오(11장)도 같은 실행 위에서 계산한다.
     *
     * <p>막지 않으면 사라진 계좌의 원장으로 「이 가정이면 잔액이 어떻게 되나」를 답하게 된다.
     */
    @Test
    void 마지막_계좌를_해제하면_시나리오도_계산하지_않는다() throws Exception {
        readyWithCurve();

        disconnectAllAccounts();
        worker.execute(businessId);

        mockMvc.perform(post(url("/scenarios"))
                        .header("Authorization", bearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"days\":30,\"save\":false,\"parameters\":["
                                + "{\"type\":\"DEPOSIT_DECREASE\",\"rate\":0.2}]}"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error.code").value("FORECAST_INSUFFICIENT_DATA"));
    }

    /** 저장된 실행과 알림을 남겨 두면 계좌를 다시 연결했을 때 옛 곡선이 되살아난다. */
    @Test
    void 워커가_저장된_실행과_알림을_걷어낸다() {
        readyWithCurve();
        assertThat(runCount()).isPositive();

        disconnectAllAccounts();
        worker.execute(businessId);

        assertThat(runCount()).as("사라진 계좌로 계산된 실행은 남길 이유가 없다").isZero();
        assertThat(openAlertCount()).as("근거가 없어진 경고가 목록에 남으면 안 된다").isZero();
    }

    /**
     * 계좌가 하나 남으면 아무것도 걷어내지 않는다.
     *
     * <p>이쪽은 원래 잘 돌던 경로다. 고치면서 함께 지워 버리면 계좌 하나를 정리했을 뿐인
     * 사용자의 곡선이 통째로 사라진다.
     */
    @Test
    void 계좌가_하나라도_남으면_곡선은_그대로다() throws Exception {
        connectedAccount(new BigDecimal("20000000"));
        connectedAccount(new BigDecimal("5000000"));
        plannedWithdrawal(5, new BigDecimal("3000000"));
        worker.execute(businessId);

        jdbc.update("""
                UPDATE core.account SET status = 'DISCONNECTED'
                 WHERE business_id = ? AND id = (
                       SELECT min(id) FROM core.account WHERE business_id = ?)
                """, businessId, businessId);
        worker.execute(businessId);

        mockMvc.perform(get(url("/forecast/balance?days=30")).header("Authorization", bearer()))
                .andExpect(status().isOk())
                // 남은 계좌 하나로 다시 계산된다 — 해제한 계좌의 2천만은 빠진다.
                .andExpect(jsonPath("$.data.currentBalance").value(5000000));
        assertThat(runCount()).isPositive();
    }

    // --- helpers -------------------------------------------------------------------

    /** 계좌와 예정 거래가 있고 곡선이 한 번 계산된 상태. */
    private void readyWithCurve() {
        connectedAccount(new BigDecimal("1000000"));
        // 잔액보다 큰 지출이라 공백이 생긴다 — 알림까지 함께 만들어 두려는 것이다.
        plannedWithdrawal(5, new BigDecimal("3000000"));
        worker.execute(businessId);
    }

    private void disconnectAllAccounts() {
        jdbc.update("UPDATE core.account SET status = 'DISCONNECTED' WHERE business_id = ?", businessId);
    }

    private int runCount() {
        Integer count = jdbc.queryForObject(
                "SELECT count(*) FROM forecast.forecast_run WHERE business_id = ?", Integer.class, businessId);
        return count == null ? 0 : count;
    }

    /** 해소는 삭제가 아니라 {@code ACKNOWLEDGED} 로 내리는 것이다 — 이력은 남긴다. */
    private int openAlertCount() {
        Integer count = jdbc.queryForObject("""
                SELECT count(*) FROM core.alert
                 WHERE business_id = ? AND status = 'ACTIVE'
                """, Integer.class, businessId);
        return count == null ? 0 : count;
    }

    private void connectedAccount(BigDecimal balance) {
        jdbc.update("""
                INSERT INTO core.account (business_id, provider, bank_code, account_number_encrypted,
                                          account_number_hash, account_number_masked, name,
                                          status, sync_status, current_balance, balance_as_of)
                VALUES (?, 'FINOPENAPI', '004', 'enc', ?, '004-**-1234', '주계좌',
                        'CONNECTED', 'SUCCESS', ?, now())
                """, businessId, "hash-" + System.nanoTime(), balance);
    }

    private void plannedWithdrawal(int daysAhead, BigDecimal amount) {
        LocalDate date = TODAY.plusDays(daysAhead);
        jdbc.update("""
                INSERT INTO forecast.planned_transaction
                    (business_id, source_type, name, expected_date, original_expected_date, flow_type,
                     amount_expected, amount_min, amount_max, status, version, created_at, updated_at)
                VALUES (?, 'USER', '급여', ?, ?, 'WITHDRAWAL', ?, ?, ?, 'CONFIRMED', 0, now(), now())
                """, businessId, date, date, amount, amount, amount);
    }

    private String url(String suffix) {
        return "/api/v1/businesses/" + businessId + suffix;
    }

    private String bearer() {
        return "Bearer " + token;
    }
}
