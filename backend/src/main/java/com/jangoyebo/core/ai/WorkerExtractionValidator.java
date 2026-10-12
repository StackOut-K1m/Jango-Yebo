package com.jangoyebo.core.ai;

import com.jangoyebo.core.evidence.ExtractionValidator;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * {@link ExtractionValidator} 의 워커 구현 (API 20.4 확장, 계약 v1.17).
 *
 * <p>검산 규칙은 워커에만 있다. 여기서 하는 일은 {@code extraction} 을 그대로 실어 보내고
 * {@code validation} 을 받아 오는 것뿐이다 — <b>이 클래스에 산술이 한 줄도 없는 것이 요점</b>이다.
 * 규칙이 여기 생기는 순간 워커와 갈라진다.
 */
@Component
public class WorkerExtractionValidator implements ExtractionValidator {

    private static final String PATH = "/internal/v1/ai/evidence-validation";

    private final AiWorkerClient client;

    public WorkerExtractionValidator(AiWorkerClient client) {
        this.client = client;
    }

    /**
     * <p>{@code lowConfidence} 를 보내지 않는다. 임계값을 주면 낮은 신뢰도가 검토 사유에
     * 들어가는데, <b>신뢰도에는 변별력이 없다</b> — 0.7 이 정밀도 23%, 0.85 가 25% 인데 그
     * 데이터의 기저 오류율이 16.7% 다(계약 v1.13 이 게이트에서 뺀 이유). 강조는 응답의
     * {@code fieldConfidence} 로 화면이 하고, 게이트에는 넣지 않는다.
     */
    @Override
    public Map<String, Object> revalidate(Map<String, Object> extraction) {
        Map<String, Object> data = client.postSyncOrNull(
                PATH, "evidence-revalidate", Map.of("extraction", extraction));
        // 응답 data 가 곧 ValidationReport 다(20.4 와 같은 구조).
        return data;
    }
}
