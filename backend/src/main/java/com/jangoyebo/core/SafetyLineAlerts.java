package com.jangoyebo.core;

import com.jangoyebo.common.domain.RiskLevel;

import java.util.Map;
import java.util.Set;

/**
 * 안전선 하회 알림 (P1-08 · API 10.3 {@code SAFETY_LINE}) — {@code core} 가 밖에 내놓는 공개 API.
 *
 * <p>{@link CashGapAlerts} 와 같은 이유로 여기 있다. 하회를 아는 것은 {@code forecast} 이고
 * 알림을 소유한 것은 {@code core} 라 이 방향의 통로가 필요하다.
 *
 * <p><b>{@code CashGapAlerts} 와 나눈 이유.</b> 한 포트에 유형을 인자로 받게 하면 {@code forecast}
 * 가 {@code AlertType} 전체를 보게 된다 — 증빙 누락이나 대사 불일치까지 올릴 수 있는 문이
 * 열린다. 좁은 통로 둘이 소유권을 분명하게 한다.
 *
 * <p><b>{@code cashGapId} 같은 식별자를 내놓지 않는다.</b> 공백과 달리 하회 구간은 API 응답에
 * 자기 목록이 없다 — API 12.1 의 {@code current} 는 구간 하나를 요약할 뿐이라 알림 id 를
 * 되짚을 자리가 없다.
 */
public interface SafetyLineAlerts {

    /**
     * 하회 알림을 올리거나 갱신한다.
     *
     * @param riskLevel 구간 최저점의 등급. {@code CAUTION} 또는 {@code DANGER} 다 —
     *                  {@code GAP} 이 되는 구간은 {@link CashGapAlerts} 가 가져간다
     * @param dedupKey  같은 하회 판정 키. {@code safety-line:{최초하회일}}
     */
    Long raise(Long businessId, RiskLevel riskLevel, String dedupKey, String title, String message,
               Map<String, Object> relatedRef, Map<String, Object> detail);

    /**
     * 이번 관측에 없는 하회 알림을 내린다.
     *
     * <p><b>안전선을 올려 하회가 사라졌는데 알림이 그대로면 해결한 것이 계속 경고로 남는다.</b>
     * 안전선을 <b>내렸을 때</b>가 특히 중요하다 — 곡선은 그대로인데 판정선만 움직여 구간이
     * 통째로 사라지는 경우다.
     *
     * @param keepDedupKeys 이번에 여전히 있는 하회의 키. 비어 있으면 전부 내린다
     */
    void resolveExcept(Long businessId, Set<String> keepDedupKeys);
}
