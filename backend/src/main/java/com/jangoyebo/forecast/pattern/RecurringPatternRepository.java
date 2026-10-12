package com.jangoyebo.forecast.pattern;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface RecurringPatternRepository extends JpaRepository<RecurringPattern, Long> {

    List<RecurringPattern> findByBusinessIdOrderByConfidenceDesc(Long businessId);

    List<RecurringPattern> findByBusinessIdAndStatusOrderByConfidenceDesc(Long businessId, PatternStatus status);

    /**
     * 분류가 비어 있는 패턴 — 거래 분류가 뒤늦게 돌았을 때 백필할 대상
     * ({@code RecurringPatternService.backfillCategories}).
     *
     * <p><b>상태로 거르지 않는다.</b> 후보는 재탐지가 채우지만 그 전에도 채워 둘 이유가 있고
     * (승인하는 순간 예정 거래에 그대로 복사된다), 승인·중지된 것은 재탐지가 닿지 않아
     * <b>여기가 유일한 경로</b>다.
     */
    List<RecurringPattern> findByBusinessIdAndCategoryIdIsNull(Long businessId);

    /**
     * 재탐지 시 기존 패턴을 찾는 키.
     *
     * <p>같은 계좌·방향·이름이면 같은 패턴으로 본다. 이 조회가 없으면 배치를 돌릴 때마다
     * <b>같은 후보가 계속 새로 쌓인다.</b>
     */
    Optional<RecurringPattern> findByBusinessIdAndAccountIdAndFlowTypeAndName(
            Long businessId, Long accountId, com.jangoyebo.common.domain.FlowType flowType, String name);

    /**
     * 이 계좌의 반복 패턴을 지운다 (계좌 삭제 · API 7.2 purge).
     *
     * <p>근거 거래 연결({@code RECURRING_PATTERN_TRANSACTION})은 {@code fk_rpt_pattern} 이
     * {@code ON DELETE CASCADE} 라 따라 지워진다 — 그쪽에 남는 것은 <b>다른 계좌 패턴이</b>
     * 이 계좌 거래를 근거로 삼은 연결뿐이고, 그건
     * {@link RecurringPatternTransactionRepository#deleteLinksToTransactionsOf} 가 맡는다.
     *
     * <p>사업장 단위 패턴({@code account_id} 가 {@code null})은 건드리지 않는다. 계좌 하나가
     * 사라져도 그 패턴은 남은 계좌의 거래로 계속 성립할 수 있고, 성립하지 않으면 다음 재탐지가
     * 정리한다.
     */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("delete from RecurringPattern r where r.accountId = :accountId")
    int deleteByAccountId(@Param("accountId") Long accountId);
}
