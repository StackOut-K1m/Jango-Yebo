"""§6.4 검산 — 읽어낸 값만 저장하고, 계산은 검산에만 쓴다.

부가세를 LLM 에게 계산시키지 않는다. 인쇄된 값을 옮겨 적게 하고 여기서 대조한다.

검산식은 한국 영수증의 실제 합계 구역을 따른다 (KORIE val 50장 실측, 2026-09-07):
- 마트·편의점: `과세물품 + 부가세 + 면세물품 = 결제금액`. 면세 줄이 없으면 면세 0.
- 세금계산서·거래명세서·간이영수증: `공급가액 + 세액 = 합계`.
- 할인이 있으면 품목 합은 `Σ(품목금액 - 품목할인)` 또는 `Σ품목금액 - 할인총액` 이 합계와 맞아야
  한다.
v1.9 의 `공급가 + 부가세 = 합계` 하나만 보면 면세 품목이 있는 영수증(50장 중 26장)이 전부 실패한다.
"""

from __future__ import annotations

import re
from datetime import date, timedelta

from app.ocr.schemas import EvidenceExtraction, ExtractedItem, ReviewReason, ValidationReport

_BUSINESS_NO_WEIGHTS = (1, 3, 7, 1, 3, 7, 1, 3, 5)
# 신뢰도는 기본적으로 needsReview 에 넣지 않는다 (`low_confidence=None`).
#
# test 359장 재채점(2026-09-08, `experiments/analyze_review_gate.py`): 임계값 0.7 을 켜면
# 검토율이 13.9% → 23.4% 로 늘지만 검토 대상 중 실제 오류는 18% → 23% 로만 오른다. 이 데이터의
# 오류율 자체가 16.7% 이니 무작위로 찍어도 16.7% 다 — 영수증 단위 게이트로서 신뢰도는 변별력이
# 거의 없다.
#
# 다만 회계에 영향을 주는 오류(날짜 3건·합계 3건)는 결정적 검사가 0/6 을 잡는다. 그럴듯한 오답
# 날짜는 범위 검사를 통과하고, 서로 맞아떨어지는 숫자를 잘못 읽으면 검산도 통과하기 때문이다.
# 이 6건에 유일하게 반응하는 신호가 신뢰도다(0.7 에서 2/6, 0.85 에서 5/6).
#
# 그래서 신뢰도를 버리지 않고 역할을 나눈다. `needsReview` 는 "인쇄된 숫자들이 서로 모순된다"는
# 설명 가능한 불리언으로 두고, 신뢰도는 `fieldConfidence` 로 그대로 보내 어느 필드를 강조할지는
# 화면이 정한다 — 불리언으로 합치면 어느 필드였는지가 사라진다. 명세 13장에서 모든 추출은 어차피
# `EXTRACTED`(사용자 확인 대기)를 거치므로 게이트가 사람이 보는지를 가르지 않는다. 게이트에
# 신뢰도를 넣어야 하는 호출자는 임계값을 넘긴다.
LOW_CONFIDENCE_THRESHOLD = 0.7  # 켤 때 쓰는 기준값 (기본은 끔)

# 표의 열 머리글 낱말 — 상품명이 될 수 없다. 이 낱말이 품목 행으로 오면 행 정렬이 밀린 것이다.
_TABLE_HEADER_WORDS = frozenset(
    {
        "상품명",
        "품명",
        "품목",
        "품목명",
        "제품명",
        "메뉴",
        "메뉴명",
        "수량",
        "단가",
        "금액",
        "단위",
        "규격",
        "비고",
        "일자",
        "번호",
        "순번",
        "합계",
        "소계",
        "총계",
        "공급가액",
        "부가세",
        "세액",
    }
)
_NAME_NOISE = re.compile(r"[\s\-_/·,.()\[\]:]+")


def _squash_name(raw: str) -> str:
    """머리글 대조용으로만 쓰는 최소 정리. 품목 정규화(§6.5)와는 별개다."""
    return _NAME_NOISE.sub("", raw).strip()


def normalize_business_number(value: str | None) -> str | None:
    """숫자 10자리면 000-00-00000 로 정리, 아니면 None."""
    if value is None:
        return None
    digits = re.sub(r"\D", "", value)
    if len(digits) != 10:
        return None
    return f"{digits[:3]}-{digits[3:5]}-{digits[5:]}"


def is_valid_business_number(value: str | None) -> bool | None:
    """국세청 사업자등록번호 검증.

    앞 9자리 × 가중치(1 3 7 1 3 7 1 3 5) 합 + (9번째 자리 × 5) // 10 →
    (10 - 합 % 10) % 10 이 10번째 자리와 같아야 한다.
    """
    if value is None:
        return None
    digits = re.sub(r"\D", "", value)
    if len(digits) != 10:
        return False
    d = [int(c) for c in digits]
    total = sum(a * w for a, w in zip(d[:9], _BUSINESS_NO_WEIGHTS, strict=True))
    total += (d[8] * 5) // 10
    return (10 - total % 10) % 10 == d[9]


def check_amounts(
    supply: int | None,
    vat: int | None,
    total: int | None,
    *,
    taxable: int | None = None,
    tax_free: int | None = None,
    tolerance: int = 0,
) -> bool | None:
    """인쇄된 금액들의 검산. 어느 식도 세울 수 없으면 판정 불가(None).

    우선순위: ① 과세물품 + 부가세 + 면세물품 = 합계 ② 과세물품 + 부가세 = 합계(면세 줄 없음)
    ③ 공급가액 + 부가세 = 합계. 파생값(예: 합계 - 부가세)으로는 검산하지 않는다 — 항등식이 된다.
    """
    if vat is None or total is None:
        return None
    if taxable is not None:
        return abs(taxable + vat + (tax_free or 0) - total) <= tolerance
    if supply is not None:
        return abs(supply + vat - total) <= tolerance
    return None


def check_vat_ratio(
    vat: int | None,
    *,
    taxable: int | None = None,
    supply: int | None = None,
    tax_free: int | None = None,
    total: int | None = None,
    tolerance_won: int = 2,
    tolerance_rel: float = 0.005,
) -> bool | None:
    """부가세 = 과세분 × 10%. `check_amounts` 가 원리적으로 못 잡는 오류를 잡는다.

    합계 검산은 덧셈이라 **자리가 바뀐 것을 못 본다** — 합성 현금영수증 실측(2026-09-08)에서
    모델이 과세·면세·부가세 세 값을 한 칸씩 밀어 적었는데 `과세 + 부가세 + 면세 = 합계` 는
    그대로 통과했다(교환법칙). 세율 관계는 순열에 깨지므로 이 경우를 잡는다.

    과세분은 인쇄된 `과세물품` → `공급가액` → `합계 - 부가세 - 면세물품` 순으로 잡는다. 마지막
    유도식은 항등식이 아니다 — `11 × 부가세 = 합계 - 면세` 라는 독립 제약이 된다(10/110 관계).

    세액 0 은 판정하지 않는다(None) — 영세율·면세 계산서에서 정상이다. 허용 오차를 넉넉히 둔 것은
    이 검사의 목적이 1원 차이가 아니라 **자리 밀림·자릿수 오독**이기 때문이다. 좁히면 POS 의 반올림
    방식 차이(`과세 × 0.1` vs `과세포함액 × 10/110`)에서 오탐이 난다.
    """
    if vat is None or vat == 0:
        return None
    base = taxable if taxable is not None else supply
    if base is None and total is not None:
        base = total - vat - (tax_free or 0)
    if base is None:
        return None
    expected = base / 10
    return abs(vat - expected) <= max(tolerance_won, abs(expected) * tolerance_rel)


def check_items_sum(
    items: list[ExtractedItem],
    total: int | None,
    *,
    discount_total: int | None = None,
    supply: int | None = None,
    abs_tolerance: int = 10,
    rel_tolerance: float = 0.01,
) -> bool | None:
    """Σ품목금액 ≒ 합계. 절사·할인으로 조금 어긋날 수 있어 허용 오차를 둔다.

    품목별 할인(`discountAmount`)이 있으면 순액(금액 - 할인)으로 더한다. 그래도 맞지 않고
    할인 총액이 따로 있으면 `Σ품목금액 - 할인총액` 도 시도한다 — 모델이 할인을 행에 붙였는지
    총액으로만 읽었는지에 따라 둘 중 하나가 맞는다.
    세금계산서·거래명세서는 행 금액이 공급가액(부가세 제외)이라 Σ품목 = 공급가액이면 통과다.

    **공급가액 줄이 인쇄되지 않은 거래명세서**도 있다 — 행 금액은 공급가액인데 아래에 합계(부가세
    포함)만 찍혀 있다. 이때는 `Σ품목 × 1.1 ≒ 합계` 로 본다. 합성 거래명세서 15장 중 9장이 이
    양식이었고, 이 경로가 없어서 **예측이 라벨과 완전히 같은데도 불일치로 걸렸다**(2026-09-09).
    파생값이지만 항등식이 아니다 — 법정 세율로 행 합과 인쇄된 합계를 잇는 독립 제약이다.
    """
    amounts = [item.amount for item in items if item.amount is not None]
    if total is None or not amounts:
        return None
    allowed = max(abs_tolerance, round(abs(total) * rel_tolerance))
    gross = sum(amounts)
    net = gross - sum(item.discount_amount or 0 for item in items)
    if abs(net - total) <= allowed:
        return True
    if discount_total is not None and abs(gross - discount_total - total) <= allowed:
        return True
    if supply is not None and abs(gross - supply) <= allowed:
        return True
    # 공급가액·세액 줄이 없는 양식: 행 금액이 공급가액이고 합계만 부가세 포함으로 인쇄된 경우
    if supply is None and abs(round(gross * 1.1) - total) <= allowed:
        return True
    return False


def check_item_alignment(items: list[ExtractedItem]) -> bool | None:
    """품목 행의 이름과 숫자가 같은 줄에서 온 것인지 **구조로** 본다.

    `check_items_sum` 은 이걸 못 잡는다. 이름 열만 한 칸 밀려도 금액은 온전해서 합이 그대로
    맞기 때문이다 — 합성 현금영수증 실측(2026-09-08, `synth-cash-receipt-005`)에서 표 머리글이
    첫 행 이름이 되고 마지막 상품이 금액을 못 받았는데 Σ품목·검산이 다 통과했다. 같은 계열의
    실패가 세 번 관측됐다: P1-22 매출 인쇄물 행 밀림, 금액 칸 밀림(`check_vat_ratio` 로 보완),
    품목 이름 밀림.

    두 신호 중 하나라도 걸리면 밀림으로 본다.
    ① 품목 이름이 표의 열 머리글 낱말이다 — 상품명·수량·금액 같은 낱말은 상품명이 아니다.
    ② 마지막 행만 금액이 비어 있다 — 이름이 한 칸 밀리면 맨 끝 상품이 숫자를 못 받는다.

    실측 오탐(2026-09-08): ① 은 실제 영수증 409장(KORIE test 359 + val 50)에서 0건,
    ② 는 test 359장에서 1건. ② 를 함께 넣는 것은 머리글에서 시작하지 않는 밀림도 잡기 위해서다.
    """
    if not items:
        return None
    if any(_squash_name(item.raw_name) in _TABLE_HEADER_WORDS for item in items):
        return False
    if len(items) >= 2 and items[-1].amount is None:
        return not all(item.amount is not None for item in items[:-1])
    return True


def check_issued_date(value: date | None, *, today: date | None = None) -> bool | None:
    if value is None:
        return None
    today = today or date.today()
    return date(2000, 1, 1) <= value <= today + timedelta(days=1)


def validate_extraction(
    extraction: EvidenceExtraction,
    *,
    today: date | None = None,
    low_confidence: float | None = None,
) -> ValidationReport:
    """§6.4 검산 + 검토 사유. `low_confidence` 에 임계값을 주면 신뢰도도 사유에 넣는다(기본 끔)."""
    report = ValidationReport(
        amounts_consistent=check_amounts(
            extraction.supply_amount,
            extraction.vat_amount,
            extraction.total_amount,
            taxable=extraction.taxable_amount,
            tax_free=extraction.tax_free_amount,
        ),
        items_sum_consistent=check_items_sum(
            extraction.items,
            extraction.total_amount,
            discount_total=extraction.discount_amount,
            supply=extraction.supply_amount,
        ),
        vat_ratio_consistent=check_vat_ratio(
            extraction.vat_amount,
            taxable=extraction.taxable_amount,
            supply=extraction.supply_amount,
            tax_free=extraction.tax_free_amount,
            total=extraction.total_amount,
        ),
        items_aligned=check_item_alignment(extraction.items),
        business_no_valid=is_valid_business_number(extraction.supplier_business_no),
        date_valid=check_issued_date(extraction.issued_date, today=today),
    )
    reasons: list[ReviewReason] = []
    if report.amounts_consistent is False:
        reasons.append("AMOUNTS_INCONSISTENT")
        report.issues.append("인쇄된 금액의 검산(과세 + 부가세 + 면세 = 합계)이 맞지 않습니다.")
    if report.items_sum_consistent is False:
        reasons.append("ITEMS_SUM_INCONSISTENT")
        report.issues.append("품목 금액 합이 합계와 다릅니다.")
    if report.vat_ratio_consistent is False:
        reasons.append("VAT_RATIO_INCONSISTENT")
        report.issues.append("부가세가 과세분의 10%와 다릅니다 — 금액 칸이 밀렸을 수 있습니다.")
    if report.items_aligned is False:
        reasons.append("ITEM_ROWS_MISALIGNED")
        report.issues.append("품목 행의 이름과 금액이 어긋난 것으로 보입니다.")
    if report.business_no_valid is False:
        reasons.append("BUSINESS_NO_INVALID")
        report.issues.append("사업자등록번호 체크섬이 맞지 않습니다.")
    if report.date_valid is False:
        reasons.append("DATE_INVALID")
        report.issues.append("발행일이 유효 범위(2000년~내일)를 벗어납니다.")
    if extraction.total_amount is None:
        reasons.append("TOTAL_MISSING")
        report.issues.append("합계를 읽지 못했습니다.")
    if extraction.issued_date is None:
        reasons.append("DATE_MISSING")
        report.issues.append("발행일을 읽지 못했습니다.")
    if extraction.supplier_name is None:
        reasons.append("SUPPLIER_MISSING")
        report.issues.append("상호를 읽지 못했습니다.")
    if low_confidence is not None:
        low = sorted(k for k, v in extraction.field_confidence.items() if v < low_confidence)
        if low:
            reasons.append("LOW_CONFIDENCE")
            report.issues.append(f"모델 신뢰도가 낮은 필드: {', '.join(low)}")
    report.review_reasons = reasons
    report.needs_review = bool(reasons)
    return report
