package com.jangoyebo.core;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Optional;

/**
 * 앞으로 나갈 예정 지출 (API 9.4 · 15.2 지표) — {@code core} 가 밖에 내놓는 공개 API.
 *
 * <p>{@link CashGapOutlook}·{@link SafetyLineOutlook} 과 같은 이유로 여기 있다. 예정 거래는
 * {@code forecast} 스키마라 {@code core} 가 직접 읽으면 모듈 경계를 넘는다.
 *
 * <p><b>좁게 연다.</b> 예정 거래 목록 전체가 아니라 「다음으로 큰 지출 하나」만 내놓는다.
 * 넓은 통로를 열면 {@code core} 가 예측의 내부 모양에 묶인다 — 브리핑 지표에 필요한 것은
 * 이 한 줄뿐이다.
 */
public interface UpcomingPayments {

    /**
     * {@code after} 다음 날부터 {@code days} 일 안에 나갈 예정 출금 중 가장 큰 것.
     *
     * <p><b>계산하지 않는다.</b> 저장된 예정 거래를 읽을 뿐이다. 없으면
     * {@link Optional#empty()} 이고, 부르는 쪽은 그 지표를 <b>빼야 한다</b> —
     * 「다음 지출 0원」은 말이 안 된다.
     */
    Optional<Payment> largestUpcoming(Long businessId, LocalDate after, int days);

    /** @param plannedTransactionId 화면이 근거로 되짚을 id (15.2 {@code sourceRef}) */
    record Payment(Long plannedTransactionId, LocalDate expectedDate, String name,
                   BigDecimal amount) {
    }
}
