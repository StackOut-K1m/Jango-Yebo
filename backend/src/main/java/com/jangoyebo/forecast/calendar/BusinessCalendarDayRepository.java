package com.jangoyebo.forecast.calendar;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

public interface BusinessCalendarDayRepository extends JpaRepository<BusinessCalendarDay, Long> {

    /**
     * 이 사업장이 <b>문 닫은 날</b>. 공통 달력은 보지 않는다 — 공휴일은 휴무일이 아니다
     * ({@link DayType} 머리말).
     */
    @Query("""
            select c.calendarDate from BusinessCalendarDay c
            where c.businessId = :businessId and c.dayType = com.jangoyebo.forecast.calendar.DayType.CLOSED
              and c.calendarDate between :from and :to
            """)
    List<LocalDate> findClosedDates(@Param("businessId") Long businessId,
                                    @Param("from") LocalDate from,
                                    @Param("to") LocalDate to);

    Optional<BusinessCalendarDay> findByBusinessIdAndCalendarDate(Long businessId, LocalDate date);

    List<BusinessCalendarDay> findByBusinessIdAndCalendarDateBetweenOrderByCalendarDate(
            Long businessId, LocalDate from, LocalDate to);
}
