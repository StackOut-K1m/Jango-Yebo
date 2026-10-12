package com.jangoyebo.core.support;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 공고 자격 조건(JSONB)에서 값을 <b>모양을 믿지 않고</b> 꺼낸다.
 *
 * <p>{@code eligibility} 는 워커가 준 JSON 을 <b>검증 없이 그대로</b> 담는다
 * ({@link SupportProgramStructuringService} {@code eligibilityOf}). 지금은 워커의 pydantic 이
 * {@code regions: list[str]} 을 강제해서 배열만 들어오지만 <b>그 하나가 유일한 방어다.</b>
 *
 * <p>모양이 어긋난 값 하나가 {@code (List<String>)} 캐스팅을 깨면 {@code ClassCastException}
 * 으로 응답 전체가 500 이 된다. 공고 한 건 때문에 <b>목록 스무 건이 통째로 안 보이는</b>
 * 모양이라, 자격 조건을 읽는 자리는 전부 여기를 지난다.
 *
 * <p>SQL 쪽의 짝은 {@code core.jsonb_text_array}(V33) 다 — 거기서는 같은 값이
 * {@code jsonb_array_length} 를 터뜨려 <b>Postgres 트랜잭션을 통째로 중단</b>시킨다.
 * 2026-09-17 고장 주입에서 공고 한 건의 {@code regions} 를 문자열로 바꿨더니 17.1 검색·
 * 17.3 추천·10.2 정산 공백이 한꺼번에 500 이 났다.
 */
final class Eligibility {

    private Eligibility() {
    }

    /**
     * 문자열 목록으로 읽는다. <b>배열이 아니면 「안 적혔다」로 친다</b> — 오류가 아니다.
     *
     * <p>검색·추천의 기존 규칙이 그렇다. 조건을 안 적은 공고는 후보에서 빼지 않는다
     * ({@link SupportProgramRecommendationService} 참조). 형식이 깨진 값도 같은 취급을 하는
     * 편이 일관되고, 무엇보다 <b>사장님이 볼 수 있었던 지원금을 잃지 않는다.</b>
     *
     * <p>원소도 문자열이 아닐 수 있다. 숫자가 섞이면 캐스팅은 통과하고 <b>쓰는 자리에서</b>
     * 터지므로(제네릭 소거) 여기서 문자열로 바꿔 둔다.
     */
    static List<String> textList(Map<String, Object> eligibility, String key) {
        if (eligibility == null || !(eligibility.get(key) instanceof List<?> raw)) {
            return List.of();
        }
        List<String> values = new ArrayList<>(raw.size());
        for (Object item : raw) {
            if (item != null) {
                values.add(String.valueOf(item));
            }
        }
        return List.copyOf(values);
    }
}
