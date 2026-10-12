package com.jangoyebo.forecast.planned;

import com.jangoyebo.common.domain.FlowType;
import com.jangoyebo.core.AccountExecutions.ExecutionResult;
import com.jangoyebo.core.AccountExecutions.LedgerEntry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * 자동 반영의 <b>짧은 트랜잭션</b>들 (API 9.6). {@link PlannedExecutionWorker} 가 단계마다 부른다.
 *
 * <p><b>왜 워커에서 갈라냈는가</b> — {@code AccountSyncService} 와 같은 두 이유다. 하나,
 * 금융망 왕복을 트랜잭션으로 감싸면 커넥션을 수 초간 쥔다. 둘, 워커 안에 {@code @Transactional}
 * 메서드를 두고 자기가 부르면 프록시를 안 타 <b>트랜잭션이 아예 안 열린다.</b>
 *
 * <p>단계마다 커밋하는 것이 안전한 근거는 <b>보내기 전에 원장을 먼저 커밋한다</b>는 것 하나다.
 * 그 행이 있으면 다음 차례는 「보냈을 수도 있다」로 읽고 금융망 거래내역부터 확인한다.
 */
@Service
public class PlannedExecutionService {

    private static final Logger log = LoggerFactory.getLogger(PlannedExecutionService.class);

    /** 금융망은 정수 원만 받는다. 1원 미만은 보낼 수 없다. */
    private static final BigDecimal MIN_AMOUNT = BigDecimal.ONE;

    private final PlannedTransactionRepository planned;
    private final PlannedExecutionRepository executions;

    public PlannedExecutionService(PlannedTransactionRepository planned,
                                   PlannedExecutionRepository executions) {
        this.planned = planned;
        this.executions = executions;
    }

    /**
     * 이번 차례에 다룰 것과, 이미 우리 것으로 잡아 둔 금융망 거래번호.
     *
     * <p>번호 집합을 함께 주는 이유는 <b>채택 후보에서 빼기 위해서다</b> — 이미 어떤 실행에
     * 붙어 있는 거래를 다른 예정 거래가 또 가져가면 {@code uk_pe_fin_tx} 가 막지만, 막히기
     * 전에 거르는 편이 실패 로그를 남기지 않아 읽기 좋다.
     */
    public record Plan(List<Candidate> candidates, Set<String> claimedFinIds) {

        public boolean isEmpty() {
            return candidates.isEmpty();
        }
    }

    /**
     * 이번 차례에 다룰 예정 거래 하나.
     *
     * @param ledgerId {@code null} 이면 아직 원장 행이 없다 — 처음 보내는 건이다
     * @param amount   실제로 보낼 정수 원. 예상액을 반올림해 {@code [min, max]} 안으로 맞췄다
     * @param ordinal  같은 {@code (예정일, 방향, 금액, 적요)} 안의 순번. 채번과 채택을 가른다
     */
    public record Candidate(Long ledgerId,
                            Long plannedTransactionId,
                            LocalDate dueDate,
                            FlowType flowType,
                            BigDecimal amount,
                            String summary,
                            int ordinal,
                            int attemptCount) {

        /** 아직 답을 못 받은 건. 보내기 전에 거래내역부터 확인해야 한다. */
        public boolean isOpen() {
            return ledgerId != null;
        }
    }

    /**
     * 이번 차례의 계획을 세운다.
     *
     * <p>순서는 <b>입금 먼저, 그다음 날짜</b>다. 같은 날 정산 입금이 지출을 덮는 순서라야
     * 시연 계좌가 잔액 부족으로 멈추지 않는다 — 곡선 계산기가 하루를 순액으로 보는 것과 같다.
     *
     * @param nowKst 지금 시각(KST). <b>당일자는 {@code earliestTime} 이후에만</b> 보낸다.
     *               지난 회차는 시각과 무관하게 따라잡는다 — 낮에 켠 노트북이 곧 집어야 한다
     */
    @Transactional(readOnly = true)
    public Plan plan(Long accountId, Long businessId, LocalDate today, LocalTime nowKst,
                     LocalTime earliestTime, int catchUpDays, boolean claimUnassigned) {

        List<PlannedTransaction> rows = planned.findForExecutionWindow(
                        businessId, today.minusDays(catchUpDays), today).stream()
                .filter(row -> belongsHere(row, accountId, claimUnassigned))
                .toList();
        if (rows.isEmpty()) {
            return new Plan(List.of(), Set.of());
        }

        Map<Long, PlannedExecution> ledgerByPlanned = new HashMap<>();
        for (PlannedExecution execution : executions.findByPlannedTransactionIdIn(
                rows.stream().map(PlannedTransaction::getId).toList())) {
            ledgerByPlanned.put(execution.getPlannedTransactionId(), execution);
        }

        // 순번은 실행 이력과 무관하게 센다 — 거르고 세면 한 건이 실행될 때마다 나머지가 밀린다.
        Map<String, Integer> seenByKey = new LinkedHashMap<>();
        List<Candidate> candidates = new ArrayList<>();

        for (PlannedTransaction row : rows) {
            BigDecimal amount = sendableAmount(row);
            int ordinal = seenByKey.merge(
                    key(row.getExpectedDate(), row.getFlowType(), amount, row.getName()),
                    0, (old, ignored) -> old + 1);

            PlannedExecution ledger = ledgerByPlanned.get(row.getId());
            if (ledger != null) {
                retryableOf(ledger, today).ifPresent(candidates::add);
                continue;
            }
            if (amount.compareTo(MIN_AMOUNT) < 0) {
                // 금융망은 정수 원만 받는다. 보낼 수 없으니 매 차례 조용히 건너뛴다 —
                // 원장에 남기지 않는 이유는 amount > 0 CHECK 때문에 사실과 다른 금액을
                // 적어야 하기 때문이다. 판정이 싸서 다시 세는 비용이 문제되지 않는다.
                log.debug("금액이 1원 미만이라 반영하지 않는다 — 예정 거래 {}", row.getId());
                continue;
            }
            if (row.getExpectedDate().isEqual(today) && nowKst.isBefore(earliestTime)) {
                continue;
            }
            candidates.add(new Candidate(null, row.getId(), row.getExpectedDate(),
                    row.getFlowType(), amount, row.getName(), ordinal, 0));
        }

        candidates.sort((a, b) -> {
            // 입금을 먼저 보낸다. 그래야 같은 날 지출이 잔액 부족으로 튕기지 않는다.
            int byFlow = Boolean.compare(a.flowType() != FlowType.DEPOSIT, b.flowType() != FlowType.DEPOSIT);
            if (byFlow != 0) {
                return byFlow;
            }
            int byDate = a.dueDate().compareTo(b.dueDate());
            return byDate != 0 ? byDate : Long.compare(a.plannedTransactionId(), b.plannedTransactionId());
        });

        return new Plan(candidates, Set.copyOf(executions.findClaimedFinTransactionIds(accountId)));
    }

    /**
     * 이 계좌에서 내보낼 행인가.
     *
     * <p><b>계좌가 비어 있는 행이 문제다.</b> 웹 등록 폼이 계좌를 보내기 전에 만들어진 예정
     * 거래가 그렇다. 그런 행은 <b>그 사업장에 자동 반영을 켠 계좌가 하나뿐일 때만</b> 집는다 —
     * 둘 이상이면 어느 통장에서 뺄지를 서버가 고르는 셈이고, 돈이 나가는 자리에서 그건
     * 좋은 기본값이 아니다.
     */
    private static boolean belongsHere(PlannedTransaction row, Long accountId, boolean claimUnassigned) {
        return row.getAccountId() == null ? claimUnassigned : row.getAccountId().equals(accountId);
    }

    /** 원장 행이 있는 건 중 이번 차례에 다시 다룰 것. 끝난 것과 영구 실패는 여기서 걸러진다. */
    private static Optional<Candidate> retryableOf(PlannedExecution ledger, LocalDate today) {
        boolean retryableFailure = ledger.getStatus() == PlannedExecutionStatus.FAILED
                && ledger.getRetryAfter() != null
                && !ledger.getRetryAfter().isAfter(today);

        if (!ledger.isOpen() && !retryableFailure) {
            return Optional.empty();
        }
        // 저장된 금액은 NUMERIC(19,4) 라 scale 4 로 돌아온다. 그대로 다시 보내면 금융망에
        // 소수점이 붙은 금액이 나가 Q1001 이다 — 재전송이 통째로 막힌다(테스트가 잡았다).
        return Optional.of(new Candidate(ledger.getId(), ledger.getPlannedTransactionId(),
                ledger.getDueDate(), ledger.getFlowType(),
                ledger.getAmount().setScale(0, RoundingMode.HALF_UP),
                ledger.getSummary(), ledger.getOrdinal(), ledger.getAttemptCount()));
    }

    /**
     * 보낼 금액 — 예상액을 정수 원으로 반올림하고 {@code [min, max]} 안으로 맞춘다.
     *
     * <p>반올림이 범위를 벗어날 수 있다(요일별 금액처럼 폭이 좁으면 실제로 그렇다). 범위 밖
     * 금액을 보내면 나중에 최소 매처가 <b>같은 거래를 못 붙인다</b> — 정확 매칭이 있어도
     * 그쪽이 실패했을 때의 안전망이 사라진다.
     */
    private static BigDecimal sendableAmount(PlannedTransaction row) {
        BigDecimal rounded = row.getAmountExpected().setScale(0, RoundingMode.HALF_UP);
        BigDecimal floor = row.getAmountMin().setScale(0, RoundingMode.CEILING);
        BigDecimal ceiling = row.getAmountMax().setScale(0, RoundingMode.FLOOR);
        if (rounded.compareTo(floor) < 0) {
            rounded = floor;
        }
        if (ceiling.compareTo(floor) >= 0 && rounded.compareTo(ceiling) > 0) {
            rounded = ceiling;
        }
        return rounded;
    }

    private static String key(LocalDate date, FlowType flowType, BigDecimal amount, String summary) {
        return date + "|" + flowType + "|" + amount.toPlainString() + "|" + summary;
    }

    /**
     * 보내기 <b>직전에</b> 원장을 연다. 돌려준 뒤 커밋된다.
     *
     * @return 원장 id. 같은 회차가 이미 있거나 예정 거래가 사라졌으면 비어 있다 — <b>보내지 않는다</b>
     */
    @Transactional
    public Optional<Long> openSent(Candidate candidate, Long accountId, String executorId, Instant now) {
        Optional<PlannedTransaction> row = planned.findById(candidate.plannedTransactionId());
        if (row.isEmpty()) {
            return Optional.empty();
        }
        Long patternId = row.get().getRecurringPatternId();
        if (patternId != null
                && executions.existsByRecurringPatternIdAndDueDate(patternId, candidate.dueDate())) {
            // 같은 패턴의 같은 회차가 이미 나갔다. 달력 재생성이 예정 거래를 새 id 로 다시
            // 만들면 이런 짝이 생긴다 — 행은 둘이지만 돈은 한 번이어야 한다.
            log.info("같은 회차가 이미 반영됐다 — 패턴 {} / {} (예정 거래 {})",
                    patternId, candidate.dueDate(), candidate.plannedTransactionId());
            return Optional.empty();
        }
        try {
            PlannedExecution execution = executions.saveAndFlush(PlannedExecution.sending(
                    row.get(), accountId, candidate.amount(), candidate.ordinal(), executorId, now));
            return Optional.of(execution.getId());
        } catch (DataIntegrityViolationException e) {
            // 위 사전 확인과 삽입 사이에 다른 스레드가 끼어든 경우다. 이 트랜잭션은 이미
            // 되돌리기로 표시돼 커밋에서 다시 터지므로(실측) 삼키지 않고 올린다 —
            // 워커가 이 건만 건너뛴다.
            log.warn("예정 거래 {} 실행 원장을 열지 못했다 — 이미 있는 회차다",
                    candidate.plannedTransactionId());
            throw e;
        }
    }

    /** 답을 못 받았던 건을 다시 보내기 직전. 시도 횟수만 올린다 — 채번은 그대로다. */
    @Transactional
    public void beginRetry(Long ledgerId, Instant now) {
        executions.findById(ledgerId).ifPresent(execution -> execution.beginAttempt(now));
    }

    /**
     * 금융망에 <b>이미 있던</b> 거래를 이 예정 거래의 것으로 인정한다 — 원장 행이 아직 없을 때.
     *
     * <p>다른 서버가 보냈거나, 우리가 지난 차례에 보내고 그 결과를 기록하지 못한 경우다.
     *
     * <p>⚠ <b>사용자 등록 행에는 이 경로가 잘 걸리지 않는다.</b> 패턴 행의 이름은 적요에서
     * 나와 금융망 거래와 글자가 같지만, 사용자 등록은 제목을 자유롭게 적는다 — 「에스프레소
     * 머신」과 통장 적요 「경동프리텍」은 안 맞는다. 이미 일어난 일을 뒤늦게 등록하면 한 번 더
     * 나갈 수 있다는 뜻이고, 그건 등록의 뜻이 「앞으로 나갈 것」이라는 전제에 기대어 둔다.
     */
    @Transactional
    public boolean adoptNew(Candidate candidate, Long accountId, LedgerEntry entry, Instant now) {
        Optional<PlannedTransaction> row = planned.findById(candidate.plannedTransactionId());
        if (row.isEmpty()) {
            return false;
        }
        if (row.get().getRecurringPatternId() != null && executions.existsByRecurringPatternIdAndDueDate(
                row.get().getRecurringPatternId(), candidate.dueDate())) {
            // 같은 회차가 이미 있다. 제약을 터뜨리면 이 트랜잭션을 되살릴 수 없다.
            return false;
        }
        executions.saveAndFlush(PlannedExecution.adopted(row.get(), accountId, candidate.amount(),
                candidate.ordinal(), entry.finTransactionUniqueNo(), entry.date(), now));
        logAdopted(candidate, entry);
        return true;
    }

    /**
     * 이미 열려 있는 원장 행에 「사실은 나갔다」를 적는다.
     *
     * <p>답을 못 받았던 건과, 방금 {@code H1007} 을 받은 건이 여기로 온다. <b>새 행을 만들지
     * 않는다</b> — {@code uk_pe_planned} 가 막기도 하고, 시도 횟수 같은 이력이 그 행에 있다.
     */
    @Transactional
    public boolean adoptInto(Long ledgerId, Candidate candidate, LedgerEntry entry, Instant now) {
        Optional<PlannedExecution> execution = executions.findById(ledgerId);
        if (execution.isEmpty()) {
            return false;
        }
        execution.get().markAdopted(entry.finTransactionUniqueNo(), entry.date(), now);
        logAdopted(candidate, entry);
        return true;
    }

    private static void logAdopted(Candidate candidate, LedgerEntry entry) {
        log.info("이미 나간 거래를 주웠다 — 예정 거래 {} / 거래 {} / {}",
                candidate.plannedTransactionId(), entry.finTransactionUniqueNo(), entry.date());
    }

    /**
     * 호출 결과를 원장에 적는다 (API 9.6 표).
     *
     * <p>{@code DUPLICATE_UNIQUE_NO} 는 <b>여기서 판정하지 않는다</b> — 「이미 나갔다」는 뜻이라
     * 거래내역을 다시 봐야 짝을 찾을 수 있고, 그건 워커의 일이다. {@code SENT} 로 남긴다.
     *
     * @param maxAttempts 답을 못 받은 건의 재전송 상한. 넘으면 영구 실패로 접는다
     */
    @Transactional
    public void record(Long ledgerId, ExecutionResult result, LocalDate today, int maxAttempts) {
        executions.findById(ledgerId).ifPresent(execution -> {
            Instant now = Instant.now();
            execution.rememberUniqueNo(result.institutionTransactionUniqueNo());
            switch (result.outcome()) {
                case EXECUTED -> execution.markExecuted(result.finTransactionUniqueNo(),
                        result.transactionDate(), now);
                case INSUFFICIENT_BALANCE -> execution.markFailed(result.finCode(),
                        // 결함이 아니라 사건이다 — 시연 계좌에 돈이 모자라 지출이 못 나갔다.
                        "잔액이 모자라 반영하지 못했다", today.plusDays(1), now);
                case REJECTED -> execution.markFailed(result.finCode(), result.message(), null, now);
                case DUPLICATE_UNIQUE_NO -> execution.recordAttempt(result.finCode(),
                        "같은 채번이 이미 쓰였다 — 거래내역에서 짝을 찾는다", now);
                case TRANSIENT -> {
                    if (execution.getAttemptCount() >= maxAttempts) {
                        execution.markFailed(result.finCode(),
                                "답을 받지 못한 채 재전송 상한(" + maxAttempts + "회)에 닿았다 — 거래내역을 직접 확인한다",
                                null, now);
                    } else {
                        execution.recordAttempt(result.finCode(), result.message(), now);
                    }
                }
            }
        });
    }

    /** 중복 채번이 왔는데 거래내역에서도 짝을 못 찾았다. 사람이 봐야 한다. */
    @Transactional
    public void failUnverifiedDuplicate(Long ledgerId, String finCode) {
        executions.findById(ledgerId).ifPresent(execution -> execution.markFailed(finCode,
                "중복 채번이나 거래내역에서 짝을 찾지 못했다 — 금융망에서 직접 확인한다",
                null, Instant.now()));
    }
}
