package com.jangoyebo.core.transaction;

import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * CSV 거래일시 파싱 (S15P21C105-138). Docker 불필요.
 *
 * <p><b>여기서 못 받으면 파일 전체가 0건으로 끝난다.</b> 형식 오류 행만 건너뛰는 관용성은
 * 한 파일 안에서 형식이 섞여 있을 때 쓸모가 있는데, 엑셀은 파일 전체를 같은 형식으로
 * 다시 쓴다 — 한 행이 깨지면 전 행이 깨진다.
 */
class CsvTimestampsTests {

    private static final LocalDateTime MORNING = LocalDateTime.of(2026, 9, 1, 9, 30);

    @Test
    void 엑셀이_다시_쓴_표기를_모두_같은_시각으로_읽는다() {
        // 어느 PC 에서 저장했든 저장된 거래일시가 같아야 한다. 다르면 중복 판정이 갈린다.
        List<String> sameMoment = List.of(
                "2026-09-01 09:30:00",      // 은행 원본
                "2026-09-01 9:30",          // 엑셀 기본 재저장
                "2026-09-01 9:30:00",
                "2026-09-01 09:30",
                "2026-09-01 오전 9:30:00",   // 한국어 로케일
                "2026-09-01 오전 9:30",
                "2026. 09. 01 9:30",        // 날짜 구분자가 점
                "2026/09/01 9:30",
                "2026-09-01T09:30:00",      // ISO 구분자
                "2026-09-01 9:30:00 AM",    // 영문 로케일
                "2026-09-01 9:30:00 am",
                "  2026-09-01   9:30  ");

        assertThat(sameMoment).allSatisfy(raw ->
                assertThat(CsvTimestamps.parse(raw)).as(raw).isEqualTo(MORNING));
    }

    @Test
    void 오후를_열두시간_더해_읽는다() {
        // 오전·오후를 무시하면 저녁 정산이 아침으로 찍힌다 — 곡선이 하루를 당겨 그린다.
        assertThat(CsvTimestamps.parse("2026-09-01 오후 9:30"))
                .isEqualTo(LocalDateTime.of(2026, 9, 1, 21, 30));
        assertThat(CsvTimestamps.parse("2026-09-01 9:30:00 PM"))
                .isEqualTo(LocalDateTime.of(2026, 9, 1, 21, 30));
    }

    @Test
    void 정오와_자정의_열두시를_뒤집지_않는다() {
        // 12시간제에서 가장 자주 틀리는 자리다.
        assertThat(CsvTimestamps.parse("2026-09-01 오전 12:00")).isEqualTo(midnight());
        assertThat(CsvTimestamps.parse("2026-09-01 오후 12:00"))
                .isEqualTo(LocalDateTime.of(2026, 9, 1, 12, 0));
    }

    @Test
    void 시각이_없으면_자정으로_둔다() {
        // 날짜만 있는 내보내기가 실제로 있다. 시각을 지어내는 것보다 하루의 시작이 낫다.
        assertThat(CsvTimestamps.parse("2026-09-01")).isEqualTo(midnight());
        assertThat(CsvTimestamps.parse("2026. 09. 01")).isEqualTo(midnight());
        assertThat(CsvTimestamps.parse("2026. 09. 01.")).isEqualTo(midnight());
        assertThat(CsvTimestamps.parse("2026/09/01")).isEqualTo(midnight());
    }

    @Test
    void 파일_앞머리_BOM_을_걷어낸다() {
        assertThat(CsvTimestamps.parse("﻿2026-09-01 9:30")).isEqualTo(MORNING);
    }

    @Test
    void 연도가_뒤에_오면_추측하지_않고_거부한다() {
        // 1월 9일인지 9월 1일인지 문자열만으로 알 수 없다. 돈을 세는 데이터에서
        // 날짜를 잘못 찍으면 곡선이 조용히 틀린다 — 거부하면 사용자가 보고 고친다.
        assertThatThrownBy(() -> CsvTimestamps.parse("01/09/2026 9:30"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("거래일시 형식");
    }

    @Test
    void 없는_날짜를_만들어내지_않는다() {
        for (String impossible : List.of("2026-13-45 9:30", "2026-02-30", "2026-09-01 25:00")) {
            assertThatThrownBy(() -> CsvTimestamps.parse(impossible)).as(impossible)
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Test
    void 읽을_수_없으면_사유에_예시를_담는다() {
        // 사용자가 무엇을 고쳐야 하는지 알 수 있어야 한다.
        for (String bad : List.of("", "   ", "어제", "2026년 9월 1일")) {
            assertThatThrownBy(() -> CsvTimestamps.parse(bad)).as(bad)
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("2026-09-01 09:30:00");
        }
    }

    @Test
    void 널을_받아도_던지기만_한다() {
        assertThatThrownBy(() -> CsvTimestamps.parse(null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private static LocalDateTime midnight() {
        return LocalDateTime.of(2026, 9, 1, 0, 0);
    }
}
