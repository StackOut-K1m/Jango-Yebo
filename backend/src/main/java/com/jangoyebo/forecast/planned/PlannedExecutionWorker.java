package com.jangoyebo.forecast.planned;

import com.jangoyebo.core.AccountExecutions;
import com.jangoyebo.core.AccountExecutions.ExecutableAccount;
import com.jangoyebo.core.AccountExecutions.ExecutionRequest;
import com.jangoyebo.core.AccountExecutions.ExecutionResult;
import com.jangoyebo.core.AccountExecutions.LedgerEntry;
import com.jangoyebo.core.AccountExecutions.Outcome;
import com.jangoyebo.forecast.planned.PlannedExecutionService.Candidate;
import com.jangoyebo.forecast.planned.PlannedExecutionService.Plan;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;

import java.lang.management.ManagementFactory;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * 예정 거래를 금융망에 반영하는 실행 스레드 (API 9.6).
 *
 * <p><b>왜 이 기능이 있는가.</b> 확정한 반복 패턴은 날짜별 예정 거래를 만들지만 그건 우리
 * DB 안의 계획일 뿐이라, 예정일이 지나도 연결된 계좌에서는 아무 일도 일어나지 않는다. 그 행은
 * {@code EXPECTED} 인 채 곡선 창 밖으로 밀려나고 잔액은 그대로다 — 시연 데이터가 하루하루
 * 낡는다. 이 스레드가 그 간극을 메운다.
 *
 * <h2>스레드를 빈으로 등록하지 않는다</h2>
 *
 * <p>{@code ForecastRunWorker} 와 같은 이유다. Boot 기본 {@code taskScheduler} 가
 * {@code @ConditionalOnMissingBean} 이라 풀 빈을 하나라도 추가하면 {@code JobSweeper} 가
 * 우리 풀로 옮겨 탄다. {@link SmartLifecycle} 로 자기 풀을 소유한다.
 *
 * <h2>보내기 전에 반드시 금융망 거래내역을 본다</h2>
 *
 * <p>로컬 서버와 배포 서버는 DB 도 Redis 도 서로 보지 못하는데 <b>같은 시연 계좌</b>를 가리킬
 * 수 있다. 그래서 「이미 나갔는가」의 유일한 공통 근거가 금융망 자신이다. 조회가 실패하면
 * <b>그 계좌는 이번 차례에 아무것도 보내지 않는다</b> — 근거 없이 보내면 중복을 막을 방법이 없다.
 */
@Component
public class PlannedExecutionWorker implements SmartLifecycle {

    private static final Logger log = LoggerFactory.getLogger(PlannedExecutionWorker.class);

    /**
     * 「오늘」과 「지금 몇 시」를 정하는 시간대.
     *
     * <p>앱 공용 {@code Clock} 은 {@code systemDefaultZone()} 이라 컨테이너 TZ 에 의존한다.
     * 예정일은 사업장의 벽시계 날짜여야 하므로 {@code ForecastRunWorker}·{@code AccountSyncHandler}
     * 처럼 여기서 KST 를 명시한다.
     */
    private static final ZoneId KST = ZoneId.of("Asia/Seoul");

    private static final int SHUTDOWN_GRACE_SECONDS = 5;

    private final AccountExecutions accountExecutions;
    private final PlannedExecutionService service;
    private final PlannedExecutionProperties properties;
    private final Clock clock;

    /** 원장에 남기는 실행기 이름. 두 환경이 같은 계좌를 건드렸을 때 사후 추적의 유일한 단서다. */
    private final String executorId = ManagementFactory.getRuntimeMXBean().getName();

    private volatile boolean running;
    private ScheduledExecutorService executor;

    public PlannedExecutionWorker(AccountExecutions accountExecutions,
                                  PlannedExecutionService service,
                                  PlannedExecutionProperties properties,
                                  Clock clock) {
        this.accountExecutions = accountExecutions;
        this.service = service;
        this.properties = properties;
        this.clock = clock;
    }

    /**
     * 한 차례 돈다.
     *
     * <p>{@code package-private} 이라 테스트가 스레드를 띄우지 않고 직접 부른다
     * ({@code ForecastRunWorker.execute}·{@code JobWorker.pollOnce} 와 같은 이음매).
     *
     * @return 이번 차례에 실제로 반영된 건수(보낸 것 + 주운 것)
     */
    int runOnce() {
        LocalDate today = LocalDate.now(clock.withZone(KST));
        LocalTime nowKst = LocalTime.now(clock.withZone(KST));

        List<ExecutableAccount> accounts = accountExecutions.autoExecuteAccounts();
        // 사업장에 켠 계좌가 하나뿐이면, 계좌가 비어 있는 예정 거래도 그 계좌 몫으로 본다.
        Map<Long, Long> countByBusiness = accounts.stream().collect(
                Collectors.groupingBy(ExecutableAccount::businessId, Collectors.counting()));

        int total = 0;
        for (ExecutableAccount account : accounts) {
            boolean claimUnassigned = countByBusiness.get(account.businessId()) == 1L;
            total += runAccount(account, today, nowKst, claimUnassigned);
        }
        return total;
    }

    private int runAccount(ExecutableAccount account, LocalDate today, LocalTime nowKst,
                           boolean claimUnassigned) {
        Plan plan = service.plan(account.accountId(), account.businessId(), today, nowKst,
                properties.earliestTime(), properties.catchUpDays(), claimUnassigned);
        if (plan.isEmpty()) {
            return 0;
        }

        List<LedgerEntry> available;
        try {
            available = unclaimed(fetch(account.accountId(), today), plan.claimedFinIds());
        } catch (RuntimeException e) {
            // 사전 조회가 안 되면 「이미 나갔는가」를 알 수 없다. 보내지 않는 쪽이 맞다.
            log.warn("계좌 {} 금융망 거래내역을 읽지 못해 이번 차례를 건너뛴다: {}",
                    account.accountId(), e.toString());
            return 0;
        }

        int applied = 0;
        int sent = 0;
        for (Candidate candidate : plan.candidates()) {
            Optional<LedgerEntry> already = pick(available, candidate, today);
            if (already.isPresent()) {
                if (adopt(candidate, account.accountId(), candidate.ledgerId(), already.get())) {
                    available.remove(already.get());
                    applied++;
                }
                continue;
            }
            if (sent >= properties.maxPerAccountPerTick()) {
                log.warn("계좌 {} 한 차례 전송 상한({})에 닿아 나머지는 다음 차례로 미룬다",
                        account.accountId(), properties.maxPerAccountPerTick());
                break;
            }
            sent++;
            try {
                    if (send(account.accountId(), candidate, today, available)) {
                    applied++;
                }
            } catch (RuntimeException e) {
                // 한 건이 실패해도 나머지는 보낸다. 원장에 흔적이 남았으면 다음 차례가 잇는다.
                log.warn("예정 거래 {} 를 반영하지 못했다: {}",
                        candidate.plannedTransactionId(), e.toString());
            }
        }

        if (applied > 0) {
            // 방금 만든 거래를 우리 DB 로 가져온다. 그 뒤 재계산이 정확 매칭까지 밀어 준다.
            accountExecutions.requestSync(account.accountId());
            log.info("예정 거래 자동 반영 — 계좌 {} / {}건 반영", account.accountId(), applied);
        }
        return applied;
    }

    /**
     * 한 건을 보낸다.
     *
     * <p><b>원장을 먼저 커밋하고 그다음에 부른다.</b> 순서를 뒤집으면 응답을 기다리다 죽었을 때
     * 흔적이 없어 다음 차례가 또 보낸다.
     *
     * @return 실제로 반영됐으면 {@code true}
     */
    private boolean send(Long accountId, Candidate candidate, LocalDate today,
                         List<LedgerEntry> available) {
        Long ledgerId;
        if (candidate.isOpen()) {
            service.beginRetry(candidate.ledgerId(), Instant.now());
            ledgerId = candidate.ledgerId();
        } else {
            Optional<Long> opened = service.openSent(candidate, accountId, executorId, Instant.now());
            if (opened.isEmpty()) {
                return false;
            }
            ledgerId = opened.get();
        }

        ExecutionResult result = accountExecutions.execute(new ExecutionRequest(
                accountId, candidate.dueDate(), candidate.flowType(),
                candidate.amount(), candidate.summary(), candidate.ordinal()));
        service.record(ledgerId, result, today, properties.maxAttempts());

        if (result.outcome() == Outcome.DUPLICATE_UNIQUE_NO) {
            return resolveDuplicate(accountId, candidate, ledgerId, today, available, result);
        }
        return result.isExecuted();
    }

    /**
     * 같은 채번이 이미 쓰였다 — <b>누군가 이 실행을 이미 보냈다는 뜻이다.</b>
     *
     * <p>새 번호로 다시 부르면 돈이 두 번 나간다. 대신 거래내역을 <b>다시</b> 읽어 짝을 찾는다 —
     * 이번 차례 시작 뒤에 들어온 거래라 앞서 읽은 목록에는 없을 수 있다.
     */
    private boolean resolveDuplicate(Long accountId, Candidate candidate, Long ledgerId,
                                     LocalDate today, List<LedgerEntry> available,
                                     ExecutionResult result) {
        List<LedgerEntry> refreshed;
        try {
            refreshed = unclaimed(fetch(accountId, today), Set.of());
        } catch (RuntimeException e) {
            // 다음 차례가 다시 본다. SENT 로 남아 있으므로 잃는 것이 없다.
            log.warn("계좌 {} 중복 채번 확인용 조회에 실패했다 — 다음 차례로 미룬다: {}",
                    accountId, e.toString());
            return false;
        }

        Optional<LedgerEntry> match = pick(refreshed, candidate, today);
        if (match.isEmpty()) {
            log.error("계좌 {} 예정 거래 {} — 중복 채번인데 거래내역에 짝이 없다. 금융망에서 직접 확인한다",
                    accountId, candidate.plannedTransactionId());
            service.failUnverifiedDuplicate(ledgerId, result.finCode());
            return false;
        }
        // 원장 행은 보내기 직전에 이미 열어 두었다. 새 행을 만들려 하면 uk_pe_planned 가 막는다.
        boolean adopted = adopt(candidate, accountId, ledgerId, match.get());
        if (adopted) {
            available.remove(match.get());
        }
        return adopted;
    }

    /** 열린 원장 행이 있으면 그 행을 채우고, 없으면 새로 만든다. */
    private boolean adopt(Candidate candidate, Long accountId, Long ledgerId, LedgerEntry entry) {
        Instant now = Instant.now();
        return ledgerId == null
                ? service.adoptNew(candidate, accountId, entry, now)
                : service.adoptInto(ledgerId, candidate, entry, now);
    }

    private List<LedgerEntry> fetch(Long accountId, LocalDate today) {
        return accountExecutions.transactions(
                accountId, today.minusDays(properties.catchUpDays()), today);
    }

    /** 이미 어떤 실행에 붙어 있는 거래는 후보가 아니다. */
    private static List<LedgerEntry> unclaimed(List<LedgerEntry> entries, Set<String> claimed) {
        Set<String> taken = new HashSet<>(claimed);
        List<LedgerEntry> available = new ArrayList<>(entries.size());
        for (LedgerEntry entry : entries) {
            if (entry.finTransactionUniqueNo() != null && taken.add(entry.finTransactionUniqueNo())) {
                available.add(entry);
            }
        }
        return available;
    }

    /**
     * 이 예정 거래에 해당하는 금융망 거래가 이미 있는가.
     *
     * <p>같은 방향·금액·적요이고 <b>예정일 이후 오늘까지</b>에 찍힌 것을 본다. 예정일보다 앞선
     * 거래는 이 회차가 아니다 — 금융망은 과거 날짜를 만들 수 없으므로 예정일에 보냈다면 그날
     * 이후에만 찍힌다.
     *
     * <p>여럿이면 <b>예정일에 가장 가까운 것</b>을 고르고, 같으면 거래번호가 작은 쪽이다.
     * 두 서버가 같은 목록을 보고 같은 짝을 지어야 하므로 순서가 결정적이어야 한다.
     */
    private static Optional<LedgerEntry> pick(List<LedgerEntry> available, Candidate candidate,
                                              LocalDate today) {
        return available.stream()
                .filter(entry -> entry.flowType() == candidate.flowType())
                .filter(entry -> entry.amount().compareTo(candidate.amount()) == 0)
                .filter(entry -> candidate.summary().equals(entry.summary()))
                .filter(entry -> !entry.date().isBefore(candidate.dueDate())
                        && !entry.date().isAfter(today))
                .min(Comparator.comparing((LedgerEntry entry) -> entry.date())
                        .thenComparing(LedgerEntry::finTransactionUniqueNo));
    }

    // --- SmartLifecycle -----------------------------------------------------

    @Override
    public boolean isAutoStartup() {
        return properties.enabled();
    }

    @Override
    public synchronized void start() {
        if (running) {
            return;
        }
        executor = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "planned-execution-worker");
            thread.setDaemon(true);
            return thread;
        });
        running = true;
        executor.scheduleWithFixedDelay(this::tickSafely,
                properties.initialDelay().toMillis(),
                properties.pollInterval().toMillis(),
                TimeUnit.MILLISECONDS);
        log.info("예정 거래 자동 반영 스레드 기동 — 주기 {}, 당일 시작 {}, 따라잡기 {}일",
                properties.pollInterval(), properties.earliestTime(), properties.catchUpDays());
    }

    /**
     * 예외를 밖으로 내지 않는다.
     *
     * <p>{@code scheduleWithFixedDelay} 는 한 번 던지면 <b>이후 실행이 영영 취소된다.</b>
     * 자동 반영이 조용히 멈추면 며칠 뒤에야 「예보가 안 움직인다」로 발견된다
     * ({@code JobWorker} 가 {@code Throwable} 까지 받는 것과 같은 이유).
     */
    private void tickSafely() {
        try {
            runOnce();
        } catch (Throwable t) {
            log.warn("예정 거래 자동 반영 차례가 실패했다 — 다음 차례에 다시 시도한다", t);
        }
    }

    @Override
    public synchronized void stop() {
        if (!running) {
            return;
        }
        running = false;
        executor.shutdown();
        try {
            if (!executor.awaitTermination(SHUTDOWN_GRACE_SECONDS, TimeUnit.SECONDS)) {
                executor.shutdownNow();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            executor.shutdownNow();
        }
        log.info("예정 거래 자동 반영 스레드 종료");
    }

    @Override
    public boolean isRunning() {
        return running;
    }
}
