package com.jangoyebo.forecast.residual;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * {@code POST /internal/v1/ai/forecast-residual} 요청·응답 (API 20.11).
 *
 * <p><b>요청에는 금액과 날짜만 담는다.</b> 적요·거래처명·계좌번호는 보내지 않는다 —
 * 추론에 필요하지 않고, 보내면 로그 금지 대상만 늘어난다(20.1).
 */
public final class ResidualDtos {

    private ResidualDtos() {
    }

    /**
     * @param series      입금·출금 두 축. 두 축의 {@code startDate} 와 길이가 같아야 한다
     * @param horizonDays 7·14·30 중 하나. 응답 배열 길이와 일치한다
     */
    public record Request(List<Series> series, int horizonDays, List<BigDecimal> quantiles) {

        /** {@code calendar.holidays} 는 선택 필드라 보내지 않는다 — 공휴일 캘린더가 P1-04 다. */
        public static Request of(ResidualSeries s, int horizonDays) {
            return new Request(
                    List.of(new Series("DEPOSIT", s.startDate(), s.deposit()),
                            new Series("WITHDRAWAL", s.startDate(), s.withdrawal())),
                    horizonDays,
                    List.of(new BigDecimal("0.1"), new BigDecimal("0.5"), new BigDecimal("0.9")));
        }

        /** @param values 일별 값. <b>결측일은 0 이며 {@code null} 을 보내지 않는다</b>(20.11) */
        public record Series(String name, LocalDate startDate, List<BigDecimal> values) {
        }
    }

    /**
     * 워커 응답. Envelope 로 감싸여 온다(20.1).
     *
     * <p>{@code @JsonIgnoreProperties} 를 붙인 이유 — 워커가 필드를 더해도 우리가 깨지지
     * 않아야 한다. 계약이 넓어지는 방향은 무해하다.
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Response(Boolean success, Data data, ErrorBody error) {

        @JsonIgnoreProperties(ignoreUnknown = true)
        public record Data(String modelName,
                           String modelVersion,
                           LocalDate startDate,
                           Integer contextDays,
                           Boolean lagAvailable,
                           List<Forecast> forecasts,
                           BigDecimal judgmentAlpha,
                           Long inferenceMs) {
        }

        /** 분위수는 {@code BigDecimal} 로 받는다. {@code double} 로 받으면 곡선 값이 갈린다. */
        @JsonIgnoreProperties(ignoreUnknown = true)
        public record Forecast(String name, List<BigDecimal> q10, List<BigDecimal> q50, List<BigDecimal> q90) {
        }

        @JsonIgnoreProperties(ignoreUnknown = true)
        public record ErrorBody(String code, String message) {
        }
    }
}
