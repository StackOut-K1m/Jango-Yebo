package com.jangoyebo.forecast.run;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.jangoyebo.common.domain.RiskLevel;
import com.jangoyebo.forecast.pattern.ConfidenceBand;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/**
 * 예측 응답 (API 10.1·10.2·10.7).
 *
 * <p><b>{@code @JsonInclude(ALWAYS)} 를 붙인 record 가 있다.</b> 전역 정책이
 * {@code non_null} 이라 값이 null 이면 키가 통째로 빠지는데, {@code safetyLine} 은
 * <b>null 이 의미를 갖는 필드</b>다 — "안전선을 설정하지 않았다"를 뜻하고 화면이 그것으로
 * 설정 유도를 띄운다(API 10.1). 키가 없으면 프론트가 "아직 안 온 값"과 구분할 수 없다.
 */
public final class ForecastDtos {

    private ForecastDtos() {
    }

    // --- 10.1 예상 잔액 곡선 ---------------------------------------------------

    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record BalanceForecastResponse(Instant baseTime,
                                          int days,
                                          BigDecimal currentBalance,
                                          BigDecimal safetyLine,
                                          Map<String, Object> appliedOptions,
                                          String modelVersion,
                                          List<CurvePoint> curve,
                                          LowestPoint lowestPoint,
                                          List<Adjustment> adjustments,
                                          ConfidenceBand confidence,
                                          Basis basis,
                                          List<String> warnings) {
    }

    public record CurvePoint(LocalDate date,
                             BigDecimal expectedBalance,
                             BigDecimal balanceMin,
                             BigDecimal balanceMax,
                             BigDecimal expectedDeposit,
                             BigDecimal expectedWithdrawal,
                             RiskLevel riskLevel) {
    }

    public record LowestPoint(LocalDate date, BigDecimal expectedBalance) {
    }

    public record Adjustment(String type, String description, List<LocalDate> affectedDates,
                             String modelName, String modelVersion) {
    }

    public record Basis(int confirmedPlannedCount,
                        int expectedPlannedCount,
                        int userAddedCount,
                        String referencePeriod,
                        Residual residual) {
    }

    /** {@code applied:false} 여도 키를 통째로 내보낸다 — 화면이 사유를 읽어야 한다. */
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record Residual(boolean applied,
                           String source,
                           String modelName,
                           String modelVersion,
                           Integer historyDays,
                           BigDecimal judgmentAlpha,
                           Range totalDeposit,
                           Range totalWithdrawal) {
    }

    public record Range(BigDecimal min, BigDecimal expected, BigDecimal max) {
    }

    // --- 10.2 정산 공백 --------------------------------------------------------

    public record CashGapsResponse(Instant baseTime,
                                   List<CashGapItem> items,
                                   ConfidenceBand confidence,
                                   CashGapBasis basis,
                                   List<String> warnings) {
    }

    /**
     * @param cashGapId 이 공백으로 만들어진 {@code ALERT.id}. 공백 구간은 저장하지 않는
     *                  파생값이라 자기 식별자가 없다(ERD 1.3)
     */
    public record CashGapItem(Long cashGapId,
                              LocalDate firstShortfallDate,
                              BigDecimal maxShortfallAmount,
                              LocalDate maxShortfallDate,
                              int durationDays,
                              LocalDate expectedRecoveryDate,
                              RiskLevel riskLevel,
                              List<Cause> causes,
                              List<Suggestion> suggestions) {
    }

    public record Cause(LocalDate date, String title, Long plannedTransactionId) {
    }

    public record Suggestion(String type, String description) {
    }

    public record CashGapBasis(int referenceCurveDays) {
    }

    // --- 10.7 예측 옵션 --------------------------------------------------------

    public record ForecastSettingsResponse(boolean applySeasonality,
                                           boolean applyVatReserve,
                                           boolean applyResidualModel,
                                           List<Integer> peakMonths,
                                           List<Integer> offMonths) {
    }

    /**
     * 부분 수정 — 보내지 않은 필드는 그대로 둔다. 그래서 전부 박스 타입이다.
     *
     * <p>{@code applyResidualModel} 만 P0 에서 곡선을 바꾼다.
     */
    public record ForecastSettingsPatchRequest(Boolean applySeasonality,
                                               Boolean applyVatReserve,
                                               Boolean applyResidualModel,
                                               List<Integer> peakMonths,
                                               List<Integer> offMonths) {
    }

    // --- 명세 밖 임시 ----------------------------------------------------------

    /** {@code POST /forecast/refresh} 응답. 계산은 잠시 뒤 끝난다. */
    public record RefreshAcceptedResponse(Long businessId, String status) {

        public static RefreshAcceptedResponse accepted(Long businessId) {
            return new RefreshAcceptedResponse(businessId, "SCHEDULED");
        }
    }
}
