package com.jangoyebo.core.transaction;

/**
 * 분류를 누가·무엇이 했는가 (ERD {@code TRANSACTION_CLASSIFICATION_HISTORY}, API 8.2).
 *
 * <p>DB {@code ck_tch_classifier} 와 값이 일치해야 한다.
 *
 * <p>⚠ <b>API 응답에서는 {@link #LLM} 을 {@code AI} 로 내보낸다.</b> 네 값 중 이것만 이름이
 * 다르다(API 8.2 — "DB 는 이 값을 {@code LLM} 으로 저장한다"). DB 표기를 바꾸지 않는 이유는
 * {@code ck_tch_classifier} 가 이미 적용돼 동결이기 때문이다.
 */
public enum ClassifierType {

    /** 규칙 기반. 아직 없다(CLASSIFICATION_RULE 테이블 미생성). */
    RULE,

    /** 거래처 사전. 아직 없다(P1-01 2단계). */
    DICTIONARY,

    /** LLM 분류 (P1-01). 응답에서는 {@code AI} 다. */
    LLM,

    /** 사용자 확정. few-shot 예시의 원천이다. */
    USER;

    /**
     * API 응답에 나가는 표기 (8.2 {@code classification.source}).
     *
     * <p>네 값 중 {@link #LLM} 만 이름이 다르다. 변환을 여기 한 곳에 두는 이유는, 응답을 만드는
     * 자리마다 {@code == LLM ? "AI" : name()} 을 적으면 <b>한 곳을 빠뜨렸을 때 같은 값이 두
     * 이름으로 나가기</b> 때문이다.
     */
    public String apiValue() {
        return this == LLM ? "AI" : name();
    }
}
