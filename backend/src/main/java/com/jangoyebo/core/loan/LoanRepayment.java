package com.jangoyebo.core.loan;

import com.jangoyebo.common.entity.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * 대출 회차별 상환 결과 (ERD {@code LOAN_REPAYMENT}, P1-13).
 *
 * <p>{@code LOAN_09}(상환 내역 조회)가 <b>이미 시도한 회차</b>를 준다. 앞으로의 회차는 여기
 * 쌓지 않고 {@code LoanRepaymentSchedule} 이 계약에서 계산해 예정 거래로 만든다 — 1년 대출이면
 * 365회차라, 곡선이 30~90일만 보는데 전부 미리 저장할 이유가 없다.
 *
 * <h2>{@link #failureReason} 이 이 제품의 장면이다</h2>
 *
 * <p>잔액이 모자라 자동이체가 실패하면 금융망이 <b>「자동이체 실패 잔액 부족」</b>을 그대로
 * 준다. 우리가 막으려던 사건이 실제로 일어났다는 증거라 원문을 남긴다 — 나중에
 * 「우리 예보는 그 전날 경고했는가」를 대조할 수 있는 유일한 기록이다.
 */
@Entity
@Table(name = "loan_repayment", schema = "core")
public class LoanRepayment extends BaseEntity {

    /** 아직 오지 않은 회차. 수동 등록 대출에만 쓴다 — 금융망은 시도한 것만 준다. */
    static final String SCHEDULED = "SCHEDULED";
    static final String SUCCESS = "SUCCESS";
    static final String FAIL = "FAIL";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "loan_id", nullable = false, updatable = false)
    private Long loanId;

    /** 금융망 {@code installmentNumber}. 수동 등록은 없다. */
    @Column(name = "installment_no")
    private Integer installmentNo;

    @Column(name = "due_date", nullable = false)
    private LocalDate dueDate;

    /**
     * 회차 금액 ({@code LOAN_09} 의 {@code paymentBalance}).
     *
     * <p>원금·이자로 나누지 않는다 — 금융망이 하나만 주므로 쪼개면 우리가 지어낸 비율이 된다.
     */
    @Column(nullable = false)
    private BigDecimal amount;

    /**
     * 실제 출금 거래. 상환이 성공해도 <b>비어 있을 수 있다</b> — 그 계좌를 우리 앱에
     * 연결하지 않았으면 거래가 우리 DB 에 없다.
     */
    @Column(name = "transaction_id")
    private Long transactionId;

    @Column(nullable = false, length = 20)
    private String status;

    /** {@code LOAN_09} 의 {@code failureReason} 원문. 실패가 아니면 비어 있다. */
    @Column(name = "failure_reason", length = 255)
    private String failureReason;

    protected LoanRepayment() {
    }

    private LoanRepayment(Long loanId, Integer installmentNo, LocalDate dueDate,
                          BigDecimal amount, String status) {
        this.loanId = loanId;
        this.installmentNo = installmentNo;
        this.dueDate = dueDate;
        this.amount = amount;
        this.status = status;
    }

    /**
     * {@code LOAN_09} 가 준 한 회차.
     *
     * <p>금융망은 회차 금액({@code paymentBalance}) 하나만 준다. 원금·이자로 나눠 주지
     * 않으므로 우리도 나누지 않는다.
     */
    public static LoanRepayment fromFinApi(Long loanId, Integer installmentNo, LocalDate dueDate,
                                           BigDecimal amount,
                                           boolean success, String failureReason) {
        LoanRepayment row = new LoanRepayment(loanId, installmentNo, dueDate,
                amount, success ? SUCCESS : FAIL);
        row.failureReason = success ? null : failureReason;
        return row;
    }

    /** 같은 회차를 다시 읽었을 때. 실패가 나중에 성공으로 바뀔 수 있다(재시도). */
    public void syncFrom(boolean success, String failureReason) {
        this.status = success ? SUCCESS : FAIL;
        this.failureReason = success ? null : failureReason;
    }

    /** 우리 거래와 이어 붙인다. 대사(對査)와 「그날 정말 빠졌나」 확인에 쓴다. */
    public void matchTo(Long transactionId) {
        this.transactionId = transactionId;
    }

    public boolean isFailure() {
        return FAIL.equals(status);
    }

    public Long getId() {
        return id;
    }

    public Long getLoanId() {
        return loanId;
    }

    public Integer getInstallmentNo() {
        return installmentNo;
    }

    public LocalDate getDueDate() {
        return dueDate;
    }

    public BigDecimal getAmount() {
        return amount;
    }

    public Long getTransactionId() {
        return transactionId;
    }

    public String getStatus() {
        return status;
    }

    public String getFailureReason() {
        return failureReason;
    }
}
