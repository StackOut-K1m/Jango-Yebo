package com.jangoyebo.core.transaction;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeFormatterBuilder;
import java.time.format.DateTimeParseException;
import java.time.format.ResolverStyle;
import java.time.format.SignStyle;
import java.time.temporal.ChronoField;
import java.util.List;
import java.util.Locale;

/**
 * CSV 거래일시 파싱 (API 7.4).
 *
 * <p><b>엑셀이 형식을 바꿔 놓는다.</b> 은행에서 내려받은 CSV 를 사장님이 엑셀로 한 번
 * 열었다 저장하면, 거래일시가 원래 문자열이 아니라 <b>그 PC 의 셀 표시 형식</b>으로 다시
 * 쓰인다. {@code 2026-09-01 09:30:00} 이 {@code 2026-09-01 9:30} 이 되고, 로케일에 따라
 * {@code 오전 9:30:00} 이나 {@code 2026. 09. 01} 도 나온다.
 *
 * <p>고정 패턴 하나만 받으면 <b>파일 전체가 같은 형식이라 전 행이 거부되고 0건으로 끝난다.</b>
 * 행 단위 관용성(형식 오류 행만 건너뛴다)이 여기서는 아무 도움이 안 된다.
 *
 * <h2>추측하지 않는 선</h2>
 *
 * <p><b>{@code 01/09/2026} 처럼 연도가 뒤에 오는 형식은 받지 않는다.</b> 1월 9일인지 9월
 * 1일인지 문자열만으로 알 수 없고, <b>돈을 세는 데이터에서 날짜를 잘못 찍으면 곡선이 조용히
 * 틀린다.</b> 거부하면 사용자가 {@code rejectedRows} 를 보고 고칠 수 있다.
 *
 * <p><b>날짜를 잘라 맞추지 않는다.</b> {@link ResolverStyle#STRICT} 다 — 기본값인 SMART 는
 * {@code 2026-02-30} 을 조용히 2월 28일로 바꾼다. 없는 날짜가 들어왔다는 것은 파일이
 * 잘못됐다는 뜻이고, 그 행은 거부해야 사용자가 안다.
 *
 * <p>시각이 없으면 자정으로 둔다. 날짜만 있는 내보내기가 실제로 있고, 그 경우 시각을
 * 지어내는 것보다 하루의 시작으로 두는 편이 낫다 — 중복 판정도 일관되게 걸린다.
 */
final class CsvTimestamps {

    /** 오전·오후가 앞에 오는 한국 엑셀 표기. */
    private static final DateTimeFormatter AMPM_PREFIX = withMinuteSecond(date()
            .appendLiteral(' ')
            .appendText(ChronoField.AMPM_OF_DAY)
            .appendLiteral(' ')
            .appendValue(ChronoField.CLOCK_HOUR_OF_AMPM, 1, 2, SignStyle.NEVER))
            .parseDefaulting(ChronoField.SECOND_OF_MINUTE, 0)
            .toFormatter(Locale.KOREAN)
            .withResolverStyle(ResolverStyle.STRICT);

    /** {@code AM}·{@code PM} 이 뒤에 오는 영문 엑셀 표기. 대소문자를 가리지 않는다. */
    private static final DateTimeFormatter AMPM_SUFFIX = withMinuteSecond(
            new DateTimeFormatterBuilder()
                    .parseCaseInsensitive()
                    .append(date().toFormatter())
                    .appendLiteral(' ')
                    .appendValue(ChronoField.CLOCK_HOUR_OF_AMPM, 1, 2, SignStyle.NEVER))
            .appendLiteral(' ')
            .appendText(ChronoField.AMPM_OF_DAY)
            .parseDefaulting(ChronoField.SECOND_OF_MINUTE, 0)
            .toFormatter(Locale.ENGLISH)
            .withResolverStyle(ResolverStyle.STRICT);

    /** 24시간 표기. 시·분·초의 자릿수를 가리지 않고, 시각 전체가 없어도 된다. */
    private static final DateTimeFormatter NUMERIC = withMinuteSecond(date()
            .optionalStart()
            .appendLiteral(' ')
            .appendValue(ChronoField.HOUR_OF_DAY, 1, 2, SignStyle.NEVER))
            .optionalEnd()
            .parseDefaulting(ChronoField.HOUR_OF_DAY, 0)
            .parseDefaulting(ChronoField.MINUTE_OF_HOUR, 0)
            .parseDefaulting(ChronoField.SECOND_OF_MINUTE, 0)
            .toFormatter()
            .withResolverStyle(ResolverStyle.STRICT);

    /** 좁은 것부터 시도한다 — {@code NUMERIC} 이 오전·오후를 못 읽으므로 순서는 무관하나 의도를 남긴다. */
    private static final List<DateTimeFormatter> FORMATS =
            List.of(NUMERIC, AMPM_PREFIX, AMPM_SUFFIX);

    private CsvTimestamps() {
    }

    /**
     * @return 파싱된 거래일시
     * @throws IllegalArgumentException 어떤 형식으로도 읽히지 않을 때. 호출자가 그 행만
     *                                  건너뛰고 {@code rejectedRows} 에 사유를 담는다
     */
    static LocalDateTime parse(String raw) {
        String normalized = normalize(raw);
        for (DateTimeFormatter format : FORMATS) {
            try {
                return LocalDateTime.parse(normalized, format);
            } catch (DateTimeParseException e) {
                // 다음 형식으로
            }
        }
        throw new IllegalArgumentException(
                "거래일시 형식을 알 수 없습니다: %s (예: 2026-09-01 09:30:00)".formatted(raw));
    }

    /**
     * 구분자와 공백을 하나로 모은다.
     *
     * <p>연·월·일 순서는 바꾸지 않는다 — 바꾸는 순간 {@code 01/09/2026} 을 해석하는 셈이 된다.
     */
    private static String normalize(String raw) {
        if (raw == null) {
            return "";
        }
        return raw.replace("\uFEFF", "").trim()
                // 2026. 09. 01 · 2026/09/01 → 2026-09-01
                .replaceAll("\\s*[./]\\s*", "-")
                // 2026. 09. 01. 처럼 끝에 남는 구분자
                .replaceAll("-+$", "")
                // ISO 구분자
                .replace('T', ' ')
                .replaceAll("\\s+", " ");
    }

    private static DateTimeFormatterBuilder date() {
        return new DateTimeFormatterBuilder()
                .appendValue(ChronoField.YEAR, 4)
                .appendLiteral('-')
                .appendValue(ChronoField.MONTH_OF_YEAR, 1, 2, SignStyle.NEVER)
                .appendLiteral('-')
                .appendValue(ChronoField.DAY_OF_MONTH, 1, 2, SignStyle.NEVER);
    }

    /** {@code :분} 과 생략 가능한 {@code :초}. 세 형식이 공유한다. */
    private static DateTimeFormatterBuilder withMinuteSecond(DateTimeFormatterBuilder builder) {
        return builder
                .appendLiteral(':')
                .appendValue(ChronoField.MINUTE_OF_HOUR, 1, 2, SignStyle.NEVER)
                .optionalStart()
                .appendLiteral(':')
                .appendValue(ChronoField.SECOND_OF_MINUTE, 1, 2, SignStyle.NEVER)
                .optionalEnd();
    }
}
