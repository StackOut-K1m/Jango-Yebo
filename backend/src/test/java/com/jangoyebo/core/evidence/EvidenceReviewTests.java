package com.jangoyebo.core.evidence;

import com.jangoyebo.NeedsDocker;
import com.jangoyebo.common.security.JwtTokenProvider;
import com.jangoyebo.core.job.JobService;
import com.jangoyebo.core.job.JobType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 증빙 목록·상세·검토 (API 13.2~13.3, P1-02).
 *
 * <p><b>여기서 확인하는 핵심은 {@code EVIDENCE_CORRECTION} 이다.</b> 테이블은 {@code V6} 에
 * 있었는데 INSERT 하는 코드가 없어, 그 사이 확정된 추출들의 수정 이력이 <b>영영 비어 있게</b>
 * 된다(합의 ⑦). 그건 실사용 정확도의 분자다 — 나중에 넣어도 그 구간은 복구되지 않는다.
 *
 * <p>분모도 함께 본다: <b>고친 것이 없어도 확인은 기록돼야 한다.</b> 안 그러면 "고칠 것이
 * 없던 추출"과 "아직 안 본 추출"이 구분되지 않아 정확도가 실제보다 높게 나온다.
 *
 * <p><b>Docker가 필요하다.</b>
 */
@NeedsDocker
@Import(EvidenceReviewTests.StubValidator.class)
@SpringBootTest
@AutoConfigureMockMvc
class EvidenceReviewTests {

    /**
     * 검산 재실행을 가로챈다.
     *
     * <p>진짜 워커를 부르면 이 테스트가 워커 기동에 묶인다. 실패해도 검토 저장이 되는 것은
     * 그것대로 중요한 성질이지만(fail-soft), 여기서 보려는 것은 <b>결과가 왔을 때 헤더에
     * 반영되는가</b> 다.
     */
    @TestConfiguration(proxyBeanMethods = false)
    static class StubValidator {
        @Bean
        @Primary
        ExtractionValidator stubExtractionValidator() {
            return extraction -> Map.of("needsReview", false, "reviewReasons", List.of(),
                    "amountsConsistent", true, "revalidated", true);
        }
    }

    @Autowired private MockMvc mockMvc;
    @Autowired private JwtTokenProvider tokenProvider;
    @Autowired private EvidenceExtractionService extractionService;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private JobService jobService;

    private Long businessId;
    private Long evidenceId;
    private Long ownerId;
    private String token;

    @BeforeEach
    void setUp() {
        String unique = String.valueOf(System.nanoTime());
        String email = "rev-" + unique + "@test.local";
        ownerId = jdbc.queryForObject("""
                INSERT INTO core.users (email, password_hash, name)
                VALUES (?, 'x', '검토자') RETURNING id
                """, Long.class, email);
        token = tokenProvider.createAccessToken(ownerId, email);
        businessId = jdbc.queryForObject("""
                INSERT INTO core.business (owner_user_id, name) VALUES (?, '검토 사업장') RETURNING id
                """, Long.class, ownerId);
        evidenceId = jdbc.queryForObject("""
                INSERT INTO core.evidence
                    (business_id, uploaded_by, evidence_type, original_filename,
                     storage_key, mime_type, file_size, processing_status)
                VALUES (?, ?, 'OTHER', 'receipt.jpg', ?, 'image/jpeg', 100, 'QUEUED')
                RETURNING id
                """, Long.class, businessId, ownerId, "evidences/" + unique + ".jpg");

        // 워커 콜백을 그대로 태워 EXTRACTED 상태를 만든다. 손으로 INSERT 하면 계약과 갈라진다.
        extractionService.apply(jobService.enqueue(businessId, ownerId, JobType.EVIDENCE_EXTRACTION, Map.of("evidenceId", evidenceId)), callback());
    }

    /** 합의 문서 부록 B 와 같은 모양. 검산이 걸린 건이라 {@code needsReview} 가 true 다. */
    private Map<String, Object> callback() {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("rawName", "서울우유 1L");
        item.put("quantity", 40);
        item.put("unit", "개");
        item.put("unitPrice", 2900);
        item.put("amount", 116000);

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("evidenceId", evidenceId);
        data.put("modelName", "stub");
        data.put("pipeline", "STUB");
        data.put("status", "COMPLETED");
        data.put("supplierName", "서울우유 광주대리점");
        data.put("supplierBusinessNo", "123-45-67890");
        data.put("issuedDate", "2026-08-30");
        data.put("totalAmount", 156000);
        data.put("vatAmount", 14182);
        data.put("items", List.of(item));
        data.put("fieldConfidence", Map.of("supplierName", 0.95, "totalAmount", 0.99));
        data.put("validation", Map.of("needsReview", true,
                "reviewReasons", List.of("ITEMS_SUM_INCONSISTENT")));
        return data;
    }

    // ------------------------------------------------------------------ 13.2

    @Test
    void detailShowsTheExtraction() throws Exception {
        mockMvc.perform(get("/api/v1/evidences/{id}", evidenceId)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.ocrStatus").value("EXTRACTED"))
                .andExpect(jsonPath("$.data.extraction.supplierName").value("서울우유 광주대리점"))
                .andExpect(jsonPath("$.data.extraction.totalAmount").value(156000))
                .andExpect(jsonPath("$.data.extraction.items[0].rawName").value("서울우유 1L"))
                // ② 순액은 저장하지 않고 계산해 내보낸다. 할인이 없으면 금액 그대로다.
                .andExpect(jsonPath("$.data.extraction.items[0].netAmount").value(116000))
                .andExpect(jsonPath("$.data.extraction.fieldConfidence.totalAmount").value(0.99))
                .andExpect(jsonPath("$.data.extraction.validation.needsReview").value(true));
    }

    /**
     * <b>좌표는 나가지 않는다.</b> P1-02 완료 기준이 「필드 강조」로 바뀐 근거가 이것이다 —
     * 값은 99% 맞는데 박스는 중심 적중 33% 라 틀린 하이라이트가 된다.
     */
    @Test
    void detailNeverCarriesBoundingBoxes() throws Exception {
        mockMvc.perform(get("/api/v1/evidences/{id}", evidenceId)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.extraction.items[0].boundingBox").doesNotExist());
    }

    /** 목록은 검토 화면이 쓰는 {@code needsReview} 를 함께 싣는다. */
    @Test
    void listCarriesTheReviewSignal() throws Exception {
        mockMvc.perform(get("/api/v1/businesses/{id}/evidences", businessId)
                        .param("ocrStatus", "EXTRACTED")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items[0].evidenceId").value(evidenceId))
                .andExpect(jsonPath("$.data.items[0].needsReview").value(true))
                .andExpect(jsonPath("$.data.items[0].totalAmount").value(156000))
                .andExpect(jsonPath("$.data.items[0].linked").value(false));
    }

    /**
     * <b>검토 화면의 「먼저 볼 곳」이다.</b> {@code needsReview} 가 {@code evidence} 의 컬럼이라
     * {@code Pageable} 의 정렬로 그대로 먹는다({@code V9}). 컬럼이 아니면 JSONB 안이라
     * {@code ORDER BY} 를 걸 수 없어 페이지 단위로 갈라진다.
     */
    @Test
    void sortsReviewNeededFirst() throws Exception {
        Long clean = anotherExtractedEvidence(false);

        mockMvc.perform(get("/api/v1/businesses/{id}/evidences", businessId)
                        .param("ocrStatus", "EXTRACTED")
                        .param("sort", "needsReview,desc")
                        .param("sort", "createdAt,desc")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items[0].evidenceId").value(evidenceId))
                .andExpect(jsonPath("$.data.items[1].evidenceId").value(clean));
    }

    /** 같은 컬럼이 필터도 된다 — 증빙함의 「확인 필요」 칩. */
    @Test
    void filtersByReviewFlag() throws Exception {
        anotherExtractedEvidence(false);

        mockMvc.perform(get("/api/v1/businesses/{id}/evidences", businessId)
                        .param("needsReview", "true")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.totalElements").value(1))
                .andExpect(jsonPath("$.data.items[0].evidenceId").value(evidenceId));
    }

    /**
     * <b>{@code null} 은 {@code false} 가 아니다.</b> 아직 추출 결과가 없다는 뜻이라 어느 쪽
     * 필터에도 걸리면 안 된다 — 걸리면 업로드 직후의 증빙이 "검산을 통과했다"로 읽힌다.
     */
    @Test
    void treatsNotYetExtractedAsNeitherTrueNorFalse() throws Exception {
        Long queued = uploadedEvidence();

        mockMvc.perform(get("/api/v1/businesses/{id}/evidences", businessId)
                        .param("needsReview", "false")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items[?(@.evidenceId == %d)]".formatted(queued))
                        .isEmpty());
    }

    /** 금액을 바로잡았는데 목록이 계속 "확인 필요"로 올리면 안 된다 — 정렬 키도 같이 옮긴다. */
    @Test
    void clearsTheSortKeyWhenRevalidationPasses() throws Exception {
        assertThat(reviewFlag(evidenceId)).isTrue();

        patchExtraction("""
                { "totalAmount": 155000 }
                """).andExpect(status().isOk());

        assertThat(reviewFlag(evidenceId))
                .as("스텁 검산이 needsReview=false 를 돌려준다")
                .isFalse();
    }

    @Test
    void listRejectsAnUnknownStatusInsteadOfIgnoringIt() throws Exception {
        mockMvc.perform(get("/api/v1/businesses/{id}/evidences", businessId)
                        .param("ocrStatus", "REVIEW")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isBadRequest());
    }

    // ------------------------------------------------------------------ 13.3

    /** <b>이 테스트가 이 파일의 이유다.</b> 고친 값이 필드별로 남아야 한다. */
    @Test
    void recordsWhatTheUserChanged() throws Exception {
        patchExtraction("""
                { "totalAmount": 155000, "supplierName": "서울우유 광주지점", "confirmed": true }
                """).andExpect(status().isOk());

        assertThat(corrections()).containsExactlyInAnyOrder(
                Map.of("field", "totalAmount", "before", "156000", "after", "155000"),
                Map.of("field", "supplierName",
                        "before", "서울우유 광주대리점", "after", "서울우유 광주지점"));
    }

    /**
     * <b>안 바뀐 것은 넣지 않는다.</b> 넣으면 분자가 부풀어 실사용 정확도가 실제보다 낮게
     * 나온다. 화면이 폼 전체를 되돌려 보내는 것이 정상 동선이라 이 경우가 흔하다.
     */
    @Test
    void doesNotRecordUnchangedFields() throws Exception {
        patchExtraction("""
                { "totalAmount": 156000, "supplierName": "서울우유 광주대리점", "confirmed": true }
                """).andExpect(status().isOk());

        assertThat(corrections()).isEmpty();
    }

    /**
     * <b>고친 것이 없어도 확인은 기록한다</b>(합의 ⑦). 이 값이 실사용 정확도의 분모다 —
     * 없으면 "고칠 것이 없던 추출"과 "아직 안 본 추출"이 구분되지 않는다.
     */
    @Test
    void recordsTheConfirmationEvenWithNoCorrections() throws Exception {
        patchExtraction("""
                { "confirmed": true }
                """).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.ocrStatus").value("CONFIRMED"));

        assertThat(jdbc.queryForObject("""
                SELECT confirmed_at IS NOT NULL AND confirmed_by IS NOT NULL
                  FROM core.evidence_extraction WHERE evidence_id = ? AND is_current
                """, Boolean.class, evidenceId)).isTrue();
    }

    /** 품목 이력의 키는 {@code items[N].field} 다 — 워커 분석기가 그 모양을 전제한다. */
    @Test
    void recordsItemEditsWithTheRowIndex() throws Exception {
        Long itemId = jdbc.queryForObject("""
                SELECT i.id FROM core.evidence_item i
                  JOIN core.evidence_extraction e ON e.id = i.extraction_id
                 WHERE e.evidence_id = ?
                """, Long.class, evidenceId);

        patchExtraction("""
                { "items": [ { "extractionItemId": %d, "amount": 115000, "quantity": 39 } ] }
                """.formatted(itemId)).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.extraction.items[0].amount").value(115000));

        assertThat(corrections()).containsExactlyInAnyOrder(
                Map.of("field", "items[0].amount", "before", "116000", "after", "115000"),
                Map.of("field", "items[0].quantity", "before", "40", "after", "39"));
    }

    /**
     * PATCH 는 <b>보낸 필드만</b> 바꾼다. null 을 지움으로 읽으면 화면이 수량만 고쳐 보냈을 때
     * 모델이 제대로 읽은 품목명이 저장에서 사라진다.
     */
    @Test
    void leavesFieldsThatWereNotSent() throws Exception {
        patchExtraction("""
                { "totalAmount": 155000 }
                """).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.extraction.supplierName").value("서울우유 광주대리점"))
                .andExpect(jsonPath("$.data.extraction.items[0].rawName").value("서울우유 1L"));
    }

    /** 발행일을 바로잡으면 {@code evidence.issued_on} 도 따라가야 기간 필터가 맞는다. */
    @Test
    void movesTheIssuedDateColumnToo() throws Exception {
        patchExtraction("""
                { "issuedDate": "2026-08-29" }
                """).andExpect(status().isOk());

        assertThat(jdbc.queryForObject("""
                SELECT issued_on FROM core.evidence WHERE id = ?
                """, java.time.LocalDate.class, evidenceId))
                .isEqualTo(java.time.LocalDate.of(2026, 8, 29));
        assertThat(corrections())
                .containsExactly(Map.of("field", "issuedDate",
                        "before", "2026-08-30", "after", "2026-08-29"));
    }

    /** 고친 값으로 검산을 다시 돌린다. 안 그러면 바로잡았는데도 옛 경고가 남는다. */
    @Test
    void revalidatesWithTheCorrectedValues() throws Exception {
        patchExtraction("""
                { "totalAmount": 155000 }
                """).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.extraction.validation.needsReview").value(false))
                .andExpect(jsonPath("$.data.extraction.validation.revalidated").value(true));
    }

    /** 추출 전 증빙을 확정하면 없는 값을 확정하게 된다. */
    @Test
    void rejectsEditingBeforeExtractionFinished() throws Exception {
        jdbc.update("UPDATE core.evidence SET processing_status = 'QUEUED' WHERE id = ?", evidenceId);

        patchExtraction("""
                { "confirmed": true }
                """).andExpect(status().isConflict());
    }

    /** 남의 증빙은 보이지 않는다. 직접 경로라 자동 필터가 안 걸린다. */
    @Test
    void hidesEvidenceOfAnotherOwner() throws Exception {
        String other = tokenProvider.createAccessToken(
                jdbc.queryForObject("""
                        INSERT INTO core.users (email, password_hash, name)
                        VALUES (?, 'x', '남') RETURNING id
                        """, Long.class, "other-" + System.nanoTime() + "@test.local"),
                "other@test.local");

        mockMvc.perform(get("/api/v1/evidences/{id}", evidenceId)
                        .header("Authorization", "Bearer " + other))
                .andExpect(status().isForbidden());
    }

    // ------------------------------------------------------------------ 도구

    private ResultActions patchExtraction(String body) throws Exception {
        return mockMvc.perform(patch("/api/v1/evidences/{id}/extraction", evidenceId)
                .header("Authorization", "Bearer " + token)
                .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                .content(body));
    }

    /** 업로드 직후 상태의 증빙. 추출 결과가 없어 {@code needs_review} 가 null 이다. */
    private Long uploadedEvidence() {
        return jdbc.queryForObject("""
                INSERT INTO core.evidence
                    (business_id, uploaded_by, evidence_type, original_filename,
                     storage_key, mime_type, file_size, processing_status)
                VALUES (?, (SELECT owner_user_id FROM core.business WHERE id = ?),
                        'OTHER', 'queued.jpg', ?, 'image/jpeg', 100, 'QUEUED')
                RETURNING id
                """, Long.class, businessId, businessId, "evidences/q-" + System.nanoTime() + ".jpg");
    }

    /** 검산이 걸리지 않은 추출 하나를 더 만든다. 정렬·필터의 상대편이다. */
    private Long anotherExtractedEvidence(boolean needsReview) {
        Long id = uploadedEvidence();
        Map<String, Object> data = new LinkedHashMap<>(callback());
        data.put("evidenceId", id);
        data.put("validation", Map.of("needsReview", needsReview, "reviewReasons", List.of()));
        extractionService.apply(jobService.enqueue(businessId, ownerId, JobType.EVIDENCE_EXTRACTION, Map.of("evidenceId", evidenceId)), data);
        return id;
    }

    private Boolean reviewFlag(Long id) {
        return jdbc.queryForObject(
                "SELECT needs_review FROM core.evidence WHERE id = ?", Boolean.class, id);
    }

    private List<Map<String, Object>> corrections() {
        return jdbc.queryForList("""
                SELECT c.field, c.before_value AS before, c.after_value AS after
                  FROM core.evidence_correction c
                  JOIN core.evidence_extraction e ON e.id = c.extraction_id
                 WHERE e.evidence_id = ?
                """, evidenceId);
    }

    // ------------------------------------------------------------------ 13.7

    /**
     * 재추출은 같은 증빙을 {@code THOROUGH} 로 다시 건다 (20장 v1.17).
     *
     * <p>Job 파라미터에 모드가 실려야 워커가 타일링을 켜고, 그 값이 추출 행까지 내려가야
     * 나중에 「재추출이 실제로 고쳤나」를 셀 수 있다.
     */
    @Test
    void reExtractQueuesAThoroughJob() throws Exception {
        mockMvc.perform(post("/api/v1/evidences/{id}/re-extract", evidenceId)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.data.extractionMode").value("THOROUGH"))
                .andExpect(jsonPath("$.data.ocrJobId").isNumber());

        assertThat(jdbc.queryForObject("""
                SELECT params ->> 'extractionMode' FROM core.job
                 WHERE job_type = 'EVIDENCE_EXTRACTION'
                 ORDER BY id DESC LIMIT 1
                """, String.class)).isEqualTo("THOROUGH");

        // 다시 읽는 중이라는 것이 목록·상세에 보여야 한다.
        mockMvc.perform(get("/api/v1/evidences/{id}", evidenceId)
                        .header("Authorization", "Bearer " + token))
                .andExpect(jsonPath("$.data.ocrStatus").value("PROCESSING"));
    }

    /**
     * 확정된 증빙은 거절한다.
     *
     * <p>재추출은 새 행을 넣고 이전 행을 내린다. 확정본에 그대로 하면 <b>사장님이 승인한 값이
     * 조용히 사라진다</b> — 확정을 되돌리는 경로가 먼저 있어야 한다.
     */
    @Test
    void reExtractRejectsAConfirmedEvidence() throws Exception {
        mockMvc.perform(patch("/api/v1/evidences/{id}/extraction", evidenceId)
                        .header("Authorization", "Bearer " + token)
                        .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                        .content("""
                { "confirmed": true }
                """))
                .andExpect(status().isOk());

        mockMvc.perform(post("/api/v1/evidences/{id}/re-extract", evidenceId)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("EVIDENCE_STATE_CONFLICT"));
    }

}
