"""§6.8 매출 인쇄물 검산 — 20.9 에는 검산이 없었다. 어떤 숫자 오독도 그대로 통과했다.

증빙(20.4)에는 검산 5종이 있는데 매출 인쇄물에는 하나도 없어서, 백엔드가 받는 숫자에 신호가
전혀 없었다. 명세 20.9 요구 2 는 합계 행을 "체크섬" 이라 부르는데 우리는 읽어만 두고 대조하지
않았다.

**쓸 수 있는 식 (합성 90표본 1,059 라벨 행에서 전부 100% 성립, 2026-09-09 확인)**
- `판매금액 − 할인 = 공급가액 + 부가세` (337행)
- `공급가액 + 부가세 = 결제금액` (337행)
- `공급가액 × 10% = 부가세` (337행) — 세액 0 은 판정하지 않는다(영세율·면세)
- `판매금액 − 할인 = 결제금액` (706행 — 열이 적은 레이아웃까지 덮는다)

같은 식을 **합계 행에도** 적용한다. 라벨 90개 중 위반 0 이라 유효하고, 실측에서 모델이 합계 행을
틀린 적이 없어(요구 2 100%) 지금은 발동하지 않는 가드다.

**쓸 수 없는 식 — `Σ품목 = 합계 행`.** 증빙의 `check_items_sum` 을 유추해 넣으려다 접었다.
라벨 90개 중 20개(22.2%)가 이 등식을 만족하지 않는다. POS 인쇄물이 여러 장으로 잘리면 합계 행은
기간 전체를 담고 촬영된 장에는 일부 행만 보인다 — 생성기가 의도한 것이고 실제 상황도 그렇다.
넣으면 22% 를 틀리게 잡는다.

**기간 누락은 사유가 아니다.** 헤더에 기간이 인쇄되지 않은 인쇄물이 있고(90표본 중 12개), 그때
`period.source = null` 은 요구 1 이 지시한 정상 동작이다. 사유로 넣으면 게이트가 2.2% → 15.6% 로
뛰는데 늘어난 13%p 가 전부 정상이다. 기간이 필요한지는 화면이 `period.source` 를 직접 본다.

**한계 (실측 2026-09-09, 90표본).** 행 산술은 **정밀도 100%(2/2)** 다. 검출률은 어느 분모로
세는지에 따라 다르고, 그 차이가 이 검사를 이해하는 핵심이다.

숫자가 틀린 57행을 예측한 숫자 뭉치와 **이웃 라벨 행의 뭉치**로 대조해 갈랐다
(`experiments/analyze_row_shift.py`, 재현 비용 0).

| 계열 | 행 | 이 검사가 잡는 것 |
| --- | ---: | ---: |
| 이웃 행의 숫자 뭉치를 그대로 받음 (**행 밀림**) | 52 (91.2%) | 0 |
| 값 자체를 잘못 읽음 (**진짜 오독**) | 5 (8.8%) | **2 → 40%** |

- 전체 기준 3.5%(2/57)는 **분모에 원리적으로 볼 수 없는 52행이 들어 있어** 검사를 깎아 적는다.
- 겨냥한 계열 기준 **40%(2/5)** 가 실제 성능이다. 플래그율은 0.2% 라 검토 부담이 없다.
- 행 밀림은 열이 통째로 움직여 행 안의 산술이 그대로 성립한다. 구성비(`share`)도 같이 실려
  오므로 `결제금액 / 구성비` 합의 검사조차 0/57 이다(라벨 1,059행 오탐 0 — 식은 옳지만 이
  계열을 못 본다). 잡으려면 좌표(v1.16 실측: 중심 적중 33%)나 재판독처럼 **행 밖의 근거**가
  필요하다. 증빙 쪽에서 세 번 관측한 것과 같은 계열이다(`validators.py` 참고).
"""

from __future__ import annotations

from app.ocr.schemas import (
    PrintedTotal,
    ProductSalesExtraction,
    ProductSalesRow,
    ProductSalesValidation,
    SalesReviewReason,
)

TOLERANCE = 2  # 원 단위 절사·반올림 여유


def _arithmetic_problems(row: ProductSalesRow | PrintedTotal) -> list[str]:
    """행 하나가 스스로 모순되는가. 값이 없어 세울 수 없는 식은 건너뛴다.

    **할인은 없으면 0 으로 본다.** 「인쇄된 0」과 「열이 없음」을 구분하지 않는다는 뜻이다.
    라벨 1,059행에서 `grossAmount` 와 `discountAmount` 는 항상 함께 있거나 함께 없어서
    (58mm 는 둘 다 없다) 이 가정이 오탐을 내지 않았다. 판매금액만 있고 할인 열이 없는
    인쇄물이 실제로 나오면 `판매 − 할인` 두 식이 오탐이 된다 — 그때 여기를 본다.
    """
    gross, discount = row.gross_amount, row.discount_amount or 0
    net, vat, billed = row.net_amount, row.vat_amount, row.billed_amount
    problems: list[str] = []
    if None not in (gross, net, vat) and abs((gross - discount) - (net + vat)) > TOLERANCE:
        problems.append("판매금액 − 할인 ≠ 공급가액 + 부가세")
    if None not in (net, vat, billed) and abs(net + vat - billed) > TOLERANCE:
        problems.append("공급가액 + 부가세 ≠ 결제금액")
    # 세액 0 은 판정하지 않는다 — 영세율·면세 상품에서 정상이다
    if None not in (net, vat) and vat != 0 and abs(round(net * 0.1) - vat) > TOLERANCE:
        problems.append("부가세 ≠ 공급가액 × 10%")
    if None not in (gross, billed) and abs((gross - discount) - billed) > TOLERANCE:
        problems.append("판매금액 − 할인 ≠ 결제금액")
    return problems


def check_rows(rows: list[ProductSalesRow]) -> tuple[bool | None, list[int]]:
    """(전부 일관적인가, 어긋난 행 번호). 판정할 식이 하나도 없으면 (None, [])."""
    if not rows:
        return None, []
    bad = [index for index, row in enumerate(rows) if _arithmetic_problems(row)]
    judged = any(
        row.net_amount is not None or row.billed_amount is not None or row.gross_amount is not None
        for row in rows
    )
    if not judged:
        return None, []
    return not bad, bad


def check_printed_total(total: PrintedTotal | None) -> bool | None:
    """합계 행이 스스로 모순되는가 (명세 20.9 요구 2 의 체크섬)."""
    if total is None:
        return None
    if _arithmetic_problems(total):
        return False
    if all(
        getattr(total, name) is None
        for name in ("gross_amount", "net_amount", "vat_amount", "billed_amount")
    ):
        return None
    return True


def validate_product_sales(extraction: ProductSalesExtraction) -> ProductSalesValidation:
    """§6.8 검산 + 검토 사유. `needsReview` 는 증빙과 같은 뜻이다 — 결정적 검사에서만 나온다."""
    rows_ok, bad_rows = check_rows(extraction.items)
    report = ProductSalesValidation(
        row_arithmetic_consistent=rows_ok,
        printed_total_consistent=check_printed_total(extraction.printed_total),
        inconsistent_rows=bad_rows,
    )
    reasons: list[SalesReviewReason] = []
    if rows_ok is False:
        reasons.append("ROW_ARITHMETIC_INCONSISTENT")
        numbers = ", ".join(str(index + 1) for index in bad_rows)
        report.issues.append(f"행 안의 금액이 서로 맞지 않습니다: {numbers}행")
    if report.printed_total_consistent is False:
        reasons.append("TOTAL_ARITHMETIC_INCONSISTENT")
        report.issues.append("합계 행의 금액이 서로 맞지 않습니다.")
    if extraction.printed_total is None:
        reasons.append("TOTAL_MISSING")
        report.issues.append("합계 행을 읽지 못했습니다.")
    if not extraction.items:
        reasons.append("NO_ROWS")
        report.issues.append("상품 행을 하나도 읽지 못했습니다.")
    report.review_reasons = reasons
    report.needs_review = bool(reasons)
    return report
