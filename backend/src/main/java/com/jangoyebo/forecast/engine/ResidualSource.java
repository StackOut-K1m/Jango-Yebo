package com.jangoyebo.forecast.engine;

/**
 * 잔여 현금흐름을 무엇이 만들었나 (API 10.1 {@code basis.residual.source}).
 *
 * <p>세 값 모두 <b>정상 상태</b>다. 오류가 아니다 — 「모델이 실패해도 곡선은 반드시
 * 산출된다」가 인수 조건이고(SRS 13장), 폴백 사슬이 그것을 지킨다.
 *
 * <pre>
 * MODEL ──실패·타임아웃·단조성 위반·미상주──→ BASELINE ──표본 30일 미만──→ NONE
 * </pre>
 *
 * <p>{@code NONE} 은 세 경우다 — 과거 데이터 30일 미만, 워커·통계 양쪽 실패,
 * 사용자가 토글을 껐을 때(10.7). 사유는 응답 {@code warnings} 에 담는다.
 */
public enum ResidualSource {

    /** 잔여 예측 모델({@code residual-gru}). */
    MODEL,

    /** 통계 베이스라인(요일 중앙값). 모델이 없거나 실패했을 때. */
    BASELINE,

    /**
     * 잔여를 반영하지 않았다. 곡선은 예정 거래만으로 낸다.
     *
     * <p><b>매칭이 서기 전까지 P0 의 기본값이다.</b> 잔여의 정의가
     * {@code 실제 합계 − 예정 거래 매칭분}인데 매칭을 채우는 코드가 없으면 잔여가
     * 「실제 거래 전부」가 되어 예정 거래를 두 번 센다(설계노트 M-1).
     */
    NONE
}
