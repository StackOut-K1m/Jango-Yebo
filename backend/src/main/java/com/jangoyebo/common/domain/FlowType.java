package com.jangoyebo.common.domain;

/**
 * 입출금 방향. DB는 {@code flow_type}, API는 {@code direction} 으로 부른다(ERD 11장 표기 대응).
 *
 * <p><b>왜 {@code common} 에 있는가.</b> 원래 {@code core.transaction} 에 있었으나
 * {@code forecast} 의 예정 거래·반복 패턴도 같은 값을 쓴다. Modulith 에서 {@code core} 는
 * CLOSED 라 하위 패키지가 밖에서 안 보이고, 실제로 {@code ModularityTests} 가 위반으로 잡았다.
 *
 * <p>선택지는 둘이었다 — {@code common} 으로 올리거나, {@code forecast} 에 같은 enum 을 하나 더
 * 두거나. 후자는 두 벌이 갈라지는 순간 <b>같은 거래가 모듈마다 다른 방향으로 읽힌다.</b>
 * 값이 둘뿐인 어휘라 {@code common} 의 "도메인 로직을 두지 않는다" 규칙을 깨지 않는다고 보고
 * 올렸다. 엔티티나 계산이 아니라 <b>용어</b>다.
 */
public enum FlowType {
    DEPOSIT,
    WITHDRAWAL
}
