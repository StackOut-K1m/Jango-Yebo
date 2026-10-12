package com.jangoyebo.core.settlement;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface SettlementTransactionRepository extends JpaRepository<SettlementTransaction, Long> {

    List<SettlementTransaction> findBySettlementId(Long settlementId);

    /**
     * 매칭 전량 교체의 앞단.
     *
     * <p><b>파생 {@code deleteBySettlementId} 를 쓰면 안 된다.</b> 그건 엔티티를 지연 삭제로
     * 표시만 하고 커밋 시점에 플러시하는데, 그 사이에 같은 {@code (settlement_id, transaction_id)}
     * 쌍을 다시 넣으면 DELETE 가 아직 DB 에 가지 않아 {@code uk_st_pair} 가 터진다 —
     * 실제로 PATCH 세 건이 500 이었다. JPQL 벌크 삭제는 즉시 실행된다.
     *
     * <p>{@code clearAutomatically} 는 <b>켜지 않는다.</b> 켜면 영속성 컨텍스트가 비워져
     * 호출부가 들고 있던 {@code Settlement} 가 detached 되고, 이어지는
     * {@code recalculate()} 의 상태 변경이 저장되지 않는다.
     */
    @Modifying(flushAutomatically = true)
    @Query("DELETE FROM SettlementTransaction st WHERE st.settlementId = :settlementId")
    void deleteBySettlementId(@Param("settlementId") Long settlementId);

    /**
     * 이 계좌 거래가 배분돼 있는 대사 id (계좌 삭제 · API 7.2 purge).
     *
     * <p>배분을 지우기 <b>전에</b> 받아 둬야 한다. 지운 뒤에는 어느 대사가 영향을 받았는지
     * 되짚을 방법이 없고, 그러면 {@code MATCHED} 인데 배분이 0인 대사가 남는다.
     */
    @Query("""
            SELECT DISTINCT st.settlementId FROM SettlementTransaction st
             WHERE st.transactionId IN (SELECT t.id FROM Transaction t WHERE t.accountId = :accountId)
            """)
    List<Long> findSettlementIdsOfAccount(@Param("accountId") Long accountId);

    /** 이 계좌 거래의 배분을 지운다 (계좌 삭제 · API 7.2 purge). 대사 자체는 사업장 것이라 남는다. */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("""
            DELETE FROM SettlementTransaction st
             WHERE st.transactionId IN (SELECT t.id FROM Transaction t WHERE t.accountId = :accountId)
            """)
    int deleteAllocationsOfAccount(@Param("accountId") Long accountId);
}
