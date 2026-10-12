package com.jangoyebo;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

@TestConfiguration(proxyBeanMethods = false)
public class TestcontainersConfiguration {

	// 이미지 태그는 운영과 동일하게 고정한다.
	// 생성 기본값이던 :latest 는 인프라 규약 위반이자 테스트 재현성을 깨뜨린다.
	// 운영 서버: postgres:16-alpine / redis:7-alpine (infra/compose.yml)

	@Bean
	@ServiceConnection
	PostgreSQLContainer postgresContainer() {
		return new PostgreSQLContainer(DockerImageName.parse("postgres:16-alpine"))
				.withEnv("TZ", "Asia/Seoul");
	}

	@Bean
	@ServiceConnection(name = "redis")
	GenericContainer<?> redisContainer() {
		return new GenericContainer<>(DockerImageName.parse("redis:7-alpine"))
				.withExposedPorts(6379);
	}

}
