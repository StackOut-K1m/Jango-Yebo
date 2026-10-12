package com.jangoyebo.forecast.planned;

import com.jangoyebo.common.domain.FlowType;
import com.jangoyebo.common.error.BusinessException;
import com.jangoyebo.common.error.ErrorCode;
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
import jakarta.persistence.Version;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * 미래 특정 날짜의 예정 거래 (ERD {@code PLANNED_TRANSACTION}, API 9.4).
 *
 * <p><b>잔액 예측(P0-07)의 직접 입력이다.</b> 곡선은 이 목록을 날짜별로 더해 만든다.
 * 확정된 반복 패턴이 자동 생성하거나(P0-04) 사용자가 직접 등록한다(P0-06).
 *
 * <p><b>날짜가 둘인 이유</b> — {@code expectedDate} 는 공휴일 보정 <b>후</b>,
 * {@code originalExpectedDate} 는 보정 전이다. 둘이 다르면 보정이 걸린 것이고 화면이
 * "공휴일로 하루 밀림"을 표시한다(P1-04). 보정 로직 자체는 아직 없어 지금은 항상 같다.
 *
 * <p><b>금액이 셋인 이유</b> — 패턴에서 온 것은 범위가 있고(min~max), 사용자가 등록한
 * 일회성은 확정값이라 셋이 같다. 예측 곡선의 낙관·비관 폭이 여기서 나온다.
 */
@Entity
@Table(name = "planned_transaction", schema = "forecast")
public class PlannedTransaction extends BaseEntity implements BusinessScoped {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "business_id", nullable = false, updatable = false)
    private Long businessId;

    @Column(name = "account_id")
    private Long accountId;

    @Column(name = "category_id")
    private Long categoryId;

    @Enumerated(EnumType.STRING)
    @Column(name = "source_type", nullable = false, updatable = false, length = 20)
    private PlannedSourceType sourceType;

    @Column(name = "recurring_pattern_id", updatable = false)
    private Long recurringPatternId;

    /** 생성 출처 대출 ({@code sourceType = LOAN}). 대출 조건이 바뀌면 이 값으로 찾아 다시 만든다. */
    @Column(name = "loan_id", updatable = false)
    private Long loanId;

    /** 시스템이 만든 것이면 null. */
    @Column(name = "created_by", updatable = false)
    private Long createdBy;

    @Column(nullable = false, length = 100)
    private String name;

    @Column(name = "expected_date", nullable = false)
    private LocalDate expectedDate;

    @Column(name = "original_expected_date", nullable = false)
    private LocalDate originalExpectedDate;

    @Enumerated(EnumType.STRING)
    @Column(name = "flow_type", nullable = false, updatable = false, length = 20)
    private FlowType flowType;

    @Column(name = "amount_expected", nullable = false)
    private BigDecimal amountExpected;

    @Column(name = "amount_min", nullable = false)
    private BigDecimal amountMin;

    @Column(name = "amount_max", nullable = false)
    private BigDecimal amountMax;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private PlannedStatus status;

    @Column(name = "matched_transaction_id")
    private Long matchedTransactionId;

    /**
     * 실제일 − 예정일. 늦게 들어왔으면 양수다.
     *
     * <p>컬럼은 {@code V1} 에 있었는데 매핑이 없었다 — 채우는 코드가 없었기 때문이다.
     * 정확도 리포트(P1-05)가 거래처별 평균 지연일을 여기서 집계한다.
     */
    @Column(name = "matched_date_diff_days")
    private Integer matchedDateDiffDays;

    /** 실제액 − 예정액. 금액 범위 폭을 좁히는 근거가 된다(P1-05). */
    @Column(name = "matched_amount_diff")
    private BigDecimal matchedAmountDiff;

    @Column
    private String memo;

    /**
     * 낙관적 락. 예정 거래는 <b>사용자 수정과 롤링 배치가 같은 행을 건드린다</b> —
     * 배치가 패턴 기준으로 금액을 갱신하는 사이 사용자가 날짜를 바꾸면 한쪽이 유실된다.
     */
    @Version
    @Column(nullable = false)
    private int version;

    protected PlannedTransaction() {
    }

    private PlannedTransaction(Long businessId, PlannedSourceType sourceType, String name,
                               LocalDate expectedDate, FlowType flowType,
                               BigDecimal min, BigDecimal expected, BigDecimal max,
                               PlannedStatus status) {
        this.businessId = businessId;
        this.sourceType = sourceType;
        this.name = name;
        this.expectedDate = expectedDate;
        // 기본은 둘이 같다. 다르게 만드는 것은 공휴일 보정뿐이고, 그건 fromPattern 이 받는다.
        this.originalExpectedDate = expectedDate;
        this.flowType = flowType;
        this.amountMin = min;
        this.amountExpected = expected;
        this.amountMax = max;
        this.status = status;
    }

    /**
     * 사용자가 직접 등록한 일회성 거래 (P0-06).
     *
     * <p>사용자가 넣은 것은 이미 확정된 의사이므로 {@code EXPECTED} 가 아니라
     * {@link PlannedStatus#CONFIRMED} 로 시작한다(API 9.4 응답 예시).
     */
    public static PlannedTransaction byUser(Long businessId, Long userId, String name,
                                            LocalDate expectedDate, FlowType flowType,
                                            BigDecimal min, BigDecimal expected, BigDecimal max,
                                            Long accountId, Long categoryId, String memo) {
        PlannedTransaction pt = new PlannedTransaction(businessId, PlannedSourceType.USER, name,
                expectedDate, flowType, min, expected, max, PlannedStatus.CONFIRMED);
        pt.createdBy = userId;
        pt.accountId = accountId;
        pt.categoryId = categoryId;
        pt.memo = memo;
        return pt;
    }

    /**
     * 확정된 반복 패턴이 만든 예정 거래 (P0-04).
     *
     * <p>{@link PlannedStatus#EXPECTED} 로 시작한다 — 사용자가 등록한 것과 달리 <b>아직 예측</b>이다.
     * 롤링 배치가 이 상태의 미래분만 갱신하므로 사용자가 {@code CANCELLED} 로 바꾼 것은 살아남는다.
     */
    /**
     * @param expectedDate 실제로 돈이 오가는 날 — <b>공휴일 보정을 거친 뒤</b>
     * @param anchorDate   주기가 가리킨 날 — 보정 <b>전</b>. 둘이 다르면 화면이 「휴일로 밀림」을
     *                     표시한다. 같은 값을 넣으면 그 표시가 조용히 사라진다
     */
    public static PlannedTransaction fromPattern(Long businessId, Long patternId, String name,
                                                 LocalDate expectedDate, LocalDate anchorDate,
                                                 FlowType flowType,
                                                 BigDecimal min, BigDecimal expected, BigDecimal max,
                                                 Long accountId, Long categoryId) {
        PlannedTransaction pt = new PlannedTransaction(businessId, PlannedSourceType.PATTERN, name,
                expectedDate, flowType, min, expected, max, PlannedStatus.EXPECTED);
        pt.originalExpectedDate = anchorDate;
        pt.recurringPatternId = patternId;
        pt.accountId = accountId;
        pt.categoryId = categoryId;
        return pt;
    }

    /**
     * 대출 상환 한 회차.
     *
     * <p><b>금액이 확정이라 min·expected·max 가 같다.</b> 반복 패턴은 관측에서 추정한 범위를
     * 갖지만 대출은 계약이다 — 개설 시점에 마지막 회차까지의 금액이 전부 정해진다.
     *
     * <p>공휴일 보정도 하지 않는다. 금융망은 <b>달력과 무관하게 매일 08:30</b> 에 시도하고,
     * 잔액이 없으면 그날 실패로 남긴다. 은행이 쉬는 날을 비켜 가지 않는다.
     */
    public static PlannedTransaction fromLoan(Long businessId, Long loanId, String name,
                                              LocalDate dueDate, BigDecimal amount,
                                              Long accountId, Long categoryId) {
        PlannedTransaction pt = new PlannedTransaction(businessId, PlannedSourceType.LOAN, name,
                dueDate, FlowType.WITHDRAWAL, amount, amount, amount, PlannedStatus.EXPECTED);
        pt.originalExpectedDate = dueDate;
        pt.loanId = loanId;
        pt.accountId = accountId;
        pt.categoryId = categoryId;
        return pt;
    }

    /** 수정 가능 필드만 받는다 (API 9.4). {@code direction} 과 출처는 바뀌지 않는다. */
    public void update(String name, Long categoryId, LocalDate expectedDate,
                       BigDecimal min, BigDecimal expected, BigDecimal max, String memo) {
        if (name != null) this.name = name;
        if (categoryId != null) this.categoryId = categoryId;
        if (expectedDate != null) {
            this.expectedDate = expectedDate;
            // 사용자가 직접 옮긴 날짜는 그 자체가 원본이다 — 공휴일 보정분으로 오해되면
            // 화면이 "공휴일로 밀림"을 잘못 표시한다.
            this.originalExpectedDate = expectedDate;
        }
        if (min != null) this.amountMin = min;
        if (expected != null) this.amountExpected = expected;
        if (max != null) this.amountMax = max;
        if (memo != null) this.memo = memo;
    }

    /**
     * 실제 거래에 붙는다 (API 9.5).
     *
     * <p><b>{@code EXPECTED}·{@code CONFIRMED} 에서만 붙인다.</b> 이미 {@code MATCHED} 인 것에
     * 다시 붙이면 앞의 매칭이 소리 없이 덮이고, {@code CANCELLED} 는 일어나지 않기로 한 것이다.
     *
     * <p>붙는 순간 이 예정 거래는 <b>곡선에서 빠진다</b> — 실제로 일어나 이미 잔액에 반영돼
     * 있으므로 다시 더하면 이중 계상이다.
     */
    public void match(Long transactionId, int dateDiffDays, BigDecimal amountDiff) {
        if (status != PlannedStatus.EXPECTED && status != PlannedStatus.CONFIRMED) {
            throw new BusinessException(ErrorCode.MATCH_CONFLICT);
        }
        this.matchedTransactionId = transactionId;
        this.matchedDateDiffDays = dateDiffDays;
        this.matchedAmountDiff = amountDiff;
        this.status = PlannedStatus.MATCHED;
    }

    /**
     * 위상 재조정 — 예정일을 {@code days} 만큼 민다 (API 9.5 닻 재조정).
     *
     * <p><b>아직 안 붙은 회차에만 건다.</b> 이미 매칭됐거나 취소된 것을 밀면 과거가 바뀐다.
     *
     * <p>{@code originalExpectedDate} 도 같이 민다. 둘이 갈라지는 것은 <b>공휴일 보정</b>이
     * 걸렸다는 뜻이고 화면이 그렇게 읽는데(이 클래스 머리말), 이건 보정이 아니라
     * <b>일정 자체가 옮겨간 것</b>이다.
     */
    public void reschedule(int days) {
        if (days == 0 || status != PlannedStatus.EXPECTED || matchedTransactionId != null) {
            return;
        }
        this.expectedDate = expectedDate.plusDays(days);
        this.originalExpectedDate = originalExpectedDate.plusDays(days);
    }

    public void cancel() {
        this.status = PlannedStatus.CANCELLED;
    }

    /** 사용자가 만든 것만 지울 수 있다. 패턴 생성분은 취소로 대신한다(API 9.4). */
    public boolean isDeletable() {
        return sourceType == PlannedSourceType.USER;
    }

    public Long getId() { return id; }
    @Override public Long getBusinessId() { return businessId; }
    public Long getAccountId() { return accountId; }
    public Long getCategoryId() { return categoryId; }
    public PlannedSourceType getSourceType() { return sourceType; }
    public Long getLoanId() {
        return loanId;
    }

    public Long getRecurringPatternId() { return recurringPatternId; }
    public String getName() { return name; }
    public LocalDate getExpectedDate() { return expectedDate; }
    public LocalDate getOriginalExpectedDate() { return originalExpectedDate; }
    public FlowType getFlowType() { return flowType; }
    public BigDecimal getAmountExpected() { return amountExpected; }
    public BigDecimal getAmountMin() { return amountMin; }
    public BigDecimal getAmountMax() { return amountMax; }
    public PlannedStatus getStatus() { return status; }
    public Long getMatchedTransactionId() { return matchedTransactionId; }
    public Integer getMatchedDateDiffDays() { return matchedDateDiffDays; }
    public BigDecimal getMatchedAmountDiff() { return matchedAmountDiff; }
    public String getMemo() { return memo; }
}
