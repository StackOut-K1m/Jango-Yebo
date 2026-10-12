package com.jangoyebo.core;

import com.jangoyebo.common.domain.RiskLevel;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

/**
 * 안전선 화면이 곁들이는 현재 상태 — API 12.1 의 {@code current} 블록.
 *
 * <h2>방향이 반대인 포트다</h2>
 *
 * <p>{@link CashGapAlerts}·{@link SafetyLines} 는 {@code core} 가 선언하고 {@code core} 가
 * 구현해 {@code forecast} 가 <b>쓴다</b>. 이것은 {@code core} 가 선언하고
 * <b>{@code forecast} 가 구현해 {@code core} 가 쓴다</b> — 의존 역전이다.
 *
 * <p>처음에는 이것 하나뿐이었다. 지금은 {@link AccountPurgeParticipant} 가 같은 모양이며,
 * 그쪽은 이유가 하나 더 있다 — 계좌를 지우기 전에 {@code forecast} 의 행이 먼저 비워져야
 * 해서 <b>같은 트랜잭션 안에서 순서대로</b> 불러야 한다.
 *
 * <p>그래야 하는 이유: {@code current} 의 여섯 값 중 넷이 예측 곡선에서 나오는데
 * ({@code next30dLowest*}·{@code shortfallAgainstLine}·{@code firstBelowLineDate}·{@code causes})
 * 안전선 설정은 사업장 속성이라 {@code core} 가 소유한다(V5 마이그레이션이 정한 배치다).
 * {@code core → forecast} 참조는 금지이므로 {@code core} 가 <b>필요한 모양만 선언</b>하고
 * 구현을 {@code forecast} 에 맡긴다. 컴파일 시점에 {@code core} 는 {@code forecast} 를
 * import 하지 않아 Modulith 검증이 통과한다.
 */
public interface SafetyLineOutlook {

    /**
     * 최신 완료 예측으로 만든 현재 상태.
     *
     * <p><b>계산하지 않는다.</b> 저장된 곡선을 읽을 뿐이다 — 조회가 예측을 돌리면 공통규약 2.6
     * 이 막은 경로가 된다. 실행이 아직 없으면 {@link Optional#empty()} 이고 응답에서
     * {@code current} 키가 통째로 빠진다.
     *
     * <p><b>예측을 예약하지도 않는다.</b> 안전선 조회는 곡선을 보러 온 것이 아니라 설정을 보러
     * 온 것이라, 실행이 없다고 409 를 내거나 배치를 거는 것은 과하다. 곡선이 필요한 화면
     * (SC-03-1)이 이미 그 일을 한다.
     *
     * @param safetyLine 비교선. {@code null} 이면 미설정이고 하회 관련 값이 전부 빈다 —
     *                   그래도 현재 잔액과 30일 최저는 의미가 있어 응답한다
     */
    Optional<Current> of(Long businessId, BigDecimal safetyLine);

    /**
     * @param currentBalance       예측이 출발한 잔액. 화면이 따로 더하지 않도록 같은 출처를 준다
     * @param riskLevel            <b>현재 잔액</b>의 등급이다. 30일 최저의 등급이 아니다(12.1 예시)
     * @param shortfallAgainstLine {@code 안전선 − 30일 최저}. 최저가 안전선 위면 {@code null}
     * @param firstBelowLineDate   처음 안전선 아래로 내려가는 날. 없으면 {@code null}
     * @param causes               첫 하회 구간의 원인. 하회가 없으면 빈 목록
     */
    record Current(BigDecimal currentBalance,
                   RiskLevel riskLevel,
                   BigDecimal next30dLowestBalance,
                   LocalDate next30dLowestDate,
                   BigDecimal shortfallAgainstLine,
                   LocalDate firstBelowLineDate,
                   List<Cause> causes) {
    }

    /** @param plannedTransactionId 「예정 입금 없음」 원인은 {@code null} 이다 */
    record Cause(LocalDate date, String title, Long plannedTransactionId) {
    }
}
