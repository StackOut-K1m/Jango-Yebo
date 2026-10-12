package com.jangoyebo.forecast.pattern;

/**
 * 반복 주기 (API 9.2 {@code cycle.type}).
 *
 * <p>DB {@code ck_rp_cadence} 와 값이 일치해야 한다.
 */
public enum CadenceType {

    /** 요일 반복 — 매주 화요일 플랫폼 정산 같은 것. */
    WEEKLY,

    /** 일자 반복 — 매월 5일 임대료. */
    MONTHLY,

    /** N일 간격. */
    INTERVAL,

    /**
     * 영업일마다 (V5 추가, 설계노트 G-2).
     *
     * <p>카드 정산이 여기다. {@code INTERVAL: 1} 로 표현하면 주말에도 입금을 예측하고,
     * 공휴일 보정을 걸면 토·일 예정분이 월요일로 몰려 <b>한 날에 3건</b>이 된다.
     * 실제로는 영업일당 1건이고 금액만 요일마다 다르다.
     */
    BUSINESS_DAILY
}
