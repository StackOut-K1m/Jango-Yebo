package com.jangoyebo.forecast.run;

import com.jangoyebo.forecast.planned.PlannedExecutionProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * 예측 설정 등록 — 곡선 실행과 예정 거래 자동 반영.
 *
 * <p>이 저장소에는 {@code @ConfigurationPropertiesScan} 이 없다. {@code SecurityConfig}·
 * {@code JobWorkerConfig}·{@code FinOpenApiConfig} 가 {@code @EnableConfigurationProperties} 로
 * 명시 등록하는 선례를 따른다.
 */
@Configuration
@EnableConfigurationProperties({ForecastProperties.class, PlannedExecutionProperties.class})
public class ForecastConfig {
}
