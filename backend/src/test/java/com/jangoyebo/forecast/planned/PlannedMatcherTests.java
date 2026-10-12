package com.jangoyebo.forecast.planned;

import com.jangoyebo.common.domain.FlowType;
import com.jangoyebo.core.TransactionHistory.HistoricalTransaction;
import com.jangoyebo.forecast.pattern.CadenceType;
import com.jangoyebo.forecast.pattern.RecurringPatternDetector;
import com.jangoyebo.forecast.planned.PlannedMatcher.Candidate;
import com.jangoyebo.forecast.planned.PlannedMatcher.Match;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 예정 거래 최소 매처 (S15P21C105-136).
 *
 * <p>Docker 불필요. 기준일을 상수로 고정하므로 날짜가 흘러도 결과가 바뀌지 않는다 —
 * {@code RecurringPatternDetectorTests} 와 같은 형태다.
 */
class PlannedMatcherTests {

    private static final LocalDate D = LocalDate.of(2026, 9, 10);

    @Test
    void 날짜와_금액이_맞으면_붙는다() {
        List<Match> matches = PlannedMatcher.match(
                List.of(candidate(1L, D, "100000", "100000", "100000", 3)),
                List.of(tx(50L, D, FlowType.WITHDRAWAL, "100000")),
                Set.of());

        assertThat(matches).hasSize(1);
        assertThat(matches.get(0).plannedId()).isEqualTo(1L);
        assertThat(matches.get(0).transactionId()).isEqualTo(50L);
        assertThat(matches.get(0).dateDiffDays()).isZero();
        assertThat(matches.get(0).amountDiff()).isEqualByComparingTo("0");
    }

    @Test
    void 허용폭_안이면_늦게_들어와도_붙는다() {
        List<Match> matches = PlannedMatcher.match(
                List.of(candidate(1L, D, "100000", "100000", "100000", 3)),
                List.of(tx(50L, D.plusDays(2), FlowType.WITHDRAWAL, "100000")),
                Set.of());

        assertThat(matches).hasSize(1);
        assertThat(matches.get(0).dateDiffDays()).isEqualTo(2);   // 실제일 − 예정일
    }

    @Test
    void 허용폭을_넘으면_안_붙는다() {
        assertThat(PlannedMatcher.match(
                List.of(candidate(1L, D, "100000", "100000", "100000", 1)),
                List.of(tx(50L, D.plusDays(2), FlowType.WITHDRAWAL, "100000")),
                Set.of())).isEmpty();
    }

    @Test
    void 금액이_범위_밖이면_안_붙는다() {
        // 범위 포함으로만 판정한다. 느슨하게 하면 엉뚱한 거래가 붙고
        // 그 오차가 잔여를 통해 곡선에 조용히 들어간다.
        assertThat(PlannedMatcher.match(
                List.of(candidate(1L, D, "90000", "100000", "110000", 3)),
                List.of(tx(50L, D, FlowType.WITHDRAWAL, "110001")),
                Set.of())).isEmpty();
    }

    @Test
    void 범위_경계는_붙는다() {
        assertThat(PlannedMatcher.match(
                List.of(candidate(1L, D, "90000", "100000", "110000", 3)),
                List.of(tx(50L, D, FlowType.WITHDRAWAL, "110000")),
                Set.of())).hasSize(1);
    }

    @Test
    void 방향이_다르면_안_붙는다() {
        assertThat(PlannedMatcher.match(
                List.of(candidate(1L, D, "100000", "100000", "100000", 3)),
                List.of(tx(50L, D, FlowType.DEPOSIT, "100000")),
                Set.of())).isEmpty();
    }

    /**
     * 실측(시드 「김가네」 · 기준일 2026-09-17)에서 실제로 붙었던 두 건이다.
     *
     * <p>방향·계좌·날짜·금액 네 조건을 전부 통과한다 — 금액이 패턴 범위에 들고 허용폭 안이다.
     * 붙은 예정 거래는 곡선에서 빠지므로 <b>나갈 411,100원이 조용히 사라졌다.</b>
     */
    @Test
    void 거래처가_다르면_금액과_날짜가_맞아도_안_붙는다() {
        // 동서식품 예정 −291,100 ← 무등빌딩관리단 −260,000 (이틀 앞, 패턴 범위 안)
        assertThat(PlannedMatcher.match(
                List.of(new Candidate(1L, null, FlowType.WITHDRAWAL, D,
                        new BigDecimal("251500"), new BigDecimal("291100"), new BigDecimal("330700"),
                        3, RecurringPatternDetector.merchantKey("동서식품"))),
                List.of(txOf(50L, "무등빌딩관리단", D.minusDays(2), "260000")),
                Set.of())).isEmpty();

        // 해양도시가스 예정 −120,000 ← SK매직 −120,000 (사흘 앞, 금액이 똑같다)
        assertThat(PlannedMatcher.match(
                List.of(ofMerchant(2L, "해양도시가스", D, "120000")),
                List.of(txOf(51L, "SK매직", D.minusDays(3), "120000")),
                Set.of())).isEmpty();
    }

    @Test
    void 거래처가_같으면_붙는다() {
        assertThat(PlannedMatcher.match(
                List.of(ofMerchant(1L, "해양도시가스", D, "120000")),
                List.of(txOf(50L, "해양도시가스", D.minusDays(1), "120000")),
                Set.of())).hasSize(1);
    }

    /** 적요에 회차·날짜가 붙는다. 탐지가 그것을 지우고 묶었으므로 매칭도 지우고 봐야 한다. */
    @Test
    void 적요의_회차번호는_거래처_판정에서_지운다() {
        assertThat(PlannedMatcher.match(
                List.of(ofMerchant(1L, "카드정산 0912", D, "100000")),
                List.of(txOf(50L, "카드정산 0913", D, "100000")),
                Set.of())).hasSize(1);
    }

    /** 사용자가 적은 이름은 통장 적요와 같을 이유가 없다 — 게이트를 걸면 아무것도 안 붙는다. */
    @Test
    void 사용자_등록분은_거래처를_가리지_않는다() {
        assertThat(PlannedMatcher.match(
                List.of(candidate(1L, D, "100000", "100000", "100000", 3)),
                List.of(txOf(50L, "김영자", D, "100000")),
                Set.of())).hasSize(1);
    }

    @Test
    void 계좌를_지정했으면_같은_계좌만_붙는다() {
        Candidate onAccount11 = new Candidate(1L, 11L, FlowType.WITHDRAWAL, D,
                new BigDecimal("100000"), new BigDecimal("100000"), new BigDecimal("100000"), 3, null);

        assertThat(match(onAccount11, List.of(txOnAccount(50L, 99L, D, "100000")), Set.of())).isEmpty();
        assertThat(match(onAccount11, List.of(txOnAccount(50L, 11L, D, "100000")), Set.of())).hasSize(1);
    }

    @Test
    void 계좌를_지정하지_않으면_어느_계좌든_붙는다() {
        assertThat(PlannedMatcher.match(
                List.of(candidate(1L, D, "100000", "100000", "100000", 3)),
                List.of(txOnAccount(50L, 99L, D, "100000")),
                Set.of())).hasSize(1);
    }

    @Test
    void 이미_매칭된_거래는_다시_안_붙는다() {
        assertThat(PlannedMatcher.match(
                List.of(candidate(1L, D, "100000", "100000", "100000", 3)),
                List.of(tx(50L, D, FlowType.WITHDRAWAL, "100000")),
                Set.of(50L))).isEmpty();
    }

    @Test
    void 한_거래가_두_예정에_붙지_않는다() {
        // uk_pt_matched 가 1:1 을 강제한다. 여기서 안 거르면 INSERT 에서 터진다.
        List<Match> matches = PlannedMatcher.match(
                List.of(candidate(1L, D, "100000", "100000", "100000", 3),
                        candidate(2L, D, "100000", "100000", "100000", 3)),
                List.of(tx(50L, D, FlowType.WITHDRAWAL, "100000")),
                Set.of());

        assertThat(matches).hasSize(1);
        assertThat(matches.get(0).plannedId()).isEqualTo(1L);   // 동점이면 id 순
    }

    @Test
    void 한_예정이_두_거래에_붙지_않는다() {
        List<Match> matches = PlannedMatcher.match(
                List.of(candidate(1L, D, "100000", "100000", "100000", 3)),
                List.of(tx(50L, D, FlowType.WITHDRAWAL, "100000"),
                        tx(51L, D, FlowType.WITHDRAWAL, "100000")),
                Set.of());

        assertThat(matches).hasSize(1);
        assertThat(matches.get(0).transactionId()).isEqualTo(50L);
    }

    @Test
    void 가까운_날짜부터_붙는다() {
        // 탐욕이라 전역 최적은 아니지만 「가장 가까운 짝부터」가 사람의 판단과 맞는다.
        List<Match> matches = PlannedMatcher.match(
                List.of(candidate(1L, D, "100000", "100000", "100000", 3)),
                List.of(tx(50L, D.plusDays(3), FlowType.WITHDRAWAL, "100000"),
                        tx(51L, D.plusDays(1), FlowType.WITHDRAWAL, "100000")),
                Set.of());

        assertThat(matches.get(0).transactionId()).isEqualTo(51L);
    }

    @Test
    void 날짜가_같으면_금액이_가까운_쪽부터_붙는다() {
        List<Match> matches = PlannedMatcher.match(
                List.of(candidate(1L, D, "90000", "100000", "110000", 3)),
                List.of(tx(50L, D, FlowType.WITHDRAWAL, "110000"),
                        tx(51L, D, FlowType.WITHDRAWAL, "101000")),
                Set.of());

        assertThat(matches.get(0).transactionId()).isEqualTo(51L);
        assertThat(matches.get(0).amountDiff()).isEqualByComparingTo("1000");
    }

    @Test
    void 같은_입력은_항상_같은_결과다() {
        List<Candidate> candidates = List.of(
                candidate(1L, D, "90000", "100000", "110000", 3),
                candidate(2L, D.plusDays(1), "90000", "100000", "110000", 3));
        List<HistoricalTransaction> txs = List.of(
                tx(50L, D, FlowType.WITHDRAWAL, "100000"),
                tx(51L, D.plusDays(1), FlowType.WITHDRAWAL, "105000"));

        assertThat(PlannedMatcher.match(candidates, txs, Set.of()))
                .isEqualTo(PlannedMatcher.match(candidates, txs, Set.of()));
    }

    @Test
    void 허용폭은_주기마다_다르다() {
        assertThat(MatchPolicy.toleranceDays(PlannedSourceType.PATTERN, CadenceType.WEEKLY, null)).isEqualTo(1);
        assertThat(MatchPolicy.toleranceDays(PlannedSourceType.PATTERN, CadenceType.BUSINESS_DAILY, null)).isEqualTo(1);
        assertThat(MatchPolicy.toleranceDays(PlannedSourceType.PATTERN, CadenceType.MONTHLY, null)).isEqualTo(3);
        assertThat(MatchPolicy.toleranceDays(PlannedSourceType.USER, null, null)).isEqualTo(3);
    }

    @Test
    void 간격_주기는_간격에_비례하되_잘린다() {
        // 0.25k 를 1~3 으로 자른다. 탐지의 MAX_INTERVAL_DEVIATION 과 같은 값이다.
        assertThat(MatchPolicy.toleranceDays(PlannedSourceType.PATTERN, CadenceType.INTERVAL, 2)).isEqualTo(1);
        assertThat(MatchPolicy.toleranceDays(PlannedSourceType.PATTERN, CadenceType.INTERVAL, 8)).isEqualTo(2);
        assertThat(MatchPolicy.toleranceDays(PlannedSourceType.PATTERN, CadenceType.INTERVAL, 40)).isEqualTo(3);
        assertThat(MatchPolicy.toleranceDays(PlannedSourceType.PATTERN, CadenceType.INTERVAL, null)).isEqualTo(1);
    }

    // --- 헬퍼 -----------------------------------------------------------------

    private static List<Match> match(Candidate candidate, List<HistoricalTransaction> txs, Set<Long> used) {
        return PlannedMatcher.match(List.of(candidate), txs, used);
    }

    /** 거래처를 가리지 않는 후보(사용자 등록분). 거래처 게이트를 보는 시험은 아래 {@link #ofMerchant} 다. */
    private static Candidate candidate(long id, LocalDate date, String min, String expected, String max, int tolerance) {
        return new Candidate(id, null, FlowType.WITHDRAWAL, date,
                new BigDecimal(min), new BigDecimal(expected), new BigDecimal(max), tolerance, null);
    }

    private static Candidate ofMerchant(long id, String merchant, LocalDate date, String amount) {
        return new Candidate(id, null, FlowType.WITHDRAWAL, date,
                new BigDecimal(amount), new BigDecimal(amount), new BigDecimal(amount), 3,
                RecurringPatternDetector.merchantKey(merchant));
    }

    private static HistoricalTransaction txOf(long id, String description, LocalDate date, String amount) {
        return new HistoricalTransaction(id, 11L, null, date, FlowType.WITHDRAWAL,
                new BigDecimal(amount), description);
    }

    private static HistoricalTransaction tx(long id, LocalDate date, FlowType flow, String amount) {
        return new HistoricalTransaction(id, 11L, null, date, flow, new BigDecimal(amount), "적요");
    }

    private static HistoricalTransaction txOnAccount(long id, long accountId, LocalDate date, String amount) {
        return new HistoricalTransaction(id, accountId, null, date, FlowType.WITHDRAWAL,
                new BigDecimal(amount), "적요");
    }
}
