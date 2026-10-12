package com.jangoyebo.core.ai;

import com.jangoyebo.common.ai.AiProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpHeaders;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import java.net.http.HttpClient;

/**
 * AI 워커 호출 클라이언트.
 *
 * <p>{@code RestClient} 는 {@code spring-boot-starter-webmvc} 에 포함돼 있어
 * <b>새 의존성이 없다</b>(기술스택 문서 §3.2 — 의존성은 기능 착수 시 추가).
 */
@Configuration
public class AiClientConfig {

    /**
     * {@code RestClient.Builder} 를 주입받지 않고 직접 만든다 — 이 클래스패스에는 그 빈이
     * 자동 구성되지 않는다({@code RestClient.Builder} 의 자동 구성은 별도 모듈에 있다).
     * 직접 만들면 Boot 커스터마이저를 못 받지만, 워커 호출은 관측 대상이 아니라 무관하다.
     */
    @Bean
    AiWorkerClient aiWorkerClient(AiProperties properties) {
        // 연결 타임아웃은 HttpClient, 읽기 타임아웃은 팩토리가 갖는다.
        // 짧게 두는 이유: 비동기 접수(202)는 금방 오므로, 워커가 멈췄을 때
        // 폴링 워커 스레드가 오래 묶이지 않게 한다.
        HttpClient httpClient = HttpClient.newBuilder()
                .connectTimeout(properties.client().connectTimeout())
                .build();
        var requestFactory = new JdkClientHttpRequestFactory(httpClient);
        requestFactory.setReadTimeout(properties.client().readTimeout());

        RestClient client = RestClient.builder()
                .baseUrl(properties.baseUrl())
                .requestFactory(requestFactory)
                // 워커의 require_service_token 이 검증한다. 요청마다 붙일 필요가 없다.
                .defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer " + properties.serviceToken())
                .build();
        return new AiWorkerClient(client);
    }
}
