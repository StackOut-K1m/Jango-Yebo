package com.jangoyebo.core.job;

/** API 명세 2.7의 status. */
public enum JobStatus {
    QUEUED,
    PROCESSING,
    COMPLETED,
    FAILED;

    /**
     * 더 이상 바뀌지 않는 상태인가.
     *
     * <p>콜백 중복 판정의 최종 근거다 — Redis 멱등 키가 없거나 사라져도 이 값으로 판정할 수
     * 있어야 한다(멱등 저장소는 동시 도착만 막는다).
     */
    public boolean isTerminal() {
        return this == COMPLETED || this == FAILED;
    }
}
