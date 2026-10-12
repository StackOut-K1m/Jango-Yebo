package com.jangoyebo.core.transaction;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.BatchPreparedStatementSetter;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Types;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

/**
 * 거래 대량 삽입. <b>여기만 JPA 를 우회한다.</b>
 *
 * <p>{@code TransactionRepository} 의 커스텀 fragment 로 넣지 않았다. 한 리포지토리에
 * "영속성 컨텍스트를 타는 쓰기"와 "그걸 우회하는 쓰기"가 섞이면, 같은 트랜잭션에서 둘을 부를 때
 * <b>플러시 순서가 호출 순서와 달라져</b> 조용히 어긋난다. 우회한다는 사실이 호출부에서
 * 보여야 해서 타입을 갈라 뒀다.
 *
 * <p><b>왜 필요한가</b> — {@code Transaction} PK 가 {@code GenerationType.IDENTITY} 라
 * Hibernate 가 삽입마다 생성 키를 받아야 하고, 그래서 JDBC 배치를 스스로 끈다
 * ({@code application.yml} 의 {@code hibernate.jdbc.batch_size} 가 이 엔티티에는 무효다).
 * ERD 와 기술스택 §4.1 이 대량 삽입만 JPA 를 우회하라고 정한 근거가 이것이다.
 *
 * <p><b>{@code ON CONFLICT DO NOTHING} 이 중복 흡수와 멱등성을 함께 준다.</b> 동기화 Job 은
 * 스위퍼 회수로 재실행될 수 있어(같은 구간을 다시 받는다) 멱등이 필수인데, 사전 조회로는
 * 조회와 삽입 사이 경쟁을 막지 못한다.
 */
@Repository
public class TransactionBulkWriter {

    private static final Logger log = LoggerFactory.getLogger(TransactionBulkWriter.class);

    /** 한 번에 보내는 행 수. 월 단위 구간이라 보통 이 아래지만 패킷 크기를 묶어 둔다. */
    private static final int CHUNK = 500;

    /**
     * ⚠ <b>{@code WHERE ... IS NOT NULL} 을 빼면 런타임에 죽는다.</b> {@code uk_tx_external} 이
     * <b>부분</b> 유일 인덱스라, PostgreSQL 이 arbiter index 를 추론하려면 인덱스 술어를 함께
     * 줘야 한다. 없으면 {@code there is no unique or exclusion constraint matching the
     * ON CONFLICT specification} 이다 — 컴파일러도 JPA 도 못 잡고 <b>실제 Postgres 에서만</b> 난다.
     *
     * <p>목록에서 뺀 컬럼({@code classification_status}·{@code needs_confirmation}·
     * {@code is_excluded}·{@code tax_review_status}·{@code created_at}·{@code updated_at})은
     * DDL 기본값이 채운다. 특히 {@code tax_review_status} 는 <b>엔티티가 매핑조차 하지 않는
     * {@code NOT NULL} 컬럼</b>이라 JDBC 경로에서 놓치기 쉽다 — 기본값이 있어 안전하고, 테스트로
     * 못 박아 뒀다.
     */
    private static final String FIN_SQL = """
            INSERT INTO core.transaction
                   (business_id, account_id, source_type, external_transaction_id,
                    occurred_at, transaction_date, flow_type, amount,
                    balance_after, description, counterparty_account_no)
            VALUES (?, ?, 'FIN_API', ?, ?, ?, ?, ?, ?, ?, ?)
            ON CONFLICT (account_id, external_transaction_id)
                 WHERE external_transaction_id IS NOT NULL
                    DO NOTHING
            """;

    /** 충돌 대상이 {@code uk_tx_dedup} 이라 금융망 문장과 합칠 수 없다. 술어 주의는 위와 같다. */
    private static final String CSV_SQL = """
            INSERT INTO core.transaction
                   (business_id, account_id, source_type, deduplication_key,
                    occurred_at, transaction_date, flow_type, amount,
                    balance_after, description)
            VALUES (?, ?, 'CSV', ?, ?, ?, ?, ?, ?, ?)
            ON CONFLICT (account_id, deduplication_key)
                 WHERE deduplication_key IS NOT NULL
                    DO NOTHING
            """;

    private final JdbcTemplate jdbc;

    public TransactionBulkWriter(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * @return 실제로 삽입된 행 수. 중복이라 건너뛴 수는 {@code rows.size() - 반환값} 이다
     */
    public int insertFinRows(List<FinTransactionRow> rows) {
        return insert(FIN_SQL, rows, (ps, row) -> {
            ps.setLong(1, row.businessId());
            ps.setLong(2, row.accountId());
            ps.setString(3, row.externalTransactionId());
            setInstant(ps, 4, row.occurredAt());
            ps.setObject(5, row.transactionDate());
            ps.setString(6, row.flowType().name());
            ps.setBigDecimal(7, row.amount());
            setNullable(ps, 8, row.balanceAfter());
            ps.setString(9, row.description());
            ps.setString(10, row.counterpartyAccountNo());
        });
    }

    /** @return 실제로 삽입된 행 수 */
    public int insertCsvRows(List<CsvTransactionRow> rows) {
        return insert(CSV_SQL, rows, (ps, row) -> {
            ps.setLong(1, row.businessId());
            ps.setLong(2, row.accountId());
            ps.setString(3, row.deduplicationKey());
            setInstant(ps, 4, row.occurredAt());
            ps.setObject(5, row.transactionDate());
            ps.setString(6, row.flowType().name());
            ps.setBigDecimal(7, row.amount());
            setNullable(ps, 8, row.balanceAfter());
            ps.setString(9, row.description());
        });
    }

    private <T> int insert(String sql, List<T> rows, Binder<T> binder) {
        if (rows == null || rows.isEmpty()) {
            return 0;
        }
        int inserted = 0;
        for (int start = 0; start < rows.size(); start += CHUNK) {
            List<T> chunk = rows.subList(start, Math.min(start + CHUNK, rows.size()));
            inserted += countInserted(jdbc.batchUpdate(sql, new BatchPreparedStatementSetter() {
                @Override
                public void setValues(PreparedStatement ps, int i) throws SQLException {
                    binder.bind(ps, chunk.get(i));
                }

                @Override
                public int getBatchSize() {
                    return chunk.size();
                }
            }));
        }
        log.debug("거래 {}행 중 {}행 삽입", rows.size(), inserted);
        return inserted;
    }

    /**
     * {@code DO NOTHING} 으로 건너뛴 행은 영향 행 수 0, 삽입된 행은 1 이라 그대로 더하면 된다.
     *
     * <p>⚠ JDBC URL 에 {@code reWriteBatchedInserts=true} 가 켜지면 pgjdbc 가 배치를 multi-VALUES
     * 한 문장으로 재작성하고 전부 {@code SUCCESS_NO_INFO} 를 돌려준다. 그러면 <b>숫자만 조용히
     * 틀려진다.</b> 무증상 오류를 유증상으로 바꾸려고 여기서 터뜨린다.
     */
    private static int countInserted(int[] affected) {
        int inserted = 0;
        for (int rows : affected) {
            if (rows == Statement.SUCCESS_NO_INFO) {
                throw new IllegalStateException(
                        "배치가 건별 결과를 주지 않아 삽입 건수를 셀 수 없다 — "
                                + "JDBC URL 의 reWriteBatchedInserts 를 확인한다");
            }
            inserted += rows;
        }
        return inserted;
    }

    /**
     * {@code Instant} 를 직접 바인딩하지 않는다. {@code hibernate.jdbc.time_zone} 은 Hibernate
     * 전용이라 raw JDBC 에 걸리지 않으므로, UTC 오프셋을 명시해 서버 시간대와 무관하게 만든다.
     */
    private static void setInstant(PreparedStatement ps, int index, Instant value) throws SQLException {
        ps.setObject(index, value.atOffset(ZoneOffset.UTC));
    }

    private static void setNullable(PreparedStatement ps, int index, BigDecimal value) throws SQLException {
        if (value == null) {
            ps.setNull(index, Types.NUMERIC);
        } else {
            ps.setBigDecimal(index, value);
        }
    }

    /** {@code BatchPreparedStatementSetter} 를 두 번 쓰지 않으려는 최소 추상화. */
    @FunctionalInterface
    private interface Binder<T> {
        void bind(PreparedStatement ps, T row) throws SQLException;
    }

}
