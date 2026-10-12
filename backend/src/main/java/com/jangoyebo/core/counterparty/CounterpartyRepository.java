package com.jangoyebo.core.counterparty;

import com.jangoyebo.common.domain.FlowType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface CounterpartyRepository extends JpaRepository<Counterparty, Long> {

    /**
     * 사업장 안에서 적요로 찾는다. {@code uk_cp_business_name_flow} 를 그대로 탄다.
     *
     * <p>사전에 쌓을 때도 분류에 쓸 때도 같은 열쇠다 — 두 경로가 다른 기준으로 찾으면
     * 쌓아 둔 것을 못 찾는다.
     *
     * <p><b>방향이 열쇠에 들어간다.</b> 같은 적요가 입금과 출금 양쪽에 오기 때문이다
     * ({@link Counterparty#getDefaultFlowType()}).
     */
    Optional<Counterparty> findByBusinessIdAndCanonicalNameAndDefaultFlowType(
            Long businessId, String canonicalName, FlowType defaultFlowType);

    /**
     * 적요 여러 개를 한 번에 찾는다 (사전 단계 분류).
     *
     * <p>거래마다 조회하면 한 배치에 수백 번이 된다. 사전은 사업장당 수십~수백 행이라 적요
     * 목록을 통째로 넘기는 편이 싸다.
     *
     * <p>분류에 쓸 수 있는 행만 준다 — 비활성이거나 기본 분류가 비어 있으면 볼 것이 없다.
     *
     * <p>방향까지 짝지어 거르는 것은 호출부가 한다 — 적요 목록만으로 찾고 (적요, 방향) 으로
     * 맞춰 본다. 한 적요에 방향별로 최대 두 줄이라 더 읽는 양이 무시할 만하다.
     */
    List<Counterparty> findByBusinessIdAndActiveTrueAndDefaultCategoryIdNotNullAndCanonicalNameIn(
            Long businessId, Collection<String> canonicalNames);

    /**
     * 사전 목록 (API 8.6).
     *
     * <p><b>표시 이름과 적요 양쪽을 본다.</b> 사장님이 「배달」로 찾든 「우아한」으로 찾든 같은
     * 줄이 나와야 한다 — 고친 이름만 검색되면 통장에 찍힌 문자열로는 못 찾는다.
     *
     * <p><b>{@code CAST(:keyword AS String)} 을 빼지 말 것.</b> {@code :keyword} 가
     * {@code IS NULL} 비교로만 등장하는 자리가 있어 Hibernate 가 타입을 정하지 못하고,
     * Postgres 가 {@code bytea} 로 굳혀 {@code lower(bytea) does not exist} 로 터진다 —
     * 거래 목록(8.2)에서 검색어를 비운 첫 진입이 전부 500 이었던 것과 같은 함정이다.
     *
     * <p>최근 쓴 순이다. {@code ix_cp_recent} 가 그 정렬을 받친다.
     */
    @Query("""
            SELECT c FROM Counterparty c
             WHERE c.businessId = :businessId
               AND (:keyword IS NULL
                    OR LOWER(c.displayName) LIKE LOWER(CONCAT('%', CAST(:keyword AS String), '%'))
                    OR LOWER(c.canonicalName) LIKE LOWER(CONCAT('%', CAST(:keyword AS String), '%')))
             ORDER BY c.lastUsedAt DESC NULLS LAST, c.id DESC
            """)
    Page<Counterparty> search(@Param("businessId") Long businessId,
                              @Param("keyword") String keyword,
                              Pageable pageable);
}
