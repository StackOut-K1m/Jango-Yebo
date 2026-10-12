package com.jangoyebo.core.transaction;

import com.jangoyebo.common.domain.FlowType;
import com.jangoyebo.NeedsDocker;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * 거래 대량 삽입 검증.
 *
 * <p><b>Docker 를 켤 값을 하는 유일한 이유가 여기 있다.</b> `ON CONFLICT` 의 인덱스 술어 누락은
 * 컴파일러도 JPA 도 못 잡고 <b>실제 PostgreSQL 에서만</b> 드러난다. 부분 유일 인덱스라
 * {@code WHERE ... IS NOT NULL} 이 빠지면 첫 삽입부터 죽는다.
 *
 * <p>멱등성도 여기서만 증명된다 — 동기화 Job 은 스위퍼 회수로 재실행될 수 있어(같은 구간을
 * 다시 받는다) 두 번째 삽입이 <b>조용히 0건</b>이어야 한다.
 */
@NeedsDocker
@SpringBootTest
class TransactionBulkWriterTests {

    private static final Instant AT = Instant.parse("2026-09-08T02:30:00Z");
    private static final LocalDate ON = LocalDate.of(2026, 9, 8);

    @Autowired
    private TransactionBulkWriter writer;

    @Autowired
    private JdbcTemplate jdbc;

    private Long businessId;
    private Long accountId;

    @BeforeEach
    void setUp() {
        String unique = String.valueOf(System.nanoTime());
        Long userId = jdbc.queryForObject("""
                INSERT INTO core.users (email, password_hash, name)
                VALUES (?, 'x', '대량삽입') RETURNING id
                """, Long.class, "bulk-" + unique + "@test.local");
        businessId = jdbc.queryForObject("""
                INSERT INTO core.business (owner_user_id, name) VALUES (?, '대량삽입 사업장') RETURNING id
                """, Long.class, userId);
        accountId = jdbc.queryForObject("""
                INSERT INTO core.account
                    (business_id, bank_code, account_number_encrypted, account_number_hash,
                     account_number_masked, name)
                VALUES (?, '001', 'enc', ?, '001***219', '주거래') RETURNING id
                """, Long.class, businessId, "hash-" + unique);
    }

    private FinTransactionRow fin(String externalId, BigDecimal balanceAfter, String counterparty) {
        return new FinTransactionRow(businessId, accountId, externalId, AT, ON,
                FlowType.DEPOSIT, new BigDecimal("1000"), balanceAfter, "정산 입금", counterparty);
    }

    private CsvTransactionRow csv(String dedupKey) {
        return new CsvTransactionRow(businessId, accountId, dedupKey, AT, ON,
                FlowType.WITHDRAWAL, new BigDecimal("2000"), null, "월세");
    }

    private int countRows() {
        return jdbc.queryForObject(
                "SELECT count(*) FROM core.transaction WHERE account_id = ?", Integer.class, accountId);
    }

    /**
     * <b>술어가 빠졌으면 여기서 죽는다</b> — {@code no unique or exclusion constraint matching
     * the ON CONFLICT specification}. 그래서 이 테스트를 가장 먼저 썼다.
     */
    @Test
    void absorbsRepeatedInsertOfTheSameFinTransaction() {
        assertThat(writer.insertFinRows(List.of(fin("191471", new BigDecimal("5"), null)))).isEqualTo(1);

        assertThatCode(() -> {
            int second = writer.insertFinRows(List.of(fin("191471", new BigDecimal("5"), null)));
            assertThat(second).as("재실행은 0건이어야 멱등이다").isZero();
        }).doesNotThrowAnyException();

        assertThat(countRows()).isEqualTo(1);
    }

    /** 스위퍼가 회수해 재실행하면 이미 넣은 것과 새 것이 섞여 온다. 새 것만 세야 한다. */
    @Test
    void countsOnlyTheRowsItActuallyInserted() {
        writer.insertFinRows(List.of(fin("1", null, null), fin("2", null, null)));

        int inserted = writer.insertFinRows(List.of(
                fin("1", null, null), fin("2", null, null),
                fin("3", null, null), fin("4", null, null), fin("5", null, null)));

        assertThat(inserted).isEqualTo(3);
        assertThat(countRows()).isEqualTo(5);
    }

    /**
     * 부분 유일 인덱스가 둘이라 서로 걸리지 않는다 — 같은 계좌에 CSV 행({@code deduplication_key}
     * 만 있음)과 금융망 행({@code external_transaction_id} 만 있음)이 공존해야 한다.
     * 하나로 합친 인덱스였다면 여기서 충돌한다.
     */
    @Test
    void csvAndFinRowsCoexistOnTheSameAccount() {
        assertThat(writer.insertFinRows(List.of(fin("191471", null, null)))).isEqualTo(1);
        assertThat(writer.insertCsvRows(List.of(csv("sha-a"), csv("sha-b")))).isEqualTo(2);

        assertThat(countRows()).isEqualTo(3);
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM core.transaction
                 WHERE account_id = ? AND source_type = 'CSV'
                """, Integer.class, accountId)).isEqualTo(2);
    }

    @Test
    void absorbsRepeatedInsertOfTheSameCsvRow() {
        assertThat(writer.insertCsvRows(List.of(csv("sha-a")))).isEqualTo(1);
        assertThat(writer.insertCsvRows(List.of(csv("sha-a")))).isZero();

        assertThat(countRows()).isEqualTo(1);
    }

    /**
     * INSERT 목록에서 뺀 컬럼을 DDL 기본값이 채우는지 본다.
     * <b>{@code tax_review_status} 는 엔티티가 매핑조차 하지 않는 {@code NOT NULL} 컬럼</b>이라
     * JDBC 경로에서 가장 놓치기 쉽다.
     */
    @Test
    void leavesDdlDefaultsToFillTheColumnsItOmits() {
        writer.insertFinRows(List.of(fin("191471", null, null)));

        var row = jdbc.queryForMap("""
                SELECT source_type, classification_status, needs_confirmation, is_excluded,
                       tax_review_status, created_at, updated_at
                  FROM core.transaction WHERE account_id = ?
                """, accountId);

        assertThat(row.get("source_type")).isEqualTo("FIN_API");
        assertThat(row.get("classification_status")).isEqualTo("UNCLASSIFIED");
        assertThat(row.get("needs_confirmation")).isEqualTo(false);
        assertThat(row.get("is_excluded")).isEqualTo(false);
        assertThat(row.get("tax_review_status")).isEqualTo("UNREVIEWED");
        assertThat(row.get("created_at")).isNotNull();
        assertThat(row.get("updated_at")).isNotNull();
    }

    /** nullable 3열이 전부 비어도 들어가야 한다 — 금융망이 적요·상대계좌를 안 줄 수 있다. */
    @Test
    void acceptsRowsWithEveryNullableColumnEmpty() {
        var bare = new FinTransactionRow(businessId, accountId, "191471", AT, ON,
                FlowType.DEPOSIT, new BigDecimal("1"), null, null, null);

        assertThat(writer.insertFinRows(List.of(bare))).isEqualTo(1);

        var row = jdbc.queryForMap("""
                SELECT balance_after, description, counterparty_account_no
                  FROM core.transaction WHERE account_id = ?
                """, accountId);
        assertThat(row.get("balance_after")).isNull();
        assertThat(row.get("description")).isNull();
        assertThat(row.get("counterparty_account_no")).isNull();
    }

    /** 빈 목록·null 에 왕복을 만들지 않는다. 구간에 거래가 없는 달이 흔하다. */
    @Test
    void doesNothingForEmptyInput() {
        assertThat(writer.insertFinRows(List.of())).isZero();
        assertThat(writer.insertFinRows(null)).isZero();
        assertThat(countRows()).isZero();
    }

    /**
     * {@code occurred_at} 이 UTC 오프셋으로 들어가는지 — {@code Instant} 를 그대로 바인딩하면
     * 드라이버가 서버 시간대로 해석해 9시간이 밀릴 수 있다.
     */
    @Test
    void storesOccurredAtAsTheSameInstant() {
        writer.insertFinRows(List.of(fin("191471", null, null)));

        Instant stored = jdbc.queryForObject("""
                SELECT occurred_at FROM core.transaction WHERE account_id = ?
                """, java.sql.Timestamp.class, accountId).toInstant();

        assertThat(stored).isEqualTo(AT);
    }
}
