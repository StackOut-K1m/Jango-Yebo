package com.jangoyebo.forecast.calendar;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.time.LocalDate;

/**
 * 사업장 영업일 달력 한 행 (ERD {@code BUSINESS_CALENDAR_DAY}).
 *
 * <p><b>{@code businessId} 가 null 이면 공통 달력</b>이다 — 관공서 공휴일처럼 모든 사업장에
 * 같이 적용되는 것. 사업장 행이 따로 있으면 그쪽이 이긴다.
 *
 * <p>이 테이블이 있어야 하는 이유는 {@link DayType} 머리말에 있다 — 공휴일과 휴무일이
 * 다르고, <b>휴무일은 사장님만 안다.</b> 이력에서 배울 수가 없다: 탐지 창(180일)에 휴업이
 * 한 번도 없는 경우가 흔하고(시드 「카페 한잔」이 그렇다) 그러면 「금액이 영업일 수에
 * 비례하는가」를 검정해도 신호가 없다.
 *
 * <p>그래서 <b>아는 쪽이 등록한다.</b> 등록이 없으면 투영은 지금과 똑같이 동작한다
 * (열린 날 비율 1.0).
 */
@Entity
@Table(name = "business_calendar_day", schema = "forecast")
public class BusinessCalendarDay {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** null 이면 공통 달력. */
    @Column(name = "business_id")
    private Long businessId;

    @Column(name = "calendar_date", nullable = false)
    private LocalDate calendarDate;

    @Enumerated(EnumType.STRING)
    @Column(name = "day_type", nullable = false, length = 20)
    private DayType dayType;

    @Enumerated(EnumType.STRING)
    @Column(name = "source", nullable = false, length = 20)
    private CalendarSource source;

    @Column(name = "name", length = 100)
    private String name;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected BusinessCalendarDay() {
    }

    public static BusinessCalendarDay of(Long businessId, LocalDate date, DayType type,
                                         CalendarSource source, String name) {
        BusinessCalendarDay row = new BusinessCalendarDay();
        row.businessId = businessId;
        row.calendarDate = date;
        row.dayType = type;
        row.source = source;
        row.name = name;
        row.createdAt = Instant.now();
        row.updatedAt = Instant.now();
        return row;
    }

    /** 같은 날짜를 다시 등록하면 덮어쓴다. 사장님이 「그날 열기로 했다」고 바꿀 수 있어야 한다. */
    public void update(DayType type, CalendarSource source, String name) {
        this.dayType = type;
        this.source = source;
        this.name = name;
        this.updatedAt = Instant.now();
    }

    public Long getId() { return id; }
    public Long getBusinessId() { return businessId; }
    public LocalDate getCalendarDate() { return calendarDate; }
    public DayType getDayType() { return dayType; }
    public CalendarSource getSource() { return source; }
    public String getName() { return name; }
}
