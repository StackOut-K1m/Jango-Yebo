package com.jangoyebo.core.ai;

import com.jangoyebo.common.error.BusinessException;
import com.jangoyebo.common.error.ErrorCode;
import com.jangoyebo.core.job.Job;
import com.jangoyebo.core.job.JobHandler;
import com.jangoyebo.core.job.JobType;
import com.jangoyebo.core.transaction.DictionaryClassificationService;
import com.jangoyebo.core.transaction.TransactionClassificationRequest;
import com.jangoyebo.core.transaction.TransactionClassificationRequestFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 거래 분류 작업을 AI 워커에 접수한다 (명세 20.3 · P1-01).
 *
 * <p>{@link EvidenceExtractionHandler} 와 같은 모양이다 — 접수만 하고
 * {@code AWAITING_CALLBACK} 을 반환해 Job 을 {@code PROCESSING} 에 남기고,
 * {@link AiCallbackController} 가 콜백을 받아 완결한다.
 *
 * <p><b>{@code params} 를 읽지 않는다.</b> 다른 핸들러와 다른 점이 이것이다 — 무엇을 분류할지는
 * Job 에 적힌 id 목록이 아니라 <b>그 시점의 미분류 거래를 조회</b>해서 정한다(합의 ④).
 * 그래서 Job 이 중복으로 들어와도 같은 거래를 두 번 보내지 않고, 큐에 들어온 뒤 사용자가
 * 직접 분류한 거래는 자동으로 빠진다. 대신 <b>같은 사업장에 끝나지 않은 Job 이 둘 있으면
 * 같은 거래를 두 번 과금</b>하므로, 그것을 막는 가드는 큐에 넣는 쪽의 책임이다(3단계).
 */
@Component
public class TransactionClassificationHandler implements JobHandler {

    private static final Logger log = LoggerFactory.getLogger(TransactionClassificationHandler.class);
    private static final String PATH = "/internal/v1/ai/transaction-classification";

    private final AiWorkerClient client;
    private final DictionaryClassificationService dictionaryClassificationService;
    private final TransactionClassificationRequestFactory requestFactory;

    public TransactionClassificationHandler(AiWorkerClient client,
                                            DictionaryClassificationService dictionaryClassificationService,
                                            TransactionClassificationRequestFactory requestFactory) {
        this.client = client;
        this.dictionaryClassificationService = dictionaryClassificationService;
        this.requestFactory = requestFactory;
    }

    @Override
    public JobType jobType() {
        return JobType.TRANSACTION_CLASSIFICATION;
    }

    @Override
    public Outcome handle(Job job) {
        Long businessId = job.getBusinessId();
        if (businessId == null) {
            // 큐에 넣은 쪽의 버그다. 워커 스레드에는 SecurityContext 가 없어
            // 사업장을 다른 데서 알아낼 방법도 없다.
            throw new BusinessException(ErrorCode.VALIDATION_FAILED,
                    "작업 %d 에 businessId 가 없습니다.".formatted(job.getId()));
        }

        // 사전에 있는 적요를 먼저 분류한다 (명세 8.5 — 규칙 → 사전 → LLM).
        // 여기서 걸러진 거래는 아래 요청에 담기지 않는다. 도메인 규칙 3 이 지켜지는 지점이고,
        // 사전이 비어 있으면 0 을 돌려주고 지나간다.
        int fromDictionary = dictionaryClassificationService.classify(businessId);

        Optional<TransactionClassificationRequest> request =
                requestFactory.create(job.getId(), businessId);
        if (request.isEmpty()) {
            // 분류할 거래가 없다. 실패가 아니다 — 큐에 들어온 뒤 사용자가 직접 분류했거나
            // 앞선 Job 이 먼저 처리했을 수 있다. 워커 스키마는 거래를 1건 이상 요구하므로
            // 빈 요청을 보내면 422 로 거절된다.
            // 사전이 전부 처리했으면 그 수를 남긴다. 워커를 부르지 않았으므로 콜백이 없고,
            // 이 자리가 그 Job 의 결과를 적을 유일한 기회다.
            log.info("작업 {} 워커에 보낼 거래가 없다 — 사전 분류 {}건으로 완료한다",
                    job.getId(), fromDictionary);
            return Outcome.completed(Map.of(
                    "status", "COMPLETED",
                    "results", List.of(),
                    "dictionaryCount", fromDictionary,
                    "classifiedCount", fromDictionary,
                    "needsConfirmationCount", 0));
        }

        try {
            client.requestAsync(PATH, job.getId(), request.get());
            return Outcome.AWAITING_CALLBACK;
        } catch (AiWorkerException e) {
            if (e.isPermanent()) {
                // 재시도해도 같은 결과다. 즉시 실패로 확정해 사용자가 스위퍼 임계값(10분)을
                // 기다리지 않게 한다.
                throw new BusinessException(ErrorCode.AI_ANALYSIS_FAILED, e.getMessage());
            }
            // 5xx·연결 실패. 워커가 이 작업을 받지 못했으므로 대기열로 되돌린다 —
            // 외부에 일이 떠 있지 않아 다시 보내도 중복이 없고, 배포·재시작 중이면 수 초 뒤
            // 살아난다. 접수된 뒤 콜백이 없는 것과는 위험이 달라 대기도 달라야 한다.
            log.warn("작업 {} 워커 접수 실패 — 대기열로 되돌린다: {}",
                    job.getId(), e.getMessage());
            return Outcome.NOT_ACCEPTED;
        }
    }
}
