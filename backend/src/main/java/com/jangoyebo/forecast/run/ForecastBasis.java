package com.jangoyebo.forecast.run;

import com.jangoyebo.common.domain.FlowType;
import com.jangoyebo.forecast.engine.BalanceCurveCalculator.PlannedItem;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * {@code FORECAST_RUN.basis} 의 구조 (ERD 1.3 · API 10.1).
 *
 * <p><b>실행 시점의 스냅샷이고 불변이다.</b> v1.1 에서 제거한 {@code FORECAST_ITEM} 테이블의
 * 감사 추적 역할을 대신한다 — 예정 거래가 나중에 수정돼도 「그때 그 곡선이 왜 그 값을
 * 냈는지」가 여기 남는다.
 *
 * <p><b>{@code Map<String,Object>} 가 아니라 타입 있는 record 로 매핑한다.</b> 이 값은
 * 응답({@code basis.residual.*})으로 <b>다시 읽히는 금액</b>이라 정밀도가 필요한데,
 * {@code Map} 역직렬화는 숫자를 {@code Integer}·{@code Double} 로 준다. 이 코드베이스는
 * 이미 그 문제를 겪었다({@code AccountSyncHandler} 가 JSONB 정수를 {@code Number#longValue()}
 * 로 받는 이유). {@code Job.params} 처럼 한 번 쓰고 마는 값과 다르다.
 */
public record ForecastBasis(Summary summary,
                            Residual residual,
                            List<PlannedSnapshot> items,
                            List<Adjustment> adjustments,
                            List<String> warnings,
                            Engine engine) {

    /**
     * 스냅샷을 계산기가 쓰는 모양으로. <b>원인을 찾는 모든 곳이 이것을 쓴다</b> —
     * 공백 조회·안전선 하회·알림 재동기화가 각자 변환하면 한쪽만 고쳐질 자리다.
     *
     * <p>실행 시점의 스냅샷이라 원본 예정 거래가 그 뒤 수정돼도 근거가 흔들리지 않는다.
     */
    public List<PlannedItem> ledger() {
        if (items == null) {
            return List.of();
        }
        List<PlannedItem> ledger = new ArrayList<>(items.size());
        for (PlannedSnapshot s : items) {
            ledger.add(new PlannedItem(s.plannedTransactionId(), s.expectedDate(),
                    FlowType.valueOf(s.direction()), s.amountMin(), s.amountExpected(), s.amountMax(),
                    s.confidence() == null ? BigDecimal.ONE : s.confidence()));
        }
        return ledger;
    }

    /**
     * 스냅샷의 {@code plannedTransactionId} → 이름. 원인 문구가 쓴다.
     *
     * <p>실행 시점의 이름이다 — 예정 거래가 그 뒤 이름이 바뀜어도 근거가 흔들리지 않는다.
     */
    public String nameOf(Long plannedTransactionId) {
        if (items == null || plannedTransactionId == null) {
            return "예정 지출";
        }
        for (PlannedSnapshot s : items) {
            if (plannedTransactionId.equals(s.plannedTransactionId())) {
                return s.name() == null ? "예정 지출" : s.name();
            }
        }
        return "예정 지출";
    }

    /** API 10.1 {@code basis} 최상위. 화면 헤더의 "확정 14건·예측 6건·직접 입력 2건 기준". */
    public record Summary(int confirmedPlannedCount,
                          int expectedPlannedCount,
                          int userAddedCount,
                          String referencePeriod) {
    }

    /**
     * 잔여 현금흐름 산출 스냅샷.
     *
     * @param judgmentAlpha 워커가 준 판정 보수성. <b>없으면 곡선을 재현할 수 없다</b> —
     *                      같은 {@code modelVersion} 과 같은 입력이어도 α 가 다르면 부족일이
     *                      달라진다(API 10.1·20.11). 통계 폴백에도 같은 값을 쓴다
     * @param historyDays   잔여 집계에 실제로 쓴 과거 일수. 30 미만이면 적용하지 않는다
     */
    public record Residual(boolean applied,
                           String source,
                           String modelName,
                           String modelVersion,
                           Integer historyDays,
                           BigDecimal judgmentAlpha,
                           Range totalDeposit,
                           Range totalWithdrawal) {

        /** 잔여를 반영하지 않은 실행. 사유는 {@code warnings} 에 담는다. */
        public static Residual notApplied(String source, Integer historyDays, BigDecimal judgmentAlpha) {
            return new Residual(false, source, null, null, historyDays, judgmentAlpha,
                    Range.zero(), Range.zero());
        }
    }

    public record Range(BigDecimal min, BigDecimal expected, BigDecimal max) {

        public static Range zero() {
            return new Range(BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO);
        }
    }

    /**
     * 실행에 들어간 예정 거래 한 건.
     *
     * @param confidence 패턴에서 전파한 신뢰도. {@code PLANNED_TRANSACTION} 에는 이 컬럼이 없다
     */
    public record PlannedSnapshot(Long plannedTransactionId,
                                  String name,
                                  String sourceType,
                                  String direction,
                                  Long categoryId,
                                  LocalDate originalExpectedDate,
                                  LocalDate expectedDate,
                                  BigDecimal amountMin,
                                  BigDecimal amountExpected,
                                  BigDecimal amountMax,
                                  BigDecimal confidence) {
    }

    /**
     * API 10.1 {@code adjustments[]} 의 원천.
     *
     * <p>P0 에서 채우는 것은 {@code MODEL_RESIDUAL} 하나다. {@code HOLIDAY_SHIFT}(P1-04)·
     * {@code SEASONALITY}(P1-07)·{@code VAT_DUE}(P1-11) 은 해당 기능이 서야 생긴다 —
     * 배열이라 나중에 더하면 되고, 지금 빈 배열이어도 계약을 만족한다.
     */
    public record Adjustment(String type,
                             String description,
                             List<LocalDate> affectedDates,
                             String modelName,
                             String modelVersion) {
    }

    /**
     * 계산 엔진 쪽 메타. API 응답에는 나가지 않고 재현·진단에 쓴다.
     *
     * @param balanceKnown 연결 계좌의 잔액을 전부 알고 있었나. false 면 신뢰도를 한 단계 내렸다
     */
    public record Engine(String version, boolean balanceKnown) {
    }
}
