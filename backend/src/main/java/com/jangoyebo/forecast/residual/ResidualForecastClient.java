package com.jangoyebo.forecast.residual;

import com.jangoyebo.common.ai.AiProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import java.net.http.HttpClient;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 잔여 예측 워커 호출 (API 20.11) — <b>동기 15초</b>.
 *
 * <p><b>왜 {@code AiWorkerClient} 를 안 쓰는가.</b> 그 클래스는 비동기 접수만 다루고
 * ({@code requestAsync} 하나뿐) read 타임아웃이 5초다. 그 5초는 「202 는 금방 온다」와
 * 콜백 처리 예산에 묶여 있어 <b>올리면 다른 경로가 함께 느슨해진다.</b> 여기만 15초가
 * 필요하므로 클라이언트를 따로 둔다.
 *
 * <p>{@code AiProperties} 는 {@code common/ai} 에 있어 {@code forecast} 에서 보인다 —
 * core 통로가 필요 없다.
 *
 * <h2>Circuit Breaker 대용</h2>
 *
 * <p>공통규약은 연속 5회 실패 시 60초 open 을 요구하지만 resilience4j 의 Boot 4 대응이
 * 미확인이다. 대신 <b>연속 3회 실패 후 60초간 호출을 생략</b>한다. 워커가 죽어 있는 동안
 * 매 실행마다 15초를 버리지 않기 위해서다 — 04:00 배치가 사업장을 순회할 때 그 차이가 크다.
 *
 * <p>정식 Circuit Breaker 는 두 번째 동기 호출이 생길 때 도입한다.
 */
public class ResidualForecastClient {

    private static final Logger log = LoggerFactory.getLogger(ResidualForecastClient.class);

    private static final String PATH = "/internal/v1/ai/forecast-residual";

    /** 이만큼 연속 실패하면 잠시 쉰다. */
    static final int FAILURE_THRESHOLD = 3;

    /** 쉬는 시간. 공통규약의 60초와 같다. */
    static final long COOLDOWN_SECONDS = 60;

    private final RestClient client;
    private final AtomicInteger consecutiveFailures = new AtomicInteger();
    private final AtomicReference<Instant> skipUntil = new AtomicReference<>(Instant.EPOCH);

    public ResidualForecastClient(AiProperties properties) {
        HttpClient httpClient = HttpClient.newBuilder()
                .connectTimeout(properties.client().connectTimeout())
                .build();
        var requestFactory = new JdkClientHttpRequestFactory(httpClient);
        requestFactory.setReadTimeout(properties.client().forecastReadTimeout());

        this.client = RestClient.builder()
                .baseUrl(properties.baseUrl())
                .requestFactory(requestFactory)
                .defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer " + properties.serviceToken())
                .build();
    }

    /**
     * 잔여 분위수를 받아 온다.
     *
     * @param requestId 워커가 필수로 요구한다. 없으면 400 이다
     * @return 실패하면 {@code null} — <b>예외를 밖으로 내지 않는다.</b> 호출자는 통계로
     *         내려가면 되고, 「모델이 실패했다」는 예측 실패가 아니다(20.1 ③)
     */
    public ResidualDtos.Response forecast(ResidualDtos.Request request, String requestId) {
        if (Instant.now().isBefore(skipUntil.get())) {
            log.debug("워커가 연속 실패해 호출을 건너뛴다 (재개 {})", skipUntil.get());
            return null;
        }
        try {
            ResidualDtos.Response response = client.post()
                    .uri(PATH)
                    .contentType(MediaType.APPLICATION_JSON)
                    .header("X-Request-Id", requestId)
                    .body(request)
                    .retrieve()
                    .body(ResidualDtos.Response.class);
            consecutiveFailures.set(0);
            return response;
        } catch (RuntimeException e) {
            onFailure(e);
            return null;
        }
    }

    /**
     * 422 도 여기로 온다 — 과거 30일 미만이라는 뜻이고 <b>요청 잘못이 아니라 아직 예측할 수
     * 없는 상태</b>다. 우리는 그 전에 걸러서 호출하지 않지만, 창 계산이 어긋나면 여기 온다.
     */
    private void onFailure(RuntimeException e) {
        int count = consecutiveFailures.incrementAndGet();
        if (count >= FAILURE_THRESHOLD) {
            skipUntil.set(Instant.now().plusSeconds(COOLDOWN_SECONDS));
            consecutiveFailures.set(0);
            log.warn("잔여 워커 연속 {}회 실패 — {}초간 호출을 쉰다: {}",
                    FAILURE_THRESHOLD, COOLDOWN_SECONDS, e.toString());
        } else {
            log.warn("잔여 워커 호출 실패({}회차) — 통계로 내려간다: {}", count, e.toString());
        }
    }

    /** 테스트가 쉬는 상태를 초기화한다. */
    void reset() {
        consecutiveFailures.set(0);
        skipUntil.set(Instant.EPOCH);
    }
}
