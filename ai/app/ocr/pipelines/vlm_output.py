"""VLM 이 내는 출력 스키마와 계약 스키마로의 변환 — Claude·로컬 VLM 두 경로가 공유한다.

계약 스키마(schemas.py)와 분리한다: 좌표(boundingBox)는 요청하지 않아 빼고(실측 2026-09-08:
값을 함께 요구하면 값 정확도는 99% 로 멀쩡한데 박스는 중심 적중 33%·IoU≥0.5 9% 라 하이라이트로
못 쓴다. `experiments/measure_boxes.py`),
날짜는 문자열로 받아 서버에서 검증한다. 모든 필드는 필수(nullable 로 "없음"을 표현) — 구조화
출력이 필드를 빠뜨리지 못하게 한다. 금액 필드 순서는 영수증 합계 구역의 인쇄 순서
(과세 → 부가세 → 면세 → 할인 → 결제금액)를 따른다.
"""

from __future__ import annotations

from datetime import date
from typing import Literal

from pydantic import BaseModel, ConfigDict

from app.ocr.schemas import (
    MAX_RAW_NAME,
    MAX_UNIT,
    EvidenceExtraction,
    ExtractedItem,
    PrintedTotal,
    ProductSalesExtraction,
    ProductSalesRow,
    SalesPeriod,
)
from app.ocr.validators import normalize_business_number


class _Strict(BaseModel):
    model_config = ConfigDict(extra="forbid")


class LlmItem(_Strict):
    rawName: str
    quantity: float | None
    unit: str | None
    unitPrice: int | None
    amount: int | None
    discountAmount: int | None  # 바로 아래 에누리 줄 (양수)


class LlmFieldConfidence(_Strict):
    supplierName: float
    supplierBusinessNo: float
    issuedDate: float
    totalAmount: float
    vatAmount: float
    items: float


class LlmEvidence(_Strict):
    supplierName: str | None
    supplierBusinessNo: str | None
    issuedDate: str | None
    taxableAmount: int | None
    vatAmount: int | None
    taxFreeAmount: int | None
    discountAmount: int | None
    totalAmount: int | None
    supplyAmount: int | None
    items: list[LlmItem]
    fieldConfidence: LlmFieldConfidence
    warnings: list[str]


class LlmPeriod(_Strict):
    start: str | None
    end: str | None
    source: Literal["HEADER"] | None


class LlmSalesRow(_Strict):
    productName: str
    quantity: float | None
    grossAmount: int | None
    discountAmount: int | None
    netAmount: int | None
    vatAmount: int | None
    billedAmount: int | None
    share: float | None
    confidence: float


class LlmPrintedTotal(_Strict):
    quantity: float | None
    grossAmount: int | None
    discountAmount: int | None
    netAmount: int | None
    vatAmount: int | None
    billedAmount: int | None


# 계약(20.9 예시)이 정한 열 키. 열거형으로 강제해 모델이 인쇄된 한글 헤더("상품명"·"구성비")를
# 그대로 내지 못하게 한다 — 실측(sales-001)에서 30표본 전부 한글 헤더를 냈다.
SalesColumn = Literal[
    "productName",
    "quantity",
    "grossAmount",
    "discountAmount",
    "netAmount",
    "vatAmount",
    "billedAmount",
    "share",
]


class LlmProductSales(_Strict):
    period: LlmPeriod
    columnsDetected: list[SalesColumn]
    items: list[LlmSalesRow]
    printedTotal: LlmPrintedTotal | None
    warnings: list[str]


def parse_date(value: str | None, warnings: list[str], label: str) -> date | None:
    if value is None:
        return None
    try:
        return date.fromisoformat(value)
    except ValueError:
        warnings.append(f"{label} 형식을 해석할 수 없어 비웠습니다.")
        return None


def _clip(value: str | None, limit: int, label: str, warnings: list[str]) -> str | None:
    """저장 한도를 넘으면 자르고 남긴다. 버리면 나머지 멀쩡한 값까지 잃는다."""
    if value is None or len(value) <= limit:
        return value
    warnings.append(f"{label}이 {limit}자를 넘어 잘랐습니다.")
    return value[:limit]


def to_evidence(out: LlmEvidence) -> EvidenceExtraction:
    """VLM 출력 → 계약 EvidenceExtraction. 사업자번호는 형식 정리, 날짜는 파싱한다."""
    warnings = list(out.warnings)
    return EvidenceExtraction(
        supplier_name=out.supplierName,
        supplier_business_no=normalize_business_number(out.supplierBusinessNo)
        or out.supplierBusinessNo,
        issued_date=parse_date(out.issuedDate, warnings, "발행일"),
        supply_amount=out.supplyAmount,
        taxable_amount=out.taxableAmount,
        tax_free_amount=out.taxFreeAmount,
        vat_amount=out.vatAmount,
        discount_amount=out.discountAmount,
        total_amount=out.totalAmount,
        items=[
            ExtractedItem(
                raw_name=_clip(item.rawName, MAX_RAW_NAME, "품목명", warnings) or "",
                quantity=item.quantity,
                unit=_clip(item.unit, MAX_UNIT, "단위", warnings),
                unit_price=item.unitPrice,
                amount=item.amount,
                discount_amount=item.discountAmount,
            )
            for item in out.items
        ],
        field_confidence=out.fieldConfidence.model_dump(),
        warnings=warnings,
    )


def _ratio(share: float | None) -> float | None:
    """구성비를 계약대로 0~1 로 맞춘다 (명세 14.4 `share: 0.242`).

    인쇄물은 `7.0%` 처럼 백분율로 찍히고 모델이 그 숫자를 그대로 내는 일이 많다(실측에서 100배).
    1 을 넘으면 백분율로 보고 나눈다 — 단일 상품 100% 는 100.0 → 1.0 이 되어 그대로 맞는다.
    """
    if share is None:
        return None
    return round(share / 100, 6) if share > 1.0 else share


def to_product_sales(out: LlmProductSales) -> ProductSalesExtraction:
    """VLM 출력 → 계약 ProductSalesExtraction. 헤더에서 못 읽은 기간은 source=null 로 되돌린다."""
    warnings = list(out.warnings)
    period = SalesPeriod(
        start=parse_date(out.period.start, warnings, "기간 시작"),
        end=parse_date(out.period.end, warnings, "기간 종료"),
        source=out.period.source,
    )
    if period.source == "HEADER" and (period.start is None or period.end is None):
        period.source = None  # 헤더에서 읽었다는데 날짜가 없으면 못 읽은 것으로 본다
    if period.start and period.end and period.start > period.end:
        # 뒤집힌 기간은 **버린다.** 백엔드가 `CHECK (period_end >= period_start)` 로 막으므로
        # 그대로 보내면 INSERT 가 깨져 **추출 전체**(행·검산·모델 메타)가 사라진다. 기간만
        # 잃는 편이 낫고, `source = null` 은 요구 1 이 지시한 정상 상태다.
        #
        # 합성 90표본에서 라벨·예측 모두 0건이다 — 관측된 적이 없다. 그래도 넣는 이유는
        # 오탐이 원리적으로 불가능하고(시작 > 종료는 판단이 아니라 순서다) 나면 잃는 것이
        # 크기 때문이다. 품목명 길이 절단을 넣은 것과 같은 성격이다.
        warnings.append("기간이 뒤집혀 있어 비웠습니다.")
        period = SalesPeriod()
    return ProductSalesExtraction(
        period=period,
        columns_detected=out.columnsDetected,
        items=[
            ProductSalesRow(
                product_name=row.productName,
                quantity=row.quantity,
                gross_amount=row.grossAmount,
                discount_amount=row.discountAmount,
                net_amount=row.netAmount,
                vat_amount=row.vatAmount,
                billed_amount=row.billedAmount,
                share=_ratio(row.share),
                confidence=row.confidence,
            )
            for row in out.items
        ],
        printed_total=(
            PrintedTotal(
                quantity=out.printedTotal.quantity,
                gross_amount=out.printedTotal.grossAmount,
                discount_amount=out.printedTotal.discountAmount,
                net_amount=out.printedTotal.netAmount,
                vat_amount=out.printedTotal.vatAmount,
                billed_amount=out.printedTotal.billedAmount,
            )
            if out.printedTotal is not None
            else None
        ),
        warnings=warnings,
    )
