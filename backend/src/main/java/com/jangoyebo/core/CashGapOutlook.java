package com.jangoyebo.core;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Optional;

/**
 * 가장 가까운 정산 공백 — 지원 제도 추천(API 17.3)이 쓴다.
 *
 * <h2>방향이 반대인 포트다</h2>
 *
 * <p>{@link SafetyLineOutlook}·{@link AccountPurgeParticipant} 에 이은 <b>세 번째 의존
 * 역전</b>이다. {@code core} 가 선언하고 {@code forecast} 가 구현해 {@code core} 가 쓴다.
 *
 * <p>공고는 사업장 공용 기준 데이터라 {@code core} 가 소유하는데, 「이 지원금이 정산 공백에
 * 닿는가」를 말하려면 곡선이 필요하다. {@code core → forecast} 참조는 금지이므로 필요한
 * 모양만 여기 선언하고 구현을 넘긴다.
 *
 * <p><b>{@link CashGapAlerts} 와 방향이 반대다.</b> 그쪽은 {@code core} 가 선언하고
 * {@code core} 가 구현해 {@code forecast} 가 알림을 남길 때 쓴다. 이름이 비슷해 헷갈리기
 * 쉬운데, 읽는 쪽이 누구인지로 가른다.
 */
public interface CashGapOutlook {

    /**
     * 가장 이른 공백 하나.
     *
     * <p><b>계산하지 않는다.</b> 저장된 곡선을 읽을 뿐이다 — 조회가 예측을 돌리면 공통규약
     * 2.6 이 막은 경로가 된다. 완료된 실행이 없거나 공백이 없으면 {@link Optional#empty()}
     * 이고, 17.3 응답에서 {@code cashGap} 키가 통째로 빠진다.
     *
     * <p><b>여러 개 중 가장 이른 것만 준다.</b> 추천이 답해야 하는 질문이 「지금 신청해서
     * 다음 고비를 넘길 수 있나」 하나여서다. 공백 전체 목록은 10.2 가 이미 낸다.
     */
    Optional<NearestGap> nearestGap(Long businessId, int days);

    /**
     * @param baseTime            이 값을 만든 예측 실행의 기준 시각. 화면이 「언제 기준인지」를
     *                            말해야 한다(도메인 규칙 1 — 근거를 함께 보인다)
     * @param firstShortfallDate  잔액이 처음 음수로 내려가는 날
     * @param maxShortfallAmount  그 구간에서 가장 크게 모자란 금액(양수)
     */
    record NearestGap(Instant baseTime, LocalDate firstShortfallDate, BigDecimal maxShortfallAmount) {
    }
}
