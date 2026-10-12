package com.jangoyebo.core.transaction;

import com.jangoyebo.common.domain.FlowType;

/**
 * LLM 분류 프롬프트에 붙는 예시 한 건 (API 20.3 {@code fewShotExamples}).
 *
 * <p>사용자가 정정·확정한 이력에서 만든다 — 이 사업장에서 <b>사람이 옳다고 한</b> 짝이라
 * 모델이 같은 거래처를 다시 틀리지 않게 한다.
 *
 * <p><b>{@code merchantName} 을 담지 않는다.</b> 계약에는 그 필드가 있지만(선택) 채울
 * 근거가 없다. 사용자가 확정하는 것은 <b>분류</b>이고 거래처명이 아니다 — 거래처명은 LLM 이
 * 추정해 {@code reason.merchantName} 에 남긴 값뿐이다. 그걸 예시로 되돌려 주면 <b>모델이
 * 자기 추측을 정답으로 학습</b>한다. 거래처 사전(P1-01 2단계)이 생겨 사람이 확인한 거래처명이
 * 쌓이면 그때 채운다.
 *
 * @param direction JSON 으로는 {@code "DEPOSIT"}·{@code "WITHDRAWAL"} 이다. 계약의 이름이
 *                  {@code direction} 이고 DB 의 이름이 {@code flow_type} 이다(ERD 11장)
 */
public record FewShotExample(String description, FlowType direction, String category) {

    /**
     * 적요를 워커 상한에 맞춰 자른다 — 예시 하나가 200자를 넘으면 요청 전체가 422 다
     * ({@link WorkerText}). JPQL 생성자 표현식도 이 생성자를 타므로 조회 경로에서도 적용된다.
     */
    public FewShotExample {
        description = WorkerText.clip(description);
    }
}
