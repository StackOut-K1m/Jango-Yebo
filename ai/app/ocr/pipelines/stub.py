"""고정 응답 파이프라인 — 외부 호출 없이 계약 모양만 돌려준다.

용도: 백엔드 연동 검증(도입 순서 6단계 "더미 응답으로 Job 이 완결 상태에 도달").
결과의 pipeline 은 항상 STUB 이라 운영 데이터와 섞여도 구분된다.
값은 명세 13.2 / 20.9 예시를 따르되 검산(§6.4)이 통과하도록 맞췄다.
"""

from __future__ import annotations

from datetime import date

from app.common.model_meta import ModelMeta
from app.ocr.preprocess import PreparedImage
from app.ocr.schemas import (
    EvidenceExtraction,
    EvidenceExtractionRequest,
    ExtractedItem,
    PrintedTotal,
    ProductSalesExtraction,
    ProductSalesExtractionRequest,
    ProductSalesRow,
    SalesPeriod,
)

_META = ModelMeta(model_name="stub", model_version=None, prompt_version=None, pipeline="STUB")


class StubPipeline:
    async def extract_evidence(
        self, images: list[PreparedImage], request: EvidenceExtractionRequest
    ) -> tuple[EvidenceExtraction, ModelMeta]:
        extraction = EvidenceExtraction(
            supplier_name="서울우유 광주대리점",
            supplier_business_no="124-81-00998",  # 체크섬이 맞는 번호
            issued_date=date(2026, 8, 30),
            supply_amount=141_818,
            vat_amount=14_182,
            total_amount=156_000,
            items=[
                ExtractedItem(
                    raw_name="서울우유 1L",
                    quantity=40,
                    unit="개",
                    unit_price=2_900,
                    amount=116_000,
                    # 좌표는 채우지 않는다 — live(VLM)가 항상 null 이다(v1.16 실측:
                    # 중심 적중 33%·IoU≥0.5 9%). 스텁은 백엔드·프론트의 연동 픽스처라,
                    # 여기서 값을 내면 운영에 오지 않는 좌표에 맞춰 화면을 만들게 된다.
                    # 스키마에는 남겨 두어 OCR_PIPELINE 공급자가 붙으면 채워진다.
                ),
                ExtractedItem(
                    raw_name="바나나우유 240ml",
                    quantity=25,
                    unit="개",
                    unit_price=1_600,
                    amount=40_000,
                ),
            ],
            field_confidence={
                "supplierName": 0.95,
                "supplierBusinessNo": 0.9,
                "issuedDate": 0.97,
                "totalAmount": 0.99,
                "vatAmount": 0.96,
                "items": 0.88,
            },
            warnings=["STUB 파이프라인 — 고정 응답입니다."],
        )
        return extraction, _META

    async def extract_product_sales(
        self, images: list[PreparedImage], request: ProductSalesExtractionRequest
    ) -> tuple[ProductSalesExtraction, ModelMeta]:
        extraction = ProductSalesExtraction(
            period=SalesPeriod(start=date(2022, 12, 1), end=date(2022, 12, 14), source="HEADER"),
            columns_detected=[
                "productName",
                "quantity",
                "grossAmount",
                "discountAmount",
                "netAmount",
                "vatAmount",
                "billedAmount",
                "share",
            ],
            items=[
                ProductSalesRow(
                    product_name="카페라떼",
                    quantity=47,
                    gross_amount=163_000,
                    discount_amount=1_050,
                    net_amount=147_227,
                    vat_amount=14_723,
                    billed_amount=161_950,
                    share=0.24,
                    confidence=0.91,
                ),
                ProductSalesRow(
                    product_name="아메리카노",
                    quantity=92,
                    gross_amount=368_000,
                    discount_amount=0,
                    net_amount=334_545,
                    vat_amount=33_455,
                    billed_amount=368_000,
                    share=0.55,
                    confidence=0.94,
                ),
            ],
            # 항목 합(529,950)과 일부러 다르게 둔 값 —
            # 행 누락 체크섬이 동작하는지 백엔드에서 확인할 수 있게
            # 할인 열을 비우면 검산이 0 으로 보고 `판매 − 할인 ≠ 결제` 로 잡는다.
            # 690,000 − 19,550 = 670,450 (ERD §1.9 의 실제 POS 화면 값).
            printed_total=PrintedTotal(
                quantity=139,
                gross_amount=690_000,
                discount_amount=19_550,
                billed_amount=670_450,
            ),
            warnings=["3행 금액을 읽지 못했습니다", "STUB 파이프라인 — 고정 응답입니다."],
        )
        return extraction, _META
