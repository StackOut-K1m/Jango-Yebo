package com.jangoyebo.forecast.planned;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;
import java.time.LocalTime;

/**
 * 예정 거래 자동 반영 설정 (API 9.6).
 *
 * @param enabled       실행 스레드 기동 여부. <b>테스트는 끈다</b> — 켜 두면 Testcontainers 가
 *                      만든 계좌에 실제 금융망 호출이 나간다(설정상 호출 주소는 더미라 실패하지만
 *                      로그가 뒤덮인다). {@code ForecastProperties#enabled} 와 같은 자리다
 * @param pollInterval  차례 사이 간격. 예정 거래는 하루 단위라 자주 볼 이유가 없다
 * @param initialDelay  기동 후 첫 차례까지. <b>짧게 둔다</b> — 노트북을 낮에 켠 개발자가 밀린
 *                      회차를 곧 보게 하는 것이 이 값의 목적이다
 * @param earliestTime  당일자를 보내기 시작하는 시각(KST). 04:00~04:35 는 예측 배치·정리 배치·
 *                      호스트 백업이 겹치는 구간이라 피한다. 금융망 예약이체 재실행도 09:00 이다
 * @param catchUpDays   오늘보다 이만큼 지난 예정일까지 따라잡는다. <b>거래내역 사전 조회 창의
 *                      길이이기도 하다</b> — 다른 서버가 예정일에 보낸 것을 우리가 늦게 보려면
 *                      그 날짜까지 거슬러 봐야 한다
 * @param maxAttempts   {@code SENT} 로 남은 건의 재전송 상한. 넘으면 영구 실패로 접고 사람이 본다
 * @param maxPerAccountPerTick 한 차례에 한 계좌로 보낼 수 있는 최대 건수. 설정이 잘못돼 대량으로
 *                      나가는 사고의 상한이다
 */
@Validated
@ConfigurationProperties(prefix = "jango.planned-execution")
public record PlannedExecutionProperties(boolean enabled,
                                         @NotNull Duration pollInterval,
                                         @NotNull Duration initialDelay,
                                         @NotNull LocalTime earliestTime,
                                         @Min(0) @Max(30) int catchUpDays,
                                         @Min(1) int maxAttempts,
                                         @Min(1) int maxPerAccountPerTick) {
}
