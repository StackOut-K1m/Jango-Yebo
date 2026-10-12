package com.jangoyebo.core;

/**
 * 거래 분류가 실제로 바뀌었다 — {@code core} 가 밖에 알리는 사실 (API 8.3·8.4·20.3).
 *
 * <p>{@link TransactionsChanged}·{@link SafetyLineChanged} 와 같은 자리에 같은 이유로 있다.
 * {@code forecast} 가 이 타입을 참조하지만 의존 방향은 여전히 {@code forecast → core} 다.
 *
 * <h2>왜 {@link TransactionsChanged} 를 재사용하지 않는가</h2>
 *
 * <p>그쪽을 들으면 {@code TransactionClassificationTriggerListener} 가 분류 Job 을 또 넣는다 —
 * 분류가 분류를 부르는 고리가 생긴다. 미분류가 없으면 한 바퀴에 멈추긴 하지만, 분류할 것이
 * 없는 Job 이 매번 한 건 뜨는 것을 전제로 삼을 이유가 없다.
 *
 * <h2>듣는 쪽이 하는 일</h2>
 *
 * <p>반복 패턴의 <b>빈 분류를 채운다.</b> 패턴은 탐지 시점에 표본 거래의 분류를 복사하는데,
 * 분류가 그보다 늦게 돌면 그 값이 빈 채로 굳는다 — 재탐지는 후보만 갱신하고, 승인·수정
 * 요청에 분류를 실어 보내는 화면이 없기 때문이다. 그 결과 시나리오의 매출 변화
 * ({@code SALES_CHANGE}·{@code PRODUCT_SALES_CHANGE})가 대상을 한 건도 찾지 못한다(11.1).
 *
 * <p><b>곡선 재계산 트리거가 아니다.</b> 분류 자체는 금액도 날짜도 바꾸지 않는다. 백필이
 * 예정 거래를 다시 만든 경우에만 듣는 쪽이 {@code ForecastInputsChanged} 를 따로 발행한다.
 *
 * <p><b>커밋되는 트랜잭션 안에서 발행한다.</b> 듣는 쪽이 {@code AFTER_COMMIT} 이라 트랜잭션
 * 밖에서 발행하면 이벤트가 조용히 버려진다.
 */
public record TransactionsClassified(Long businessId) {
}
