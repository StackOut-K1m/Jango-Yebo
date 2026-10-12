/**
 * common — 모든 모듈이 공유하는 기반 코드.
 *
 * <p>응답 Envelope, 오류 코드, 예외 처리, 감사 컬럼, 인증 프리미티브, 사업장 범위 강제.
 *
 * <p>Modulith의 <b>공유 모듈</b>로 선언한다({@code type = OPEN}). {@code core}·{@code forecast}
 * 어디서든 참조할 수 있지만, <b>이 모듈은 다른 모듈을 참조하지 않는다.</b>
 * 도메인 엔티티·비즈니스 로직을 여기에 두면 그 규칙이 깨지므로 넣지 않는다.
 *
 * @see <a href="../../../../../../../.agents/Jango-Yebo/Backend/backend-architecture.md">
 *      backend-architecture.md §5</a>
 */
@org.springframework.modulith.ApplicationModule(
        displayName = "Common",
        type = org.springframework.modulith.ApplicationModule.Type.OPEN
)
package com.jangoyebo.common;
