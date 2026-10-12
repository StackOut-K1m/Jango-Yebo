package com.jangoyebo.forecast.pattern;

import com.jangoyebo.NeedsDocker;
import com.jangoyebo.common.domain.BusinessCalendar;
import com.jangoyebo.forecast.calendar.BusinessCalendarDtos.DayRequest;
import com.jangoyebo.forecast.calendar.BusinessCalendarService;
import com.jangoyebo.forecast.calendar.CalendarSource;
import com.jangoyebo.forecast.calendar.DayType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 달력이 바뀌면 예정 거래를 다시 만든다 ({@code RecurringPatternService.refreshPlanned}).
 *
 * <p><b>닻({@code next_expected_date})이 함께 저장되는지를 본다.</b> 예정 거래를 다시 만드는
 * 것은 눈에 보이지만 닻은 안 보인다 — 틀려도 그 자리에서는 아무 일도 일어나지 않고, 다음
 * 재생성이 <b>옛 위상에서 시작</b>할 때 조용히 드러난다.
 */
@NeedsDocker
@SpringBootTest
class RecurringPatternRefreshTests {

    @Autowired
    private BusinessCalendarService calendar;

    @Autowired
    private RecurringPatternRepository patterns;

    @Autowired
    private JdbcTemplate jdbc;

    private Long businessId;
    private Long accountId;

    @BeforeEach
    void setUp() {
        Long userId = jdbc.queryForObject("""
                INSERT INTO core.users (email, password_hash, name) VALUES (?, 'x', '사장') RETURNING id
                """, Long.class, "refresh-" + System.nanoTime() + "@test.local");
        businessId = jdbc.queryForObject("""
                INSERT INTO core.business (owner_user_id, name) VALUES (?, '카페 한잔') RETURNING id
                """, Long.class, userId);
        accountId = jdbc.queryForObject("""
                INSERT INTO core.account (business_id, bank_code, account_number_encrypted,
                                          account_number_hash, account_number_masked, name, status)
                VALUES (?, '999', 'enc', ?, '999-**-1234', '주계좌', 'CONNECTED') RETURNING id
                """, Long.class, businessId, "hash-" + System.nanoTime());
    }

    /**
     * <b>닻이 첫 회차로 옮겨져 저장돼야 한다.</b>
     *
     * <p>{@code BUSINESS_DAILY} 는 영업일에만 회차가 있다. 닻이 토요일에 있으면 그날은 건너뛰고
     * 다음 영업일이 첫 회차가 되므로, 재생성 뒤 닻은 <b>토요일이 아니라 그 첫 회차</b>여야 한다.
     *
     * <p>이 단정이 필요한 이유는 벌크 삭제가 영속성 컨텍스트를 비우기 때문이다
     * ({@code deleteRegenerable} 의 {@code clearAutomatically}). 그 전에 읽어 둔 패턴은
     * detach 돼서, 거기에 닻을 써도 <b>커밋에 UPDATE 가 나가지 않는다.</b>
     */
    @Test
    void 달력을_고치면_닻도_첫_회차로_옮겨져_저장된다() {
        LocalDate today = LocalDate.now();
        LocalDate saturday = nextSaturdayFrom(today);
        Long patternId = businessDailyPattern(saturday);

        calendar.upsert(businessId,
                List.of(new DayRequest(today.plusDays(20), DayType.CLOSED, "휴업")),
                CalendarSource.USER, today);

        LocalDate firstOccurrence = jdbc.queryForObject("""
                SELECT min(expected_date) FROM forecast.planned_transaction
                 WHERE recurring_pattern_id = ?
                """, LocalDate.class, patternId);
        assertThat(firstOccurrence)
                .as("토요일에는 회차가 없다 — 첫 회차는 그다음 영업일이다")
                .isNotNull()
                .isNotEqualTo(saturday);

        assertThat(patterns.findById(patternId).orElseThrow().getNextExpectedDate())
                .as("닻은 첫 회차와 같아야 한다. 토요일에 머물러 있으면 저장이 안 된 것이다")
                .isEqualTo(firstOccurrence);
    }

    /** 토요일에서 시작한 닻이 그대로 남으면, 다음 재생성이 다시 그 토요일부터 센다. */
    @Test
    void 두_번_고쳐도_닻이_과거에_남지_않는다() {
        LocalDate today = LocalDate.now();
        Long patternId = businessDailyPattern(nextSaturdayFrom(today));

        calendar.upsert(businessId, List.of(new DayRequest(today.plusDays(20), DayType.CLOSED, "휴업")),
                CalendarSource.USER, today);
        LocalDate afterFirst = patterns.findById(patternId).orElseThrow().getNextExpectedDate();

        calendar.upsert(businessId, List.of(new DayRequest(today.plusDays(21), DayType.CLOSED, "휴업")),
                CalendarSource.USER, today);
        LocalDate afterSecond = patterns.findById(patternId).orElseThrow().getNextExpectedDate();

        assertThat(afterSecond)
                .as("두 번째 재생성도 같은 자리에서 출발해야 한다")
                .isEqualTo(afterFirst);
        assertThat(BusinessCalendar.isBusinessDay(afterSecond))
                .as("영업일마다 도는 패턴의 닻은 영업일이다")
                .isTrue();
    }

    /**
     * <b>닻이 과거에 있어도 지난 회차는 만들지 않는다</b> (S15P21C105-180).
     *
     * <p>닻은 첫 회차에 머무르므로 시간이 지나면 과거에 있다. 하한 없이 세면 지난 회차가 한 벌
     * 더 생기고 — 같은 날짜에 행이 겹치고, {@code MAX_GENERATED} 를 과거가 먹어 미래가 잘리고,
     * 무엇보다 최근 사흘치가 <b>자동 반영의 따라잡기 창에 들어가 이미 지난 회차의 돈이 나간다.</b>
     */
    @Test
    void 닻이_과거에_있어도_지난_회차를_만들지_않는다() {
        LocalDate today = LocalDate.now();
        Long patternId = businessDailyPattern(today.minusDays(40));

        calendar.upsert(businessId,
                List.of(new DayRequest(today.plusDays(20), DayType.CLOSED, "휴업")),
                CalendarSource.USER, today);

        LocalDate first = jdbc.queryForObject("""
                SELECT min(expected_date) FROM forecast.planned_transaction
                 WHERE recurring_pattern_id = ?
                """, LocalDate.class, patternId);
        assertThat(first)
                .as("지난 회차를 다시 만들면 안 된다")
                .isNotNull()
                .isAfterOrEqualTo(today);

        assertThat(patterns.findById(patternId).orElseThrow().getNextExpectedDate())
                .as("닻도 회차 단위로 굴러 오늘 이후에 있어야 한다")
                .isAfterOrEqualTo(today);
    }

    // --- 픽스처 --------------------------------------------------------------

    private static LocalDate nextSaturdayFrom(LocalDate from) {
        LocalDate date = from.plusDays(1);
        while (date.getDayOfWeek() != DayOfWeek.SATURDAY) {
            date = date.plusDays(1);
        }
        return date;
    }

    private Long businessDailyPattern(LocalDate anchor) {
        return jdbc.queryForObject("""
                INSERT INTO forecast.recurring_pattern
                       (business_id, account_id, name, flow_type, cadence_type,
                        expected_amount_min, expected_amount_max, next_expected_date,
                        confidence, status)
                VALUES (?, ?, '신한카드', 'DEPOSIT', 'BUSINESS_DAILY', 100000, 160000, ?, 0.9, 'CONFIRMED')
                RETURNING id
                """, Long.class, businessId, accountId, anchor);
    }
}
