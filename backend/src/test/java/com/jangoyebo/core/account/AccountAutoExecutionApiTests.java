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

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 예정 거래 자동 반영 on/off (API 9.6 · 7.2).
 *
 * <p><b>이 스위치가 이 기능의 유일한 안전장치다.</b> 켜면 서버가 예정일마다 그 계좌에서 진짜로
 * 돈을 빼고 넣는다. 기본은 꺼짐이고, 인증이 끝난 계좌에만 켤 수 있으며, 연동을 해제하면 꺼진다.
 */
@NeedsDocker
@SpringBootTest
@AutoConfigureMockMvc
class AccountAutoExecutionApiTests {

    @Autowired private MockMvc mockMvc;
    @Autowired private JwtTokenProvider tokenProvider;
    @Autowired private AccountRepository accounts;
    @Autowired private JdbcTemplate jdbc;

    private Long businessId;
    private String token;

    @BeforeEach
    void setUp() {
        String email = "auto-" + System.nanoTime() + "@test.local";
        Long userId = jdbc.queryForObject("""
                INSERT INTO core.users (email, password_hash, name) VALUES (?, 'x', '사장') RETURNING id
                """, Long.class, email);
        token = tokenProvider.createAccessToken(userId, email);
        businessId = jdbc.queryForObject("""
                INSERT INTO core.business (owner_user_id, name) VALUES (?, '카페 한잔') RETURNING id
                """, Long.class, userId);
    }

    @Test
    void 인증된_계좌는_켜고_끌_수_있다() throws Exception {
        Long accountId = account("CONNECTED");

        toggle(accountId, true)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.autoExecutePlanned").value(true));
        assertThat(accounts.findById(accountId).orElseThrow().isAutoExecutePlanned()).isTrue();

        toggle(accountId, false)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.autoExecutePlanned").value(false));
    }

    /** 기본은 꺼짐이다. 실사용자 계좌에 자동으로 켜지는 경로가 있으면 안 된다. */
    @Test
    void 기본은_꺼져_있다() {
        assertThat(accounts.findById(account("CONNECTED")).orElseThrow().isAutoExecutePlanned())
                .isFalse();
    }

    /**
     * 인증 전 계좌는 <b>우리가 소유를 확인하지 못한 계좌</b>다. 거기에 돈을 넣고 빼면 안 된다 —
     * 동기화·예측 대상에서 빼는 것과 같은 기준이다(API 7.2).
     */
    @Test
    void 인증_전_계좌에는_켤_수_없다() throws Exception {
        toggle(account("PENDING_VERIFICATION"), true)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("ACCOUNT_NOT_VERIFIED"));
    }

    @Test
    void 남의_계좌는_건드릴_수_없다() throws Exception {
        Long otherUser = jdbc.queryForObject("""
                INSERT INTO core.users (email, password_hash, name) VALUES (?, 'x', '남') RETURNING id
                """, Long.class, "other-" + System.nanoTime() + "@test.local");
        Long otherBusiness = jdbc.queryForObject("""
                INSERT INTO core.business (owner_user_id, name) VALUES (?, '남의 가게') RETURNING id
                """, Long.class, otherUser);
        Long otherAccount = jdbc.queryForObject("""
                INSERT INTO core.account (business_id, bank_code, account_number_encrypted,
                                          account_number_hash, account_number_masked, name, status)
                VALUES (?, '999', 'enc', ?, '999-**-9999', '남의 계좌', 'CONNECTED') RETURNING id
                """, Long.class, otherBusiness, "hash-" + System.nanoTime());

        toggle(otherAccount, true).andExpect(status().isForbidden());
    }

    @Test
    void enabled_를_안_보내면_400_이다() throws Exception {
        mockMvc.perform(patch("/api/v1/accounts/" + account("CONNECTED") + "/planned-execution")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest());
    }

    /** 연동을 해제하면 함께 꺼진다 — 되살릴 때 다시 명시적으로 켜야 한다. */
    @Test
    void 연동을_해제하면_꺼진다() throws Exception {
        Long accountId = account("CONNECTED");
        toggle(accountId, true).andExpect(status().isOk());

        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .delete("/api/v1/accounts/" + accountId)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isNoContent());

        assertThat(accounts.findById(accountId).orElseThrow().isAutoExecutePlanned()).isFalse();
    }

    private org.springframework.test.web.servlet.ResultActions toggle(Long accountId, boolean enabled)
            throws Exception {
        return mockMvc.perform(patch("/api/v1/accounts/" + accountId + "/planned-execution")
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"enabled\":" + enabled + "}"));
    }

    private Long account(String status) {
        return jdbc.queryForObject("""
                INSERT INTO core.account (business_id, bank_code, account_number_encrypted,
                                          account_number_hash, account_number_masked, name, status)
                VALUES (?, '999', 'enc', ?, '999-**-1234', '주계좌', ?) RETURNING id
                """, Long.class, businessId, "hash-" + System.nanoTime(), status);
    }
}
