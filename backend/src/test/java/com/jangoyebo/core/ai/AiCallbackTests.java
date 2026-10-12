package com.jangoyebo.core.ai;

import com.jangoyebo.NeedsDocker;
import com.jangoyebo.core.job.Job;
import com.jangoyebo.core.job.JobRepository;
import com.jangoyebo.core.job.JobService;
import com.jangoyebo.core.job.JobStatus;
import com.jangoyebo.core.job.JobType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
// Boot 4 에서 org.springframework.boot.test.autoconfigure.web.servlet 에서 옮겨졌다.
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Duration;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * AI 워커 콜백 수신 검증 (명세 20.2).
 *
 * <p>와이어 계약은 {@code ai/app/common/callback.py} 가 기준이다 — 백엔드가 여기에 맞춰야지
 * 그 반대가 아니다. 특히 워커는 {@code < 500} 을 전부 <b>영구 실패</b>로 보고 재시도하지 않으며
 * 응답 본문을 읽지 않는다. 따라서 <b>400 = AI 결과 유실</b>이고, 관대한 파싱 테스트가
 * 이 파일에서 가장 중요하다.
 *
 * <p><b>Docker가 필요하다.</b> postgres + redis 를 Testcontainers 로 띄운다.
 */
@NeedsDocker
@SpringBootTest
@AutoConfigureMockMvc
class AiCallbackTests {

    private static final String TOKEN = "test-service-token";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JobService jobService;

    @Autowired
    private JobRepository jobRepository;

    @Autowired
    private StringRedisTemplate redis;

    @Autowired
    private JdbcTemplate jdbc;

    private Long businessId;
    private Long userId;

    @BeforeEach
    void setUp() {
        // 신고서 초안이 job 을 참조한다(fk_tfd_job). 앞선 테스트 클래스가 남긴 초안이 있으면
        // job 만 지우려다 FK 에 걸려 이 클래스 전체가 죽는다 — 실제로 그랬다(2026-09-18).
        // 순서에 기대지 않으려면 지우는 쪽이 딸린 것을 먼저 치워야 한다.
        jdbc.execute("DELETE FROM core.tax_filing_draft");
        jdbc.execute("DELETE FROM core.job");
        userId = jdbc.queryForObject("""
                INSERT INTO core.users (email, password_hash, name)
                VALUES (?, 'x', '콜백') RETURNING id
                """, Long.class, "callback-" + System.nanoTime() + "@test.local");
        businessId = jdbc.queryForObject("""
                INSERT INTO core.business (owner_user_id, name) VALUES (?, '콜백 사업장') RETURNING id
                """, Long.class, userId);
    }

    // --- 정상 경로 -----------------------------------------------------------

    @Test
    void storesSuccessResultAndCompletesJob() throws Exception {
        Long jobId = processingJob();

        callback(jobId, requestId(jobId), """
                {"success": true,
                 "data": {"status": "COMPLETED", "modelName": "claude-opus-5", "modelVersion": null,
                          "promptVersion": "evidence-extraction/v1", "pipeline": "STUB",
                          "evidenceId": 501, "supplierBusinessNo": "124-81-00998"},
                 "error": null}
                """)
                .andExpect(status().isOk())
                .andExpect(header().string("X-Idempotent-Replay", "false"));

        Job job = jobRepository.findById(jobId).orElseThrow();
        assertThat(job.getStatus()).isEqualTo(JobStatus.COMPLETED);
        assertThat(job.getResult())
                .containsEntry("pipeline", "STUB")
                .containsEntry("modelName", "claude-opus-5")
                .containsEntry("evidenceId", 501)
                .containsEntry("supplierBusinessNo", "124-81-00998");
    }

    @Test
    void storesFailureResultAndFailsJob() throws Exception {
        Long jobId = processingJob();

        callback(jobId, requestId(jobId), """
                {"success": false, "data": null,
                 "error": {"code": "AI_ANALYSIS_FAILED", "message": "추출에 실패했습니다.",
                           "detail": {"evidenceId": 501, "stage": "extract"}}}
                """)
                .andExpect(status().isOk());

        Job job = jobRepository.findById(jobId).orElseThrow();
        assertThat(job.getStatus()).isEqualTo(JobStatus.FAILED);
        assertThat(job.getErrorCode()).isEqualTo("AI_ANALYSIS_FAILED");
        assertThat(job.getErrorMessage()).isEqualTo("추출에 실패했습니다.");
    }

    /**
     * 추출 기록의 id 가 Job 결과로 나간다 (14.4 {@code extractionId} · P1-22).
     *
     * <p><b>여기가 그 값의 유일한 출구다.</b> 추출 행은 이 콜백에서 처음 생겨 워커는 id 를
     * 모르고, 14.7 의 202 는 그보다 앞서며, 추출 기록을 조회하는 엔드포인트가 없다. 이
     * 한 줄이 빠지면 클라이언트가 14.4 에 보낼 값을 어디서도 얻지 못해
     * {@code PRODUCT_SALES_PERIOD.extraction_id} 가 영원히 NULL 이다 — 컬럼·FK·요청 필드가
     * 다 있는 채로 조용히 안 채워지므로 테스트가 없으면 드러나지 않는다.
     */
    @Test
    void exposesProductSalesExtractionIdInJobResult() throws Exception {
        Long jobId = productSalesOcrJob();

        callback(jobId, requestId(jobId), """
                {"success": true,
                 "data": {"status": "COMPLETED", "modelName": "claude-sonnet-5", "modelVersion": null,
                          "promptVersion": "product-sales-extraction/v3", "pipeline": "VLM",
                          "period": {"start": "2022-12-01", "end": "2022-12-14", "source": "HEADER"},
                          "columnsDetected": ["productName", "quantity", "billedAmount"],
                          "items": [{"productName": "카페라떼", "quantity": 47.0,
                                     "billedAmount": 161950, "confidence": 0.91}],
                          "printedTotal": {"quantity": 139.0, "billedAmount": 670450},
                          "warnings": [],
                          "validation": {"rowArithmeticConsistent": null,
                                         "printedTotalConsistent": true, "inconsistentRows": [],
                                         "needsReview": false, "reviewReasons": [], "issues": []}},
                 "error": null}
                """)
                .andExpect(status().isOk());

        Job job = jobRepository.findById(jobId).orElseThrow();
        assertThat(job.getStatus()).isEqualTo(JobStatus.COMPLETED);

        Long extractionId = jdbc.queryForObject("""
                SELECT id FROM core.product_sales_extraction WHERE job_id = ?
                """, Long.class, jobId);

        // 워커가 보낸 data 는 그대로 두고 우리가 안 것만 더한다.
        assertThat(job.getResult()).containsEntry("pipeline", "VLM");

        // JSONB 를 거쳐 나오면 작은 정수는 Integer 다 — Long 으로 넣었어도 그렇다.
        // containsEntry(…, extractionId) 로 쓰면 값이 같은데 박싱 타입이 달라 실패한다.
        // 나가는 JSON 에서는 그냥 숫자라 클라이언트에는 차이가 없다.
        assertThat(((Number) job.getResult().get("extractionId")).longValue())
                .isEqualTo(extractionId);
    }

    /**
     * 분류 결과가 콜백에서 거래까지 내려간다 (20.3 · P1-01).
     *
     * <p><b>여기서 보는 것은 분기 하나다.</b> {@code persist} 의 {@code switch} 에
     * {@code TRANSACTION_CLASSIFICATION} 이 없으면 Job 은 얌전히 {@code COMPLETED} 가 되고
     * <b>결과만 조용히 버려진다</b> — LLM 값은 이미 치렀는데 거래는 그대로 미분류다.
     * 실패가 아니라 침묵이라서 테스트가 없으면 드러나지 않는다.
     *
     * <p>무엇을 어떻게 쓰는지는 {@code TransactionClassificationServiceTests} 가 본다.
     */
    @Test
    void appliesTransactionClassificationToTheTransaction() throws Exception {
        Long transactionId = unclassifiedTransaction("김영자");
        Long jobId = classificationJob();

        classificationCallback(jobId, """
                [{"transactionId": %d, "category": "RENT", "merchantName": "김영자(임대인 추정)",
                  "confidence": 0.92, "reason": "매월 25일 동일 금액 반복 출금"}]
                """.formatted(transactionId))
                .andExpect(status().isOk());

        assertThat(jobRepository.findById(jobId).orElseThrow().getStatus())
                .isEqualTo(JobStatus.COMPLETED);
        assertThat(jdbc.queryForMap(
                "SELECT classification_status, category_id FROM core.transaction WHERE id = ?",
                transactionId))
                .containsEntry("classification_status", "CONFIRMED")
                .containsEntry("category_id", jdbc.queryForObject(
                        "SELECT id FROM core.transaction_category WHERE code = 'RENT'", Long.class));
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM core.transaction_classification_history
                 WHERE transaction_id = ? AND classifier_type = 'LLM'
                """, Integer.class, transactionId)).isEqualTo(1);
    }

    /**
     * 결과에 <b>몇 건을 분류했는지</b>가 남는다 (API 8.5 {@code resultRef}).
     *
     * <p>화면이 재실행 완료에 "34건 분류 · 6건 확인 필요" 토스트를 띄운다(화면구성도 SC-04).
     * 워커 본문만 남기면 그 수를 셀 수 없다 — 반영하면서 이미 센 값이다.
     *
     * <p>워커 본문은 그대로 둔다. 도메인 테이블에 옮긴 뒤에도 원본이 남아 있어야 무엇이 왔는지
     * 확인할 수 있다.
     */
    @Test
    void writesClassificationCountsIntoTheJobResult() throws Exception {
        Long confident = unclassifiedTransaction("김영자");
        Long unsure = unclassifiedTransaction("우아한형제들");
        Long jobId = classificationJob();

        classificationCallback(jobId, """
                [{"transactionId": %d, "category": "RENT", "confidence": 0.92, "reason": "임차료"},
                 {"transactionId": %d, "category": "PLATFORM_SETTLEMENT", "confidence": 0.40,
                  "reason": "확신이 낮다"}]
                """.formatted(confident, unsure))
                .andExpect(status().isOk());

        assertThat(jobRepository.findById(jobId).orElseThrow().getResult())
                .containsEntry("classifiedCount", 2)
                .containsEntry("needsConfirmationCount", 1)
                .as("워커 본문도 그대로 남는다")
                .containsKey("modelName");
    }

    /**
     * 남은 미분류가 있으면 <b>다음 Job 하나</b>를 이어 넣는다 (합의 ① 체이닝).
     *
     * <p>한 Job 이 {@code batch-size} 만큼만 담으므로 큰 CSV 는 한 번에 끝나지 않는다.
     * 동시에 도는 것은 항상 하나이고 진행률은 미분류 잔량으로 센다.
     */
    @Test
    void chainsAnotherJobWhileUnclassifiedRemain() throws Exception {
        Long classified = unclassifiedTransaction("김영자");
        unclassifiedTransaction("우아한형제들");
        Long jobId = classificationJob();

        classificationCallback(jobId, """
                [{"transactionId": %d, "category": "RENT", "confidence": 0.92, "reason": "임차료"}]
                """.formatted(classified))
                .andExpect(status().isOk());

        assertThat(classificationJobCount())
                .as("처음 넣은 것 + 이어 가는 것")
                .isEqualTo(2);
    }

    /** 다 분류됐으면 이어 가지 않는다. */
    @Test
    void doesNotChainWhenNothingRemains() throws Exception {
        Long transactionId = unclassifiedTransaction("김영자");
        Long jobId = classificationJob();

        classificationCallback(jobId, """
                [{"transactionId": %d, "category": "RENT", "confidence": 0.92, "reason": "임차료"}]
                """.formatted(transactionId))
                .andExpect(status().isOk());

        assertThat(classificationJobCount()).isEqualTo(1);
    }

    /**
     * <b>한 건도 반영되지 않았으면 멈춘다.</b>
     *
     * <p>남았다는 것만 보고 이어 가면 같은 거래를 <b>값만 치르며 영원히 다시 보낸다</b> —
     * 허용 목록 밖 분류 코드만 돌아오는 경우가 그렇다. 진전이 없으면 다음 수집이나 8.5
     * 재실행에 맡긴다.
     */
    @Test
    void doesNotChainWhenNothingWasApplied() throws Exception {
        Long transactionId = unclassifiedTransaction("김영자");
        Long jobId = classificationJob();

        classificationCallback(jobId, """
                [{"transactionId": %d, "category": "COFFEE_BEANS", "confidence": 0.99,
                  "reason": "허용 목록에 없는 코드"}]
                """.formatted(transactionId))
                .andExpect(status().isOk());

        assertThat(classificationJobCount()).isEqualTo(1);
        assertThat(jdbc.queryForObject(
                "SELECT classification_status FROM core.transaction WHERE id = ?",
                String.class, transactionId)).isEqualTo("UNCLASSIFIED");
    }

    // --- 멱등 ----------------------------------------------------------------

    /** 워커는 같은 X-Request-Id 로 최대 4번 보낸다. 두 번째 본문이 달라도 첫 결과가 남아야 한다. */
    @Test
    void duplicateCallbackDoesNotOverwriteStoredResult() throws Exception {
        Long jobId = processingJob();
        String requestId = requestId(jobId);

        callback(jobId, requestId, """
                {"success": true, "data": {"status": "COMPLETED", "pipeline": "STUB", "attempt": 1},
                 "error": null}
                """).andExpect(status().isOk());

        callback(jobId, requestId, """
                {"success": true, "data": {"status": "COMPLETED", "pipeline": "VLM", "attempt": 2},
                 "error": null}
                """)
                .andExpect(status().isOk())
                .andExpect(header().string("X-Idempotent-Replay", "true"));

        assertThat(jobRepository.findById(jobId).orElseThrow().getResult())
                .as("첫 번째 결과가 유지되어야 한다")
                .containsEntry("attempt", 1)
                .containsEntry("pipeline", "STUB");
    }

    /** Redis 키가 없어도 Job 이 종단 상태면 중복이다. 상태 가드가 최종 근거다. */
    @Test
    void terminalJobRejectsNewCallbackWithoutRedisKey() throws Exception {
        Long jobId = processingJob();
        callback(jobId, requestId(jobId), successBody()).andExpect(status().isOk());

        callback(jobId, "완전히-다른-요청-id", successBody())
                .andExpect(status().isOk())
                .andExpect(header().string("X-Idempotent-Replay", "true"));
    }

    /**
     * <b>유령 키 회귀.</b> 멱등 키는 {@code DONE} 인데 작업은 미완결인 상황 —
     * 스위퍼가 회수해 재실행된 경우다. 그대로 무시하면 작업이 영구히 PROCESSING 에 갇힌다.
     */
    @Test
    void reprocessesWhenKeyIsDoneButJobIsNot() throws Exception {
        Long jobId = processingJob();
        String requestId = requestId(jobId);
        redis.opsForValue().set("idem:ai-job:" + jobId + ":" + requestId, "DONE", Duration.ofHours(1));

        callback(jobId, requestId, successBody())
                .andExpect(status().isOk())
                .andExpect(header().string("X-Idempotent-Replay", "false"));

        assertThat(jobRepository.findById(jobId).orElseThrow().getStatus())
                .isEqualTo(JobStatus.COMPLETED);
    }

    // --- 관대한 파싱 (400 이면 결과 유실이다) -------------------------------------

    @Test
    void acceptsUnknownFieldsInData() throws Exception {
        Long jobId = processingJob();

        callback(jobId, requestId(jobId), """
                {"success": true,
                 "data": {"status": "COMPLETED", "미래에추가된필드": {"깊이": [1, 2, 3]}, "another": 42},
                 "error": null}
                """).andExpect(status().isOk());

        assertThat(jobRepository.findById(jobId).orElseThrow().getStatus())
                .isEqualTo(JobStatus.COMPLETED);
    }

    /** 워커는 detail 이 falsy 면 키를 아예 만들지 않는다. */
    @Test
    void acceptsErrorWithoutDetail() throws Exception {
        Long jobId = processingJob();

        callback(jobId, requestId(jobId), """
                {"success": false, "data": null,
                 "error": {"code": "INTERNAL_ERROR", "message": "워커 내부 오류"}}
                """).andExpect(status().isOk());

        assertThat(jobRepository.findById(jobId).orElseThrow().getErrorCode()).isEqualTo("INTERNAL_ERROR");
    }

    /** enum 에 없는 코드는 AI_ANALYSIS_FAILED 로 뭉갠다. valueOf 예외로 500 이 나면 실패다. */
    @Test
    void mapsUnknownErrorCodeToAiAnalysisFailed() throws Exception {
        Long jobId = processingJob();

        callback(jobId, requestId(jobId), """
                {"success": false, "data": null,
                 "error": {"code": "WEIRD_UNKNOWN_CODE", "message": "무엇인가 잘못됐다"}}
                """).andExpect(status().isOk());

        assertThat(jobRepository.findById(jobId).orElseThrow().getErrorCode())
                .isEqualTo("AI_ANALYSIS_FAILED");
    }

    /** PARTIAL 은 COMPLETED 로 저장하되 원본을 result 에 남긴다(명세 20.7). */
    @Test
    void mapsPartialStatusToCompletedAndPreservesOriginal() throws Exception {
        Long jobId = processingJob();

        callback(jobId, requestId(jobId), """
                {"success": true, "data": {"status": "PARTIAL", "pipeline": "LLM", "sections": 2},
                 "error": null}
                """).andExpect(status().isOk());

        Job job = jobRepository.findById(jobId).orElseThrow();
        assertThat(job.getStatus()).isEqualTo(JobStatus.COMPLETED);
        assertThat(job.getResult())
                .containsEntry("status", "PARTIAL")
                .containsEntry("partial", true);
    }

    // --- 오류 경로 -----------------------------------------------------------

    /** 워커 버그다. 500 이면 서버 오류로 기록돼 장애 조사가 엉뚱한 곳으로 간다. */
    @Test
    void missingRequestIdReturns400() throws Exception {
        Long jobId = processingJob();

        mockMvc.perform(post("/internal/v1/ai-jobs/{jobId}/result", jobId)
                        .header("Authorization", "Bearer " + TOKEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(successBody()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"));
    }

    @Test
    void unknownJobReturns404AndReleasesKey() throws Exception {
        long missing = 9_999_999L;
        String requestId = requestId(missing);

        callback(missing, requestId, successBody())
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("JOB_NOT_FOUND"));

        assertThat(redis.hasKey("idem:ai-job:" + missing + ":" + requestId))
                .as("실패했으면 키를 해제해 재시도를 막지 않아야 한다")
                .isFalse();
    }

    // --- helpers -------------------------------------------------------------

    /** 워커 호출 후 콜백을 기다리는 상태를 만든다. */
    private Long processingJob() {
        jobService.enqueue(businessId, userId, JobType.EVIDENCE_EXTRACTION, Map.of());
        return jobService.claimNext(JobType.EVIDENCE_EXTRACTION).orElseThrow().getId();
    }

    /** 워커가 쓰는 형식 그대로다 — jobId 당 영구 고정이라 재시도해도 같은 값이다. */
    private String requestId(long jobId) {
        return "ai-job-" + jobId + "-result";
    }

    private String successBody() {
        return """
                {"success": true, "data": {"status": "COMPLETED", "pipeline": "STUB"}, "error": null}
                """;
    }

    private Long accountId;

    private Long unclassifiedTransaction(String description) {
        if (accountId == null) {
            accountId = jdbc.queryForObject("""
                    INSERT INTO core.account
                        (business_id, bank_code, account_number_encrypted, account_number_hash,
                         account_number_masked, name, status)
                    VALUES (?, '088', 'enc', ?, '***1234', '주거래', 'CONNECTED') RETURNING id
                    """, Long.class, businessId, "hash-" + System.nanoTime());
        }
        return jdbc.queryForObject("""
                INSERT INTO core.transaction
                    (business_id, account_id, external_transaction_id, source_type, occurred_at,
                     transaction_date, flow_type, amount, description, classification_status,
                     needs_confirmation)
                VALUES (?, ?, ?, 'FIN_API', now(), DATE '2026-08-25', 'WITHDRAWAL', 1200000,
                        ?, 'UNCLASSIFIED', FALSE) RETURNING id
                """, Long.class, businessId, accountId, "ext-" + System.nanoTime(), description);
    }

    /** 14.7 이 만드는 것과 같은 모양이다 — params 에 captureType 이 있어야 저장이 그것을 읽는다. */
    private Long productSalesOcrJob() {
        jobService.enqueue(businessId, userId, JobType.PRODUCT_SALES_OCR,
                Map.of("captureType", "PRINTOUT",
                        "fileObjectKeys", java.util.List.of("product-sales/1-0.jpg")));
        return jobService.claimNext(JobType.PRODUCT_SALES_OCR).orElseThrow().getId();
    }

    /** 큐에 넣고 워커가 집어 간 상태까지 만든다. */
    private Long classificationJob() {
        jobService.enqueue(businessId, userId, JobType.TRANSACTION_CLASSIFICATION, Map.of());
        return jobService.claimNext(JobType.TRANSACTION_CLASSIFICATION).orElseThrow().getId();
    }

    private int classificationJobCount() {
        return jdbc.queryForObject("""
                SELECT count(*) FROM core.job
                 WHERE business_id = ? AND job_type = 'TRANSACTION_CLASSIFICATION'
                """, Integer.class, businessId);
    }

    /** 부록 B 의 본문에서 {@code results} 만 갈아 끼운다. */
    private org.springframework.test.web.servlet.ResultActions classificationCallback(
            long jobId, String results) throws Exception {
        return callback(jobId, requestId(jobId), """
                {"success": true,
                 "data": {"status": "COMPLETED", "modelName": "claude-sonnet-5", "modelVersion": null,
                          "promptVersion": "transaction-classification/v2", "pipeline": "LLM",
                          "results": %s},
                 "error": null}
                """.formatted(results));
    }

    private org.springframework.test.web.servlet.ResultActions callback(long jobId, String requestId, String body)
            throws Exception {
        return mockMvc.perform(post("/internal/v1/ai-jobs/{jobId}/result", jobId)
                .header("Authorization", "Bearer " + TOKEN)
                .header("X-Request-Id", requestId)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body));
    }
}
