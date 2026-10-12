package com.jangoyebo.core;

/**
 * 사업장의 운영자금 안전선이 바뀌었다 — {@code core} 가 밖에 알리는 사실 (API 12.1).
 *
 * <p>{@link TransactionsChanged} 와 같은 자리에 같은 이유로 있다. {@code forecast} 가 이 타입을
 * 참조하지만 의존 방향은 여전히 {@code forecast → core} 다.
 *
 * <h2>이것은 재계산 트리거가 아니다</h2>
 *
 * <p>공통규약 2.6 이 「안전선 변경」을 실행 트리거에서 <b>뺐다</b> — 곡선은 안전선에 의존하지
 * 않고(위험 등급은 조회 시점 파생값이라) 다시 돌릴 이유가 없다. 그 판단은 그대로다.
 *
 * <p><b>그런데 알림은 저장된다.</b> 등급과 달리 {@code SAFETY_LINE} 알림은 {@code ALERT} 행이라
 * 누가 고쳐 주지 않으면 옛 기준으로 남는다 — 안전선을 300만에서 500만으로 올려도 다음
 * 04:00 배치까지 「50만 원 모자랄 가능성」이 그대로 떠 있게 된다. 듣는 쪽이 하는 일은
 * <b>저장된 곡선으로 알림만 다시 맞추는 것</b>이고, 곡선은 건드리지 않는다.
 *
 * <p><b>커밋되는 트랜잭션 안에서 발행한다.</b> 듣는 쪽이 {@code AFTER_COMMIT} 이라 트랜잭션
 * 밖에서 발행하면 이벤트가 조용히 버려진다.
 */
public record SafetyLineChanged(Long businessId) {
}
