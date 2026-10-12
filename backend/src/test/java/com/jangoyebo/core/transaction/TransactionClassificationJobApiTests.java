package com.jangoyebo.core.transaction;

import com.jangoyebo.NeedsDocker;
import com.jangoyebo.common.security.JwtTokenProvider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * LLM 분류 수동 재실행 (API 8.5 · 화면구성도 SC-04 「AI 분류 재실행」).
 *
 * <p>자동 실행이 놓친 것을 회수하는 문이다 — 워커가 죽어 있던 동안, 이어달리기가 진전 없이
 * 멈춘 뒤, 계좌를 되살린 뒤. 그 구간의 거래는 다음 수집이 있을 때까지 미분류로 남는다.
 *
 * <p><b>Docker가 필요하다.</b>
 */
@NeedsDocker
@SpringBootTest
@AutoConfigureMockMvc
class TransactionClassificationJobApiTests {

    @Autowired private MockMvc mockMvc;
    @Autowired private JwtTokenProvider tokenProvider;
    @Autowired private JdbcTemplate jdbc;

    private Long businessId;
    private String token;

    @BeforeEach
    void setUp() {
        String unique = String.valueOf(System.nanoTime());
        String email = "rerun-" + unique + "@test.local";
        Long userId = jdbc.queryForObject("""
                INSERT INTO core.users (email, password_hash, name)
                VALUES (?, 'x', '재실행') RETURNING id
                """, Long.class, email);
        token = tokenProvider.createAccessToken(userId, email);
        businessId = jdbc.queryForObject("""
                INSERT INTO core.business (owner_user_id, name) VALUES (?, '재실행 사업장') RETURNING id
                """, Long.class, userId);
    }

    private org.springframework.test.web.servlet.ResultActions requestClassification(Long target)
            throws Exception {
        return mockMvc.perform(post("/api/v1/businesses/" + target + "/classification-jobs")
                .header("Authorization", "Bearer " + token));
    }

    private org.springframework.test.web.servlet.ResultActions currentClassificationJob(Long target)
            throws Exception {
        return mockMvc.perform(get("/api/v1/businesses/" + target + "/classification-jobs/current")
                .header("Authorization", "Bearer " + token));
    }

    private int jobCount() {
        return jdbc.queryForObject("""
                SELECT count(*) FROM core.job
                 WHERE business_id = ? AND job_type = 'TRANSACTION_CLASSIFICATION'
                """, Integer.class, businessId);
    }

    /** 기준선 — 작업이 큐에 들어가고 202 와 함께 그 작업이 나온다. */
    @Test
    void enqueuesJobAndReturnsAccepted() throws Exception {
        requestClassification(businessId)
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.data.jobType").value("TRANSACTION_CLASSIFICATION"))
                .andExpect(jsonPath("$.data.status").value("QUEUED"))
                .andExpect(jsonPath("$.data.jobId").isNumber());

        assertThat(jobCount()).isEqualTo(1);
    }

    /**
     * <b>이미 도는 것이 있으면 그것을 돌려준다.</b> 409 가 아니다.
     *
     * <p>버튼을 두 번 눌러도 사용자가 보려는 것은 같은 진행률이다. 새로 넣으면 같은 거래를
     * 두 번 분류해 값이 두 번 나간다(합의 ④).
     */
    @Test
    void returnsTheRunningJobInsteadOfCreatingAnother() throws Exception {
        Long running = jdbc.queryForObject("""
                INSERT INTO core.job (business_id, job_type, status)
                VALUES (?, 'TRANSACTION_CLASSIFICATION', 'PROCESSING') RETURNING id
                """, Long.class, businessId);

        requestClassification(businessId)
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.data.jobId").value(running))
                .andExpect(jsonPath("$.data.status").value("PROCESSING"));

        assertThat(jobCount()).isEqualTo(1);
    }

    /** 끝난 작업은 막지 않는다 — 재실행은 그러라고 있는 버튼이다. */
    @Test
    void enqueuesAgainAfterThePreviousOneFinished() throws Exception {
        jdbc.update("""
                INSERT INTO core.job (business_id, job_type, status)
                VALUES (?, 'TRANSACTION_CLASSIFICATION', 'COMPLETED')
                """, businessId);

        requestClassification(businessId)
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.data.status").value("QUEUED"));

        assertThat(jobCount()).isEqualTo(2);
    }

    // --- 8.5.1 진행 중 분류 작업 조회 -------------------------------------------------

    /**
     * <b>도는 것이 없어도 200 이다.</b> 404 가 아니다.
     *
     * <p>「분류가 안 돌고 있다」는 오류가 아니라 정상 상태다(화면구성도 6.4). 404 로 주면
     * 화면이 「기능 없음」과 구분하지 못한다. {@code job} 은 키 자체가 없다.
     */
    @Test
    void reportsNothingRunningWithoutAJobKey() throws Exception {
        currentClassificationJob(businessId)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.running").value(false))
                .andExpect(jsonPath("$.data.job").doesNotExist());
    }

    /** 도는 것이 있으면 그 작업을 준다. 화면이 돌아왔을 때 진행 표시를 되살리는 근거다. */
    @Test
    void reportsTheRunningJob() throws Exception {
        Long running = jdbc.queryForObject("""
                INSERT INTO core.job (business_id, job_type, status)
                VALUES (?, 'TRANSACTION_CLASSIFICATION', 'PROCESSING') RETURNING id
                """, Long.class, businessId);

        currentClassificationJob(businessId)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.running").value(true))
                .andExpect(jsonPath("$.data.job.jobId").value(running))
                .andExpect(jsonPath("$.data.job.status").value("PROCESSING"))
                .andExpect(jsonPath("$.data.job.createdAt").exists());
    }

    /** 큐에 든 것도 「도는 중」이다 — 워커가 아직 안 집었을 뿐이다. */
    @Test
    void countsQueuedAsRunning() throws Exception {
        jdbc.update("""
                INSERT INTO core.job (business_id, job_type, status)
                VALUES (?, 'TRANSACTION_CLASSIFICATION', 'QUEUED')
                """, businessId);

        currentClassificationJob(businessId)
                .andExpect(jsonPath("$.data.running").value(true))
                .andExpect(jsonPath("$.data.job.status").value("QUEUED"));
    }

    /** 끝난 작업은 도는 것이 아니다. 안 그러면 한 번 돌린 뒤 영영 「진행 중」으로 보인다. */
    @Test
    void ignoresFinishedJobs() throws Exception {
        jdbc.update("""
                INSERT INTO core.job (business_id, job_type, status)
                VALUES (?, 'TRANSACTION_CLASSIFICATION', 'COMPLETED')
                """, businessId);

        currentClassificationJob(businessId)
                .andExpect(jsonPath("$.data.running").value(false))
                .andExpect(jsonPath("$.data.job").doesNotExist());
    }

    /**
     * <b>조회가 작업을 만들지 않는다.</b> 이 엔드포인트가 따로 있는 이유가 이것이다 —
     * 8.5 POST 로 대신하면 화면에 들어올 때마다 분류가 시작되고 LLM 값이 나간다.
     */
    @Test
    void doesNotEnqueueAnything() throws Exception {
        currentClassificationJob(businessId).andExpect(status().isOk());
        currentClassificationJob(businessId).andExpect(status().isOk());

        assertThat(jobCount()).isZero();
    }

    /** 남의 사업장 작업은 들여다볼 수 없다. */
    @Test
    void rejectsAnotherUsersBusinessOnRead() throws Exception {
        currentClassificationJob(otherUsersBusiness())
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("BUSINESS_ACCESS_DENIED"));
    }

    /** 남의 사업장에는 걸 수 없다. 값이 나가는 작업이라 더 그렇다. */
    @Test
    void rejectsAnotherUsersBusiness() throws Exception {
        requestClassification(otherUsersBusiness())
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("BUSINESS_ACCESS_DENIED"));
    }

    private Long otherUsersBusiness() {
        Long otherUserId = jdbc.queryForObject("""
                INSERT INTO core.users (email, password_hash, name)
                VALUES (?, 'x', '남') RETURNING id
                """, Long.class, "other-" + System.nanoTime() + "@test.local");
        return jdbc.queryForObject("""
                INSERT INTO core.business (owner_user_id, name) VALUES (?, '남의 사업장') RETURNING id
                """, Long.class, otherUserId);
    }
}
