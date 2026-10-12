package com.jangoyebo.forecast.residual;

import com.jangoyebo.forecast.engine.BalanceCurveCalculator.Quantile;
import com.jangoyebo.forecast.engine.BalanceCurveCalculator.ResidualQuantiles;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * 워커 응답을 믿기 전에 확인한다 (API 20.1 공통 규약 ②).
 *
 * <p><b>하나라도 어긋나면 응답 전체를 폐기한다.</b> 일부만 고쳐 쓰지 않는다 — 어디가
 * 틀렸는지 모르는 값에서 「맞는 부분」을 고르는 것은 추측이다. 폐기하면 통계로 내려가고
 * 곡선은 그대로 나온다.
 *
 * <p><b>단조성 검사가 특히 중요하다.</b> {@code q10 ≤ q50 ≤ q90} 이 깨지면 곡선의
 * {@code min ≤ expected ≤ max} 가 무너지고, 그것은 DB 의 {@code ck_dbf_range} 가
 * INSERT 에서 잡는다 — 원인에서 아주 먼 곳에서 터진다.
 */
public final class ResidualResponseValidator {

    private static final String DEPOSIT = "DEPOSIT";
    private static final String WITHDRAWAL = "WITHDRAWAL";

    private ResidualResponseValidator() {
    }

    /**
     * @param expectedStart 곡선의 첫날(기준일 + 1). 워커의 {@code startDate} 와 같아야 한다
     * @throws InvalidResidualResponse 하나라도 어긋났을 때
     */
    public static ResidualQuantiles validate(ResidualDtos.Response response,
                                             LocalDate expectedStart,
                                             int horizonDays) {
        if (response == null || !Boolean.TRUE.equals(response.success()) || response.data() == null) {
            throw new InvalidResidualResponse("성공 응답이 아니다");
        }
        ResidualDtos.Response.Data data = response.data();

        if (data.startDate() == null || !data.startDate().equals(expectedStart)) {
            // 달력이 하루 밀리면 요일이 통째로 어긋난다. 워커 쪽에서 두 번 났던 버그다.
            throw new InvalidResidualResponse(
                    "예보 시작일이 다르다: %s (기대 %s)".formatted(data.startDate(), expectedStart));
        }
        if (data.judgmentAlpha() == null
                || data.judgmentAlpha().signum() < 0
                || data.judgmentAlpha().compareTo(BigDecimal.ONE) > 0) {
            // α 없이는 곡선을 재현할 수 없다(API 10.1).
            throw new InvalidResidualResponse("judgmentAlpha 가 없거나 [0,1] 밖이다: " + data.judgmentAlpha());
        }
        if (data.forecasts() == null || data.forecasts().size() != 2) {
            throw new InvalidResidualResponse("축이 둘이 아니다");
        }

        List<Quantile> deposit = axis(data.forecasts(), DEPOSIT, horizonDays);
        List<Quantile> withdrawal = axis(data.forecasts(), WITHDRAWAL, horizonDays);
        return new ResidualQuantiles(expectedStart, deposit, withdrawal);
    }

    private static List<Quantile> axis(List<ResidualDtos.Response.Forecast> forecasts,
                                       String name, int horizonDays) {
        ResidualDtos.Response.Forecast found = null;
        for (ResidualDtos.Response.Forecast f : forecasts) {
            if (name.equals(f.name())) {
                found = f;
                break;
            }
        }
        if (found == null) {
            throw new InvalidResidualResponse(name + " 축이 없다");
        }
        requireLength(found.q10(), horizonDays, name, "q10");
        requireLength(found.q50(), horizonDays, name, "q50");
        requireLength(found.q90(), horizonDays, name, "q90");

        List<Quantile> out = new ArrayList<>(horizonDays);
        for (int i = 0; i < horizonDays; i++) {
            BigDecimal q10 = found.q10().get(i);
            BigDecimal q50 = found.q50().get(i);
            BigDecimal q90 = found.q90().get(i);
            if (q10 == null || q50 == null || q90 == null) {
                throw new InvalidResidualResponse("%s[%d] 에 null 이 있다".formatted(name, i));
            }
            if (q10.signum() < 0) {
                // 잔여는 방향별 합계라 음수가 될 수 없다.
                throw new InvalidResidualResponse("%s[%d] 가 음수다: %s".formatted(name, i, q10));
            }
            if (q10.compareTo(q50) > 0 || q50.compareTo(q90) > 0) {
                throw new InvalidResidualResponse(
                        "%s[%d] 분위수 순서가 뒤집혔다: %s / %s / %s".formatted(name, i, q10, q50, q90));
            }
            out.add(new Quantile(q10, q50, q90));
        }
        return out;
    }

    private static void requireLength(List<BigDecimal> values, int horizonDays, String name, String field) {
        if (values == null || values.size() != horizonDays) {
            throw new InvalidResidualResponse("%s.%s 길이가 %d 가 아니다: %s".formatted(
                    name, field, horizonDays, values == null ? "null" : values.size()));
        }
    }

    /** 검증 실패. 밖에서는 「통계로 내려가라」는 신호로만 쓴다. */
    public static class InvalidResidualResponse extends RuntimeException {
        public InvalidResidualResponse(String message) {
            super(message);
        }
    }
}
