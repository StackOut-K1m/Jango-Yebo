package com.jangoyebo.core.transaction;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface TransactionClassificationHistoryRepository
        extends JpaRepository<TransactionClassificationHistory, Long> {

    /**
     * few-shot 예시의 원천 (API 20.3).
     *
     * <p><b>{@code classifier_type = 'USER'} 로 고정</b>해야 부분 인덱스
     * {@code ix_tch_fewshot (created_at DESC) WHERE classifier_type = 'USER'} 를 탄다.
     * 파라미터로 받으면 플래너가 그 인덱스를 못 쓴다.
     *
     * <p>이력 테이블에는 {@code business_id} 도 적요도 없다 — 둘 다 거래를 따라가므로
     * {@code TRANSACTION} 과 조인하고, 분류 코드는 {@code TRANSACTION_CATEGORY} 에서 온다.
     * 이력이 들고 있는 것은 <b>id</b> 뿐인데 계약이 요구하는 것은 <b>코드</b>다.
     *
     * <p>적요가 없는 거래는 제외한다 — 예시의 본문이 적요이므로 비면 가르칠 것이 없다.
     *
     * <p>⚠ <b>이 결과는 하루 1회만 갱신해 쓴다.</b> 매 요청 재구성하면 프롬프트 프리픽스가
     * 매번 달라져 워커의 캐시 적중률이 0 이 된다(합의 ①·②). 캐싱은 호출부
     * ({@link FewShotExampleProvider})의 책임이다.
     */
    @Query("""
            SELECT new com.jangoyebo.core.transaction.FewShotExample(
                       t.description, t.flowType, c.code)
              FROM TransactionClassificationHistory h
              JOIN Transaction t ON t.id = h.transactionId
              JOIN TransactionCategory c ON c.id = h.newCategoryId
             WHERE h.classifierType = com.jangoyebo.core.transaction.ClassifierType.USER
               AND t.businessId = :businessId
               AND t.description IS NOT NULL
             ORDER BY h.createdAt DESC
            """)
    List<FewShotExample> findFewShotExamples(@Param("businessId") Long businessId,
                                             Pageable pageable);

    /**
     * 이 계좌 거래의 분류 이력을 지운다 (계좌 삭제 · API 7.2 purge).
     *
     * <p><b>잃는 것이 있다.</b> 이 표는 분류 감사이자 few-shot 원천인데
     * ({@link #findFewShotExamples} 가 읽는다) {@code transaction_id} 가 {@code NOT NULL}
     * 이라 거래만 지우고 이력을 남길 수가 없다. 계좌를 통째로 지운다는 것이 그 표본까지
     * 버린다는 뜻임을 알고 하는 일이다 — 사용자가 명시로 요청한 자리에서만 일어난다.
     */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("""
            delete from TransactionClassificationHistory h
            where h.transactionId in (select t.id from Transaction t where t.accountId = :accountId)
            """)
    int deleteByAccountId(@Param("accountId") Long accountId);
}
