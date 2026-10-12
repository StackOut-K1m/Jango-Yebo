package com.jangoyebo.core.evidence;

import java.util.Map;

/**
 * 검산만 다시 돌린다 (API 20.4 확장 {@code POST /internal/v1/ai/evidence-validation}).
 *
 * <p>사용자가 검토 화면에서 금액을 고친 직후 <b>"이제 검산이 맞습니다"</b> 를 보여주려면 규칙을
 * 다시 돌려야 한다. 그 규칙(과세+부가세+면세=합계, 부가세=과세×10%, Σ품목, 품목 행 정렬,
 * 사업자등록번호 체크섬)을 <b>화면이나 API 서버가 복제하면 워커와 어긋난다</b> — 같은 식이 두
 * 곳에 살면 반드시 드리프트한다. 그래서 규칙은 워커 한 곳에 두고 결과만 받는다.
 * 모델을 부르지 않아 비용 0 · 즉시 응답이다.
 *
 * <p><b>이 인터페이스가 {@code core.evidence} 에 있는 이유</b>는 방향이다. {@code core.ai} 는
 * 이미 {@code core.evidence} 를 부르고 있어(추출 결과 저장), 반대로도 부르면 패키지가 서로를
 * 참조한다. 쓰는 쪽이 인터페이스를 갖고 {@code core.ai} 가 구현을 놓는다.
 */
public interface ExtractionValidator {

    /**
     * @param extraction 13.2 {@code extraction} 과 같은 모양(헤더 + {@code items})
     * @return 워커 {@code validation}. <b>실패하면 {@code null}</b> — 워커가 죽어 있다고 검토
     *         저장을 막지 않는다. 호출자는 있던 검산 결과를 그대로 두면 된다
     */
    Map<String, Object> revalidate(Map<String, Object> extraction);
}
