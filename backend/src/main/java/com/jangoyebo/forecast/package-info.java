/**
 * forecast 모듈 — 계획 거래·반복 패턴·예측 실행·일별 잔액·공백 알림·시나리오·안전선·준비금.
 *
 * <p>DB 스키마 {@code forecast} 를 소유한다.
 *
 * <p>{@code core} 에만 의존할 수 있다(단방향). {@code core} 가 {@code forecast} 를
 * 참조하면 Modulith 검증이 실패한다 — 이 방향성이 나중에 서비스를 분리할 때의 절개선이다.
 *
 * <p>계산 엔진은 DB·HTTP를 건드리지 않는 순수 함수로 유지한다.
 * 인수조건 "동일 입력에 대해 재현된다"가 단위테스트로 검증되어야 하고,
 * 시나리오 계산(P0-09)이 같은 함수를 재사용하기 때문이다.
 *
 * @see <a href="../../../../../../../.agents/Jango-Yebo/Backend/backend-architecture.md">
 *      backend-architecture.md §6</a>
 */
@org.springframework.modulith.ApplicationModule(
        displayName = "Forecast",
        // allowedDependencies 를 선언하는 순간 이 목록이 전부가 된다 — type = OPEN 인 common 도
        // 여기 없으면 위반이다. forecast 에 코드가 없던 동안 드러나지 않았고, 첫 구현
        // (예정 거래)에서 ModularityTests 가 13건을 한꺼번에 잡았다.
        allowedDependencies = {"core", "common"}
)
package com.jangoyebo.forecast;
