package com.jangoyebo.forecast.calendar;

import com.jangoyebo.common.error.BusinessException;
import com.jangoyebo.common.error.ErrorCode;
import com.jangoyebo.common.response.ApiResponse;
import com.jangoyebo.common.tenancy.CurrentBusiness;
import com.jangoyebo.forecast.calendar.BusinessCalendarDtos.CalendarDayResponse;
import com.jangoyebo.forecast.calendar.BusinessCalendarDtos.UpsertRequest;
import com.jangoyebo.forecast.calendar.BusinessCalendarDtos.UpsertResponse;
import jakarta.validation.Valid;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.Map;

/**
 * 영업일 달력 — <b>휴무일 등록</b> (설계노트 G-2 ③).
 *
 * <p>공휴일과 휴무일은 다르다 ({@link DayType}). 공휴일은 달력에서 알 수 있지만
 * <b>가게가 언제 닫는지는 사장님만 안다</b> — 명절에 쉬는 집도 있고 여는 집도 있다.
 * 이력으로 배울 수도 없다: 탐지 창(180일)에 휴업이 한 번도 없는 경우가 흔하다.
 *
 * <p>등록하면 확정된 패턴의 미래 예정 거래를 <b>즉시 다시 만든다</b> — 정산액이
 * 휴무일만큼 줄어야 곡선이 움직인다.
 */
@RestController
@RequestMapping("/api/v1")
public class BusinessCalendarController {

    /** 조회 범위 상한. 예정 거래(92일)와 같은 이유로 한 해를 넘기지 않는다. */
    private static final long MAX_RANGE_DAYS = 366;

    private final BusinessCalendarService service;
    private final Clock clock;

    public BusinessCalendarController(BusinessCalendarService service, Clock clock) {
        this.service = service;
        this.clock = clock;
    }

    @GetMapping("/businesses/{businessId}/calendar-days")
    public ApiResponse<Map<String, Object>> list(
            @CurrentBusiness Long businessId,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {

        if (to.isBefore(from)) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "to 가 from 보다 앞입니다.");
        }
        if (ChronoUnit.DAYS.between(from, to) > MAX_RANGE_DAYS) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED,
                    "조회 범위는 최대 %d일입니다.".formatted(MAX_RANGE_DAYS));
        }
        return ApiResponse.ok(Map.of("items", service.list(businessId, from, to)
                .stream().map(CalendarDayResponse::of).toList()));
    }

    /** 같은 날짜를 다시 보내면 덮어쓴다. 명절 연휴처럼 여러 날을 한 번에 보낸다. */
    @PutMapping("/businesses/{businessId}/calendar-days")
    public ApiResponse<UpsertResponse> upsert(@CurrentBusiness Long businessId,
                                              @Valid @RequestBody UpsertRequest request) {
        int[] result = service.upsert(businessId, request.days(),
                CalendarSource.USER, today());
        return ApiResponse.ok(new UpsertResponse(result[0], result[1]));
    }

    /** 없는 날짜를 지워도 성공이다 — 여러 번 불러도 같은 상태가 된다. */
    @DeleteMapping("/businesses/{businessId}/calendar-days/{date}")
    public ApiResponse<UpsertResponse> remove(
            @CurrentBusiness Long businessId,
            @PathVariable @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {

        int refreshed = service.remove(businessId, date, today());
        return ApiResponse.ok(new UpsertResponse(0, refreshed));
    }

    private LocalDate today() {
        return LocalDate.now(clock.withZone(ZoneId.of("Asia/Seoul")));
    }
}
