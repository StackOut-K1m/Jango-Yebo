package com.jangoyebo.core.ai;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.Map;

/**
 * AI 워커 콜백 본문 (명세 20.2).
 *
 * <p><b>관대함이 유일한 설계 목표다.</b> 역직렬화가 실패하면 {@code GlobalExceptionHandler} 가
 * 400 을 내고, 워커는 {@code < 500} 을 전부 영구 실패로 보아 재시도 없이 포기한다
 * ({@code ai/app/common/callback.py}). 즉 <b>파싱 실패 = AI 결과 유실</b>이다.
 *
 * <p>그래서 여기에는:
 * <ul>
 *   <li>jakarta 검증 애노테이션을 <b>붙이지 않는다</b> — {@code @Valid} 실패도 400 이다.
 *       검증은 서비스가 하고, 이상한 값은 관대한 기본값으로 흡수한다.
 *   <li>원시 타입을 쓰지 않는다 — {@code boolean success} 로 두면 키 누락이 조용히
 *       {@code false} 가 되어 <b>성공한 작업이 FAILED 로 저장</b>된다.
 *   <li>{@code data} 를 {@code Map} 으로 받는다. 작업 유형마다 스키마가 다르고,
 *       {@code Job.result} 가 같은 타입이라 변환 없이 그대로 저장된다.
 * </ul>
 */
public final class AiCallbackDtos {

    private AiCallbackDtos() {
    }

    /**
     * @param success 워커가 항상 보내지만 누락에 대비해 {@code Boolean} 이다.
     * @param data    성공 시 {@code status} + 모델 메타({@code modelName}·{@code modelVersion}·
     *                {@code promptVersion}·{@code pipeline}) + 작업별 결과를 평면 병합한 것.
     * @param error   실패 시에만 값이 있다.
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record ResultRequest(Boolean success,
                                Map<String, Object> data,
                                ErrorBody error) {
    }

    /** @param detail 워커가 값이 있을 때만 키를 만든다. 부재는 {@code null} 로 흡수된다. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record ErrorBody(String code, String message, Map<String, Object> detail) {
    }
}
