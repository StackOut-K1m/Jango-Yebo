package com.jangoyebo.core.ai;

import com.jangoyebo.common.error.BusinessException;
import com.jangoyebo.common.error.ErrorCode;
import com.jangoyebo.core.job.Job;
import com.jangoyebo.core.job.JobHandler;
import com.jangoyebo.core.job.JobType;
import com.jangoyebo.core.support.SupportProgram;
import com.jangoyebo.core.support.SupportProgramRepository;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * 지원사업 공고문 구조화를 AI 워커에 접수한다 (명세 20.6, P1-15).
 *
 * <p><b>사업장이 없는 첫 핸들러다.</b> 공고는 사업장 공용 데이터라 {@code job.businessId}
 * 가 {@code null} 이다(V21). 다른 핸들러들이 쓰는 사업장 범위 검사가 여기엔 없고, 대상은
 * {@code params.programId} 하나로 정해진다.
 */
@Component
public class ProgramStructuringHandler implements JobHandler {

    private static final Logger log = LoggerFactory.getLogger(ProgramStructuringHandler.class);
    private static final String PATH = "/internal/v1/ai/program-structuring";

    /**
     * 워커 {@code RAW_TEXT_MAX} 와 같은 값이다 ({@code ai/app/nlp/schemas.py}).
     *
     * <p><b>넘으면 자르지 않고 실패시킨다.</b> 잘라 보내면 모델은 그것이 전문인 줄 알고
     * 읽는다 — 뒤쪽에 있던 「국세 체납자 제외」 같은 조항이 사라진 채
     * {@code uncertainConditions} 는 비어서 돌아오고, 사장님은 자격이 있다고 읽고 서류를
     * 준비한 뒤 기관에서 거절당한다. <b>이 기능의 안전장치를 정확히 무력화하는 방식</b>이라
     * 「반쯤 읽기」보다 「안 읽기」가 낫다. 구조화되지 않은 공고는 검색에서 빠질 뿐이고
     * 원문은 DB 에 남아 나중에 나눠 읽을 수 있다.
     */
    private static final int RAW_TEXT_MAX = 50_000;

    private final AiWorkerClient client;
    private final SupportProgramRepository programs;

    public ProgramStructuringHandler(AiWorkerClient client, SupportProgramRepository programs) {
        this.client = client;
        this.programs = programs;
    }

    @Override
    public JobType jobType() {
        return JobType.PROGRAM_STRUCTURING;
    }

    @Override
    public Outcome handle(Job job) {
        Long programId = requiredLong(job, "programId");
        SupportProgram program = programs.findById(programId)
                .orElseThrow(() -> new BusinessException(ErrorCode.VALIDATION_FAILED,
                        "공고 %d 를 찾을 수 없습니다.".formatted(programId)));

        String rawText = program.getRawText();
        if (rawText == null || rawText.isBlank()) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED,
                    "공고 %d 에 원문이 없습니다.".formatted(programId));
        }
        if (rawText.length() > RAW_TEXT_MAX) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED,
                    "공고 %d 원문이 %d자로 상한(%d)을 넘습니다."
                            .formatted(programId, rawText.length(), RAW_TEXT_MAX));
        }

        var request = new StructuringRequest(job.getId(), programId, rawText,
                program.getSourceUrl());

        try {
            client.requestAsync(PATH, job.getId(), request);
            return Outcome.AWAITING_CALLBACK;
        } catch (AiWorkerException e) {
            if (e.isPermanent()) {
                throw new BusinessException(ErrorCode.AI_ANALYSIS_FAILED, e.getMessage());
            }
            // 워커가 이 작업을 받지 못했다. 대기열로 되돌린다 — 외부에 일이 떠 있지 않아
            // 다시 보내도 중복 과금이 없다(JobHandler.Outcome 주석).
            log.warn("작업 {} 공고 구조화 접수 실패 — 대기열로 되돌린다: {}",
                    job.getId(), e.getMessage());
            return Outcome.NOT_ACCEPTED;
        }
    }

    /**
     * 워커 요청 본문 (명세 20.6 · {@code ai/app/nlp/schemas.py} 의
     * {@code ProgramStructuringRequest}).
     *
     * <p>{@code sourceUrl} 은 모델에게 읽히려는 것이 아니라 결과에 되돌려 붙이기 위한
     * 것이다 — 17.1 이 원문 링크 병기를 못박았다.
     */
    record StructuringRequest(Long jobId, Long programId, String rawText, String sourceUrl) {
    }

    /** JSONB 역직렬화에서 정수는 {@code Integer} 로 온다. */
    private Long requiredLong(Job job, String key) {
        Map<String, Object> params = job.getParams();
        Object value = params == null ? null : params.get(key);
        if (value instanceof Number number) {
            return number.longValue();
        }
        throw new BusinessException(ErrorCode.VALIDATION_FAILED,
                "작업 %d 의 params 에 %s 가 없습니다.".formatted(job.getId(), key));
    }
}
