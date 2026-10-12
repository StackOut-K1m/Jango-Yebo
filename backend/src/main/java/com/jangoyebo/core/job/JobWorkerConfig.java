package com.jangoyebo.core.job;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * Job 워커·스위퍼 설정 등록.
 *
 * <p>이 저장소에는 {@code @ConfigurationPropertiesScan} 이 없다. {@code SecurityConfig} 가
 * {@code @EnableConfigurationProperties} 로 명시 등록하는 선례를 따른다.
 */
@Configuration
@EnableConfigurationProperties(JobProperties.class)
public class JobWorkerConfig {
}
