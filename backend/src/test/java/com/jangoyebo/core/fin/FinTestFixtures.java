package com.jangoyebo.core.fin;

import java.time.Duration;

/**
 * 금융망 테스트용 설정값.
 *
 * <p>한곳에 모아 두는 이유는 단순하다 — {@link FinOpenApiProperties} 에 필드가 하나 늘 때마다
 * 테스트 세 곳이 함께 깨졌다. 설정은 이 테스트들의 관심사가 아니다.
 */
final class FinTestFixtures {

    static final String BASE_URL = "https://fin.test";
    static final String AUTH_TEXT = "잔고예보";

    private FinTestFixtures() {
    }

    static FinOpenApiProperties properties() {
        return new FinOpenApiProperties(BASE_URL, "test-api-key", "00100", "001",
                new FinOpenApiProperties.Client(Duration.ofSeconds(3), Duration.ofSeconds(15)),
                new FinOpenApiProperties.AccountAuth(AUTH_TEXT, Duration.ofMinutes(10), 3));
    }
}
