package com.jangoyebo.core.report;

import java.util.List;
import java.util.Map;

/**
 * 브리핑을 워커 쪽에서 읽고 쓰는 좁은 통로 (API 20.7).
 *
 * <p>{@code core.ai} 가 {@code core.report} 의 저장소를 직접 만지지 않게 한다 — 같은 모듈
 * 안이지만, 콜백 반영 코드가 리포지터리를 들면 「어느 상태로 갈 수 있는가」가 두 곳에
 * 흩어진다. 상태 전이는 여기서만 일어난다.
 */
public interface BriefingSource {

    /** 워커에 보낼 재료. */
    Snapshot snapshot(Long briefingId);

    /**
     * 문장을 얹는다.
     *
     * <p><b>{@code partial} 은 워커가 판정한다.</b> 명세 20.7 은 「API 서버가 폐기하고
     * {@code PARTIAL} 로 저장한다」고 적었지만, 환각 숫자를 잡는 한국어 수치 파서가 워커에
     * 있어 실제로 거르는 쪽도 워커다({@code ai/app/nlp/briefing.py}). 백엔드가 하는 일은
     * <b>그 판정을 기록하는 것</b>이다 — 같은 검사를 두 벌 두면 둘이 갈릴 때 어느 쪽이
     * 옳은지 알 수 없다.
     */
    void applySentence(Long briefingId, String headline, String content, boolean partial);

    /** 문장 생성이 끝내 실패했다. 지표는 그대로 남는다. */
    void markFailed(Long briefingId);

    /** @param periodType {@code WEEKLY}·{@code MONTHLY} */
    record Snapshot(Long businessId, String periodType, List<Map<String, Object>> metrics) {
    }
}
