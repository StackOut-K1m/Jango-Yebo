package com.jangoyebo.core.transaction;

/** 분류 상태 (API 8.2). */
public enum ClassificationStatus {
    /** 아직 아무 분류도 적용되지 않음 */
    UNCLASSIFIED,
    /** 규칙·사전·LLM이 제안했고 사용자 확인 대기 */
    SUGGESTED,
    /** 사용자가 확정 */
    CONFIRMED
}
