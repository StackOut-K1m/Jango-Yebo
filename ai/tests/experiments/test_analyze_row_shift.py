"""행 밀림과 진짜 오독을 가르는 규칙을 못 박는다 — 이 구분이 검출률 표기의 근거다."""

from app.ocr.schemas import ProductSalesExtraction, ProductSalesRow, SalesPeriod
from experiments import analyze_row_shift as ars


def _rows() -> list[ProductSalesRow]:
    return [
        ProductSalesRow(product_name="아메리카노", quantity=92, billed_amount=368_000, share=0.40),
        ProductSalesRow(product_name="카페라떼", quantity=47, billed_amount=161_950, share=0.18),
        ProductSalesRow(product_name="콜드브루", quantity=30, billed_amount=120_000, share=0.13),
    ]


def test_taking_the_neighbour_row_numbers_is_a_shift_not_a_misread():
    labeled = _rows()
    # 2행 이름에 3행 숫자가 붙은 경우 — 값을 잘못 읽은 것이 아니라 뭉치가 밀린 것이다
    shifted = ProductSalesRow(
        product_name="카페라떼", quantity=30, billed_amount=120_000, share=0.13
    )
    assert ars.same_numbers(labeled[1], shifted) is False
    assert ars.shift_distance(labeled, 1, shifted) == 1

    # 앞쪽으로 밀린 것도 같은 계열이다
    back = ProductSalesRow(product_name="카페라떼", quantity=92, billed_amount=368_000, share=0.40)
    assert ars.shift_distance(labeled, 1, back) == -1


def test_a_single_wrong_value_is_not_classified_as_a_shift():
    labeled = _rows()
    misread = ProductSalesRow(
        product_name="카페라떼", quantity=47, billed_amount=161_050, share=0.18
    )
    assert ars.same_numbers(labeled[1], misread) is False
    assert ars.shift_distance(labeled, 1, misread) is None


def test_share_consensus_ignores_rounding_but_sees_a_broken_ratio():
    extraction = ProductSalesExtraction(
        period=SalesPeriod(), columns_detected=["productName"], items=_rows()
    )
    assert ars.share_outliers(extraction) == set()

    broken = ProductSalesExtraction(
        period=SalesPeriod(),
        columns_detected=["productName"],
        items=[row.model_copy() for row in _rows()],
    )
    broken.items[2].billed_amount = 500_000  # 구성비는 그대로인데 금액만 바뀌면 비율이 깨진다
    assert ars.share_outliers(broken) == {2}


def test_too_few_rows_cannot_form_a_consensus():
    extraction = ProductSalesExtraction(
        period=SalesPeriod(), columns_detected=["productName"], items=_rows()[:2]
    )
    assert ars.share_outliers(extraction) == set()
