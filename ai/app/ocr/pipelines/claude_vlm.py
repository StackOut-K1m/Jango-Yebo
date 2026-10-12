"""Claude vision 경로 (`VLM`) — 기술스택 §6.4 저신뢰 보정 · §6.8 매출 인쇄물.

호출 규약(구조화 출력·effort·폴백·stop_reason·usage 로그)은 `app.common.claude.ClaudeClient` 가
맡는다. 이 파일은 이미지 → Anthropic 메시지 → VLM 출력 스키마 호출까지만 하고, 계약 스키마로의
변환은 `vlm_output`(로컬 VLM 경로와 공유)이 맡는다.
"""

from __future__ import annotations

import base64
import logging
from typing import Any

from pydantic import BaseModel

from app.common.claude import ClaudeClient, ClaudeError
from app.common.model_meta import ModelMeta
from app.config import Settings
from app.ocr import prompts
from app.ocr.pipelines.base import ExtractionError
from app.ocr.pipelines.vlm_output import (
    LlmEvidence,
    LlmProductSales,
    to_evidence,
    to_product_sales,
)
from app.ocr.preprocess import PreparedImage
from app.ocr.schemas import (
    EvidenceExtraction,
    EvidenceExtractionRequest,
    ProductSalesExtraction,
    ProductSalesExtractionRequest,
)

log = logging.getLogger(__name__)


def _image_block(image: PreparedImage) -> dict[str, Any]:
    return {
        "type": "image",
        "source": {
            "type": "base64",
            "media_type": image.media_type,
            "data": base64.standard_b64encode(image.data).decode("ascii"),
        },
    }


class ClaudeVisionPipeline:
    def __init__(
        self, settings: Settings, claude: ClaudeClient, *, model: str | None = None
    ) -> None:
        self._settings = settings
        self._claude = claude
        # 티어링에서 같은 클라이언트로 모델만 바꿔 두 단계를 만든다. 없으면 설정값.
        self._model = model or settings.vision_model

    async def _parse(
        self,
        *,
        system: str,
        content: list[dict[str, Any]],
        output_format: type[BaseModel],
        max_tokens: int,
        effort: str,
    ) -> Any:
        try:
            return await self._claude.parse(
                model=self._model,
                system=system,
                messages=[{"role": "user", "content": content}],
                output_format=output_format,
                max_tokens=max_tokens,
                effort=effort,  # type: ignore[arg-type]
            )
        except ClaudeError as exc:
            raise ExtractionError(exc.stage, str(exc)) from exc

    @staticmethod
    def _meta(response: Any, prompt_version: str) -> ModelMeta:
        # response.model 은 실제로 응답한 모델 — 폴백이 일어났으면 폴백 모델이 기록된다
        return ModelMeta(
            model_name=response.model,
            model_version=None,
            prompt_version=prompt_version,
            pipeline="VLM",
        )

    async def extract_evidence(
        self, images: list[PreparedImage], request: EvidenceExtractionRequest
    ) -> tuple[EvidenceExtraction, ModelMeta]:
        if not images:
            raise ExtractionError("load", "이미지가 없습니다.")
        # 긴 영수증을 타일링했으면 위에서부터 순서대로 여러 장 — 한 장의 증빙으로 읽게 한다
        content: list[dict[str, Any]] = [_image_block(image) for image in images]
        content.append(
            {
                "type": "text",
                "text": prompts.evidence_user_text(request.evidence_type_hint, len(images)),
            }
        )
        response = await self._parse(
            system=prompts.EVIDENCE_SYSTEM,
            content=content,
            output_format=LlmEvidence,
            max_tokens=self._settings.evidence_max_tokens,
            effort=self._settings.evidence_effort,
        )
        extraction = to_evidence(response.parsed_output)
        return extraction, self._meta(response, prompts.EVIDENCE_PROMPT_VERSION)

    async def extract_product_sales(
        self, images: list[PreparedImage], request: ProductSalesExtractionRequest
    ) -> tuple[ProductSalesExtraction, ModelMeta]:
        if not images:
            raise ExtractionError("load", "이미지가 없습니다.")
        # 여러 장을 한 요청에 실어 하나의 기간으로 병합·중복 제거시킨다 (명세 20.9)
        content: list[dict[str, Any]] = [_image_block(image) for image in images]
        content.append(
            {
                "type": "text",
                "text": prompts.product_sales_user_text(request.known_products, len(images)),
            }
        )
        response = await self._parse(
            system=prompts.product_sales_system(request.capture_type),
            content=content,
            output_format=LlmProductSales,
            max_tokens=self._settings.product_sales_max_tokens,
            effort=self._settings.product_sales_effort,
        )
        extraction = to_product_sales(response.parsed_output)
        return extraction, self._meta(response, prompts.PRODUCT_SALES_PROMPT_VERSION)
