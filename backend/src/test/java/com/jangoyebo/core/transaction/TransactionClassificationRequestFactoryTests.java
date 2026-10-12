package com.jangoyebo.core.transaction;

import com.jangoyebo.NeedsDocker;
import com.jangoyebo.common.domain.FlowType;
import com.jangoyebo.common.error.BusinessException;
import com.jangoyebo.core.transaction.TransactionClassificationRequest.TransactionToClassify;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 워커 요청 조립 (API 20.3 · {@code ai/app/nlp/schemas.py}).
 *
 * <p><b>여기서 확인하는 것은 대부분 "요청 전체가 422 로 거절되지 않는가" 다.</b> pydantic
 * 검증은 항목 단위가 아니라 본문 단위라, 거래 한 건의 적요가 길거나 금액이 음수이거나 업종이
 * 비어 있으면 <b>같은 Job 의 나머지 전부가 분류되지 않는다.</b> 그래서 거절될 수 있는 값은
 * 보내는 쪽에서 미리 맞춘다.
 *
 * <p>{@code batch-size} 를 2 로 낮춰 상한 동작을 적은 데이터로 본다.
 *
 * <p><b>Docker가 필요하다.</b>
 */
@NeedsDocker
@SpringBootTest(properties = "jango.classification.batch-size=2")
class TransactionClassificationRequestFactoryTests {

    /**
     * 워커 {@code TransactionCategory} Literal 의 순서 그대로 (23종).
     *
     * <p><b>이 목록이 어긋나면 모든 분류 요청이 422 다.</b> 같은 어휘가 세 곳에 산다 —
     * 시드 SQL({@code V2}), 워커({@code ai/app/nlp/schemas.py}), 프론트 선택 UI. 시드에 한
     * 종을 더하고 워커에 알리지 않으면 그 사업장의 분류가 통째로 멈추므로, 드리프트를 여기서
     * 잡는다. 워커 쪽도 같은 방식으로 {@code get_args(TransactionCategory)} 와 대조한다.
     */
    private static final List<String> WORKER_CATEGORIES = List.of(
            "CARD_SETTLEMENT", "PLATFORM_SETTLEMENT", "CASH_SALES", "TRANSFER_SALES",
            "LOAN_DISBURSEMENT", "OWNER_FUNDING", "OTHER_INCOME",
            "RENT", "PAYROLL", "SOCIAL_INSURANCE", "MATERIALS", "UTILITIES", "TELECOM",
            "ADVERTISING", "PLATFORM_FEE", "INSURANCE", "SUBSCRIPTION", "LOAN_REPAYMENT",
            "TAX", "SUPPLIES", "EQUIPMENT", "PERSONAL", "OTHER_EXPENSE");

    @Autowired private TransactionClassificationRequestFactory factory;
    @Autowired private JdbcTemplate jdbc;

    private Long userId;
    private Long businessId;
    private Long accountId;

    @BeforeEach
    void setUp() {
        String unique = String.valueOf(System.nanoTime());
        userId = jdbc.queryForObject("""
                INSERT INTO core.users (email, password_hash, name)
                VALUES (?, 'x', '조립') RETURNING id
                """, Long.class, "req-" + unique + "@test.local");
        businessId = business("FOOD_CAFE");
        accountId = account("CONNECTED");
    }

    private Long account(String status) {
        return jdbc.queryForObject("""
                INSERT INTO core.account
                    (business_id, bank_code, account_number_encrypted, account_number_hash,
                     account_number_masked, name, status)
                VALUES (?, '088', 'enc', ?, '***1234', '주거래', ?) RETURNING id
                """, Long.class, businessId, "hash-" + System.nanoTime(), status);
    }

    private Long business(String industryCode) {
        return jdbc.queryForObject("""
                INSERT INTO core.business (owner_user_id, name, industry_code)
                VALUES (?, '조립 사업장', ?) RETURNING id
                """, Long.class, userId, industryCode);
    }

    private Long unclassified(String description) {
        return transaction(description, "UNCLASSIFIED", false, 1200000);
    }

    private Long transaction(String description, String status, boolean excluded, int amount) {
        return transaction(accountId, description, status, excluded, amount);
    }

    private Long transaction(Long accountId, String description, String status,
                             boolean excluded, int amount) {
        return jdbc.queryForObject("""
                INSERT INTO core.transaction
                    (business_id, account_id, external_transaction_id, source_type, occurred_at,
                     transaction_date, flow_type, amount, description,
                     classification_status, needs_confirmation, is_excluded)
                VALUES (?, ?, ?, 'FIN_API', TIMESTAMPTZ '2026-08-25T01:00:00Z',
                        DATE '2026-08-25', 'WITHDRAWAL', ?, ?, ?, FALSE, ?) RETURNING id
                """, Long.class, businessId, accountId, "ext-" + System.nanoTime(),
                amount, description, status, excluded);
    }

    private TransactionClassificationRequest request() {
        return factory.create(9012L, businessId).orElseThrow();
    }

    /** 기준선 — 계약(부록 A)의 모양으로 조립된다. */
    @Test
    void assemblesRequestFromUnclassifiedTransactions() {
        Long transactionId = unclassified("김영자");

        TransactionClassificationRequest request = request();

        assertThat(request.jobId()).isEqualTo(9012L);
        assertThat(request.business().industryType()).isEqualTo("FOOD_CAFE");
        assertThat(request.transactions()).containsExactly(
                new TransactionToClassify(transactionId, "김영자", FlowType.WITHDRAWAL,
                        1200000L, "2026-08-25T01:00:00Z"));
    }

    /**
     * 23종 전부, 시드 순서 그대로.
     *
     * <p>업종별로 골라 보내지 않는 이유는 프리픽스 캐시가 정확 일치라서다(합의 ②) — 사업장마다
     * 목록이 갈리면 캐시도 갈린다.
     */
    @Test
    void sendsEveryActiveCategoryInSeedOrder() {
        unclassified("김영자");

        assertThat(request().allowedCategories()).isEqualTo(WORKER_CATEGORIES);
    }

    /** 한 Job 이 담는 수는 설정이 정한다 — 여기서는 2 다. */
    @Test
    void limitsToBatchSize() {
        unclassified("첫째");
        unclassified("둘째");
        unclassified("셋째");

        assertThat(request().transactions()).hasSize(2);
    }

    /**
     * 적요가 200자를 넘으면 잘라 보낸다.
     *
     * <p>워커 스키마가 {@code max_length=200} 이고 검증이 본문 단위라, 긴 적요 하나가 같은
     * Job 의 나머지를 전부 막는다. DB 값은 그대로다 — 자른 것은 요청뿐이다.
     */
    @Test
    void clipsDescriptionToTheWorkerLimit() {
        String tooLong = "가".repeat(250);
        unclassified(tooLong);

        assertThat(request().transactions().getFirst().description()).hasSize(200);
        assertThat(jdbc.queryForObject(
                "SELECT length(description) FROM core.transaction WHERE business_id = ?",
                Integer.class, businessId)).isEqualTo(250);
    }

    /**
     * 적요가 없는 거래는 보내지 않는다.
     *
     * <p>금융망이 주는 것이 적요 한 줄이 전부라(거래처명 필드가 없다) 적요가 비면 모델에게
     * 남는 것이 금액과 방향뿐이다. 값을 치르고 추측을 사지 않는다.
     */
    @Test
    void skipsTransactionsWithoutDescription() {
        Long withDescription = unclassified("김영자");
        unclassified(null);
        unclassified("   ");

        assertThat(request().transactions())
                .extracting(TransactionToClassify::transactionId)
                .containsExactly(withDescription);
    }

    /** 현금흐름에서 제외한 거래는 분류하지 않는다 — 쓰이는 곳이 없고 값만 나간다. */
    @Test
    void skipsExcludedTransactions() {
        Long included = unclassified("김영자");
        transaction("제외한 거래", "UNCLASSIFIED", true, 500000);

        assertThat(request().transactions())
                .extracting(TransactionToClassify::transactionId)
                .containsExactly(included);
    }

    /**
     * 해제한 계좌의 거래는 분류하지 않는다.
     *
     * <p>분류 결과가 나타나는 곳이 거래 목록인데 그 목록이 해제된 계좌를 기본으로 빼므로
     * ({@code TransactionRepository#search}), 분류해도 사용자가 「해제된 계좌 포함」을 켜기
     * 전에는 보이지 않는다. 값은 그대로 나간다.
     *
     * <p>영영 잃는 것이 아니라 미루는 것이다 — 되살리면 그 거래는 여전히
     * {@code UNCLASSIFIED} 라 다음 트리거나 8.5 재실행이 주워간다.
     */
    @Test
    void skipsTransactionsOfDisconnectedAccounts() {
        Long included = unclassified("김영자");
        transaction(account("DISCONNECTED"), "해제한 계좌의 거래", "UNCLASSIFIED", false, 500000);

        assertThat(request().transactions())
                .extracting(TransactionToClassify::transactionId)
                .containsExactly(included);
    }

    /**
     * 인증 대기 계좌의 거래는 분류한다.
     *
     * <p>표시에서 빼는 것은 <b>해제</b>뿐이다 — 인증 전 계좌에도 CSV 로 올린 거래가 있을 수
     * 있고 그것은 사용자가 직접 넣은 자료라 목록에 보인다. 예측 입력만 기준이 좁다
     * ({@code TransactionHistoryService} 는 {@code CONNECTED} 만 읽는다).
     */
    @Test
    void classifiesTransactionsOfAccountsAwaitingVerification() {
        Long pending = transaction(account("PENDING_VERIFICATION"), "인증 전 계좌의 거래",
                "UNCLASSIFIED", false, 500000);

        assertThat(request().transactions())
                .extracting(TransactionToClassify::transactionId)
                .containsExactly(pending);
    }

    /** 이미 분류된 거래는 다시 보내지 않는다. 같은 거래에 두 번 값을 치르지 않는다. */
    @Test
    void skipsAlreadyClassifiedTransactions() {
        transaction("확정된 거래", "CONFIRMED", false, 500000);
        transaction("제안된 거래", "SUGGESTED", false, 500000);

        assertThat(factory.create(9012L, businessId)).isEmpty();
    }

    /**
     * 업종이 비어 있어도 요청은 나간다.
     *
     * <p>워커 스키마의 {@code industry_type} 은 필수이고 우리 컬럼은 nullable 이다 —
     * 사업장 등록에서 업종은 선택이라 비어 있는 사업장이 실제로 생긴다. 비우고 보내면 요청
     * 전체가 422 이므로 허용 값 중 하나로 맞춘다.
     */
    @Test
    void fallsBackToOtherWhenIndustryIsMissing() {
        businessId = business(null);
        accountId = account("CONNECTED");
        unclassified("김영자");

        assertThat(request().business().industryType()).isEqualTo("OTHER");
    }

    /**
     * 분류할 것이 없으면 빈 값이다. <b>오류가 아니다.</b>
     *
     * <p>트리거가 쿼리 기반이라(합의 ④) 큐에 들어온 뒤 사용자가 직접 분류했거나 다른 Job 이
     * 먼저 처리했을 수 있다. 워커 스키마는 거래를 1건 이상 요구하므로 빈 요청을 보내면 422 다.
     */
    @Test
    void returnsEmptyWhenNothingToClassify() {
        assertThat(factory.create(9012L, businessId)).isEqualTo(Optional.empty());
    }

    /** 사업장이 지워진 뒤에 Job 이 돌면 재시도해도 같다 — 즉시 실패로 확정한다. */
    @Test
    void failsWhenBusinessIsGone() {
        unclassified("김영자");
        jdbc.update("UPDATE core.business SET deleted_at = now() WHERE id = ?", businessId);

        assertThatThrownBy(() -> factory.create(9012L, businessId))
                .isInstanceOf(BusinessException.class);
    }
}
