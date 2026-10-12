package com.jangoyebo.common.domain;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.Set;

/**
 * 관공서 공휴일과 영업일 (P1-04).
 *
 * <p><b>주말만 보면 곡선이 조용히 틀린다.</b> 카드사는 영업일에만 정산하므로 공휴일 매출은
 * 다음 영업일에 얹혀 들어오는데, 공휴일을 모르면 그날치를 그날 받는 것으로 투영한다 —
 * <b>없는 돈이 곡선에 들어간다.</b>
 *
 * <p>실측(시드 「카페 한잔」): 2026-10-09 한글날은 <b>금요일</b>이라 주말 규칙에 안 걸린다.
 * 그날 투영한 카드 정산은 실제로 없고, 10/10~11 주말까지 밀려 10/12 월요일에 사흘치가
 * 한 번에 들어온다. 이 시드가 설계한 10월 공백이 바로 그 어긋남에서 나온다.
 *
 * <h2>목록이 세 곳에 있다</h2>
 *
 * <pre>
 * scripts/seed/plan.mjs        HOLIDAYS_2026      시드 생성기 (Node)
 * ai/app/forecast/calendar_kr.py  HOLIDAYS        워커 달력 특징 (Python)
 * 이 클래스                       HOLIDAYS         예정 거래 투영 (Java)
 * </pre>
 *
 * <p>모듈도 언어도 배포 단위도 달라 한 파일을 같이 읽을 수가 없다. 대신
 * {@code BusinessCalendarTests} 가 <b>셋이 같은 날짜를 갖는지 테스트로 묶는다.</b>
 * 실제로 워커 쪽에 대체공휴일 둘(3/2·5/25)이 빠져 있었고, 그렇게 찾았다.
 *
 * <h2>2026년만 안다</h2>
 *
 * <p>음력 기반(설날·부처님오신날·추석)은 매년 바뀌어 추정할 수 없다. 해가 바뀌면
 * <b>테스트가 먼저 실패한다</b>({@code 달력이_올해를_안다}) — 운영에서 조용히 틀리는 대신
 * 빌드에서 터지게 해 둔 것이다.
 */
public final class BusinessCalendar {

    /** 달력이 아는 해. 이 밖은 주말만 보고 판단하므로 {@link #covers}로 먼저 확인한다. */
    public static final int KNOWN_YEAR = 2026;

    /**
     * 2026 관공서 공휴일. <b>대체공휴일을 포함한다</b> — 삼일절(3/1)과 부처님오신날(5/24)이
     * 일요일이라 다음 월요일이 공휴일이다. 이 둘을 빠뜨리면 그 주 정산이 하루씩 어긋난다.
     */
    private static final Set<LocalDate> HOLIDAYS = Set.of(
            LocalDate.of(2026, 1, 1),    // 신정 (목)
            LocalDate.of(2026, 2, 16),   // 설날 연휴 (월)
            LocalDate.of(2026, 2, 17),   // 설날 (화)
            LocalDate.of(2026, 2, 18),   // 설날 연휴 (수)
            LocalDate.of(2026, 3, 1),    // 삼일절 (일)
            LocalDate.of(2026, 3, 2),    // 〃 대체공휴일 (월)
            LocalDate.of(2026, 5, 5),    // 어린이날 (화)
            LocalDate.of(2026, 5, 24),   // 부처님오신날 (일)
            LocalDate.of(2026, 5, 25),   // 〃 대체공휴일 (월)
            LocalDate.of(2026, 6, 6),    // 현충일 (토)
            LocalDate.of(2026, 8, 15),   // 광복절 (토)
            LocalDate.of(2026, 9, 24),   // 추석 연휴 (목)
            LocalDate.of(2026, 9, 25),   // 추석 (금)
            LocalDate.of(2026, 9, 26),   // 추석 연휴 (토)
            LocalDate.of(2026, 10, 3),   // 개천절 (토)
            LocalDate.of(2026, 10, 9),   // 한글날 (금)
            LocalDate.of(2026, 12, 25)   // 성탄절 (금)
    );

    /** 영업일을 못 찾고 헤매는 것을 막는다. 연휴가 아무리 길어도 2주를 넘지 않는다. */
    private static final int MAX_SHIFT_DAYS = 14;

    private BusinessCalendar() {
    }

    /** 이 날짜를 달력이 아는가. 모르는 해는 주말만 보고 판단한다. */
    public static boolean covers(LocalDate date) {
        return date.getYear() == KNOWN_YEAR;
    }

    public static boolean isHoliday(LocalDate date) {
        return HOLIDAYS.contains(date);
    }

    public static boolean isWeekend(LocalDate date) {
        DayOfWeek day = date.getDayOfWeek();
        return day == DayOfWeek.SATURDAY || day == DayOfWeek.SUNDAY;
    }

    public static boolean isBusinessDay(LocalDate date) {
        return !isWeekend(date) && !isHoliday(date);
    }

    /** 영업일이면 그대로, 아니면 다음 영업일. 카드 정산·자동이체 기본값이다. */
    public static LocalDate nextBusinessDay(LocalDate date) {
        return shift(date, 1);
    }

    /** 영업일이면 그대로, 아니면 직전 영업일. 급여처럼 <b>앞당겨</b> 주는 것에 쓴다. */
    public static LocalDate previousBusinessDay(LocalDate date) {
        return shift(date, -1);
    }

    /** 공휴일 목록 사본. 테스트가 다른 구현과 대조하는 데 쓴다. */
    public static Set<LocalDate> holidays() {
        return HOLIDAYS;
    }

    private static LocalDate shift(LocalDate date, int direction) {
        LocalDate cursor = date;
        for (int i = 0; i <= MAX_SHIFT_DAYS; i++) {
            if (isBusinessDay(cursor)) {
                return cursor;
            }
            cursor = cursor.plusDays(direction);
        }
        throw new IllegalStateException(
                "영업일을 %d일 안에 못 찾았다: %s".formatted(MAX_SHIFT_DAYS, date));
    }
}
