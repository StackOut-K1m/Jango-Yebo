package com.jangoyebo.core.settlement;

/**
 * 정산 대사 상태 (API 14.1).
 *
 * <p>DB {@code ck_settlement_status} 와 값이 일치해야 한다.
 */
public enum SettlementStatus {

    /**
     * 매칭된 입금 거래가 없다.
     *
     * <p><b>불일치가 아니다.</b> 아직 입금 전인 정상 상태를 {@code MISMATCHED} 로 만들면
     * 정산 주기 안의 모든 대사가 경고로 뜬다.
     */
    PENDING,

    /** 차이가 허용 오차 이내. */
    MATCHED,

    /** 차이가 있다. 정산 지연·누락·추가 차감을 확인해야 한다 — 알림이 나간다. */
    MISMATCHED
}
