package com.jangoyebo.forecast.run;

import com.jangoyebo.forecast.engine.BalanceCurveCalculator.DailyPoint;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;

/**
 * 날짜별 예상 잔액 범위 (예측 곡선 캐시).
 *
 * <p><b>{@code BaseEntity} 도 {@code BusinessScoped} 도 아니다.</b> DDL 에
 * {@code created_at}·{@code updated_at}·{@code business_id} 가 전부 없다 — 실행에 딸린
 * 값이라 사업장은 {@code forecast_run} 을 통해 도달하고, 실행과 함께 만들어져 함께 지워진다
 * ({@code ON DELETE CASCADE}).
 *
 * <p><b>{@code residual_*} 는 {@code expected_*} 에 이미 포함된 내역이다.</b> 곡선을 그릴 때
 * 더하면 이중 계상이다(ERD 1.3). 화면의 "모델 반영분" 표시와 정확도 리포트의 베이스라인
 * 대비 비교에 쓴다.
 */
@Entity
@Table(name = "daily_balance_forecast", schema = "forecast")
public class DailyBalanceForecast {

    /** DB {@code NUMERIC(19,4)}. 넘치면 INSERT 가 거부된다. */
    private static final int AMOUNT_SCALE = 4;

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "forecast_run_id", nullable = false, updatable = false)
    private Long forecastRunId;

    @Column(name = "forecast_date", nullable = false)
    private LocalDate forecastDate;

    @Column(name = "balance_min", nullable = false)
    private BigDecimal balanceMin;

    @Column(name = "balance_expected", nullable = false)
    private BigDecimal balanceExpected;

    @Column(name = "balance_max", nullable = false)
    private BigDecimal balanceMax;

    /** 원장 + 잔여 합계. */
    @Column(name = "expected_deposit", nullable = false)
    private BigDecimal expectedDeposit;

    @Column(name = "expected_withdrawal", nullable = false)
    private BigDecimal expectedWithdrawal;

    /** 위 합계 중 잔여 기여분. 모델 미적용 실행은 0. */
    @Column(name = "residual_deposit", nullable = false)
    private BigDecimal residualDeposit;

    @Column(name = "residual_withdrawal", nullable = false)
    private BigDecimal residualWithdrawal;

    /** 일별 신뢰도 0~1. DB 는 {@code NUMERIC(5,4)} 다. */
    @Column(nullable = false)
    private BigDecimal confidence;

    protected DailyBalanceForecast() {
    }

    /**
     * 곡선의 한 점을 저장 형태로 옮긴다.
     *
     * <p><b>여기서 한 번만 반올림한다.</b> 누적 중에 자르면 30일 누적에서 오차가 벌어져
     * 재현성 인수 조건을 해친다(ERD 11장). {@code HALF_UP} 은 단조라 반올림 뒤에도
     * {@code min ≤ expected ≤ max} 가 보존된다.
     */
    public static DailyBalanceForecast of(Long forecastRunId, DailyPoint point, BigDecimal confidence) {
        DailyBalanceForecast row = new DailyBalanceForecast();
        row.forecastRunId = forecastRunId;
        row.forecastDate = point.date();
        row.balanceMin = scale(point.balanceMin());
        row.balanceExpected = scale(point.balanceExpected());
        row.balanceMax = scale(point.balanceMax());
        row.expectedDeposit = scale(point.expectedDeposit());
        row.expectedWithdrawal = scale(point.expectedWithdrawal());
        row.residualDeposit = scale(point.residualDeposit());
        row.residualWithdrawal = scale(point.residualWithdrawal());
        row.confidence = confidence;
        return row;
    }

    private static BigDecimal scale(BigDecimal value) {
        return value.setScale(AMOUNT_SCALE, RoundingMode.HALF_UP);
    }

    /**
     * 저장된 행을 계산기가 쓰는 모양으로 되돌린다. 조회(API 10.1·10.2)와 알림
     * 재동기화(API 12.1)가 <b>같은 변환</b>을 써야 둘이 같은 구간을 본다.
     *
     * <p>{@code plannedConfidence} 는 1 로 둔다 — 그 값은 행에 저장되지 않고(일별 신뢰도로
     * 접혀 들어있다) 구간 파생에도 쓰이지 않는다.
     */
    public DailyPoint toPoint() {
        return new DailyPoint(forecastDate, balanceMin, balanceExpected, balanceMax,
                expectedDeposit, expectedWithdrawal, residualDeposit, residualWithdrawal, BigDecimal.ONE);
    }

    public Long getId() { return id; }
    public Long getForecastRunId() { return forecastRunId; }
    public LocalDate getForecastDate() { return forecastDate; }
    public BigDecimal getBalanceMin() { return balanceMin; }
    public BigDecimal getBalanceExpected() { return balanceExpected; }
    public BigDecimal getBalanceMax() { return balanceMax; }
    public BigDecimal getExpectedDeposit() { return expectedDeposit; }
    public BigDecimal getExpectedWithdrawal() { return expectedWithdrawal; }
    public BigDecimal getResidualDeposit() { return residualDeposit; }
    public BigDecimal getResidualWithdrawal() { return residualWithdrawal; }
    public BigDecimal getConfidence() { return confidence; }
}
