package com.jangoyebo.core.job;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;

/**
 * Job 워커·스위퍼 설정.
 *
 * <p>{@code common} 이 아니라 여기 두는 이유: {@code common} 은 다른 모듈을 참조하지 않고
 * (common/package-info.java) 이 설정은 {@code core.job} 만 쓴다.
 *
 * <p><b>{@code sweep-interval} 은 여기에 없다.</b> {@code @Scheduled(fixedDelayString)} 이
 * 프로퍼티를 직접 읽으므로, record 가 같은 키를 다시 읽으면 아무도 쓰지 않는 필드가 된다.
 *
 * @param stallTimeout {@code PROCESSING} 잔류 판정 기준. 공통규약 23장 — 10분.
 *                     ⚠ 동기 완결형 핸들러의 실행 시간 상한이기도 하다. 이보다 오래 도는
 *                     핸들러는 실행 중에 스위퍼가 requeue 해 중복 실행된다.
 * @param maxAttempts  획득 시도 총 횟수 상한. 2 = 최초 1회 + 재시도 1회(공통규약 23장).
 *                     {@code CLAIM_SQL} 이 획득마다 올리므로 첫 획득 직후 값은 1 이다.
 * @param workerCount  폴링 루프 스레드 수. 0 이면 워커를 기동하지 않는다.
 *                     상한 3 은 {@code spring.datasource.hikari} 주석의 "워커 몫 2~3"
 *                     예산을 주석이 아니라 부팅 검증으로 옮긴 것이다.
 *                     {@code @Min(1)} 을 붙이면 안 된다 — 테스트가 0 을 주입한다.
 * @param rejectionRetryDelay 워커가 받지 못한(202 미수신) 작업을 대기열로 되돌린 뒤 다시
 *                     집기까지 기다리는 시간. <b>{@code stallTimeout} 과 다른 축이다</b> —
 *                     그쪽은 "접수는 됐는데 답이 없다", 이쪽은 "접수조차 안 됐다" 이고 후자는
 *                     외부에 일이 떠 있지 않아 빨리 다시 보내도 중복이 없다. 짧을수록 워커
 *                     재시작을 빨리 따라가고, 너무 짧으면 워커가 죽어 있는 동안 로그만 쌓인다.
 * @param pollInterval 큐가 비었을 때 다음 폴링까지 대기. {@code GET /jobs/{jobId}} 체감
 *                     지연의 하한이며, 짧으면 빈 claim 쿼리가 늘어난다.
 */
@Validated
@ConfigurationProperties(prefix = "jango.job")
public record JobProperties(@NotNull Duration stallTimeout,
                            @Min(1) int maxAttempts,
                            @PositiveOrZero @Max(3) int workerCount,
                            @NotNull Duration rejectionRetryDelay,
                            @NotNull Duration pollInterval) {
}
