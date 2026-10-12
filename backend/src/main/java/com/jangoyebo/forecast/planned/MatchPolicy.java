package com.jangoyebo.forecast.planned;

import com.jangoyebo.forecast.pattern.CadenceType;

/**
 * 예정 거래를 실제 거래에 붙일 때의 허용폭 (API 9.5 최소 매처).
 *
 * <p><b>값의 출처는 탐지기다.</b> 후보를 만들 때 「이 정도 흔들려도 같은 패턴」이라고 본
 * 폭을, 매칭에서도 그대로 쓴다. 두 값이 갈리면 <b>탐지가 같은 패턴으로 묶은 거래를 매칭이
 * 다른 것으로 보는</b> 모순이 생긴다.
 *
 * <p>9.5 는 "금액·날짜·거래처 유사도"라고만 적어 구현할 수 없었다. 여기 상수가 그 빈칸을
 * 채운 값이고, 정확도 리포트(P1-05)가 서면 실측으로 다시 정한다.
 */
public final class MatchPolicy {

    /** 사용자·세금·대출이 만든 예정 거래. 사람이 넣은 날짜라 편차가 크다. */
    static final int USER_TOLERANCE_DAYS = 3;

    /** 요일이 고정이라 공휴일 하루 밀림만 흡수하면 된다. */
    static final int WEEKLY_TOLERANCE_DAYS = 1;

    /**
     * 영업일마다 1건이라 창을 넓히면 이웃 회차와 섞인다.
     *
     * <p>카드 정산이 여기 해당한다 — 하루 차이로 붙는 상대를 잘못 고르면 금액 오차가
     * 통계에 그대로 들어간다.
     */
    static final int BUSINESS_DAILY_TOLERANCE_DAYS = 1;

    /** 탐지의 월 반복일 허용폭 2 + 주말 이월 1. */
    static final int MONTHLY_TOLERANCE_DAYS = 3;

    /** 탐지의 간격 편차 허용치. {@code RecurringPatternDetector.MAX_INTERVAL_DEVIATION} 과 같다. */
    static final double INTERVAL_DEVIATION = 0.25;

    static final int MIN_TOLERANCE_DAYS = 1;
    static final int MAX_TOLERANCE_DAYS = 3;

    private MatchPolicy() {
    }

    /**
     * @param cadence       패턴이 만든 예정 거래면 그 주기. 사용자 등록이면 {@code null}
     * @param intervalValue {@code INTERVAL} 주기의 간격(일). 그 외에는 무시된다
     */
    public static int toleranceDays(PlannedSourceType source, CadenceType cadence, Integer intervalValue) {
        if (source != PlannedSourceType.PATTERN || cadence == null) {
            return USER_TOLERANCE_DAYS;
        }
        return switch (cadence) {
            case WEEKLY -> WEEKLY_TOLERANCE_DAYS;
            case BUSINESS_DAILY -> BUSINESS_DAILY_TOLERANCE_DAYS;
            case MONTHLY -> MONTHLY_TOLERANCE_DAYS;
            case INTERVAL -> intervalTolerance(intervalValue);
        };
    }

    private static int intervalTolerance(Integer intervalValue) {
        if (intervalValue == null || intervalValue <= 0) {
            return MIN_TOLERANCE_DAYS;
        }
        int scaled = (int) Math.round(INTERVAL_DEVIATION * intervalValue);
        return Math.clamp(scaled, MIN_TOLERANCE_DAYS, MAX_TOLERANCE_DAYS);
    }

    /**
     * 후보를 찾을 때 거슬러 올라가는 과거 범위.
     *
     * <p>예정일이 이보다 오래됐으면 매칭을 포기한다 — 두 달 전 예정 거래에 지금 거래를 붙이는
     * 것은 매칭이 아니라 추측이다. 잔여 집계 창(365일)보다 훨씬 짧은 이유다.
     */
    public static final int LOOKBACK_DAYS = 60;
}
