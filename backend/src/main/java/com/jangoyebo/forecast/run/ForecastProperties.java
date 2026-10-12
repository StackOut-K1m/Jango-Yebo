package com.jangoyebo.forecast.run;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.math.BigDecimal;
import java.time.Duration;

/**
 * 예측 실행 설정 (공통규약 2.6 캐시 전략).
 *
 * @param horizonDays  한 실행이 계산하는 일수. <b>조회의 {@code days}(7·14·30)와 다르다</b> —
 *                     실행은 항상 이 값으로 한 번 하고 조회에서 자른다. {@code ix_fr_latest} 가
 *                     기간을 구분하지 않아 기간별로 저장하면 "최신"이 엉뚱한 실행을 가리킨다
 * @param debounce     같은 사업장의 재계산 요청을 모으는 시간. 없으면 CSV 200건 업로드에
 *                     200회 재계산이 돈다(공통규약 2.6)
 * @param lockTtl      사업장 단위 실행 락의 수명. 실행 중 프로세스가 죽어도 이 시간 뒤 풀린다.
 *                     워커 호출(15초)에 DB 왕복을 더한 것보다 넉넉해야 한다
 * @param dirtyTtl     재계산 대기 표시의 수명. Redis 는 {@code volatile-lru} 라 <b>모든 키에
 *                     TTL 이 필수</b>다(공통규약 23장)
 * @param residualMode 잔여를 어디까지 쓸 상한인가. 매칭이 서기 전까지 {@code NONE} 이다 —
 *                     매칭 없이 모델을 태우면 예정 거래를 두 번 센다(설계노트 M-1)
 * @param judgmentAlpha 모델을 쓰지 않을 때의 판정 보수성. 모델 경로는 워커가 준 값을 쓰고,
 *                      통계·미적용 경로가 이 값을 쓴다. <b>두 경로에 같은 α 여야</b>
 *                      채택 비교가 성립한다(설계노트 M-3)
 * @param enabled      실행 스레드 기동 여부. 테스트는 꺼서 {@code execute} 를 직접 부른다
 */
@Validated
@ConfigurationProperties(prefix = "jango.forecast")
public record ForecastProperties(@Min(1) @Max(90) int horizonDays,
                                 @NotNull Duration debounce,
                                 @NotNull Duration lockTtl,
                                 @NotNull Duration dirtyTtl,
                                 @NotNull ResidualMode residualMode,
                                 @NotNull BigDecimal judgmentAlpha,
                                 boolean enabled) {

    /**
     * 잔여 축을 어디까지 켤지의 <b>상한</b>이다. 실제 출처는 폴백 사슬이 정한다 —
     * {@code MODEL} 로 두어도 워커가 죽으면 {@code BASELINE} 이나 {@code NONE} 이 된다.
     */
    public enum ResidualMode {
        /** 잔여를 쓰지 않는다. 매칭이 서기 전까지의 기본값. */
        NONE,
        /** 통계 베이스라인까지. 워커를 부르지 않는다. */
        BASELINE,
        /** 워커를 부르고 실패하면 통계로 내려간다. */
        MODEL
    }
}
