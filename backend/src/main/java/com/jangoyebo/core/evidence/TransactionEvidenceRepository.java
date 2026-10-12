package com.jangoyebo.core.evidence;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface TransactionEvidenceRepository extends JpaRepository<TransactionEvidence, Long> {

    List<TransactionEvidence> findByEvidenceId(Long evidenceId);

    List<TransactionEvidence> findByTransactionId(Long transactionId);

    /** {@code uk_te_pair} 와 조건이 같다 — 중복 연결 시도를 400 으로 돌려주기 위한 조회다. */
    Optional<TransactionEvidence> findByTransactionIdAndEvidenceId(Long transactionId, Long evidenceId);

    /**
     * 이 중 실제로 거래에 연결된 증빙 id (13.2 목록의 {@code linked}).
     *
     * <p><b>DISTINCT 가 필요하다.</b> 한 증빙이 여러 거래에 연결될 수 있어(13.4) 그대로 두면
     * 같은 id 가 여러 번 나온다 — 목록에 쓰는 쪽은 집합이라 티가 안 나지만, 개수를 세는 데
     * 재사용하면 그때 틀린다.
     */
    @Query("""
            SELECT DISTINCT te.evidenceId FROM TransactionEvidence te
             WHERE te.evidenceId IN :evidenceIds
               AND te.matchStatus = com.jangoyebo.core.evidence.MatchStatus.LINKED
            """)
    List<Long> findLinkedEvidenceIds(@Param("evidenceIds") Collection<Long> evidenceIds);

    /**
     * 이 계좌 거래에만 붙어 있던 증빙 (계좌 삭제 · API 7.2 purge).
     *
     * <p>계좌를 지우면 이 증빙들은 <b>어느 거래에도 닿지 않는 고아</b>가 된다. 사용자가
     * 「함께 정리」를 켰을 때만 소프트 삭제 대상이고, 기본값은 남기는 쪽이다.
     *
     * <p><b>바깥 연결이 하나라도 있으면 뺀다.</b> 증빙 ↔ 거래는 N:M 이라(ERD §658) 한 증빙이
     * 여러 계좌의 거래에 붙어 있을 수 있는데, 그것까지 지우면 <b>살아 있는 계좌의 근거가
     * 사라진다.</b> 그래서 이 계좌 밖으로 연결이 없는 것만 고른다.
     */
    @Query("""
            SELECT DISTINCT te.evidenceId FROM TransactionEvidence te
             WHERE te.transactionId IN (SELECT t.id FROM Transaction t WHERE t.accountId = :accountId)
               AND NOT EXISTS (SELECT 1 FROM TransactionEvidence other
                                WHERE other.evidenceId = te.evidenceId
                                  AND other.transactionId NOT IN
                                      (SELECT t2.id FROM Transaction t2 WHERE t2.accountId = :accountId))
            """)
    List<Long> findEvidenceOnlyLinkedToAccount(@Param("accountId") Long accountId);

    /**
     * 이 계좌 거래의 증빙 연결을 끊는다 (계좌 삭제 · API 7.2 purge).
     *
     * <p><b>끊는 것은 연결뿐이다.</b> {@code EVIDENCE} 행도 파일도 남는다 — 증빙은 계좌가 아니라
     * 사장님이 올린 것이고({@code business_id} 만 있다), 지우면 {@code EVIDENCE_EXTRACTION} 에
     * {@code ON DELETE CASCADE} 로 매달린 {@code EVIDENCE_CORRECTION} 까지 사라진다.
     * 그 표가 실사용 정확도를 얻는 유일한 원천이다({@code V18} 주석).
     */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("""
            DELETE FROM TransactionEvidence te
             WHERE te.transactionId IN (SELECT t.id FROM Transaction t WHERE t.accountId = :accountId)
            """)
    int deleteLinksOfAccount(@Param("accountId") Long accountId);
}
