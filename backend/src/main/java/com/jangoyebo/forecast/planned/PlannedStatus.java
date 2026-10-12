package com.jangoyebo.forecast.planned;

/**
 * 예정 거래 상태 (API 9.1).
 *
 * <p>물리 삭제 대신 {@link #CANCELLED} 로 남기는 것이 원칙이다 — 패턴이 만든 예정 거래를
 * 지우면 다음 롤링 배치가 같은 것을 다시 만들어 사용자의 취소 의사가 사라진다.
 */
public enum PlannedStatus {

    /** 패턴에서 생성된 예측 상태. */
    EXPECTED,

    /** 사용자가 등록·확정한 거래. */
    CONFIRMED,

    /** 실제 거래와 매칭 완료 (P1-05). */
    MATCHED,

    /** 예정일이 지났으나 실제 거래 미발생. */
    MISSED,

    /** 사용자가 취소. */
    CANCELLED
}
