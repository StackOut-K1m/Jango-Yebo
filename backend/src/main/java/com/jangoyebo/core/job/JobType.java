package com.jangoyebo.core.job;

/** API 명세 2.7의 jobType. DB CHECK 제약(ck_job_type)과 값이 일치해야 한다. */
public enum JobType {
    ACCOUNT_SYNC,
    TRANSACTION_IMPORT,
    TRANSACTION_CLASSIFICATION,
    EVIDENCE_EXTRACTION,
    EXPORT_GENERATION,
    /** 종합소득세 신고서 초안 생성 (API 14.3). 결과는 TAX_FILING_DRAFT, result 에는 draftId 만. */
    TAX_DRAFT_GENERATION,
    /** POS 매출현황 인쇄물 OCR (API 14.6, P1-22). */
    PRODUCT_SALES_OCR,
    LOAN_SYNC,
    /**
     * 지원사업 공고문 구조화 (API 20.6, P1-15).
     *
     * <p>20.6 요청 본문이 {@code jobId} 를 요구하는데 <b>그 Job 을 만들 타입이 없었다</b> —
     * 공통규약 2.7 · ERD 8장 · 이 enum · {@code ck_job_type} 네 곳 모두 8종이었다(V21 에서 보강).
     *
     * <p>적재(20.8)가 신규 공고를 넣으면 이 Job 이 걸리고, 워커 콜백이 구조화 결과를
     * {@code SUPPORT_PROGRAM.eligibility} 에 채운다. 공고는 사업장 공용 데이터지만
     * {@code JOB.business_id} 가 NOT NULL 이라 수집 배치가 만든 Job 은 그 제약을 어떻게
     * 채울지 적재 구현에서 정한다.
     */
    PROGRAM_STRUCTURING,

    /**
     * 주간·월간 브리핑 문장 생성 (API 20.7, P1-17).
     *
     * <p>워커는 이 경로를 진작 열어 뒀는데 <b>그 Job 을 만들 타입이 없었다</b> —
     * {@code PROGRAM_STRUCTURING} 이 {@code V21} 에서 겪은 것과 같은 사정이다({@code V35}).
     *
     * <p>지표는 이 Job 이 걸리기 <b>전에</b> {@code REPORT} 에 저장된다. 그래서 이 Job 이
     * 실패해도 브리핑은 남는다 — 문장만 없을 뿐이고, 명세가 그것을 정상 산출물로 정했다.
     */
    BRIEFING_GENERATION
}
