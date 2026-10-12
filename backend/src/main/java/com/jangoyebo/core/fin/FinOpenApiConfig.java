package com.jangoyebo.core.fin;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.ObjectMapper;

import java.net.http.HttpClient;
import java.time.Clock;

/**
 * 금융망 어댑터 빈 구성.
 *
 * <p>이 저장소에는 {@code @ConfigurationPropertiesScan} 이 없다. {@code SecurityConfig} ·
 * {@code JobWorkerConfig} 가 {@code @EnableConfigurationProperties} 로 명시 등록하는 선례를 따른다.
 */
@Configuration
@EnableConfigurationProperties(FinOpenApiProperties.class)
public class FinOpenApiConfig {

    @Bean
    FinApiHeaderFactory finApiHeaderFactory(FinOpenApiProperties properties) {
        return new FinApiHeaderFactory(properties, kstClock());
    }

    /**
     * <b>KST 고정이다.</b> 서버가 UTC 로 떠 있어도 {@code transmissionTime} 은 한국시간이어야
     * 한다 — 금융망 서버 시각 ±5분을 벗어나면 전부 {@code H1003} 이다. 시스템 기본 시간대에
     * 기대지 않는 이유가 이것이다.
     *
     * <p>⚠ <b>{@code @Bean} 으로 노출하지 않는다.</b> Modulith 의
     * {@code MomentsAutoConfiguration} 이 {@code Clock} 빈을 <b>정확히 하나</b> 기대해서,
     * 두 번째를 만들면 앱이 부팅에 실패한다({@code expected single matching bean but found 2}).
     * 게다가 이건 앱 공용 시계가 아니라 금융망 전용이라 주입 대상이 되어서도 안 된다 —
     * 다른 곳이 집어 가면 그쪽 시간대가 조용히 한국시간으로 바뀐다.
     * {@link FinApiHeaderFactory} 가 {@link Clock} 을 받는 것은 테스트가 시각을 고정하기
     * 위해서일 뿐이다.
     */
    static Clock kstClock() {
        return Clock.system(FinApiHeaderFactory.KST);
    }

    /**
     * {@code RestClient.Builder} 를 주입받지 않고 직접 만든다 — 이 클래스패스에 그 빈이
     * 자동 구성되지 않는다({@code AiClientConfig} 와 같은 이유).
     *
     * <p>읽기 타임아웃을 AI 워커 접수(5초)보다 길게 잡는다. {@code DD_12} 는 페이지네이션이
     * 없어 한 달치가 한 응답으로 오고, 초기 적재는 그걸 12번 반복한다.
     */
    @Bean
    FinOpenApiClient finOpenApiClient(FinOpenApiProperties properties,
                                      FinApiHeaderFactory headerFactory,
                                      ObjectMapper objectMapper) {
        HttpClient httpClient = HttpClient.newBuilder()
                .connectTimeout(properties.client().connectTimeout())
                .build();
        var requestFactory = new JdkClientHttpRequestFactory(httpClient);
        requestFactory.setReadTimeout(properties.client().readTimeout());

        RestClient client = RestClient.builder()
                .baseUrl(properties.baseUrl())
                .requestFactory(requestFactory)
                .build();
        return new FinOpenApiClient(client, headerFactory, objectMapper);
    }
}
