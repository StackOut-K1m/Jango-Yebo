package com.jangoyebo.forecast.residual;

import com.jangoyebo.common.ai.AiProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 잔여 예측 워커 클라이언트 등록.
 *
 * <p>{@code AiClientConfig} 와 나눠 둔 이유는 타임아웃이 다르기 때문이다 — 저쪽은 비동기
 * 접수용 5초, 이쪽은 동기 15초다. {@code AiProperties} 는 {@code common} 에 있어
 * {@code forecast} 에서 보인다.
 */
@Configuration
@EnableConfigurationProperties(AiProperties.class)
public class ResidualClientConfig {

    @Bean
    ResidualForecastClient residualForecastClient(AiProperties properties) {
        return new ResidualForecastClient(properties);
    }
}
