package com.jangoyebo;

import org.junit.jupiter.api.Tag;
import org.springframework.context.annotation.Import;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 컨테이너가 필요한 테스트임을 표시한다.
 *
 * <p><b>컨테이너를 얻는 통로와 그 표시가 같은 애너테이션이다.</b> 둘을 따로 두면
 * {@code @Import} 만 베끼고 태그를 빠뜨린 테스트가 Docker 없는 실행에 섞여 터진다.
 * 하나로 묶으면 그게 불가능하다 — 컨테이너만 가져갈 방법이 없다.
 *
 * <p>Jenkins 는 DooD 라 워크스페이스 경로가 호스트에 없어 컨테이너가 뜨지 않는다.
 * 그래서 CI 는 {@code -PexcludeDocker} 로 이 태그를 빼고 돈다 (build.gradle).
 * 그 경로가 고쳐지면 스위치를 빼는 것만으로 전부 켜진다.
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Documented
@Import(TestcontainersConfiguration.class)
@Tag("docker")
public @interface NeedsDocker {
}
