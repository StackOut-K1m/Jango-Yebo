package com.jangoyebo.forecast.calendar;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.LocalDate;
import java.util.List;

/** 영업일 달력 요청·응답 (ERD {@code BUSINESS_CALENDAR_DAY}). */
public final class BusinessCalendarDtos {

    private BusinessCalendarDtos() {
    }

    /**
     * 여러 날을 한 번에 등록한다. <b>명절 연휴는 사흘이 한 덩어리</b>라 하루씩 보내게 하면
     * 중간에 실패했을 때 반만 등록된 상태가 남는다.
     */
    public record UpsertRequest(@NotEmpty @Valid List<DayRequest> days) {
    }

    public record DayRequest(
            @NotNull LocalDate date,
            @NotNull DayType dayType,
            @Size(max = 100) String name) {
    }

    public record CalendarDayResponse(LocalDate date, DayType dayType,
                                      CalendarSource source, String name) {

        public static CalendarDayResponse of(BusinessCalendarDay row) {
            return new CalendarDayResponse(row.getCalendarDate(), row.getDayType(),
                    row.getSource(), row.getName());
        }
    }

    /** 등록 결과. {@code refreshedPatterns} 가 0 이면 곡선은 다음 승인 때 바뀐다. */
    public record UpsertResponse(int saved, int refreshedPatterns) {
    }
}
