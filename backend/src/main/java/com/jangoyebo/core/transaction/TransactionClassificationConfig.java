package com.jangoyebo.core.transaction;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * 거래 분류 설정 등록.
 *
 * <p>이 저장소에는 {@code @ConfigurationPropertiesScan} 이 없다. {@code SecurityConfig} ·
 * {@code JobWorkerConfig} · {@code FinOpenApiConfig} 가 {@code @EnableConfigurationProperties}
 * 로 명시 등록하는 선례를 따른다.
 */
@Configuration
@EnableConfigurationProperties(TransactionClassificationProperties.class)
class TransactionClassificationConfig {
}
