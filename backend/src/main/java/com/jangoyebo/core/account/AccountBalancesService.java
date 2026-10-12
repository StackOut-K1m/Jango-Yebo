package com.jangoyebo.core.account;

import com.jangoyebo.core.AccountBalances;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/**
 * {@link AccountBalances} 구현.
 *
 * <p>{@code TransactionHistoryService} 와 같은 이유로 엔티티를 로드하지 않는다 — 필요한 것은
 * 계좌당 세 값뿐이고, 잔액 대체 조회가 거래 테이블을 함께 봐야 해서 한 번의 SQL 이 낫다.
 *
 * <h2>잔액이 비어 있는 계좌</h2>
 *
 * <p>재연결({@code relink})은 잔액을 지운다 — 시점 값이라 지금도 맞다고 말할 수 없기 때문이다.
 * 그 상태에서 곡선을 그리면 시작 잔액이 통째로 빠지므로, <b>마지막 거래의 거래 후 잔액</b>으로
 * 대신하고 출처를 표시한다. 그것도 없으면 {@code null} 로 두고 신뢰도를 내린다 —
 * <b>0 으로 치면 곡선 전체가 조용히 낮아져 없는 공백이 생긴다.</b>
 */
@Service
public class AccountBalancesService implements AccountBalances {

    /**
     * {@code balance_after} 는 금융망이 준 거래 후 잔액이라 계좌마다 최신 한 건만 의미가 있다.
     * {@code DISTINCT ON} 이 계좌별 첫 행을 고른다(PostgreSQL).
     */
    private static final String SQL = """
            SELECT a.id,
                   a.current_balance,
                   a.balance_as_of,
                   t.balance_after AS fallback_balance,
                   t.occurred_at   AS fallback_as_of
              FROM core.account a
              LEFT JOIN LATERAL (
                   SELECT balance_after, occurred_at
                     FROM core.transaction
                    WHERE account_id = a.id
                      AND balance_after IS NOT NULL
                    ORDER BY occurred_at DESC, id DESC
                    LIMIT 1
              ) t ON TRUE
             WHERE a.business_id = ?
               AND a.status = 'CONNECTED'
             ORDER BY a.id
            """;

    private static final String BUSINESS_IDS_SQL = """
            SELECT DISTINCT business_id
              FROM core.account
             WHERE status = 'CONNECTED'
             ORDER BY business_id
            """;

    private final JdbcTemplate jdbc;

    public AccountBalancesService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    @Transactional(readOnly = true)
    public List<AccountBalance> connectedBalances(Long businessId) {
        return jdbc.query(SQL, (rs, rowNum) -> {
            Long accountId = rs.getLong("id");
            BigDecimal current = rs.getBigDecimal("current_balance");
            if (current != null) {
                return new AccountBalance(accountId, current,
                        instant(rs.getTimestamp("balance_as_of")), BalanceSource.SYNC);
            }
            BigDecimal fallback = rs.getBigDecimal("fallback_balance");
            if (fallback != null) {
                return new AccountBalance(accountId, fallback,
                        instant(rs.getTimestamp("fallback_as_of")), BalanceSource.LAST_TRANSACTION);
            }
            return new AccountBalance(accountId, null, null, BalanceSource.UNKNOWN);
        }, businessId);
    }

    @Override
    @Transactional(readOnly = true)
    public List<Long> businessIdsWithConnectedAccounts() {
        return jdbc.queryForList(BUSINESS_IDS_SQL, Long.class);
    }

    private static Instant instant(java.sql.Timestamp timestamp) {
        return timestamp == null ? null : timestamp.toInstant();
    }
}
