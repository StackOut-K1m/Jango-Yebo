package com.jangoyebo.forecast.residual;

import com.jangoyebo.forecast.engine.BalanceCurveCalculator.ResidualQuantiles;
import com.jangoyebo.forecast.engine.ResidualSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * 잔여 분위수를 어디서 가져올지 정하고, 실패하면 내려간다.
 *
 * <pre>
 * MODEL ──실패·타임아웃·검증 실패·미상주──→ BASELINE ──표본 30일 미만──→ NONE
 * </pre>
 *
 * <p><b>실행은 여기서 절대 실패하지 않는다.</b> 「모델이 실패해도 곡선은 반드시 산출된다」가
 * 인수 조건이다(SRS 13장).
 *
 * <h2>경로는 컨텍스트 길이가 정한다</h2>
 *
 * <p>3번 담당 실측(2026-09-10). 축은 「기록이 몇 년 됐나」가 아니라 <b>「워커에 며칠을
 * 넣었나」</b>다 — {@code historyDays} 하나만 본다.
 *
 * <table>
 *   <tr><th>historyDays</th><th>경로</th><th>근거</th></tr>
 *   <tr><td>&lt; 30</td><td>NONE</td><td>워커가 422 로 거절한다</td></tr>
 *   <tr><td>30 ~ 364</td><td>MODEL</td><td>구간 B — {@code +0.016 [+0.008, +0.024]} 유의</td></tr>
 *   <tr><td>365 (창 상한)</td><td>BASELINE</td><td>구간 C — 게이트 미통과</td></tr>
 * </table>
 *
 * <p>⚠ <b>365 구간은 「통계가 유의하게 낫다」가 아니다.</b> {@code −0.006 [−0.012, +0.001]}
 * 이라 차이가 노이즈 안이고, 최초 부족일 오차는 오히려 모델이 낫다(9.10 → 8.99일). 게이트의
 * 첫 조건({@code F1 > 베이스라인})을 못 넘어 탈락한 것이다. <b>「게이트를 못 넘으면 안 쓴다」는
 * 규칙을 따라</b> 통계로 두고 사유를 사용자에게 알린다.
 *
 * <p><b>임시 경계다.</b> 워커의 {@code LOOKBACK} 을 730 으로 늘려 재학습하면 365 위쪽을
 * 다시 재서 풀 수 있다. 지금은 그 구간 데이터를 모델이 본 적이 없어 열 근거가 없다.
 */
@Component
public class ResidualProvider {

    private static final Logger log = LoggerFactory.getLogger(ResidualProvider.class);

    /** 창이 꽉 찼다는 뜻. 워커의 {@code features.LOOKBACK} 과 같은 값이다. */
    static final int FULL_WINDOW_DAYS = 365;

    private final ResidualForecastClient client;

    public ResidualProvider(ResidualForecastClient client) {
        this.client = client;
    }

    /**
     * @param mode        어디까지 쓸지의 <b>상한</b>. 사용자 토글({@code applyResidualModel})이
     *                    꺼져 있으면 {@code NONE} 이 들어온다
     * @param defaultAlpha 모델을 안 쓸 때의 판정 보수성. 두 경로에 <b>같은 α</b> 를 써야
     *                     채택 비교가 성립한다(설계노트 M-3)
     */
    public Result provide(ResidualSeries series, LocalDate forecastFrom, int horizonDays,
                          Mode mode, BigDecimal defaultAlpha, Long businessId) {
        List<String> warnings = new ArrayList<>();
        int historyDays = series == null ? 0 : series.historyDays();

        if (mode == Mode.NONE) {
            warnings.add("패턴 외 흐름 추정을 끈 상태로 계산했습니다.");
            return none(historyDays, defaultAlpha, warnings);
        }
        if (historyDays < WeekdayMedianBaseline.MIN_SAMPLE_DAYS) {
            warnings.add("거래 기록이 %d일치라 패턴 외 흐름을 추정하기에는 아직 짧습니다 (최소 %d일)."
                    .formatted(historyDays, WeekdayMedianBaseline.MIN_SAMPLE_DAYS));
            return none(historyDays, defaultAlpha, warnings);
        }

        if (mode == Mode.MODEL && historyDays >= FULL_WINDOW_DAYS) {
            // 게이트를 못 넘은 구간이다. 값을 못 내는 것이 아니라 안 쓰기로 한 것이다.
            warnings.add("거래 기록이 1년 이상이라 이 구간은 통계 경로가 채택되었습니다.");
            mode = Mode.BASELINE;
        }

        if (mode == Mode.MODEL) {
            Result model = tryModel(series, forecastFrom, horizonDays, businessId, warnings);
            if (model != null) {
                return model;
            }
            warnings.add("패턴 외 흐름 추정 모델을 쓰지 못해 통계로 계산했습니다.");
        }

        return baseline(series, forecastFrom, horizonDays, defaultAlpha, warnings);
    }

    private Result tryModel(ResidualSeries series, LocalDate forecastFrom, int horizonDays,
                            Long businessId, List<String> warnings) {
        String requestId = "forecast-%d-%s".formatted(businessId, forecastFrom);
        ResidualDtos.Response response =
                client.forecast(ResidualDtos.Request.of(series, horizonDays), requestId);
        if (response == null) {
            return null;
        }
        try {
            ResidualQuantiles quantiles =
                    ResidualResponseValidator.validate(response, forecastFrom, horizonDays);
            ResidualDtos.Response.Data data = response.data();
            log.info("잔여 모델 적용 — 사업장 {} / {} {} / 컨텍스트 {}일 / α {}",
                    businessId, data.modelName(), data.modelVersion(), data.contextDays(),
                    data.judgmentAlpha());
            return new Result(ResidualSource.MODEL, Optional.of(quantiles),
                    // α 는 응답에서 읽는다. 코드에 박으면 재학습 때 가중치와 어긋난다.
                    data.judgmentAlpha(), series.historyDays(),
                    data.modelName(), data.modelVersion(), warnings);
        } catch (ResidualResponseValidator.InvalidResidualResponse e) {
            // 일부만 고쳐 쓰지 않는다 — 어디가 틀렸는지 모르는 값이다.
            log.warn("잔여 모델 응답을 폐기한다 — 사업장 {}: {}", businessId, e.getMessage());
            return null;
        }
    }

    private static Result baseline(ResidualSeries series, LocalDate forecastFrom, int horizonDays,
                                   BigDecimal alpha, List<String> warnings) {
        ResidualQuantiles quantiles =
                WeekdayMedianBaseline.forecast(series, forecastFrom, horizonDays);
        if (quantiles == null) {
            return none(series == null ? 0 : series.historyDays(), alpha, warnings);
        }
        if (series.historyDays() < WeekdayMedianBaseline.DEGRADED_SAMPLE_DAYS) {
            warnings.add("거래 기록이 %d일치라 신뢰 수준을 한 단계 낮췄습니다."
                    .formatted(series.historyDays()));
        }
        return new Result(ResidualSource.BASELINE, Optional.of(quantiles), alpha,
                series.historyDays(), "weekday-median", "0.1", warnings);
    }

    private static Result none(int historyDays, BigDecimal alpha, List<String> warnings) {
        return new Result(ResidualSource.NONE, Optional.empty(), alpha,
                historyDays, null, null, warnings);
    }

    /** 어디까지 쓸지의 상한. 실제 출처는 폴백 사슬이 정한다. */
    public enum Mode {
        NONE, BASELINE, MODEL
    }

    /**
     * @param quantiles 비어 있으면 잔여를 0 으로 둔다
     * @param alpha     실제로 곡선에 적용할 판정 보수성. {@code basis} 에 그대로 기록한다
     */
    public record Result(ResidualSource source,
                         Optional<ResidualQuantiles> quantiles,
                         BigDecimal alpha,
                         int historyDays,
                         String modelName,
                         String modelVersion,
                         List<String> warnings) {

        public boolean applied() {
            return source != ResidualSource.NONE;
        }
    }
}
