package com.jangoyebo.forecast.engine;

import com.jangoyebo.common.domain.FlowType;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Layer 1 — 회계 항등식 (P0-07).
 *
 * <pre>
 * 잔액(d) = 시작잔액 + Σ 입금(~d) − Σ 지출(~d)
 * </pre>
 *
 * <p><b>순수 함수다.</b> DB·HTTP·시계를 건드리지 않고 <b>모델 출력조차 인자로 주입받는다</b>
 * ({@code residual}). {@code forecast/package-info.java} 가 계산 엔진을 이렇게 유지하기로 한
 * 이유는 둘이다 — 인수 조건 「동일 입력 + 동일 modelVersion 에 대해 재현된다」(API 10.1)를
 * 단위 테스트로 증명해야 하고, <b>시나리오(P0-09)가 같은 함수를 재사용</b>하기 때문이다.
 * What-if 는 {@code planned} 를 변형해 다시 부르면 된다.
 *
 * <h2>3층 중 어디인가</h2>
 *
 * <p>설계노트 M-1 의 Layer 1 이다. Layer 2-A(원장)는 {@code planned}, Layer 2-B(잔여)는
 * {@code residual} 로 들어온다. <b>매출 레이어는 없다</b> — 잔여는 매출이 아니라
 * 「원장이 설명하지 못한 나머지」이고, 그 정의가 뺄셈이라 원장 항목이 늘면 자동으로 준다.
 *
 * <h2>왜 곡선이 기준일 다음 날부터인가</h2>
 *
 * <p>API 10.1 예시가 {@code baseTime} 8/31 에 {@code curve[0]} 9/1 이고, 워커 응답의
 * {@code startDate}(20.11)도 입력 시계열 바로 다음 날이다. 기준일 당일은 이미 지나간 날이라
 * 시작 잔액에 반영돼 있다.
 *
 * <p><b>단 그 전제는 실제로 일어난 거래에만 참이다.</b> 기준일 당일자 예정 거래는 아직
 * 일어나지 않아 시작 잔액에 없고, 곡선에는 당일 점이 없어 양쪽 모두에서 빠졌다 —
 * 곡선 전 구간이 그 금액만큼 높게 나왔다. 그래서 <b>첫 점이 기준일 당일까지 끌어안는다.</b>
 * 딥은 하루 늦게 찍히지만 D+1 이후 레벨이 맞는다.
 */
public final class BalanceCurveCalculator {

    /**
     * 계산 규칙 버전. {@code FORECAST_RUN.model_version} 의 앞부분
     * ({@code engine-<x.y>/<model>-<ver>} — API 10.1)이 된다.
     *
     * <p><b>누적 규칙이나 분위수 합성 방식을 바꾸면 올린다.</b> 재현성 보증의 단위가
     * 「같은 입력 + 같은 {@code modelVersion}」이므로, 규칙이 바뀌었는데 버전이 그대로면
     * 옛 실행과 새 실행이 같은 값을 낸다고 잘못 말하게 된다.
     *
     * <p>1.1 — 첫 점이 기준일 당일자 예정 거래를 합산한다.
     * <p>1.2 — 표시용 밴드를 커버리지 80% 로 보정한다 ({@link #coverageShrink}).
     *          대표선과 공백 판정은 1.1 과 같은 값이다.
     */
    public static final String ENGINE_VERSION = "1.2";

    /** 7일차까지의 폭 배율. {@link #coverageShrink} 에 근거가 있다. */
    private static final BigDecimal SHRINK_7 = new BigDecimal("0.42");
    /** 14일차 배율. */
    private static final BigDecimal SHRINK_14 = new BigDecimal("0.32");
    /** 30일차 배율. 창 상한이라 그 뒤는 같은 값을 쓴다. */
    private static final BigDecimal SHRINK_30 = new BigDecimal("0.23");

    private BalanceCurveCalculator() {
    }

    /**
     * 일별 곡선을 만든다.
     *
     * @throws IllegalArgumentException 기간이 1일 미만이거나 잔여 시계열 길이가 {@code horizonDays} 와 다를 때
     */
    public static Curve compute(CurveInput in) {
        if (in.horizonDays() < 1) {
            throw new IllegalArgumentException("horizonDays 는 1 이상이어야 한다: " + in.horizonDays());
        }
        in.residual().ifPresent(r -> r.requireLength(in.horizonDays()));

        // α 는 워커가 준 값이지만 방어한다. [0,1] 밖이면 아래 순서 보존 논증이 깨진다.
        BigDecimal alpha = clamp(in.judgmentAlpha());

        List<DailyPoint> points = new ArrayList<>(in.horizonDays());
        BigDecimal min = in.openingBalance();
        BigDecimal expected = in.openingBalance();
        BigDecimal max = in.openingBalance();

        for (int i = 0; i < in.horizonDays(); i++) {
            LocalDate date = in.baseDate().plusDays(i + 1L);
            DayLedger ledger = DayLedger.of(in.planned(), date, i == 0);
            Residual r = residualOf(in.residual(), i, alpha);

            // 대표선 — 예정 거래의 기대값과 잔여의 α 판정값.
            BigDecimal incExpected = ledger.depositExpected.subtract(ledger.withdrawalExpected)
                    .add(r.deposit).subtract(r.withdrawal);
            // 하한 — 가장 나쁜 조합(입금은 하한, 지출은 상한). baseline.py compose_balance 와 같다.
            BigDecimal incMin = ledger.depositMin.subtract(ledger.withdrawalMax)
                    .add(r.depositLow).subtract(r.withdrawalHigh);
            // 상한 — 가장 좋은 조합.
            BigDecimal incMax = ledger.depositMax.subtract(ledger.withdrawalMin)
                    .add(r.depositHigh).subtract(r.withdrawalLow);

            min = min.add(incMin);
            expected = expected.add(incExpected);
            max = max.add(incMax);

            // 항별로 min ≤ expected ≤ max 가 성립하므로(아래 불변식) 여기서 깨지면 버그다.
            // 조용히 넘기면 DB 의 ck_dbf_range 가 INSERT 에서 터진다 — 원인이 먼 곳에서 드러난다.
            if (min.compareTo(expected) > 0 || expected.compareTo(max) > 0) {
                throw new IllegalStateException(
                        "곡선 범위 불변식 위반 %s: min=%s expected=%s max=%s".formatted(date, min, expected, max));
            }

            // 누적 변수(min·max)는 그대로 두고 **내보내는 값만** 좁힌다. 누적에서 좁히면
            // 다음 날이 좁혀진 값에서 다시 출발해 매일 복리로 줄어든다.
            BigDecimal shrink = coverageShrink(i + 1);
            points.add(new DailyPoint(date,
                    expected.add(min.subtract(expected).multiply(shrink)),
                    expected,
                    expected.add(max.subtract(expected).multiply(shrink)),
                    ledger.depositExpected.add(r.deposit),
                    ledger.withdrawalExpected.add(r.withdrawal),
                    r.deposit, r.withdrawal,
                    ledger.confidenceFactor()));
        }

        return new Curve(points, totals(points));
    }

    /**
     * 잔여 분위수를 판정 보수성 α 로 대표값 하나로 접는다 (API 20.11).
     *
     * <pre>
     * 잔여입금 = q50 + α·(q10 − q50)      적게 들어온다고 본다
     * 잔여출금 = q50 + α·(q90 − q50)      많이 나간다고 본다
     * </pre>
     *
     * <p><b>α 를 우리가 정하지 않는다.</b> 워커가 응답에 실어 준 값을 그대로 쓴다 —
     * 「공백 경보니까 안전하게」 올리면 모델 채택 근거가 사라진다(게이트가 α=0.00 에서만 통과).
     */
    private static Residual residualOf(Optional<ResidualQuantiles> residual, int index, BigDecimal alpha) {
        if (residual.isEmpty()) {
            return Residual.ZERO;
        }
        Quantile dep = residual.get().deposit().get(index);
        Quantile wd = residual.get().withdrawal().get(index);
        return new Residual(
                blend(dep.q50(), dep.q10(), alpha), dep.q10(), dep.q90(),
                blend(wd.q50(), wd.q90(), alpha), wd.q10(), wd.q90());
    }

    /** {@code from + α·(to − from)}. α∈[0,1] 이면 결과는 항상 두 값 사이다. */
    private static BigDecimal blend(BigDecimal from, BigDecimal to, BigDecimal alpha) {
        return from.add(to.subtract(from).multiply(alpha));
    }

    /**
     * <b>표시용 밴드를 커버리지 80% 로 맞추는 배율.</b>
     *
     * <p>하루치 분위수를 그대로 더하면 「{@code n}일 내리 매일 상위 10% 가 동시에 터진다」는
     * 시나리오가 된다. 현실에 없는 조합이라 <b>명목 80% 인 밴드가 실제로는 99.9% 를 덮고</b>,
     * 지평이 길수록 과잉이 커진다.
     *
     * <p>실측 (창 9,600개 · {@code private/현재/forecast-lab/audit/coverage.py} · 2026-09-14):
     *
     * <pre>
     * 지점    커버리지        지금 폭         80% 폭        과잉
     *  7일     98.5%     7,937,954원    3,327,005원     2.4배
     * 14일     99.6%    15,922,531원    5,043,044원     3.2배
     * 30일     99.9%    34,512,519원    8,102,956원     4.3배
     *
     * 일별(모델이 직접 배운 것)  입금 62.7% · 지출 79.0%   ← 명목 80% 에 근접
     * </pre>
     *
     * <p><b>일별은 맞고 누적만 틀리다.</b> 그래서 모델 교정(재학습)이 아니라 더하는 쪽의
     * 문제이고, 여기서 되돌린다. 원인을 직접 고치는 것은 분위수를 누적에 거는
     * {@code gru-cumq} 이지만 계약의 <b>뜻</b>이 바뀌어 채택 게이트를 다시 돌려야 한다.
     *
     * <p><b>대표선과 판정은 건드리지 않는다.</b> 공백은 {@code balanceExpected} 로만 판정하고
     * α 는 원본 분위수를 쓴다 — 모델 채택 게이트가 그대로 유효하다. 다만
     * {@code CashGapDetector} 의 「최악 부족액」은 {@code balanceMin} 을 보므로 <b>함께 줄어든다</b>
     * (팀 합의 2026-09-14). 배율이 날마다 달라 <b>최악인 날이 앞으로 옮겨질 수 있다.</b>
     *
     * @param day 기준일로부터 며칠째인가 (1-based). 조회 창과 무관하다 — 30일 곡선의 7일째와
     *            7일 조회의 7일째는 같은 누적이다
     */
    static BigDecimal coverageShrink(int day) {
        if (day <= 7) {
            return SHRINK_7;
        }
        if (day <= 14) {
            return interpolate(day, 7, 14, SHRINK_7, SHRINK_14);
        }
        if (day <= 30) {
            return interpolate(day, 14, 30, SHRINK_14, SHRINK_30);
        }
        return SHRINK_30;
    }

    /** {@code lo} 와 {@code hi} 사이를 선형으로 잇는다. 과잉이 지평에 따라 매끄럽게 커지기 때문이다. */
    private static BigDecimal interpolate(int day, int fromDay, int toDay,
                                          BigDecimal fromValue, BigDecimal toValue) {
        BigDecimal t = BigDecimal.valueOf(day - fromDay)
                .divide(BigDecimal.valueOf(toDay - fromDay), 6, java.math.RoundingMode.HALF_UP);
        return fromValue.add(toValue.subtract(fromValue).multiply(t));
    }

    private static BigDecimal clamp(BigDecimal alpha) {
        if (alpha == null || alpha.signum() < 0) {
            return BigDecimal.ZERO;
        }
        return alpha.compareTo(BigDecimal.ONE) > 0 ? BigDecimal.ONE : alpha;
    }

    private static Totals totals(List<DailyPoint> points) {
        BigDecimal deposit = BigDecimal.ZERO;
        BigDecimal withdrawal = BigDecimal.ZERO;
        for (DailyPoint p : points) {
            deposit = deposit.add(p.residualDeposit());
            withdrawal = withdrawal.add(p.residualWithdrawal());
        }
        return new Totals(deposit, withdrawal);
    }

    // --- 입력 -----------------------------------------------------------------

    /**
     * @param baseDate      기준일. 곡선은 <b>다음 날</b>부터 시작한다
     * @param openingBalance 기준일 시점의 사업장 합계 잔액
     * @param planned       {@code [baseDate, baseDate+horizonDays]} 안의 예정 거래.
     *                      <b>기준일 당일자는 첫 점에 합산된다</b> — 위 「왜 곡선이 …」 참조.
     *                      <b>{@code MATCHED}·{@code CANCELLED}·{@code MISSED} 는 넣지 않는다</b> —
     *                      매칭된 것은 이미 실제 거래로 일어나 시작 잔액에 들어 있다
     * @param residual      잔여 분위수. 비어 있으면 잔여를 0 으로 둔다({@code source = NONE})
     * @param judgmentAlpha 워커가 준 판정 보수성. {@code residual} 이 없으면 무시된다
     */
    public record CurveInput(LocalDate baseDate,
                             int horizonDays,
                             BigDecimal openingBalance,
                             List<PlannedItem> planned,
                             Optional<ResidualQuantiles> residual,
                             BigDecimal judgmentAlpha) {
    }

    /**
     * 예정 거래 한 건.
     *
     * @param itemConfidence 0~1. 사용자가 넣었거나 확정한 것은 1.0, 패턴 생성분은 그 패턴의 신뢰도다.
     *                       {@code PLANNED_TRANSACTION} 에는 이 컬럼이 없어 실행 시점에 전파한다
     */
    public record PlannedItem(Long plannedTransactionId,
                              LocalDate date,
                              FlowType flowType,
                              BigDecimal min,
                              BigDecimal expected,
                              BigDecimal max,
                              BigDecimal itemConfidence) {

        /**
         * 세 금액의 순서를 강제한다. DB {@code ck_pt_amount} 가 이미 보장하지만 시나리오(P0-09)가
         * 만든 입력은 DB 를 거치지 않으므로 여기서 한 번 더 정렬한다 — 순서가 뒤집히면
         * 곡선의 {@code min ≤ expected ≤ max} 가 무너진다.
         */
        public PlannedItem {
            BigDecimal lo = min.min(expected).min(max);
            BigDecimal hi = max.max(expected).max(min);
            min = lo;
            max = hi;
        }
    }

    /** 일별 잔여 분위수. 두 축 모두 길이가 {@code horizonDays} 와 같아야 한다. */
    public record ResidualQuantiles(LocalDate startDate, List<Quantile> deposit, List<Quantile> withdrawal) {

        void requireLength(int horizonDays) {
            if (deposit.size() != horizonDays || withdrawal.size() != horizonDays) {
                throw new IllegalArgumentException(
                        "잔여 시계열 길이가 예측 기간과 다르다: deposit=%d withdrawal=%d horizon=%d"
                                .formatted(deposit.size(), withdrawal.size(), horizonDays));
            }
        }
    }

    /**
     * 하루치 분위수. {@code q10 ≤ q50 ≤ q90} 는 워커 응답 검증(20.1 ②)과 통계 폴백이 보장한다 —
     * 여기서는 다시 확인하지 않고, 깨진 값이 들어오면 위 불변식 검사가 잡는다.
     */
    public record Quantile(BigDecimal q10, BigDecimal q50, BigDecimal q90) {

        public static Quantile zero() {
            return new Quantile(BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO);
        }
    }

    // --- 출력 -----------------------------------------------------------------

    /**
     * @param expectedDeposit    <b>원장 + 잔여</b> 합계 (ERD {@code expected_deposit})
     * @param residualDeposit    위 합계에 <b>이미 포함된</b> 잔여 기여분. 곡선을 그릴 때 더하면 이중 계상이다
     * @param plannedConfidence  그날 예정 거래의 금액 가중 신뢰도. 항목이 없는 날은 1
     */
    public record DailyPoint(LocalDate date,
                             BigDecimal balanceMin,
                             BigDecimal balanceExpected,
                             BigDecimal balanceMax,
                             BigDecimal expectedDeposit,
                             BigDecimal expectedWithdrawal,
                             BigDecimal residualDeposit,
                             BigDecimal residualWithdrawal,
                             BigDecimal plannedConfidence) {
    }

    /** 30일 합계. API 10.1 {@code basis.residual.total*} 의 원천이다. */
    public record Totals(BigDecimal residualDeposit, BigDecimal residualWithdrawal) {
    }

    public record Curve(List<DailyPoint> points, Totals residualTotals) {

        /** 예상 잔액이 가장 낮은 날. 동률이면 빠른 날(API 10.1 {@code lowestPoint}). */
        public DailyPoint lowestPoint() {
            DailyPoint lowest = points.get(0);
            for (DailyPoint p : points) {
                if (p.balanceExpected().compareTo(lowest.balanceExpected()) < 0) {
                    lowest = p;
                }
            }
            return lowest;
        }
    }

    // --- 내부 -----------------------------------------------------------------

    /** 하루치 예정 거래 합계. */
    private static final class DayLedger {

        private BigDecimal depositMin = BigDecimal.ZERO;
        private BigDecimal depositExpected = BigDecimal.ZERO;
        private BigDecimal depositMax = BigDecimal.ZERO;
        private BigDecimal withdrawalMin = BigDecimal.ZERO;
        private BigDecimal withdrawalExpected = BigDecimal.ZERO;
        private BigDecimal withdrawalMax = BigDecimal.ZERO;
        private BigDecimal weightedConfidence = BigDecimal.ZERO;
        private BigDecimal weight = BigDecimal.ZERO;

        /**
         * @param firstPoint 곡선의 첫 점이면 기준일 당일자까지 함께 담는다. 시작 잔액이
         *                   담지 못하는 유일한 구간이라 여기서 받는다
         */
        static DayLedger of(List<PlannedItem> planned, LocalDate date, boolean firstPoint) {
            DayLedger ledger = new DayLedger();
            for (PlannedItem item : planned) {
                boolean belongs = firstPoint ? !item.date().isAfter(date) : item.date().equals(date);
                if (!belongs) {
                    continue;
                }
                if (item.flowType() == FlowType.DEPOSIT) {
                    ledger.depositMin = ledger.depositMin.add(item.min());
                    ledger.depositExpected = ledger.depositExpected.add(item.expected());
                    ledger.depositMax = ledger.depositMax.add(item.max());
                } else {
                    ledger.withdrawalMin = ledger.withdrawalMin.add(item.min());
                    ledger.withdrawalExpected = ledger.withdrawalExpected.add(item.expected());
                    ledger.withdrawalMax = ledger.withdrawalMax.add(item.max());
                }
                BigDecimal w = item.expected().abs();
                ledger.weightedConfidence = ledger.weightedConfidence.add(w.multiply(item.itemConfidence()));
                ledger.weight = ledger.weight.add(w);
            }
            return ledger;
        }

        /**
         * 금액 가중 평균. 예정 거래가 없거나 금액이 전부 0 이면 1 이다 —
         * <b>예정 층에서 불확실할 것이 없는 날</b>이지 신뢰할 수 없는 날이 아니다.
         */
        BigDecimal confidenceFactor() {
            if (weight.signum() == 0) {
                return BigDecimal.ONE;
            }
            return weightedConfidence.divide(weight, java.math.MathContext.DECIMAL64);
        }
    }

    /** α 를 적용한 하루치 잔여와 그 분위수 양끝. */
    private record Residual(BigDecimal deposit, BigDecimal depositLow, BigDecimal depositHigh,
                            BigDecimal withdrawal, BigDecimal withdrawalLow, BigDecimal withdrawalHigh) {

        static final Residual ZERO = new Residual(BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
                BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO);
    }
}
