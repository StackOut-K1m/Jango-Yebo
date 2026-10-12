package com.jangoyebo.core;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

/**
 * 상품별 매출 구성비 조회 — {@code core} 모듈이 밖에 내놓는 공개 API (API 14.5, P1-22).
 *
 * <p>{@code core.productsales.ProductSalesService} 를 {@code forecast} 가 직접 볼 수 없어
 * 여기 둔다({@link CategoryCatalog}·{@link SafetyLines} 와 같은 이유 — {@code core} 는
 * CLOSED 라 하위 패키지가 밖에서 안 보인다).
 *
 * <p><b>What-if 의 {@code PRODUCT_SALES_CHANGE}(11.1)가 유일한 소비자다.</b> 상품 하나의
 * 판매량·단가 가정에서 총매출 배율을 환산할 때 <b>구성비만</b> 쓴다. 금액을 가져가지 않는
 * 것이 요점이다 — 매출 금액의 단일 출처는 계좌 입금이고, 이 표의 숫자는 상품 간 비율의
 * 분자로만 쓴다(ERD §1.9). 구성비는 분모가 무엇이든 보존되므로 POS 판매 총액과 계좌
 * 입금액의 기준이 달라도 환산이 필요 없다(11.1).
 */
public interface ProductSalesShares {

    /**
     * <b>가장 최근에 등록된 기간</b>의 구성비.
     *
     * <p>왜 기간을 인자로 받지 않는가 — 11.1 의 {@code PRODUCT_SALES_CHANGE} 에 기간
     * 파라미터가 없다. 14.5 의 기본 창(이번 달 1일~오늘)을 쓰면 <b>매월 1~2일에 등록이
     * 비어</b> 「구성비가 없다」는 409 가 나간다. 실제로는 지난달 인쇄물이 있고 그것이
     * 지금의 구성비에 가장 가까운 추정이다.
     *
     * <p>계절성 때문에 기간에 따라 구성비가 달라지는 것은 사실이고, 그래서 명세가
     * {@code warnings} 에 <b>어느 기간을 썼는지 반드시 밝히라</b>고 정해 두었다(11.1).
     * 기간을 고르는 문제를 숨기지 않고 사용자에게 드러내는 쪽이다.
     *
     * <p><b>비어 있으면 등록이 하나도 없는 사업장이다</b> — 호출자는
     * {@code 409 PRODUCT_SALES_NOT_REGISTERED} 로 답한다.
     */
    Optional<ShareSnapshot> latest(Long businessId);

    /**
     * 인쇄된 상품명을 집계 키로 정규화한다.
     *
     * <p>규칙이 사는 곳은 {@code core} 한 곳뿐이다 — UK 를 가진 쪽이 키를 만들어야 하고,
     * 같은 규칙이 두 곳에 살면 반드시 갈라진다. 시나리오 요청의 {@code productKey} 는
     * 화면이 보낸 <b>표시 이름</b>일 수 있으므로 대조 전에 이 함수를 통과시킨다.
     *
     * @throws com.jangoyebo.common.error.BusinessException 정규화 결과가 비면
     *         {@code VALIDATION_FAILED}
     */
    String keyOf(String productName);

    /**
     * 한 등록 기간의 구성비 전체.
     *
     * @param shareBasis {@code BILLED}(결제금액 기준) · {@code GROSS}(총매출 기준).
     *                   경고 문구가 어느 기준이었는지 밝힐 때 쓴다
     * @param incomplete 인쇄물이 잘려 합계가 맞지 않는 등록이 섞여 있다. 오류가 아니라
     *                   흔한 상태이며(라벨 90개 중 20개), 구성비는 보이는 행의 합을
     *                   분모로 쓰므로 계산 자체는 성립한다
     */
    record ShareSnapshot(LocalDate periodStart,
                         LocalDate periodEnd,
                         String shareBasis,
                         boolean incomplete,
                         List<ProductShare> items) {

        public Optional<ProductShare> find(String productKey) {
            return items.stream().filter(i -> i.productKey().equals(productKey)).findFirst();
        }
    }

    /**
     * @param share 합계 기준 점유율(0~1, 소수 3자리). 분모가 0 이거나 금액이 없으면
     *              <b>{@code null}</b> 이다 — 0 으로 두면 「팔리지 않은 상품」이 되어
     *              배율이 조용히 1 이 된다
     */
    record ProductShare(String productKey, String productName, BigDecimal share) {
    }
}
