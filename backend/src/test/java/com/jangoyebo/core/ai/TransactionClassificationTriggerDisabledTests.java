package com.jangoyebo.core.ai;

import com.jangoyebo.NeedsDocker;
import com.jangoyebo.core.TransactionsChanged;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 자동 분류가 꺼져 있을 때 — <b>기본값이다</b> ({@code jango.classification.auto-trigger}).
 *
 * <p>거래가 들어올 때마다 LLM 을 부르면 시드를 넣고 빼며 시험하는 동안 그것이 그대로 토큰이
 * 된다. 그래서 기본은 꺼짐이고, 분류는 8.5 버튼으로만 돈다.
 *
 * <p><b>속성을 주지 않는다.</b> {@code application.yml} 의 기본값을 그대로 태워야
 * 「배포에 무엇이 깔리는가」를 보는 테스트가 된다 — 여기서 {@code =false} 를 적어 두면
 * 기본값이 나중에 켜짐으로 바뀌어도 이 테스트는 계속 초록이다.
 */
@NeedsDocker
@SpringBootTest
class TransactionClassificationTriggerDisabledTests {

    @Autowired private ApplicationEventPublisher events;
    @Autowired private TransactionTemplate transactions;
    @Autowired private JdbcTemplate jdbc;

    @Test
    void 기본값에서는_동기화가_끝나도_분류_작업을_넣지_않는다() {
        Long businessId = business();

        transactions.executeWithoutResult(status ->
                events.publishEvent(TransactionsChanged.bySync(businessId, 1L)));

        assertThat(classificationJobs(businessId))
                .as("자동 분류 기본값은 꺼짐이다 — 켤 때는 CLASSIFICATION_AUTO_TRIGGER 로 켠다")
                .isZero();
    }

    /** CSV 임포트도 같다. 자동으로 도는 경로는 하나도 없어야 한다. */
    @Test
    void 기본값에서는_CSV_임포트도_분류_작업을_넣지_않는다() {
        Long businessId = business();

        transactions.executeWithoutResult(status ->
                events.publishEvent(TransactionsChanged.byCsv(businessId, 1L)));

        assertThat(classificationJobs(businessId)).isZero();
    }

    private Long business() {
        Long userId = jdbc.queryForObject("""
                INSERT INTO core.users (email, password_hash, name) VALUES (?, 'x', '사장') RETURNING id
                """, Long.class, "auto-off-" + System.nanoTime() + "@test.local");
        return jdbc.queryForObject("""
                INSERT INTO core.business (owner_user_id, name) VALUES (?, '온기식당') RETURNING id
                """, Long.class, userId);
    }

    private Integer classificationJobs(Long businessId) {
        return jdbc.queryForObject("""
                SELECT count(*) FROM core.job
                 WHERE business_id = ? AND job_type = 'TRANSACTION_CLASSIFICATION'
                """, Integer.class, businessId);
    }
}
