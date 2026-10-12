package com.jangoyebo.forecast.pattern;

import java.math.BigDecimal;

/**
 * 신뢰도 구간 (공통규약 2.5).
 *
 * <p>DB 는 {@code NUMERIC(5,4)} 로 0~1 을 저장하고 API 는 이 네 값 중 하나를 내보낸다.
 * 숫자를 그대로 노출하지 않는 이유는 도메인 규칙 1 — <b>확정 표현 금지</b>다.
 * "0.81" 은 정밀해 보이지만 근거는 표본 12건이다.
 *
 * <p>경계값은 조정 대상이다. 표본이 쌓이면 실제 적중률(P1-05)로 다시 맞춘다.
 */
public enum ConfidenceBand {

    HIGH,
    MEDIUM,
    LOW,

    /** 계산은 됐지만 신뢰할 수 없다. 오류가 아니라 상태다(공통규약 2.5). */
    UNPREDICTABLE;

    // --- 조정 가능 지점 ---------------------------------------------------
    /** 이 값 이상이면 HIGH. */
    private static final BigDecimal HIGH_AT = new BigDecimal("0.80");
    /** 이 값 이상이면 MEDIUM. */
    private static final BigDecimal MEDIUM_AT = new BigDecimal("0.60");
    /** 이 값 미만이면 UNPREDICTABLE — 화면에 올리지 않는 편이 나은 수준이다. */
    private static final BigDecimal LOW_AT = new BigDecimal("0.40");

    public static ConfidenceBand of(BigDecimal score) {
        if (score == null || score.compareTo(LOW_AT) < 0) {
            return UNPREDICTABLE;
        }
        if (score.compareTo(HIGH_AT) >= 0) {
            return HIGH;
        }
        return score.compareTo(MEDIUM_AT) >= 0 ? MEDIUM : LOW;
    }
}
