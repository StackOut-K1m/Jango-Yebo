package com.jangoyebo.forecast.calendar;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.HashSet;
import java.util.Set;

/**
 * <b>이 기간에 가게가 며칠 열었나</b> — 정산액을 그만큼 줄이는 데 쓴다.
 *
 * <p>카드·배달·현금 정산은 <b>그 기간의 매출을 모아서</b> 들어온다. 명절에 사흘 닫으면
 * 그 주 정산이 그만큼 적다. 지금 투영은 매 회차 같은 금액을 찍어 곡선을 위로 띄운다.
 *
 * <p>실측 (시드 「카페 한잔」 · 기준일 2026-09-13 · 30일):
 *
 * <pre>
 * 배달 정산 09-29   투영 497,400원   실제 290,200원    그 주에 추석 휴업 사흘
 * 현금 정산 09-28   투영 147,550원   실제 101,700원
 * </pre>
 *
 * <p>예정 투영의 입금 과대평가 331,850원 중 <b>253,050원(76%)</b>이 이 두 건이다.
 *
 * <h2>주말·공휴일은 빼지 않는다</h2>
 *
 * <p>세는 것은 <b>열린 날</b>이지 영업일이 아니다. 카페는 주말에도 열고 한글날에도 연다 —
 * 그날 매출은 그대로 생기고 정산만 밀린다. 영업일로 세면 한글날이 낀 주까지 깎아
 * <b>오차를 다른 오차로 바꾼다</b>(실측: 그렇게 하면 10-13 회차가 −79,480원 틀린다).
 *
 * <p>그래서 {@link DayType#CLOSED} 만 뺀다.
 */
@Component
public class OpenDayRatio {

    private final BusinessCalendarDayRepository calendar;

    public OpenDayRatio(BusinessCalendarDayRepository calendar) {
        this.calendar = calendar;
    }

    /**
     * {@code (from, to]} 구간의 열린 날 비율.
     *
     * <p><b>등록된 휴무일이 없으면 1.0 이다.</b> 달력을 안 채운 사업장은 지금과 똑같이
     * 동작한다 — 이 기능이 조용히 금액을 바꾸는 일이 없어야 한다.
     */
    @Transactional(readOnly = true)
    public BigDecimal of(Long businessId, LocalDate from, LocalDate to) {
        int span = (int) ChronoUnit.DAYS.between(from, to);
        if (span <= 0) {
            return BigDecimal.ONE;
        }
        Set<LocalDate> closed = new HashSet<>(
                calendar.findClosedDates(businessId, from.plusDays(1), to));
        if (closed.isEmpty()) {
            return BigDecimal.ONE;
        }
        return BigDecimal.valueOf(span - closed.size())
                .divide(BigDecimal.valueOf(span), 4, RoundingMode.HALF_UP);
    }
}
