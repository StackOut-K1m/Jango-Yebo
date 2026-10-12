package com.jangoyebo.forecast.calendar;

/**
 * 달력 하루의 성격 (ERD {@code BUSINESS_CALENDAR_DAY.day_type}).
 *
 * <p><b>{@link #HOLIDAY} 와 {@link #CLOSED} 는 다르다.</b> 섞으면 곡선이 조용히 틀린다.
 *
 * <pre>
 * HOLIDAY   은행·카드사가 쉰다   → 매출은 있고 정산만 다음 영업일로 밀린다
 * CLOSED    가게가 문을 닫는다   → 그 기간 매출 자체가 없다
 * </pre>
 *
 * <p>카페는 한글날·개천절·어린이날에 연다. 설날·추석만 닫는다.
 */
public enum DayType {

    /** 여는 날. 공통 달력이 공휴일이라도 이 사업장은 연다는 뜻으로 쓴다. */
    BUSINESS_DAY,

    /** 관공서 공휴일. 정산이 다음 영업일로 밀린다. */
    HOLIDAY,

    /** <b>휴무일.</b> 가게가 닫아 매출이 없다. */
    CLOSED
}
