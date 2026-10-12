"""2번 OCR 파트 계약 — 명세 20.4(증빙) · 20.5(품목 정규화) · 20.9(매출 인쇄물) · 13.2(extraction).

JSON 은 camelCase(CamelModel). 명세에 없는 필드는 주석으로 "확장"임을 밝힌다.
"""

from datetime import date
from typing import Literal

from pydantic import Field

from app.common.envelope import CamelModel

# ERD core.evidence ck_evidence_type 과 동일
EvidenceType = Literal[
    "RECEIPT",
    "TAX_INVOICE",
    "INVOICE",
    "CASH_RECEIPT",
    "SETTLEMENT_STATEMENT",
    "CONTRACT",
    "PAYROLL",
    "OTHER",
]
PreferredPipeline = Literal["AUTO", "OCR_PIPELINE", "VLM"]
# 확장(v1.17): 재추출 강도. THOROUGH 는 사용자가 "이 결과가 틀렸다" 고 했을 때만 쓴다 —
# 긴 영수증 타일링과 강한 모델을 켜므로 비용이 오른다(타일링 실측 +52%). 전건에 쓰지 않는다.
ExtractionMode = Literal["STANDARD", "THOROUGH"]
CaptureType = Literal["SCREEN", "PRINTOUT"]


class AcceptedJob(CamelModel):
    """비동기 접수 응답 — `202 { "jobId": ... }` (명세 20.2)."""

    job_id: int


# ---------------------------------------------------------------- 20.4 증빙 추출


class EvidenceExtractionRequest(CamelModel):
    job_id: int
    evidence_id: int
    file_object_key: str = Field(min_length=1, max_length=512)
    evidence_type_hint: EvidenceType = "RECEIPT"
    preferred_pipeline: PreferredPipeline = "AUTO"
    # 확장(v1.17): 기본 STANDARD. 재추출 요청에서만 THOROUGH 로 올린다
    extraction_mode: ExtractionMode = "STANDARD"


class BoundingBox(CamelModel):
    """원본 이미지 대비 비율 좌표 (13.2). 검토 화면 하이라이트용."""

    x: float = Field(ge=0, le=1)
    y: float = Field(ge=0, le=1)
    width: float = Field(ge=0, le=1)
    height: float = Field(ge=0, le=1)


#: 백엔드 저장 한도. `evidence_item.raw_name VARCHAR(255)`·`unit VARCHAR(20)` 에서 왔다
#: (V6__evidence_extraction.sql). 모델이 낸 값을 그대로 흘리면 검산·게이트를 다 통과한
#: 결과가 저장 단계에서만 죽는다 — 원인을 찾기 가장 어려운 자리다. 넘치면 자르고
#: warnings 에 남긴다(버리지 않는다). 값이 어긋나지 않게 테스트가 DDL 과 대조한다
#: (`tests/ocr/test_v6_schema_conformance.py`).
MAX_RAW_NAME = 255
MAX_UNIT = 20


class ExtractedItem(CamelModel):
    raw_name: str
    quantity: float | None = None
    unit: str | None = None
    unit_price: int | None = None
    amount: int | None = None  # 인쇄된 행 금액(할인 전)
    # 확장(v1.10): 이 행 바로 아래 인쇄된 에누리·행사할인 줄의 금액(양수). 할인 줄을 별도 행으로
    # 만들지 않는다 — 음수 행이 품목으로 섞이면 단가·품목 통계가 왜곡된다.
    # 순액은 amount - discountAmount.
    discount_amount: int | None = None
    bounding_box: BoundingBox | None = None


class EvidenceExtraction(CamelModel):
    """13.2 `extraction` 과 같은 구조 + 검산용 확장 필드.

    한국 마트 영수증의 합계 구역은 `과세물품 + 부가세 + 면세물품 = 결제금액` 으로 짜여 있고,
    할인이 있으면 `총합계(할인 전) - 할인 = 결제금액` 이 따로 인쇄된다. v1.9 의 `supplyAmount`
    하나로는 이 구조를 담을 수 없어(면세 금액이 갈 곳이 없고, 검산이 면세 영수증에서 전부 실패)
    v1.10 에서 `taxableAmount`·`taxFreeAmount`·`discountAmount` 를 추가했다. 모두 **인쇄된 값만**
    옮기고, 계산은 `validators.py` 가 한다.
    """

    # 확장(v1.21) — **지금은 항상 null 이다(v1.22).** 모델에게 종류까지 판별시켰더니 품목 행의
    # 숫자가 이웃 행 것으로 밀렸다(합성 현금영수증 15장 A/B: 품목 금액 86.4% → 69.0%,
    # 수량 86.4% → 72.4%, v4 에서 맞던 8행이 틀리고 반대는 0건). `boundingBox` 와 같은 처리로
    # 스키마에는 남긴다 — 판별을 본문 추출과 분리할 방법이 생기면 채운다.
    evidence_type: EvidenceType | None = None
    supplier_name: str | None = None  # 매장명 줄(상호 + 지점). 로고만 있으면 로고 텍스트
    supplier_business_no: str | None = None
    issued_date: date | None = None
    # "공급가액·공급가" 라고 인쇄된 줄만(세금계산서·거래명세서·간이영수증). 없으면 null.
    supply_amount: int | None = None
    taxable_amount: int | None = None  # 확장(v1.10): "과세물품·과세금액" 줄
    tax_free_amount: int | None = None  # 확장(v1.10): "면세물품·면세금액" 줄 (0 으로 인쇄되면 0)
    vat_amount: int | None = None
    discount_amount: int | None = None  # 확장(v1.10): "할인·에누리" 총액 줄 (양수)
    # 최종 결제 금액(결제대상금액·청구액·받을금액). 할인 전 "총합계"가 따로 있으면 그것이 아니다.
    total_amount: int | None = None
    items: list[ExtractedItem] = Field(default_factory=list)
    field_confidence: dict[str, float] = Field(default_factory=dict)
    warnings: list[str] = Field(default_factory=list)


# 검토(review)가 필요한 이유 코드. 신뢰도 점수가 아니라 결정적 검사에서 나온다 — 실측(val-001)에서
# 틀린 필드의 평균 신뢰도(0.935)와 맞은 필드(0.972)가 거의 같아 임계값으로는 걸러지지 않았다.
ReviewReason = Literal[
    "AMOUNTS_INCONSISTENT",  # 인쇄된 금액들의 검산이 맞지 않음
    "ITEMS_SUM_INCONSISTENT",  # 품목 합이 합계와 다름
    # 확장(v1.14): 부가세 ≠ 과세분 × 10%. 합계 검산이 원리적으로 못 잡는 "금액 칸 밀림" 을 잡는다
    "VAT_RATIO_INCONSISTENT",
    # 확장(v1.15): 품목 행의 이름과 숫자가 어긋났다(표 머리글이 품목으로 들어옴 / 마지막 행만
    # 금액 없음). 금액 자체는 온전해 합·검산이 다 통과하므로 산술로는 잡히지 않는 계열이다
    "ITEM_ROWS_MISALIGNED",
    "BUSINESS_NO_INVALID",  # 사업자등록번호 체크섬 실패
    "DATE_INVALID",  # 발행일이 유효 범위 밖
    "TOTAL_MISSING",  # 합계를 읽지 못함
    "DATE_MISSING",  # 발행일을 읽지 못함
    "SUPPLIER_MISSING",  # 상호를 읽지 못함
    "LOW_CONFIDENCE",  # 모델 신뢰도가 낮은 필드 — 기본은 게이트에 넣지 않는다(변별력 약함)
]


class ValidationReport(CamelModel):
    """§6.4 검산 결과 (확장). None 은 판정 불가(값 없음).

    v1.10: 워커가 `needsReview` 와 이유 코드를 함께 보낸다 — API 서버는 `needsReview` 만 본다.
    """

    # 과세물품 + 부가세 + 면세물품 = 합계. 과세·면세 줄이 없는 양식(세금계산서 등)은
    # 공급가 + 부가세 = 합계
    amounts_consistent: bool | None = None
    items_sum_consistent: bool | None = None  # Σ(품목금액 - 품목할인) ≒ 합계 (또는 Σ품목 - 할인)
    # 확장(v1.14): 부가세 = 과세분 × 10%. 세액 0(영세율·면세)은 판정하지 않는다
    vat_ratio_consistent: bool | None = None
    # 확장(v1.15): 품목 행 정렬. 품목이 없으면 판정 불가
    items_aligned: bool | None = None
    business_no_valid: bool | None = None  # 사업자등록번호 체크섬
    date_valid: bool | None = None
    needs_review: bool = False
    review_reasons: list[ReviewReason] = Field(default_factory=list)
    issues: list[str] = Field(default_factory=list)  # 사람이 읽는 설명 (reviewReasons 와 1:1 아님)


class EvidenceValidationRequest(CamelModel):
    """§6.4 검산만 다시 돌린다 (확장 v1.17). 모델을 부르지 않아 즉시·무료다.

    사용자가 검토 화면에서 값을 고친 뒤 "이제 검산이 맞는가" 를 확인하는 용도다. 검산 규칙을
    백엔드나 화면이 복제하면 워커와 어긋나므로(같은 식이 두 곳에 살면 반드시 드리프트한다)
    규칙은 여기 한 곳에 두고 결과만 돌려준다.
    """

    extraction: EvidenceExtraction
    # 임계값을 주면 낮은 신뢰도도 검토 사유에 넣는다. 기본은 끔 (v1.13 — 변별력이 약하다)
    low_confidence: float | None = Field(default=None, ge=0, le=1)


# ---------------------------------------------------------------- 20.5 품목 정규화


class KnownItem(CamelModel):
    item_id: int
    canonical_name: str
    aliases: list[str] = Field(default_factory=list)


class ItemNormalizationRequest(CamelModel):
    business_id: int
    known_items: list[KnownItem] = Field(default_factory=list)
    raw_names: list[str] = Field(min_length=1, max_length=200)


class ItemMapping(CamelModel):
    raw_name: str
    matched_item_id: int | None = None
    confidence: float | None = None
    suggested_canonical_name: str | None = None
    suggested_category: str | None = None
    # 확장(v1.11): 어느 단계가 확정했나 — RULE(정규화 후 동일) / SIMILARITY(2차) / LLM(3차).
    # §6.5 "규칙이 90% 이상을 처리" 비율을 대시보드에서 세기 위한 필드. 미매칭은 null.
    matched_by: Literal["RULE", "SIMILARITY", "LLM"] | None = None


class ItemNormalizationResponse(CamelModel):
    mappings: list[ItemMapping]


# ---------------------------------------------------------------- 20.9 매출현황 인쇄물


class ProductSalesExtractionRequest(CamelModel):
    job_id: int
    business_id: int
    capture_type: CaptureType
    file_object_keys: list[str] = Field(min_length=1, max_length=10)
    known_products: list[str] = Field(default_factory=list)


class SalesPeriod(CamelModel):
    start: date | None = None
    end: date | None = None
    # 헤더에서 못 읽으면 None — 촬영 시각으로 추론하지 않는다 (명세 20.9 요구 1)
    source: Literal["HEADER"] | None = None


class ProductSalesRow(CamelModel):
    product_name: str
    quantity: float | None = None
    gross_amount: int | None = None
    discount_amount: int | None = None
    net_amount: int | None = None
    vat_amount: int | None = None
    billed_amount: int | None = None
    share: float | None = None
    confidence: float | None = None


class PrintedTotal(CamelModel):
    """합계 행 — 항목 행과 분리해 체크섬으로 쓴다 (명세 20.9 요구 2)."""

    quantity: float | None = None
    gross_amount: int | None = None
    discount_amount: int | None = None
    net_amount: int | None = None
    vat_amount: int | None = None
    billed_amount: int | None = None


# 확장(v1.18): 매출 인쇄물 검토 사유. 증빙과 같은 원칙 — 결정적 검사에서만 나온다.
SalesReviewReason = Literal[
    "ROW_ARITHMETIC_INCONSISTENT",  # 행 안의 금액이 서로 안 맞음 (판매−할인 ≠ 공급가+부가세 등)
    "TOTAL_ARITHMETIC_INCONSISTENT",  # 합계 행이 스스로 모순 (요구 2 의 체크섬)
    "TOTAL_MISSING",  # 합계 행을 읽지 못함
    "NO_ROWS",  # 상품 행을 하나도 읽지 못함
]


class ProductSalesValidation(CamelModel):
    """§6.8 검산 결과 (확장 v1.18). None 은 판정 불가(값이 없어 식을 세울 수 없음).

    **`Σ품목 = 합계 행` 은 넣지 않았다** — 라벨 90개 중 20개가 이 등식을 만족하지 않는다.
    POS 인쇄물이 여러 장으로 잘리면 합계 행은 기간 전체를 담고 촬영된 장에는 일부 행만 보인다.
    """

    # **`PERIOD_MISSING` 은 사유에 없다.** 헤더에 기간이 인쇄되지 않은 인쇄물이 있고(합성 90표본
    # 중 12개), 그때 `period.source = null` 은 **요구 1 이 지시한 정상 동작**이다. 사유로 넣으면
    # 정상을 오류로 세어 게이트가 2.2% → 15.6% 로 뛴다. 기간이 필요한지는 화면이 `period.source`
    # 를 직접 보면 된다 — 페이로드에 그대로 있는 것을 불리언에 또 담지 않는다(v1.13 과 같은 원칙).
    row_arithmetic_consistent: bool | None = None
    printed_total_consistent: bool | None = None
    inconsistent_rows: list[int] = Field(default_factory=list)  # 0-based 행 번호
    needs_review: bool = False
    review_reasons: list[SalesReviewReason] = Field(default_factory=list)
    issues: list[str] = Field(default_factory=list)


class ProductSalesExtraction(CamelModel):
    period: SalesPeriod
    columns_detected: list[str] = Field(default_factory=list)  # 읽은 열만 (요구 3)
    items: list[ProductSalesRow] = Field(default_factory=list)
    printed_total: PrintedTotal | None = None
    warnings: list[str] = Field(default_factory=list)  # 못 읽은 행은 여기 (요구 4)
