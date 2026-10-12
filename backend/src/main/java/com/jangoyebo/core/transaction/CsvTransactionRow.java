package com.jangoyebo.core.transaction;

import com.jangoyebo.common.domain.FlowType;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

/**
 * CSV 업로드로 수집한 거래 한 줄. {@link TransactionBulkWriter} 삽입 단위다.
 *
 * <p>{@link FinTransactionRow} 와 형제이며 <b>합치지 않는 이유는 그쪽 Javadoc</b>에 있다.
 * 이쪽은 {@code deduplicationKey} 가 항상 있고 {@code externalTransactionId} 가 항상 없다.
 *
 * <p>{@code counterpartyAccountNo} 가 없는 것은 누락이 아니다 — CSV 규격(API 7.4)에 상대 계좌번호
 * 컬럼이 없다. 금융망만 그 값을 준다.
 */
public record CsvTransactionRow(Long businessId,
                                Long accountId,
                                String deduplicationKey,
                                Instant occurredAt,
                                LocalDate transactionDate,
                                FlowType flowType,
                                BigDecimal amount,
                                BigDecimal balanceAfter,
                                String description) {
}
