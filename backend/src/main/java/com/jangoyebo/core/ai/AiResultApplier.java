package com.jangoyebo.core.ai;

import com.jangoyebo.common.error.ErrorCode;
import com.jangoyebo.core.ai.AiCallbackDtos.ErrorBody;
import com.jangoyebo.core.ai.AiCallbackDtos.ResultRequest;
import com.jangoyebo.core.evidence.EvidenceExtractionService;
import com.jangoyebo.core.job.Job;
import com.jangoyebo.core.report.BriefingSource;
import com.jangoyebo.core.job.JobService;
import com.jangoyebo.core.job.JobType;
import com.jangoyebo.core.productsales.ProductSalesExtractionService;
import com.jangoyebo.core.support.SupportProgramStructuringService;
import com.jangoyebo.core.transaction.TransactionClassificationService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 콜백 결과를 Job 에 반영한다. 트랜잭션 경계가 여기다.
 *
 * <p>{@link AiCallbackService} 와 나눈 이유는 두 가지다 — 한 클래스에서
 * {@code @Transactional} 메서드를 자기 호출하면 프록시를 타지 않고, 멱등 키 보상은
 * 트랜잭션 결과가 확정된 <b>뒤</b>에 실행돼야 한다.
 */
@Service
public class AiResultApplier {

    private static final Logger log = LoggerFactory.getLogger(AiResultApplier.class);

    /** {@code job.error_message} 는 TEXT 지만 무한정 담을 이유가 없다. */
    private static final int MESSAGE_LIMIT = 1_000;

    private final JobService jobService;
    private final EvidenceExtractionService evidenceExtractionService;
    private final ProductSalesExtractionService productSalesExtractionService;
    private final TransactionClassificationService transactionClassificationService;
    private final SupportProgramStructuringService supportProgramStructuringService;
    private final BriefingSource briefings;

    public AiResultApplier(JobService jobService,
                           EvidenceExtractionService evidenceExtractionService,
                           ProductSalesExtractionService productSalesExtractionService,
                           TransactionClassificationService transactionClassificationService,
                           SupportProgramStructuringService supportProgramStructuringService,
                           BriefingSource briefings) {
        this.jobService = jobService;
        this.evidenceExtractionService = evidenceExtractionService;
        this.productSalesExtractionService = productSalesExtractionService;
        this.transactionClassificationService = transactionClassificationService;
        this.supportProgramStructuringService = supportProgramStructuringService;
        this.briefings = briefings;
    }

    /**
     * @return 반영했으면 {@code true}. 이미 종단 상태여서 건너뛰었으면 {@code false}
     *         (= 중복 콜백). Redis 멱등 키가 사라져도 이 판정이 최종 근거다.
     */
    @Transactional
    public boolean applyResult(Long jobId, ResultRequest body) {
        Job job = jobService.findForCallback(jobId);
        if (job.getStatus().isTerminal()) {
            log.info("작업 {} 은 이미 {} 다 — 콜백을 건너뛴다", jobId, job.getStatus());
            return false;
        }

        if (isSuccess(body)) {
            Map<String, Object> result = normalize(jobId, body.data());
            jobService.complete(jobId, result);
            persist(job, result);
            // 본문을 통째로 남기지 않는다. 공급자명·품목·영수증 내용이 들어 있다(명세 20.1).
            log.info("작업 {} 완료 — jobType={} status={} pipeline={} modelName={}",
                    jobId, job.getJobType(), result.get("status"),
                    result.get("pipeline"), result.get("modelName"));
        } else {
            ErrorCode code = mapErrorCode(body.error());
            jobService.fail(jobId, code, messageOf(body.error(), code));
            log.warn("작업 {} 실패 — jobType={} code={}", jobId, job.getJobType(), code);
        }
        return true;
    }

    /**
     * 브리핑 문장 (20.7).
     *
     * <p><b>문장이 비어 있으면 실패로 남긴다.</b> 워커가 모든 문장을 폐기했다는 뜻인데,
     * 빈 제목·본문을 그대로 저장하면 화면이 「문장이 있다」로 읽고 빈 칸을 그린다. 지표는
     * 그대로 남아 있으므로 브리핑 자체가 없어지지는 않는다.
     */
    private void applyBriefing(Job job, Map<String, Object> result) {
        Long briefingId = longOf(job.getParams(), "briefingId");
        if (briefingId == null) {
            log.warn("작업 {} 에 briefingId 가 없어 문장을 옮기지 못했다", job.getId());
            return;
        }
        String headline = stringOf(result, "headline");
        String content = stringOf(result, "content");
        if (headline == null || content == null) {
            briefings.markFailed(briefingId);
            return;
        }
        briefings.applySentence(briefingId, headline, content,
                "PARTIAL".equals(result.get("status")));
    }

    private static Long longOf(Map<String, Object> source, String key) {
        Object raw = source == null ? null : source.get(key);
        return raw instanceof Number number ? number.longValue() : null;
    }

    private static String stringOf(Map<String, Object> source, String key) {
        Object raw = source.get(key);
        String value = raw == null ? null : raw.toString().trim();
        return value == null || value.isEmpty() ? null : value;
    }

    /**
     * 결과를 도메인 테이블로 옮긴다. {@code JOB.result} 는 90일 뒤 정리 대상이라
     * (공통규약 23장) 거기 두면 <b>추출 결과가 Job 과 함께 사라진다.</b>
     *
     * <p><b>저장에 실패해도 Job 은 완료 그대로 둔다.</b> 여기서 던지면 트랜잭션이 되돌아
     * {@code jobService.complete} 까지 취소되고, 워커는 5xx 를 받아 같은 결과를 다시 보낸다.
     * 저장이 안 되는 원인(증빙 삭제·계약 불일치)은 재시도로 낫지 않으므로 무한히 반복된다.
     * 원본은 {@code JOB.result} 에 남아 있어 나중에 다시 밀어 넣을 수 있다.
     */
    private void persist(Job job, Map<String, Object> result) {
        if (result.isEmpty()) {
            return;
        }
        try {
            switch (job.getJobType()) {
                case EVIDENCE_EXTRACTION -> evidenceExtractionService.apply(job, result);
                // 상품 행은 저장하지 않는다(14.7) — 사용자가 확인한 뒤 14.4 가 쓴다.
                // 여기 남는 것은 기간·읽은 열·합계 행·검산·모델 메타뿐이다(합의 ④).
                case PRODUCT_SALES_OCR -> applyProductSalesExtraction(job, result);
                // 사업장은 Job 에서 온다 — 콜백 본문에는 businessId 가 없고, 결과에 담긴
                // 거래가 이 사업장 것인지 확인하는 데 쓴다.
                case TRANSACTION_CLASSIFICATION ->
                        applyClassification(job, transactionClassificationService.apply(
                                job.getBusinessId(), result));
                // 사업장이 없는 유일한 유형이다 — 공고는 사업장 공용 데이터라
                // job.businessId 가 null 이고, 대상은 params.programId 로 정해진다(V21).
                case PROGRAM_STRUCTURING -> supportProgramStructuringService.apply(job, result);
                // 문장을 얹는다. PARTIAL 판정은 워커가 한다 — 환각 숫자를 잡는 한국어 수치
                // 파서가 거기 있고, 같은 검사를 두 벌 두면 둘이 갈릴 때 어느 쪽이 옳은지
                // 알 수 없다. 여기서는 그 판정을 기록할 뿐이다(20.7).
                case BRIEFING_GENERATION -> applyBriefing(job, result);
                // 옮길 곳이 없는 유형이다. Job 은 완료로 두되 조용히 넘기지 않는다 —
                // 워커가 새 라우트를 붙이면 결과가 여기로 오는데, 분기를 빠뜨리면
                // Job 은 COMPLETED 인데 도메인 테이블에는 아무것도 안 남는다. 실패가 아니라
                // 침묵이라 붙이는 쪽에서 알아채기 어렵다(분류에서 실제로 겪었다).
                //
                // 동기화·임포트처럼 애초에 워커를 타지 않는 유형은 여기 오지 않는다.
                default -> log.warn("작업 {} 유형 {} 의 콜백 결과를 옮길 곳이 없다 — "
                                + "JOB.result 에만 남아 보관 기간이 지나면 사라진다. 분기가 필요하다",
                        job.getId(), job.getJobType());
            }
        } catch (RuntimeException e) {
            log.error("작업 {} 추출 결과를 저장하지 못했다 — Job 은 완료로 둔다. {}",
                    job.getId(), e.toString());
        }
    }

    /**
     * 추출 기록을 남기고 그 id 를 결과에 실어 보낸다 (14.4 의 {@code extractionId}).
     *
     * <p><b>여기서 싣지 않으면 그 링크를 채울 방법이 아예 없다.</b> 추출 행은 이 콜백에서
     * 처음 생기므로 워커는 id 를 모르고({@code data} 에 담아 보낼 수가 없다), 14.7 의 202
     * 응답은 그보다 앞서며, 추출 기록을 조회하는 엔드포인트도 없다. 그래서 클라이언트가
     * 14.4 에 보낼 값을 어디서도 얻지 못해 {@code PRODUCT_SALES_PERIOD.extraction_id} 가
     * <b>항상 NULL</b> 로 남았다 — 컬럼·FK·요청 필드가 다 있는데 값만 없는 상태였다.
     *
     * <p>이 링크가 있어야 구성비가 나중에 틀린 것으로 드러났을 때 「모델이 그때 무엇을
     * 읽었나」에 답할 수 있다. {@code JOB.result} 는 90일 뒤 정리되지만(공통규약 23장)
     * 추출 기록은 남기 때문에 <b>수명이 다른 두 곳을 잇는 것</b>이 이 값의 일이다.
     *
     * <p>{@code addResult} 로 덧붙이는 것은 {@code applyClassification} 과 같은 방식이다 —
     * 워커가 보낸 {@code data} 는 그대로 두고 우리가 안 것만 더한다.
     */
    private void applyProductSalesExtraction(Job job, Map<String, Object> result) {
        Long extractionId = productSalesExtractionService.apply(job, result);
        if (extractionId == null) {
            // 계약상 null 이 가능하다. 그때는 이을 기록이 없는 것이라 키를 만들지 않는다 —
            // extractionId: null 을 내보내면 "기록이 없다"와 "필드를 안 채웠다"가 섞인다.
            return;
        }
        job.addResult(Map.of("extractionId", extractionId));
    }

    /**
     * 미분류가 남았으면 다음 Job 하나를 넣는다 (합의 ① 체이닝).
     *
     * <p>한 Job 이 {@code jango.classification.batch-size} 만큼만 담으므로 큰 CSV 는 한 번에
     * 끝나지 않는다. <b>동시에 도는 것은 항상 하나</b>다 — 이 Job 은 방금 {@code COMPLETED} 가
     * 됐고 {@code enqueueIfIdle} 가 다음 하나만 넣는다. 워커의 타임아웃·429 위험이 그만큼 없고
     * 진행률은 미분류 잔량으로 센다.
     *
     * <p>같은 트랜잭션에서 넣는다 — 콜백이 롤백되면 이어 갈 이유도 사라진다.
     *
     * <p>이어 갈지는 {@link TransactionClassificationService.Applied#shouldContinue()} 가
     * 정한다. <b>반영이 한 건도 없으면 멈춘다</b> — 그대로 이어 가면 같은 거래를 값만 치르며
     * 영원히 다시 보낸다.
     */
    private void applyClassification(Job job, TransactionClassificationService.Applied applied) {
        // 화면이 "34건 분류 · 6건 확인 필요" 토스트를 띄운다(화면구성도 SC-04). 워커 본문만
        // 남기면 그 수를 셀 수 없다 — 세는 일은 이미 반영 서비스가 했다.
        job.addResult(Map.of(
                "classifiedCount", applied.classified(),
                "needsConfirmationCount", applied.needsConfirmation()));
        chainIfUnfinished(job, applied);
    }

    private void chainIfUnfinished(Job job, TransactionClassificationService.Applied applied) {
        if (!applied.shouldContinue()) {
            return;
        }
        jobService.enqueueIfIdle(job.getBusinessId(), job.getRequestedBy(),
                        JobType.TRANSACTION_CLASSIFICATION, Map.of())
                .ifPresent(next -> log.info("미분류 {}건이 남아 분류를 이어 간다 — 사업장 {} jobId={}",
                        applied.remaining(), job.getBusinessId(), next.getId()));
    }

    /**
     * {@code success} 가 없으면 {@code data} 유무로 추론한다. 거부하면 결과가 사라지고
     * 워커는 4xx 로 영구 포기한다.
     */
    private boolean isSuccess(ResultRequest body) {
        if (body.success() != null) {
            return body.success();
        }
        log.warn("콜백에 success 가 없다 — data 유무로 추론한다");
        return body.data() != null;
    }

    /**
     * 워커가 보낸 {@code data} 를 그대로 보존한다.
     *
     * <p><b>{@code status = PARTIAL} 도 {@code COMPLETED} 로 저장한다</b>(명세 20.7 — 브리핑이
     * 일부만 살아남은 경우). {@code FAILED} 로 접으면 쓸 수 있는 결과를 버린다.
     * 원본 {@code status} 가 {@code Job.result} 안에 남고 {@code JobResponse.resultRef} 로
     * 그대로 나가므로 프론트가 {@code resultRef.status} 로 부분 성공을 구분할 수 있다 —
     * {@code JobStatus} enum·DB CHECK·프론트 계약을 건드리지 않는다.
     *
     * <p>TODO(20.7): 브리핑 도입 시 {@code JobStatus.PARTIAL} 승격 여부를 재검토한다.
     */
    private Map<String, Object> normalize(Long jobId, Map<String, Object> data) {
        if (data == null) {
            log.warn("작업 {} 콜백에 data 가 없다 — 빈 결과로 저장한다", jobId);
            return Map.of();
        }

        Map<String, Object> result = new LinkedHashMap<>(data);
        Object status = data.get("status");
        if ("PARTIAL".equals(status)) {
            result.put("partial", true);
            log.warn("작업 {} 부분 성공(PARTIAL) — COMPLETED 로 저장하고 result.status 에 원본을 남긴다", jobId);
        } else if (status != null && !"COMPLETED".equals(status)) {
            // Envelope 이 success=true 인데 data.status 가 다른 경우. Envelope 을 신뢰한다.
            log.warn("작업 {} 콜백의 data.status 가 {} 다 — Envelope 을 신뢰해 COMPLETED 로 저장한다",
                    jobId, status);
        }
        return result;
    }

    /**
     * 워커는 4종만 보내고 넷 다 {@code ErrorCode} 에 있다. enum 을 경유하면
     * {@code job.error_code VARCHAR(50)} 초과가 구조적으로 불가능해진다.
     */
    private ErrorCode mapErrorCode(ErrorBody error) {
        if (error == null || error.code() == null) {
            return ErrorCode.AI_ANALYSIS_FAILED;
        }
        try {
            return ErrorCode.valueOf(error.code());
        } catch (IllegalArgumentException e) {
            log.warn("워커가 알 수 없는 오류 코드를 보냈다 — AI_ANALYSIS_FAILED 로 대체한다. code={}",
                    error.code());
            return ErrorCode.AI_ANALYSIS_FAILED;
        }
    }

    private String messageOf(ErrorBody error, ErrorCode fallback) {
        if (error == null || error.message() == null || error.message().isBlank()) {
            return fallback.defaultMessage();
        }
        String message = error.message();
        return message.length() <= MESSAGE_LIMIT ? message : message.substring(0, MESSAGE_LIMIT);
    }
}
