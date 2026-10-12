package com.jangoyebo.forecast.planned;

import com.jangoyebo.NeedsDocker;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 매처를 실제 데이터에 적용한다 (S15P21C105-136).
 *
 * <p>단위 테스트({@code PlannedMatcherTests})가 규칙을 굳히고, 여기서는 <b>DB 를 지나
 * 실제로 저장되는지</b>를 본다. 곡선까지 반영되는지는 {@code ForecastResidualTests} 가 본다 —
 * 거기서만 {@code ForecastRunWorker.execute} 가 보인다.
 */
@NeedsDocker
@SpringBootTest
class PlannedMatchServiceTests {

    private static final LocalDate TODAY = LocalDate.now();

    /** 패턴 이름 = 통장 적요. 탐지기가 적요로 거래를 묶어 패턴을 만들므로 둘이 같은 계보다. */
    private static final String PATTERN_NAME = "우아한형제들";

    @Autowired
    private PlannedMatchService matchService;

    @Autowired
    private PlannedTransactionRepository planned;

    @Autowired
    private JdbcTemplate jdbc;

    private Long businessId;
    private Long accountId;

    @BeforeEach
    void setUp() {
        Long userId = jdbc.queryForObject("""
                INSERT INTO core.users (email, password_hash, name, status)
                VALUES (?, 'hash', '김사장', 'ACTIVE') RETURNING id
                """, Long.class, "match-" + System.nanoTime() + "@test.local");
        businessId = jdbc.queryForObject("""
                INSERT INTO core.business (owner_user_id, name, taxation_type, status)
                VALUES (?, '온기식당', 'GENERAL', 'ACTIVE') RETURNING id
                """, Long.class, userId);
        accountId = jdbc.queryForObject("""
                INSERT INTO core.account (business_id, provider, bank_code, account_number_encrypted,
                                          account_number_hash, account_number_masked, name,
                                          status, sync_status, current_balance, balance_as_of)
                VALUES (?, 'FINOPENAPI', '004', 'enc', ?, '004-**-1234', '주계좌',
                        'CONNECTED', 'SUCCESS', 5000000, now()) RETURNING id
                """, Long.class, businessId, "hash-" + System.nanoTime());
    }

    @Test
    void 붙으면_상태와_오차_두_컬럼이_채워진다() {
        Long plannedId = plannedWithdrawal(TODAY.minusDays(1), "3000000");
        transaction(TODAY, "WITHDRAWAL", "3000000");

        matchService.matchAndCollect(businessId, TODAY);

        PlannedTransaction saved = planned.findById(plannedId).orElseThrow();
        assertThat(saved.getStatus()).isEqualTo(PlannedStatus.MATCHED);
        assertThat(saved.getMatchedTransactionId()).isNotNull();
        assertThat(saved.getMatchedDateDiffDays()).isEqualTo(1);       // 하루 늦게 나갔다
        assertThat(saved.getMatchedAmountDiff()).isEqualByComparingTo("0");
    }

    @Test
    void 매칭된_거래_id_를_돌려준다() {
        plannedWithdrawal(TODAY, "3000000");
        Long txId = transaction(TODAY, "WITHDRAWAL", "3000000");

        Set<Long> matched = matchService.matchAndCollect(businessId, TODAY);

        assertThat(matched).contains(txId);
    }

    @Test
    void 조건이_안_맞으면_아무것도_안_붙는다() {
        plannedWithdrawal(TODAY, "3000000");
        transaction(TODAY, "DEPOSIT", "3000000");   // 방향이 다르다

        assertThat(matchService.matchAndCollect(businessId, TODAY)).isEmpty();
    }

    @Test
    void 두_번_돌려도_한_번_붙은_것을_다시_안_붙인다() {
        Long plannedId = plannedWithdrawal(TODAY, "3000000");
        transaction(TODAY, "WITHDRAWAL", "3000000");

        Set<Long> first = matchService.matchAndCollect(businessId, TODAY);
        Set<Long> second = matchService.matchAndCollect(businessId, TODAY);

        assertThat(second).isEqualTo(first);
        assertThat(planned.findById(plannedId).orElseThrow().getStatus())
                .isEqualTo(PlannedStatus.MATCHED);
    }

    /**
     * <b>닻 재조정</b> — 실제가 하루 늦게 들어오면 앞으로의 예정일도 하루 민다.
     *
     * <p>안 밀면 예정일은 탐지 때의 위상에 남고 실제와의 간격이 회차마다 벌어진다.
     * {@code WEEKLY} 허용폭이 1일이라(→ {@code MatchPolicy}) 다음 회차부터 안 붙고,
     * 못 붙은 예정 거래는 잔여에서 안 빠져 이중 계상된다.
     */
    @Test
    void 실제가_늦게_들어오면_앞으로의_예정일도_민다() {
        Long patternId = pattern("WEEKLY", TODAY.plusDays(6));
        plannedFromPattern(patternId, TODAY.minusDays(1), "880000", "MATCHED 대상");
        Long future1 = plannedFromPattern(patternId, TODAY.plusDays(6), "880000", "다음 회차");
        Long future2 = plannedFromPattern(patternId, TODAY.plusDays(13), "880000", "그다음 회차");
        transaction(TODAY, "DEPOSIT", "880000");           // 예정보다 하루 늦다

        matchService.matchAndCollect(businessId, TODAY);

        assertThat(planned.findById(future1).orElseThrow().getExpectedDate())
                .isEqualTo(TODAY.plusDays(7));
        assertThat(planned.findById(future2).orElseThrow().getExpectedDate())
                .isEqualTo(TODAY.plusDays(14));
        // 공휴일 보정이 걸린 것으로 읽히면 안 된다 — 둘이 같이 움직여야 한다.
        assertThat(planned.findById(future1).orElseThrow().getOriginalExpectedDate())
                .isEqualTo(TODAY.plusDays(7));
    }

    /** 영업일 반복은 매일 한 건이라 옮길 위상이 없다. 날짜로 밀면 주말에 얹힌다. */
    @Test
    void 영업일_반복은_닻을_옮기지_않는다() {
        Long patternId = pattern("BUSINESS_DAILY", TODAY.plusDays(1));
        plannedFromPattern(patternId, TODAY.minusDays(1), "300000", "MATCHED 대상");
        Long future = plannedFromPattern(patternId, TODAY.plusDays(1), "300000", "다음 회차");
        transaction(TODAY, "DEPOSIT", "300000");

        matchService.matchAndCollect(businessId, TODAY);

        assertThat(planned.findById(future).orElseThrow().getExpectedDate())
                .isEqualTo(TODAY.plusDays(1));
    }

    /** 오차가 0 이면 건드릴 것이 없다 — 쓸데없는 UPDATE 를 내지 않는다. */
    @Test
    void 제날짜에_들어오면_아무것도_안_민다() {
        Long patternId = pattern("WEEKLY", TODAY.plusDays(7));
        plannedFromPattern(patternId, TODAY, "880000", "MATCHED 대상");
        Long future = plannedFromPattern(patternId, TODAY.plusDays(7), "880000", "다음 회차");
        transaction(TODAY, "DEPOSIT", "880000");

        matchService.matchAndCollect(businessId, TODAY);

        assertThat(planned.findById(future).orElseThrow().getExpectedDate())
                .isEqualTo(TODAY.plusDays(7));
    }

    /**
     * <b>투영 범위가 좁아도 매칭은 패턴 전체 폭으로 한다.</b>
     *
     * <p>요일별 금액(MR !76)이 {@code BUSINESS_DAILY} 의 예정 금액을 q10~q90 으로 좁혔는데,
     * 같은 값이 매칭 필터로도 쓰여 범위 밖 거래가 안 붙었다 — 실측에서 신한카드 124건 중
     * 20건이 자기 요일 범위 밖이다. 안 붙으면 잔여로 새어 이중 계상된다.
     */
    @Test
    void 투영_범위_밖이어도_패턴_범위_안이면_붙는다() {
        Long patternId = pattern("BUSINESS_DAILY", TODAY, "110600", "582100");   // 관측 전체 폭
        Long plannedId = plannedFromPattern(patternId, TODAY, "394300", "요일별 q10~q90");
        jdbc.update("UPDATE forecast.planned_transaction SET amount_min = 342600, amount_max = 437600"
                + " WHERE id = ?", plannedId);
        transaction(TODAY, "DEPOSIT", "150000");        // 투영 범위 밖 · 패턴 범위 안

        matchService.matchAndCollect(businessId, TODAY);

        assertThat(planned.findById(plannedId).orElseThrow().getStatus())
                .isEqualTo(PlannedStatus.MATCHED);
    }

    /**
     * <b>거래처가 다르면 안 붙는다</b> — 방향·계좌·날짜·금액이 다 맞아도.
     *
     * <p>실측(시드 「김가네」 · 기준일 2026-09-17)에서 실제로 붙었다 — 해양도시가스 예정
     * 120,000원이 사흘 앞 SK매직 120,000원에 붙었고, 동서식품 예정 291,100원이 무등빌딩관리단
     * 260,000원에 붙었다. 붙은 예정 거래는 곡선에서 빠지므로 <b>나갈 411,100원이 조용히
     * 사라져</b> 예보가 그만큼 후해졌다.
     */
    @Test
    void 거래처가_다르면_패턴_예정_거래에_안_붙는다() {
        Long patternId = pattern("WEEKLY", TODAY);
        Long plannedId = plannedFromPattern(patternId, TODAY, "880000", "우아한형제들");
        transaction(TODAY, "DEPOSIT", "880000", "SK매직");

        assertThat(matchService.matchAndCollect(businessId, TODAY)).isEmpty();
        assertThat(planned.findById(plannedId).orElseThrow().getStatus())
                .isEqualTo(PlannedStatus.EXPECTED);
    }

    /** 넓히기만 한다 — 패턴 범위 밖이면 여전히 안 붙는다. */
    @Test
    void 패턴_범위_밖이면_안_붙는다() {
        Long patternId = pattern("BUSINESS_DAILY", TODAY, "110600", "582100");
        Long plannedId = plannedFromPattern(patternId, TODAY, "394300", "요일별 q10~q90");
        transaction(TODAY, "DEPOSIT", "9000000");       // 패턴 범위도 벗어난다

        matchService.matchAndCollect(businessId, TODAY);

        assertThat(planned.findById(plannedId).orElseThrow().getStatus())
                .isEqualTo(PlannedStatus.EXPECTED);
    }

    // --- 헬퍼 -----------------------------------------------------------------

    private Long pattern(String cadence, LocalDate nextExpected) {
        return pattern(cadence, nextExpected, "880000", "880000");
    }

    private Long pattern(String cadence, LocalDate nextExpected, String min, String max) {
        return jdbc.queryForObject("""
                INSERT INTO forecast.recurring_pattern
                    (business_id, account_id, name, flow_type, cadence_type, holiday_rule,
                     expected_amount_min, expected_amount_max, next_expected_date,
                     confidence, status, created_at, updated_at)
                VALUES (?, ?, ?, 'DEPOSIT', ?, 'NEXT_BUSINESS_DAY',
                        ?, ?, ?, 0.9, 'CONFIRMED', now(), now())
                RETURNING id
                """, Long.class, businessId, accountId, PATTERN_NAME, cadence,
                new BigDecimal(min), new BigDecimal(max), nextExpected);
    }

    private Long plannedFromPattern(Long patternId, LocalDate date, String amount, String name) {
        return jdbc.queryForObject("""
                INSERT INTO forecast.planned_transaction
                    (business_id, account_id, source_type, recurring_pattern_id, name,
                     expected_date, original_expected_date, flow_type,
                     amount_expected, amount_min, amount_max, status, version, created_at, updated_at)
                VALUES (?, ?, 'PATTERN', ?, ?, ?, ?, 'DEPOSIT', ?, ?, ?, 'EXPECTED', 0, now(), now())
                RETURNING id
                """, Long.class, businessId, accountId, patternId, name, date, date,
                new BigDecimal(amount), new BigDecimal(amount), new BigDecimal(amount));
    }

    private Long plannedWithdrawal(LocalDate date, String amount) {
        return jdbc.queryForObject("""
                INSERT INTO forecast.planned_transaction
                    (business_id, account_id, source_type, name, expected_date, original_expected_date,
                     flow_type, amount_expected, amount_min, amount_max, status, version, created_at, updated_at)
                VALUES (?, ?, 'USER', '급여', ?, ?, 'WITHDRAWAL', ?, ?, ?, 'CONFIRMED', 0, now(), now())
                RETURNING id
                """, Long.class, businessId, accountId, date, date,
                new BigDecimal(amount), new BigDecimal(amount), new BigDecimal(amount));
    }

    /**
     * 적요를 {@link #PATTERN_NAME} 으로 둔다 — 패턴이 만든 예정 거래는 <b>거래처가 같아야</b>
     * 붙는다({@code PlannedMatcher}). 적요가 다른 경우는
     * {@link #거래처가_다르면_패턴_예정_거래에_안_붙는다} 가 따로 본다.
     */
    private Long transaction(LocalDate date, String flowType, String amount) {
        return transaction(date, flowType, amount, PATTERN_NAME);
    }

    private Long transaction(LocalDate date, String flowType, String amount, String description) {
        return jdbc.queryForObject("""
                INSERT INTO core.transaction
                    (business_id, account_id, source_type, occurred_at, transaction_date, flow_type,
                     amount, description, classification_status, is_excluded, created_at, updated_at)
                VALUES (?, ?, 'CSV', ?, ?, ?, ?, ?, 'UNCLASSIFIED', false, now(), now())
                RETURNING id
                """, Long.class, businessId, accountId,
                java.sql.Timestamp.valueOf(date.atStartOfDay()), date, flowType,
                new BigDecimal(amount), description);
    }
}
