package com.jangoyebo.core.alert;

/**
 * 인앱 알림 유형 (API 10.3).
 *
 * <p>DB {@code ck_alert_type} 과 값이 일치해야 한다. {@code ALERT} 가 {@code core} 스키마에 있는
 * 이유가 이 목록에 있다 — {@code CASH_GAP}·{@code SAFETY_LINE} 은 {@code forecast} 가,
 * 나머지는 {@code core} 가 만든다. 공용 테이블은 의존 방향상 {@code core} 가 소유해야 한다.
 */
public enum AlertType {

    /** 정산 공백 (P0-08). 이 알림의 id 가 API 의 {@code cashGapId} 다. */
    CASH_GAP,

    /** 안전선 하회 (P1-08). */
    SAFETY_LINE,

    /** 준비금 부족 (P1-09). */
    RESERVE_SHORTFALL,

    /** 정산 대사 불일치 (P0-11). */
    SETTLEMENT_MISMATCH,

    /** 새 반복 후보 (P0-03). */
    PATTERN_CANDIDATE,

    /** 증빙 누락 다건 (P0-10). */
    EVIDENCE_MISSING
}
