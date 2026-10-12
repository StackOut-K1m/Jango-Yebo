package com.jangoyebo.core;

/**
 * 사업장의 거래가 늘거나 바뀌었다 — {@code core} 가 밖에 알리는 사실.
 *
 * <p><b>왜 {@code core} 기저 패키지에 있는가.</b> {@link TransactionHistory}·
 * {@link CategoryCatalog} 와 같은 이유다. {@code forecast} 가 이 타입을 참조하지만
 * 의존 방향은 여전히 {@code forecast → core} 라 Modulith 경계를 깨지 않는다.
 * {@code core} 는 누가 듣는지 모른다.
 *
 * <p><b>Modulith 이벤트 레지스트리를 쓰지 않는다.</b> {@code spring-modulith-starter-jpa} 가
 * 의도적으로 빠져 있고({@code event_publication} 테이블이 없어 {@code ddl-auto=validate} 가
 * 부팅을 막는다) {@code @ApplicationModuleListener} 는 클래스패스에 없다. 순수
 * {@code ApplicationEventPublisher} 로 충분한 이유는 <b>이 이벤트가 유실돼도 손실이 없기</b>
 * 때문이다 — 듣는 쪽이 하는 일은 재계산 표시 하나이고, 표시가 없으면 매일 04:00 배치가
 * 같은 사업장을 다시 계산한다. 발행을 영속화할 값어치가 없다.
 *
 * <p><b>커밋되는 트랜잭션 안에서 발행한다.</b> 듣는 쪽이
 * {@code @TransactionalEventListener(AFTER_COMMIT)} 라, 트랜잭션 밖에서 발행하면
 * 이벤트가 조용히 버려진다.
 *
 * @param accountId 계좌 하나에서 비롯된 변경이면 그 계좌. CSV 도 계좌 단위다
 * @param source    어디서 왔는지. 로그와 진단에만 쓴다 — 듣는 쪽의 동작은 같다
 */
public record TransactionsChanged(Long businessId, Long accountId, Source source) {

    public enum Source {
        /** 금융망 거래 동기화가 전 구간 성공했다. */
        SYNC,
        /** CSV 임포트가 끝났다. */
        CSV,
        /**
         * 계좌 연동을 해제했다.
         *
         * <p><b>거래 행은 하나도 안 바뀌었다.</b> 바뀐 것은 예측이 <b>세는 집합</b>이다 —
         * 시작 잔액과 패턴 탐지가 모두 {@code CONNECTED} 계좌만 보므로 해제 순간
         * 저장된 곡선이 낡는다. 알리지 않으면 그 곡선이 <b>해제한 계좌의 잔액을 포함한
         * 총잔액</b>을 04:00 배치까지 계속 보여준다.
         */
        ACCOUNT_DISCONNECTED,
        /**
         * 계좌를 지웠다 (API 7.2 purge).
         *
         * <p>{@link #ACCOUNT_DISCONNECTED} 와 달리 <b>거래 행이 실제로 사라졌다.</b> 해제는
         * 세는 집합만 바꿨지만 이쪽은 세어야 할 것 자체가 없어졌다 — 반복 패턴의 근거 거래,
         * 잔여 시계열의 표본, 예정 거래까지 함께 지워진다. 곡선을 다시 그리지 않으면
         * 저장된 값이 <b>이제 DB 에 없는 거래로 만든 곡선</b>을 04:00 배치까지 내려준다.
         *
         * <p>{@code accountId} 는 남겨 두지만 그 계좌 행은 이미 없다. 듣는 쪽은 사업장 단위로
         * 다시 계산하므로 문제가 되지 않는다 — 이 값은 로그와 진단용이다.
         */
        ACCOUNT_DELETED
    }

    public static TransactionsChanged bySync(Long businessId, Long accountId) {
        return new TransactionsChanged(businessId, accountId, Source.SYNC);
    }

    public static TransactionsChanged byCsv(Long businessId, Long accountId) {
        return new TransactionsChanged(businessId, accountId, Source.CSV);
    }

    public static TransactionsChanged byDisconnect(Long businessId, Long accountId) {
        return new TransactionsChanged(businessId, accountId, Source.ACCOUNT_DISCONNECTED);
    }

    public static TransactionsChanged byAccountDeletion(Long businessId, Long accountId) {
        return new TransactionsChanged(businessId, accountId, Source.ACCOUNT_DELETED);
    }
}
