package com.jangoyebo.forecast.run;

import com.jangoyebo.forecast.engine.SafetyLineDetector.SafetyLineSegment;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

/**
 * 안전선 하회 알림 문구 (API 10.3 {@code SAFETY_LINE}).
 *
 * <p>{@link CashGapMessages} 와 같은 규칙이다 — <b>확정 표현을 쓰지 않고</b>(도메인 규칙 1)
 * 부족액을 <b>예상~하한</b> 범위로 적는다. 예상값 하나만 적으면 확정처럼 읽히고, 하한만 적으면
 * 실제보다 심각해 보인다.
 *
 * <p><b>공백 문구와 말이 달라야 한다.</b> 「부족」은 0원 아래를 가리키는 말로 공백이 이미 쓰고
 * 있어, 하회는 「안전선보다 모자란다」로 적는다. 둘이 같은 말을 쓰면 알림 목록에서 급한 쪽을
 * 가려낼 수 없다.
 */
final class SafetyLineMessages {

    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("M월 d일", Locale.KOREAN);

    /** 만원 단위 축약의 기준. 이보다 작으면 원 단위로 적는다. */
    private static final BigDecimal MAN = new BigDecimal("10000");

    private SafetyLineMessages() {
    }

    static String title(SafetyLineSegment segment) {
        return DATE.format(segment.firstBelowDate()) + " 안전선 아래로 내려갈 가능성";
    }

    static String message(SafetyLineSegment segment, BigDecimal safetyLine) {
        String from = DATE.format(segment.firstBelowDate());
        String worst = DATE.format(segment.lowestDate());
        String amount = range(segment.shortfall(), segment.worstShortfall().max(segment.shortfall()));

        if (segment.firstBelowDate().equals(segment.lowestDate())) {
            return "현재 데이터 기준 %s 예상 잔액이 안전선(%s)보다 약 %s 모자랄 가능성이 있습니다."
                    .formatted(from, manwon(safetyLine), amount);
        }
        return "현재 데이터 기준 %s부터 예상 잔액이 안전선(%s) 아래로 내려가, %s에 약 %s 모자랄 가능성이 있습니다."
                .formatted(from, manwon(safetyLine), worst, amount);
    }

    /** {@code 50만~80만 원}. 둘 중 하나라도 만원 미만이면 각각 그대로 적는다. */
    private static String range(BigDecimal low, BigDecimal high) {
        if (low.compareTo(high) == 0) {
            return manwon(low);
        }
        if (low.compareTo(MAN) < 0 || high.compareTo(MAN) < 0) {
            return manwon(low) + "~" + manwon(high);
        }
        return "%,d만~%,d만 원".formatted(man(low), man(high));
    }

    /** {@code 42만 원}. 만원 미만은 그대로 적는다 — 「0만 원」이 되면 뜻이 사라진다. */
    private static String manwon(BigDecimal amount) {
        if (amount.compareTo(MAN) < 0) {
            return "%,d원".formatted(amount.setScale(0, RoundingMode.HALF_UP).longValue());
        }
        return "%,d만 원".formatted(man(amount));
    }

    private static long man(BigDecimal amount) {
        return amount.divide(MAN, 0, RoundingMode.HALF_UP).longValue();
    }
}
