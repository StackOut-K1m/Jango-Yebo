package com.jangoyebo.core;

import java.util.Collection;
import java.util.Map;
import java.util.Set;

/**
 * 정산 공백 알림 (P0-08 · API 10.2·10.3) — {@code core} 모듈이 밖에 내놓는 공개 API.
 *
 * <p>{@code core.alert.AlertService} 를 {@code forecast} 가 직접 볼 수 없어 여기 둔다
 * ({@link CategoryCatalog} 와 같은 이유). 공백을 아는 것은 {@code forecast} 이고
 * 알림을 소유한 것은 {@code core} 라 이 방향의 통로가 필요하다.
 *
 * <h2>{@code cashGapId} 가 여기서 나온다</h2>
 *
 * <p>공백 구간은 <b>저장하지 않는 파생값</b>이라(ERD 1.3) 자기 식별자가 없다. 그래서 API 10.2 의
 * {@code cashGapId} 는 <b>그 공백으로 만들어진 알림의 {@code ALERT.id}</b> 다. 같은 최초 부족일의
 * 공백은 {@code dedupKey} 로 같은 행에 모이므로 곡선이 다시 계산돼도 id 가 유지된다.
 */
public interface CashGapAlerts {

    /**
     * 공백 알림을 올리거나 갱신한다.
     *
     * @param dedupKey 같은 공백 판정 키. {@code cash-gap:{최초부족일}}
     * @return 알림 id. 이 값이 API 의 {@code cashGapId} 다
     */
    Long raise(Long businessId, String dedupKey, String title, String message,
               Map<String, Object> relatedRef, Map<String, Object> detail);

    /**
     * 이번 실행에서 관측되지 않은 공백 알림을 내린다.
     *
     * <p>예정 거래를 취소해 공백이 사라졌는데 알림이 그대로면 <b>해결한 것이 계속 경고로
     * 남는다.</b> 곡선을 다시 계산할 때마다 살아 있는 공백 집합을 통째로 넘겨 맞춘다.
     *
     * @param keepDedupKeys 이번 곡선에 여전히 있는 공백의 키. 비어 있으면 전부 내린다
     */
    void resolveExcept(Long businessId, Set<String> keepDedupKeys);

    /**
     * 공백 키 → 알림 id. 조회(10.2)가 응답에 {@code cashGapId} 를 채울 때 쓴다.
     *
     * <p><b>상태를 가리지 않는다.</b> 사용자가 알림을 확인 처리해도 공백 자체는 곡선에 남아
     * 있으므로 id 를 계속 붙여 줘야 한다. 같은 키가 여러 건이면 가장 최근 것을 준다.
     */
    Map<String, Long> idsByDedupKey(Long businessId, Collection<String> dedupKeys);
}
