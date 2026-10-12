package com.jangoyebo.common.domain;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 공휴일 목록이 세 곳에서 같은가, 그리고 보정이 맞는가.
 *
 * <p>Docker 가 필요 없다 — 정적 비교와 순수 함수뿐이다.
 *
 * <pre>
 * scripts/seed/plan.mjs            HOLIDAYS_2026   (Node)   ← 기준
 * ai/app/forecast/calendar_kr.py   HOLIDAYS        (Python) ← test_calendar_matches_seed.py 가 묶는다
 * BusinessCalendar                 HOLIDAYS        (Java)   ← 이 테스트가 묶는다
 * </pre>
 *
 * <p>세 곳에 적으면 어긋난다. 실제로 어긋나 있었다 — 대체공휴일 둘(3/2 · 5/25)이 워커에만
 * 빠져 있었고, 이 비교를 짜면서 찾았다.
 */
class BusinessCalendarTests {

    private static final Path SEED = Path.of("..", "scripts", "seed", "plan.mjs");
    private static final Pattern YMD = Pattern.compile("'(\\d{4})-(\\d{2})-(\\d{2})'");

    @Test
    void 시드와_같은_공휴일을_쓴다() throws IOException {
        assertThat(SEED).as("시드 생성기를 못 찾았다").exists();

        Set<LocalDate> theirs = seedHolidays();
        Set<LocalDate> mine = BusinessCalendar.holidays();

        assertThat(new TreeSet<>(mine))
                .as("공휴일이 어긋난다 — scripts/seed/plan.mjs 의 HOLIDAYS_2026 이 기준이다")
                .isEqualTo(new TreeSet<>(theirs));
    }

    /**
     * <b>해가 바뀌면 여기서 먼저 터진다.</b>
     *
     * <p>음력 기반(설날·부처님오신날·추석)은 매년 바뀌어 추정할 수 없다. 달력을 안 늘리면
     * 그 해는 공휴일이 하나도 없는 것이 되어 <b>운영에서 조용히 틀린다.</b> 빌드에서 터지는
     * 쪽이 낫다.
     *
     * <p>고치는 순서: {@code scripts/seed/plan.mjs} → {@code ai/.../calendar_kr.py} →
     * {@link BusinessCalendar}.
     */
    @Test
    void 달력이_올해를_안다() {
        assertThat(BusinessCalendar.covers(LocalDate.now()))
                .as("달력이 %d년까지만 안다 — 세 곳의 목록을 모두 늘린다",
                        BusinessCalendar.KNOWN_YEAR)
                .isTrue();
    }

    @Test
    void 주말과_공휴일은_영업일이_아니다() {
        assertThat(BusinessCalendar.isBusinessDay(LocalDate.of(2026, 10, 8))).isTrue();   // 목
        assertThat(BusinessCalendar.isBusinessDay(LocalDate.of(2026, 10, 9))).isFalse();  // 한글날(금)
        assertThat(BusinessCalendar.isBusinessDay(LocalDate.of(2026, 10, 10))).isFalse(); // 토
        assertThat(BusinessCalendar.isBusinessDay(LocalDate.of(2026, 10, 12))).isTrue();  // 월
    }

    /**
     * 한글날이 <b>금요일</b>이라 주말 규칙에 안 걸린다. 이 하루가 이 프로젝트에서 공휴일
     * 달력이 필요한 이유다 — 없으면 그날 카드 정산을 투영하고, 그 돈은 실제로 없다.
     */
    @Test
    void 한글날은_다음_영업일이_월요일이다() {
        assertThat(BusinessCalendar.nextBusinessDay(LocalDate.of(2026, 10, 9)))
                .isEqualTo(LocalDate.of(2026, 10, 12));
        assertThat(BusinessCalendar.previousBusinessDay(LocalDate.of(2026, 10, 9)))
                .isEqualTo(LocalDate.of(2026, 10, 8));
    }

    /** 영업일은 그대로 둔다. 「보정」이 아니라 「보정이 필요하면」이다. */
    @Test
    void 영업일은_옮기지_않는다() {
        LocalDate thursday = LocalDate.of(2026, 10, 8);
        assertThat(BusinessCalendar.nextBusinessDay(thursday)).isEqualTo(thursday);
        assertThat(BusinessCalendar.previousBusinessDay(thursday)).isEqualTo(thursday);
    }

    /** 추석 연휴는 목·금·토에 일요일까지 붙어 나흘이다. 다음 영업일이 월요일이어야 한다. */
    @Test
    void 긴_연휴도_넘어간다() {
        assertThat(BusinessCalendar.nextBusinessDay(LocalDate.of(2026, 9, 24)))
                .isEqualTo(LocalDate.of(2026, 9, 28));
    }

    private static Set<LocalDate> seedHolidays() throws IOException {
        String text = Files.readString(SEED, StandardCharsets.UTF_8);
        int from = text.indexOf("const HOLIDAYS_2026");
        String block = text.substring(from, text.indexOf("]);", from));

        Matcher m = YMD.matcher(block);
        return m.results()
                .map(r -> LocalDate.of(Integer.parseInt(r.group(1)),
                        Integer.parseInt(r.group(2)), Integer.parseInt(r.group(3))))
                .collect(Collectors.toSet());
    }
}
