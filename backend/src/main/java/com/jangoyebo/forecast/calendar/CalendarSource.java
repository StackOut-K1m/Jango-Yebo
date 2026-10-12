package com.jangoyebo.forecast.calendar;

/** 달력 행이 어디서 왔나. 사용자가 넣은 것을 배치가 덮어쓰지 않게 구분한다. */
public enum CalendarSource {

    /** 사장님이 화면에서 등록했다. <b>가장 믿을 수 있다.</b> */
    USER,

    /** 특일 정보 API 로 시딩한 공통 달력. */
    PUBLIC_API,

    /** 시연 데이터 생성기가 넣었다. */
    SEED
}
