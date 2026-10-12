package com.jangoyebo.core.transaction;

import com.jangoyebo.common.domain.FlowType;

import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * 워커 요청 본문 (API 20.3 · {@code ai/app/nlp/schemas.py} 의
 * {@code TransactionClassificationRequest}).
 *
 * <p><b>업종만 보낸다.</b> 사업장명·사업자등록번호는 분류에 필요하지 않고, 보내면 워커의
 * 로그 금지 대상만 늘어난다(20.1 입력 위생).
 *
 * @param allowedCategories 활성 23종 전부, <b>모든 사업장에 같은 순서</b>로 보낸다.
 *                          워커의 프롬프트 프리픽스 캐시가 정확 일치라 사업장마다 목록이
 *                          갈리면 캐시도 갈린다(합의 ②)
 * @param transactions      워커가 {@code BATCH_MAX}(20) 씩 잘라 순차로 부르고 콜백은 한 번만
 *                          보낸다(합의 ①). 상한은 {@code REQUEST_MAX}(400) 이다
 */
public record TransactionClassificationRequest(Long jobId,
                                               BusinessContext business,
                                               List<String> allowedCategories,
                                               List<FewShotExample> fewShotExamples,
                                               List<TransactionToClassify> transactions) {

    /** @param industryType {@code FOOD_CAFE}·{@code RETAIL} 등 7종 (API 7.1) */
    public record BusinessContext(String industryType) {
    }

    /**
     * 분류할 거래 한 건.
     *
     * <p><b>{@code cycleHint} 를 넣지 않는다.</b> 계약에는 있고 워커는 "넣어 주면 정확도가
     * 오른다"고 적었지만, 그 값을 만들려면 반복 패턴 탐지(P0-03)와 결선해야 한다. 지금 단계의
     * 범위 밖이고, 없어도 계약상 선택 필드다. 붙일 때는 여기 필드 하나만 늘면 된다.
     *
     * <p><b>{@code occurredAt} 을 문자열로 담는 이유.</b> 이 요청을 보내는
     * {@code RestClient} 는 {@code AiClientConfig} 가 직접 조립해 <b>{@code spring.jackson}
     * 설정을 받지 않는다</b> — 날짜를 어떤 모양으로 쓸지가 Jackson 기본값에 달리고, 그 기본값은
     * 버전에 따라 바뀐다(에포크 정수 ↔ ISO 문자열). 계약이 요구하는 모양이 하나뿐이므로
     * 여기서 못박는다.
     *
     * @param amount 부호 없는 금액. 방향은 {@code direction} 이 들고 있으므로 부호는 의미가
     *               없고, 워커 스키마가 {@code ge=0} 이라 음수면 요청 전체가 422 다
     */
    public record TransactionToClassify(Long transactionId,
                                        String description,
                                        FlowType direction,
                                        long amount,
                                        String occurredAt) {

        public TransactionToClassify {
            description = WorkerText.clip(description);
        }

        static TransactionToClassify of(Long transactionId, String description,
                                        FlowType direction, long amount, Instant occurredAt) {
            return new TransactionToClassify(transactionId, description, direction, amount,
                    DateTimeFormatter.ISO_INSTANT.format(occurredAt));
        }
    }
}
