package com.jangoyebo.core.safety;

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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** 운영자금 안전선 (API 12.1). */
@NeedsDocker
@SpringBootTest
@AutoConfigureMockMvc
class SafetyLineTests {

    @Autowired private MockMvc mockMvc;
    @Autowired private JwtTokenProvider tokenProvider;
    @Autowired private JdbcTemplate jdbc;

    private Long businessId;
    private String token;

    @BeforeEach
    void setUp() {
        String email = "safety-" + System.nanoTime() + "@test.local";
        Long userId = jdbc.queryForObject("""
                INSERT INTO core.users (email, password_hash, name)
                VALUES (?, 'x', '안전선') RETURNING id
                """, Long.class, email);
        token = tokenProvider.createAccessToken(userId, email);
        businessId = jdbc.queryForObject("""
                INSERT INTO core.business (owner_user_id, name) VALUES (?, '가게') RETURNING id
                """, Long.class, userId);
    }

    /**
     * 미설정은 오류가 아니다.
     *
     * <p>404 로 주면 프론트가 예외 경로로 다뤄야 한다 — 화면구성도 6.4 가 빈 상태를
     * throw 하지 않기로 정했다. {@code non_null} 정책 때문에 키 자체가 빠진다.
     */
    @Test
    void returnsEmptyWhenNotConfigured() throws Exception {
        getLine()
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.amount").doesNotExist())
                .andExpect(jsonPath("$.data.method").doesNotExist());
    }

    @Test
    void createsThenReadsBack() throws Exception {
        putLine("""
                {"amount": 2000000, "method": "FIXED_COST_BASED", "autoUpdate": true}
                """)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.amount").value(2000000))
                .andExpect(jsonPath("$.data.method").value("FIXED_COST_BASED"))
                .andExpect(jsonPath("$.data.autoUpdate").value(true));

        getLine()
                .andExpect(jsonPath("$.data.amount").value(2000000))
                .andExpect(jsonPath("$.data.updatedAt").exists());
    }

    /** PUT 은 전체 교체다. 사업장당 한 행이라 두 번 불러도 행이 늘지 않아야 한다. */
    @Test
    void replacesInsteadOfInserting() throws Exception {
        putLine("{\"amount\": 1000000}").andExpect(status().isOk());
        putLine("""
                {"amount": 3000000, "method": "MANUAL", "autoUpdate": false}
                """).andExpect(status().isOk());

        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM core.safety_balance_policy WHERE business_id = ?",
                Integer.class, businessId)).isEqualTo(1);

        getLine().andExpect(jsonPath("$.data.amount").value(3000000));
    }

    /** method 를 생략하면 MANUAL 이다 — 직접 입력이 기본이고 나머지는 산출 근거 표시용이다. */
    @Test
    void defaultsMethodToManual() throws Exception {
        putLine("{\"amount\": 500000}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.method").value("MANUAL"))
                .andExpect(jsonPath("$.data.autoUpdate").value(false));
    }

    @Test
    void rejectsNegativeAmount() throws Exception {
        putLine("{\"amount\": -1}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"));
    }

    @Test
    void rejectsMissingAmount() throws Exception {
        putLine("{\"method\": \"MANUAL\"}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"));
    }

    /** 남의 사업장은 @CurrentBusiness 가 막는다. */
    @Test
    void rejectsOtherBusiness() throws Exception {
        Long otherOwner = jdbc.queryForObject("""
                INSERT INTO core.users (email, password_hash, name)
                VALUES (?, 'x', '남') RETURNING id
                """, Long.class, "other-" + System.nanoTime() + "@test.local");
        Long otherBusiness = jdbc.queryForObject("""
                INSERT INTO core.business (owner_user_id, name) VALUES (?, '남의 가게') RETURNING id
                """, Long.class, otherOwner);

        mockMvc.perform(get("/api/v1/businesses/" + otherBusiness + "/safety-line")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isForbidden());
    }

    private ResultActions getLine() throws Exception {
        return mockMvc.perform(get("/api/v1/businesses/" + businessId + "/safety-line")
                .header("Authorization", "Bearer " + token));
    }

    private ResultActions putLine(String body) throws Exception {
        return mockMvc.perform(put("/api/v1/businesses/" + businessId + "/safety-line")
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body));
    }
}
