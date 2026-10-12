package com.jangoyebo.forecast.residual;

import com.jangoyebo.forecast.engine.BalanceCurveCalculator.ResidualQuantiles;
import com.jangoyebo.forecast.residual.ResidualDtos.Response;
import com.jangoyebo.forecast.residual.ResidualResponseValidator.InvalidResidualResponse;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 워커 응답 검증 (S15P21C105-137). Docker 불필요.
 *
 * <p>여기서 못 막은 값은 <b>원인에서 아주 먼 곳에서 터진다.</b> 분위수 순서가 뒤집히면
 * 곡선의 {@code min ≤ expected ≤ max} 가 무너지고, DB 의 {@code ck_dbf_range} 가
 * INSERT 에서 잡는다. 그때는 어느 응답이 범인이었는지 남지 않는다.
 */
class ResidualResponseValidatorTests {

    private static final LocalDate START = LocalDate.of(2026, 9, 11);
    private static final int HORIZON = 3;

    @Test
    void 정상_응답을_통과시킨다() {
        ResidualQuantiles q = ResidualResponseValidator.validate(ok(), START, HORIZON);

        assertThat(q.startDate()).isEqualTo(START);
        assertThat(q.deposit()).hasSize(HORIZON);
        assertThat(q.withdrawal()).hasSize(HORIZON);
        assertThat(q.deposit().get(0).q50()).isEqualByComparingTo("200");
    }

    @Test
    void 시작일이_다르면_폐기한다() {
        // 하루 밀리면 요일이 통째로 어긋난다. 값은 멀쩡해 보이므로 여기서 못 잡으면 안 잡힌다.
        Response wrong = withData(data(START.plusDays(1), alpha(), forecasts()));

        assertThatThrownBy(() -> ResidualResponseValidator.validate(wrong, START, HORIZON))
                .isInstanceOf(InvalidResidualResponse.class)
                .hasMessageContaining("예보 시작일");
    }

    @Test
    void 분위수_순서가_뒤집히면_폐기한다() {
        Response inverted = withData(data(START, alpha(), List.of(
                forecast("DEPOSIT", List.of("100", "500", "100"), List.of("200", "200", "200"),
                        List.of("300", "300", "300")),
                forecast("WITHDRAWAL", List.of("0", "0", "0"), List.of("0", "0", "0"),
                        List.of("0", "0", "0")))));

        assertThatThrownBy(() -> ResidualResponseValidator.validate(inverted, START, HORIZON))
                .isInstanceOf(InvalidResidualResponse.class)
                .hasMessageContaining("순서가 뒤집혔다");
    }

    @Test
    void 음수가_있으면_폐기한다() {
        // 잔여는 방향별 합계라 음수가 될 수 없다. 부호가 뒤집힌 축은 곡선을 반대로 민다.
        Response negative = withData(data(START, alpha(), List.of(
                forecast("DEPOSIT", List.of("-1", "100", "100"), List.of("200", "200", "200"),
                        List.of("300", "300", "300")),
                forecast("WITHDRAWAL", List.of("0", "0", "0"), List.of("0", "0", "0"),
                        List.of("0", "0", "0")))));

        assertThatThrownBy(() -> ResidualResponseValidator.validate(negative, START, HORIZON))
                .isInstanceOf(InvalidResidualResponse.class)
                .hasMessageContaining("음수");
    }

    @Test
    void 길이가_예측_기간과_다르면_폐기한다() {
        assertThatThrownBy(() -> ResidualResponseValidator.validate(ok(), START, 30))
                .isInstanceOf(InvalidResidualResponse.class)
                .hasMessageContaining("길이");
    }

    @Test
    void 축이_둘이_아니면_폐기한다() {
        Response oneAxis = withData(data(START, alpha(), List.of(forecasts().get(0))));

        assertThatThrownBy(() -> ResidualResponseValidator.validate(oneAxis, START, HORIZON))
                .isInstanceOf(InvalidResidualResponse.class)
                .hasMessageContaining("축이 둘이 아니다");
    }

    @Test
    void 출금_축_이름이_다르면_폐기한다() {
        Response misnamed = withData(data(START, alpha(), List.of(
                forecasts().get(0),
                forecast("OUTFLOW", List.of("0", "0", "0"), List.of("0", "0", "0"),
                        List.of("0", "0", "0")))));

        assertThatThrownBy(() -> ResidualResponseValidator.validate(misnamed, START, HORIZON))
                .isInstanceOf(InvalidResidualResponse.class)
                .hasMessageContaining("WITHDRAWAL 축이 없다");
    }

    @Test
    void 알파가_범위_밖이면_폐기한다() {
        // α 없이는 곡선을 재현할 수 없다(API 10.1).
        for (String bad : List.of("-0.01", "1.01")) {
            Response response = withData(data(START, new BigDecimal(bad), forecasts()));
            assertThatThrownBy(() -> ResidualResponseValidator.validate(response, START, HORIZON))
                    .isInstanceOf(InvalidResidualResponse.class)
                    .hasMessageContaining("judgmentAlpha");
        }
    }

    @Test
    void 알파가_없으면_폐기한다() {
        Response response = withData(data(START, null, forecasts()));

        assertThatThrownBy(() -> ResidualResponseValidator.validate(response, START, HORIZON))
                .isInstanceOf(InvalidResidualResponse.class)
                .hasMessageContaining("judgmentAlpha");
    }

    @Test
    void 알파_경계값은_통과한다() {
        for (String edge : List.of("0", "1")) {
            Response response = withData(data(START, new BigDecimal(edge), forecasts()));
            assertThat(ResidualResponseValidator.validate(response, START, HORIZON)).isNotNull();
        }
    }

    @Test
    void 값에_널이_있으면_폐기한다() {
        List<BigDecimal> q10 = new ArrayList<>(Arrays.asList(new BigDecimal("100"), null, new BigDecimal("100")));
        Response response = withData(data(START, alpha(), List.of(
                new Response.Forecast("DEPOSIT", q10, decimals("200", "200", "200"),
                        decimals("300", "300", "300")),
                forecasts().get(1))));

        assertThatThrownBy(() -> ResidualResponseValidator.validate(response, START, HORIZON))
                .isInstanceOf(InvalidResidualResponse.class)
                .hasMessageContaining("null 이 있다");
    }

    @Test
    void 실패_응답과_빈_응답을_폐기한다() {
        assertThatThrownBy(() -> ResidualResponseValidator.validate(null, START, HORIZON))
                .isInstanceOf(InvalidResidualResponse.class);
        assertThatThrownBy(() -> ResidualResponseValidator.validate(
                new Response(false, null, new Response.ErrorBody("AI_TIMEOUT", "느림")), START, HORIZON))
                .isInstanceOf(InvalidResidualResponse.class);
        assertThatThrownBy(() -> ResidualResponseValidator.validate(
                new Response(true, null, null), START, HORIZON))
                .isInstanceOf(InvalidResidualResponse.class);
    }

    // --- 헬퍼 -----------------------------------------------------------------

    static Response ok() {
        return withData(data(START, alpha(), forecasts()));
    }

    private static BigDecimal alpha() {
        return new BigDecimal("0.00");
    }

    private static Response withData(Response.Data data) {
        return new Response(true, data, null);
    }

    private static Response.Data data(LocalDate startDate, BigDecimal judgmentAlpha,
                                      List<Response.Forecast> forecasts) {
        return new Response.Data("residual-gru", "2026-09-08", startDate, 365, true,
                forecasts, judgmentAlpha, 120L);
    }

    private static List<Response.Forecast> forecasts() {
        return List.of(
                forecast("DEPOSIT", List.of("100", "100", "100"), List.of("200", "200", "200"),
                        List.of("300", "300", "300")),
                forecast("WITHDRAWAL", List.of("10", "10", "10"), List.of("20", "20", "20"),
                        List.of("30", "30", "30")));
    }

    private static Response.Forecast forecast(String name, List<String> q10, List<String> q50,
                                              List<String> q90) {
        return new Response.Forecast(name,
                decimals(q10.toArray(String[]::new)),
                decimals(q50.toArray(String[]::new)),
                decimals(q90.toArray(String[]::new)));
    }

    private static List<BigDecimal> decimals(String... raw) {
        List<BigDecimal> out = new ArrayList<>(raw.length);
        for (String r : raw) {
            out.add(new BigDecimal(r));
        }
        return out;
    }
}
