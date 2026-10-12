package com.jangoyebo.forecast.scenario;

import com.jangoyebo.forecast.pattern.ConfidenceBand;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/**
 * 시나리오 요청·응답 (API 11.1·11.2).
 *
 * <p>응답의 {@code null} 필드는 전역 {@code non_null} 정책에 따라 <b>키가 통째로 빠진다.</b>
 * 프론트 타입이 전부 옵셔널인 것이 그 전제이고, 여기서는 그것이 계약의 일부다 —
 * 「부족한 날이 없다」는 {@code firstShortfallDate} 키의 부재로 말한다.
 */
public final class ScenarioDtos {

    private ScenarioDtos() {
    }

    /**
     * 11.1 요청.
     *
     * @param save {@code false} 면 저장하지 않고 결과만 낸다({@code 200}). {@code true} 면
     *             {@code 201} 과 {@code scenarioId}
     */
    public record CreateRequest(@Size(max = 100) String name,
                                @NotNull Integer days,
                                @NotNull Boolean save,
                                @NotNull @NotEmpty @Size(max = MAX_PARAMETERS) List<ParameterDto> parameters) {
    }

    /**
     * 파라미터 하나를 담는 <b>평면</b> 표현. 요청과 응답이 같은 모양을 쓴다.
     *
     * <p><b>{@code type} 이 enum 이 아니라 String 인 것은 의도다.</b> enum 이면 모르는 값이
     * Jackson 파싱 단계에서 터지고, 전역 핸들러가 「요청 본문을 읽을 수 없습니다」
     * ({@code VALIDATION_FAILED})로 뭉갠다 — 명세가 그 자리에 약속한
     * {@code SCENARIO_PARAM_INVALID} 와 「몇 번째 파라미터가 왜 틀렸는지」를 낼 수 없다.
     *
     * <p>유형별로 쓰는 필드가 다르고 쓰지 않는 필드는 {@code null} 이라, 응답에서는 그 키가
     * 빠져 유형별 모양이 저절로 맞는다.
     */
    public record ParameterDto(String type,
                               Long patternId,
                               Integer delayDays,
                               BigDecimal rate,
                               LocalDate date,
                               BigDecimal amount,
                               String title,
                               Long plannedTransactionId,
                               LocalDate newDate,
                               String productKey,
                               BigDecimal quantityRate,
                               BigDecimal priceRate,
                               BigDecimal interestRate,
                               Integer loanPeriodDays,
                               String repaymentCycle,
                               LocalDate disbursementDate) {
    }

    /** 11.2 PATCH — 이름만 바꾼다. */
    public record RenameRequest(@NotBlank @Size(max = 100) String name) {
    }

    /**
     * 11.1 응답.
     *
     * @param scenarioId {@code save = false} 면 {@code null} → 키가 빠진다
     * @param baseTime   계산의 기준이 된 <b>예측 실행</b>의 시각. 저장한 시나리오를 며칠 뒤
     *                   열면 그날 실행 위에서 다시 계산되고 이 값이 바뀐다(11.2)
     */
    public record ScenarioResultResponse(Long scenarioId,
                                         String name,
                                         Instant baseTime,
                                         int days,
                                         List<ParameterDto> parameters,
                                         Comparison comparison,
                                         List<CurvePoint> originalCurve,
                                         List<CurvePoint> modifiedCurve,
                                         ConfidenceBand confidence,
                                         List<String> warnings) {
    }

    /** 원본과 변경을 <b>항상 나란히</b> 낸다(11.1 — 변경 전후 비교 UI). */
    public record Comparison(Summary original, Summary modified) {
    }

    /**
     * 곡선 하나의 요약.
     *
     * @param firstShortfallDate  부족한 날이 없으면 {@code null} → 키가 빠진다.
     *                            화면은 그것을 「없음」으로 읽는다
     * @param gapDurationDays     창 안에서 잔액이 0원 미만인 날의 수. <b>{@code int} 다</b> —
     *                            0 은 「공백이 없다」는 정보이므로 키가 빠지면 안 된다
     * @param belowSafetyLineDays 안전선을 설정하지 않았으면 {@code null} → 키가 빠진다.
     *                            0 과 다르다 — 「하회한 날이 없다」가 아니라 「잴 선이 없다」다
     */
    public record Summary(BigDecimal lowestBalance,
                          LocalDate lowestBalanceDate,
                          LocalDate firstShortfallDate,
                          int gapDurationDays,
                          Integer belowSafetyLineDays) {
    }

    /**
     * 곡선의 한 점. <b>10.1 과 달리 두 필드뿐이다</b> — 시나리오는 대표선만 비교하고
     * 밴드({@code balanceMin}/{@code balanceMax})는 내지 않는다.
     */
    public record CurvePoint(LocalDate date, BigDecimal expectedBalance) {
    }

    /** 11.2 목록. 페이징 필드가 없다(9.2·10.3 과 같다). */
    public record ScenarioListResponse(List<SavedScenarioItem> items) {
    }

    public record SavedScenarioItem(Long scenarioId,
                                    String name,
                                    Instant createdAt,
                                    List<ParameterDto> parameters) {
    }

    /**
     * 한 시나리오에 담을 수 있는 파라미터 수.
     *
     * <p>명세에 상한이 없지만 두는 이유는 계산 비용이 아니라 <b>읽을 수 있는 결과</b>다 —
     * 스무 개를 겹치면 어느 가정이 곡선을 움직였는지 사람이 되짚을 수 없다. 화면의 달력
     * 끌어다 놓기가 지출 하나당 파라미터 하나를 만들므로 넉넉히 잡았다.
     */
    static final int MAX_PARAMETERS = 20;
}
