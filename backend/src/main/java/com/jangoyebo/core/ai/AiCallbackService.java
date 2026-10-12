package com.jangoyebo.core.ai;

import com.jangoyebo.common.ai.AiProperties;
import com.jangoyebo.common.error.BusinessException;
import com.jangoyebo.common.error.ErrorCode;
import com.jangoyebo.common.idempotency.RequestIdStore;
import com.jangoyebo.core.ai.AiCallbackDtos.ResultRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * AI 워커 콜백의 멱등 처리와 오케스트레이션 (명세 20.2 · 2.8).
 *
 * <p><b>트랜잭션을 걸지 않는다.</b> 멱등 키 보상은 DB 결과가 확정된 뒤에 실행돼야 하고,
 * {@code @Transactional} 메서드를 자기 호출하면 프록시를 타지 않는다. 반영은
 * {@link AiResultApplier} 가 한다.
 */
@Service
public class AiCallbackService {

    private static final Logger log = LoggerFactory.getLogger(AiCallbackService.class);

    /** 워커의 상한과 같다({@code ai/app/common/request_id.py}). */
    private static final int REQUEST_ID_LIMIT = 128;

    private final AiResultApplier applier;
    private final RequestIdStore requestIds;
    private final AiProperties properties;

    public AiCallbackService(AiResultApplier applier, RequestIdStore requestIds, AiProperties properties) {
        this.applier = applier;
        this.requestIds = requestIds;
        this.properties = properties;
    }

    /** 처리 결과. 컨트롤러는 둘 다 200 으로 응답한다 — 4xx 는 워커가 영구 실패로 기록한다. */
    public enum Outcome {
        APPLIED,
        DUPLICATE
    }

    public Outcome receive(Long jobId, String requestId, ResultRequest body) {
        String id = requireRequestId(requestId);
        if (body == null) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "콜백 본문이 없습니다.");
        }

        // jobId 를 네임스페이스에 넣는다. requestId 만으로 키를 만들면 워커가 잘못된 ID 를
        // 보냈을 때 다른 작업의 콜백까지 idempotency-ttl 동안 막힌다.
        String scope = "ai-job:" + jobId;

        switch (requestIds.reserve(scope, id, properties.callback().claimTtl())) {
            case IN_PROGRESS ->
                    // 결과를 아직 모른다. 200 을 주면 1차가 실패했어도 워커는 성공으로 안다.
                    // 5xx 로 내보내 0.5초 뒤 재시도를 유도한다.
                    throw new BusinessException(ErrorCode.INTERNAL_ERROR, "동일 콜백을 처리하는 중입니다.");
            case DONE ->
                    // 키가 DONE 이어도 여기서 끝내지 않는다. 스위퍼가 회수해 재실행된 경우
                    // 키만 DONE 이고 작업은 미완결일 수 있고, 그때 무시하면 작업이 영구히
                    // PROCESSING 에 갇힌다. 진짜 중복인지는 아래 applyResult 가 DB 로 판정한다
                    // — Redis 는 동시 도착을 막을 뿐 최종 권위가 아니다.
                    requestIds.takeOver(scope, id, properties.callback().claimTtl());
            case RESERVED -> {
                // 정상 경로
            }
        }

        try {
            boolean applied = applier.applyResult(jobId, body);
            requestIds.markDone(scope, id, properties.callback().idempotencyTtl());
            return applied ? Outcome.APPLIED : Outcome.DUPLICATE;
        } catch (RuntimeException e) {
            // 반드시 해제한다. 남겨두면 그 작업의 재시도가 claim-ttl 동안 막힌다.
            requestIds.release(scope, id);
            throw e;
        }
    }

    /**
     * {@code @RequestHeader(required = true)} 를 쓰지 않고 직접 검사하는 이유:
     * {@code MissingRequestHeaderException} 은 {@code GlobalExceptionHandler} 에 핸들러가
     * 없어 500 으로 떨어진다. 워커 버그를 서버 오류로 기록하게 된다.
     */
    private String requireRequestId(String requestId) {
        if (requestId == null || requestId.isBlank()) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "X-Request-Id 헤더가 필요합니다.");
        }
        if (requestId.length() > REQUEST_ID_LIMIT) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED,
                    "X-Request-Id 는 %d자를 넘을 수 없습니다.".formatted(REQUEST_ID_LIMIT));
        }
        return requestId;
    }
}
