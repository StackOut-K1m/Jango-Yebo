package com.jangoyebo.forecast.run;

import com.jangoyebo.core.AccountBalances;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;

/**
 * 예측 배치 두 가지 (공통규약 2.6).
 *
 * <p><b>매일 04:00 전 사업장 재계산</b> — 트리거가 유실돼도 하루 안에 회수하는 안전망이다.
 * 이벤트 발행을 영속화하지 않기로 한 근거이기도 하다. 새벽인 이유는 그때가 예정 거래의
 * 날짜 경계가 막 넘어간 시점이라, 사용자가 아침에 열면 오늘 기준 곡선이 이미 서 있다.
 *
 * <p><b>매일 04:30 오래된 실행 정리</b> — 보존 30일. 재계산 배치와 시각을 벌려 둔 이유는
 * 아래 스레드 사정 때문이다.
 *
 * <h2>스케줄러 스레드는 하나다</h2>
 *
 * <p>Spring 기본 스케줄러 풀 크기가 1이고 {@code JobSweeper} 가 같은 풀을 쓴다. 여기서
 * 사업장을 순회하며 <b>계산까지 하면 그동안 스위퍼가 멈춘다</b> — 멈춘 Job 이 회수되지 않는다.
 * 그래서 이 메서드들은 <b>표시만 남기고 즉시 반환</b>하고, 실제 계산은 forecast 전용 스레드가
 * 디바운스 뒤에 한 사업장씩 처리한다. 풀 크기를 늘리지 않는 이유는
 * {@code ForecastRunWorker} 의 javadoc 에 있다 — 풀 빈을 추가하면 Boot 기본 빈이 사라진다.
 */
@Component
public class ForecastBatchScheduler {

    private static final Logger log = LoggerFactory.getLogger(ForecastBatchScheduler.class);

    /** 실행 보존 기간 (공통규약 2.6). */
    static final int RETENTION_DAYS = 30;

    private final AccountBalances accountBalances;
    private final ForecastRunWorker worker;
    private final ForecastRunRepository runs;

    public ForecastBatchScheduler(AccountBalances accountBalances,
                                  ForecastRunWorker worker,
                                  ForecastRunRepository runs) {
        this.accountBalances = accountBalances;
        this.worker = worker;
        this.runs = runs;
    }

    /**
     * 연결된 계좌가 있는 모든 사업장의 곡선을 다시 계산하도록 표시한다.
     *
     * <p>계좌가 없는 사업장은 시작 잔액이 없어 곡선을 만들 수 없으므로 대상이 아니다.
     */
    @Scheduled(cron = "${jango.forecast.daily-cron:0 0 4 * * *}", zone = "Asia/Seoul")
    @Transactional(readOnly = true)
    public void scheduleDailyRecalculation() {
        // enabled 를 여기서 다시 보지 않는다. 스레드가 도는지 아는 것은
        // requestRecalculation 하나이고, 꺼져 있으면 표시만 남기고 반환한다
        // (표시에는 TTL 이 있어 쌓이지 않는다). 판단을 두 곳에 두면 갈린다.
        List<Long> businessIds = accountBalances.businessIdsWithConnectedAccounts();
        for (Long businessId : businessIds) {
            worker.requestRecalculation(businessId);
        }
        log.info("일일 예측 재계산 예약 — 사업장 {}곳", businessIds.size());
    }

    /**
     * 30일 지난 실행을 지운다. 일별 곡선은 {@code ON DELETE CASCADE} 가 함께 지운다.
     *
     * <p>사업장별 최신 {@code COMPLETED} 한 건은 오래됐어도 남는다 — 쿼리가 그렇게 짜여 있다.
     */
    @Scheduled(cron = "${jango.forecast.cleanup-cron:0 30 4 * * *}", zone = "Asia/Seoul")
    public void cleanUpOldRuns() {
        Instant cutoff = Instant.now().minus(RETENTION_DAYS, ChronoUnit.DAYS);
        int deleted = runs.deleteOlderThanKeepingLatest(cutoff);
        if (deleted > 0) {
            log.info("오래된 예측 실행 정리 — {}건 삭제 (기준 {}일)", deleted, RETENTION_DAYS);
        }
    }
}
