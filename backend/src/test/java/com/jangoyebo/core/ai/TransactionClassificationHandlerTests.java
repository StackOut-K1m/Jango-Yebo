package com.jangoyebo.core.ai;

import com.jangoyebo.common.domain.FlowType;
import com.jangoyebo.common.error.BusinessException;
import com.jangoyebo.common.error.ErrorCode;
import com.jangoyebo.core.job.Job;
import com.jangoyebo.core.job.JobHandler;
import com.jangoyebo.core.job.JobType;
import com.jangoyebo.core.transaction.DictionaryClassificationService;
import com.jangoyebo.core.transaction.FewShotExample;
import com.jangoyebo.core.transaction.TransactionClassificationRequest;
import com.jangoyebo.core.transaction.TransactionClassificationRequest.BusinessContext;
import com.jangoyebo.core.transaction.TransactionClassificationRequest.TransactionToClassify;
import com.jangoyebo.core.transaction.TransactionClassificationRequestFactory;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.client.RestClient;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 거래 분류 핸들러 검증 (명세 20.3).
 *
 * <p><b>Spring 컨텍스트도 Docker 도 쓰지 않는다.</b> 요청을 무엇으로 채우는지는 조립기
 * 테스트가 보고, 여기서 볼 것은 "보낼 것이 없을 때와 워커가 실패할 때 Job 을 어떻게
 * 끝내는가" 뿐이다.
 */
class TransactionClassificationHandlerTests {

    @Test
    void sendsAssembledRequestAndAwaitsCallback() {
        var sent = new AtomicReference<Object>();
        var handler = new TransactionClassificationHandler(
                clientThat((path, jobId, body) -> {
                    assertThat(path).isEqualTo("/internal/v1/ai/transaction-classification");
                    sent.set(body);
                }),
                dictionaryThatMatches(0),
                factoryReturning(Optional.of(request())));

        JobHandler.Outcome outcome = handler.handle(job());

        assertThat(outcome).isEqualTo(JobHandler.Outcome.AWAITING_CALLBACK);
        assertThat(sent.get()).isSameAs(request());
    }

    /**
     * 분류할 거래가 없으면 <b>워커를 부르지 않고</b> 그대로 완료한다.
     *
     * <p>실패가 아니다 — 트리거가 쿼리 기반이라(합의 ④) 큐에 들어온 뒤 사용자가 직접 분류했거나
     * 앞선 Job 이 먼저 처리했을 수 있다. 빈 요청을 보내면 워커 스키마가 거래를 1건 이상
     * 요구하므로 422 로 거절되고, 그러면 정상 상태가 실패로 기록된다.
     */
    @Test
    void completesWithoutCallingTheWorkerWhenNothingToClassify() {
        var handler = new TransactionClassificationHandler(
                clientThat((path, jobId, body) -> {
                    throw new AssertionError("보낼 거래가 없으면 워커를 부르지 않아야 한다");
                }),
                dictionaryThatMatches(0),
                factoryReturning(Optional.empty()));

        JobHandler.Outcome outcome = handler.handle(job());

        assertThat(outcome).isInstanceOf(JobHandler.Outcome.Completed.class);
        Map<String, Object> result = ((JobHandler.Outcome.Completed) outcome).result();
        // 콜백 경로와 같은 모양으로 남긴다 — 결과를 읽는 쪽이 두 경우를 가르지 않아도 된다.
        assertThat(result).containsEntry("status", "COMPLETED")
                .containsEntry("results", List.of());
    }

    /** 4xx 는 재시도해도 같은 결과다. 사용자가 스위퍼 임계값을 기다리지 않게 즉시 끝낸다. */
    @Test
    void permanentWorkerFailureFailsTheJob() {
        var handler = new TransactionClassificationHandler(
                clientThat((path, jobId, body) -> {
                    throw new AiWorkerException(HttpStatus.UNPROCESSABLE_CONTENT,
                            "AI_ANALYSIS_FAILED", "카테고리 목록이 계약과 다릅니다.");
                }),
                dictionaryThatMatches(0),
                factoryReturning(Optional.of(request())));

        assertThatThrownBy(() -> handler.handle(job()))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).errorCode())
                .isEqualTo(ErrorCode.AI_ANALYSIS_FAILED);
    }

    /**
     * 5xx·연결 실패는 Job 을 종결하지 않는다. <b>대기열로 되돌린다.</b>
     *
     * <p>워커가 아직 뜨지 않은 경우가 여기다 — 접수가 안 됐으니 워커에 일이 떠 있지 않고,
     * 다시 보내도 중복 과금이 없다. PROCESSING 에 가둬 두면 스위퍼의 10분을 기다리는 동안
     * 8.5 재실행까지 그 작업을 돌려주며 막힌다(실측 20분 정지).
     */
    @Test
    void temporaryWorkerFailureSendsTheJobBackToTheQueue() {
        var handler = new TransactionClassificationHandler(
                clientThat((path, jobId, body) -> {
                    throw new AiWorkerException("연결 실패", new RuntimeException("connection refused"));
                }),
                dictionaryThatMatches(0),
                factoryReturning(Optional.of(request())));

        assertThat(handler.handle(job())).isEqualTo(JobHandler.Outcome.NOT_ACCEPTED);
    }

    /** 큐에 넣은 쪽의 버그다. 워커 스레드에는 SecurityContext 가 없어 사업장을 알아낼 길이 없다. */
    @Test
    void failsWhenJobHasNoBusiness() {
        var handler = new TransactionClassificationHandler(
                clientThat((path, jobId, body) -> {
                    throw new AssertionError("사업장이 없으면 워커를 부르지 않아야 한다");
                }),
                dictionaryThatMatches(0),
                factoryReturning(Optional.of(request())));

        assertThatThrownBy(() -> handler.handle(
                Job.queue(null, 1L, JobType.TRANSACTION_CLASSIFICATION, null)))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).errorCode())
                .isEqualTo(ErrorCode.VALIDATION_FAILED);
    }

    /**
     * 사전이 전부 처리하면 워커를 부르지 않고, <b>그 수를 결과에 남긴다</b>.
     *
     * <p>이 경로에는 콜백이 없다 — 워커를 안 불렀으니 결과를 적을 기회가 여기뿐이다.
     * 남기지 않으면 화면의 "N건 분류" 토스트가 0 을 띄운다.
     */
    @Test
    void reportsDictionaryCountWhenTheWorkerIsNotNeeded() {
        var handler = new TransactionClassificationHandler(
                clientThat((path, jobId, body) -> {
                    throw new AssertionError("사전이 다 처리했으면 워커를 부르지 않아야 한다");
                }),
                dictionaryThatMatches(7),
                factoryReturning(Optional.empty()));

        JobHandler.Outcome outcome = handler.handle(job());

        Map<String, Object> result = ((JobHandler.Outcome.Completed) outcome).result();
        assertThat(result)
                .containsEntry("dictionaryCount", 7)
                .containsEntry("classifiedCount", 7)
                .containsEntry("needsConfirmationCount", 0);
    }

    // --- helpers -------------------------------------------------------------

    private static final TransactionClassificationRequest REQUEST =
            new TransactionClassificationRequest(
                    77L,
                    new BusinessContext("FOOD_CAFE"),
                    List.of("RENT", "PAYROLL"),
                    List.of(new FewShotExample("우아한형제들", FlowType.DEPOSIT, "PLATFORM_SETTLEMENT")),
                    List.of(new TransactionToClassify(88031L, "김영자", FlowType.WITHDRAWAL,
                            1200000L, "2026-08-25T01:00:00Z")));

    private TransactionClassificationRequest request() {
        return REQUEST;
    }

    /**
     * 사전 단계를 갈아끼운다. 무엇이 걸리는지는
     * {@code DictionaryClassificationTests} 가 본다 — 여기서 볼 것은 순서뿐이다.
     */
    private DictionaryClassificationService dictionaryThatMatches(int matched) {
        return new DictionaryClassificationService(null, null, null) {
            @Override
            public int classify(Long businessId) {
                return matched;
            }
        };
    }

    private interface Call {
        void accept(String path, Long jobId, Object body);
    }

    /** {@code requestAsync} 만 갈아끼운다. RestClient 는 쓰이지 않는다. */
    private AiWorkerClient clientThat(Call call) {
        return new AiWorkerClient(RestClient.create()) {
            @Override
            public void requestAsync(String path, Long jobId, Object requestBody) {
                call.accept(path, jobId, requestBody);
            }
        };
    }

    /**
     * 조립 결과만 갈아끼운다. 조립기의 의존성 다섯 개는 쓰이지 않아 {@code null} 로 둔다 —
     * 무엇을 담는지는 {@code TransactionClassificationRequestFactoryTests} 가 본다.
     */
    private TransactionClassificationRequestFactory factoryReturning(
            Optional<TransactionClassificationRequest> result) {
        return new TransactionClassificationRequestFactory(null, null, null, null, null) {
            @Override
            public Optional<TransactionClassificationRequest> create(Long jobId, Long businessId) {
                return result;
            }
        };
    }

    private Job job() {
        return Job.queue(11L, 1L, JobType.TRANSACTION_CLASSIFICATION, null);
    }
}
