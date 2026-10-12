package com.jangoyebo.core;

import java.math.BigDecimal;
import java.util.Optional;

/**
 * 운영자금 안전선 조회 — {@code core} 모듈이 밖에 내놓는 공개 API (API 12.1).
 *
 * <p>{@code core.safety.SafetyBalancePolicyRepository} 를 {@code forecast} 가 직접 볼 수 없어
 * 여기 둔다({@link CategoryCatalog} 와 같은 이유).
 *
 * <p><b>곡선은 안전선에 의존하지 않는다.</b> 안전선은 위험 등급을 만들 때만 쓰이고 등급은
 * 저장하지 않는 파생값이라(ERD 1.3), <b>조회 시점에</b> 읽어 비교한다. 그래서 안전선을 바꿔도
 * 예측을 다시 돌릴 필요가 없다 — 공통규약 2.6 이 열거한 실행 트리거 중 「안전선 변경」을
 * 붙이지 않은 이유다.
 */
public interface SafetyLines {

    /**
     * 사업장의 안전선 금액.
     *
     * <p><b>비어 있는 것이 정상 상태다.</b> 「설정 안 함」은 금액 0 이 아니라 행이 없는 것이며,
     * 그때는 0원 기준({@code GAP})만 판정한다(API 10.1 {@code safetyLine: null}).
     */
    Optional<BigDecimal> currentAmount(Long businessId);
}
