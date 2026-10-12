package com.jangoyebo.forecast.pattern;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.Set;

public interface RecurringPatternTransactionRepository
        extends JpaRepository<RecurringPatternTransaction, Long> {

    /**
     * 재탐지할 때 갈아끼운다 — 표본이 바뀌면 옛 연결이 남아 잔여에서 잘못 빠진다.
     *
     * <p><b>벌크 삭제여야 한다.</b> 파생 삭제({@code deleteByRecurringPatternId})는 영속성
     * 컨텍스트에 쌓였다가 flush 될 때 실행되는데, Hibernate 가 <b>insert 를 delete 보다 먼저</b>
     * 내보낸다. 그러면 새 연결이 옛 연결과 부딪혀 {@code uk_rpt_pair} 위반으로 죽는다.
     * 실제로 그렇게 터졌다(2026-09-11). 벌크 질의는 부를 때 바로 나간다.
     */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("delete from RecurringPatternTransaction l where l.recurringPatternId = :patternId")
    void deleteLinksOf(@Param("patternId") Long patternId);

    /**
     * 이 패턴들이 설명하는 과거 거래 id.
     *
     * <p>잔여 집계의 뺄셈 대상이라 <b>확정된 패턴만</b> 넘겨야 한다
     * ({@link RecurringPatternTransaction} 머리말).
     */
    @Query("""
            select l.transactionId from RecurringPatternTransaction l
            where l.recurringPatternId in :patternIds
            """)
    Set<Long> findTransactionIds(@Param("patternIds") Collection<Long> patternIds);

    /**
     * 이 패턴들의 근거 거래 연결 — 어느 패턴의 것인지까지 필요할 때
     * ({@code RecurringPatternService.backfillCategories}).
     *
     * <p>{@link #findTransactionIds} 는 여러 패턴의 거래를 <b>하나의 집합</b>으로 뭉쳐 준다.
     * 잔여에서 빼는 데는 그걸로 충분하지만, 패턴마다 분류를 정하려면 짝이 남아 있어야 한다.
     */
    List<RecurringPatternTransaction> findByRecurringPatternIdIn(Collection<Long> patternIds);

    /**
     * 지워질 거래를 근거로 삼은 연결을 끊는다 (계좌 삭제 · API 7.2 purge).
     *
     * <p><b>여기 걸리는 것은 다른 계좌의 패턴이다.</b> 지우는 계좌 자신의 패턴이 가진 연결은
     * {@code fk_rpt_pattern} 의 {@code ON DELETE CASCADE} 가 가져간다. 남는 경우는 사업장 단위
     * 패턴({@code account_id} 가 {@code null})이 이 계좌의 거래도 근거로 세고 있던 것인데,
     * 끊지 않으면 {@code fk_rpt_transaction} 이 거래 삭제를 막는다.
     *
     * <p>패턴 자체는 남긴다 — 근거 하나가 빠졌다고 반복이 사라지는 것은 아니고, 신뢰도는
     * 다음 재탐지가 다시 센다.
     *
     * <p><b>네이티브인 이유</b>는 {@code PlannedTransactionRepository#unmatchTransactionsOf}
     * 와 같다 — 술어가 {@code core.transaction} 을 봐야 하는데 그 엔티티를 JPQL 에서 부를 수 없다.
     */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query(nativeQuery = true, value = """
            DELETE FROM forecast.recurring_pattern_transaction
             WHERE transaction_id IN (SELECT id FROM core.transaction WHERE account_id = :accountId)
            """)
    int deleteLinksToTransactionsOf(@Param("accountId") Long accountId);
}
