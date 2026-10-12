package com.jangoyebo.core.loan;

import com.jangoyebo.common.entity.BaseEntity;
import com.jangoyebo.common.tenancy.BusinessScoped;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * 보유 대출 (ERD {@code LOAN}, P1-13).
 *
 * <h2>금융망 대출은 매일 갚는다</h2>
 *
 * <p>개설 <b>다음날부터 매일 08:30</b> 에 {@code (대출금+이자)/대출기간} 이
 * {@link #withdrawalAccountId} 에서 빠진다. 기간은 2~365일이라 다년 대출이 없고, 이자는
 * 단리 일할이다. 상환 방식 이름은 「원리금균등상환」으로 오지만 그 이름과 계산이 다르다 —
 * 계산은 {@code LoanRepaymentSchedule} 에 있다.
 *
 * <p>그래서 {@link #repaymentCycle} 의 기본값이 {@link #DAILY} 다. {@link #MONTHLY} 는 사장님이
 * 직접 등록한 대출(금융망 밖의 실제 대출)에만 쓴다.
 *
 * <h2>상태</h2>
 *
 * <pre>
 * 금융망 LOAN_08 status        우리 status
 *   개설        ──────────→    NORMAL      아직 첫 회차 전
 *   상환중      ──────────→    REPAYING
 *   연체        ──────────→    OVERDUE     잔액 부족으로 자동이체가 실패했다
 *   (없음)                     CLOSED      만기·완납. **금융망에 이 값이 없어 우리가 판정한다**
 * </pre>
 *
 * <p>{@code CLOSED} 만 우리가 만든다. {@code LOAN_09} 설명이 근거다 — 「상환이 완료되면
 * 대출 계좌는 <b>자동해지</b>되고, 한번이라도 연체 상태가 있다면 연체금을 납입할 때까지
 * 계좌는 유지됩니다.」 즉 <b>목록에서 빠지는 것</b>이 완납 신호라 동기화가 {@link #close} 로
 * 닫는다. {@link #closeIfMatured} 는 동기화가 한동안 못 돈 경우의 보조 수단이다.
 *
 * <p>마이너스통장 컬럼({@link #creditLimit}·{@link #usedAmount}·{@link #accruedInterest})은
 * 자리만 잡아 둔다. 기술스택 4.13 이 마이너스통장을 보류로 정리했고, P1 에서는
 * {@link #TERM_LOAN} 만 채운다.
 */
@Entity
@Table(name = "loan", schema = "core")
public class Loan extends BaseEntity implements BusinessScoped {

    static final String TERM_LOAN = "TERM_LOAN";
    static final String MINUS_ACCOUNT = "MINUS_ACCOUNT";

    static final String MANUAL = "MANUAL";
    static final String FIN_API = "FIN_API";

    /** 금융망 대출의 실제 동작. 개설 다음날부터 매일 같은 금액이 빠진다. */
    static final String DAILY = "DAILY";
    /** 사장님이 직접 등록한 대출. 월 상환일에 한 번 빠진다. */
    static final String MONTHLY = "MONTHLY";
    /** 마이너스통장 이자 납입. 원금 상환이 아니다. */
    static final String INTEREST_MONTHLY = "INTEREST_MONTHLY";

    static final String NORMAL = "NORMAL";
    static final String REPAYING = "REPAYING";
    static final String OVERDUE = "OVERDUE";
    static final String CLOSED = "CLOSED";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "business_id", nullable = false, updatable = false)
    private Long businessId;

    @Column(name = "loan_type", nullable = false, length = 20)
    private String loanType;

    @Column(nullable = false, length = 20)
    private String source;

    /** 금융망 대출 계좌번호. 동기화 중복 방지 키다 ({@code uk_loan_external}). */
    @Column(name = "external_loan_no", length = 32)
    private String externalLoanNo;

    @Column(name = "lender_name", nullable = false, length = 50)
    private String lenderName;

    @Column(name = "product_name", nullable = false, length = 100)
    private String productName;

    /** 수동 등록만 채운다. <b>금융망은 원금을 주지 않는다</b> — {@link #totalRepayable} 참조. */
    @Column(name = "principal_amount")
    private BigDecimal principalAmount;

    /**
     * 원금+이자 총액 (<b>{@code LOAN_09}</b> 의 {@code loanBalance}).
     *
     * <p>일 상환액의 분자다 — 금융망이 이 값을 {@code loanPeriod} 로 나눠 매일 뺀다.
     *
     * <p>⚠ <b>{@code LOAN_08} 의 같은 이름 필드가 아니다.</b> 거기서 오는 값은 원금이다
     * (2026-09-15 실측). 표에는 둘 다 「대출금」이라고만 적혀 있다.
     *
     * <p>⚠ <b>우리가 계산하지 않는다.</b> 금융망 이자가 플로우 문서의 식과 맞지 않아
     * (500만·365일·8.4% → 1억 5,830만, 단리 일할의 29배) 다시 계산하면 실제로 빠지는
     * 금액과 어긋난다. 준 값을 그대로 쓴다.
     */
    @Column(name = "total_repayable")
    private BigDecimal totalRepayable;

    @Column(name = "outstanding_balance")
    private BigDecimal outstandingBalance;

    @Column(name = "credit_limit")
    private BigDecimal creditLimit;

    @Column(name = "used_amount")
    private BigDecimal usedAmount;

    @Column(name = "accrued_interest")
    private BigDecimal accruedInterest;

    /** 연 %. 금융망은 0.1~20 만 허용한다. */
    @Column(name = "interest_rate", nullable = false)
    private BigDecimal interestRate;

    @Column(name = "repayment_cycle", nullable = false, length = 20)
    private String repaymentCycle;

    @Column(name = "repayment_type", length = 30)
    private String repaymentType;

    @Column(name = "monthly_payment_day")
    private Integer monthlyPaymentDay;

    @Column(name = "monthly_payment_amount")
    private BigDecimal monthlyPaymentAmount;

    /**
     * 상환금이 빠지는 계좌. <b>이 계좌가 우리와 연결돼 있지 않을 수 있다</b> — 금융망 대출은
     * 우리 앱에 등록하지 않은 계좌에서도 빠진다. 그때는 {@code null} 로 두고, 예정 거래를
     * 만들지 않는다(우리가 그리지 않는 계좌의 곡선을 바꿀 수는 없다).
     */
    @Column(name = "withdrawal_account_id")
    private Long withdrawalAccountId;

    @Column(name = "started_on", nullable = false)
    private LocalDate startedOn;

    @Column(name = "maturity_date", nullable = false)
    private LocalDate maturityDate;

    @Column(name = "overdue_days")
    private Integer overdueDays;

    @Column(nullable = false, length = 20)
    private String status;

    protected Loan() {
    }

    private Loan(Long businessId, String source, String lenderName, String productName,
                 BigDecimal interestRate, String repaymentCycle,
                 LocalDate startedOn, LocalDate maturityDate, String status) {
        this.businessId = businessId;
        this.loanType = TERM_LOAN;
        this.source = source;
        this.lenderName = lenderName;
        this.productName = productName;
        this.interestRate = interestRate;
        this.repaymentCycle = repaymentCycle;
        this.startedOn = startedOn;
        this.maturityDate = maturityDate;
        this.status = status;
    }

    /**
     * {@code LOAN_08}(가입 대출 목록)이 준 한 건.
     *
     * @param externalLoanNo 금융망 대출 계좌번호. 같은 값이 다시 오면 새로 만들지 않고
     *                       {@link #syncFrom} 으로 갱신한다
     */
    public static Loan fromFinApi(Long businessId, String externalLoanNo,
                                  String lenderName, String productName,
                                  BigDecimal principal,
                                  BigDecimal interestRate, Long withdrawalAccountId,
                                  LocalDate startedOn, LocalDate maturityDate, String status) {
        Loan loan = new Loan(businessId, FIN_API, lenderName, productName,
                interestRate, DAILY, startedOn, maturityDate, status);
        loan.externalLoanNo = externalLoanNo;
        loan.principalAmount = principal;
        loan.withdrawalAccountId = withdrawalAccountId;
        return loan;
    }

    /**
     * {@code LOAN_08} 이 다시 읽은 상태. <b>금액은 여기서 안 바꾼다</b> —
     * 그 응답의 {@code loanBalance} 는 원금이고, 원금은 계약이라 움직이지 않는다.
     */
    public void syncStatus(String status, Integer overdueDays) {
        this.status = status;
        this.overdueDays = overdueDays;
    }

    /**
     * {@code LOAN_09} 가 주는 총액과 남은 금액.
     *
     * <p>총액도 갱신하는 이유는 <b>연체가 붙으면 늘어나기 때문</b>이다 — 개설 시점 값으로
     * 굳혀 두면 연체 이자가 곡선에서 빠진다.
     */
    public void syncAmounts(BigDecimal totalRepayable, BigDecimal outstanding) {
        this.totalRepayable = totalRepayable;
        this.outstandingBalance = outstanding;
    }

    /**
     * 만기가 지났으면 닫는다.
     *
     * <p>금융망 {@code LOAN_08} 에 「완납」 상태가 없어 우리가 판정한다. <b>연체는 닫지 않는다</b> —
     * 만기가 지나도 갚지 않은 돈이 남아 있고, 그것이 곡선에서 사라지면 안 된다.
     *
     * @return 이번 호출로 닫혔으면 {@code true}
     */
    public boolean closeIfMatured(LocalDate today) {
        if (OVERDUE.equals(status) || CLOSED.equals(status) || !today.isAfter(maturityDate)) {
            return false;
        }
        this.status = CLOSED;
        return true;
    }

    /**
     * 다 갚아 금융망 목록에서 사라졌다.
     *
     * <p>{@code LOAN_09} 설명이 근거다 — 「상환이 완료되면 대출 계좌는 <b>자동해지</b>되고,
     * 한번이라도 연체 상태가 있다면 연체금을 납입할 때까지 계좌는 유지됩니다.」 금융망에
     * 「완납」 상태가 따로 없어 <b>목록에서 빠지는 것</b>이 완납 신호다.
     */
    public void close() {
        this.status = CLOSED;
    }

    /** 앞으로 돈이 빠질 대출인가. 예정 거래를 만들 대상이다. */
    public boolean isRepaying() {
        return !CLOSED.equals(status);
    }

    public Long getId() {
        return id;
    }

    @Override
    public Long getBusinessId() {
        return businessId;
    }

    public String getExternalLoanNo() {
        return externalLoanNo;
    }

    public String getLenderName() {
        return lenderName;
    }

    public String getProductName() {
        return productName;
    }

    public BigDecimal getPrincipalAmount() {
        return principalAmount;
    }

    public BigDecimal getTotalRepayable() {
        return totalRepayable;
    }

    public BigDecimal getOutstandingBalance() {
        return outstandingBalance;
    }

    public BigDecimal getInterestRate() {
        return interestRate;
    }

    /** 수동 등록 대출의 상환 방식(원리금균등 등). 금융망 대출은 비어 있다. */
    public String getRepaymentType() {
        return repaymentType;
    }

    public String getRepaymentCycle() {
        return repaymentCycle;
    }

    public Integer getMonthlyPaymentDay() {
        return monthlyPaymentDay;
    }

    public BigDecimal getMonthlyPaymentAmount() {
        return monthlyPaymentAmount;
    }

    public Long getWithdrawalAccountId() {
        return withdrawalAccountId;
    }

    public LocalDate getStartedOn() {
        return startedOn;
    }

    public LocalDate getMaturityDate() {
        return maturityDate;
    }

    public Integer getOverdueDays() {
        return overdueDays;
    }

    public String getStatus() {
        return status;
    }
}
