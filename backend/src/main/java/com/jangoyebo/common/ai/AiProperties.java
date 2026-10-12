package com.jangoyebo.common.ai;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;

/**
 * {@code jango-ai} 워커 연동 설정.
 *
 * <p>{@code common} 에 두는 이유: 서비스 토큰을 인바운드 필터({@code common.security})와
 * 아웃바운드 클라이언트({@code core.ai})가 모두 쓴다. {@code common} 은 다른 모듈을 참조할 수
 * 없으므로 프로퍼티가 아래쪽에 있어야 양쪽이 볼 수 있다.
 *
 * <p>{@code service-token} 은 시크릿이라 기본값을 두지 않는다 — 미주입 시 부팅 실패가 의도다.
 * 워커의 {@code SERVICE_TOKEN}({@code ai/.env})과 <b>값이 같아야</b> 한다. 파일이 분리돼 있어
 * 환경변수 이름은 달라도 된다.
 *
 * @param client   워커 호출 타임아웃. 비동기 접수(202)는 금방 오므로 짧게 둔다 —
 *                 워커가 멈춰도 폴링 워커 스레드가 오래 묶이지 않게 한다.
 * @param callback 콜백 수신 측 설정.
 */
@Validated
@ConfigurationProperties(prefix = "jango.ai")
public record AiProperties(@NotBlank String baseUrl,
                           @NotBlank String serviceToken,
                           @NotNull Client client,
                           @NotNull Callback callback) {

    /**
     * @param readTimeout         비동기 접수(202) 전용. <b>올리지 않는다</b> — 콜백 처리
     *                            예산(5초)과 묶여 있어 여기를 늘리면 다른 경로가 함께
     *                            느슨해진다
     * @param forecastReadTimeout 잔여 예측(20.11)은 <b>동기 15초</b>다. 유일한 동기 라우트라
     *                            위 값과 따로 둔다 — 5초로 부르면 규격보다 먼저 끊긴다
     */
    public record Client(@NotNull Duration connectTimeout,
                         @NotNull Duration readTimeout,
                         @NotNull Duration forecastReadTimeout) {
    }

    /**
     * @param idempotencyTtl 처리 완료 기록 보관 기간. 공통규약 23장 — 24시간.
     * @param claimTtl       처리 중 선점 TTL. <b>스위퍼 임계값(기본 10분)보다 반드시 짧아야
     *                       한다.</b> 선점 직후 JVM 이 죽어도 이 시간 뒤 키가 사라져야,
     *                       스위퍼가 회수해 재실행했을 때 콜백이 막히지 않는다.
     */
    public record Callback(@NotNull Duration idempotencyTtl, @NotNull Duration claimTtl) {
    }
}
