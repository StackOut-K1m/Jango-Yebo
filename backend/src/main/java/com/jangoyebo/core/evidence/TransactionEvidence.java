package com.jangoyebo.core.evidence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * 거래–증빙 연결 (ERD {@code TRANSACTION_EVIDENCE}).
 *
 * <p><b>N:M 이다.</b> 영수증 한 장에 여러 거래가 걸릴 수 있고(분할 결제), 거래 하나에
 * 증빙이 여럿 붙을 수도 있다(영수증 + 카드전표).
 *
 * <p>{@code matchScore} 는 AI 매칭 추천(P1-02)이 채운다. 사용자가 직접 연결한 것은 null 이다 —
 * <b>0 으로 두면 "일치도 0%로 판정됨"과 구분되지 않는다.</b>
 */
@Entity
@Table(name = "transaction_evidence", schema = "core")
public class TransactionEvidence {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "transaction_id", nullable = false, updatable = false)
    private Long transactionId;

    @Column(name = "evidence_id", nullable = false, updatable = false)
    private Long evidenceId;

    /** AI 매칭 추천의 일치도. 사용자가 직접 연결하면 null. */
    @Column(name = "match_score")
    private BigDecimal matchScore;

    @Enumerated(EnumType.STRING)
    @Column(name = "match_status", nullable = false, length = 20)
    private MatchStatus matchStatus;

    @Column(name = "confirmed_by")
    private Long confirmedBy;

    @Column(name = "confirmed_at")
    private Instant confirmedAt;

    @org.hibernate.annotations.CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected TransactionEvidence() {
    }

    /** 사용자가 직접 연결. 확인을 거치지 않으므로 바로 {@code LINKED} 다. */
    public static TransactionEvidence linkedByUser(Long transactionId, Long evidenceId, Long userId) {
        TransactionEvidence te = new TransactionEvidence();
        te.transactionId = transactionId;
        te.evidenceId = evidenceId;
        te.matchStatus = MatchStatus.LINKED;
        te.confirmedBy = userId;
        te.confirmedAt = Instant.now();
        return te;
    }

    /** AI 매칭 추천 (P1-02). 사용자가 받기 전까지 후보다. */
    public static TransactionEvidence candidate(Long transactionId, Long evidenceId, BigDecimal score) {
        TransactionEvidence te = new TransactionEvidence();
        te.transactionId = transactionId;
        te.evidenceId = evidenceId;
        te.matchScore = score;
        te.matchStatus = MatchStatus.CANDIDATE;
        return te;
    }

    public void accept(Long userId) {
        this.matchStatus = MatchStatus.LINKED;
        this.confirmedBy = userId;
        this.confirmedAt = Instant.now();
    }

    /**
     * 추천을 거절한다. 행을 지우지 않는다 — 지우면 다음 매칭 배치가 같은 쌍을 다시
     * 추천해 사용자가 같은 거절을 반복하게 된다.
     */
    public void reject(Long userId) {
        this.matchStatus = MatchStatus.REJECTED;
        this.confirmedBy = userId;
        this.confirmedAt = Instant.now();
    }

    public Long getId() { return id; }
    public Long getTransactionId() { return transactionId; }
    public Long getEvidenceId() { return evidenceId; }
    public BigDecimal getMatchScore() { return matchScore; }
    public MatchStatus getMatchStatus() { return matchStatus; }
    public Long getConfirmedBy() { return confirmedBy; }
    public Instant getConfirmedAt() { return confirmedAt; }
    public Instant getCreatedAt() { return createdAt; }
}
