"""로컬 VLM 경로 — Claude 와 같은 프롬프트·출력 스키마·계약 변환을 쓰되 전송만 Ollama 로.

`pipeline` 메타는 계약상 여전히 `VLM` 이고, `modelName` 에 로컬 모델 태그(예: qwen2.5vl:7b)가 남아
Claude 실측과 섞이지 않는다. 정확도가 낮으므로 개발·검증용이다(최종 수치는 Claude).
"""

from __future__ import annotations

import asyncio
from typing import Any

from app.common.local_vlm import LocalVlmClient, LocalVlmError
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


class LocalVlmPipeline:
    """로컬 Ollama 경로. **자체 호스팅이라 CPU 를 예측 모델과 나눠 쓴다.**

    호출을 `compute` 세마포어 안에서만 돌린다 — 명세 20.1 ④, GPU 없는 4 vCPU 에서 OCR 계열과
    예측 모델을 동시에 돌리지 않는다. 예측은 `forecast/router.py` 가 같은 것을 잡는다.
    **둘이 같은 세마포어를 봐야 의미가 있어 밖에서 받는다** — 여기서 만들면 각자 1개씩 갖고
    결국 둘이 동시에 돈다.

    Claude 경로(`ClaudeVisionPipeline`)는 이 제한을 받지 않는다. 네트워크 대기라 CPU 를 잡지
    않기 때문이다(`config.py` `compute_concurrency` 주석).
    """

    def __init__(
        self,
        settings: Settings,
        client: LocalVlmClient,
        compute: asyncio.Semaphore | None = None,
    ) -> None:
        self._settings = settings
        self._client = client
        # 안 받으면 자기 것을 만든다 — 스스로는 제한되지만 예측과 공유되지 않는다.
        # 운영 경로(`build_pipelines`)는 반드시 넘긴다.
        self._compute = compute or asyncio.Semaphore(1)

    @staticmethod
    def _meta(response: Any, prompt_version: str) -> ModelMeta:
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
        try:
            # 예측 모델과 CPU 를 동시에 잡지 않는다 (명세 20.1 ④)
            async with self._compute:
                response = await self._client.parse(
                    model=self._settings.local_vlm_model,
                    system=prompts.EVIDENCE_SYSTEM,
                    user_text=prompts.evidence_user_text(request.evidence_type_hint, len(images)),
                    images=images,
                    output_format=LlmEvidence,
                    max_tokens=self._settings.evidence_max_tokens,
                )
        except LocalVlmError as exc:
            raise ExtractionError(exc.stage, str(exc)) from exc
        return to_evidence(response.parsed_output), self._meta(
            response, prompts.EVIDENCE_PROMPT_VERSION
        )

    async def extract_product_sales(
        self, images: list[PreparedImage], request: ProductSalesExtractionRequest
    ) -> tuple[ProductSalesExtraction, ModelMeta]:
        if not images:
            raise ExtractionError("load", "이미지가 없습니다.")
        try:
            # 예측 모델과 CPU 를 동시에 잡지 않는다 (명세 20.1 ④)
            async with self._compute:
                response = await self._client.parse(
                    model=self._settings.local_vlm_model,
                    system=prompts.product_sales_system(request.capture_type),
                    user_text=prompts.product_sales_user_text(request.known_products, len(images)),
                    images=images,
                    output_format=LlmProductSales,
                    max_tokens=self._settings.product_sales_max_tokens,
                )
        except LocalVlmError as exc:
            raise ExtractionError(exc.stage, str(exc)) from exc
        return to_product_sales(response.parsed_output), self._meta(
            response, prompts.PRODUCT_SALES_PROMPT_VERSION
        )
