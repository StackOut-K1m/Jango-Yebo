package com.jangoyebo.core;

/**
 * 계좌를 지우기 전에 자기 모듈의 행을 걷어내는 쪽 — {@code core} 가 밖에 내놓는 포트 (API 7.2 purge).
 *
 * <p><b>방향이 거꾸로인 두 번째 포트다</b> — 먼저 온 것이 {@link SafetyLineOutlook} 이다.
 * {@link CashGapAlerts}·{@link AccountBalances} 는 {@code core} 가 구현하고 {@code forecast} 가
 * 부르는데, 이 둘은 {@code forecast} 가 구현하고 {@code core} 가 부른다. 그래도 컴파일 의존은
 * 여전히 {@code forecast → core} 다 — {@code core} 는 구현 클래스를 모르고 이 인터페이스만
 * 주입받으므로 {@code ModularityTests} 를 통과한다.
 *
 * <p><b>왜 이벤트가 아닌가.</b> {@link TransactionsChanged} 처럼 알리고 끝낼 수가 없다.
 * {@code forecast} 의 행이 {@code core.account}·{@code core.transaction} 을 외래키로 붙잡고
 * 있어 <b>그쪽이 먼저 비워지지 않으면 계좌 삭제가 제약 위반으로 죽는다.</b>
 * {@code @TransactionalEventListener(AFTER_COMMIT)} 는 이미 늦고, 커밋 전으로 옮기면 이번엔
 * 순서를 보장할 수 없다. 같은 트랜잭션 안에서 순서대로 불러야 하는 일이다.
 *
 * <p>참여자가 여럿이면 순서는 정하지 않는다. 서로 외래키로 엮이지 않는 모듈끼리만 이 포트를
 * 구현한다는 뜻이고, 엮이는 순간 그 둘은 한 참여자로 합쳐야 한다.
 */
public interface AccountPurgeParticipant {

    /**
     * 이 계좌에 매달린 자기 모듈의 행을 지운다.
     *
     * <p>{@code core} 가 거래와 계좌를 지우기 <b>직전에</b>, 같은 트랜잭션 안에서 부른다.
     * 여기서 던지면 삭제 전체가 되감긴다 — 반쯤 지워진 계좌를 남기지 않기 위해서다.
     *
     * @param businessId 계좌가 속한 사업장. 참여자가 사업장 범위로 좁혀 지울 때 쓴다
     * @param accountId  지울 계좌
     */
    void purgeAccount(Long businessId, Long accountId);
}
