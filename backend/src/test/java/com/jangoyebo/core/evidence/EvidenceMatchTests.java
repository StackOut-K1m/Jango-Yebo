package com.jangoyebo.core.evidence;

import com.jangoyebo.NeedsDocker;
import com.jangoyebo.common.security.JwtTokenProvider;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 증빙 ↔ 거래 매칭과 메타 수정 (13.4·13.5).
 *
 * <p><b>여기서 보려는 것은 점수 자체가 아니라 자동 연결이 켜지는 조건</b>이다. 점수는 사람이
 * 고르는 것을 돕는 값이라 조금 틀려도 화면에서 걸러지지만, 자동 연결이 잘못 켜지면 사장님
 * 모르게 엉뚱한 거래에 영수증이 붙는다.
 *
 * <p><b>Docker가 필요하다.</b>
 */
@NeedsDocker
@SpringBootTest
@AutoConfigureMockMvc
class EvidenceMatchTests {

    private static final ZoneId KST = ZoneId.of("Asia/Seoul");
    private static final LocalDate ISSUED = LocalDate.of(2026, 8, 30);

    @Autowired private MockMvc mockMvc;
    @Autowired private JwtTokenProvider tokenProvider;
    @Autowired private JdbcTemplate jdbc;

    private Long businessId;
    private Long accountId;
    private Long evidenceId;
    private Long ownerId;
    private String token;

    @BeforeEach
    void setUp() {
        String unique = String.valueOf(System.nanoTime());
        String email = "match-" + unique + "@test.local";
        ownerId = jdbc.queryForObject("""
                INSERT INTO core.users (email, password_hash, name)
                VALUES (?, 'x', '매칭') RETURNING id
                """, Long.class, email);
        token = tokenProvider.createAccessToken(ownerId, email);
        businessId = jdbc.queryForObject("""
                INSERT INTO core.business (owner_user_id, name) VALUES (?, '매칭 사업장') RETURNING id
                """, Long.class, ownerId);
        accountId = jdbc.queryForObject("""
                INSERT INTO core.account
                    (business_id, bank_code, account_number_encrypted, account_number_hash,
                     account_number_masked, name)
                VALUES (?, '004', 'enc', ?, '004-***-1234', '주거래') RETURNING id
                """, Long.class, businessId, "hash-" + unique);

        // 발행일과 상호·금액이 추출로 채워진 증빙. 매칭의 입력이 이 셋이다.
        evidenceId = jdbc.queryForObject("""
                INSERT INTO core.evidence
                    (business_id, uploaded_by, evidence_type, original_filename, storage_key,
                     mime_type, file_size, processing_status, issued_on)
                VALUES (?, ?, 'RECEIPT', 'receipt.jpg', ?, 'image/jpeg', 100, 'EXTRACTED', ?)
                RETURNING id
                """, Long.class, businessId, ownerId, "evidences/" + unique + ".jpg", ISSUED);
        jdbc.update("""
                INSERT INTO core.evidence_extraction
                    (evidence_id, model_name, pipeline, status, header_data, is_current)
                VALUES (?, 'stub', 'STUB', 'COMPLETED', ?::jsonb, true)
                """, evidenceId, """
                {"supplierName": "(주)서울우유 광주대리점", "totalAmount": 156000}
                """);
    }

    /** 발행일 당일, 같은 금액, 적요가 상호를 품고 있으면 셋 다 걸린다. */
    @Test
    void scoresAmountDateAndMerchant() throws Exception {
        insertTransaction(ISSUED, "156000", "서울우유광주", "WITHDRAWAL");

        mockMvc.perform(get("/api/v1/evidences/{id}/match-candidates", evidenceId)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items[0].matchedBy",
                        org.hamcrest.Matchers.containsInAnyOrder(
                                "AMOUNT_EXACT", "DATE_SAME_DAY", "MERCHANT_SIMILAR")))
                .andExpect(jsonPath("$.data.items[0].score").value(1.0))
                .andExpect(jsonPath("$.data.items[0].autoLinkEligible").value(true));
    }

    /**
     * <b>같은 금액·같은 날 거래가 둘이면 자동 연결을 끈다.</b>
     *
     * <p>어느 쪽인지 서버가 알 방법이 없다. 이 안전망이 없으면 절반의 확률로 엉뚱한 거래에
     * 붙고, 사장님은 신고 직전에야 알게 된다.
     */
    @Test
    void turnsOffAutoLinkWhenTwoTransactionsTie() throws Exception {
        insertTransaction(ISSUED, "156000", "서울우유광주", "WITHDRAWAL");
        insertTransaction(ISSUED, "156000", "다른거래처", "WITHDRAWAL");

        mockMvc.perform(get("/api/v1/evidences/{id}/match-candidates", evidenceId)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items.length()").value(2))
                .andExpect(jsonPath("$.data.items[0].autoLinkEligible").value(false))
                .andExpect(jsonPath("$.data.items[1].autoLinkEligible").value(false));
    }

    /** 발행일을 아직 모르면 빈 목록이다 — 오류가 아니다(화면구성도 6.4). */
    @Test
    void returnsEmptyWhenTheIssuedDateIsUnknown() throws Exception {
        jdbc.update("UPDATE core.evidence SET issued_on = NULL WHERE id = ?", evidenceId);

        mockMvc.perform(get("/api/v1/evidences/{id}/match-candidates", evidenceId)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items.length()").value(0));
    }

    /** 창 밖 거래는 후보가 아니다. */
    @Test
    void excludesTransactionsOutsideTheWindow() throws Exception {
        insertTransaction(ISSUED.minusDays(10), "156000", "서울우유광주", "WITHDRAWAL");

        mockMvc.perform(get("/api/v1/evidences/{id}/match-candidates", evidenceId)
                        .header("Authorization", "Bearer " + token))
                .andExpect(jsonPath("$.data.items.length()").value(0));
    }

    @Test
    void linksAndUnlinks() throws Exception {
        Long txId = insertTransaction(ISSUED, "156000", "서울우유광주", "WITHDRAWAL");

        link(txId).andExpect(status().isOk());
        assertThat(linkCount()).isEqualTo(1);

        // 같은 거래에 다시 붙이는 것은 성공이다 — 화면이 두 번 눌렀을 뿐이다.
        link(txId).andExpect(status().isOk());
        assertThat(linkCount()).isEqualTo(1);

        mockMvc.perform(delete("/api/v1/evidences/{id}/link", evidenceId)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isNoContent());
        assertThat(linkCount()).isZero();

        // 붙어 있지 않아도 204 다.
        mockMvc.perform(delete("/api/v1/evidences/{id}/link", evidenceId)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isNoContent());
    }

    /** 다른 거래로 옮기려면 먼저 떼야 한다 — 조용히 옮기지 않는다. */
    @Test
    void rejectsLinkingToASecondTransaction() throws Exception {
        Long first = insertTransaction(ISSUED, "156000", "서울우유광주", "WITHDRAWAL");
        Long second = insertTransaction(ISSUED, "20000", "다른거래처", "WITHDRAWAL");

        link(first).andExpect(status().isOk());
        link(second)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("EVIDENCE_ALREADY_LINKED"));
    }

    /** 13.5 — 추출이 종류를 잘못 추정했을 때 사람이 고친다. */
    @Test
    void patchesTheEvidenceType() throws Exception {
        mockMvc.perform(patch("/api/v1/evidences/{id}", evidenceId)
                        .header("Authorization", "Bearer " + token)
                        .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                        .content("""
                { "evidenceType": "TAX_INVOICE" }
                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.evidenceType").value("TAX_INVOICE"));
    }


    // ------------------------------------------------------------------ 13.5 삭제

    /**
     * 소프트 삭제 — 목록·상세에서 사라지고 연결은 끊기지만, <b>수정 이력과 파일은 남는다.</b>
     *
     * <p>이력이 실사용 정확도를 얻는 유일한 원천이라 증빙과 함께 지우면 측정이 사라진다.
     */
    @Test
    void softDeletesKeepingTheExtraction() throws Exception {
        Long txId = insertTransaction(ISSUED, "156000", "서울우유광주", "WITHDRAWAL");
        link(txId).andExpect(status().isOk());

        mockMvc.perform(delete("/api/v1/evidences/{id}", evidenceId)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isNoContent());

        // 행은 남고 지운 흔적이 찍힌다.
        assertThat(jdbc.queryForObject(
                "SELECT deleted_at IS NOT NULL AND deleted_by IS NOT NULL FROM core.evidence WHERE id = ?",
                Boolean.class, evidenceId)).isTrue();

        // 추출은 그대로다 — 이력의 뿌리다.
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM core.evidence_extraction WHERE evidence_id = ?",
                Integer.class, evidenceId)).isEqualTo(1);

        // 거래 연결은 실제로 끊는다. 안 끊으면 화면이 "근거 있음"으로 센다.
        assertThat(linkCount()).isZero();

        // 상세는 404 다. 목록에서 사라졌는데 링크로 열리면 무엇을 믿을지 알 수 없다.
        mockMvc.perform(get("/api/v1/evidences/{id}", evidenceId)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isNotFound());
    }

    /** 두 번 눌러도 204 이고, 처음 지운 시각이 바뀌지 않는다. */
    @Test
    void deleteIsIdempotent() throws Exception {
        mockMvc.perform(delete("/api/v1/evidences/{id}", evidenceId)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isNoContent());
        Instant first = jdbc.queryForObject(
                "SELECT deleted_at FROM core.evidence WHERE id = ?", Instant.class, evidenceId);

        mockMvc.perform(delete("/api/v1/evidences/{id}", evidenceId)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isNoContent());

        assertThat(jdbc.queryForObject("SELECT deleted_at FROM core.evidence WHERE id = ?",
                Instant.class, evidenceId)).isEqualTo(first);
    }

    /** 지운 증빙은 목록에도 안 나온다. */
    @Test
    void hidesDeletedEvidenceFromTheList() throws Exception {
        mockMvc.perform(delete("/api/v1/evidences/{id}", evidenceId)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isNoContent());

        mockMvc.perform(get("/api/v1/businesses/{businessId}/evidences", businessId)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items.length()").value(0));
    }


    // ------------------------------------------------------------------ 8.7 자동 판정

    /** 증빙을 붙이면 검토 상태가 「증빙 확인 완료」가 된다 (8.7 자동 판정). */
    @Test
    void linkMarksTheTransactionAsEvidenceConfirmed() throws Exception {
        Long txId = insertTransaction(ISSUED, "156000", "서울우유광주", "WITHDRAWAL");

        link(txId).andExpect(status().isOk());

        assertThat(taxStatus(txId)).isEqualTo("EVIDENCE_CONFIRMED");
    }

    /**
     * <b>사람이 찍은 값은 증빙이 붙어도 유지된다</b> (8.7).
     *
     * <p>「세무사 확인 필요」라고 해 둔 것을 서버가 「증빙 확인 완료」로 바꾸면, 확인받지 않은
     * 건이 확인된 것으로 신고 자료에 나간다.
     */
    @Test
    void keepsUserChosenStatusWhenEvidenceArrives() throws Exception {
        Long txId = insertTransaction(ISSUED, "156000", "서울우유광주", "WITHDRAWAL");
        jdbc.update("UPDATE core.transaction SET tax_review_status = 'EXPERT_REVIEW' WHERE id = ?",
                txId);

        link(txId).andExpect(status().isOk());

        assertThat(taxStatus(txId)).isEqualTo("EXPERT_REVIEW");
    }

    /**
     * <b>거래에 증빙이 여럿 붙어 있으면 하나를 떼도 「확인 완료」가 유지된다</b> (8.7).
     *
     * <p>하나 뗐다고 「증빙 누락」으로 바꾸면 아직 붙어 있는 다른 영수증이 없는 것처럼 보인다.
     */
    @Test
    void marksEvidenceMissingOnlyWhenNoEvidenceRemains() throws Exception {
        Long txId = insertTransaction(ISSUED, "156000", "서울우유광주", "WITHDRAWAL");
        Long second = anotherEvidence();

        link(txId).andExpect(status().isOk());
        jdbc.update("""
                INSERT INTO core.transaction_evidence
                    (transaction_id, evidence_id, match_status, confirmed_by, confirmed_at)
                VALUES (?, ?, 'LINKED', ?, now())
                """, txId, second, ownerId);

        // 첫 증빙만 뗀다 — 둘째가 남아 있으므로 확인 완료가 유지돼야 한다.
        mockMvc.perform(delete("/api/v1/evidences/{id}/link", evidenceId)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isNoContent());
        assertThat(taxStatus(txId)).isEqualTo("EVIDENCE_CONFIRMED");

        // 둘째까지 떼면 그때 누락이다.
        mockMvc.perform(delete("/api/v1/evidences/{id}/link", second)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isNoContent());
        assertThat(taxStatus(txId)).isEqualTo("EVIDENCE_MISSING");
    }

    private String taxStatus(Long transactionId) {
        return jdbc.queryForObject("SELECT tax_review_status FROM core.transaction WHERE id = ?",
                String.class, transactionId);
    }

    private Long anotherEvidence() {
        String unique = String.valueOf(System.nanoTime());
        return jdbc.queryForObject("""
                INSERT INTO core.evidence
                    (business_id, uploaded_by, evidence_type, original_filename, storage_key,
                     mime_type, file_size, processing_status, issued_on)
                VALUES (?, ?, 'RECEIPT', 'r2.jpg', ?, 'image/jpeg', 100, 'EXTRACTED', ?)
                RETURNING id
                """, Long.class, businessId, ownerId, "evidences/" + unique + ".jpg", ISSUED);
    }

    // ------------------------------------------------------------------ 도우미

    private org.springframework.test.web.servlet.ResultActions link(Long transactionId)
            throws Exception {
        return mockMvc.perform(post("/api/v1/evidences/{id}/link", evidenceId)
                .header("Authorization", "Bearer " + token)
                .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                .content("{ \"transactionId\": " + transactionId + " }"));
    }

    private Integer linkCount() {
        return jdbc.queryForObject(
                "SELECT count(*) FROM core.transaction_evidence WHERE evidence_id = ?",
                Integer.class, evidenceId);
    }

    private Long insertTransaction(LocalDate date, String amount, String description,
                                   String flow) {
        Instant at = date.atTime(10, 0).atZone(KST).toInstant();
        return jdbc.queryForObject("""
                INSERT INTO core.transaction
                    (business_id, account_id, source_type, occurred_at, transaction_date,
                     flow_type, amount, description, deduplication_key)
                VALUES (?, ?, 'FIN_API', ?, ?, ?, ?::numeric, ?, ?) RETURNING id
                """, Long.class, businessId, accountId, java.sql.Timestamp.from(at),
                java.sql.Date.valueOf(date), flow, amount, description,
                "dedup-" + System.nanoTime());
    }
}
