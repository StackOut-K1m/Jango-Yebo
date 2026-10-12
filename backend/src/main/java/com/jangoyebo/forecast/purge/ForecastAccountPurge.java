package com.jangoyebo.forecast.purge;

import com.jangoyebo.core.AccountPurgeParticipant;
import com.jangoyebo.forecast.pattern.RecurringPatternRepository;
import com.jangoyebo.forecast.pattern.RecurringPatternTransactionRepository;
import com.jangoyebo.forecast.planned.PlannedExecutionRepository;
import com.jangoyebo.forecast.planned.PlannedTransactionRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * 계좌가 지워질 때 {@code forecast} 가 걷어내는 행들 (API 7.2 purge).
 *
 * <p>{@code core} 가 {@link AccountPurgeParticipant} 로 부른다 — 그쪽이 거래와 계좌를 지우기
 * 직전, 같은 트랜잭션 안이다. 여기서 던지면 삭제 전체가 되감긴다.
 *
 * <h2>순서가 전부다</h2>
 *
 * <p>네 표가 서로를, 그리고 {@code core} 를 외래키로 잡고 있어 한 칸만 어긋나도 제약 위반이다.
 *
 * <pre>
 * planned_execution ──→ planned_transaction ──→ recurring_pattern ──→ account
 *         │                      │                      │
 *         └──────────────────────┴──────────────────────┘
 *                                ↓
 *                        core.transaction
 * </pre>
 *
 * <ol>
 *   <li><b>매칭 떼기</b> — 남의 예정 거래가 이 계좌 거래를 가리키고 있으면 먼저 놓아준다.
 *       지우는 것이 아니라 {@code EXPECTED} 로 되돌린다</li>
 *   <li><b>실행 원장</b> — 계좌·패턴·예정 거래를 모두 잡고 있어 가장 먼저 비운다</li>
 *   <li><b>예정 거래</b> — 이 계좌 것과 이 계좌 패턴이 만든 것</li>
 *   <li><b>패턴 연결</b> — 다른 패턴이 이 계좌 거래를 근거로 세고 있던 것.
 *       이 계좌 패턴의 연결은 5번의 {@code ON DELETE CASCADE} 가 가져간다</li>
 *   <li><b>패턴</b></li>
 * </ol>
 *
 * <p>예측 실행({@code FORECAST_RUN})·일별 곡선은 <b>여기서 지우지 않는다.</b> 사업장 단위라
 * 계좌 하나에 매달려 있지 않고, {@code TransactionsChanged} 가 재계산을 걸어 새 실행이 덮는다.
 *
 * <p>⚠ <b>덮는다는 전제는 마지막 계좌에서 깨진다.</b> 계좌가 0개가 되면 워커는 시작 잔액이
 * 없어 새 실행을 만들지 못하므로, 덮을 것이 오지 않고 직전 실행이 그대로 남는다. 그 자리는
 * {@code ForecastRunService.clearForBusiness} 가 맡는다 — 워커가 계좌 0개를 확인하면 저장된
 * 실행과 알림을 걷어내고, 조회({@code ForecastWindowReader})도 계좌부터 보고 막는다.
 * <b>연동 해제(7.2 {@code DELETE})도 같은 경로다</b> — 「연결된 계좌」의 기준이 행의 존재가
 * 아니라 {@code status = 'CONNECTED'} 이기 때문이다.
 */
@Component
class ForecastAccountPurge implements AccountPurgeParticipant {

    private static final Logger log = LoggerFactory.getLogger(ForecastAccountPurge.class);

    private final PlannedExecutionRepository executions;
    private final PlannedTransactionRepository planned;
    private final RecurringPatternTransactionRepository patternLinks;
    private final RecurringPatternRepository patterns;

    ForecastAccountPurge(PlannedExecutionRepository executions,
                         PlannedTransactionRepository planned,
                         RecurringPatternTransactionRepository patternLinks,
                         RecurringPatternRepository patterns) {
        this.executions = executions;
        this.planned = planned;
        this.patternLinks = patternLinks;
        this.patterns = patterns;
    }

    @Override
    public void purgeAccount(Long businessId, Long accountId) {
        int unmatched = planned.unmatchTransactionsOf(accountId);
        int executionRows = executions.deleteOfAccount(accountId);
        int plannedRows = planned.deleteOfAccount(accountId);
        int linkRows = patternLinks.deleteLinksToTransactionsOf(accountId);
        int patternRows = patterns.deleteByAccountId(accountId);

        log.info("계좌 {} 삭제 — forecast 정리: 매칭 해제 {}, 실행 원장 {}, 예정 거래 {}, 패턴 연결 {}, 패턴 {}",
                accountId, unmatched, executionRows, plannedRows, linkRows, patternRows);
    }
}
