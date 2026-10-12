package com.jangoyebo.core.transaction;

import com.jangoyebo.common.domain.FlowType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Collection;
import java.time.Instant;
import java.util.List;
import java.util.Set;

public interface TransactionRepository extends JpaRepository<Transaction, Long> {

    /**
     * 거래 목록 조회 (API 8.2).
     *
     * <p>{@code businessId} 는 항상 조건에 들어간다 — 호출자가 넘기는 값은
     * {@code @CurrentBusiness} 가 소유권을 검증한 뒤의 것이다.
     *
     * <p><b>{@code CAST(:keyword AS String)} 을 빼지 말 것.</b> {@code :keyword} 는
     * {@code IS NULL} 비교로만 등장하는 자리가 있어 Hibernate 가 타입을 정하지 못하고,
     * {@code null} 이 오면 드라이버가 타입 없는 파라미터로 보낸다. Postgres 는 이걸
     * {@code bytea} 로 굳혀 {@code function lower(bytea) does not exist} 로 터진다 —
     * <b>검색어를 비운 목록 첫 진입이 전부 500 이었다.</b> 다른 조건절들이 멀쩡한 이유는
     * 파라미터가 컬럼과 <b>비교만</b> 되어 컬럼 타입에서 추론이 되기 때문이다.
     * 함수 인자로 들어가는 파라미터에만 생기는 문제다.
     *
     * <h2>해제된 계좌</h2>
     *
     * <p><b>기본으로 뺀다.</b> 연동을 끊은 계좌의 거래가 목록·합계에 계속 섞이면 사용자가
     * 해제로 무엇을 했는지 알 수 없다. 행을 지우지 않고 보존하는 것은 되살릴 수 있어야 하기
     * 때문이고({@code Account#relink}), 보존이 <b>합계에 기여</b>한다는 뜻은 아니다.
     *
     * <p>{@code includeDisconnected = true} 면 그대로 보여준다 — 보존한 이유가 그것이다.
     * 화면의 「해제된 계좌 포함」이 이 문을 쓴다.
     *
     * <p><b>{@code CONNECTED} 만 남기지 않는다.</b> 인증 대기·만료 계좌에도 CSV 로 올린 거래가
     * 있을 수 있고({@code TransactionImportService} 는 상태를 보지 않는다) 그것은 사용자가
     * 직접 넣은 자료다. 표시에서 빼는 것은 <b>해제</b>뿐이다. 예측 입력은 기준이 다르다 —
     * {@code TransactionHistoryService} 를 볼 것.
     */
    @Query("""
            SELECT t FROM Transaction t
             WHERE t.businessId = :businessId
               AND t.transactionDate BETWEEN :from AND :to
               AND (:accountId IS NULL OR t.accountId = :accountId)
               AND (:includeDisconnected = TRUE
                    OR NOT EXISTS (SELECT 1 FROM Account a
                                    WHERE a.id = t.accountId
                                      AND a.status = 'DISCONNECTED'))
               AND (:flowType IS NULL OR t.flowType = :flowType)
               AND (:status IS NULL OR t.classificationStatus = :status)
               AND (:needsConfirmation IS NULL OR t.needsConfirmation = :needsConfirmation)
               AND (:categoryId IS NULL OR t.categoryId = :categoryId)
               AND (:minAmount IS NULL OR t.amount >= :minAmount)
               AND (:maxAmount IS NULL OR t.amount <= :maxAmount)
               AND (:taxReviewStatuses IS NULL OR t.taxReviewStatus IN :taxReviewStatuses)
               AND (:keyword IS NULL
                    OR LOWER(t.description) LIKE LOWER(CONCAT('%', CAST(:keyword AS String), '%'))
                    OR LOWER(t.counterpartyName) LIKE LOWER(CONCAT('%', CAST(:keyword AS String), '%')))
            """)
    Page<Transaction> search(@Param("businessId") Long businessId,
                             @Param("from") LocalDate from,
                             @Param("to") LocalDate to,
                             @Param("accountId") Long accountId,
                             @Param("flowType") FlowType flowType,
                             @Param("status") ClassificationStatus status,
                             @Param("needsConfirmation") Boolean needsConfirmation,
                             @Param("categoryId") Long categoryId,
                             @Param("minAmount") BigDecimal minAmount,
                             @Param("maxAmount") BigDecimal maxAmount,
                             @Param("taxReviewStatuses") Collection<TaxReviewStatus> taxReviewStatuses,
                             @Param("keyword") String keyword,
                             @Param("includeDisconnected") boolean includeDisconnected,
                             Pageable pageable);

    /**
     * 분류가 필요한 거래 (API 20.3 요청 재료).
     *
     * <p><b>제외한 거래는 보내지 않는다.</b> 현금흐름에서 빼 둔 거래를 분류해 봐야 쓰이는
     * 곳이 없고 LLM 값만 나간다.
     *
     * <h2>해제된 계좌</h2>
     *
     * <p><b>{@link #search} 와 같은 기준으로 뺀다.</b> 분류 결과가 나타나는 곳이 그 목록이라서다 —
     * 해제한 계좌의 거래를 분류해도 사용자가 「해제된 계좌 포함」을 켜기 전에는 보이지 않는데
     * LLM 값은 그대로 나간다. {@code is_excluded} 를 빼는 것과 같은 이유다.
     *
     * <p><b>{@code includeDisconnected} 같은 문을 두지 않는다.</b> 그 스위치는 <b>보기</b>
     * 스위치이지 "값을 치르고 분류하라"는 뜻이 아니다. 되살리면({@code Account#relink}) 그
     * 거래는 여전히 {@code UNCLASSIFIED} 라 다음 트리거나 8.5 재실행이 그대로 주워간다 —
     * 분류를 영영 잃는 것이 아니라 미루는 것이다.
     *
     * <p>{@code CONNECTED} 만 남기지 않는 이유도 {@link #search} 와 같다 — 인증 대기·만료
     * 계좌에 CSV 로 올린 거래는 사용자가 직접 넣은 자료이고 목록에 보인다. 예측 입력만 기준이
     * 좁다({@code TransactionHistoryService}).
     *
     * <p>오래된 것부터 준다 — 밀린 거래가 먼저 분류돼야 사용자가 보는 목록의 위쪽이 채워진다.
     * 한 번에 담는 수는 {@code jango.classification.batch-size} 가 정한다.
     */
    @Query("""
            SELECT t FROM Transaction t
             WHERE t.businessId = :businessId
               AND t.classificationStatus
                     = com.jangoyebo.core.transaction.ClassificationStatus.UNCLASSIFIED
               AND t.excluded = false
               AND NOT EXISTS (SELECT 1 FROM Account a
                                WHERE a.id = t.accountId
                                  AND a.status = 'DISCONNECTED')
             ORDER BY t.occurredAt ASC
            """)
    List<Transaction> findUnclassified(@Param("businessId") Long businessId, Pageable pageable);

    /**
     * 아직 분류되지 않은 거래 수 (체이닝 판단).
     *
     * <p><b>{@link #findUnclassified} 와 조건이 한 글자도 달라서는 안 된다.</b> 여기가 넓으면
     * "남았다"고 보고 다음 Job 을 넣는데 그 Job 이 보낼 거래를 못 찾아 빈 채로 끝난다 —
     * 조용히 도는 Job 이 생긴다.
     */
    @Query("""
            SELECT COUNT(t) FROM Transaction t
             WHERE t.businessId = :businessId
               AND t.classificationStatus
                     = com.jangoyebo.core.transaction.ClassificationStatus.UNCLASSIFIED
               AND t.excluded = false
               AND NOT EXISTS (SELECT 1 FROM Account a
                                WHERE a.id = t.accountId
                                  AND a.status = 'DISCONNECTED')
            """)
    long countUnclassified(@Param("businessId") Long businessId);

    /** CSV 임포트 시 이미 저장된 중복 키를 한 번에 조회한다(행마다 조회하지 않기 위해). */
    @Query("SELECT t.deduplicationKey FROM Transaction t "
            + "WHERE t.accountId = :accountId AND t.deduplicationKey IN :keys")
    Set<String> findExistingDeduplicationKeys(@Param("accountId") Long accountId,
                                              @Param("keys") List<String> keys);

    /**
     * 증빙 매칭 후보 (13.4). 발행일 앞뒤 며칠을 통째로 가져와 점수는 서비스가 매긴다.
     *
     * <p>창이 좁아 건수가 적다 — 페이지를 나누면 점수 정렬이 페이지 안에서만 맞아 더 나쁘다.
     */
    @Query("""
            SELECT t FROM Transaction t
             WHERE t.businessId = :businessId
               AND t.occurredAt >= :from AND t.occurredAt < :to
             ORDER BY t.occurredAt DESC
            """)
    List<Transaction> findForEvidenceMatch(@Param("businessId") Long businessId,
                                           @Param("from") Instant from,
                                           @Param("to") Instant to);

    /**
     * 이 계좌의 거래를 지운다 (계좌 삭제 · API 7.2 purge).
     *
     * <p><b>자식이 먼저 비워져 있어야 한다.</b> 분류 이력·증빙 연결·정산 배분·패턴 근거가
     * 모두 {@code transaction_id} 를 잡고 있어, 순서를 어기면 제약 위반으로 트랜잭션이 통째로
     * 되감긴다. 순서는 {@code AccountPurgeService} 가 한 곳에서 정한다.
     *
     * <p>{@code is_excluded} 여부를 보지 않는다 — 계좌가 사라지는 자리라 예측 입력에서
     * 빠져 있던 행도 남길 이유가 없다.
     */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("delete from Transaction t where t.accountId = :accountId")
    int deleteByAccountId(@Param("accountId") Long accountId);
}
