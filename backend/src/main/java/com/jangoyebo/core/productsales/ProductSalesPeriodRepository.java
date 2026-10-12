package com.jangoyebo.core.productsales;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface ProductSalesPeriodRepository extends JpaRepository<ProductSalesPeriod, Long> {

    /** 같은 기간의 기존 행. 재등록은 이 집합을 갱신·삭제한다(14.4 upsert). */
    List<ProductSalesPeriod> findByBusinessIdAndPeriodStartAndPeriodEnd(
            Long businessId, LocalDate periodStart, LocalDate periodEnd);

    /**
     * 조회 기간과 <b>겹치는</b> 등록 기간의 행 (14.5).
     *
     * <p>포함이 아니라 겹침인 것은 데이터가 기간 단위 집계라 잘라 쓸 수 없기 때문이다 —
     * 12/1~12/31 로 등록된 것을 12/1~12/14 로 물으면, 포함 조건에서는 <b>빈 화면</b>이 나온다.
     * 대신 걸쳐 있다는 사실을 {@code warnings} 로 알린다.
     */
    @Query("""
            SELECT p FROM ProductSalesPeriod p
             WHERE p.businessId = :businessId
               AND p.periodStart <= :to
               AND p.periodEnd >= :from
            """)
    List<ProductSalesPeriod> findOverlapping(@Param("businessId") Long businessId,
                                             @Param("from") LocalDate from,
                                             @Param("to") LocalDate to);

    /**
     * 이 사업장에 이미 등장한 적 있는 상품 키 (14.4 {@code newProducts} 판정).
     *
     * <p>키만 읽는다 — 첫 등록이면 수십 행이지만 2회차부터는 전체 이력을 훑게 되므로
     * 엔티티를 끌어오지 않는다.
     */
    @Query("SELECT DISTINCT p.productKey FROM ProductSalesPeriod p WHERE p.businessId = :businessId")
    List<String> findProductKeys(@Param("businessId") Long businessId);

    /**
     * 두 상품 키에 해당하는 모든 기간의 행 (14.6 일괄 변경).
     *
     * <p>기간을 안 좁히는 것이 요점이다 — <b>표기 정리는 과거 전체에 적용</b>돼야 한다.
     * 한 기간만 고치면 추이 조회에서 같은 상품이 두 줄로 갈라진 채 남는다.
     */
    @Query("""
            SELECT p FROM ProductSalesPeriod p
             WHERE p.businessId = :businessId AND p.productKey IN :keys
            """)
    List<ProductSalesPeriod> findByProductKeys(@Param("businessId") Long businessId,
                                               @Param("keys") Collection<String> keys);

    /**
     * 워커에 넘길 {@code knownProducts} (14.7 · 계약 20.9).
     *
     * <p><b>정규화 키가 아니라 인쇄된 원문</b>이다 — 워커가 이 목록에 스냅되도록 유도하는
     * 값이라 모델이 실제로 볼 글자여야 한다. 월 단위 입력은 중간 검토가 없어 오인식이 다음
     * 달까지 살아남으므로 이 스냅이 일 단위보다 중요하다.
     *
     * <p>⚠ 같은 상품의 표기가 바뀐 적이 있으면 옛 이름도 함께 나온다. 워커는 목록에 없는
     * 신규 상품도 그대로 반환하므로(계약 20.9) 해로운 쪽은 아니고, 호출자가 개수를 자른다.
     */
    @Query("SELECT DISTINCT p.productName FROM ProductSalesPeriod p WHERE p.businessId = :businessId")
    List<String> findProductNames(@Param("businessId") Long businessId);

    boolean existsByBusinessId(Long businessId);

    /**
     * 가장 최근에 등록된 기간의 행 하나 (What-if {@code PRODUCT_SALES_CHANGE} · 11.1).
     *
     * <p>기간을 고르기 위한 조회라 행 하나만 필요하다 — 같은 기간의 나머지 행은 호출자가
     * {@link #findByBusinessIdAndPeriodStartAndPeriodEnd} 로 다시 읽는다.
     *
     * <p>{@code periodEnd} 만으로 정렬하면 같은 날 끝나는 등록이 둘일 때(8/1~8/31 과
     * 8/25~8/31) 어느 쪽이 올지 모른다. {@code periodStart} 를 함께 내림차순으로 두어
     * <b>짧고 최근인 것</b>을 고른다 — 뒤에 올린 쪽이 사용자의 최신 의도다.
     */
    Optional<ProductSalesPeriod> findFirstByBusinessIdOrderByPeriodEndDescPeriodStartDesc(Long businessId);
}
