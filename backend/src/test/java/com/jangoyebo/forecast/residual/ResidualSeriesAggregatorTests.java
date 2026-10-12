package com.jangoyebo.forecast.residual;

import com.jangoyebo.common.domain.FlowType;
import com.jangoyebo.core.TransactionHistory.HistoricalTransaction;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 잔여 현금흐름 집계 (S15P21C105-136). Docker 불필요 — 순수 함수다.
 *
 * <p><b>앞의 세 케이스는 {@code ai/tests/forecast/test_forecast.py} 에서 숫자 그대로 옮겼다.</b>
 * 같은 입력에 같은 값이 나와야 워커에 보내는 시계열이 참조 구현과 갈리지 않는다.
 */
class ResidualSeriesAggregatorTests {

    private static final LocalDate START = LocalDate.of(2026, 6, 1);
    private static final Set<Long> ALL_ACCOUNTS = Set.of();

    @Test
    void 예정거래에_매칭된_건_잔여에서_빠진다() {
        // 잔여 = 실제 − 예정거래 매칭분. 뺄셈이라 이중 계상이 구조적으로 불가능하다.
        List<HistoricalTransaction> txs = List.of(deposit(1L, START, "50000"));

        ResidualSeries without = aggregate(txs, Set.of());
        ResidualSeries withMatch = aggregate(txs, Set.of(1L));

        assertThat(without.deposit().get(0)).isEqualByComparingTo("50000");
        assertThat(withMatch.deposit().get(0)).isEqualByComparingTo("0");
    }

    @Test
    void 예측제외_거래는_잔여로도_안_넘어간다() {
        // is_excluded = true 는 입력을 주는 SQL 이 이미 걸러 준다.
        // 여기서는 「걸러진 것이 들어오지 않는다」를 고정한다 — 빈 입력이면 전부 0 이다.
        ResidualSeries series = aggregate(List.of(), Set.of());

        assertThat(series.withdrawal().get(0)).isEqualByComparingTo("0");
    }

    @Test
    void 거래없는_날은_비지_않고_0이다() {
        // 날짜가 빠지면 뒤의 값이 하루씩 밀려 달력 정렬이 통째로 어긋난다.
        List<HistoricalTransaction> txs = List.of(deposit(1L, START, "1000"));

        ResidualSeries series = ResidualSeriesAggregator.aggregate(
                txs, Set.of(), ALL_ACCOUNTS, START, START.plusDays(4));

        assertThat(series.deposit()).hasSize(5);
        assertThat(series.deposit().get(0)).isEqualByComparingTo("1000");
        assertThat(series.deposit().subList(1, 5))
                .allSatisfy(v -> assertThat(v).isEqualByComparingTo("0"));
    }

    // --- 여기부터는 Java 쪽에서 새로 생긴 조건 -----------------------------------

    @Test
    void 입금과_출금을_따로_센다() {
        List<HistoricalTransaction> txs = List.of(
                deposit(1L, START, "50000"),
                withdrawal(2L, START, "30000"));

        ResidualSeries series = aggregate(txs, Set.of());

        assertThat(series.deposit().get(0)).isEqualByComparingTo("50000");
        assertThat(series.withdrawal().get(0)).isEqualByComparingTo("30000");
    }

    @Test
    void 같은_날_여러_건은_더한다() {
        List<HistoricalTransaction> txs = List.of(
                deposit(1L, START, "10000"),
                deposit(2L, START, "20000"),
                deposit(3L, START, "5000"));

        assertThat(aggregate(txs, Set.of()).deposit().get(0)).isEqualByComparingTo("35000");
    }

    @Test
    void 연결이_끊긴_계좌의_거래는_빼고_센다() {
        // 해제한 계좌는 거래가 남아 있는데 잔액이 시작 잔액에서 빠진다.
        // 그 거래를 잔여에 넣으면 곡선이 한쪽으로 기운다.
        List<HistoricalTransaction> txs = List.of(
                new HistoricalTransaction(1L, 11L, null, START, FlowType.DEPOSIT, new BigDecimal("50000"), "연결됨"),
                new HistoricalTransaction(2L, 99L, null, START, FlowType.DEPOSIT, new BigDecimal("70000"), "해제됨"));

        ResidualSeries series = ResidualSeriesAggregator.aggregate(
                txs, Set.of(), Set.of(11L), START, START);

        assertThat(series.deposit().get(0)).isEqualByComparingTo("50000");
    }

    @Test
    void 계좌를_지정하지_않으면_전부_센다() {
        List<HistoricalTransaction> txs = List.of(
                new HistoricalTransaction(1L, 11L, null, START, FlowType.DEPOSIT, new BigDecimal("50000"), "가"),
                new HistoricalTransaction(2L, 99L, null, START, FlowType.DEPOSIT, new BigDecimal("70000"), "나"));

        assertThat(aggregate(txs, Set.of()).deposit().get(0)).isEqualByComparingTo("120000");
    }

    @Test
    void 창_밖_거래는_무시한다() {
        List<HistoricalTransaction> txs = List.of(
                deposit(1L, START.minusDays(1), "10000"),
                deposit(2L, START, "20000"),
                deposit(3L, START.plusDays(5), "30000"));

        ResidualSeries series = ResidualSeriesAggregator.aggregate(
                txs, Set.of(), ALL_ACCOUNTS, START, START.plusDays(2));

        assertThat(series.length()).isEqualTo(3);
        assertThat(series.totalDeposit()).isEqualByComparingTo("20000");
    }

    @Test
    void 창_길이가_과거_일수다() {
        // basis.residual.historyDays 로 나가고 30일 하한 판정의 근거가 된다.
        ResidualSeries series = ResidualSeriesAggregator.aggregate(
                List.of(), Set.of(), ALL_ACCOUNTS, START, START.plusDays(59));

        assertThat(series.historyDays()).isEqualTo(60);
        assertThat(series.endDate()).isEqualTo(START.plusDays(59));
    }

    @Test
    void 기간이_거꾸로면_거부한다() {
        assertThatThrownBy(() -> ResidualSeriesAggregator.aggregate(
                List.of(), Set.of(), ALL_ACCOUNTS, START, START.minusDays(1)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("앞이다");
    }

    @Test
    void 범위_밖_날짜를_물으면_던진다() {
        // 조용히 0 을 주면 오차가 숨는다.
        ResidualSeries series = aggregate(List.of(), Set.of());

        assertThatThrownBy(() -> series.day(START.plusDays(10)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void 설명한_비율은_매칭_금액의_몫이다() {
        List<HistoricalTransaction> txs = List.of(
                deposit(1L, START, "75000"),
                deposit(2L, START, "25000"));

        assertThat(ResidualSeriesAggregator.explainedRatio(txs, Set.of(1L))).isEqualTo(0.75);
        assertThat(ResidualSeriesAggregator.explainedRatio(txs, Set.of())).isEqualTo(0.0);
        assertThat(ResidualSeriesAggregator.explainedRatio(List.of(), Set.of())).isEqualTo(1.0);
    }

    // --- 헬퍼 -----------------------------------------------------------------

    private static ResidualSeries aggregate(List<HistoricalTransaction> txs, Set<Long> matched) {
        return ResidualSeriesAggregator.aggregate(txs, matched, ALL_ACCOUNTS, START, START);
    }

    private static HistoricalTransaction deposit(long id, LocalDate date, String amount) {
        return new HistoricalTransaction(id, 11L, null, date, FlowType.DEPOSIT, new BigDecimal(amount), "입금");
    }

    private static HistoricalTransaction withdrawal(long id, LocalDate date, String amount) {
        return new HistoricalTransaction(id, 11L, null, date, FlowType.WITHDRAWAL, new BigDecimal(amount), "출금");
    }
}
