package com.jangoyebo.core.safety;

/**
 * 안전선 산출 방식 (API 12.1).
 *
 * <p>P0 범위는 {@link #MANUAL} 만 실제로 계산이 없다. 나머지 둘은 <b>값으로 받아 저장할 뿐</b>
 * 서버가 그 방식으로 금액을 계산해 주지는 않는다 — 추천값 산출(`/safety-line/recommendations`)이
 * P1 이기 때문이다. 사용자가 스스로 계산해 넣고 근거만 표시하는 용도다.
 */
public enum SafetyLineMethod {

    /** 직접 입력. */
    MANUAL,

    /** 최근 한 달 고정비 기준. */
    FIXED_COST_BASED,

    /** 급여·임대료 등 필수지출 합계 기준. */
    ESSENTIAL_EXPENSE_BASED
}
