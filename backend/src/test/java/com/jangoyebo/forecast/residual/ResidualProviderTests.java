package com.jangoyebo.forecast.residual;

import com.jangoyebo.common.ai.AiProperties;
import com.jangoyebo.forecast.engine.ResidualSource;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 폴백 사슬 (S15P21C105-137). Docker 불필요.
 *
 * <pre>
 * MODEL ──실패·타임아웃·검증 실패·미상주──→ BASELINE ──표본 30일 미만──→ NONE
 * </pre>
 *
 * <p><b>어느 갈래로 가든 예측은 실패하지 않는다.</b> 「모델이 실패해도 곡선은 반드시
 * 산출된다」가 인수 조건이다(SRS 13장). 이 테스트가 지키는 것은 그 한 문장이다.
 */
class ResidualProviderTests {

    /** 2026-06-01 은 월요일. 요일 묶음을 셈하기 쉽게 여기서 시작한다. */
    private static final LocalDate SERIES_START = LocalDate.of(2026, 6, 1);
    private static final BigDecimal DEFAULT_ALPHA = new BigDecimal("0.00");
    private static final int HORIZON = 3;
    private static final Long BUSINESS_ID = 11L;

    @Test
    void 모델이_응답하면_모델을_쓴다() {
        StubClient client = respondingFor(200);

        ResidualProvider.Result result = provide(client, series(200), ResidualProvider.Mode.MODEL);

        assertThat(result.source()).isEqualTo(ResidualSource.MODEL);
        assertThat(result.applied()).isTrue();
        assertThat(result.modelName()).isEqualTo("residual-gru");
        assertThat(result.modelVersion()).isEqualTo("2026-09-08");
        assertThat(result.quantiles()).isPresent();
        assertThat(result.quantiles().orElseThrow().deposit()).hasSize(HORIZON);
        assertThat(client.calls).isEqualTo(1);
    }

    @Test
    void 알파는_응답에서_읽는다() {
        // 코드에 박으면 재학습 때 가중치와 어긋난다 — 곡선이 조용히 틀린 보수성으로 그려진다.
        StubClient client = StubClient.returning(
                response(forecastFrom(200), new BigDecimal("0.40")));

        ResidualProvider.Result result = new ResidualProvider(client).provide(
                series(200), forecastFrom(200), HORIZON,
                ResidualProvider.Mode.MODEL, DEFAULT_ALPHA, BUSINESS_ID);

        assertThat(result.source()).isEqualTo(ResidualSource.MODEL);
        assertThat(result.alpha()).isEqualByComparingTo("0.40");
    }

    @Test
    void 모델이_침묵하면_통계로_내려간다() {
        StubClient client = StubClient.returning(null);

        ResidualProvider.Result result = provide(client, series(200), ResidualProvider.Mode.MODEL);

        assertThat(result.source()).isEqualTo(ResidualSource.BASELINE);
        assertThat(result.quantiles()).isPresent();
        assertThat(result.modelName()).isEqualTo("weekday-median");
        assertThat(result.warnings()).anyMatch(w -> w.contains("통계로 계산했습니다"));
    }

    @Test
    void 응답이_검증을_통과하지_못하면_통계로_내려간다() {
        // 시작일이 하루 어긋난 응답. 값은 멀쩡해 보이지만 일부만 고쳐 쓰지 않는다.
        StubClient client = StubClient.returning(
                response(forecastFrom(200).plusDays(1), DEFAULT_ALPHA));

        ResidualProvider.Result result = provide(client, series(200), ResidualProvider.Mode.MODEL);

        assertThat(result.source()).isEqualTo(ResidualSource.BASELINE);
        assertThat(result.warnings()).anyMatch(w -> w.contains("통계로 계산했습니다"));
    }

    @Test
    void 기록이_일년치면_통계를_고른다() {
        // 게이트를 못 넘은 구간이다. 값을 못 내는 것이 아니라 안 쓰기로 한 것이다.
        int days = ResidualProvider.FULL_WINDOW_DAYS;
        StubClient client = respondingFor(days);

        ResidualProvider.Result result = provide(client, series(days), ResidualProvider.Mode.MODEL);

        assertThat(result.source()).isEqualTo(ResidualSource.BASELINE);
        assertThat(client.calls).isZero();
        assertThat(result.warnings()).anyMatch(w -> w.contains("1년 이상"));
    }

    @Test
    void 일년에서_하루_모자라면_모델을_쓴다() {
        // 경계가 어디인지가 곧 채택 근거다. 364 는 게이트를 넘은 구간 B 다.
        int days = ResidualProvider.FULL_WINDOW_DAYS - 1;
        StubClient client = respondingFor(days);

        ResidualProvider.Result result = provide(client, series(days), ResidualProvider.Mode.MODEL);

        assertThat(result.source()).isEqualTo(ResidualSource.MODEL);
        assertThat(client.calls).isEqualTo(1);
    }

    @Test
    void 표본이_삼십일_미만이면_잔여를_쓰지_않는다() {
        StubClient client = respondingFor(29);

        ResidualProvider.Result result = provide(client, series(29), ResidualProvider.Mode.MODEL);

        assertThat(result.source()).isEqualTo(ResidualSource.NONE);
        assertThat(result.applied()).isFalse();
        assertThat(result.quantiles()).isEmpty();
        assertThat(result.historyDays()).isEqualTo(29);
        assertThat(client.calls).isZero();
        assertThat(result.warnings()).anyMatch(w -> w.contains("최소 30일"));
    }

    @Test
    void 시계열이_없어도_실패하지_않는다() {
        ResidualProvider.Result result =
                provide(StubClient.returning(null), null, ResidualProvider.Mode.MODEL);

        assertThat(result.source()).isEqualTo(ResidualSource.NONE);
        assertThat(result.historyDays()).isZero();
    }

    @Test
    void 토글을_끄면_모델도_통계도_쓰지_않는다() {
        // 인수 조건 — 끈 상태에서도 곡선과 부족일은 정상 산출된다.
        StubClient client = respondingFor(200);

        ResidualProvider.Result result = provide(client, series(200), ResidualProvider.Mode.NONE);

        assertThat(result.source()).isEqualTo(ResidualSource.NONE);
        assertThat(result.quantiles()).isEmpty();
        assertThat(client.calls).isZero();
        assertThat(result.warnings()).anyMatch(w -> w.contains("끈 상태"));
    }

    @Test
    void 상한이_통계면_모델을_부르지_않는다() {
        StubClient client = respondingFor(200);

        ResidualProvider.Result result = provide(client, series(200), ResidualProvider.Mode.BASELINE);

        assertThat(result.source()).isEqualTo(ResidualSource.BASELINE);
        assertThat(client.calls).isZero();
    }

    @Test
    void 모델을_안_쓰면_설정된_알파를_쓴다() {
        // 두 경로에 같은 α 를 써야 채택 비교가 성립한다(설계노트 M-3).
        ResidualProvider.Result result = new ResidualProvider(StubClient.returning(null))
                .provide(series(200), forecastFrom(200), HORIZON,
                        ResidualProvider.Mode.MODEL, new BigDecimal("0.25"), BUSINESS_ID);

        assertThat(result.source()).isEqualTo(ResidualSource.BASELINE);
        assertThat(result.alpha()).isEqualByComparingTo("0.25");
    }

    @Test
    void 기록이_짧으면_신뢰_수준을_낮춘_사유를_남긴다() {
        ResidualProvider.Result shortHistory =
                provide(StubClient.returning(null), series(90), ResidualProvider.Mode.BASELINE);
        ResidualProvider.Result longHistory =
                provide(StubClient.returning(null), series(200), ResidualProvider.Mode.BASELINE);

        assertThat(shortHistory.warnings()).anyMatch(w -> w.contains("한 단계 낮췄습니다"));
        assertThat(longHistory.warnings()).noneMatch(w -> w.contains("한 단계 낮췄습니다"));
    }

    // --- 헬퍼 -----------------------------------------------------------------

    private static ResidualProvider.Result provide(ResidualForecastClient client,
                                                   ResidualSeries series,
                                                   ResidualProvider.Mode mode) {
        int days = series == null ? 0 : series.historyDays();
        return new ResidualProvider(client)
                .provide(series, forecastFrom(days), HORIZON, mode, DEFAULT_ALPHA, BUSINESS_ID);
    }

    /** 예보 첫날은 시계열 마지막 날의 다음 날이다. 검증기가 이 값을 그대로 확인한다. */
    private static LocalDate forecastFrom(int historyDays) {
        return SERIES_START.plusDays(historyDays);
    }

    private static StubClient respondingFor(int historyDays) {
        return StubClient.returning(response(forecastFrom(historyDays), DEFAULT_ALPHA));
    }

    private static ResidualSeries series(int days) {
        List<BigDecimal> deposit = new ArrayList<>(days);
        List<BigDecimal> withdrawal = new ArrayList<>(days);
        for (int i = 0; i < days; i++) {
            deposit.add(new BigDecimal(String.valueOf(100_000 + (i % 7) * 1_000)));
            withdrawal.add(new BigDecimal(String.valueOf(50_000 + (i % 5) * 500)));
        }
        return new ResidualSeries(SERIES_START, deposit, withdrawal);
    }

    /** {@link #HORIZON} 일치 정상 응답. */
    private static ResidualDtos.Response response(LocalDate startDate, BigDecimal alpha) {
        return new ResidualDtos.Response(true, new ResidualDtos.Response.Data(
                "residual-gru", "2026-09-08", startDate, 365, true,
                List.of(axis("DEPOSIT", "100", "200", "300"), axis("WITHDRAWAL", "10", "20", "30")),
                alpha, 120L), null);
    }

    private static ResidualDtos.Response.Forecast axis(String name, String q10, String q50, String q90) {
        return new ResidualDtos.Response.Forecast(name, flat(q10), flat(q50), flat(q90));
    }

    private static List<BigDecimal> flat(String value) {
        List<BigDecimal> out = new ArrayList<>(HORIZON);
        for (int i = 0; i < HORIZON; i++) {
            out.add(new BigDecimal(value));
        }
        return out;
    }

    /**
     * 호출을 세고 정해진 답을 돌려준다.
     *
     * <p>{@code AiProperties} 는 클라이언트가 {@code RestClient} 를 조립하는 데만 쓴다 —
     * 조립된 클라이언트는 여기서 한 번도 쓰이지 않는다. 그래서 부를 수 없는 주소를 준다.
     */
    private static final class StubClient extends ResidualForecastClient {

        private final ResidualDtos.Response response;
        private int calls;

        private StubClient(ResidualDtos.Response response) {
            super(new AiProperties("http://localhost:9998", "test-token",
                    new AiProperties.Client(Duration.ofSeconds(1), Duration.ofSeconds(1),
                            Duration.ofSeconds(1)),
                    new AiProperties.Callback(Duration.ofHours(24), Duration.ofMinutes(5))));
            this.response = response;
        }

        static StubClient returning(ResidualDtos.Response response) {
            return new StubClient(response);
        }

        @Override
        public ResidualDtos.Response forecast(ResidualDtos.Request request, String requestId) {
            calls++;
            return response;
        }
    }
}
