package com.jangoyebo.forecast.run;

import com.jangoyebo.forecast.engine.CashGapDetector.CashGapSegment;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

/**
 * 공백 알림 문구 (API 10.2·10.3).
 *
 * <p><b>확정 표현을 쓰지 않는다</b>(도메인 규칙 1). "부족합니다" ✕ →
 * "현재 데이터 기준 부족할 가능성이 있습니다" ○. 예측은 항상 <b>범위</b>로 말한다.
 *
 * <p>10.2 는 문구 생성을 클라이언트가 한다고 적었지만, 알림({@code ALERT.title}·
 * {@code message})은 서버가 만들어 저장해야 하는 값이라 여기 둔다. 형식은 10.3 의 예시다.
 */
final class CashGapMessages {

    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("M월 d일", Locale.KOREAN);

    /** 만원 단위 축약의 기준. 이보다 작으면 원 단위로 적는다. */
    private static final BigDecimal MAN = new BigDecimal("10000");

    private CashGapMessages() {
    }

    static String title(CashGapSegment segment) {
        return DATE.format(segment.firstShortfallDate()) + " 잔액 부족 가능성";
    }

    /**
     * 부족액을 <b>예상~하한</b> 범위로 적는다. 예상값 하나만 적으면 확정처럼 읽히고,
     * 하한만 적으면 실제보다 심각해 보인다.
     */
    static String message(CashGapSegment segment) {
        String date = DATE.format(segment.firstShortfallDate());
        BigDecimal low = segment.maxShortfallAmount();
        BigDecimal high = segment.worstCaseShortfall().max(low);

        String amount = low.compareTo(high) == 0
                ? manwon(low)
                : manwon(low) + "~" + manwon(high);
        return "현재 데이터 기준 %s 잔액이 약 %s 부족할 가능성이 있습니다.".formatted(date, amount);
    }

    /** {@code 42만 원}. 만원 미만은 그대로 적는다 — 「0만 원」이 되면 뜻이 사라진다. */
    private static String manwon(BigDecimal amount) {
        if (amount.compareTo(MAN) < 0) {
            return "%,d원".formatted(amount.setScale(0, RoundingMode.HALF_UP).longValue());
        }
        return "%,d만 원".formatted(amount.divide(MAN, 0, RoundingMode.HALF_UP).longValue());
    }
}
