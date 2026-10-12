package com.jangoyebo.core.transaction;

import com.jangoyebo.NeedsDocker;
import com.jangoyebo.common.domain.FlowType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * few-shot 예시 구성 (API 20.3 {@code fewShotExamples}).
 *
 * <p>확인하는 것은 셋이다 — 사용자가 확정한 것만 예시가 되는가, 같은 짝이 목록을 도배하지
 * 않는가, <b>하루 동안 목록이 고정되는가</b>. 마지막이 핵심이다: 목록이 요청마다 달라지면
 * 워커의 프롬프트 프리픽스 캐시가 죽는다(합의 ①·②).
 *
 * <p><b>Docker가 필요하다.</b>
 */
@NeedsDocker
@SpringBootTest
class FewShotExampleProviderTests {

    private static final ZoneId KST = ZoneId.of("Asia/Seoul");

    @Autowired private TransactionClassificationHistoryRepository historyRepository;
    @Autowired private TransactionClassificationProperties properties;
    @Autowired private JdbcTemplate jdbc;

    private Long userId;
    private Long businessId;
    private Long accountId;

    @BeforeEach
    void setUp() {
        String unique = String.valueOf(System.nanoTime());
        userId = jdbc.queryForObject("""
                INSERT INTO core.users (email, password_hash, name)
                VALUES (?, 'x', '예시') RETURNING id
                """, Long.class, "few-" + unique + "@test.local");
        businessId = jdbc.queryForObject("""
                INSERT INTO core.business (owner_user_id, name) VALUES (?, '예시 사업장') RETURNING id
                """, Long.class, userId);
        accountId = jdbc.queryForObject("""
                INSERT INTO core.account
                    (business_id, bank_code, account_number_encrypted, account_number_hash,
                     account_number_masked, name)
                VALUES (?, '088', 'enc', ?, '***1234', '주거래') RETURNING id
                """, Long.class, businessId, "hash-" + unique);
    }

    private Long categoryId(String code) {
        return jdbc.queryForObject(
                "SELECT id FROM core.transaction_category WHERE code = ?", Long.class, code);
    }

    /** 사용자가 확정한 거래 하나와 그 이력. 예시의 재료가 이 짝이다. */
    private void confirmed(String description, FlowType direction, String category, int minutesAgo) {
        Long categoryId = categoryId(category);
        Long transactionId = jdbc.queryForObject("""
                INSERT INTO core.transaction
                    (business_id, account_id, category_id, external_transaction_id, source_type,
                     occurred_at, transaction_date, flow_type, amount, description,
                     classification_status, needs_confirmation)
                VALUES (?, ?, ?, ?, 'FIN_API', now(), DATE '2026-08-25', ?, 1200000, ?,
                        'CONFIRMED', FALSE) RETURNING id
                """, Long.class, businessId, accountId, categoryId,
                "ext-" + System.nanoTime(), direction.name(), description);
        history(transactionId, categoryId, "USER", minutesAgo);
    }

    private void history(Long transactionId, Long categoryId, String classifier, int minutesAgo) {
        jdbc.update("""
                INSERT INTO core.transaction_classification_history
                    (transaction_id, new_category_id, changed_by, classifier_type,
                     previous_status, new_status, created_at)
                VALUES (?, ?, ?, ?, 'UNCLASSIFIED', 'CONFIRMED', now() - make_interval(mins => ?))
                """, transactionId, categoryId,
                "USER".equals(classifier) ? userId : null, classifier, minutesAgo);
    }

    private FewShotExampleProvider provider(Clock clock) {
        return new FewShotExampleProvider(historyRepository, properties, clock);
    }

    private FewShotExampleProvider provider() {
        return provider(Clock.fixed(Instant.parse("2026-09-11T03:00:00Z"), KST));
    }

    /** 기준선 — 사용자가 확정한 짝이 계약의 모양으로 나온다. */
    @Test
    void buildsExamplesFromUserConfirmations() {
        confirmed("우아한형제들", FlowType.DEPOSIT, "PLATFORM_SETTLEMENT", 10);
        confirmed("김영자", FlowType.WITHDRAWAL, "RENT", 20);

        List<FewShotExample> examples = provider().forBusiness(businessId);

        // 최신순이다 — 방금 정정한 것이 프롬프트 앞에 온다.
        assertThat(examples).containsExactly(
                new FewShotExample("우아한형제들", FlowType.DEPOSIT, "PLATFORM_SETTLEMENT"),
                new FewShotExample("김영자", FlowType.WITHDRAWAL, "RENT"));
    }

    /**
     * 같은 (적요, 분류) 짝은 한 번만 넣는다.
     *
     * <p>매달 같은 임대인·같은 플랫폼이라 확정이 수십 건씩 쌓인다. 그대로 넣으면 목록이 한
     * 거래처로 도배되고 <b>다른 거래처가 예시에서 밀려난다</b> — 예시 수가 예산이라서다.
     */
    @Test
    void keepsOneExamplePerDescriptionAndCategory() {
        confirmed("우아한형제들", FlowType.DEPOSIT, "PLATFORM_SETTLEMENT", 10);
        confirmed("우아한형제들", FlowType.DEPOSIT, "PLATFORM_SETTLEMENT", 40);
        confirmed("김영자", FlowType.WITHDRAWAL, "RENT", 20);

        assertThat(provider().forBusiness(businessId))
                .extracting(FewShotExample::description)
                .containsExactly("우아한형제들", "김영자");
    }

    /**
     * LLM 이 제안한 이력은 예시가 아니다.
     *
     * <p>모델의 추측을 정답으로 되돌려 주면 <b>자기 추측을 학습</b>한다. 예시가 되는 것은
     * 사람이 옳다고 한 것뿐이다.
     */
    @Test
    void ignoresSuggestionsThatNobodyConfirmed() {
        Long transactionId = jdbc.queryForObject("""
                INSERT INTO core.transaction
                    (business_id, account_id, category_id, external_transaction_id, source_type,
                     occurred_at, transaction_date, flow_type, amount, description,
                     classification_status, needs_confirmation)
                VALUES (?, ?, ?, ?, 'FIN_API', now(), DATE '2026-08-25', 'WITHDRAWAL', 1200000,
                        '김영자', 'SUGGESTED', FALSE) RETURNING id
                """, Long.class, businessId, accountId, categoryId("RENT"), "ext-" + System.nanoTime());
        history(transactionId, categoryId("RENT"), "LLM", 5);

        assertThat(provider().forBusiness(businessId)).isEmpty();
    }

    /**
     * <b>목록은 하루 동안 고정된다.</b> 오늘 정정한 것은 내일부터 예시가 된다.
     *
     * <p>즉시 반영과 프리픽스 캐시 적중은 함께 가질 수 없다. 여기서 확인하는 것은 그 맞바꿈이
     * 실제로 일어나는가다 — 같은 날 다시 물어도 같은 목록이고, 날이 바뀌면 갱신된다.
     */
    @Test
    void holdsTheListForTheDayAndRefreshesTheNextDay() {
        confirmed("김영자", FlowType.WITHDRAWAL, "RENT", 20);
        MutableClock clock = new MutableClock(Instant.parse("2026-09-11T03:00:00Z"), KST);
        FewShotExampleProvider provider = provider(clock);

        assertThat(provider.forBusiness(businessId)).hasSize(1);

        confirmed("우아한형제들", FlowType.DEPOSIT, "PLATFORM_SETTLEMENT", 1);
        assertThat(provider.forBusiness(businessId)).hasSize(1);

        clock.advance(Duration.ofDays(1));
        assertThat(provider.forBusiness(businessId)).hasSize(2);
    }

    /** 이력이 없는 사업장은 예시 없이 보낸다 — 계약에서 선택 필드다. */
    @Test
    void returnsEmptyWhenNothingWasConfirmed() {
        assertThat(provider().forBusiness(businessId)).isEmpty();
    }

    /**
     * 날짜를 움직일 수 있는 시계.
     *
     * <p>{@code withZone} 이 <b>그 시점의 instant 로 복사</b>하므로, 호출부가 매번
     * {@code withZone} 을 거치는 한 여기서 시간을 밀면 다음 호출에 반영된다.
     */
    private static final class MutableClock extends Clock {

        private Instant instant;
        private final ZoneId zone;

        private MutableClock(Instant instant, ZoneId zone) {
            this.instant = instant;
            this.zone = zone;
        }

        void advance(Duration duration) {
            this.instant = instant.plus(duration);
        }

        @Override
        public ZoneId getZone() {
            return zone;
        }

        @Override
        public Clock withZone(ZoneId other) {
            return new MutableClock(instant, other);
        }

        @Override
        public Instant instant() {
            return instant;
        }
    }
}
