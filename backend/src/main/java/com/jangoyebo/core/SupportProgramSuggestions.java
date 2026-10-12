package com.jangoyebo.core;

import java.time.LocalDate;
import java.util.Optional;

/**
 * 정산 공백에 붙일 지원 제도 힌트 (P1-15 · API 10.2 {@code suggestions}) —
 * {@code core} 모듈이 밖에 내놓는 공개 API.
 *
 * <p>{@code forecast} 가 {@code core.support} 를 직접 볼 수 없어 여기 둔다
 * ({@link CashGapAlerts} 와 같은 이유). <b>방향은 그 둘과 반대다</b> — {@code CashGapOutlook}
 * 은 {@code core} 가 선언하고 {@code forecast} 가 구현하지만, 이것은 {@code core} 가 선언하고
 * <b>{@code core} 가 구현한다.</b> {@code forecast} 는 쓰기만 한다. 순환이 아니다.
 *
 * <h2>건수만 준다</h2>
 *
 * <p>공고 목록이 아니라 <b>몇 건인지</b>만 돌려준다. API 10.2 의 {@code Suggestion} 계약이
 * {@code (type, description)} 둘뿐이라 화면이 특정 공고로 갈 수단이 없고, 명세의 예시도
 * 「집행이 빠른 지원 제도 2건 확인」처럼 <b>화면을 가리키는 한 줄</b>이다. 목록을 실으려면
 * 명세 10장 개정이 먼저다.
 */
public interface SupportProgramSuggestions {

    /**
     * 이 공백에 걸어 볼 만한 지원 제도가 몇 건인가.
     *
     * <p><b>비어 있으면 제안을 붙이지 않는다.</b> 「0건」이라고 적는 것은 사장님에게 쓸모가
     * 없고, 공고 수집이 아직 안 붙은 환경에서는 매번 그 줄이 나간다.
     *
     * <p>세는 기준은 <b>17.3 추천이 실제로 보여 줄 목록과 같다.</b> 다른 수를 말하면
     * 「2건 확인」을 누른 사장님이 다른 개수를 보게 된다.
     *
     * @param firstShortfallDate 최초 부족 예상일. 집행이 이 날 전에 닿는지 역산하는 데 쓴다
     */
    Optional<GapHint> forGap(Long businessId, LocalDate firstShortfallDate);

    /**
     * @param count                 17.3 이 보여 줄 추천 건수
     * @param anyArrivesBeforeGap   그중 <b>공백일 이전 수령 가능성이 확인된</b> 것이 있나.
     *                              모르는 것은 {@code false} 쪽에 들어간다 — 실측상 공고의
     *                              97% 가 지급 시기를 말하지 않아(32건 중 1건) 대개 거짓이며,
     *                              그래서 문구도 「빠르다」가 아니라 「확인」에 머문다
     */
    record GapHint(int count, boolean anyArrivesBeforeGap) {
    }
}
