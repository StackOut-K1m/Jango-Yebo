package com.jangoyebo.core.report;

/** 브리핑 주기 (API 15.2). DB {@code ck_report_type} 과 값이 일치해야 한다. */
public enum ReportType {

    /** 매주 월요일 배치. 대상 기간은 직전 월~일. */
    WEEKLY,

    /** 매월 1일 배치. 대상 기간은 직전 달 1일~말일. */
    MONTHLY
}
