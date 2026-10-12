package com.jangoyebo.forecast.planned;

import com.jangoyebo.common.domain.FlowType;
import com.jangoyebo.common.entity.BaseEntity;
import com.jangoyebo.common.tenancy.BusinessScoped;
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
import java.time.LocalDate;

/**
 * 예정 거래를 금융망에 반영한 원장 한 줄 (ERD {@code PLANNED_EXECUTION}, API 9.6).
 *
 * <p><b>이 테이블이 이중 출금을 막는다.</b> 예정 거래당 한 행이고({@code uk_pe_planned}),
 * 같은 패턴의 같은 회차도 한 행이다({@code uk_pe_pattern_due}). 두 번째 제약이 필요한 이유는
 * 달력을 고치면 {@code refreshPlanned} 가 미래 예정 거래를 지우고 <b>새 id 로 다시 만들기</b>
 * 때문이다 — 첫 제약만으로는 같은 회차가 두 번 나갈 수 있다.
 *
 * <p><b>예정 거래를 직접 고치지 않는다.</b> {@code PlannedTransaction} 에는 낙관적 락이 걸려
 * 있고 사용자가 같은 행을 수정할 수 있다. 실행 사실을 별도 테이블에 적으면 그 경쟁을 아예
 * 만들지 않는다 — 예정 거래를 건드리는 것은 나중에 정확 매칭 한 번뿐이다.
 */
@Entity
@Table(name = "planned_execution", schema = "forecast")
public class PlannedExecution extends BaseEntity implements BusinessScoped {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "planned_transaction_id", nullable = false, updatable = false)
    private Long plannedTransactionId;

    @Column(name = "business_id", nullable = false, updatable = false)
    private Long businessId;

    @Column(name = "account_id", nullable = false, updatable = false)
    private Long accountId;

    /** 패턴 생성분이면 그 패턴. 회차 중복을 막는 부분 UK 의 키다. */
    @Column(name = "recurring_pattern_id", updatable = false)
    private Long recurringPatternId;

    /**
     * 예정일. <b>실제 거래가 찍힌 날과 다를 수 있다</b> — 금융망은 과거 날짜를 만들지 못해
     * 따라잡기 실행은 실행한 날로 찍힌다(기술스택 §4.1.1).
     */
    @Column(name = "due_date", nullable = false, updatable = false)
    private LocalDate dueDate;

    @Enumerated(EnumType.STRING)
    @Column(name = "flow_type", nullable = false, updatable = false, length = 20)
    private FlowType flowType;

    /** 실제로 보낸 금액. 예상액을 정수 원으로 반올림·클램프한 값이다. */
    @Column(nullable = false, updatable = false)
    private BigDecimal amount;

    /** {@code transactionSummary} 로 보낸 적요. 예정 거래 이름과 같다. */
    @Column(nullable = false, length = 100)
    private String summary;

    /** 같은 {@code (예정일, 방향, 금액, 적요)} 안의 순번. 채번과 채택 배정을 가른다. */
    @Column(nullable = false, updatable = false)
    private int ordinal;

    @Column(name = "institution_transaction_unique_no", length = 20)
    private String institutionTransactionUniqueNo;

    /** 금융망 거래고유번호. {@code core.transaction.external_transaction_id} 와 같은 값이다. */
    @Column(name = "fin_transaction_unique_no", length = 64)
    private String finTransactionUniqueNo;

    @Column(name = "fin_transaction_date")
    private LocalDate finTransactionDate;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private PlannedExecutionStatus status;

    /** 거래내역에서 주워 온 것이면 {@code true} — 다른 서버나 이전 시도가 보낸 것이다. */
    @Column(nullable = false)
    private boolean adopted;

    /** 어느 실행기가 보냈나(hostname+pid). 두 환경이 겹쳤을 때 사후 추적용이다. */
    @Column(name = "executor_id", length = 100)
    private String executorId;

    @Column(name = "fin_code", length = 10)
    private String finCode;

    @Column(name = "failure_reason")
    private String failureReason;

    @Column(name = "attempt_count", nullable = false)
    private int attemptCount;

    /** {@code FAILED} 인데 값이 있으면 그날 이후 재시도, {@code null} 이면 영구 실패다. */
    @Column(name = "retry_after")
    private LocalDate retryAfter;

    @Column(name = "last_attempt_at")
    private Instant lastAttemptAt;

    @Column(name = "executed_at")
    private Instant executedAt;

    protected PlannedExecution() {
    }

    /**
     * @param accountId <b>실제로 보낸 계좌.</b> 예정 거래의 계좌가 아니다 — 웹 폼이 계좌를 보내기
     *                  전에 만들어진 행은 그 값이 비어 있어서, 어느 계좌로 나갔는지는 실행 시점에만
     *                  안다. 원장에는 언제나 실제 계좌가 남아야 추적이 된다
     */
    private PlannedExecution(PlannedTransaction planned, Long accountId, BigDecimal amount,
                             int ordinal, PlannedExecutionStatus status) {
        this.plannedTransactionId = planned.getId();
        this.businessId = planned.getBusinessId();
        this.accountId = accountId;
        this.recurringPatternId = planned.getRecurringPatternId();
        this.dueDate = planned.getExpectedDate();
        this.flowType = planned.getFlowType();
        this.amount = amount;
        this.summary = planned.getName();
        this.ordinal = ordinal;
        this.status = status;
    }

    /**
     * 보내기 <b>직전에</b> 만든다. 이 행을 커밋한 뒤에야 금융망을 부른다.
     *
     * <p>순서를 뒤집으면 응답을 기다리다 죽었을 때 흔적이 남지 않아 다음 차례가 또 보낸다.
     */
    public static PlannedExecution sending(PlannedTransaction planned, Long accountId,
                                           BigDecimal amount, int ordinal, String executorId,
                                           Instant now) {
        PlannedExecution execution =
                new PlannedExecution(planned, accountId, amount, ordinal, PlannedExecutionStatus.SENT);
        execution.executorId = executorId;
        execution.attemptCount = 1;
        execution.lastAttemptAt = now;
        return execution;
    }

    /**
     * 금융망에 <b>이미 있던</b> 거래를 이 예정 거래의 것으로 인정한다.
     *
     * <p>다른 서버가 보냈거나 우리가 이전에 보내고 결과를 못 받은 경우다. 호출을 하지 않았으므로
     * {@code attemptCount} 를 올리지 않는다 — 「우리가 몇 번 시도했나」를 흐리지 않기 위해서다.
     */
    public static PlannedExecution adopted(PlannedTransaction planned, Long accountId,
                                           BigDecimal amount, int ordinal,
                                           String finTransactionUniqueNo, LocalDate finTransactionDate,
                                           Instant now) {
        PlannedExecution execution =
                new PlannedExecution(planned, accountId, amount, ordinal, PlannedExecutionStatus.EXECUTED);
        execution.adopted = true;
        execution.finTransactionUniqueNo = finTransactionUniqueNo;
        execution.finTransactionDate = finTransactionDate;
        execution.executedAt = now;
        return execution;
    }

    /** 보낼 수 없는 것이었다. 행을 남기는 것은 다음 차례가 같은 판단을 반복하지 않게 하려는 것이다. */
    public static PlannedExecution skipped(PlannedTransaction planned, Long accountId,
                                           BigDecimal amount, int ordinal, String reason) {
        PlannedExecution execution =
                new PlannedExecution(planned, accountId, amount, ordinal, PlannedExecutionStatus.SKIPPED);
        execution.failureReason = reason;
        return execution;
    }

    /**
     * 우리가 실제로 보낸 채번을 적어 둔다.
     *
     * <p>보내기 전에는 모른다 — 채번은 계좌번호 원문이 필요해 {@code core} 안에서 만든다.
     * 정정성에는 쓰이지 않고(채번은 언제나 다시 계산된다) <b>사후 추적용</b>이다:
     * {@code H1007} 이 났을 때 어떤 번호가 부딪혔는지 여기서만 알 수 있다.
     */
    public void rememberUniqueNo(String institutionTransactionUniqueNo) {
        if (institutionTransactionUniqueNo != null) {
            this.institutionTransactionUniqueNo = institutionTransactionUniqueNo;
        }
    }

    /** 나갔다. 금융망 번호는 응답에 없을 수 있어 나중에 거래내역으로 메우기도 한다. */
    public void markExecuted(String finTransactionUniqueNo, LocalDate finTransactionDate, Instant now) {
        this.status = PlannedExecutionStatus.EXECUTED;
        this.finTransactionUniqueNo = finTransactionUniqueNo;
        this.finTransactionDate = finTransactionDate;
        this.executedAt = now;
        this.failureReason = null;
        this.retryAfter = null;
    }

    /** 거래내역에서 주워 온 번호로 채운다. 호출은 하지 않았다. */
    public void markAdopted(String finTransactionUniqueNo, LocalDate finTransactionDate, Instant now) {
        markExecuted(finTransactionUniqueNo, finTransactionDate, now);
        this.adopted = true;
    }

    /**
     * @param retryAfter {@code null} 이면 영구 실패다 — 같은 요청을 다시 보내도 같은 답이 온다
     */
    public void markFailed(String finCode, String reason, LocalDate retryAfter, Instant now) {
        this.status = PlannedExecutionStatus.FAILED;
        this.finCode = finCode;
        this.failureReason = reason;
        this.retryAfter = retryAfter;
        this.lastAttemptAt = now;
    }

    /**
     * 다시 보내기 <b>직전</b>. 시도 횟수는 여기서만 오른다.
     *
     * <p>결과를 적는 쪽({@link #recordAttempt})에서도 올리면 한 번 보낼 때 둘이 올라
     * 재전송 상한이 절반에서 걸린다 — 실제로 그렇게 만들었다가 테스트가 잡았다.
     */
    public void beginAttempt(Instant now) {
        this.status = PlannedExecutionStatus.SENT;
        this.attemptCount++;
        this.lastAttemptAt = now;
    }

    /** 답을 못 받았다. {@code SENT} 로 남겨 다음 차례가 거래내역부터 보게 한다. */
    public void recordAttempt(String finCode, String reason, Instant now) {
        this.status = PlannedExecutionStatus.SENT;
        this.finCode = finCode;
        this.failureReason = reason;
        this.lastAttemptAt = now;
    }

    public void markMatched() {
        this.status = PlannedExecutionStatus.MATCHED;
    }

    /** 아직 결과를 모르는 행. 다음 차례가 거래내역으로 판정한다. */
    public boolean isOpen() {
        return status == PlannedExecutionStatus.SENT;
    }

    public Long getId() { return id; }
    public Long getPlannedTransactionId() { return plannedTransactionId; }
    @Override public Long getBusinessId() { return businessId; }
    public Long getAccountId() { return accountId; }
    public Long getRecurringPatternId() { return recurringPatternId; }
    public LocalDate getDueDate() { return dueDate; }
    public FlowType getFlowType() { return flowType; }
    public BigDecimal getAmount() { return amount; }
    public String getSummary() { return summary; }
    public int getOrdinal() { return ordinal; }
    public String getInstitutionTransactionUniqueNo() { return institutionTransactionUniqueNo; }
    public String getFinTransactionUniqueNo() { return finTransactionUniqueNo; }
    public LocalDate getFinTransactionDate() { return finTransactionDate; }
    public PlannedExecutionStatus getStatus() { return status; }
    public boolean isAdopted() { return adopted; }
    public String getExecutorId() { return executorId; }
    public String getFinCode() { return finCode; }
    public String getFailureReason() { return failureReason; }
    public int getAttemptCount() { return attemptCount; }
    public LocalDate getRetryAfter() { return retryAfter; }
    public Instant getLastAttemptAt() { return lastAttemptAt; }
    public Instant getExecutedAt() { return executedAt; }
}
