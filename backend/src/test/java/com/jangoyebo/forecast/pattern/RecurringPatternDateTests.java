package com.jangoyebo.forecast.pattern;

import com.jangoyebo.common.domain.FlowType;
import com.jangoyebo.forecast.pattern.RecurringPatternDetector.DetectedPattern;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 반복의 회차 날짜와 주기 기준값 (S15P21C105-187 · 189).
 *
 * <p><b>Docker 가 필요 없다.</b> {@code nextDate}·{@code firstAnchorFrom} 은 순수 함수라
 * DB 없이 돈다 — 탐지기를 그렇게 유지한 이유와 같다(아키텍처 §6).
 *
 * <p>기준일과 「오늘」을 인자로 받으므로 <b>날짜가 흘러도 결과가 바뀌지 않는다.</b> 2월을
 * 지나는 구간이 핵심이라 이게 특히 중요하다 — 실제 시계에 기대면 1월엔 아무것도 검증하지
 * 못하는 테스트가 된다.
 */
class RecurringPatternDateTests {

    /**
     * <b>2월에 잘려도 3월엔 제자리로 돌아온다.</b>
     *
     * <p>{@code plusMonths} 만 하면 2/28 에서 다시 세어 3월도 28일이 되고, 그 뒤로 영영
     * 이틀 이르게 나간다.
     */
    @Test
    void 월말_반복이_2월을_지나도_기준일로_돌아온다() {
        RecurringPattern rent = monthly(30, LocalDate.of(2026, 1, 30));

        assertThat(RecurringPatternService.nextDate(rent, LocalDate.of(2026, 1, 30)))
                .as("2월에는 30일이 없다 — 여기서 잘리는 것은 맞다")
                .isEqualTo(LocalDate.of(2026, 2, 28));
        assertThat(RecurringPatternService.nextDate(rent, LocalDate.of(2026, 2, 28)))
                .as("3월에는 30일이 있다. 28일로 남으면 주저앉은 것이다")
                .isEqualTo(LocalDate.of(2026, 3, 30));
    }

    /** 31일 패턴은 달마다 말일을 따라간다 — 2월 28, 4월 30, 5월 31. */
    @Test
    void 삼십일일_반복은_달_길이를_따라간다() {
        RecurringPattern p = monthly(31, LocalDate.of(2026, 1, 31));

        assertThat(RecurringPatternService.nextDate(p, LocalDate.of(2026, 1, 31)))
                .isEqualTo(LocalDate.of(2026, 2, 28));
        assertThat(RecurringPatternService.nextDate(p, LocalDate.of(2026, 2, 28)))
                .isEqualTo(LocalDate.of(2026, 3, 31));
        assertThat(RecurringPatternService.nextDate(p, LocalDate.of(2026, 3, 31)))
                .isEqualTo(LocalDate.of(2026, 4, 30));
        assertThat(RecurringPatternService.nextDate(p, LocalDate.of(2026, 4, 30)))
                .isEqualTo(LocalDate.of(2026, 5, 31));
    }

    /** 윤년 2월은 29일까지 간다. 2028 이 그 해다. */
    @Test
    void 윤년_2월은_29일이다() {
        RecurringPattern p = monthly(30, LocalDate.of(2028, 1, 30));

        assertThat(RecurringPatternService.nextDate(p, LocalDate.of(2028, 1, 30)))
                .isEqualTo(LocalDate.of(2028, 2, 29));
        assertThat(RecurringPatternService.nextDate(p, LocalDate.of(2028, 2, 29)))
                .isEqualTo(LocalDate.of(2028, 3, 30));
    }

    /** 29일은 평년 2월에만 하루 잘린다. 그 뒤로는 다시 29일이다. */
    @Test
    void 스물아홉일_반복은_평년_2월에만_잘린다() {
        RecurringPattern p = monthly(29, LocalDate.of(2026, 1, 29));

        assertThat(RecurringPatternService.nextDate(p, LocalDate.of(2026, 1, 29)))
                .isEqualTo(LocalDate.of(2026, 2, 28));
        assertThat(RecurringPatternService.nextDate(p, LocalDate.of(2026, 2, 28)))
                .isEqualTo(LocalDate.of(2026, 3, 29));
    }

    /** 기준일을 모르면 지어내지 않는다 — 하던 대로 둔다. */
    @Test
    void 기준일이_없으면_지금_동작을_유지한다() {
        RecurringPattern p = monthly(null, LocalDate.of(2026, 1, 30));

        assertThat(RecurringPatternService.nextDate(p, LocalDate.of(2026, 1, 30)))
                .isEqualTo(LocalDate.of(2026, 2, 28));
        assertThat(RecurringPatternService.nextDate(p, LocalDate.of(2026, 2, 28)))
                .isEqualTo(LocalDate.of(2026, 3, 28));
    }

    /**
     * <b>이미 주저앉은 닻은 다음 생성에서 되돌아온다.</b>
     *
     * <p>배포 DB 에 28일로 저장된 닻이 남아 있어도 마이그레이션이 필요 없다는 뜻이다 —
     * {@code MONTHLY} 에서 진짜는 {@code dayOfMonth} 이고 닻은 파생이다.
     */
    @Test
    void 주저앉은_닻은_기준일로_되돌아온다() {
        RecurringPattern drifted = monthly(30, LocalDate.of(2026, 3, 28));

        assertThat(RecurringPatternService.firstAnchorFrom(drifted, LocalDate.of(2026, 3, 10)))
                .isEqualTo(LocalDate.of(2026, 3, 30));
    }

    /** 닻이 과거에 있어도 회차 단위로 굴리는 동안 기준일을 잃지 않는다. */
    @Test
    void 과거_닻을_굴려도_기준일을_지킨다() {
        RecurringPattern p = monthly(30, LocalDate.of(2026, 1, 30));

        assertThat(RecurringPatternService.firstAnchorFrom(p, LocalDate.of(2026, 6, 15)))
                .as("1월부터 굴러오는 길에 2월이 끼어 있다")
                .isEqualTo(LocalDate.of(2026, 6, 30));
    }

    /**
     * 월 반복은 일정이 옮겨가면 기준일도 따라간다 (API 9.5 닻 재조정).
     *
     * <p>이게 없으면 매칭이 재 본 위상을 다음 생성이 도로 끌어당긴다.
     */
    @Test
    void 월_반복은_일정이_옮겨가면_기준일도_옮긴다() {
        RecurringPattern p = monthly(5, LocalDate.of(2026, 3, 5));

        p.moveAnchorDay(LocalDate.of(2026, 3, 7));

        assertThat(p.getDayOfMonth()).isEqualTo(7);
        assertThat(RecurringPatternService.nextDate(p, LocalDate.of(2026, 3, 7)))
                .isEqualTo(LocalDate.of(2026, 4, 7));
    }

    /**
     * <b>주 반복은 요일이 따라간다</b> (S15P21C105-189).
     *
     * <p>곡선은 이것 없이도 맞는다 — 생성이 {@code plusWeeks} 라 옮겨간 요일이 저절로 유지된다.
     * 틀리는 것은 화면이다. {@code dayOfWeek} 가 옛 값으로 남으면 목록이 「매주 금요일」이라
     * 적는 동안 예정 거래는 일요일에 있다.
     */
    @Test
    void 주_반복은_일정이_옮겨가면_요일도_옮긴다() {
        RecurringPattern p = weekly(DayOfWeek.FRIDAY);

        // 2026-03-08 은 일요일이다.
        p.moveAnchorDay(LocalDate.of(2026, 3, 8));

        assertThat(p.getDayOfWeek()).isEqualTo(DayOfWeek.SUNDAY.getValue());
        assertThat(RecurringPatternService.nextDate(p, LocalDate.of(2026, 3, 8)))
                .as("생성은 원래부터 닻에서 세므로 요일 값과 무관하게 그대로다")
                .isEqualTo(LocalDate.of(2026, 3, 15));
    }

    /** 간격은 날짜가 아니다 — 위상이 옮겨져도 그대로다. */
    @Test
    void 간격_반복은_옮겨도_기준값이_없다() {
        RecurringPattern p = interval(14);

        p.moveAnchorDay(LocalDate.of(2026, 3, 8));

        assertThat(p.getIntervalValue()).isEqualTo(14);
        assertThat(p.getDayOfWeek()).isNull();
        assertThat(p.getDayOfMonth()).isNull();
    }

    /** 보정 전 날짜가 없으면 아무것도 안 바꾼다. */
    @Test
    void 옮길_날짜가_없으면_그대로_둔다() {
        RecurringPattern p = monthly(5, LocalDate.of(2026, 3, 5));

        p.moveAnchorDay(null);

        assertThat(p.getDayOfMonth()).isEqualTo(5);
    }

    // --- 픽스처 --------------------------------------------------------------

    private static RecurringPattern monthly(Integer dayOfMonth, LocalDate anchor) {
        return pattern(CadenceType.MONTHLY, null, dayOfMonth, null, anchor);
    }

    private static RecurringPattern weekly(DayOfWeek dayOfWeek) {
        return pattern(CadenceType.WEEKLY, null, null, dayOfWeek.getValue(),
                LocalDate.of(2026, 3, 6));
    }

    private static RecurringPattern interval(int days) {
        return pattern(CadenceType.INTERVAL, days, null, null, LocalDate.of(2026, 3, 6));
    }

    private static RecurringPattern pattern(CadenceType type, Integer intervalValue,
                                            Integer dayOfMonth, Integer dayOfWeek,
                                            LocalDate anchor) {
        return RecurringPattern.candidate(1L, new DetectedPattern(
                10L, null, FlowType.WITHDRAWAL, "임차료",
                type, intervalValue, dayOfMonth, dayOfWeek, HolidayRule.NONE,
                new BigDecimal("900000"), new BigDecimal("900000"), null,
                anchor, new BigDecimal("0.9"), List.of()));
    }
}
