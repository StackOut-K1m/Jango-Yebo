package com.jangoyebo.core.transaction;

import com.jangoyebo.common.domain.FlowType;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

/**
 * 금융망에서 수집한 거래 한 줄. {@link TransactionBulkWriter} 삽입 단위다.
 *
 * <p><b>CSV 행과 합치지 않는다.</b> 둘은 "필드 두 개 차이"가 아니라 <b>불변식이 상보적</b>이다 —
 * 이쪽은 {@code externalTransactionId} 가 항상 있고 {@code deduplicationKey} 가 항상 없다.
 * CSV 는 정반대다. 그래서 충돌 대상 인덱스도 다르다({@code uk_tx_external} 대 {@code uk_tx_dedup}).
 * 하나로 묶으면 두 필드가 각각 영구히 null 인 채로 다니고, writer 가 <b>타입만 보고 어느
 * 충돌 대상을 써야 하는지 알 수 없게</b> 된다. DB 가 부분 유일 인덱스를 둘로 나눈 것과 같은 이유다.
 *
 * <p>필드 순서는 INSERT 컬럼 순서와 같다. 읽는 사람이 SQL 과 대조하기 쉬우라고 맞춰 뒀다.
 *
 * @param transactionDate <b>{@code occurredAt} 에서 역산한 값이 아니다.</b> 금융망이 KST 벽시계
 *                        날짜를 문자열로 주므로 그대로 쓴다 — 서버 시간대와 무관해야 한다.
 */
public record FinTransactionRow(Long businessId,
                                Long accountId,
                                String externalTransactionId,
                                Instant occurredAt,
                                LocalDate transactionDate,
                                FlowType flowType,
                                BigDecimal amount,
                                BigDecimal balanceAfter,
                                String description,
                                String counterpartyAccountNo) {
}
