package com.jangoyebo.core.report;

import com.jangoyebo.core.AccountBalances;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.TemporalAdjusters;
import java.util.List;

/**
 * 브리핑 배치 (API 15.2 — 「주간(매주 월요일)·월간(매월 1일) 배치로 생성한다」).
 *
 * <p><b>대상 기간은 언제나 「직전」이다.</b> 월요일에 도는 주간 브리핑은 <b>지난주 월~일</b>을
 * 본다. 오늘까지를 넣으면 월요일 새벽 한 건으로 한 주를 요약하게 된다.
 *
 * <p><b>05:00 인 이유.</b> 예측 재계산이 04:00 이고 정리가 04:30 이다. 브리핑 지표에 「향후
 * 30일 최저잔액」이 들어가므로 그날 곡선이 선 뒤여야 한다 — 순서가 반대면 어제 곡선을 읽는다.
 *
 * <h2>스케줄러 스레드는 하나다</h2>
 *
 * <p>{@code JobSweeper}·예측 배치·대출 배치가 같은 풀을 쓴다. 지표 계산은 사업장당 쿼리 몇 개라
 * 빠르지만, 사업장이 늘면 이 자리가 길어질 수 있다. 그때는 대출 배치처럼 Job 으로 미뤄야 한다.
 */
@Component
public class BriefingScheduler {

    private static final Logger log = LoggerFactory.getLogger(BriefingScheduler.class);
    private static final ZoneId KST = ZoneId.of("Asia/Seoul");

    private final AccountBalances accounts;
    private final BriefingService briefings;
    private final Clock clock;

    public BriefingScheduler(AccountBalances accounts, BriefingService briefings, Clock clock) {
        this.accounts = accounts;
        this.briefings = briefings;
        this.clock = clock;
    }

    /** 매주 월요일 — 지난주 월~일. */
    @Scheduled(cron = "${jango.briefing.weekly-cron:0 0 5 * * MON}", zone = "Asia/Seoul")
    public void generateWeekly() {
        LocalDate today = LocalDate.now(clock.withZone(KST));
        LocalDate start = today.with(TemporalAdjusters.previous(java.time.DayOfWeek.MONDAY));
        run(ReportType.WEEKLY, start, start.plusDays(6));
    }

    /** 매월 1일 — 지난달 1일~말일. */
    @Scheduled(cron = "${jango.briefing.monthly-cron:0 10 5 1 * *}", zone = "Asia/Seoul")
    public void generateMonthly() {
        LocalDate start = LocalDate.now(clock.withZone(KST)).minusMonths(1).withDayOfMonth(1);
        run(ReportType.MONTHLY, start, start.with(TemporalAdjusters.lastDayOfMonth()));
    }

    /**
     * <b>연결된 계좌가 있는 사업장만.</b> 계좌가 없으면 거래도 곡선도 없어 지표가 통째로 빈다 —
     * 예측 배치가 같은 판단을 한다.
     *
     * <p><b>한 사업장이 실패해도 나머지를 계속 만든다.</b> 사업장 하나 때문에 그 주 브리핑이
     * 통째로 없어지는 쪽이 훨씬 나쁘다.
     */
    private void run(ReportType type, LocalDate from, LocalDate to) {
        List<Long> businessIds = accounts.businessIdsWithConnectedAccounts();
        int created = 0;
        int skipped = 0;
        int failed = 0;

        for (Long businessId : businessIds) {
            try {
                if (briefings.generate(businessId, type, from, to).isPresent()) {
                    created++;
                } else {
                    skipped++;
                }
            } catch (RuntimeException e) {
                failed++;
                log.warn("브리핑을 만들지 못했다 — 사업장 {} {} {}~{}", businessId, type, from, to, e);
            }
        }

        log.info("{} 브리핑 생성 — 기간 {}~{} · 대상 {}곳 · 생성 {} · 지표 없어 건너뜀 {} · 실패 {}",
                type, from, to, businessIds.size(), created, skipped, failed);
    }
}
