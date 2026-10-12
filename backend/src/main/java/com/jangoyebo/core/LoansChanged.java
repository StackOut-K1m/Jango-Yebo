package com.jangoyebo.core;

/**
 * 사업장의 대출이 늘거나 바뀌었다 — {@code core} 가 밖에 알리는 사실 (P1-13).
 *
 * <p>{@link TransactionsChanged} 와 <b>같은 자리에 같은 이유로</b> 있다. {@code forecast} 가
 * 이 타입을 참조하지만 의존 방향은 여전히 {@code forecast → core} 라 Modulith 경계를 깨지
 * 않는다 — {@code core} 는 누가 듣는지 모른다.
 *
 * <p>듣는 쪽이 하는 일은 <b>그 사업장 대출의 예정 거래를 다시 만드는 것</b>이다. 상환이
 * 매일이라 대출 하나가 지평 일수만큼 행을 만들고, 조건이 바뀌면 남은 회차가 통째로 달라진다.
 *
 * <p>유실돼도 손실이 없는 것도 같다 — 다음 동기화가 같은 일을 하고, 그때까지는 이전 회차가
 * 남아 있다. 금액이 바뀐 경우에만 하루치가 낡는데, 상환액은 계약이라 바뀌는 일이 드물다.
 *
 * <p><b>커밋되는 트랜잭션 안에서 발행한다.</b> 듣는 쪽이
 * {@code @TransactionalEventListener(AFTER_COMMIT)} 라, 트랜잭션 밖에서 발행하면
 * 이벤트가 조용히 버려진다.
 */
public record LoansChanged(Long businessId) {
}
