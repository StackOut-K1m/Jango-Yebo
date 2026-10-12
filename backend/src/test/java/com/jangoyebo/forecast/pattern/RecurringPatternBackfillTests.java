package com.jangoyebo.forecast.pattern;

import com.jangoyebo.NeedsDocker;
import com.jangoyebo.core.TransactionsClassified;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 거래 분류가 뒤늦게 돌았을 때 패턴의 빈 분류를 채운다
 * ({@link RecurringPatternService#backfillCategories}).
 *
 * <p><b>이 경로가 없으면 빠져나올 수 없다.</b> 재탐지는 후보만 갱신하고, 승인·수정 요청에
 * 분류를 실어 보내는 화면이 없다. 그래서 분류 전에 승인해 버린 패턴은 분류가 빈 채로 굳고,
 * 그 패턴이 만든 예정 입금은 시나리오의 매출 변화에서 영원히 빠진다(11.1).
 *
 * <p><b>패턴만 고치면 반쪽이다.</b> 예정 거래는 만들 때 분류를 복사해 두므로 다시 만들지
 * 않으면 곡선이 읽는 값은 그대로 비어 있다 — 그래서 여기서 예정 거래까지 본다.
 */
@NeedsDocker
@SpringBootTest
class RecurringPatternBackfillTests {

    private static final LocalDate TODAY = LocalDate.now(ZoneId.of("Asia/Seoul"));

    @Autowired
    private RecurringPatternService service;

    @Autowired
    private RecurringPatternRepository patterns;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private ApplicationEventPublisher publisher;

    @Autowired
    private TransactionTemplate transactions;

    private Long businessId;
    private Long accountId;
    private Long userId;
    private Long cardSettlement;
    private Long platformSettlement;

    @BeforeEach
    void setUp() {
        userId = jdbc.queryForObject("""
                INSERT INTO core.users (email, password_hash, name) VALUES (?, 'x', '사장') RETURNING id
                """, Long.class, "backfill-" + System.nanoTime() + "@test.local");
        businessId = jdbc.queryForObject("""
                INSERT INTO core.business (owner_user_id, name) VALUES (?, '온기식당') RETURNING id
                """, Long.class, userId);
        accountId = jdbc.queryForObject("""
                INSERT INTO core.account (business_id, bank_code, account_number_encrypted,
                                          account_number_hash, account_number_masked, name, status)
                VALUES (?, '004', 'enc', ?, '004-**-1234', '주계좌', 'CONNECTED') RETURNING id
                """, Long.class, businessId, "hash-" + System.nanoTime());
        cardSettlement = categoryId("CARD_SETTLEMENT");
        platformSettlement = categoryId("PLATFORM_SETTLEMENT");
    }

    /**
     * <b>승인까지 끝난 패턴이 대상이다.</b> 후보만 고치면 시연 직전에 「다시 탐지하세요」를
     * 시킬 수 없는 상태가 그대로 남는다.
     */
    @Test
    void 분류가_늦게_붙어도_승인된_패턴과_예정_거래가_따라온다() {
        Long patternId = candidatePattern(null);
        linkSample(patternId, classifiedDeposit(cardSettlement, 7));
        linkSample(patternId, classifiedDeposit(cardSettlement, 14));
        service.confirm(patternId, userId, null, null, null, null, TODAY);

        // 분류 전에 승인한 결과 — 예정 입금이 분류 없이 만들어졌다. 이 상태가 버그의 모양이다.
        assertThat(plannedCategories(patternId)).isNotEmpty().containsOnlyNulls();

        int filled = service.backfillCategories(businessId, TODAY);

        assertThat(filled).isEqualTo(1);
        assertThat(patterns.findById(patternId).orElseThrow().getCategoryId()).isEqualTo(cardSettlement);
        assertThat(plannedCategories(patternId)).isNotEmpty().containsOnly(cardSettlement);
    }

    /**
     * <b>분류가 끝나면 저절로 채워져야 한다.</b> 사람이 탐지를 다시 누르는 것에 기대면
     * 「거래는 카드 정산인데 매출 변화가 안 먹는다」를 사용자가 먼저 만난다.
     *
     * <p>이벤트를 커밋되는 트랜잭션 안에서 쏜다 — 듣는 쪽이 {@code AFTER_COMMIT} 이라
     * 그냥 발행하면 조용히 버려진다. <b>그 자리에서 쓴 것이 실제로 커밋되는지</b>까지
     * 보는 것이 이 테스트의 값어치다.
     */
    @Test
    void 분류_완료_이벤트가_백필을_돌린다() {
        Long patternId = candidatePattern(null);
        linkSample(patternId, classifiedDeposit(cardSettlement, 7));
        service.confirm(patternId, userId, null, null, null, null, TODAY);

        transactions.executeWithoutResult(status ->
                publisher.publishEvent(new TransactionsClassified(businessId)));

        assertThat(categoryIdOf(patternId)).isEqualTo(cardSettlement);
        assertThat(plannedCategories(patternId)).isNotEmpty().containsOnly(cardSettlement);
    }

    /** 표가 갈리면 많은 쪽. 사용자가 한 건만 정정했다고 패턴 전체가 끌려가지 않는다. */
    @Test
    void 표본_분류가_갈리면_많은_쪽을_쓴다() {
        Long patternId = candidatePattern(null);
        linkSample(patternId, classifiedDeposit(cardSettlement, 7));
        linkSample(patternId, classifiedDeposit(cardSettlement, 14));
        linkSample(patternId, classifiedDeposit(platformSettlement, 21));

        assertThat(service.backfillCategories(businessId, TODAY)).isEqualTo(1);
        assertThat(patterns.findById(patternId).orElseThrow().getCategoryId()).isEqualTo(cardSettlement);
    }

    /** 근거 거래가 아직 미분류면 채울 값이 없다 — 0 을 채웠다고 말하지 않는다. */
    @Test
    void 근거_거래가_미분류면_아무것도_채우지_않는다() {
        Long patternId = candidatePattern(null);
        linkSample(patternId, classifiedDeposit(null, 7));

        assertThat(service.backfillCategories(businessId, TODAY)).isZero();
        assertThat(patterns.findById(patternId).orElseThrow().getCategoryId()).isNull();
    }

    /**
     * <b>재탐지가 실제로 고쳐야 한다.</b> 시나리오가 경고로 시키는 일이 「거래 분류를 실행한
     * 뒤 반복 패턴을 다시 탐지」인데, 탐지 자체는 후보만 갱신하므로 승인된 패턴에는 닿지
     * 않았다 — 시키는 대로 해도 아무것도 달라지지 않는 안내였다.
     *
     * <p>여기 거래들은 반복으로 잡히지 않는다(표본이 모자란다). 그래도 채워져야 한다 —
     * 채우는 근거는 새 탐지 결과가 아니라 <b>패턴이 이미 들고 있는 표본</b>이다.
     */
    @Test
    void 재탐지가_확정된_패턴의_빈_분류도_채운다() {
        Long patternId = candidatePattern(null);
        linkSample(patternId, classifiedDeposit(cardSettlement, 7));
        service.confirm(patternId, userId, null, null, null, null, TODAY);

        service.detectAndUpsert(businessId, TODAY);

        assertThat(patterns.findById(patternId).orElseThrow().getCategoryId()).isEqualTo(cardSettlement);
        assertThat(plannedCategories(patternId)).isNotEmpty().containsOnly(cardSettlement);
    }

    /** 이미 분류가 있는 패턴은 조회 대상도 아니다 — 사용자가 고른 값을 거래가 뒤엎지 않는다. */
    @Test
    void 분류가_있는_패턴은_건드리지_않는다() {
        Long patternId = candidatePattern(platformSettlement);
        linkSample(patternId, classifiedDeposit(cardSettlement, 7));

        assertThat(service.backfillCategories(businessId, TODAY)).isZero();
        assertThat(patterns.findById(patternId).orElseThrow().getCategoryId())
                .isEqualTo(platformSettlement);
    }

    // --- helpers -------------------------------------------------------------------

    private Long categoryId(String code) {
        return jdbc.queryForObject(
                "SELECT id FROM core.transaction_category WHERE code = ?", Long.class, code);
    }

    /** 주 1회 입금 패턴. 닻을 내일로 두어 승인하면 예정 거래가 바로 만들어진다. */
    private Long candidatePattern(Long categoryId) {
        return jdbc.queryForObject("""
                INSERT INTO forecast.recurring_pattern
                    (business_id, account_id, category_id, name, flow_type, cadence_type,
                     day_of_week, expected_amount_min, expected_amount_max,
                     next_expected_date, confidence, status)
                VALUES (?, ?, ?, '카드 정산', 'DEPOSIT', 'WEEKLY', ?, 800000, 1200000, ?, 0.82, 'CANDIDATE')
                RETURNING id
                """, Long.class, businessId, accountId, categoryId,
                TODAY.plusDays(1).getDayOfWeek().getValue(), TODAY.plusDays(1));
    }

    /** @param categoryId {@code null} 이면 아직 분류되지 않은 거래다 */
    private Long classifiedDeposit(Long categoryId, int daysAgo) {
        LocalDate date = TODAY.minusDays(daysAgo);
        return jdbc.queryForObject("""
                INSERT INTO core.transaction
                    (business_id, account_id, category_id, source_type, occurred_at, transaction_date,
                     flow_type, amount, description, classification_status)
                VALUES (?, ?, ?, 'FIN_API', ?, ?, 'DEPOSIT', 1000000, '카드매출입금', ?)
                RETURNING id
                """, Long.class, businessId, accountId, categoryId,
                date.atStartOfDay(ZoneId.of("Asia/Seoul")).toOffsetDateTime(), date,
                categoryId == null ? "UNCLASSIFIED" : "CONFIRMED");
    }

    private void linkSample(Long patternId, Long transactionId) {
        jdbc.update("""
                INSERT INTO forecast.recurring_pattern_transaction
                    (recurring_pattern_id, transaction_id, match_score)
                VALUES (?, ?, 0.9)
                """, patternId, transactionId);
    }

    /** 영속성 컨텍스트를 거치지 않고 읽는다 — 커밋된 값인지를 봐야 한다. */
    private Long categoryIdOf(Long patternId) {
        return jdbc.queryForObject(
                "SELECT category_id FROM forecast.recurring_pattern WHERE id = ?", Long.class, patternId);
    }

    private List<Long> plannedCategories(Long patternId) {
        return jdbc.queryForList("""
                SELECT category_id FROM forecast.planned_transaction
                 WHERE recurring_pattern_id = ?
                """, Long.class, patternId);
    }
}
