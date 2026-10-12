package com.jangoyebo.core.transaction;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.math.BigDecimal;

/**
 * 거래 분류(P1-01) 설정.
 *
 * @param confidenceThreshold 이 값 미만이면 {@code needs_confirmation = true} 로 둔다
 *                            (API 20.3 — 기본 0.85). <b>설정값으로 빼는 이유</b>는 이 값이
 *                            사용자가 확인할 물량을 직접 정하기 때문이다 — 실측 수락률이
 *                            0.9927 이라 나중에 내릴 여지가 있고, 그때 코드를 고치고
 *                            배포하는 일이 되어서는 안 된다.
 *                            <p>0~1 범위를 검증한다. 1 을 넘기면 모든 거래가 확인 대기로
 *                            쌓이고 0 이면 아무것도 확인받지 않는데, 둘 다 조용히 동작해서
 *                            화면에서야 드러난다.
 * @param batchSize           한 Job 이 워커에 보낼 거래 수 (기본 20). 워커는
 *                            {@code REQUEST_MAX}(400) 까지 받고 그 안에서 20건씩 잘라
 *                            순차로 부른다(합의 ①). 400 = 20청크 × 실측 15초 = 약 300초로
 *                            {@code jango.job.stall-timeout}(10분)의 절반이라, <b>이 값을
 *                            올릴 때는 그 예산을 먼저 확인</b>해야 한다 — 넘기면 아직 도는
 *                            Job 을 스위퍼가 회수해 중복 실행된다.
 * @param autoTrigger         동기화·CSV 임포트가 끝나면 분류를 <b>스스로</b> 걸 것인가
 *                            (API 8.5 "자동 실행"). <b>기본값이 꺼짐이다.</b>
 *                            <p>켜면 거래가 들어올 때마다 LLM 을 부른다 — 시드를 넣고 빼며
 *                            시험하는 동안에는 그 값이 그대로 토큰이 된다. 끄면 분류는
 *                            <b>8.5 버튼으로만</b> 돈다({@code POST
 *                            /businesses/{id}/classification-jobs}) — 언제 값을 치를지
 *                            사람이 정한다.
 *                            <p>끈 상태가 「분류가 고장났다」로 읽히지 않게 하는 것이
 *                            이 값이 설정으로 나와 있는 이유다. 이 스위치가 생기기 전에는
 *                            자동 실행이 <b>코드상 켜져 있는데 실제로는 돌지 않았다</b>
 *                            ({@code TransactionClassificationTriggerListener} 머리말).
 * @param fewShotSize         프롬프트에 붙일 예시 수 (기본 10). 예시는 프리픽스 캐시에
 *                            올라가 하루에 한 번만 값을 치르므로 늘리는 비용이 작다. 다만
 *                            <b>몇 개가 최적인지는 재지 않았다</b> — 실측 근거가 생기면
 *                            그때 기본값을 옮긴다. 0 이면 예시 없이 보낸다(계약에서 선택
 *                            필드다).
 */
@Validated
@ConfigurationProperties(prefix = "jango.classification")
public record TransactionClassificationProperties(
        @NotNull @DecimalMin("0.0") @DecimalMax("1.0") BigDecimal confidenceThreshold,
        @Min(1) @Max(400) int batchSize,
        @Min(0) @Max(100) int fewShotSize,
        boolean autoTrigger) {
}
