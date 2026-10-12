package com.jangoyebo.core;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/**
 * 예측의 시작 잔액 — {@code core} 모듈이 밖에 내놓는 세 번째 공개 API.
 *
 * <p>{@link TransactionHistory}·{@link CategoryCatalog} 와 같은 이유로 여기 있다.
 * {@code core} 는 CLOSED 라 {@code core.account.AccountRepository} 를 {@code forecast} 가
 * 참조할 수 없다({@code ModularityTests} 가 "non-exposed type" 으로 잡는다).
 *
 * <h2>왜 계좌별로 주는가</h2>
 *
 * <p>합계 하나만 주면 {@code FORECAST_RUN.opening_balances} 를 채울 수 없다. 그 JSONB 는
 * {@code [{accountId, balance, asOf}]} 형태로 <b>어느 계좌의 언제 잔액을 썼는지</b>를 남기는
 * 감사 기록이다 — 나중에 "그 곡선이 왜 그 값에서 출발했나"를 설명하는 유일한 근거다.
 */
public interface AccountBalances {

    /**
     * 예측 대상 계좌의 현재 잔액.
     *
     * <p><b>{@code CONNECTED} 계좌만 준다.</b> 인증 완료 전 계좌는 동기화·예측 대상이 아니고
     * (API 7.2), 연동을 해제한 계좌는 거래 이력은 남아 있지만 잔액이 더 이상 갱신되지 않는다.
     *
     * <p>잔액이 {@code null} 인 계좌가 있을 수 있다 — 재연결 직후에는 잔액을 지우고
     * 동기화가 다시 채우기 때문이다. 그 경우 마지막 거래의 잔액으로 대신하고
     * {@link BalanceSource} 에 표시하며, 그것도 없으면 {@code balance} 가 {@code null} 이다.
     */
    List<AccountBalance> connectedBalances(Long businessId);

    /**
     * 연결된 계좌가 하나라도 있는 사업장 id.
     *
     * <p>매일 새벽 배치가 도는 대상이다. 계좌가 없는 사업장은 곡선을 만들 수 없다.
     */
    List<Long> businessIdsWithConnectedAccounts();

    /**
     * @param balance {@code null} 이면 이 계좌의 잔액을 <b>알 수 없다</b>는 뜻이다. 합계에서 빼고
     *                신뢰도를 한 단계 내린다 — 0으로 치면 곡선 전체가 조용히 낮아진다
     * @param asOf    잔액 기준 시각
     */
    record AccountBalance(Long accountId, BigDecimal balance, Instant asOf, BalanceSource source) {

        public boolean isKnown() {
            return balance != null;
        }
    }

    /** 잔액을 어디서 얻었나. {@code FORECAST_RUN.opening_balances} 에 그대로 남는다. */
    enum BalanceSource {
        /** 금융망 잔액 조회({@code DD_07})가 채운 값. */
        SYNC,
        /** 잔액 컬럼이 비어 있어 마지막 거래의 거래 후 잔액으로 대신했다. */
        LAST_TRANSACTION,
        /** 둘 다 없다. 거래가 한 건도 없는 계좌다. */
        UNKNOWN
    }
}
