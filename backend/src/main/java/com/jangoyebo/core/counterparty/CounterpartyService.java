package com.jangoyebo.core.counterparty;

import com.jangoyebo.common.domain.FlowType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 거래처 사전을 쌓는다 (API 8.6 · 도메인 규칙 3).
 *
 * <p><b>쌓기만 한다.</b> 분류 전에 사전을 보는 것은 다음 단계다 — 채우지 않은 사전으로 거르면
 * 아무것도 안 걸러지므로 순서가 이쪽이 먼저다.
 *
 * <p><b>두 출처를 구분해 담는다.</b> 사람이 확정한 것(확정 횟수 1 이상)과 모델이 판단한 것
 * (0)이다. 모델 것을 아예 안 담으면 같은 판단을 수백 번 반복하고(실측: 612건에 서로 다른
 * 판단은 61개뿐), 그대로 확정으로 담으면 <b>한 번 틀린 것이 계속 틀린다</b>.
 *
 * <p>그래서 담되 <b>확인 질문을 남긴다</b> — 모델 출처로 분류된 거래는 사장님에게 한 번 물어보고,
 * 답하면 그때 확정으로 승격된다. 호출은 아끼고 오류는 굳지 않는다.
 */
@Service
public class CounterpartyService {

    private static final Logger log = LoggerFactory.getLogger(CounterpartyService.class);

    private final CounterpartyRepository repository;

    public CounterpartyService(CounterpartyRepository repository) {
        this.repository = repository;
    }

    /**
     * 모델이 판단한 (적요 → 분류) 짝을 사전에 담는다 (AI 담당 요청 2026-09-14 ③).
     *
     * <p>같은 판단을 반복하지 않으려는 것이다 — 실측에서 {@code 신한카드 매출대금} 하나가
     * <b>124번</b> 분류됐고 매번 같은 답·같은 신뢰도였다.
     *
     * <p><b>사람이 확정한 항목은 덮지 않는다</b>(요청 4번). 신뢰도 판정은 호출부가 한다 —
     * 임계값은 분류 설정에 있고 이 클래스가 그것을 알 이유가 없다.
     */
    @Transactional
    public void rememberSuggestion(Long businessId, String description,
                                   FlowType flowType, Long categoryId) {
        if (businessId == null || categoryId == null || flowType == null
                || description == null || description.isBlank()) {
            return;
        }
        String canonicalName = description.strip();
        try {
            repository.findByBusinessIdAndCanonicalNameAndDefaultFlowType(
                            businessId, canonicalName, flowType)
                    .ifPresentOrElse(
                            existing -> existing.suggestAgain(categoryId),
                            () -> repository.save(Counterparty.fromSuggestion(
                                    businessId, canonicalName, flowType, categoryId)));
        } catch (DataIntegrityViolationException e) {
            log.debug("거래처 사전 동시 생성(제안) — 사업장 {} / 먼저 들어간 값을 쓴다", businessId);
        }
    }

    /**
     * 사용자가 확정한 (적요 → 분류) 짝을 사전에 반영한다.
     *
     * <p>호출부의 트랜잭션에 합류한다 — 확정이 되돌아가면 사전도 같이 되돌아가야 한다.
     *
     * <p><b>여기서 던지지 않는다.</b> 사전은 다음 분류를 아끼자는 것이지 확정의 조건이 아니다.
     * 쌓다가 실패했다고 사장님의 분류 저장을 되돌리면 <b>보이는 손해가 훨씬 크다.</b>
     *
     * @param description 거래 적요. 비어 있으면 아무것도 하지 않는다 — 열쇠가 없다
     */
    @Transactional
    public void rememberConfirmation(Long businessId, String description,
                                     FlowType flowType, Long categoryId) {
        if (businessId == null || categoryId == null || flowType == null
                || description == null || description.isBlank()) {
            return;
        }
        String canonicalName = description.strip();
        try {
            // 방향까지 열쇠다 — 같은 적요의 입금과 출금은 서로 다른 거래처로 기억한다.
            repository.findByBusinessIdAndCanonicalNameAndDefaultFlowType(
                            businessId, canonicalName, flowType)
                    .ifPresentOrElse(
                            existing -> existing.confirmAgain(categoryId),
                            () -> repository.save(Counterparty.firstConfirmation(
                                    businessId, canonicalName, flowType, categoryId)));
        } catch (DataIntegrityViolationException e) {
            // 같은 적요·방향을 두 요청이 동시에 처음 확정했다. uk_cp_business_name_flow 가 막은
            // 것이고, 진 쪽이 할 일은 없다 — 이긴 쪽이 같은 값을 이미 넣었다.
            log.debug("거래처 사전 동시 생성 — 사업장 {} / 먼저 들어간 값을 쓴다", businessId);
        }
    }
}
