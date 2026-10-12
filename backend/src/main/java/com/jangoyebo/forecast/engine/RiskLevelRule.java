package com.jangoyebo.forecast.engine;

import com.jangoyebo.common.domain.RiskLevel;

import java.math.BigDecimal;

/**
 * 위험 등급 판정 (API 2.6).
 *
 * <p>순수 함수다. 등급은 <b>저장하지 않는 파생값</b>이라(ERD 1.3) 곡선을 조회할 때마다
 * 그 시점의 안전선과 비교해 만든다. 그래서 안전선을 바꾸면 재계산 없이 다음 조회에 반영된다.
 *
 * <h2>왜 {@code balanceExpected} 로 판정하는가</h2>
 *
 * <p>2.6 의 네 등급이 전부 "<b>예상</b> 잔액" 기준으로 쓰여 있다. 공백 경보라는 성격상
 * 하한선({@code balanceMin})으로 판정하고 싶어지는데, 그것은 판정 보수성 <b>α=1 을 쓰는 것과
 * 같다</b>. α 는 워커가 응답에 실어 주는 값이고(현재 0.00) 우리가 임의로 올리면
 * <b>모델 채택 근거가 사라진다</b> — 게이트가 α=0.00 에서만 통과한다(API 10.1·20.11).
 */
public final class RiskLevelRule {

    /**
     * {@code DANGER} 경계. 안전선의 이 비율 미만이면 "0원 근접"으로 본다 (2.6 기본값).
     */
    static final BigDecimal DANGER_RATIO = new BigDecimal("0.25");

    private RiskLevelRule() {
    }

    /**
     * @param safetyLine 안전선. <b>{@code null} 이 정상 상태다</b> — 설정하지 않은 사업장이며
     *                   0원 기준({@code GAP})만 판정할 수 있다(API 10.1)
     */
    public static RiskLevel of(BigDecimal expectedBalance, BigDecimal safetyLine) {
        if (expectedBalance.signum() < 0) {
            return RiskLevel.GAP;
        }
        if (safetyLine == null) {
            // 0원 이상인데 비교선이 없다. CAUTION 을 임의로 붙이면 근거 없는 경보가 된다.
            return RiskLevel.SAFE;
        }
        if (expectedBalance.compareTo(safetyLine.multiply(DANGER_RATIO)) < 0) {
            return RiskLevel.DANGER;
        }
        return expectedBalance.compareTo(safetyLine) < 0 ? RiskLevel.CAUTION : RiskLevel.SAFE;
    }
}
