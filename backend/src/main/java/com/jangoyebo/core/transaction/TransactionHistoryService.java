package com.jangoyebo.core.transaction;

import com.jangoyebo.common.domain.FlowType;
import com.jangoyebo.core.TransactionHistory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * {@link TransactionHistory} 구현. 엔티티를 로드하지 않고 필요한 열만 읽는다 —
 * 탐지는 수백~수천 행을 한 번에 훑으므로 영속성 컨텍스트에 쌓을 이유가 없다.
 *
 * <h2>어느 계좌의 거래를 읽는가</h2>
 *
 * <p>{@code CONNECTED} 계좌만 읽는다. {@code AccountBalancesService} 가 시작 잔액을 고르는
 * 기준과 <b>같아야 하기 때문이다</b> — 갈리면 시작 잔액에는 없는 계좌의 반복 지출이 곡선을
 * 계속 깎아 <b>없는 정산 공백</b>이 생긴다. 사용자는 그것을 진짜 부족으로 읽는다.
 *
 * <p>명세도 같은 쪽이다: 인증 완료 전·해제된 계좌는 동기화·예측 대상에서 제외된다(API 7.2).
 * 표시 기준은 이것보다 넓다 — 거래 목록은 <b>해제</b>만 뺀다({@code TransactionRepository#search}).
 * 화면에 보이는 것과 곡선이 세는 것이 다른 것은 의도다. 인증 전 계좌에 CSV 로 올린 거래는
 * 사용자 자료이므로 목록에는 남지만, 그 계좌 잔액을 모르는 채로 곡선에 넣을 수는 없다.
 */
@Service
public class TransactionHistoryService implements TransactionHistory {

    private static final String SQL = """
            SELECT t.id, t.account_id, t.category_id, t.transaction_date,
                   t.flow_type, t.amount, t.description
              FROM core.transaction t
              JOIN core.account a ON a.id = t.account_id
             WHERE t.business_id = ?
               AND t.transaction_date BETWEEN ? AND ?
               AND t.is_excluded = FALSE
               AND a.status = 'CONNECTED'
             ORDER BY t.transaction_date, t.id
            """;

    /**
     * 자동 반영 원장이 적어 둔 금융망 번호로 우리 거래를 찾는다.
     *
     * <p>{@code uk_tx_external(account_id, external_transaction_id)} 부분 인덱스를 그대로 탄다.
     */
    private static final String BY_EXTERNAL_ID_SQL = """
            SELECT external_transaction_id, id
              FROM core.transaction
             WHERE account_id = ?
               AND external_transaction_id = ANY (?)
            """;

    /**
     * 패턴이 근거로 삼은 거래의 지금 분류 (백필용).
     *
     * <p>기본키 배열 조회라 인덱스를 그대로 탄다. 사업장을 술어에 두는 것은 값이 싸고,
     * 남의 분류가 우리 패턴에 박히는 일은 조용히 틀리기 때문이다.
     */
    private static final String CATEGORY_BY_ID_SQL = """
            SELECT id, category_id
              FROM core.transaction
             WHERE business_id = ?
               AND id = ANY (?)
               AND category_id IS NOT NULL
            """;

    private final JdbcTemplate jdbc;

    public TransactionHistoryService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    @Transactional(readOnly = true)
    public List<HistoricalTransaction> findForPatternDetection(Long businessId, LocalDate from, LocalDate to) {
        return jdbc.query(SQL, (rs, rowNum) -> new HistoricalTransaction(
                rs.getLong("id"),
                rs.getLong("account_id"),
                rs.getObject("category_id", Long.class),
                rs.getObject("transaction_date", LocalDate.class),
                FlowType.valueOf(rs.getString("flow_type")),
                rs.getBigDecimal("amount"),
                rs.getString("description")), businessId, from, to);
    }

    @Override
    @Transactional(readOnly = true)
    public Map<Long, Long> categoryIdsOf(Long businessId, Collection<Long> transactionIds) {
        if (transactionIds == null || transactionIds.isEmpty()) {
            return Map.of();
        }
        Long[] ids = transactionIds.toArray(Long[]::new);
        Map<Long, Long> categoryIdByTransactionId = new HashMap<>();
        jdbc.query(CATEGORY_BY_ID_SQL,
                rs -> {
                    categoryIdByTransactionId.put(rs.getLong(1), rs.getLong(2));
                }, businessId, ids);
        return categoryIdByTransactionId;
    }

    @Override
    @Transactional(readOnly = true)
    public Map<String, Long> findIdsByExternalTransactionIds(Long accountId, Collection<String> externalIds) {
        if (externalIds == null || externalIds.isEmpty()) {
            return Map.of();
        }
        // 배열 바인딩이라 목록이 길어져도 쿼리 문자열이 변하지 않는다 — 플랜 캐시가 유지된다.
        String[] ids = externalIds.toArray(String[]::new);
        Map<String, Long> byExternalId = new HashMap<>();
        for (String[] pair : jdbc.query(BY_EXTERNAL_ID_SQL,
                (rs, rowNum) -> new String[] {rs.getString(1), rs.getString(2)}, accountId, ids)) {
            byExternalId.put(pair[0], Long.valueOf(pair[1]));
        }
        return byExternalId;
    }
}
