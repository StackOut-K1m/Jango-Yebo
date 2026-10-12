package com.jangoyebo.core.evidence;

/**
 * 거래–증빙 연결 상태 (ERD {@code TRANSACTION_EVIDENCE}).
 *
 * <p>사용자가 직접 연결하면 바로 {@link #LINKED} 다. {@link #CANDIDATE} 는 AI 매칭
 * 추천(P1-02)이 만드는 상태이며, 사용자가 받으면 {@code LINKED}, 아니면 {@code REJECTED} 가 된다.
 *
 * <p><b>{@code REJECTED} 를 삭제로 대신하지 않는다.</b> 지우면 다음 매칭 배치가 같은 쌍을
 * 다시 추천해 사용자가 같은 거절을 반복하게 된다.
 */
public enum MatchStatus {
    CANDIDATE,
    LINKED,
    REJECTED
}
