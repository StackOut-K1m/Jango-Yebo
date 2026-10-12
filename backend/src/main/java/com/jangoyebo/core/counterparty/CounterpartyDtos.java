package com.jangoyebo.core.counterparty;

import com.jangoyebo.common.domain.FlowType;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.time.Instant;
import java.util.List;

/** 거래처 사전 조회·수정 (API 8.6). */
public final class CounterpartyDtos {

    private CounterpartyDtos() {
    }

    /**
     * 사전 한 줄.
     *
     * @param matchPatterns 이 줄에 걸리는 적요. <b>지금은 한 줄뿐이다</b> — 표기가 갈리는
     *                      거래처가 관측되면 별칭 배열이 붙는다(08장 정정). 배열로 내보내는
     *                      이유는 그때 응답 모양이 바뀌지 않게 하기 위해서다
     * @param confirmedCount 사람이 확인한 횟수. <b>0 이면 모델이 담아 둔 줄</b>이고, 그 줄로
     *                      분류된 거래는 확인 질문에 남는다
     */
    public record MerchantResponse(Long merchantId,
                                   String displayName,
                                   List<String> matchPatterns,
                                   String defaultCategory,
                                   FlowType direction,
                                   int confirmedCount,
                                   Instant lastUsedAt) {

        static MerchantResponse of(Counterparty counterparty, String categoryCode) {
            return new MerchantResponse(
                    counterparty.getId(),
                    counterparty.getDisplayName(),
                    List.of(counterparty.getCanonicalName()),
                    categoryCode,
                    counterparty.getDefaultFlowType(),
                    counterparty.getConfirmedCount(),
                    counterparty.getLastUsedAt());
        }
    }

    /**
     * 수정 요청 (API 8.6 {@code PATCH}).
     *
     * <p><b>{@code matchPatterns} 는 받지 않는다.</b> 매칭 열쇠를 바꾸는 일이라 유일 인덱스
     * 구조까지 함께 가야 하고, 표기가 갈리는 거래처가 아직 관측되지 않았다. 보내면 400 이다 —
     * 조용히 무시하면 화면은 바뀐 줄 알고 사전은 그대로다.
     *
     * <p>{@code null} 인 필드는 건드리지 않는다.
     */
    public record UpdateRequest(@Size(max = 255) String displayName,
                                @Pattern(regexp = "^[A-Z_]{2,40}$") String defaultCategory) {
    }
}
