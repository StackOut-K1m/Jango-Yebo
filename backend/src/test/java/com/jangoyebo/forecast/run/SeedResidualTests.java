package com.jangoyebo.forecast.run;

import com.jangoyebo.NeedsDocker;
import com.jangoyebo.forecast.pattern.RecurringPattern;
import com.jangoyebo.forecast.pattern.RecurringPatternRepository;
import com.jangoyebo.forecast.calendar.BusinessCalendarDtos;
import com.jangoyebo.forecast.calendar.BusinessCalendarService;
import com.jangoyebo.forecast.calendar.CalendarSource;
import com.jangoyebo.forecast.calendar.DayType;
import com.jangoyebo.forecast.pattern.RecurringPatternService;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.io.BufferedReader;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.LinkedHashMap;
import java.util.Set;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * <b>시드가 모델에게 줄 것을 남기는가</b> (S15P21C105-155).
 *
 * <p>시드 CSV 를 넣고 <b>실제</b> 탐지·승인·잔여 집계를 태운다. 지키는 불변식은 하나다 —
 * {@code irregular} 표가 만든 거래는 <b>패턴으로 잡히면 안 된다.</b> 잡히면 예정 거래로
 * 투영돼 잔여에서 빠지고, 모델 입력이 도로 빈다.
 *
 * <p>2026-09-13 에 실제로 그 상태였다. 거래 290건 중 288건이 패턴으로 설명돼 잔여가
 * 부가세 2건(금액의 2.3%)뿐이었고, 워커는 맞힐 것이 없는 시계열을 받고 있었다.
 * 탐지 임계값을 느슨하게 하거나 시드 확률을 낮추면 <b>조용히</b> 그리로 돌아간다.
 *
 * <p>나머지 수치는 단정하지 않고 찍기만 한다 ({@code -i} 로 보인다). 잔여의 모양이
 * 학습 분포 안인지는 실험실이 잰다 — {@code audit/seed_residual.py}.
 *
 * <p>인증을 타지 않는다 — 서비스를 직접 부른다. 그래서 로그인 없이 제품 경로를 잰다.
 *
 * <p>{@code seed-out/*.csv} 가 없으면 건너뛴다. 그 폴더는 git 밖이다.
 *
 * <pre>
 * node scripts/seed.mjs --csv-only --as-of 2026-09-13
 * ./gradlew test --tests SeedResidualTests -i
 * </pre>
 */
@NeedsDocker
@SpringBootTest
class SeedResidualTests {

    /** 시드 CSV 의 마지막 날 다음 날. 탐지 창 180일이 CSV 첫날과 맞는다. */
    private static final LocalDate TODAY = LocalDate.of(2026, 9, 13);

    @Autowired private JdbcTemplate jdbc;
    @Autowired private RecurringPatternService patternService;
    @Autowired private RecurringPatternRepository patterns;
    @Autowired private ForecastRunService runService;
    @Autowired private BusinessCalendarService calendarService;

    @Test
    void 시드_CSV_가_남기는_잔여를_찍는다() throws Exception {
        Path csv = newestSeedCsv();
        Assumptions.assumeTrue(csv != null,
                "seed-out 에 CSV 가 없다 — node scripts/seed.mjs --csv-only 를 먼저 돌린다");

        Long businessId = setUpBusiness();
        Long accountId = jdbc.queryForObject(
                "SELECT id FROM core.account WHERE business_id = ?", Long.class, businessId);
        int loaded = load(csv, businessId, accountId);

        int created = patternService.detectAndUpsert(businessId, TODAY);
        List<RecurringPattern> found = patterns.findByBusinessIdOrderByConfidenceDesc(businessId);
        for (RecurringPattern p : found) {
            patternService.confirm(p.getId(), 1L, null, null, null, null, TODAY);
        }

        ForecastRunService.ResidualInputs inputs = runService.loadResidual(businessId, TODAY);
        Set<Long> explained = inputs.matchedIds();

        System.out.println();
        System.out.println("==== 시드 CSV → 실제 제품 경로 ====");
        System.out.printf("CSV             %s%n", csv.getFileName());
        System.out.printf("거래            %d건%n", loaded);
        System.out.printf("패턴            %d개 (신규 %d)%n", found.size(), created);
        System.out.printf("설명된 거래      %d건%n", explained.size());
        System.out.printf("잔여 표본일수    %d일%n", inputs.historyDays());

        var s = inputs.series();
        if (s == null) {
            System.out.println("잔여 시계열이 비었다");
            return;
        }
        System.out.printf("잔여 입금 합계   %,d원%n", s.totalDeposit().longValue());
        System.out.printf("잔여 출금 합계   %,d원%n", s.totalWithdrawal().longValue());
        System.out.printf("0인 날 · 입금    %.1f%%%n", zeroRatio(s.deposit()) * 100);
        System.out.printf("0인 날 · 출금    %.1f%%%n", zeroRatio(s.withdrawal()) * 100);

        System.out.println();
        System.out.println("-- 패턴이 잡아간 적요 --");
        for (RecurringPattern p : found) {
            System.out.printf("  %-14s %-11s %-14s %s%n",
                    p.getName(), p.getFlowType(), p.getCadenceType(), p.getConfidence());
        }

        System.out.println();
        System.out.println("-- 잔여로 남은 적요 --");
        for (Map.Entry<String, long[]> e : residualByDescription(businessId, explained)) {
            System.out.printf("  %-14s %4d건 %,14d원%n",
                    e.getKey(), e.getValue()[0], e.getValue()[1]);
        }
        System.out.println("==================================");
        System.out.println();

        // 불변식 — irregular 표의 적요는 하나도 패턴이 되면 안 된다
        List<String> names = found.stream().map(RecurringPattern::getName).toList();
        assertThat(names).as("주기 없는 거래가 패턴으로 잡혔다 — 잔여가 사라진다")
                .doesNotContainAnyElementsOf(IRREGULAR);
        assertThat(loaded - explained.size())
                .as("잔여 건수")
                .isGreaterThan(loaded / 10);
    }

    /**
     * <b>휴무일을 등록하면 정산 투영이 그만큼 준다</b> (설계노트 G-2 ③ · 연휴 감쇄).
     *
     * <p>시드의 카페는 추석 사흘(9/24~26)을 닫는다. 그 주 배달·현금 정산은 매출이 적어
     * 실제로도 적게 들어오는데, 달력이 비어 있으면 투영이 평소와 같은 금액을 찍는다.
     *
     * <p>실측: 배달 정산 09-29 투영 497,400원 대 실제 290,200원. 예정 입금 과대평가
     * 331,850원 중 253,050원(76%)이 추석 주 두 건이었다.
     */
    @Test
    void 휴무일이_등록되면_정산_투영이_준다() throws Exception {
        Path csv = newestSeedCsv();
        Assumptions.assumeTrue(csv != null, "seed-out 에 CSV 가 없다");

        Long businessId = setUpBusiness();
        Long accountId = jdbc.queryForObject(
                "SELECT id FROM core.account WHERE business_id = ?", Long.class, businessId);
        load(csv, businessId, accountId);
        closeFor(businessId, LocalDate.of(2026, 9, 24), LocalDate.of(2026, 9, 26));

        patternService.detectAndUpsert(businessId, TODAY);
        for (RecurringPattern p : patterns.findByBusinessIdOrderByConfidenceDesc(businessId)) {
            patternService.confirm(p.getId(), 1L, null, null, null, null, TODAY);
        }

        Map<LocalDate, BigDecimal> platform = jdbc.query("""
                SELECT expected_date, amount_expected FROM forecast.planned_transaction
                 WHERE business_id = ? AND name = '우아한형제들' AND flow_type = 'DEPOSIT'
                   AND expected_date BETWEEN ? AND ?
                """, rs -> {
            Map<LocalDate, BigDecimal> out = new LinkedHashMap<>();
            while (rs.next()) {
                out.put(rs.getDate(1).toLocalDate(), rs.getBigDecimal(2));
            }
            return out;
        }, businessId, java.sql.Date.valueOf(TODAY), java.sql.Date.valueOf(TODAY.plusDays(30)));

        System.out.println();
        System.out.println("-- 배달 정산 투영 (추석 휴업 등록 뒤) --");
        platform.forEach((d, a) -> System.out.printf("  %s  %,12.0f%n", d, a));

        // 곡선을 밖에서 다시 그려 보려고 전량을 찍는다 (PLANNED 접두어로 뽑아 쓴다).
        jdbc.query("""
                SELECT expected_date, flow_type, amount_expected, name
                  FROM forecast.planned_transaction
                 WHERE business_id = ? AND expected_date BETWEEN ? AND ?
                 ORDER BY expected_date
                """, rs -> {
            System.out.printf("PLANNED,%s,%s,%s,%s%n", rs.getDate(1).toLocalDate(),
                    rs.getString(2), rs.getBigDecimal(3).toPlainString(), rs.getString(4));
        }, businessId, java.sql.Date.valueOf(TODAY.plusDays(1)), java.sql.Date.valueOf(TODAY.plusDays(30)));

        assertThat(platform).as("배달 정산 회차가 하나도 안 잡혔다").isNotEmpty();

        // 추석 사흘을 덮는 회차는 평소 회차보다 적어야 한다.
        BigDecimal afterChuseok = platform.entrySet().stream()
                .filter(e -> !e.getKey().isBefore(LocalDate.of(2026, 9, 27))
                        && e.getKey().isBefore(LocalDate.of(2026, 10, 4)))
                .map(Map.Entry::getValue).findFirst().orElse(null);
        BigDecimal normal = platform.entrySet().stream()
                .filter(e -> e.getKey().isBefore(LocalDate.of(2026, 9, 22)))
                .map(Map.Entry::getValue).findFirst().orElse(null);

        assertThat(afterChuseok).as("추석 뒤 회차").isNotNull();
        assertThat(normal).as("평소 회차").isNotNull();
        assertThat(afterChuseok)
                .as("추석 사흘을 덮는 회차가 평소와 같다 — 감쇄가 안 걸렸다")
                .isLessThan(normal);
    }

    /** 달력이 비면 아무것도 안 바뀐다 — 이 기능이 조용히 금액을 바꾸면 안 된다. */
    @Test
    void 달력이_비면_투영이_그대로다() throws Exception {
        Path csv = newestSeedCsv();
        Assumptions.assumeTrue(csv != null, "seed-out 에 CSV 가 없다");

        Long businessId = setUpBusiness();
        Long accountId = jdbc.queryForObject(
                "SELECT id FROM core.account WHERE business_id = ?", Long.class, businessId);
        load(csv, businessId, accountId);

        patternService.detectAndUpsert(businessId, TODAY);
        for (RecurringPattern p : patterns.findByBusinessIdOrderByConfidenceDesc(businessId)) {
            patternService.confirm(p.getId(), 1L, null, null, null, null, TODAY);
        }

        Integer distinct = jdbc.queryForObject("""
                SELECT count(DISTINCT amount_expected) FROM forecast.planned_transaction
                 WHERE business_id = ? AND name = '우아한형제들' AND flow_type = 'DEPOSIT'
                """, Integer.class, businessId);

        assertThat(distinct).as("휴무일이 없는데 금액이 갈렸다").isEqualTo(1);
    }

    /**
     * <b>승인한 뒤에 휴무일을 등록해도 곡선이 움직인다.</b>
     *
     * <p>감쇄는 예정 거래를 <b>만들 때</b> 계산하므로, 나중에 달력을 고치면 이미 만들어진
     * 것은 옛 금액 그대로다. 사장님이 「추석에 쉰다」를 등록했는데 화면이 안 바뀌면
     * 등록할 이유가 없다 — 그래서 등록이 재생성을 부른다.
     */
    @Test
    void 승인_뒤에_휴무일을_등록해도_다시_만들어진다() throws Exception {
        Path csv = newestSeedCsv();
        Assumptions.assumeTrue(csv != null, "seed-out 에 CSV 가 없다");

        Long businessId = setUpBusiness();
        Long accountId = jdbc.queryForObject(
                "SELECT id FROM core.account WHERE business_id = ?", Long.class, businessId);
        load(csv, businessId, accountId);

        patternService.detectAndUpsert(businessId, TODAY);
        for (RecurringPattern p : patterns.findByBusinessIdOrderByConfidenceDesc(businessId)) {
            patternService.confirm(p.getId(), 1L, null, null, null, null, TODAY);
        }
        BigDecimal before = platformTotal(businessId);

        // 여기서 비로소 등록한다 — 승인은 이미 끝났다.
        calendarService.upsert(businessId, List.of(
                        new BusinessCalendarDtos.DayRequest(LocalDate.of(2026, 9, 24), DayType.CLOSED, "추석"),
                        new BusinessCalendarDtos.DayRequest(LocalDate.of(2026, 9, 25), DayType.CLOSED, "추석"),
                        new BusinessCalendarDtos.DayRequest(LocalDate.of(2026, 9, 26), DayType.CLOSED, "추석")),
                CalendarSource.USER, TODAY);

        BigDecimal after = platformTotal(businessId);
        System.out.printf("%n-- 배달 정산 30일 합계 --%n  등록 전 %,12.0f%n  등록 후 %,12.0f%n", before, after);

        assertThat(after).as("휴무일을 등록했는데 예정 거래가 그대로다").isLessThan(before);
    }

    private BigDecimal platformTotal(Long businessId) {
        BigDecimal v = jdbc.queryForObject("""
                SELECT coalesce(sum(amount_expected), 0) FROM forecast.planned_transaction
                 WHERE business_id = ? AND name = '우아한형제들' AND flow_type = 'DEPOSIT'
                   AND expected_date BETWEEN ? AND ?
                """, BigDecimal.class, businessId,
                java.sql.Date.valueOf(TODAY), java.sql.Date.valueOf(TODAY.plusDays(30)));
        return v == null ? BigDecimal.ZERO : v;
    }

    private void closeFor(Long businessId, LocalDate from, LocalDate to) {
        for (LocalDate d = from; !d.isAfter(to); d = d.plusDays(1)) {
            jdbc.update("""
                    INSERT INTO forecast.business_calendar_day
                        (business_id, calendar_date, day_type, source, name)
                    VALUES (?, ?, 'CLOSED', 'SEED', '명절 휴업')
                    """, businessId, java.sql.Date.valueOf(d));
        }
    }

    /**
     * {@code scripts/seed/plan.mjs} 의 {@code irregular} 표가 쓰는 적요.
     *
     * <p>여기 손대기 전에 그 표의 머리말을 읽는다 — 셋 중 <b>어느 방식으로</b> 탐지를
     * 벗어나는지가 적혀 있다(적요 흩뿌리기 · 간격 흔들기 · 드물게 일으키기).
     */
    private static final List<String> IRREGULAR = List.of(
            "다이소", "쿠팡", "이마트에브리데이", "홈플러스", "오피스디포",
            "커피동아", "청호나이스", "카드취소", "쿠프마케팅", "스마일콘");

    /** 적요별 (건수, 금액). 매칭·패턴이 설명한 것은 뺀다 — 집계가 하는 일과 같다. */
    private List<Map.Entry<String, long[]>> residualByDescription(Long businessId,
                                                                  Set<Long> explained) {
        Map<String, long[]> byDescription = new LinkedHashMap<>();
        jdbc.query("""
                SELECT id, description, amount FROM core.transaction
                 WHERE business_id = ? AND is_excluded = false AND transaction_date <= ?
                """, rs -> {
            if (explained.contains(rs.getLong("id"))) {
                return;
            }
            long[] cell = byDescription.computeIfAbsent(rs.getString("description"),
                    k -> new long[2]);
            cell[0] += 1;
            cell[1] += rs.getBigDecimal("amount").longValue();
        }, businessId, java.sql.Date.valueOf(TODAY));

        List<Map.Entry<String, long[]>> rows = new ArrayList<>(byDescription.entrySet());
        rows.sort(Comparator.comparingLong((Map.Entry<String, long[]> e) -> e.getValue()[1])
                .reversed());
        return rows;
    }

    private static double zeroRatio(List<BigDecimal> values) {
        if (values.isEmpty()) {
            return 0;
        }
        long zero = values.stream().filter(v -> v.signum() == 0).count();
        return (double) zero / values.size();
    }

    private static Path newestSeedCsv() throws Exception {
        Path dir = Path.of("..", "seed-out");
        if (!Files.isDirectory(dir)) {
            return null;
        }
        try (Stream<Path> files = Files.list(dir)) {
            return files.filter(p -> p.toString().endsWith(".csv"))
                    .max(Comparator.comparingLong(p -> p.toFile().lastModified()))
                    .orElse(null);
        }
    }

    private Long setUpBusiness() {
        Long userId = jdbc.queryForObject("""
                INSERT INTO core.users (email, password_hash, name, status)
                VALUES (?, 'hash', '이한호', 'ACTIVE') RETURNING id
                """, Long.class, "seed155-" + System.nanoTime() + "@test.local");
        Long businessId = jdbc.queryForObject("""
                INSERT INTO core.business (owner_user_id, name, taxation_type, status)
                VALUES (?, '카페 한잔', 'GENERAL', 'ACTIVE') RETURNING id
                """, Long.class, userId);
        jdbc.update("""
                INSERT INTO core.account (business_id, provider, bank_code, account_number_encrypted,
                                          account_number_hash, account_number_masked, name,
                                          status, sync_status, current_balance, balance_as_of)
                VALUES (?, 'FINOPENAPI', '999', 'enc', ?, '999-**-0155', '주계좌',
                        'CONNECTED', 'SUCCESS', 0, now())
                """, businessId, "hash-" + System.nanoTime());
        return businessId;
    }

    /** 명세 7.4 CSV: {@code 거래일시,적요,입금액,출금액,거래후잔액}. 적요에 쉼표가 없는 시드 전용이다. */
    private int load(Path csv, Long businessId, Long accountId) throws Exception {
        List<Object[]> batch = new ArrayList<>();
        try (BufferedReader in = Files.newBufferedReader(csv, StandardCharsets.UTF_8)) {
            String line = in.readLine();          // 헤더
            while ((line = in.readLine()) != null) {
                if (line.isBlank()) {
                    continue;
                }
                String[] c = line.split(",", -1);
                boolean deposit = !c[2].isBlank();
                String at = c[0].trim().replace("﻿", "");
                batch.add(new Object[]{
                        businessId, accountId,
                        java.sql.Timestamp.valueOf(at),
                        java.sql.Date.valueOf(at.substring(0, 10)),
                        deposit ? "DEPOSIT" : "WITHDRAWAL",
                        new BigDecimal(deposit ? c[2] : c[3]),
                        c[1],
                });
            }
        }
        jdbc.batchUpdate("""
                INSERT INTO core.transaction
                    (business_id, account_id, source_type, occurred_at, transaction_date,
                     flow_type, amount, description, classification_status, is_excluded,
                     needs_confirmation, tax_review_status, created_at, updated_at)
                VALUES (?, ?, 'CSV', ?, ?, ?, ?, ?, 'UNCLASSIFIED', false, false,
                        'UNREVIEWED', now(), now())
                """, batch);
        return batch.size();
    }
}
