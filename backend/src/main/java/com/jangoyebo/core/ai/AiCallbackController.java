package com.jangoyebo.core.ai;

import com.jangoyebo.common.response.ApiResponse;
import com.jangoyebo.core.ai.AiCallbackDtos.ResultRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * AI 워커 → 서버 결과 콜백 (명세 20.2).
 *
 * <p>경로가 {@code /api/v1} 하위가 <b>아니다.</b> 서비스 토큰으로 인증하는 별개 트리이며
 * {@code InternalSecurityConfig} 가 지킨다.
 *
 * <p><b>상태코드가 워커의 동작을 결정한다.</b> 워커는 {@code < 500} 을 전부 영구 실패로 보고
 * 재시도하지 않는다. 그래서 중복 콜백에도 409 가 아니라 200 을 주고, 일시적 실패(DB 락·Redis
 * 예외)는 반드시 5xx 로 나가야 한다 — {@code GlobalExceptionHandler} 의
 * {@code INTERNAL_ERROR} 경로가 그 역할을 한다.
 *
 * <p><b>5초 예산</b>: 워커 클라이언트 타임아웃이 5초다. 여기에 아웃바운드 HTTP·파일 I/O·대량
 * DB 쓰기를 붙이면 타임아웃 → 재시도 → 중복 압력이 생긴다. 무거운 후처리가 필요하면
 * Job 을 하나 더 큐잉한다(결과는 이미 {@code Job.result} 에 안전하게 있다).
 */
@RestController
@RequestMapping("/internal/v1/ai-jobs")
public class AiCallbackController {

    private final AiCallbackService callbackService;

    public AiCallbackController(AiCallbackService callbackService) {
        this.callbackService = callbackService;
    }

    @PostMapping("/{jobId}/result")
    public ResponseEntity<ApiResponse<Void>> receiveResult(
            @PathVariable Long jobId,
            // required = false 로 받고 서비스가 검사한다 — 누락을 500 으로 만들지 않기 위해서다.
            @RequestHeader(name = "X-Request-Id", required = false) String requestId,
            @RequestBody(required = false) ResultRequest body) {

        AiCallbackService.Outcome outcome = callbackService.receive(jobId, requestId, body);

        // 워커는 본문을 읽지 않지만 명세 20.1(요청·응답 모두 공통 Envelope)을 지킨다.
        // X-Idempotent-Replay 는 curl 수동 검증에서 중복 판정을 눈으로 보기 위한 것으로,
        // 워커 계약에는 영향이 없다.
        return ResponseEntity.ok()
                .header("X-Idempotent-Replay",
                        String.valueOf(outcome == AiCallbackService.Outcome.DUPLICATE))
                .body(ApiResponse.ok());
    }
}
