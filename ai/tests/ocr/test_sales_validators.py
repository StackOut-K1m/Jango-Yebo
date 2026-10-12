"""§6.8 매출 인쇄물 검산 — 20.9 에 없던 검산 (확장 v1.18).

합성 90표본 1,059 라벨 행에서 네 식이 전부 100% 성립했고, 라벨에 돌리면 오탐이 0 이다.
이 테스트는 그 성질과 **일부러 넣지 않은 것**(Σ품목 = 합계 행, 기간 누락)을 함께 고정한다.
"""

from app.ocr.sales_validators import check_printed_total, check_rows, validate_product_sales
from app.ocr.schemas import (
    PrintedTotal,
    ProductSalesExtraction,
    ProductSalesRow,
    SalesPeriod,
)


def _row(**kwargs) -> ProductSalesRow:
    base = {
        "product_name": "아메리카노",
        "gross_amount": 368_000,
        "discount_amount": 0,
        "net_amount": 334_545,
        "vat_amount": 33_455,
        "billed_amount": 368_000,
    }
    return ProductSalesRow(**{**base, **kwargs})


GOOD_TOTAL = PrintedTotal(
    gross_amount=690_000, net_amount=627_273, vat_amount=62_727, billed_amount=690_000
)


def _extraction(rows, total=None, source="HEADER") -> ProductSalesExtraction:
    return ProductSalesExtraction(
        period=SalesPeriod(source=source),
        columns_detected=["productName", "grossAmount", "netAmount", "vatAmount", "billedAmount"],
        items=rows,
        printed_total=total,
    )


def test_consistent_row_passes():
    ok, bad = check_rows([_row()])
    assert ok is True and bad == []


def test_each_relation_is_checked():
    # 공급가 + 부가세 ≠ 결제금액
    assert check_rows([_row(billed_amount=400_000)])[0] is False
    # 부가세 ≠ 공급가액 × 10%
    assert check_rows([_row(vat_amount=50_000, billed_amount=384_545)])[0] is False
    # 판매금액 − 할인 ≠ 결제금액
    assert check_rows([_row(discount_amount=1_000)])[0] is False


def test_zero_vat_is_not_judged():
    """영세율·면세 상품은 세액 0 이 정상이다."""
    ok, _ = check_rows([_row(net_amount=368_000, vat_amount=0, billed_amount=368_000)])
    assert ok is True


def test_missing_columns_are_not_judged():
    """열이 적은 레이아웃 — 세울 식이 없으면 판정 불가다."""
    thin = ProductSalesRow(product_name="라떼", quantity=3.0)
    assert check_rows([thin]) == (None, [])
    assert check_rows([]) == (None, [])


def test_inconsistent_row_numbers_are_reported():
    rows = [_row(), _row(billed_amount=1), _row()]
    ok, bad = check_rows(rows)
    assert ok is False and bad == [1]
    report = validate_product_sales(_extraction(rows))
    assert report.inconsistent_rows == [1]
    assert "2행" in report.issues[0]  # 사람에게는 1부터 센 번호로 보인다


def test_printed_total_is_checked_the_same_way():
    good = GOOD_TOTAL
    assert check_printed_total(good) is True
    assert check_printed_total(good.model_copy(update={"billed_amount": 1})) is False
    assert check_printed_total(None) is None
    assert check_printed_total(PrintedTotal(quantity=139.0)) is None  # 금액이 없으면 판정 불가


def test_period_missing_is_not_a_review_reason():
    """요구 1 — 헤더에 기간이 없으면 null 이 정상이다. 정상을 오류로 세면 안 된다.

    합성 90표본 중 12개가 그런 인쇄물이고, 사유로 넣으면 게이트가 2.2% → 15.6% 로 뛴다.
    """
    report = validate_product_sales(_extraction([_row()], total=GOOD_TOTAL, source=None))
    assert report.needs_review is False
    assert report.review_reasons == []


def test_items_sum_against_printed_total_is_deliberately_not_checked():
    """라벨 90개 중 20개가 `Σ품목 = 합계 행` 을 만족하지 않는다 — 인쇄물이 잘리면 정상이다."""
    total = PrintedTotal(gross_amount=9_999_999, net_amount=9_090_908, vat_amount=909_091,
                         billed_amount=9_999_999)  # fmt: skip
    report = validate_product_sales(_extraction([_row()], total=total))
    assert report.printed_total_consistent is True  # 합계 행 자체는 일관적이다
    assert report.needs_review is False  # Σ품목과 달라도 사유가 아니다


def test_missing_total_and_no_rows_are_reasons():
    assert validate_product_sales(_extraction([_row()])).review_reasons == ["TOTAL_MISSING"]
    empty = validate_product_sales(_extraction([]))
    assert set(empty.review_reasons) == {"TOTAL_MISSING", "NO_ROWS"}
