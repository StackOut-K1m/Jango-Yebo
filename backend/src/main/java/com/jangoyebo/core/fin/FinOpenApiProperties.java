package com.jangoyebo.core.fin;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;

/**
 * SSAFY 교육용 금융망 연동 설정 (기술스택 §3.6).
 *
 * <p>{@code base-url} 은 <b>도메인까지만</b> 잡는다({@code https://finopenapi.ssafy.io}).
 * 그 아래에서 경로가 둘로 갈리기 때문이다(공통규약 23장) — 회원 계열
 * {@code /ssafy/api/v1/member/**} 과 업무 계열 {@code /ssafy/api/v1/edu/**}.
 * 어느 쪽인지는 {@link FinOpenApiClient} 가 안다.
 *
 * <p>{@code api-key} 는 앱(팀) 전체가 공유하는 하나이고 {@code userKey} 는 사용자마다 하나다.
 * 시크릿이라 기본값을 두지 않는다 — <b>미주입 시 부팅 실패가 의도</b>이며
 * {@code AI_SERVICE_TOKEN} 과 같은 취급이다.
 *
 * <p>⚠ <b>{@code reIssuedApiKey}(MANAGER_02)를 호출하지 않는다.</b> 명세상 "확인"과 "재발급"이
 * 같은 엔드포인트라 <b>호출 즉시 기존 키가 무효화</b>되고, {@code E3003} 때문에 만료 후에는
 * 재발급조차 막힌다. 키를 잃어버렸을 때의 복구 경로는 코드가 아니라 팀장이다(공통규약 23장).
 *
 * @param institutionCode 교육용 공개 고정값 {@code 00100}. 시크릿이 아니다.
 * @param fintechAppNo    교육용 공개 고정값 {@code 001}.
 * @param client          호출 타임아웃. {@code DD_12} 는 한 달치를 한 번에 받으므로
 *                        AI 워커 접수(2초/5초)보다 읽기 여유를 크게 둔다.
 */
@Validated
@ConfigurationProperties(prefix = "jango.finopenapi")
public record FinOpenApiProperties(@NotBlank String baseUrl,
                                   @NotBlank String apiKey,
                                   @NotBlank String institutionCode,
                                   @NotBlank String fintechAppNo,
                                   @NotNull Client client,
                                   @NotNull AccountAuth accountAuth) {

    public record Client(@NotNull Duration connectTimeout, @NotNull Duration readTimeout) {
    }

    /**
     * 1원 인증 정책 (API 7.2).
     *
     * <p>⚠ <b>{@code ttl}·{@code maxResend} 값은 공통규약 23장의 열린 항목이다.</b>
     * 10분·3회로 시작하며, 팀이 정하면 설정값만 바꾼다 — 발급 기록을 Redis TTL 로 두었기에
     * 코드도 마이그레이션도 건드리지 않는다.
     *
     * @param authText  통장 적요에 찍히는 기업명. 금융망은 <b>{@code "{authText} {코드4자리}"}</b>
     *                  형태로 남긴다(실측: {@code 잔고예보 4653}).
     *                  <b>{@code ACCOUNT_AUTH_01} 과 {@code 02} 에서 같아야 한다.</b>
     * @param ttl       인증 유효시간. Redis 키의 TTL 이 곧 이 값이다.
     * @param maxResend 재발송 상한. 넘으면 {@code ACCOUNT_AUTH_EXPIRED} 로 막는다.
     */
    public record AccountAuth(@NotBlank String authText,
                              @NotNull Duration ttl,
                              @Positive int maxResend) {
    }
}
