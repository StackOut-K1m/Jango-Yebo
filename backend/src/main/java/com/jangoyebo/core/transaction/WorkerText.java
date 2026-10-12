package com.jangoyebo.core.transaction;

/**
 * 워커 스키마의 문자열 상한을 지키게 자른다.
 *
 * <p>{@code ai/app/nlp/schemas.py} 가 {@code description}·{@code merchantName} 에
 * {@code max_length=200} 을 걸어 뒀다. <b>한 건이 넘으면 요청 전체가 422 다</b> — pydantic
 * 검증은 항목 단위가 아니라 본문 단위라, 적요가 긴 거래 하나 때문에 같은 Job 의 나머지
 * 전부가 분류되지 않는다.
 *
 * <p>우리 {@code description} 컬럼은 그보다 길 수 있어(금융망 적요·CSV 임포트 모두 자유
 * 문자열) 보내는 쪽에서 맞춘다. 잘린 값은 요청에만 쓰이고 DB 는 그대로다.
 */
final class WorkerText {

    /** {@code ai/app/nlp/schemas.py} 의 {@code max_length}. 바꾸려면 워커와 함께 바꾼다. */
    private static final int MAX_LENGTH = 200;

    private WorkerText() {
    }

    /** 빈 문자열·공백은 {@code null} 로 본다 — 없는 값과 있으나 빈 값을 가르지 않는다. */
    static String clip(String text) {
        if (text == null || text.isBlank()) {
            return null;
        }
        String trimmed = text.strip();
        return trimmed.length() <= MAX_LENGTH ? trimmed : trimmed.substring(0, MAX_LENGTH);
    }
}
