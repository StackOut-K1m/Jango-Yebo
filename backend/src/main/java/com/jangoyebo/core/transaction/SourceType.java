package com.jangoyebo.core.transaction;

/** 거래 수집 경로 (ERD {@code TRANSACTION.source_type}). */
public enum SourceType {
    /** 금융망 API 동기화. {@code externalTransactionId} 가 채워진다 */
    FIN_API,
    /** CSV 업로드. {@code deduplicationKey} 가 채워진다 */
    CSV
}
