package com.jangoyebo.core.transaction;

import com.jangoyebo.NeedsDocker;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 거래 분류 결과 반영 (API 20.3 콜백 → {@code TRANSACTION} +
 * {@code TRANSACTION_CLASSIFICATION_HISTORY}).
 *
 * <p><b>본문은 명세 20.3 의 콜백 예시를 그대로 옮겼다.</b> 손으로 지어낸 모양이 아니라 워커가
 * 조립하는 것이고, 계약이 바뀌면 여기도 같이 바뀐다. 확인하는 것은 우리 구현의 내부가 아니라
 * <b>그 계약을 우리가 받아낼 수 있는가</b> 다.
 *
 * <p><b>Docker가 필요하다.</b>
 */
@NeedsDocker
@SpringBootTest
class TransactionClassificationServiceTests {

    @Autowired private TransactionClassificationService service;
    @Autowired private TransactionClassificationProperties properties;
    @Autowired private JdbcTemplate jdbc;

    private Long businessId;
    private Long accountId;
    private Long rentCategoryId;
    private Long materialsCategoryId;

    @BeforeEach
    void setUp() {
        String unique = String.valueOf(System.nanoTime());
        Long userId = jdbc.queryForObject("""
                INSERT INTO core.users (email, password_hash, name)
                VALUES (?, 'x', '분류') RETURNING id
                """, Long.class, "cls-" + unique + "@test.local");
        businessId = jdbc.queryForObject("""
                INSERT INTO core.business (owner_user_id, name) VALUES (?, '분류 사업장') RETURNING id
                """, Long.class, userId);
        accountId = jdbc.queryForObject("""
                INSERT INTO core.account
                    (business_id, bank_code, account_number_encrypted, account_number_hash,
                     account_number_masked, name)
                VALUES (?, '088', 'enc', ?, '***1234', '주거래') RETURNING id
                """, Long.class, businessId, "hash-" + unique);
        rentCategoryId = categoryId("RENT");
        materialsCategoryId = categoryId("MATERIALS");
    }

    private Long categoryId(String code) {
        return jdbc.queryForObject(
                "SELECT id FROM core.transaction_category WHERE code = ?", Long.class, code);
    }

    /** 아직 분류되지 않은 거래. 워커가 받는 것이 이 상태다. */
    private Long unclassified() {
        return insert("UNCLASSIFIED", null, false);
    }

    private Long insert(String status, Long categoryId, boolean needsConfirmation) {
        return jdbc.queryForObject("""
                INSERT INTO core.transaction
                    (business_id, account_id, category_id, external_transaction_id, source_type,
                     occurred_at, transaction_date, flow_type, amount, description,
                     classification_status, needs_confirmation)
                VALUES (?, ?, ?, ?, 'FIN_API', now(), DATE '2026-08-25', 'WITHDRAWAL', 1200000,
                        '김영자', ?, ?) RETURNING id
                """, Long.class, businessId, accountId, categoryId,
                "ext-" + System.nanoTime(), status, needsConfirmation);
    }

    /** 명세 20.3 의 결과 한 건. 값을 바꾸지 않는다. */
    private Map<String, Object> row(Long transactionId, String category, Object confidence) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("transactionId", transactionId);
        row.put("category", category);
        row.put("merchantName", "김영자(임대인 추정)");
        row.put("confidence", confidence);
        row.put("reason", "매월 25일 동일 금액 반복 출금은 임차료 패턴과 일치합니다.");
        return row;
    }

    /** 명세 20.3 의 콜백 {@code data}. */
    @SafeVarargs
    private Map<String, Object> callback(Map<String, Object>... rows) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("status", "COMPLETED");
        data.put("modelName", "claude-sonnet-5");
        data.put("modelVersion", null);
        data.put("promptVersion", "transaction-classification/v2");
        data.put("pipeline", "LLM");
        data.put("results", List.of(rows));
        return data;
    }

    private Map<String, Object> transactionRow(Long transactionId) {
        return jdbc.queryForMap(
                "SELECT * FROM core.transaction WHERE id = ?", transactionId);
    }

    private List<Map<String, Object>> historyOf(Long transactionId) {
        return jdbc.queryForList("""
                SELECT classifier_type, previous_status, new_status, previous_category_id,
                       new_category_id, changed_by, confidence,
                       reason->>'text' AS reason_text, reason->>'merchantName' AS merchant_name
                  FROM core.transaction_classification_history
                 WHERE transaction_id = ? ORDER BY id
                """, transactionId);
    }

    /**
     * 기준선 — <b>신뢰도가 기준 이상이면 바로 확정된다.</b>
     *
     * <p>전부 제안으로 떨어뜨리면 사장님이 612번을 눌러야 했고, 그렇게 만든 "확인 완료"는
     * 아무것도 보장하지 않는다. 기준(0.85)은 실측으로 정했다 — 근거는
     * {@link Transaction#classifyByLlm} 에 적었다.
     *
     * <p><b>출처는 {@code LLM} 으로 남는다.</b> 사람이 확정한 것과 구분되는 지점이 상태가
     * 아니라 출처라, 이 단언이 무너지면 자동 확정과 사용자 확정이 섞인다.
     */
    @Test
    void confirmsHighConfidenceClassification() {
        Long transactionId = unclassified();

        TransactionClassificationService.Applied applied =
                service.apply(businessId, callback(row(transactionId, "RENT", 0.92)));

        assertThat(applied.classified()).isEqualTo(1);
        assertThat(applied.needsConfirmation()).isZero();
        assertThat(applied.skipped()).isZero();

        Map<String, Object> transaction = transactionRow(transactionId);
        assertThat(transaction.get("category_id")).isEqualTo(rentCategoryId);
        assertThat(transaction.get("classification_status")).isEqualTo("CONFIRMED");
        assertThat(transaction.get("needs_confirmation")).isEqualTo(false);
        assertThat((BigDecimal) transaction.get("classification_confidence"))
                .isEqualByComparingTo("0.92");
        // 목록 응답이 행마다 이력을 조인하지 않게 지금 상태를 거래에도 둔다(V13).
        // DB 표기는 LLM 이고 API 가 AI 로 내보낸다(8.2).
        assertThat(transaction.get("classification_source")).isEqualTo("LLM");
        assertThat((String) transaction.get("classification_reason")).startsWith("매월 25일");
    }

    /**
     * 이력이 남아야 few-shot 원천과 정확도 추적이 성립한다.
     *
     * <p>{@code merchantName} 을 {@code reason} 에 담는 것이 합의 ③ 이다 — 거래처 테이블이
     * 아직 없고 {@code JOB.result} 는 30일 뒤 사라지므로 여기 없으면 영구 손실이다.
     */
    @Test
    void recordsHistoryWithMerchantName() {
        Long transactionId = unclassified();

        service.apply(businessId, callback(row(transactionId, "RENT", 0.92)));

        assertThat(historyOf(transactionId)).singleElement().satisfies(history -> {
            assertThat(history.get("classifier_type")).isEqualTo("LLM");
            assertThat(history.get("previous_status")).isEqualTo("UNCLASSIFIED");
            // 이력은 거래가 실제로 간 상태를 적는다. 0.92 는 기준 이상이라 확정이다 —
            // 여기를 고정값으로 두면 이력이 실제와 다른 값을 기록한다.
            assertThat(history.get("new_status")).isEqualTo("CONFIRMED");
            assertThat(history.get("previous_category_id")).isNull();
            assertThat(history.get("new_category_id")).isEqualTo(rentCategoryId);
            // 시스템 변경이라 사용자가 없다.
            assertThat(history.get("changed_by")).isNull();
            assertThat(history.get("merchant_name")).isEqualTo("김영자(임대인 추정)");
            assertThat((String) history.get("reason_text")).startsWith("매월 25일");
        });
    }

    /** 신뢰도가 기준(기본 0.85) 미만이면 사용자 확인 대기다 (20.3). */
    @Test
    void flagsLowConfidenceForConfirmation() {
        assertThat(properties.confidenceThreshold()).isEqualByComparingTo("0.85");
        Long transactionId = unclassified();

        TransactionClassificationService.Applied applied =
                service.apply(businessId, callback(row(transactionId, "RENT", 0.72)));

        assertThat(applied.needsConfirmation()).isEqualTo(1);
        assertThat(transactionRow(transactionId).get("needs_confirmation")).isEqualTo(true);
    }

    /**
     * 신뢰도가 없으면 확인 대기다.
     *
     * <p>{@code null} 을 0 으로 접으면 "모델이 값을 주지 않았다"와 "신뢰도 0"이 같아진다.
     * 둘 다 확인이 필요하다는 결론은 같지만, 이력의 {@code confidence} 는 비어 있어야 한다 —
     * 나중에 정확도를 재는 분모에 0 이 섞이면 평균이 내려간다.
     */
    @Test
    void flagsMissingConfidenceForConfirmation() {
        Long transactionId = unclassified();

        service.apply(businessId, callback(row(transactionId, "RENT", null)));

        Map<String, Object> transaction = transactionRow(transactionId);
        assertThat(transaction.get("needs_confirmation")).isEqualTo(true);
        assertThat(transaction.get("classification_confidence")).isNull();
        assertThat(historyOf(transactionId)).singleElement()
                .satisfies(history -> assertThat(history.get("confidence")).isNull());
    }

    /**
     * 모르는 분류 코드는 <b>그 건만</b> 버린다 (20.1).
     *
     * <p>요청 하나가 400건이라 한 건 때문에 전체를 버리면 399건까지 잃는다. 남은 거래는
     * {@code UNCLASSIFIED} 로 있어 8.5 재실행이 주워간다.
     */
    @Test
    void skipsUnknownCategoryWithoutLosingTheRest() {
        Long good = unclassified();
        Long bad = unclassified();

        TransactionClassificationService.Applied applied = service.apply(businessId,
                callback(row(bad, "COFFEE_BEANS", 0.99), row(good, "RENT", 0.92)));

        assertThat(applied.classified()).isEqualTo(1);
        assertThat(applied.skipped()).isEqualTo(1);
        assertThat(transactionRow(good).get("classification_status")).isEqualTo("CONFIRMED");
        assertThat(transactionRow(bad).get("classification_status")).isEqualTo("UNCLASSIFIED");
        assertThat(historyOf(bad)).isEmpty();
    }

    /**
     * <b>사용자가 확정한 분류는 덮지 않는다.</b>
     *
     * <p>요청 하나가 5분 넘게 도는 동안 사용자가 같은 거래를 8.3 으로 확정할 수 있다. 그때
     * 콜백을 그대로 반영하면 방금 사람이 한 판단이 제안으로 되돌려진다. 증빙 쪽에서 같은
     * 계열의 실수를 한 번 했다({@code Evidence.applyExtraction}).
     */
    @Test
    void keepsUserConfirmedClassification() {
        Long transactionId = insert("CONFIRMED", materialsCategoryId, false);

        TransactionClassificationService.Applied applied =
                service.apply(businessId, callback(row(transactionId, "RENT", 0.99)));

        assertThat(applied.classified()).isZero();
        assertThat(applied.skipped()).isEqualTo(1);
        Map<String, Object> transaction = transactionRow(transactionId);
        assertThat(transaction.get("classification_status")).isEqualTo("CONFIRMED");
        assertThat(transaction.get("category_id")).isEqualTo(materialsCategoryId);
        // 반영하지 않았으므로 이력도 남기지 않는다.
        assertThat(historyOf(transactionId)).isEmpty();
    }
}
