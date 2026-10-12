package com.jangoyebo.core.productsales;

import com.jangoyebo.core.ProductSalesShares;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

/**
 * {@link ProductSalesShares} 구현.
 *
 * <p>계산은 {@link ProductShares} 가 한다 — 14.5 조회와 <b>같은 함수</b>를 쓰는 것이 요점이다.
 * 구성비를 두 곳에서 따로 계산하면 화면이 보여 준 24.2% 와 시나리오가 쓴 값이 갈라지고,
 * 사용자는 어느 쪽이 맞는지 알 방법이 없다.
 */
@Service
public class ProductSalesSharesService implements ProductSalesShares {

    private final ProductSalesPeriodRepository periods;

    public ProductSalesSharesService(ProductSalesPeriodRepository periods) {
        this.periods = periods;
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<ShareSnapshot> latest(Long businessId) {
        return periods.findFirstByBusinessIdOrderByPeriodEndDescPeriodStartDesc(businessId)
                .map(newest -> snapshotOf(businessId, newest));
    }

    @Override
    public String keyOf(String productName) {
        return ProductKeys.normalize(productName);
    }

    /**
     * 가장 최근 등록 <b>기간 하나</b>를 통째로 읽는다.
     *
     * <p>{@code findOverlapping} 이 아니라 기간 일치로 다시 읽는 이유 — 겹치는 다른 등록까지
     * 합치면 {@code warnings} 가 밝히는 기간과 실제 근거가 어긋난다. 「8/1~8/31 기준」이라고
     * 말해 놓고 7월 등록이 섞인 분모를 쓰면 그 문장이 거짓이 된다. 시나리오에서 구성비의
     * 근거를 밝히는 것은 도메인 규칙 1(확정 표현 금지)이 요구하는 부분이라 정확해야 한다.
     */
    private ShareSnapshot snapshotOf(Long businessId, ProductSalesPeriod newest) {
        List<ProductSalesPeriod> rows = periods.findByBusinessIdAndPeriodStartAndPeriodEnd(
                businessId, newest.getPeriodStart(), newest.getPeriodEnd());

        List<ProductShares.Line> lines = ProductShares.merge(rows.stream()
                .map(r -> new ProductShares.Line(
                        r.getProductKey(), r.getProductName(), r.getQuantity(),
                        r.getGrossAmount(), r.getDiscountAmount(), r.getNetAmount(),
                        r.getVatAmount(), r.getBilledAmount()))
                .toList());

        // previous 를 빈 목록으로 둔다 — 시나리오가 쓰는 것은 share 하나뿐이고 증감률은
        // 여기서 의미가 없다. 넘겨도 무시되지만 조회를 한 번 더 하게 된다.
        List<ProductShare> items = ProductShares.metrics(lines, List.of()).stream()
                .map(m -> new ProductShare(m.productKey(), m.productName(), m.share()))
                .toList();

        return new ShareSnapshot(
                newest.getPeriodStart(),
                newest.getPeriodEnd(),
                ProductShares.basisOf(lines).name(),
                rows.stream().anyMatch(r -> "MISMATCHED".equals(r.getChecksumStatus())),
                items);
    }
}
