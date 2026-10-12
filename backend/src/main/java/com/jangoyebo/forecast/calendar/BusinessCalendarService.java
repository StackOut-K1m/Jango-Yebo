package com.jangoyebo.forecast.calendar;

import com.jangoyebo.forecast.calendar.BusinessCalendarDtos.DayRequest;
import com.jangoyebo.forecast.pattern.RecurringPatternService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.List;

/**
 * 영업일 달력 등록 (설계노트 G-2 ③).
 *
 * <p><b>등록하면 곡선이 바로 움직여야 한다.</b> 휴무일만큼 정산액을 줄이는 계산은 예정
 * 거래를 만들 때 이뤄지므로({@code RecurringPatternService.generatePlanned}), 나중에
 * 달력을 고치면 이미 만들어진 예정 거래는 옛 금액 그대로다. 그래서 여기서 다시 만든다 —
 * 안 그러면 사장님이 「추석에 쉰다」를 등록했는데 화면이 그대로다.
 */
@Service
public class BusinessCalendarService {

    private static final Logger log = LoggerFactory.getLogger(BusinessCalendarService.class);

    private final BusinessCalendarDayRepository calendar;
    private final RecurringPatternService patterns;

    public BusinessCalendarService(BusinessCalendarDayRepository calendar,
                                   RecurringPatternService patterns) {
        this.calendar = calendar;
        this.patterns = patterns;
    }

    @Transactional(readOnly = true)
    public List<BusinessCalendarDay> list(Long businessId, LocalDate from, LocalDate to) {
        return calendar.findByBusinessIdAndCalendarDateBetweenOrderByCalendarDate(businessId, from, to);
    }

    /**
     * 같은 날짜를 다시 보내면 덮어쓴다 — 「그날 열기로 했다」로 바꿀 수 있어야 한다.
     *
     * @return 저장한 일수와 다시 만든 패턴 수
     */
    @Transactional
    public int[] upsert(Long businessId, List<DayRequest> days, CalendarSource source, LocalDate today) {
        for (DayRequest d : days) {
            calendar.findByBusinessIdAndCalendarDate(businessId, d.date())
                    .ifPresentOrElse(
                            row -> row.update(d.dayType(), source, d.name()),
                            () -> calendar.save(BusinessCalendarDay.of(
                                    businessId, d.date(), d.dayType(), source, d.name())));
        }
        int refreshed = patterns.refreshPlanned(businessId, today);
        log.info("영업일 달력 — 사업장 {} / {}일 등록 / 패턴 {}개 재생성",
                businessId, days.size(), refreshed);
        return new int[]{days.size(), refreshed};
    }

    /** 등록을 지운다. 없는 날짜면 아무 일도 일어나지 않는다 — 여러 번 불러도 같다. */
    @Transactional
    public int remove(Long businessId, LocalDate date, LocalDate today) {
        boolean removed = calendar.findByBusinessIdAndCalendarDate(businessId, date)
                .map(row -> {
                    calendar.delete(row);
                    return true;
                }).orElse(false);
        return removed ? patterns.refreshPlanned(businessId, today) : 0;
    }
}
