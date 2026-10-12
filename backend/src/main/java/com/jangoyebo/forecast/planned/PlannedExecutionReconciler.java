package com.jangoyebo.forecast.planned;

import com.jangoyebo.common.error.BusinessException;
import com.jangoyebo.core.TransactionHistory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 자동 반영한 거래를 예정 거래에 <b>정확히</b> 붙인다 (API 9.6).
 *
 * <h2>왜 최소 매처(9.5)로는 안 되는가</h2>
 *
 * <p>매처는 금액·날짜로 <b>추정</b>한다. 허용폭이 좁아서({@code WEEKLY} 1일 · {@code MONTHLY}
 * 3일) 따라잡기로 사흘 늦게 실행된 거래는 <b>영영 안 붙는다.</b> 안 붙으면 잔여에서 빠지지 않아
 * 이중 계상되고 곡선이 위로 뜬다.
 *
 * <p>우리는 추정할 필요가 없다 — 보낼 때 금융망 거래번호를 받아 원장에 적어 두었다.
 * 그 번호가 곧 {@code core.transaction.external_transaction_id} 라 1:1 로 찾을 수 있다.
 *
 * <h2>매처보다 먼저 돈다</h2>
 *
 * <p>{@code ForecastRunWorker} 가 잔여를 읽기 전에 이것을 부른다. 그러면 매처가 사전에 읽는
 * 「이미 매칭된 거래」 집합에 이 결과가 들어가 있어, <b>매처도 닻 재조정도 이 거래를 보지
 * 않는다.</b> 그것이 중요한 이유는 닻 재조정이 「실제가 늦게 들어왔으니 앞으로의 예정일도
 * 민다」를 하기 때문이다 — 따라잡기로 늦은 것은 <b>사장님 거래처가 늦게 준 것이 아니라
 * 우리 서버가 꺼져 있었던 것</b>이라, 그걸로 일정을 밀면 곡선이 엉뚱해진다.
 */
@Service
public class PlannedExecutionReconciler {

    private static final Logger log = LoggerFactory.getLogger(PlannedExecutionReconciler.class);

    private final PlannedExecutionRepository executions;
    private final PlannedTransactionRepository planned;
    private final TransactionHistory history;

    public PlannedExecutionReconciler(PlannedExecutionRepository executions,
                                      PlannedTransactionRepository planned,
                                      TransactionHistory history) {
        this.executions = executions;
        this.planned = planned;
        this.history = history;
    }

    /**
     * 나갔지만 아직 못 붙인 실행을 훑어 붙인다.
     *
     * <p>아직 동기화되지 않은 것은 그대로 둔다 — 다음 실행이 다시 본다.
     *
     * @return 붙인 건수
     */
    @Transactional
    public int reconcile(Long businessId, LocalDate today) {
        List<PlannedExecution> pending = executions.findExecutedAwaitingMatch(businessId);
        if (pending.isEmpty()) {
            return 0;
        }

        // 이미 어떤 예정 거래에 붙어 있는 거래. uk_pt_matched 위반은 flush 때 터져
        // 이 실행 전체를 되돌리므로, 붙이기 전에 미리 거른다.
        Set<Long> claimed = new HashSet<>(planned.findMatchedTransactionIds(businessId));

        Map<Long, List<PlannedExecution>> byAccount = new HashMap<>();
        for (PlannedExecution execution : pending) {
            byAccount.computeIfAbsent(execution.getAccountId(), id -> new ArrayList<>()).add(execution);
        }

        int matched = 0;
        for (Map.Entry<Long, List<PlannedExecution>> entry : byAccount.entrySet()) {
            Map<String, Long> transactionIds = history.findIdsByExternalTransactionIds(
                    entry.getKey(),
                    entry.getValue().stream().map(PlannedExecution::getFinTransactionUniqueNo).toList());

            for (PlannedExecution execution : entry.getValue()) {
                Long transactionId = transactionIds.get(execution.getFinTransactionUniqueNo());
                if (transactionId == null) {
                    // 아직 동기화되지 않았다. 다음 실행이 다시 본다.
                    continue;
                }
                if (bind(execution, transactionId, today, claimed)) {
                    claimed.add(transactionId);
                    matched++;
                }
            }
        }

        if (matched > 0) {
            log.info("자동 반영 거래 정확 매칭 — 사업장 {} / {}건", businessId, matched);
        }
        return matched;
    }

    /**
     * 한 건을 붙인다. <b>한 건이 실패해도 나머지는 붙인다.</b>
     *
     * <p>예정 거래가 이미 다른 상태로 갔을 수 있다 — 사용자가 패턴을 일시중지하면 그 회차가
     * {@code CANCELLED} 이 되고, 매처가 먼저 같은 거래를 붙였을 수도 있다. 둘 다 <b>돈은 이미
     * 나간 뒤</b>라 되돌릴 수 없으므로, 원장만 정리하고 로그를 남긴다.
     */
    private boolean bind(PlannedExecution execution, Long transactionId, LocalDate today,
                         Set<Long> claimed) {
        try {
            PlannedTransaction row = planned.findById(execution.getPlannedTransactionId()).orElse(null);
            if (row == null) {
                log.warn("실행 원장이 가리키는 예정 거래가 없다 — 원장 {}", execution.getId());
                return false;
            }
            if (transactionId.equals(row.getMatchedTransactionId())) {
                // 매처가 먼저 같은 거래를 붙였다. 결과가 같으니 원장만 따라간다.
                execution.markMatched();
                return false;
            }
            if (claimed.contains(transactionId)) {
                // 다른 예정 거래가 이 거래를 가져갔다. 돈은 이미 나갔으므로 되돌릴 것은 없고,
                // 원장을 열어 두면 매 실행마다 같은 실패를 반복한다.
                log.warn("자동 반영 거래가 이미 다른 예정 거래에 붙어 있다 — 원장 {}", execution.getId());
                execution.markMatched();
                return false;
            }

            LocalDate occurredOn = execution.getFinTransactionDate() == null
                    ? today : execution.getFinTransactionDate();
            int dateDiff = (int) ChronoUnit.DAYS.between(row.getExpectedDate(), occurredOn);
            BigDecimal amountDiff = execution.getAmount().subtract(row.getAmountExpected());

            row.match(transactionId, dateDiff, amountDiff);
            execution.markMatched();
            return true;

        } catch (BusinessException | OptimisticLockingFailureException
                 | DataIntegrityViolationException e) {
            // MATCH_CONFLICT(취소됐거나 이미 다른 거래에 붙음) · 낙관적 락 · uk_pt_matched.
            // 이번 실행만 건너뛴다 — 다음 실행이 다시 시도한다.
            log.warn("자동 반영 거래를 예정 거래 {} 에 붙이지 못했다: {}",
                    execution.getPlannedTransactionId(), e.getClass().getSimpleName());
            return false;
        }
    }
}
