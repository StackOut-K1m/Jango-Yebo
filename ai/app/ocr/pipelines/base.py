"""파이프라인 인터페이스.

명세 20.4 `preferredPipeline` = AUTO / OCR_PIPELINE / VLM. 같은 표본에 세 경로를 돌려
비교(도입 순서 9단계)할 수 있게 구현체를 바꿔 끼운다. 결과에는 항상 ModelMeta 가 붙는다.
"""

from __future__ import annotations

from typing import Protocol

from app.common.model_meta import ModelMeta
from app.ocr.preprocess import PreparedImage
from app.ocr.schemas import (
    EvidenceExtraction,
    EvidenceExtractionRequest,
    ProductSalesExtraction,
    ProductSalesExtractionRequest,
)


class ExtractionError(Exception):
    """모델·공급자 단계의 실패. 콜백에 AI_ANALYSIS_FAILED 로 실린다."""

    def __init__(self, stage: str, message: str) -> None:
        super().__init__(message)
        self.stage = stage


class EvidencePipeline(Protocol):
    async def extract_evidence(
        self,
        # 증빙 1장. 긴 영수증을 타일링하면 위에서부터 여러 장. stub 모드에서는 빈 리스트
        images: list[PreparedImage],
        request: EvidenceExtractionRequest,
    ) -> tuple[EvidenceExtraction, ModelMeta]: ...


class ProductSalesPipeline(Protocol):
    async def extract_product_sales(
        self,
        images: list[PreparedImage],  # stub 모드에서는 빈 리스트
        request: ProductSalesExtractionRequest,
    ) -> tuple[ProductSalesExtraction, ModelMeta]: ...
