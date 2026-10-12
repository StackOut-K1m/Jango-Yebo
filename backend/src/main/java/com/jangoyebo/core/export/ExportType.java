package com.jangoyebo.core.export;

/**
 * 내보내기 종류 (API 14.2 {@code exportType}).
 *
 * <p>지금은 한 종류뿐이지만 값을 열거로 둔다 — 요청 본문이 문자열을 받는데, 아무 문자열이나
 * 통과시키면 오타가 조용히 「빈 패키지」로 끝난다. 모르는 값은 {@code 400} 이다.
 */
public enum ExportType {

    /** 세무대리인 전달용 신고 준비자료 패키지 (기획정리본 §6.10). */
    TAX_PREP
}
