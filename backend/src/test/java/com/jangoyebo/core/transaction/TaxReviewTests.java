package com.jangoyebo.core.transaction;

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

import java.time.Instant;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 종합소득세 검토 상태 (8.7).
 *
 * <p><b>여기서 지키려는 것은 「서비스가 세무 판단을 대신하지 않는다」</b>이다(도메인 규칙 4,
 * SRS 1.3 제외 범위). 자동으로 정하는 것은 증빙이 붙어 있나 하나뿐이고, 사람이 찍은 값은
 * 서버가 덮어쓰지 않는다.
 *
 * <p><b>Docker가 필요하다.</b>
 */
@NeedsDocker
@SpringBootTest
@AutoConfigureMockMvc
class TaxReviewTests {

    @Autowired private MockMvc mockMvc;
    @Autowired private JwtTokenProvider tokenProvider;
    @Autowired private JdbcTemplate jdbc;

    private Long businessId;
    private Long otherBusinessId;
    private Long accountId;
    private Long ownerId;
    private String token;

    @BeforeEach
    void setUp() {
        String unique = String.valueOf(System.nanoTime());
        String email = "tax-" + unique + "@test.local";
        ownerId = jdbc.queryForObject("""
                INSERT INTO core.users (email, password_hash, name)
                VALUES (?, 'x', '세무') RETURNING id
                """, Long.class, email);
        token = tokenProvider.createAccessToken(ownerId, email);
        businessId = jdbc.queryForObject("""
                INSERT INTO core.business (owner_user_id, name) VALUES (?, '세무 사업장') RETURNING id
                """, Long.class, ownerId);
        otherBusinessId = jdbc.queryForObject("""
                INSERT INTO core.business (owner_user_id, name) VALUES (?, '남의 사업장') RETURNING id
                """, Long.class, ownerId);
        accountId = jdbc.queryForObject("""
                INSERT INTO core.account
                    (business_id, bank_code, account_number_encrypted, account_number_hash,
                     account_number_masked, name)
                VALUES (?, '004', 'enc', ?, '004-***-1234', '주거래') RETURNING id
                """, Long.class, businessId, "hash-" + unique);
    }

    /** 사람이 찍은 값은 그대로 저장된다. 메모와 시각이 함께 남는다. */
    @Test
    void bulkUpdatesUserChosenStatus() throws Exception {
        Long a = transaction(businessId);
        Long b = transaction(businessId);

        bulk("[" + a + "," + b + "]", "EXPERT_REVIEW", "세무사 확인 요청")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.updatedCount").value(2))
                .andExpect(jsonPath("$.data.failedCount").value(0));

        assertThat(statusOf(a)).isEqualTo("EXPERT_REVIEW");
        assertThat(jdbc.queryForObject(
                "SELECT tax_review_note FROM core.transaction WHERE id = ?", String.class, a))
                .isEqualTo("세무사 확인 요청");
        assertThat(jdbc.queryForObject(
                "SELECT tax_reviewed_at IS NOT NULL FROM core.transaction WHERE id = ?",
                Boolean.class, a)).isTrue();
    }

    /**
     * <b>남의 사업장 거래가 섞여 있어도 통째로 거절하지 않는다.</b>
     *
     * <p>200건을 고른 사람에게 "하나가 이상하니 처음부터 다시"라고 하면 그 화면은 쓰이지 않는다.
     */
    @Test
    void appliesWhatItCanAndReportsTheRest() throws Exception {
        Long mine = transaction(businessId);
        Long theirs = transaction(otherBusinessId);

        bulk("[" + mine + "," + theirs + "]", "PERSONAL_SUSPECTED", null)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.updatedCount").value(1))
                .andExpect(jsonPath("$.data.failedCount").value(1))
                .andExpect(jsonPath("$.data.failed[0].transactionId").value(theirs))
                .andExpect(jsonPath("$.data.failed[0].reason").value("TRANSACTION_NOT_FOUND"));

        assertThat(statusOf(mine)).isEqualTo("PERSONAL_SUSPECTED");
        assertThat(statusOf(theirs)).isEqualTo("UNREVIEWED");
    }

    /**
     * 증빙 확인 여부는 사람이 찍는 값이 아니다 (8.7 판정 주체 = 자동).
     *
     * <p>손으로 찍게 두면 증빙이 없는데도 「확인 완료」가 되어 내보내기의 검토 상태 열이
     * 거짓말을 한다.
     */
    @Test
    void rejectsAutomaticValues() throws Exception {
        Long tx = transaction(businessId);

        bulk("[" + tx + "]", "EVIDENCE_CONFIRMED", null)
                .andExpect(status().isBadRequest());
        bulk("[" + tx + "]", "EVIDENCE_MISSING", null)
                .andExpect(status().isBadRequest());

        assertThat(statusOf(tx)).isEqualTo("UNREVIEWED");
    }

    /** 201건은 거절한다 — 한 화면에서 고를 수 있는 상한이 200 이다. */
    @Test
    void rejectsMoreThanTwoHundred() throws Exception {
        String ids = IntStream.rangeClosed(1, 201).mapToObj(String::valueOf)
                .collect(Collectors.joining(",", "[", "]"));

        bulk(ids, "BUSINESS_USE_UNCLEAR", null).andExpect(status().isBadRequest());
    }

    /** 모르는 값은 거절한다 — 조용히 무시하면 안 바뀐 채로 저장됐다고 보인다. */
    @Test
    void rejectsUnknownStatus() throws Exception {
        Long tx = transaction(businessId);

        bulk("[" + tx + "]", "MAYBE_LATER", null).andExpect(status().isBadRequest());
    }

    // ------------------------------------------------------------------ 도우미

    private org.springframework.test.web.servlet.ResultActions bulk(String ids, String status,
                                                                    String note) throws Exception {
        String body = "{ \"transactionIds\": " + ids + ", \"taxReviewStatus\": \"" + status + "\""
                + (note == null ? "" : ", \"taxReviewNote\": \"" + note + "\"") + " }";
        return mockMvc.perform(post("/api/v1/businesses/{id}/tax-review/bulk", businessId)
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body));
    }

    private String statusOf(Long transactionId) {
        return jdbc.queryForObject("SELECT tax_review_status FROM core.transaction WHERE id = ?",
                String.class, transactionId);
    }

    private Long transaction(Long ownerBusinessId) {
        return jdbc.queryForObject("""
                INSERT INTO core.transaction
                    (business_id, account_id, source_type, occurred_at, transaction_date,
                     flow_type, amount, description, deduplication_key)
                VALUES (?, ?, 'FIN_API', ?, CURRENT_DATE, 'WITHDRAWAL', 10000, '거래', ?)
                RETURNING id
                """, Long.class, ownerBusinessId, accountId,
                java.sql.Timestamp.from(Instant.now()), "dedup-" + System.nanoTime());
    }
}
