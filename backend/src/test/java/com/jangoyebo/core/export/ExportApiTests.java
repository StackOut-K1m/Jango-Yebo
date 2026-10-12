package com.jangoyebo.core.export;

import com.jangoyebo.NeedsDocker;
import com.jangoyebo.common.security.JwtTokenProvider;
import com.jangoyebo.core.job.Job;
import com.jangoyebo.core.job.JobHandler;
import com.jangoyebo.core.job.JobRepository;
import com.jangoyebo.core.job.JobService;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import java.io.ByteArrayInputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 신고 준비자료 내보내기 (API 14.2 · P0-12 부속 산출물).
 *
 * <p>이 슬라이스가 세우는 것은 <b>시트가 아니라 수명</b>이다 — 접수 → 생성 → 보관 → 만료 →
 * 내려받기. 시트 7장은 다음 슬라이스에서 붙는다.
 *
 * <p><b>파일이 사라지는 자리가 여기 다 모여 있다.</b> 아직 안 끝난 것·만료된 것·남의 것을
 * 구분하지 못하면 사장님은 세무대리인에게 빈손으로 가거나, 더 나쁘게 남의 사업장 자료를
 * 받는다.
 *
 * <p><b>Docker가 필요하다.</b>
 */
@NeedsDocker
@SpringBootTest
@AutoConfigureMockMvc
class ExportApiTests {

    private static final LocalDate FROM = LocalDate.of(2026, 1, 1);
    private static final LocalDate TO = LocalDate.of(2026, 12, 31);

    @Autowired private MockMvc mockMvc;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private JwtTokenProvider tokenProvider;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private JobRepository jobRepository;
    @Autowired private JobService jobService;
    @Autowired private ExportGenerationHandler handler;

    private Long userId;
    private Long businessId;
    private Long accountId;
    private String token;

    @BeforeEach
    void setUp() {
        String unique = String.valueOf(System.nanoTime());
        String email = "export-" + unique + "@test.local";
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

    private void tx(String category, String flow, String amount, String reviewStatus) {
        Long categoryId = jdbc.queryForObject(
                "SELECT id FROM core.transaction_category WHERE code = ?", Long.class, category);
        jdbc.update("""
                INSERT INTO core.transaction
                    (business_id, account_id, category_id, source_type, occurred_at,
                     transaction_date, flow_type, amount, description, deduplication_key,
                     classification_status, tax_review_status)
                VALUES (?, ?, ?, 'FIN_API', ?, DATE '2026-06-15', ?, ?, ?, ?, 'CONFIRMED', ?)
                """, businessId, accountId, categoryId, Timestamp.from(Instant.now()), flow,
                new BigDecimal(amount), category + " 거래", "dedup-" + System.nanoTime(),
                reviewStatus);
    }

    private ResultActions create(LocalDate from, LocalDate to) throws Exception {
        return mockMvc.perform(post("/api/v1/businesses/" + businessId + "/exports")
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"exportType":"TAX_PREP","periodStart":"%s","periodEnd":"%s",
                         "includeEvidenceFiles":false}
                        """.formatted(from, to)));
    }

    private Long accept() throws Exception {
        String body = create(FROM, TO).andExpect(status().isAccepted())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(body).path("data").path("exportId").asLong();
    }

    /** 워커가 하는 일 — 핸들러를 돌리고 결과로 Job 을 닫는다. */
    private Long generate() throws Exception {
        Long exportId = accept();
        Job job = jobRepository.findById(exportId).orElseThrow();
        JobHandler.Outcome outcome = handler.handle(job);
        jobService.complete(exportId, ((JobHandler.Outcome.Completed) outcome).result());
        return exportId;
    }

    private ResultActions detail(Long exportId) throws Exception {
        return mockMvc.perform(get("/api/v1/exports/" + exportId)
                .header("Authorization", "Bearer " + token));
    }

    private ResultActions download(Long exportId) throws Exception {
        return mockMvc.perform(get("/api/v1/exports/" + exportId + "/download")
                .header("Authorization", "Bearer " + token));
    }

    private String otherUserToken() {
        String otherEmail = "other-" + System.nanoTime() + "@test.local";
        Long otherUser = jdbc.queryForObject("""
                INSERT INTO core.users (email, password_hash, name) VALUES (?, 'x', '남') RETURNING id
                """, Long.class, otherEmail);
        return tokenProvider.createAccessToken(otherUser, otherEmail);
    }

    // --- 접수와 생성 ---------------------------------------------------------

    @Test
    void acceptsTheRequestAndReportsItAsProcessing() throws Exception {
        Long exportId = accept();

        detail(exportId)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.exportId").value(exportId))
                // exportId 와 jobId 는 같은 값이다. 명세가 두 키를 다 쓰므로 둘 다 낸다.
                .andExpect(jsonPath("$.data.jobId").value(exportId))
                .andExpect(jsonPath("$.data.exportType").value("TAX_PREP"))
                .andExpect(jsonPath("$.data.status").value("PROCESSING"))
                .andExpect(jsonPath("$.data.periodStart").value("2026-01-01"))
                .andExpect(jsonPath("$.data.periodEnd").value("2026-12-31"))
                // 아직 파일이 없다. 0 으로 채우면 화면이 「0바이트짜리 파일이 있다」로 읽는다.
                .andExpect(jsonPath("$.data.fileName").doesNotExist())
                .andExpect(jsonPath("$.data.fileSize").doesNotExist())
                .andExpect(jsonPath("$.data.downloadExpiresAt").doesNotExist());
    }

    @Test
    void completesWithAFileAndAnExpiry() throws Exception {
        Long exportId = generate();

        detail(exportId)
                .andExpect(jsonPath("$.data.status").value("COMPLETED"))
                .andExpect(jsonPath("$.data.fileName").value("신고준비자료_유선상회_2026.zip"))
                .andExpect(jsonPath("$.data.fileSize").value(org.hamcrest.Matchers.greaterThan(0)))
                .andExpect(jsonPath("$.data.downloadExpiresAt").exists())
                .andExpect(jsonPath("$.data.generatedAt").exists());
    }

    @Test
    void refusesAPeriodThatEndsBeforeItStarts() throws Exception {
        create(TO, FROM).andExpect(status().isBadRequest());
    }

    /**
     * <b>기간이 다르면 다른 파일이다.</b> {@code enqueueIfIdle} 은 (사업장, 종류)로만 중복을
     * 막아서, 이것을 쓰면 1~8월을 만드는 중에 요청한 1~12월이 앞의 것으로 돌아온다.
     */
    @Test
    void makesANewExportForEachRequest() throws Exception {
        Long first = accept();
        Long second = accept();

        assertThat(second).isNotEqualTo(first);
    }

    // --- 내려받기 ------------------------------------------------------------

    @Test
    void servesTheZipWithAGuideInside() throws Exception {
        Long exportId = generate();

        byte[] body = download(exportId)
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsByteArray();

        List<String> entries = new ArrayList<>();
        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(body))) {
            for (ZipEntry entry = zip.getNextEntry(); entry != null; entry = zip.getNextEntry()) {
                entries.add(entry.getName());
            }
        }
        // 빈 ZIP 을 내보내면 「만들어졌는데 비어 있다」와 「아직 안 붙였다」가 구분되지 않는다.
        // 시트별 내용은 ExportSheetTests 가 본다 — 여기는 내려받기 경로만 확인한다.
        assertThat(entries).startsWith("00_안내.txt").hasSizeGreaterThan(1);
    }

    @Test
    void refusesToDownloadBeforeItIsReady() throws Exception {
        Long exportId = accept();

        download(exportId).andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("EXPORT_NOT_READY"));
    }

    /**
     * <b>만료를 「아직 안 됐다」로 말하지 않는다.</b> 409 로 답하면 화면은 「만드는 중」으로
     * 읽어 사장님이 기다린다 — 사실은 끝났다가 사라진 것이라 다시 생성해야 한다.
     */
    @Test
    void refusesToDownloadAfterItExpired() throws Exception {
        Long exportId = generate();
        expire(exportId);

        download(exportId).andExpect(status().isGone())
                .andExpect(jsonPath("$.error.code").value("EXPORT_EXPIRED"));
    }

    private void expire(Long exportId) {
        String expired = Instant.now().minus(1, ChronoUnit.DAYS).toString();
        jdbc.update("""
                UPDATE core.job
                   SET result = jsonb_set(result, '{expiresAt}', to_jsonb(?::text))
                 WHERE id = ?
                """, expired, exportId);
    }

    // --- 소유권 --------------------------------------------------------------

    @Test
    void refusesAnotherBusiness() throws Exception {
        Long exportId = generate();

        mockMvc.perform(get("/api/v1/exports/" + exportId)
                        .header("Authorization", "Bearer " + otherUserToken()))
                .andExpect(status().isForbidden());
    }

    @Test
    void refusesAnotherBusinessDownload() throws Exception {
        Long exportId = generate();

        mockMvc.perform(get("/api/v1/exports/" + exportId + "/download")
                        .header("Authorization", "Bearer " + otherUserToken()))
                .andExpect(status().isForbidden());
    }

    /** 다른 종류의 Job 번호로 부르면 「없다」다 — 그 작업이 있다는 것도 알려 주지 않는다. */
    @Test
    void hidesJobsThatAreNotExports() throws Exception {
        Long classificationJob = jdbc.queryForObject("""
                INSERT INTO core.job (business_id, requested_by, job_type, status)
                VALUES (?, ?, 'TRANSACTION_CLASSIFICATION', 'QUEUED') RETURNING id
                """, Long.class, businessId, userId);

        detail(classificationJob).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("EXPORT_NOT_FOUND"));
    }

    // --- 이력 ----------------------------------------------------------------

    @Test
    void listsExportsNewestFirst() throws Exception {
        Long first = accept();
        Long second = accept();

        mockMvc.perform(get("/api/v1/businesses/" + businessId + "/exports")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items.length()").value(2))
                .andExpect(jsonPath("$.data.items[0].exportId").value(second))
                .andExpect(jsonPath("$.data.items[1].exportId").value(first));
    }

    // --- 요약 ----------------------------------------------------------------

    /**
     * 요약은 14.3 초안과 같은 정의를 쓴다. 두 화면이 같은 기간을 보면서 다른 숫자를 말하면
     * 사장님은 어느 쪽을 믿어야 할지 모른다.
     */
    @Test
    void summarisesTheSamePeriodTheDraftWouldSee() throws Exception {
        tx("RENT", "WITHDRAWAL", "1200000", "EVIDENCE_CONFIRMED");
        tx("SUPPLIES", "WITHDRAWAL", "300000", "UNREVIEWED");
        tx("SUPPLIES", "WITHDRAWAL", "500000", "EXPERT_REVIEW");
        tx("PLATFORM_SETTLEMENT", "DEPOSIT", "2000000", "EVIDENCE_CONFIRMED");

        Long exportId = generate();

        detail(exportId)
                .andExpect(jsonPath("$.data.summary.transactionCount").value(4))
                // 확인 필요 3종 — 14.3 의 excluded.needsReview 와 같은 집합이다.
                .andExpect(jsonPath("$.data.summary.unverifiedCount").value(1))
                .andExpect(jsonPath("$.data.summary.unverifiedAmount").value(500000))
                // 4건 중 UNREVIEWED 한 건을 뺀 3건이 검토를 마쳤다.
                .andExpect(jsonPath("$.data.summary.taxReviewedCount").value(3))
                .andExpect(jsonPath("$.data.summary.taxReviewRate").value(0.75))
                // 증빙은 출금 3건에만 붙을 수 있고 하나도 안 붙었다.
                .andExpect(jsonPath("$.data.summary.evidenceLinkRate").value(0.0));
    }

    /**
     * <b>셀 것이 없으면 0 이 아니다.</b> 출금이 한 건도 없는데 「증빙 연결률 0%」를 내보내면
     * 화면은 「증빙을 하나도 안 붙였다」로 읽는다 — 붙일 것이 없었던 것과 다른 말이다.
     */
    @Test
    void omitsTheEvidenceRateWhenThereIsNothingToAttachItTo() throws Exception {
        tx("PLATFORM_SETTLEMENT", "DEPOSIT", "2000000", "EVIDENCE_CONFIRMED");

        Long exportId = generate();

        String body = detail(exportId).andReturn().getResponse().getContentAsString();
        JsonNode summary = objectMapper.readTree(body).path("data").path("summary");
        assertThat(summary.has("evidenceLinkRate")).isFalse();
        // 검토율은 반대다 — 셀 것이 없으면 1 이고, 그것이 14.3 의 규칙이다.
        assertThat(summary.path("taxReviewRate").asDouble()).isEqualTo(1.0);
    }
}
