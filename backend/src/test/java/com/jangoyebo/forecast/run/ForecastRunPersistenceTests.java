package com.jangoyebo.forecast.run;

import com.jangoyebo.NeedsDocker;
import com.jangoyebo.forecast.engine.BalanceCurveCalculator.DailyPoint;
import com.jangoyebo.forecast.run.ForecastRun.OpeningBalance;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 예측 실행 영속 (P0-07).
 *
 * <p><b>이 테스트가 판정하는 것 하나</b> — `opening_balances`·`basis` 를 {@code Map} 이 아니라
 * <b>타입 있는 record</b> 로 JSONB 에 매핑할 수 있는가. 이 코드베이스의 기존 JSONB 는 전부
 * {@code Map<String,Object>} 라 선례가 없다. 왕복이 깨지면 {@code Map} + 금액 문자열로
 * 후퇴해야 하므로 첫 커밋에서 확인한다.
 */
@NeedsDocker
@SpringBootTest
class ForecastRunPersistenceTests {

    private static final Instant BASE_AT = Instant.parse("2026-08-31T02:00:00Z");
    private static final LocalDate START = LocalDate.of(2026, 9, 1);
    private static final LocalDate END = LocalDate.of(2026, 9, 30);

    @Autowired
    private ForecastRunRepository runs;

    @Autowired
    private DailyBalanceForecastRepository dailyRows;

    @Autowired
    private JdbcTemplate jdbc;

    private Long businessId;

    @BeforeEach
    void setUp() {
        Long userId = jdbc.queryForObject("""
                INSERT INTO core.users (email, password_hash, name, status)
                VALUES (?, 'hash', '예보', 'ACTIVE') RETURNING id
                """, Long.class, "forecast-run-" + System.nanoTime() + "@test.local");
        businessId = jdbc.queryForObject("""
                INSERT INTO core.business (owner_user_id, name, taxation_type, status)
                VALUES (?, '온기식당', 'GENERAL', 'ACTIVE') RETURNING id
                """, Long.class, userId);
    }

    @Test
    @Transactional
    void basis_레코드가_JSONB_왕복에서_보존된다() {
        ForecastBasis basis = sampleBasis();
        ForecastRun saved = runs.saveAndFlush(completedRun(basis));
        runs.flush();

        // 영속성 컨텍스트를 비워 DB 에서 다시 읽게 한다 — 안 그러면 같은 객체를 볼 뿐이다.
        jdbc.execute("SELECT 1");
        ForecastRun loaded = runs.findById(saved.getId()).orElseThrow();

        assertThat(loaded.getBasis()).isEqualTo(basis);
    }

    @Test
    @Transactional
    void 금액이_정수나_실수로_뭉개지지_않는다() {
        // Map<String,Object> 로 매핑하면 여기서 Integer·Double 이 되어 정밀도를 잃는다.
        ForecastRun saved = runs.saveAndFlush(completedRun(sampleBasis()));
        runs.flush();

        ForecastRun loaded = runs.findById(saved.getId()).orElseThrow();

        BigDecimal expected = loaded.getBasis().residual().totalDeposit().expected();
        assertThat(expected).isInstanceOf(BigDecimal.class);
        assertThat(expected).isEqualByComparingTo("1240000.1234");
        assertThat(loaded.getBasis().residual().judgmentAlpha()).isEqualByComparingTo("0.00");
    }

    @Test
    @Transactional
    void 시작잔액은_계좌별로_남고_모르는_계좌는_합계에서_빠진다() {
        ForecastRun run = ForecastRun.completed(businessId, BASE_AT, START, END, "engine-1.0/none",
                Map.of("applyResidualModel", false),
                List.of(new OpeningBalance(11L, new BigDecimal("3000000.0000"), BASE_AT, "SYNC"),
                        new OpeningBalance(12L, new BigDecimal("2000000.0000"), BASE_AT, "LAST_TRANSACTION"),
                        new OpeningBalance(13L, null, null, "UNKNOWN")),
                sampleBasis());

        ForecastRun saved = runs.saveAndFlush(run);
        runs.flush();
        ForecastRun loaded = runs.findById(saved.getId()).orElseThrow();

        assertThat(loaded.getOpeningBalances()).hasSize(3);
        assertThat(loaded.openingBalanceTotal()).isEqualByComparingTo("5000000");
        assertThat(loaded.getOpeningBalances().get(2).balance()).isNull();
    }

    @Test
    @Transactional
    void 일별_곡선_삼십행이_저장된다() {
        ForecastRun run = runs.saveAndFlush(completedRun(sampleBasis()));

        List<DailyBalanceForecast> rows = dailyRows.saveAll(
                java.util.stream.IntStream.range(0, 30)
                        .mapToObj(i -> DailyBalanceForecast.of(run.getId(), point(START.plusDays(i)),
                                new BigDecimal("0.8500")))
                        .toList());
        dailyRows.flush();

        assertThat(rows).hasSize(30);
        assertThat(dailyRows.findByForecastRunIdOrderByForecastDateAsc(run.getId()))
                .hasSize(30)
                .first()
                .satisfies(r -> {
                    assertThat(r.getForecastDate()).isEqualTo(START);
                    assertThat(r.getConfidence()).isEqualByComparingTo("0.85");
                    assertThat(r.getBalanceExpected()).isEqualByComparingTo("5000000");
                });
    }

    @Test
    @Transactional
    void 범위가_뒤집힌_행은_DB가_거부한다() {
        // ck_dbf_range. 엔진의 불변식 검사가 뚫려도 여기서 막힌다.
        ForecastRun run = runs.saveAndFlush(completedRun(sampleBasis()));

        assertThatThrownBy(() -> {
            jdbc.update("""
                    INSERT INTO forecast.daily_balance_forecast
                        (forecast_run_id, forecast_date, balance_min, balance_expected, balance_max,
                         expected_deposit, expected_withdrawal, confidence)
                    VALUES (?, ?, 100, 50, 10, 0, 0, 0.5)
                    """, run.getId(), START);
        }).isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @Transactional
    void 최신_완료_실행만_조회한다() {
        runs.saveAndFlush(runAt(BASE_AT.minus(2, ChronoUnit.DAYS), ForecastRunStatus.COMPLETED));
        ForecastRun latest = runs.saveAndFlush(runAt(BASE_AT, ForecastRunStatus.COMPLETED));
        // 실패한 실행이 더 최근이어도 조회 대상이 아니다 — ix_fr_latest 가 COMPLETED 만 담는다.
        runs.saveAndFlush(runAt(BASE_AT.plus(1, ChronoUnit.DAYS), ForecastRunStatus.FAILED));
        runs.flush();

        assertThat(runs.findFirstByBusinessIdAndStatusOrderByBaseAtDesc(businessId, ForecastRunStatus.COMPLETED))
                .get()
                .extracting(ForecastRun::getId)
                .isEqualTo(latest.getId());
    }

    @Test
    void 실패한_실행도_사유와_함께_남는다() {
        ForecastRun failed = runs.saveAndFlush(ForecastRun.failed(businessId, BASE_AT, START, END,
                "engine-1.0/none", List.of(), "잔액을 읽지 못했습니다"));

        ForecastRun loaded = runs.findById(failed.getId()).orElseThrow();

        assertThat(loaded.getStatus()).isEqualTo(ForecastRunStatus.FAILED);
        assertThat(loaded.getBasis().warnings()).containsExactly("잔액을 읽지 못했습니다");
        assertThat(loaded.getCompletedAt()).isNull();
        assertThat(loaded.getCreatedAt()).isNotNull();
    }

    @Test
    void 정리는_사업장별_최신_완료_한건을_남긴다() {
        // 오래된 것만 지우면 한동안 안 쓴 사업장의 화면이 통째로 빈다.
        ForecastRun old1 = runs.saveAndFlush(runAt(BASE_AT.minus(5, ChronoUnit.DAYS), ForecastRunStatus.COMPLETED));
        ForecastRun old2 = runs.saveAndFlush(runAt(BASE_AT.minus(3, ChronoUnit.DAYS), ForecastRunStatus.COMPLETED));
        ForecastRun failed = runs.saveAndFlush(runAt(BASE_AT.minus(4, ChronoUnit.DAYS), ForecastRunStatus.FAILED));
        backdate(List.of(old1.getId(), old2.getId(), failed.getId()));

        runs.deleteOlderThanKeepingLatest(Instant.now());

        assertThat(runs.findById(old2.getId())).isPresent();      // 최신 COMPLETED — 남는다
        assertThat(runs.findById(old1.getId())).isEmpty();
        assertThat(runs.findById(failed.getId())).isEmpty();      // 실패는 보호 대상이 아니다
    }

    @Test
    void 실행을_지우면_일별_곡선도_함께_지워진다() {
        ForecastRun run = runs.saveAndFlush(completedRun(sampleBasis()));
        dailyRows.saveAndFlush(DailyBalanceForecast.of(run.getId(), point(START), new BigDecimal("0.9000")));

        runs.deleteById(run.getId());
        runs.flush();

        assertThat(dailyRows.findByForecastRunIdOrderByForecastDateAsc(run.getId())).isEmpty();
    }

    // --- 헬퍼 -----------------------------------------------------------------

    private ForecastRun completedRun(ForecastBasis basis) {
        return ForecastRun.completed(businessId, BASE_AT, START, END,
                "engine-1.0/residual-gru-0.3.0+a82bc981",
                Map.of("applySeasonality", false, "applyResidualModel", true),
                List.of(new OpeningBalance(11L, new BigDecimal("5000000.0000"), BASE_AT, "SYNC")),
                basis);
    }

    private ForecastRun runAt(Instant baseAt, ForecastRunStatus status) {
        return status == ForecastRunStatus.COMPLETED
                ? ForecastRun.completed(businessId, baseAt, START, END, "engine-1.0/none",
                        Map.of(), List.of(), sampleBasis())
                : ForecastRun.failed(businessId, baseAt, START, END, "engine-1.0/none", List.of(), "실패");
    }

    /** {@code created_at} 은 {@code @CreationTimestamp} 라 코드로 못 바꾼다. 정리 대상으로 만든다. */
    private void backdate(List<Long> ids) {
        for (Long id : ids) {
            jdbc.update("UPDATE forecast.forecast_run SET created_at = now() - INTERVAL '40 days' WHERE id = ?", id);
        }
    }

    private static ForecastBasis sampleBasis() {
        return new ForecastBasis(
                new ForecastBasis.Summary(14, 6, 2, "2026-06-01~2026-08-30"),
                new ForecastBasis.Residual(true, "MODEL", "residual-gru", "0.3.0+a82bc981", 214,
                        new BigDecimal("0.00"),
                        new ForecastBasis.Range(new BigDecimal("980000.0000"),
                                new BigDecimal("1240000.1234"), new BigDecimal("1510000.0000")),
                        new ForecastBasis.Range(new BigDecimal("720000.0000"),
                                new BigDecimal("860000.0000"), new BigDecimal("1030000.0000"))),
                List.of(new ForecastBasis.PlannedSnapshot(4021L, "카드정산", "PATTERN", "DEPOSIT", 12L,
                        LocalDate.of(2026, 9, 12), LocalDate.of(2026, 9, 14),
                        new BigDecimal("700000.0000"), new BigDecimal("748000.0000"),
                        new BigDecimal("800000.0000"), new BigDecimal("0.8100"))),
                List.of(new ForecastBasis.Adjustment("MODEL_RESIDUAL",
                        "반복 패턴으로 설명되지 않는 잔여 현금흐름 반영", List.of(), "residual-gru", "0.3.0+a82bc981")),
                List.of(),
                new ForecastBasis.Engine("1.0", true));
    }

    private static DailyPoint point(LocalDate date) {
        BigDecimal balance = new BigDecimal("5000000");
        return new DailyPoint(date, balance, balance, balance,
                BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ONE);
    }
}
