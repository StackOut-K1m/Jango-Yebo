package com.jangoyebo.core.tax;

/** 신고서 초안 상태 (API 14.3). */
public enum TaxDraftStatus {

    /** 집계 중. 생성 요청 직후. */
    PROCESSING,

    /** 집계 완료. 내보내기는 이 상태에서만 된다. */
    READY,

    /**
     * 검토 상태(8.7)가 바뀌어 근거가 달라졌다.
     *
     * <p>지우지 않고 남긴다 — 사장님이 무엇을 보고 판단했는지가 이력이다.
     * 갱신은 재생성으로 한다(새 행이 생긴다).
     */
    STALE,

    /** 집계가 실패했다. */
    FAILED
}
