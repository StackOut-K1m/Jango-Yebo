package com.jangoyebo.forecast.pattern;

/**
 * 공휴일 보정 규칙 (API 9.2).
 *
 * <p><b>보정은 실제로 돈다.</b> {@code RecurringPatternService.dueDate} 가 예정일을 옮기고
 * {@code RecurringPatternDetector} 가 탐지에서 같은 규칙을 쓴다. 공휴일 목록은
 * {@code BusinessCalendar} 에 있다 — 「아직 없다(P1-04)」고 적혀 있던 이 줄이 낡은
 * 것이었다(2026-09-18 정정).
 * 지금은 값을 저장하고 응답에 실어 보내기만 한다 — 예정 거래의 두 날짜가 항상 같은 이유다.
 */
public enum HolidayRule {

    /** 다음 영업일로 이월. 카드 정산 기본값. */
    NEXT_BUSINESS_DAY,

    /** 직전 영업일로 앞당김. 일부 급여. */
    PREVIOUS_BUSINESS_DAY,

    /** 보정하지 않는다. */
    NONE
}
