package com.jangoyebo.core.report;

/**
 * 브리핑 상태 (API 15.2). DB {@code ck_report_status} 와 값이 일치해야 한다.
 *
 * <p><b>문장이 없는 것은 실패가 아니다.</b> 명세가 「생성 실패 시 지표만이라도 반환한다」고
 * 정했으므로, 지표만 든 브리핑도 화면에 나갈 수 있는 산출물이다.
 */
public enum ReportStatus {

    /**
     * 지표는 저장됐고 문장을 기다린다.
     *
     * <p><b>ERD 에 없던 값이다.</b> 워커가 202 + 콜백이라 그 사이 구간이 실제로 있는데,
     * 그동안을 {@code COMPLETED} 로 두면 화면이 문장이 오기도 전에 「완료」로 읽고
     * {@code FAILED} 로 두면 실패하지도 않은 것이 실패로 보인다.
     */
    PENDING,

    /** 문장까지 붙었다. */
    COMPLETED,

    /** 문장이 입력에 없는 수치를 써서 일부를 버렸다 (20.7). */
    PARTIAL,

    /** 문장 생성이 실패했다. 지표는 그대로 읽힌다. */
    FAILED
}
