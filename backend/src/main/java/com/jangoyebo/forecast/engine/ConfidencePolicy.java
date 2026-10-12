package com.jangoyebo.forecast.engine;

import com.jangoyebo.forecast.pattern.ConfidenceBand;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.util.List;

/**
 * 예측 신뢰도 산정 (공통규약 2.5·23장).
 *
 * <p>순수 함수다. <b>DB 는 일별 숫자({@code NUMERIC(5,4)})를 저장하고 API 는 실행 단위
 * 등급 하나를 내보내는데, 그 사이 환산 규칙이 어느 문서에도 없었다</b> — 2026-09-10 에
 * 공통규약 23장의 열린 항목을 이 공식으로 닫았다.
 *
 * <pre>
 * 일별 = 잔여 계수 × 예정 계수 × 기간 계수
 * 등급 = 조회한 days 창의 일별 평균을 ConfidenceBand 로 환산
 * </pre>
 *
 * <p><b>적중률은 아직 안 들어간다.</b> 23장이 "표본 수·변동성·적중률 가중치"를 요구하지만
 * 적중률({@code hitRate})은 예측과 실제를 대조해야 나오고 그건 P1-05 다. 표본이 없는 값에
 * 가중치를 주면 숫자가 근거 없이 정밀해 보인다.
 *
 * <p><b>창 단위로 다시 계산한다.</b> 7일 조회가 30일 조회보다 등급이 높게 나올 수 있고
 * 그것이 의도다 — 기간 계수가 뒤로 갈수록 떨어지므로 짧은 창이 실제로 더 믿을 만하다.
 */
public final class ConfidencePolicy {

    // --- 조정 가능 지점 -------------------------------------------------------

    /** 잔여를 모델이 추정했다. */
    static final BigDecimal RESIDUAL_MODEL = new BigDecimal("1.00");
    /** 통계 베이스라인으로 폴백했다. 모델보다 공백 F1 이 낮다(10.4). */
    static final BigDecimal RESIDUAL_BASELINE = new BigDecimal("0.90");
    /**
     * 잔여를 아예 반영하지 못했다.
     *
     * <p>0.75 인 이유 — 이때 곡선에는 <b>패턴으로 잡히지 않는 흐름(현금 매출·소액 지출)이
     * 통째로 빠져 있다.</b> 값이 틀린 것은 아니지만 설명하는 범위가 좁다.
     */
    static final BigDecimal RESIDUAL_NONE = new BigDecimal("0.75");

    /** 과거 데이터가 {@link #DEGRADED_HISTORY_DAYS} 미만일 때 곱한다 — 한 단계 강등(10.1). */
    static final BigDecimal DEGRADED_FACTOR = new BigDecimal("0.85");

    /**
     * 이 일수 미만이면 강등한다.
     *
     * <p>하한 30일과 짝이다 — 30일 미만은 잔여를 아예 안 쓰므로({@code NONE}) 여기 걸리지 않고,
     * 30~180일이 "값을 내되 낮게" 구간이다(설계노트 「표본 부족 처리」).
     */
    static final int DEGRADED_HISTORY_DAYS = 180;

    /** 마지막 날의 감쇠 폭. 첫날 1.00 → 마지막 날 0.70. */
    static final BigDecimal HORIZON_DECAY = new BigDecimal("0.30");

    private ConfidencePolicy() {
    }

    /**
     * 하루치 신뢰도. {@code DAILY_BALANCE_FORECAST.confidence} 에 그대로 들어간다.
     *
     * @param dayIndex      1부터 센 예측일 순번
     * @param horizonDays   예측 기간(저장은 항상 30)
     * @param plannedFactor 그날 예정 거래의 금액 가중 신뢰도. 항목이 없으면 1
     * @param historyDays   잔여 집계에 쓴 과거 일수. {@code NONE} 이면 무시된다
     */
    public static BigDecimal daily(int dayIndex, int horizonDays, BigDecimal plannedFactor,
                                   ResidualSource source, int historyDays) {
        BigDecimal value = residualFactor(source, historyDays)
                .multiply(clamp01(plannedFactor))
                .multiply(horizonFactor(dayIndex, horizonDays));
        return clamp01(value).setScale(4, RoundingMode.HALF_UP);
    }

    /**
     * 실행·조회 단위 등급 (API 2.5).
     *
     * @param dailyValues  <b>조회한 창</b>의 일별 신뢰도. 30일 전체가 아니다
     * @param plannedCount 창 안의 예정 거래 수
     * @param balanceKnown 모든 연결 계좌의 잔액을 알고 있는가. 하나라도 모르면 한 단계 내린다
     */
    public static ConfidenceBand level(List<BigDecimal> dailyValues, ResidualSource source,
                                       int plannedCount, boolean balanceKnown) {
        // 예정 거래도 없고 잔여도 없으면 곡선이 수평선이다. 「값이 없다」가 아니라
        // 「예측할 근거가 없다」이고, 2.5 가 그 상태를 위해 둔 등급이 UNPREDICTABLE 이다.
        if (plannedCount == 0 && source == ResidualSource.NONE) {
            return ConfidenceBand.UNPREDICTABLE;
        }
        if (dailyValues.isEmpty()) {
            return ConfidenceBand.UNPREDICTABLE;
        }

        BigDecimal sum = BigDecimal.ZERO;
        for (BigDecimal v : dailyValues) {
            sum = sum.add(v);
        }
        BigDecimal mean = sum.divide(BigDecimal.valueOf(dailyValues.size()), MathContext.DECIMAL64);

        ConfidenceBand band = ConfidenceBand.of(mean);
        return balanceKnown ? band : degrade(band);
    }

    /** {@code HIGH → MEDIUM → LOW → UNPREDICTABLE}. 별도 상태를 만들지 않는다(화면구성도 5.11). */
    public static ConfidenceBand degrade(ConfidenceBand band) {
        return switch (band) {
            case HIGH -> ConfidenceBand.MEDIUM;
            case MEDIUM -> ConfidenceBand.LOW;
            case LOW, UNPREDICTABLE -> ConfidenceBand.UNPREDICTABLE;
        };
    }

    static BigDecimal residualFactor(ResidualSource source, int historyDays) {
        BigDecimal base = switch (source) {
            case MODEL -> RESIDUAL_MODEL;
            case BASELINE -> RESIDUAL_BASELINE;
            case NONE -> RESIDUAL_NONE;
        };
        if (source != ResidualSource.NONE && historyDays < DEGRADED_HISTORY_DAYS) {
            return base.multiply(DEGRADED_FACTOR);
        }
        return base;
    }

    /** {@code 1 − 0.30 × (t−1)/(H−1)}. 하루짜리 예측은 감쇠가 없다. */
    static BigDecimal horizonFactor(int dayIndex, int horizonDays) {
        if (horizonDays <= 1) {
            return BigDecimal.ONE;
        }
        BigDecimal progress = BigDecimal.valueOf(dayIndex - 1L)
                .divide(BigDecimal.valueOf(horizonDays - 1L), MathContext.DECIMAL64);
        return BigDecimal.ONE.subtract(HORIZON_DECAY.multiply(progress));
    }

    private static BigDecimal clamp01(BigDecimal value) {
        if (value == null || value.signum() < 0) {
            return BigDecimal.ZERO;
        }
        return value.compareTo(BigDecimal.ONE) > 0 ? BigDecimal.ONE : value;
    }
}
