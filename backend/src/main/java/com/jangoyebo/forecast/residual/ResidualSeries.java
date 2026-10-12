package com.jangoyebo.forecast.residual;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.List;

/**
 * 일별 잔여 시계열. <b>구멍 없이 하루도 빠짐없이 채운다.</b>
 *
 * <p>{@code ai/app/forecast/residual.py} 의 {@code ResidualSeries} 이식본이다. 워커에 보낼
 * 요청(20.11)의 {@code series.values} 가 이 배열이고, 계약이 "결측일은 0 이며 null 을 보내지
 * 않는다"로 못박혀 있다.
 *
 * @param startDate {@code deposit[0]}·{@code withdrawal[0]} 의 날짜. 이후 1일 간격 연속
 */
public record ResidualSeries(LocalDate startDate, List<BigDecimal> deposit, List<BigDecimal> withdrawal) {

    public ResidualSeries {
        if (deposit.size() != withdrawal.size()) {
            throw new IllegalArgumentException(
                    "입금·출금 축 길이가 다르다: %d vs %d".formatted(deposit.size(), withdrawal.size()));
        }
    }

    public int length() {
        return deposit.size();
    }

    public LocalDate endDate() {
        return startDate.plusDays(length() - 1L);
    }

    /**
     * 워커에 보낼 컨텍스트 일수이자 {@code basis.residual.historyDays} 의 값.
     *
     * <p>30일 미만이면 모델을 태우지 않고, 30~180일이면 신뢰도를 한 단계 내린다
     * (설계노트 「표본 부족 처리」).
     */
    public int historyDays() {
        return length();
    }

    /** 하루치 {@code [입금, 출금]}. 범위 밖이면 던진다 — 조용히 0 을 주면 오차가 숨는다. */
    public BigDecimal[] day(LocalDate date) {
        int i = (int) ChronoUnit.DAYS.between(startDate, date);
        if (i < 0 || i >= length()) {
            throw new IllegalArgumentException(
                    "%s 는 시계열 범위 밖이다 (%s~%s)".formatted(date, startDate, endDate()));
        }
        return new BigDecimal[] {deposit.get(i), withdrawal.get(i)};
    }

    /** 두 축의 합계. 로그와 진단에 쓴다. */
    public BigDecimal totalDeposit() {
        return sum(deposit);
    }

    public BigDecimal totalWithdrawal() {
        return sum(withdrawal);
    }

    private static BigDecimal sum(List<BigDecimal> values) {
        BigDecimal total = BigDecimal.ZERO;
        for (BigDecimal v : values) {
            total = total.add(v);
        }
        return total;
    }
}
