package com.jangoyebo.forecast.run;

/**
 * 예측 실행 상태. DB {@code ck_fr_status} 와 값이 일치해야 한다.
 *
 * <p>⚠ <b>구현은 {@link #COMPLETED}·{@link #FAILED} 만 쓴다.</b> 실행 행을 끝난 시점에
 * 한 번 넣기 때문이다.
 *
 * <p>시작 시점에 {@code QUEUED} 행을 넣으려면 {@code model_version}·{@code opening_balances}
 * ·{@code basis} 가 전부 NOT NULL 이라 <b>의미 없는 자리표시자</b>를 채워야 하고, 중간에 죽은
 * {@code PROCESSING} 행을 회수할 스위퍼가 또 필요해진다({@code JobSweeper} 가 Job 에서 겪은
 * 문제를 forecast 에 한 벌 더 만드는 셈이다). 실행은 15초 안에 끝나고 실패해도 다시 돌면
 * 되므로 진행 중 상태를 DB 에 남길 이유가 없다.
 */
public enum ForecastRunStatus {
    QUEUED,
    PROCESSING,
    COMPLETED,
    FAILED
}
