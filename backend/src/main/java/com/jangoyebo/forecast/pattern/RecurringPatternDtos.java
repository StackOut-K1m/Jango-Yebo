package com.jangoyebo.forecast.pattern;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.jangoyebo.common.domain.FlowType;
import jakarta.validation.constraints.Positive;

import java.math.BigDecimal;
import java.time.LocalDate;

/** 반복 패턴 API의 요청·응답 (API 9.2~9.3). */
public final class RecurringPatternDtos {

    private RecurringPatternDtos() {
    }

    /** 승인 (API 9.3). 필드는 모두 선택이며 전달된 값으로 보정한 뒤 확정한다. */
    public record ConfirmRequest(String category,
                                 @Positive BigDecimal amountMin,
                                 @Positive BigDecimal amountMax,
                                 HolidayRule holidayRule) {
    }

    /** 수정 (API 9.3). {@code status} 는 {@code CONFIRMED} ↔ {@code PAUSED} 만 오간다. */
    public record UpdateRequest(String category,
                                @Positive BigDecimal amountMin,
                                @Positive BigDecimal amountMax,
                                HolidayRule holidayRule,
                                PatternStatus status) {
    }

    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record RecurringPatternResponse(
            Long patternId,
            Long businessId,
            Long accountId,
            PatternStatus status,
            FlowType direction,
            String category,
            String name,
            Cycle cycle,
            AmountRange amountRange,
            NextExpected nextExpected,
            HolidayRule holidayRule,
            /** 숫자가 아니라 구간을 내보낸다 — 도메인 규칙 1, 확정 표현 금지 */
            ConfidenceBand confidence) {

        public static RecurringPatternResponse of(RecurringPattern p, String categoryCode) {
            return new RecurringPatternResponse(
                    p.getId(), p.getBusinessId(), p.getAccountId(), p.getStatus(),
                    p.getFlowType(), categoryCode, p.getName(),
                    new Cycle(p.getCadenceType(), p.getDayOfWeek(), p.getIntervalValue(), p.getDayOfMonth()),
                    new AmountRange(p.getExpectedAmountMin(), p.getExpectedAmountMax(), p.expectedAmount()),
                    p.getNextExpectedDate() == null ? null
                            : new NextExpected(p.getNextExpectedDate(), p.getNextExpectedDate(), false,
                                    p.getExpectedAmountMin(), p.getExpectedAmountMax()),
                    p.getHolidayRule(),
                    ConfidenceBand.of(p.getConfidence()));
        }
    }

    /** 해당 없는 필드는 null 이다 — {@code WEEKLY} 면 {@code dayOfWeek} 만 찬다. */
    public record Cycle(CadenceType type, Integer dayOfWeek, Integer intervalDays, Integer dayOfMonth) {
    }

    public record AmountRange(BigDecimal min, BigDecimal max, BigDecimal expected) {
    }

    /**
     * @param holidayAdjusted 공휴일 보정(P1-04)이 없어 지금은 항상 false 이고
     *                        {@code date} 와 {@code originalDate} 가 같다
     */
    public record NextExpected(LocalDate date, LocalDate originalDate, boolean holidayAdjusted,
                               BigDecimal amountMin, BigDecimal amountMax) {
    }

    /** 탐지 실행 결과 (API 명세 밖 — 배치가 없어 수동 트리거로 둔 엔드포인트다). */
    public record DetectionResult(int createdCount) {
    }
}
