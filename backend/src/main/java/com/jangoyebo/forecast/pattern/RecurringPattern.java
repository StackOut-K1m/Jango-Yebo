package com.jangoyebo.forecast.pattern;

import com.jangoyebo.common.domain.FlowType;
import com.jangoyebo.common.entity.BaseEntity;
import com.jangoyebo.common.error.BusinessException;
import com.jangoyebo.common.error.ErrorCode;
import com.jangoyebo.common.tenancy.BusinessScoped;
import com.jangoyebo.forecast.pattern.RecurringPatternDetector.DetectedPattern;
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
import java.util.Map;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * 과거 거래에서 탐지한 반복 규칙 (ERD {@code RECURRING_PATTERN}, API 9.2).
 *
 * <p>탐지 결과는 <b>후보일 뿐이다.</b> 승인해야 예정 거래가 생기고 예측에 들어간다 —
 * 서비스가 반복이라고 단정하지 않는다(도메인 규칙 1, 확정 표현 금지).
 *
 * <p>{@code hitRate}(과거 적중률)는 저장하지 않는다. P1-05 매칭 결과에서 집계할 값이고,
 * 매칭이 없는 지금 계산하면 항상 null 이라 컬럼만 늘어난다.
 */
@Entity
@Table(name = "recurring_pattern", schema = "forecast")
public class RecurringPattern extends BaseEntity implements BusinessScoped {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "business_id", nullable = false, updatable = false)
    private Long businessId;

    @Column(name = "account_id")
    private Long accountId;

    @Column(name = "category_id")
    private Long categoryId;

    @Column(nullable = false, length = 100)
    private String name;

    @Enumerated(EnumType.STRING)
    @Column(name = "flow_type", nullable = false, updatable = false, length = 20)
    private FlowType flowType;

    @Enumerated(EnumType.STRING)
    @Column(name = "cadence_type", nullable = false, length = 20)
    private CadenceType cadenceType;

    /** {@code INTERVAL} 일 때만 채워진다. */
    @Column(name = "interval_value")
    private Integer intervalValue;

    /** {@code MONTHLY} 일 때만. 1~31. */
    @Column(name = "day_of_month")
    private Integer dayOfMonth;

    /** {@code WEEKLY} 일 때만. 1(월)~7(일). */
    @Column(name = "day_of_week")
    private Integer dayOfWeek;

    @Enumerated(EnumType.STRING)
    @Column(name = "holiday_rule", nullable = false, length = 30)
    private HolidayRule holidayRule;

    @Column(name = "expected_amount_min", nullable = false)
    private BigDecimal expectedAmountMin;

    @Column(name = "expected_amount_max", nullable = false)
    private BigDecimal expectedAmountMax;

    /**
     * {@code BUSINESS_DAILY} 전용 요일별 금액 (ISO 요일 1=월 … 7=일).
     *
     * <p><b>{@code null} 이 정상이다.</b> 매월·매주 패턴이거나 표본이 모자라면 채우지 않고,
     * 그때는 {@link #expectedAmountMin}/{@link #expectedAmountMax} 를 쓴다.
     *
     * <p>카드 정산은 주말·공휴일 매출이 다음 영업일로 몰려 월요일이 나머지의 3배다. 범위
     * 하나로 뭉개면 <b>월요일은 모자라고 화~금은 과대</b>하며, 하루 47만원 폭이 30일 누적돼
     * 곡선 밴드가 불필요하게 벌어진다(설계노트 G-2 「금액 축」).
     */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "amount_by_weekday")
    private Map<Integer, RecurringPatternDetector.WeekdayAmount> amountByWeekday;

    @Column(name = "next_expected_date")
    private LocalDate nextExpectedDate;

    /** 0~1. API 는 이 값을 {@link ConfidenceBand} 로 바꿔 내보낸다. */
    @Column(nullable = false)
    private BigDecimal confidence;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private PatternStatus status;

    @Column(name = "confirmed_by")
    private Long confirmedBy;

    @Column(name = "confirmed_at")
    private Instant confirmedAt;

    protected RecurringPattern() {
    }

    public static RecurringPattern candidate(Long businessId, DetectedPattern d) {
        RecurringPattern p = new RecurringPattern();
        p.businessId = businessId;
        p.accountId = d.accountId();
        p.categoryId = d.categoryId();
        p.name = d.name();
        p.flowType = d.flowType();
        p.cadenceType = d.cadenceType();
        p.intervalValue = d.intervalValue();
        p.dayOfMonth = d.dayOfMonth();
        p.dayOfWeek = d.dayOfWeek();
        // 탐지기가 과거 회차에서 읽어낸다. 증거가 없으면 다음 영업일 이월이다(API 9.2) —
        // 카드 정산이 가장 흔한 반복이고 그 기본값이 그것이다.
        p.holidayRule = d.holidayRule() == null ? HolidayRule.NEXT_BUSINESS_DAY : d.holidayRule();
        p.expectedAmountMin = d.amountMin();
        p.expectedAmountMax = d.amountMax();
        p.amountByWeekday = d.amountByWeekday();
        p.nextExpectedDate = d.nextExpectedDate();
        p.confidence = d.confidence();
        p.status = PatternStatus.CANDIDATE;
        return p;
    }

    /**
     * 재탐지 결과로 후보를 갱신한다.
     *
     * <p><b>{@code CANDIDATE} 만 갱신한다.</b> 승인·무시된 것을 덮어쓰면 사용자가 조정한
     * 금액이나 무시 결정이 배치에 지워진다.
     */
    public void refreshIfCandidate(DetectedPattern d) {
        if (status != PatternStatus.CANDIDATE) {
            return;
        }
        this.name = d.name();
        this.cadenceType = d.cadenceType();
        this.intervalValue = d.intervalValue();
        this.dayOfMonth = d.dayOfMonth();
        this.dayOfWeek = d.dayOfWeek();
        // 분류는 탐지가 거래에서 복사한다 — 분류보다 먼저 잡힌 후보는 비어 있고, 여기서
        // 채우지 않으면 <b>채울 곳이 아예 없다</b>: 승인·수정 요청에 분류를 실어 보내는
        // 화면이 없다. 분류가 빈 예정 입금은 시나리오의 매출 변화가 대상으로 세지 않아
        // 「매출로 분류된 예정 입금이 없다」로 끝난다(11.1).
        //
        // <b>빈 결과로 있던 값을 지우지는 않는다.</b> 표본이 아직 미분류인 것과 분류가
        // 풀린 것은 다르고, 후자는 일어나지 않는다.
        if (d.categoryId() != null) this.categoryId = d.categoryId();
        // 표본이 늘면 보정 방향이 뒤집힐 수 있다 — 기준일이 처음 휴일에 걸리는 회차가
        // 들어오는 순간이 그때다. 사용자가 손댄 값은 CANDIDATE 가 아니라 여기 안 온다.
        if (d.holidayRule() != null) this.holidayRule = d.holidayRule();
        this.expectedAmountMin = d.amountMin();
        this.expectedAmountMax = d.amountMax();
        this.amountByWeekday = d.amountByWeekday();
        this.nextExpectedDate = d.nextExpectedDate();
        this.confidence = d.confidence();
    }

    /**
     * 비어 있는 분류만 채운다 — 거래 분류가 뒤늦게 돌았을 때의 백필.
     *
     * <p><b>재탐지로는 닿지 않는 자리가 있다.</b> {@link #refreshIfCandidate} 는 후보만
     * 갱신하므로 이미 승인한 패턴의 빈 분류는 영영 비어 있고, 그 패턴이 만든 예정 입금은
     * 시나리오의 매출 변화에서 빠진다. 그래서 분류가 끝난 시점에 한 번 더 채운다.
     *
     * <p><b>있는 값은 덮지 않는다.</b> 사용자가 고른 분류나 탐지가 읽어낸 분류를 나중에
     * 뒤엎으면 손댄 결과가 배치에 지워진다 — 후보만 갱신하는 이유와 같은 자리다.
     *
     * @return 실제로 채웠으면 {@code true}. 예정 거래를 다시 만들지는 호출자가 정한다
     */
    public boolean fillCategoryIfBlank(Long categoryId) {
        if (this.categoryId != null || categoryId == null) {
            return false;
        }
        this.categoryId = categoryId;
        return true;
    }

    /** 승인. 본문으로 온 값이 있으면 먼저 보정한 뒤 확정한다(API 9.3). */
    public void confirm(Long userId, Long categoryId, BigDecimal amountMin,
                        BigDecimal amountMax, HolidayRule rule) {
        assertCandidate();
        applyAdjustments(categoryId, amountMin, amountMax, rule);
        this.status = PatternStatus.CONFIRMED;
        this.confirmedBy = userId;
        this.confirmedAt = Instant.now();
    }

    public void dismiss() {
        assertCandidate();
        this.status = PatternStatus.DISMISSED;
    }

    /**
     * 수정이 <b>예정 거래에 남기는 일</b>. 호출자는 이 값만 보고 움직인다.
     *
     * <p>{@code boolean} 이었다 — 「멈췄나」만 돌려주니 <b>되돌린 경우와 아무 일도 없는 경우가
     * 같은 {@code false}</b> 였고, 그래서 되돌리기가 조용히 아무것도 하지 않았다. 패턴 목록은
     * 「확정」인데 예정 거래는 꺼진 채로 남아 곡선에서 그 돈이 통째로 사라졌다
     * ({@code S15P21C105-180}).
     */
    public enum Effect {
        /** 예정 거래는 그대로 둔다. */
        NONE,
        /** 멈췄다 — 앞으로의 회차를 걷어낸다. */
        PAUSED,
        /** 확정 상태에서 조건이 바뀌었다 — 걷어내고 다시 만든다. */
        REBUILD
    }

    /**
     * 수정 (API 9.3). 상태는 {@code CONFIRMED} ↔ {@code PAUSED} 만 오갈 수 있다.
     *
     * <p><b>상태를 안 바꾸고 금액·분류·휴일 규칙만 고쳐도 {@code REBUILD} 다.</b> 이미 만들어진
     * 예정 거래는 옛 값 그대로라, 다시 만들지 않으면 <b>고쳤는데 곡선은 그대로</b>가 된다.
     *
     * <p>확정된 것만 예정 거래를 갖고 있다. 후보·무시·중지 상태에서 값만 바꾼 것은
     * {@code NONE} 이다 — 다시 만들 대상이 없다.
     */
    public Effect update(Long categoryId, BigDecimal amountMin, BigDecimal amountMax,
                         HolidayRule rule, PatternStatus newStatus) {
        boolean adjusted = applyAdjustments(categoryId, amountMin, amountMax, rule);
        if (newStatus == null || newStatus == status) {
            return adjusted && status == PatternStatus.CONFIRMED ? Effect.REBUILD : Effect.NONE;
        }
        boolean allowed = (status == PatternStatus.CONFIRMED && newStatus == PatternStatus.PAUSED)
                || (status == PatternStatus.PAUSED && newStatus == PatternStatus.CONFIRMED);
        if (!allowed) {
            throw new BusinessException(ErrorCode.PATTERN_STATE_CONFLICT,
                    "CONFIRMED 와 PAUSED 사이에서만 바꿀 수 있습니다.");
        }
        this.status = newStatus;
        return newStatus == PatternStatus.PAUSED ? Effect.PAUSED : Effect.REBUILD;
    }

    /**
     * @return <b>실제로 달라진 값이 있으면</b> true. 같은 값을 다시 보낸 PATCH 로 예정 거래를
     *         통째로 다시 만드는 것을 막는다 — 새 id 로 갈아끼우는 일이라 공짜가 아니다
     */
    private boolean applyAdjustments(Long categoryId, BigDecimal amountMin,
                                     BigDecimal amountMax, HolidayRule rule) {
        boolean changed = false;
        if (categoryId != null && !categoryId.equals(this.categoryId)) {
            this.categoryId = categoryId;
            changed = true;
        }
        // compareTo 다. BigDecimal 의 equals 는 소수 자릿수까지 보는데 1200000 과 1200000.00 은
        // 같은 돈이다 — equals 로 재면 JSON 이 어떻게 오느냐에 따라 재생성이 갈린다.
        if (amountMin != null && this.expectedAmountMin.compareTo(amountMin) != 0) {
            this.expectedAmountMin = amountMin;
            changed = true;
        }
        if (amountMax != null && this.expectedAmountMax.compareTo(amountMax) != 0) {
            this.expectedAmountMax = amountMax;
            changed = true;
        }
        if (rule != null && rule != this.holidayRule) {
            this.holidayRule = rule;
            changed = true;
        }
        if (this.expectedAmountMin.compareTo(this.expectedAmountMax) > 0) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED,
                    "amountMin 이 amountMax 보다 큽니다.");
        }
        return changed;
    }

    private void assertCandidate() {
        if (status != PatternStatus.CANDIDATE) {
            throw new BusinessException(ErrorCode.PATTERN_STATE_CONFLICT);
        }
    }

    /** 대표값은 범위의 중앙이다. 예정 거래의 기대선이 된다. */
    public BigDecimal expectedAmount() {
        return expectedAmountMin.add(expectedAmountMax)
                .divide(BigDecimal.valueOf(2), 4, java.math.RoundingMode.HALF_UP);
    }

    public void advanceNextExpectedDate(LocalDate date) {
        this.nextExpectedDate = date;
    }

    /**
     * 일정 자체가 옮겨갔을 때 <b>주기의 기준값도 함께 옮긴다</b> (API 9.5 닻 재조정).
     *
     * <p>{@code MONTHLY} 는 생성기가 {@code dayOfMonth} 에서 날짜를 만든다(S15P21C105-187).
     * 닻만 밀고 이 값을 두면 다음 생성이 <b>옛 날짜로 도로 끌어당긴다</b> — 매칭이 재 본
     * 위상이 매번 지워지는 셈이다.
     *
     * <p>{@code WEEKLY} 는 사정이 다르다. 생성이 {@code plusWeeks} 라 옮겨간 요일이 저절로
     * 유지되므로 <b>곡선은 안 틀린다.</b> 그래도 옮긴다 — {@code dayOfWeek} 는 응답에 나가고
     * 화면이 그걸로 「매주 금요일」이라 적는데, 예정 거래가 일요일에 있으면 <b>한 화면이 두
     * 말을 한다</b>(S15P21C105-189). 재탐지도 못 고친다({@link #refreshIfCandidate} 는
     * 후보만 갱신한다).
     *
     * <p>{@code INTERVAL} 은 대상이 아니다. {@code intervalValue} 는 날짜가 아니라 <b>간격</b>
     * 이라 위상이 옮겨져도 그대로다. {@code BUSINESS_DAILY} 는 옮길 위상이 없어 재조정 경로를
     * 아예 안 탄다.
     *
     * <p><b>보정 전 날짜를 준다.</b> 공휴일로 밀린 날짜에서 읽으면 <b>옮겨간 적 없는 일자·요일</b>
     * 이 박히고, 월 반복은 그다음 달부터 주기가 그만큼 흘러간다({@code originalExpectedDate}
     * 가 그 값이다).
     */
    public void moveAnchorDay(LocalDate anchorDate) {
        if (anchorDate == null) {
            return;
        }
        switch (cadenceType) {
            case MONTHLY -> this.dayOfMonth = anchorDate.getDayOfMonth();
            case WEEKLY -> this.dayOfWeek = anchorDate.getDayOfWeek().getValue();
            default -> { }
        }
    }

    public Long getId() { return id; }
    /** {@code null} 이면 요일별 금액이 없다 — 호출부가 min/max 로 떨어져야 한다. */
    public Map<Integer, RecurringPatternDetector.WeekdayAmount> getAmountByWeekday() {
        return amountByWeekday;
    }

    @Override public Long getBusinessId() { return businessId; }
    public Long getAccountId() { return accountId; }
    public Long getCategoryId() { return categoryId; }
    public String getName() { return name; }
    public FlowType getFlowType() { return flowType; }
    public CadenceType getCadenceType() { return cadenceType; }
    public Integer getIntervalValue() { return intervalValue; }
    public Integer getDayOfMonth() { return dayOfMonth; }
    public Integer getDayOfWeek() { return dayOfWeek; }
    public HolidayRule getHolidayRule() { return holidayRule; }
    public BigDecimal getExpectedAmountMin() { return expectedAmountMin; }
    public BigDecimal getExpectedAmountMax() { return expectedAmountMax; }
    public LocalDate getNextExpectedDate() { return nextExpectedDate; }
    public BigDecimal getConfidence() { return confidence; }
    public PatternStatus getStatus() { return status; }
}
