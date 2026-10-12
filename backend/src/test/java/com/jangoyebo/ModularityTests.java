package com.jangoyebo;

import org.junit.jupiter.api.Test;
import org.springframework.modulith.core.ApplicationModules;
import org.springframework.modulith.docs.Documenter;

/**
 * 모듈 경계 검증.
 *
 * <p>아키텍처 D-2의 강제 장치다. {@code forecast → core} 단방향만 허용하며,
 * 역방향 참조나 다른 모듈의 내부 패키지 접근이 생기면 <b>이 테스트가 실패한다.</b>
 * 사람이 리뷰로 잡는 대신 CI가 잡게 하는 것이 목적이므로 삭제·비활성화하지 않는다.
 *
 * <p>Docker가 필요 없다(정적 분석만 수행). CI에서 항상 돌려야 한다.
 */
class ModularityTests {

    static final ApplicationModules MODULES = ApplicationModules.of(JangoApiApplication.class);

    @Test
    void verifiesModularStructure() {
        MODULES.verify();
    }

    /**
     * {@code build/spring-modulith-docs/} 에 모듈 구조 문서(PlantUML·AsciiDoc)를 생성한다.
     * 팀 공유·산출물 제출에 사용한다.
     */
    @Test
    void writeDocumentationSnippets() {
        new Documenter(MODULES).writeDocumentation();
    }
}
