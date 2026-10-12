package com.jangoyebo.forecast.run;

/**
 * 곡선의 입력이 바뀌었다 — {@code forecast} 모듈 <b>내부</b> 이벤트.
 *
 * <p>반복 패턴 승인·수정, 예정 거래 등록·수정·삭제가 여기 해당한다. 같은 모듈이라
 * {@link ForecastRunWorker} 를 직접 부를 수도 있지만 그러면 <b>커밋 전에 재계산이 예약된다</b> —
 * 5초 디바운스가 대개 가려 주지만 보증은 아니다. 아직 커밋되지 않은 예정 거래를 읽어
 * 곡선을 만들면 그 곡선은 존재하지 않는 값이 된다.
 *
 * <p>그래서 밖에서 오는 {@link com.jangoyebo.core.TransactionsChanged} 와 같은 방식으로
 * 통일한다 — {@code AFTER_COMMIT} 에 한 곳에서 받는다.
 *
 * @param reason 무엇이 바뀌었는지. 로그·진단용이며 듣는 쪽의 동작은 같다
 */
public record ForecastInputsChanged(Long businessId, Reason reason) {

    public enum Reason {
        /** 반복 패턴을 승인해 예정 거래가 생겼다. */
        PATTERN_CONFIRMED,
        /** 패턴을 일시중지해 미래 예정 거래가 취소됐다. */
        PATTERN_PAUSED,
        /** 사용자가 예정 거래를 직접 등록·수정·삭제했다. */
        PLANNED_CHANGED,
        /**
         * 예측 옵션이 바뀌었다 (API 10.7).
         *
         * <p>원장은 그대로이고 <b>잔여를 태울지가 바뀐 것</b>이라 다른 넷과 성격이 다르다.
         * 듣는 쪽 동작은 같지만, 로그에서 「사용자가 예정 거래를 고쳤다」로 보이면 진단이
         * 엉뚱한 곳을 판다.
         */
        SETTINGS_CHANGED,

        /**
         * 대출이 늘거나 바뀌어 상환 예정 거래를 다시 만들었다 (P1-13).
         *
         * <p>대출만 바뀌는 경로가 따로 있다 — 금융망 동기화가 거래는 하나도 안 바꾸고
         * 대출만 갱신할 수 있다. 그때 이 신호가 없으면 <b>예정 거래는 새로 만들어졌는데
         * 곡선은 옛 것</b>으로 남는다.
         */
        LOAN_CHANGED
    }

    public static ForecastInputsChanged of(Long businessId, Reason reason) {
        return new ForecastInputsChanged(businessId, reason);
    }
}
