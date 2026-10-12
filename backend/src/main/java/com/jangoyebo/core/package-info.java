/**
 * core 모듈 — 사용자·사업장·계좌·거래·분류·증빙·정산·내보내기.
 *
 * <p>DB 스키마 {@code core} 를 소유한다. 다른 모듈에 의존하지 않는다.
 *
 * <p>모듈 밖에서 접근 가능한 것은 이 패키지 바로 아래의 public 타입뿐이다.
 * 하위 패키지(예: {@code core.transaction.internal})는 Modulith가 외부 접근을 차단한다.
 * Repository를 모듈 밖으로 노출하지 말고 서비스 인터페이스로만 통하게 한다.
 *
 * @see <a href="../../../../../../../.agents/Jango-Yebo/Backend/backend-architecture.md">
 *      backend-architecture.md §3, §5</a>
 */
@org.springframework.modulith.ApplicationModule(
        displayName = "Core"
)
package com.jangoyebo.core;
