package com.jangoyebo.forecast.planned;

/**
 * 예정 거래의 출처 (API 9.4).
 *
 * <p>삭제 가능 여부가 여기서 갈린다 — {@link #USER} 만 지울 수 있고 나머지는
 * {@code CANCELLED} 처리다({@code PLANNED_TX_NOT_DELETABLE}).
 */
public enum PlannedSourceType {

    /** 사용자가 직접 등록 (P0-06). */
    USER,

    /** 확정된 반복 패턴이 생성 (P0-04). */
    PATTERN,

    /** 부가세·세금 일정에서 서버가 생성 (P1-11·P1-18). 사용자는 금액만 수정한다. */
    TAX,

    /** 대출 상환 스케줄에서 생성 (P1-13). */
    LOAN
}
