package com.jangoyebo.core.ai;

import com.jangoyebo.common.error.BusinessException;
import com.jangoyebo.common.error.ErrorCode;
import com.jangoyebo.core.job.Job;
import com.jangoyebo.core.job.JobHandler;
import com.jangoyebo.core.job.JobType;
import com.jangoyebo.core.report.BriefingSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * 브리핑 문장 생성을 AI 워커에 접수한다 (명세 20.7, P1-17).
 *
 * <p><b>숫자를 보내지 않고 지표를 보낸다.</b> 워커 요청에는 원본 거래를 담을 필드가 아예
 * 없다 — 거래를 주면 모델이 직접 세기 시작하고, 그것이 도메인 규칙 2 가 막는 것이다.
 * 입력을 계산 엔진 산출값으로 좁히는 것이 환각을 <b>구조로</b> 막는 방법이다.
 *
 * <p><b>{@code sourceRef} 는 빼고 보낸다.</b> 근거 연결은 API 서버가 하는 일이고 워커는 알
 * 필요가 없다 — 보내면 프롬프트만 길어진다(워커 스키마 주석).
 *
 * <p><b>값이 없는 지표도 빼고 보낸다.</b> 워커의 {@code value} 는 필수 필드라 「안전선 최초
 * 하회일」처럼 날짜만 있는 줄을 그대로 보내면 요청이 통째로 거절된다. 그 줄은 화면에는
 * 남고 문장 생성에만 빠진다.
 */
@Component
public class BriefingGenerationHandler implements JobHandler {

    private static final Logger log = LoggerFactory.getLogger(BriefingGenerationHandler.class);
    private static final String PATH = "/internal/v1/ai/briefing-generation";

    /** 워커 {@code METRICS_MAX} 와 같은 값이다({@code ai/app/nlp/schemas.py}). */
    private static final int METRICS_MAX = 20;

    /** 명세 20.7 예시 그대로. 워커 시스템 프롬프트에도 같은 규칙이 있어 이중이다. */
    private static final String TONE_GUIDE =
            "확정 표현 금지, 조언은 권장 수준, 입력에 없는 수치 생성 금지";

    private final AiWorkerClient client;
    private final BriefingSource briefings;

    public BriefingGenerationHandler(AiWorkerClient client, BriefingSource briefings) {
        this.client = client;
        this.briefings = briefings;
    }

    @Override
    public JobType jobType() {
        return JobType.BRIEFING_GENERATION;
    }

    @Override
    public Outcome handle(Job job) {
        Object raw = job.getParams() == null ? null : job.getParams().get("briefingId");
        if (!(raw instanceof Number number)) {
            // 큐에 넣은 쪽의 버그다. 재시도해도 같으므로 즉시 실패로 확정한다.
            throw new BusinessException(ErrorCode.VALIDATION_FAILED,
                    "작업 %d 에 briefingId 가 없습니다.".formatted(job.getId()));
        }
        long briefingId = number.longValue();

        BriefingSource.Snapshot snapshot = briefings.snapshot(briefingId);
        List<Map<String, Object>> metrics = snapshot.metrics().stream()
                .filter(metric -> metric.get("value") != null)
                .limit(METRICS_MAX)
                .map(BriefingGenerationHandler::forWorker)
                .toList();

        if (metrics.isEmpty()) {
            // 값 있는 지표가 하나도 없다. 워커는 min_length=1 이라 거절하고, 무엇보다
            // 쓸 문장이 없다 — 부르지 않고 실패로 닫아 지표만 남긴다.
            throw new BusinessException(ErrorCode.VALIDATION_FAILED,
                    "브리핑 %d 에 문장을 쓸 지표가 없습니다.".formatted(briefingId));
        }

        var request = new BriefingRequest(job.getId(), briefingId, snapshot.periodType(),
                metrics, TONE_GUIDE);

        try {
            client.requestAsync(PATH, job.getId(), request);
            return Outcome.AWAITING_CALLBACK;
        } catch (AiWorkerException e) {
            if (e.isPermanent()) {
                throw e;
            }
            log.warn("브리핑 {} 접수 실패 — 대기열로 되돌린다", briefingId);
            return Outcome.NOT_ACCEPTED;
        }
    }

    /** 워커가 읽는 모양으로 줄인다 — {@code label}·{@code value}·{@code changeRate} 뿐이다. */
    private static Map<String, Object> forWorker(Map<String, Object> metric) {
        Map<String, Object> row = new java.util.LinkedHashMap<>();
        row.put("label", metric.get("label"));
        row.put("value", metric.get("value"));
        if (metric.get("changeRate") != null) {
            row.put("changeRate", metric.get("changeRate"));
        }
        return row;
    }

    record BriefingRequest(Long jobId, Long briefingId, String periodType,
                           List<Map<String, Object>> metrics, String toneGuide) {
    }
}
