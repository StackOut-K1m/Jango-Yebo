package com.jangoyebo.forecast.run;

import com.jangoyebo.forecast.engine.BalanceCurveCalculator.CurveInput;
import com.jangoyebo.forecast.engine.BalanceCurveCalculator.DailyPoint;
import com.jangoyebo.forecast.engine.BalanceCurveCalculator.PlannedItem;
import com.jangoyebo.forecast.engine.BalanceCurveCalculator.Quantile;
import com.jangoyebo.forecast.engine.BalanceCurveCalculator.ResidualQuantiles;
import com.jangoyebo.forecast.engine.ConfidencePolicy;
import com.jangoyebo.forecast.engine.ResidualSource;
import com.jangoyebo.forecast.pattern.ConfidenceBand;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * 최신 완료 실행과 조회 창 — 곡선(10.1)·공백(10.2)·시나리오(11장)가 함께 읽는다.
 *
 * <p>{@link ForecastQueryService} 안의 private record 였던 것을 꺼냈다. 시나리오가 같은
 * 실행 위에서 계산해야 <b>원본 곡선이 대시보드와 같은 숫자</b>가 되기 때문이다 — 「원본
 * 대비 얼마나 나빠지나」를 보는 화면인데 그 원본이 대시보드와 다르면 비교가 성립하지 않는다.
 */
public record ForecastWindow(ForecastRun run, List<DailyBalanceForecast> rows) {

    /**
     * 기준일. 저장된 {@code startDate} 는 <b>기준일 다음 날</b>이다.
     *
     * <p>{@code baseAt} 에서 날짜를 뽑지 않는다 — 그쪽은 UTC {@code Instant} 라 KST 자정
     * 근처에서 하루가 어긋난다. 실행을 만들 때 이미 사업장 벽시계로 판정한 값이 여기 있다.
     */
    public LocalDate baseDate() {
        return run.getStartDate().minusDays(1);
    }

    /** 이 계산이 언제 것인지 (API {@code baseTime}). */
    public Instant baseTime() {
        return run.getBaseAt();
    }

    public List<DailyPoint> points() {
        List<DailyPoint> points = new ArrayList<>(rows.size());
        for (DailyBalanceForecast row : rows) {
            points.add(row.toPoint());
        }
        return points;
    }

    /** 실행 시점의 예정 거래 스냅샷. 원본이 그 뒤 수정돼도 근거가 흔들리지 않는다. */
    public List<PlannedItem> ledgerSnapshot() {
        return run.getBasis().ledger();
    }

    public String nameOf(Long plannedTransactionId) {
        return run.getBasis().nameOf(plannedTransactionId);
    }

    public List<String> warnings() {
        List<String> warnings = run.getBasis().warnings();
        return warnings == null ? List.of() : warnings;
    }

    /**
     * 등급은 <b>조회한 창</b>의 일별 값으로 다시 계산한다 — 7일 조회가 30일보다 높게 나올 수
     * 있고 그것이 의도다(공통규약 23장).
     */
    public ConfidenceBand confidence() {
        ForecastBasis basis = run.getBasis();
        List<BigDecimal> values = new ArrayList<>(rows.size());
        for (DailyBalanceForecast row : rows) {
            values.add(row.getConfidence());
        }
        ResidualSource source = basis.residual() == null
                ? ResidualSource.NONE
                : ResidualSource.valueOf(basis.residual().source());
        int plannedCount = basis.items() == null ? 0 : basis.items().size();
        boolean balanceKnown = basis.engine() == null || basis.engine().balanceKnown();
        return ConfidencePolicy.level(values, source, plannedCount, balanceKnown);
    }

    /**
     * 저장된 실행을 계산기 입력으로 되돌린다 — <b>시나리오가 원장만 바꿔 다시 돌리기 위해서다.</b>
     *
     * <p>{@code planned} 만 갈아 끼우면 나머지(시작 잔액·잔여·α)는 그대로이므로, 파라미터가
     * 아무것도 바꾸지 않으면 결과가 저장된 곡선과 같다. 그것이 「원본 곡선」의 정의다.
     *
     * @param planned 변형한 원장. 원본 곡선을 낼 때는 {@link #ledgerSnapshot()} 을 그대로 넣는다
     */
    public CurveInput toCurveInput(List<PlannedItem> planned) {
        ForecastBasis.Residual residual = run.getBasis().residual();
        return new CurveInput(baseDate(), rows.size(), run.openingBalanceTotal(), planned,
                storedResidual(), residual == null ? null : residual.judgmentAlpha());
    }

    /**
     * 저장된 일별 잔여를 <b>퇴화 분위수</b>로 되돌린다.
     *
     * <p>{@code basis} 에는 잔여 합계만 있고 일별 분위수는 남지 않는다. 대신
     * {@code daily_balance_forecast} 에 <b>α 를 적용한 뒤의 대표값</b>이 날짜별로 있으므로
     * 그것을 {@code q10 = q50 = q90} 으로 감싼다. 계산기의 혼합식이
     * {@code q50 + α(q10 − q50)} 이라 세 값이 같으면 α 와 무관하게 그 값이 그대로 나온다 —
     * 즉 <b>대표선이 정확히 재현된다.</b>
     *
     * <p>밴드({@code balanceMin}/{@code balanceMax})는 이렇게 하면 좁아지지만 시나리오 응답은
     * 밴드를 내보내지 않는다(11.1 의 곡선 점은 날짜와 예상 잔액 두 필드뿐이다). 밴드를 되살리려면
     * 일별 분위수를 실행에 저장해야 하는데, 쓰지도 않을 값을 위해 저장 구조를 바꾸지 않는다.
     */
    private Optional<ResidualQuantiles> storedResidual() {
        List<Quantile> deposit = new ArrayList<>(rows.size());
        List<Quantile> withdrawal = new ArrayList<>(rows.size());
        for (DailyBalanceForecast row : rows) {
            deposit.add(flat(row.getResidualDeposit()));
            withdrawal.add(flat(row.getResidualWithdrawal()));
        }
        return Optional.of(new ResidualQuantiles(baseDate().plusDays(1), deposit, withdrawal));
    }

    private static Quantile flat(BigDecimal value) {
        BigDecimal v = value == null ? BigDecimal.ZERO : value;
        return new Quantile(v, v, v);
    }
}
