package com.jangoyebo.core.evidence;

/**
 * 증빙 종류 (API 13.1).
 *
 * <p>업로드 시 <b>힌트</b>로 받는다. 생략하면 {@link #OTHER} 로 저장되고 AI 추출(P1-02)이
 * 추정한다 — 사용자가 8종을 구분해 고르는 것보다 사진만 올리는 편이 실제 사용에 가깝다.
 *
 * <p>DB {@code ck_evidence_type} 과 값이 일치해야 한다.
 */
public enum EvidenceType {

    /** 영수증. 가장 흔하다. */
    RECEIPT,

    /** 세금계산서. */
    TAX_INVOICE,

    /** 계산서·거래명세서. */
    INVOICE,

    /** 현금영수증. */
    CASH_RECEIPT,

    /** 플랫폼 정산명세서. 정산 대사(P0-11)의 근거가 된다. */
    SETTLEMENT_STATEMENT,

    /** 임대차계약서. 임차료의 근거다. */
    CONTRACT,

    /** 급여대장. */
    PAYROLL,

    /** 미지정 또는 위에 없는 것. 업로드 기본값이다. */
    OTHER
}
