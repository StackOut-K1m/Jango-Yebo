package com.jangoyebo.forecast.planned;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.jangoyebo.common.domain.FlowType;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.time.LocalDate;

/** 예정 거래 API의 요청·응답 (API 9.4). */
public final class PlannedTransactionDtos {

    private PlannedTransactionDtos() {
    }

    /**
     * 일회성 등록 (P0-06).
     *
     * @param amount    확정 금액. {@code amountMin}/{@code amountMax} 와 <b>둘 중 한 형태</b>로 보낸다
     * @param direction {@code DEPOSIT} 또는 {@code WITHDRAWAL}
     */
    public record CreateRequest(
            @NotNull FlowType direction,
            @NotBlank @Size(max = 100) String title,
            @NotNull LocalDate expectedDate,
            @Positive BigDecimal amount,
            @Positive BigDecimal amountMin,
            @Positive BigDecimal amountMax,
            String category,
            Long accountId,
            @Size(max = 2000) String memo) {

        /**
         * 확정 금액과 범위 중 정확히 하나만 와야 한다.
         *
         * <p>둘 다 오면 어느 쪽이 이기는지가 호출자마다 달라지고, 둘 다 없으면 곡선에 더할 값이
         * 없다. 여기서 막지 않으면 DB {@code ck_pt_amount} 가 잡지만 오류 메시지가
         * 제약 이름이라 프론트가 읽을 수 없다.
         */
        public boolean hasExactlyOneAmountForm() {
            boolean fixed = amount != null;
            boolean range = amountMin != null && amountMax != null;
            return fixed ^ range;
        }

        public BigDecimal min() {
            return amount != null ? amount : amountMin;
        }

        public BigDecimal max() {
            return amount != null ? amount : amountMax;
        }

        /** 범위로 왔을 때의 대표값은 중앙이다. 곡선의 기대선이 여기서 나온다. */
        public BigDecimal expected() {
            if (amount != null) {
                return amount;
            }
            return amountMin.add(amountMax).divide(BigDecimal.valueOf(2), 4, java.math.RoundingMode.HALF_UP);
        }
    }

    /** 수정 (API 9.4). 전달된 필드만 바뀐다. {@code direction}·출처는 바꿀 수 없다. */
    public record UpdateRequest(
            @Size(max = 100) String title,
            LocalDate expectedDate,
            @Positive BigDecimal amount,
            @Positive BigDecimal amountMin,
            @Positive BigDecimal amountMax,
            String category,
            @Size(max = 2000) String memo,
            PlannedStatus status) {

        /** 금액을 건드린다면 등록과 같은 규칙을 지켜야 한다. 아예 안 건드리는 것은 허용이다. */
        public boolean hasValidAmountForm() {
            boolean fixed = amount != null;
            boolean range = amountMin != null && amountMax != null;
            boolean partialRange = (amountMin != null) ^ (amountMax != null);
            if (!fixed && !range && !partialRange) {
                return true;
            }
            return fixed ^ range;
        }
    }

    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record PlannedTransactionResponse(
            Long plannedTransactionId,
            Long businessId,
            Long accountId,
            PlannedSourceType source,
            PlannedStatus status,
            FlowType direction,
            String category,
            String title,
            LocalDate expectedDate,
            LocalDate originalDate,
            boolean holidayAdjusted,
            BigDecimal amountExpected,
            BigDecimal amountMin,
            BigDecimal amountMax,
            Long matchedTransactionId,
            String memo) {

        public static PlannedTransactionResponse of(PlannedTransaction p, String categoryCode) {
            return new PlannedTransactionResponse(
                    p.getId(), p.getBusinessId(), p.getAccountId(), p.getSourceType(), p.getStatus(),
                    p.getFlowType(), categoryCode, p.getName(),
                    p.getExpectedDate(), p.getOriginalExpectedDate(),
                    // 파생값이라 저장하지 않는다 — 두 날짜가 다르면 보정이 걸린 것이다.
                    !p.getExpectedDate().equals(p.getOriginalExpectedDate()),
                    p.getAmountExpected(), p.getAmountMin(), p.getAmountMax(),
                    p.getMatchedTransactionId(), p.getMemo());
        }
    }
}
