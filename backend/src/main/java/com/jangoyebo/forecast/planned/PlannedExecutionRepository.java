package com.jangoyebo.forecast.planned;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface PlannedExecutionRepository extends JpaRepository<PlannedExecution, Long> {

    Optional<PlannedExecution> findByPlannedTransactionId(Long plannedTransactionId);

    boolean existsByPlannedTransactionId(Long plannedTransactionId);

    List<PlannedExecution> findByPlannedTransactionIdIn(Collection<Long> plannedTransactionIds);

    /**
     * 같은 패턴의 같은 회차가 이미 나갔는가 ({@code uk_pe_pattern_due}).
     *
     * <p><b>제약이 터지기 전에 먼저 본다.</b> 터뜨리면 그 트랜잭션이 되돌리기로 표시돼
     * 예외를 잡아도 커밋에서 다시 터진다 — 잡아서 「건너뛴다」로 만들 수가 없다.
     */
    boolean existsByRecurringPatternIdAndDueDate(Long recurringPatternId, LocalDate dueDate);

    /**
     * 이 계좌에서 <b>아직 끝나지 않은</b> 실행.
     *
     * <p>{@code SENT} 는 답을 못 받은 것이라 다음 차례가 거래내역으로 판정하고,
     * {@code FAILED} 는 재시도 대상일 수 있다. 둘 다 매 차례 다시 봐야 한다.
     */
    List<PlannedExecution> findByAccountIdAndStatusInOrderByDueDateAscIdAsc(
            Long accountId, Collection<PlannedExecutionStatus> statuses);

    /**
     * 이 계좌가 이미 붙잡은 금융망 거래번호.
     *
     * <p>거래내역에서 「아직 아무도 안 쓴 거래」를 고를 때의 제외 목록이다. 같은 거래를 두
     * 예정 거래에 채택하면 {@code uk_pe_fin_tx} 가 막지만, 막히기 전에 걸러 내는 편이
     * 실패 로그를 남기지 않아 읽기 좋다.
     */
    @Query("""
            select e.finTransactionUniqueNo from PlannedExecution e
             where e.accountId = :accountId
               and e.finTransactionUniqueNo is not null
            """)
    List<String> findClaimedFinTransactionIds(@Param("accountId") Long accountId);

    /**
     * 정확 매칭 대상 — 나갔는데 아직 예정 거래에 못 붙인 것 (API 9.6).
     *
     * <p>동기화가 그 거래를 가져와야 붙일 수 있으므로 한동안 여기 남아 있는 것이 정상이다.
     */
    @Query("""
            select e from PlannedExecution e
             where e.businessId = :businessId
               and e.status = com.jangoyebo.forecast.planned.PlannedExecutionStatus.EXECUTED
               and e.finTransactionUniqueNo is not null
             order by e.dueDate, e.id
            """)
    List<PlannedExecution> findExecutedAwaitingMatch(@Param("businessId") Long businessId);

    /**
     * 오늘 다시 시도할 실패 건.
     *
     * <p>{@code retryAfter} 가 {@code null} 인 행은 영구 실패라 여기 걸리지 않는다 —
     * 사람이 보기 전까지 다시 보내지 않는다.
     */
    @Query("""
            select e from PlannedExecution e
             where e.accountId = :accountId
               and e.status = com.jangoyebo.forecast.planned.PlannedExecutionStatus.FAILED
               and e.retryAfter is not null
               and e.retryAfter <= :today
             order by e.dueDate, e.id
            """)
    List<PlannedExecution> findRetryable(@Param("accountId") Long accountId,
                                         @Param("today") LocalDate today);

    /**
     * 이 계좌의 실행 원장을 지운다 (계좌 삭제 · API 7.2 purge).
     *
     * <p><b>가장 먼저 지워야 하는 표다.</b> 계좌·패턴·예정 거래를 모두 외래키로 잡고 있어
     * 이것이 남아 있으면 뒤의 셋 중 무엇도 지워지지 않는다.
     *
     * <p><b>원장을 지우는 것이 맞는가.</b> 이 표는 「우리가 이 계좌에서 실제로 돈을 뺐다」는
     * 기록이라 평소에는 지우지 않는다. 다만 대상이 시연용 샌드박스 계좌뿐이고
     * ({@code Account#enableAutoExecution}), 계좌 자체가 사라지면 그 기록이 가리킬 곳도
     * 함께 사라진다 — 실계좌에는 자동 반영을 켜지 않으므로 여기 걸릴 행이 없다.
     */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("""
            delete from PlannedExecution e
            where e.accountId = :accountId
               or e.recurringPatternId in (select r.id from RecurringPattern r
                                            where r.accountId = :accountId)
               or e.plannedTransactionId in (select p.id from PlannedTransaction p
                                              where p.accountId = :accountId)
            """)
    int deleteOfAccount(@Param("accountId") Long accountId);
}
