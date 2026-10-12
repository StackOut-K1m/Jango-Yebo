package com.jangoyebo.core.evidence;

/**
 * 증빙 추출 처리 상태 (API 13.1 {@code ocrStatus}).
 *
 * <p>DB 컬럼은 {@code processing_status} 이고 API 는 {@code ocrStatus} 로 부른다.
 * 이름이 다른 이유는 추출 수단이 OCR 만은 아니기 때문이다(VLM 도 쓴다) — 컬럼 쪽이
 * 더 일반적인 이름이다.
 *
 * <p>흐름: {@code QUEUED} → {@code PROCESSING} → {@code EXTRACTED}(사용자 확인 대기)
 * → {@code CONFIRMED} / {@code FAILED} / {@code SKIPPED}.
 *
 * <p><b>{@code EXTRACTED} 는 끝이 아니다.</b> 추출값을 사용자가 확인해야 {@code CONFIRMED} 가
 * 된다 — 세무 값을 자동 확정하지 않는다는 도메인 규칙 4 가 여기에도 걸린다.
 */
public enum EvidenceStatus {

    /** 업로드 직후. 추출 Job 이 등록된 상태다. */
    QUEUED,

    PROCESSING,

    /** 추출 완료, 사용자 확인 대기. */
    EXTRACTED,

    /** 사용자가 추출값을 확정했다. */
    CONFIRMED,

    FAILED,

    /**
     * 추출 대상이 아니다.
     *
     * <p>계약서·급여대장처럼 항목 단위 추출이 의미 없는 것, 또는 사용자가 추출을 건너뛴 것이다.
     * 실패와 구분해야 화면이 "다시 시도"를 잘못 권하지 않는다.
     */
    SKIPPED
}
