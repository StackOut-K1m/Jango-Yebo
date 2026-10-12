package com.jangoyebo.core.support;

import com.jangoyebo.core.job.Job;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 구조화 콜백 결과를 공고에 반영한다 (명세 20.6 → 17.1·17.2).
 *
 * <p><b>여기서 자격을 판정하지 않는다.</b> 워커가 준 조건을 그대로 나른다 — 「이 사업장이
 * 대상인가」는 17.3 의 규칙 엔진이 정한다(기술스택 6.6, 도메인 규칙 4와 같은 이유).
 */
@Service
public class SupportProgramStructuringService {

    private static final Logger log =
            LoggerFactory.getLogger(SupportProgramStructuringService.class);

    private final SupportProgramRepository programs;

    public SupportProgramStructuringService(SupportProgramRepository programs) {
        this.programs = programs;
    }

    /**
     * <b>{@code @Transactional} 을 붙이지 않는다.</b> 항상 {@code AiResultApplier.applyResult}
     * 의 트랜잭션 안에서 불리므로 새 경계가 필요 없고, <b>경계를 만들면 오히려 해롭다</b> —
     * 여기서 예외가 나가는 순간 Spring 이 바깥 트랜잭션을 rollback-only 로 표시하고,
     * applier 가 "저장에 실패해도 Job 은 완료로 둔다"며 catch 해도 커밋에서
     * {@code UnexpectedRollbackException} 이 터진다. 워커는 500 을 받아 재시도하고,
     * 같은 예외가 다시 나므로 <b>결과가 영영 반영되지 않는다.</b> 실제로 겪었다.
     */
    public void apply(Job job, Map<String, Object> result) {
        Long programId = programIdOf(job);
        SupportProgram program = programs.findById(programId).orElse(null);
        if (program == null) {
            log.warn("작업 {} 의 공고 {} 가 없다 — 결과는 JOB.result 에 남는다",
                    job.getId(), programId);
            return;
        }

        // 출처가 적어 둔 기간이 있으면 엔티티가 이 값을 안 쓴다 — 기업마당
        // reqstBeginEndDe 가 정본이고, 모델 값은 그게 비었을 때만 쓰인다.
        LocalDate applyStart = dateOf(result.get("applyStart"));
        LocalDate applyEnd = dateOf(result.get("applyEnd"));

        program.applyStructuring(
                stringOf(result.get("supportType")),
                amountOf(result.get("maxAmount")),
                // 연 % 다 — 대출 쪽(V23 `interest_rate`)과 같은 단위로 맞췄다.
                amountOf(result.get("interestRate")),
                applyStart,
                applyEnd,
                // ⚠ **대개 비어 있고 그것이 맞는 결과다.** 실제 공고 32건 중 지급 시기를
                // 말한 것이 1건이라, 워커는 원문에 일수가 적힌 경우에만 채운다. 17.3 이
                // 이 값으로 「지원금이 공백 전에 도착한다」를 말하므로, 없는 값을 지어내면
                // 사장님이 오지 않을 돈을 기다리며 다른 자금 대책을 접는다.
                intOf(result.get("expectedDisbursementDays")),
                eligibilityOf(result));

        log.info("공고 {} 구조화 반영 — 종류={} 기간={}~{} 상태={} 확인필요={}",
                programId, program.getSupportType(), program.getApplicationStartDate(),
                program.getApplicationEndDate(), program.getStatus(),
                program.needsVerification());
    }

    /**
     * 자격 조건 덩어리.
     *
     * <p><b>{@code requiredDocuments} 를 여기 같이 담는다.</b> ERD 가 {@code eligibility} 를
     * 「지역·업종·매출 상한·업력·제외 대상·불확실 조건·<b>필수 서류</b>」로 정의했다.
     * 서류 발급 링크는 여기 넣지 않는다 — 17.2 가 링크의 정본을 서류 사전(17.4)으로 정했고,
     * 공고 본문에서 주워 온 URL 을 실으면 죽은 링크가 그대로 사용자에게 나간다.
     */
    private Map<String, Object> eligibilityOf(Map<String, Object> result) {
        Map<String, Object> eligibility = new LinkedHashMap<>();
        if (result.get("eligibility") instanceof Map<?, ?> raw) {
            raw.forEach((key, value) -> eligibility.put(String.valueOf(key), value));
        }
        if (result.get("requiredDocuments") instanceof java.util.List<?> documents) {
            eligibility.put("requiredDocuments", documents);
        }
        return eligibility;
    }

    private Long programIdOf(Job job) {
        Map<String, Object> params = job.getParams();
        Object value = params == null ? null : params.get("programId");
        return value instanceof Number number ? number.longValue() : null;
    }

    private String stringOf(Object value) {
        return value instanceof String text && !text.isBlank() ? text : null;
    }

    private BigDecimal amountOf(Object value) {
        return value instanceof Number number ? new BigDecimal(number.toString()) : null;
    }

    /**
     * 정수 칸.
     *
     * <p><b>소수는 버린다.</b> 「30.5일」은 워커가 만들어 낸 값이지 공고에 적힌 값이 아니다 —
     * 반올림해 받으면 지어낸 숫자에 근거를 하나 더 얹는 셈이다.
     */
    private Integer intOf(Object value) {
        if (!(value instanceof Number number)) {
            return null;
        }
        double raw = number.doubleValue();
        return raw == Math.rint(raw) ? (int) raw : null;
    }

    private LocalDate dateOf(Object value) {
        return value instanceof String text && !text.isBlank() ? LocalDate.parse(text) : null;
    }
}
