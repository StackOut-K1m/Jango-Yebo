package com.jangoyebo.common.domain;

/**
 * 위험 등급 (API 2.6).
 *
 * <p>알림에는 선택 값이다 — {@code SETTLEMENT_MISMATCH} 처럼 잔액과 무관한 알림은 null 이다.
 *
 * <p><b>{@code CAUTION}·{@code DANGER} 는 안전선이 있어야 판정된다.</b> 안전선을 설정하지 않으면
 * 0원 미만({@code GAP})만 가릴 수 있다 — 안전선을 P0 로 올린 이유가 이것이다.
 *
 * <p><b>왜 {@code common} 에 있는가.</b> {@link FlowType} 과 같은 이유다. 원래
 * {@code core.alert} 에 있었으나 API 2.6 이 "대시보드·캘린더·알림·예측 곡선 전반에서 공통으로
 * 사용한다"고 정한 어휘이고, 곡선의 등급 판정은 {@code forecast} 모듈이 한다. {@code core} 는
 * CLOSED 라 {@code core.alert.RiskLevel} 을 {@code forecast} 가 참조할 수 없다.
 *
 * <p>{@code forecast} 에 같은 enum 을 하나 더 두는 선택지는 버렸다 — 두 벌이 갈라지면
 * <b>같은 잔액이 화면마다 다른 등급으로 읽힌다.</b> 판정 규칙 자체는 여기 두지 않고
 * {@code forecast.engine.RiskLevelRule} 이 갖는다. 이 파일은 값 집합일 뿐이다.
 */
public enum RiskLevel {
    SAFE,
    CAUTION,
    DANGER,
    GAP
}
