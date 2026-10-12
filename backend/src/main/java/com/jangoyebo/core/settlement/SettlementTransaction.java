package com.jangoyebo.core.settlement;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * 정산 대사에 매칭한 실제 입금 거래 (ERD {@code SETTLEMENT_TRANSACTION}).
 *
 * <p>정산 한 건에 입금이 여러 번 나뉘어 들어오는 경우가 있어 N:M 이다.
 * {@code allocatedAmount} 를 따로 두는 것은 <b>한 입금이 두 정산에 걸칠 수 있기</b> 때문이다 —
 * 거래 금액 전부가 이 정산 몫이라고 단정하지 않는다.
 */
@Entity
@Table(name = "settlement_transaction", schema = "core")
public class SettlementTransaction {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "settlement_id", nullable = false, updatable = false)
    private Long settlementId;

    @Column(name = "transaction_id", nullable = false, updatable = false)
    private Long transactionId;

    @Column(name = "allocated_amount", nullable = false)
    private BigDecimal allocatedAmount;

    @org.hibernate.annotations.CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected SettlementTransaction() {
    }

    public static SettlementTransaction of(Long settlementId, Long transactionId, BigDecimal amount) {
        SettlementTransaction st = new SettlementTransaction();
        st.settlementId = settlementId;
        st.transactionId = transactionId;
        st.allocatedAmount = amount;
        return st;
    }

    public Long getId() { return id; }
    public Long getSettlementId() { return settlementId; }
    public Long getTransactionId() { return transactionId; }
    public BigDecimal getAllocatedAmount() { return allocatedAmount; }
}
