"""preferredPipeline → 구현체 선택.

AUTO 의 최종 의미(§6.4)는 "OCR 공급자 → 저신뢰만 VLM 보정"이다. OCR_PIPELINE 공급자
(CLOVA / Azure, 기술스택 §12 미결)가 붙기 전까지 AUTO 는 VLM 으로 간다.
"""

from __future__ import annotations

import asyncio
from dataclasses import dataclass

from app.common.claude import ClaudeClient
from app.common.errors import AppError, ErrorCode
from app.common.local_vlm import LocalVlmClient
from app.config import Settings
from app.ocr.pipelines.base import EvidencePipeline, ProductSalesPipeline
from app.ocr.pipelines.claude_vlm import ClaudeVisionPipeline
from app.ocr.pipelines.local_vlm import LocalVlmPipeline
from app.ocr.pipelines.stub import StubPipeline
from app.ocr.pipelines.tiered import TieredEvidencePipeline
from app.ocr.schemas import PreferredPipeline


@dataclass(frozen=True)
class PipelineSet:
    evidence_auto: EvidencePipeline
    evidence_vlm: EvidencePipeline | None
    evidence_ocr: EvidencePipeline | None
    product_sales: ProductSalesPipeline

    def evidence(self, preferred: PreferredPipeline) -> EvidencePipeline:
        if preferred == "VLM":
            pipeline = self.evidence_vlm
        elif preferred == "OCR_PIPELINE":
            pipeline = self.evidence_ocr
        else:
            pipeline = self.evidence_auto
        if pipeline is None:
            raise AppError(
                ErrorCode.AI_ANALYSIS_FAILED,
                f"{preferred} 파이프라인이 구성되지 않았습니다.",
                status=422,
                detail={"reason": "PIPELINE_UNAVAILABLE", "pipeline": preferred},
            )
        return pipeline


def build_pipelines(
    settings: Settings, claude: ClaudeClient, compute: asyncio.Semaphore | None = None
) -> PipelineSet:
    """`compute` 는 예측 워커와 **함께 쓰는** 세마포어다 (명세 20.1 ④).

    자체 호스팅 경로(로컬 VLM)만 이것을 잡는다 — Claude 경로는 네트워크 대기라 CPU 를
    쓰지 않는다. 안 넘기면 로컬 VLM 이 자기 것을 만들어 스스로는 제한되지만 예측과
    공유되지 않는다. **운영 경로(`main.py`)는 반드시 넘긴다** — 테스트 편의용 기본값이다.
    """
    if settings.ai_pipeline_mode == "stub":
        stub = StubPipeline()
        return PipelineSet(
            evidence_auto=stub, evidence_vlm=stub, evidence_ocr=stub, product_sales=stub
        )

    if settings.vlm_provider == "local":
        # 토큰 0 개발 경로(사용자 PC 의 Ollama). Claude 는 그대로라 품목 정규화 3차는 영향 없다.
        vlm: EvidencePipeline = LocalVlmPipeline(settings, LocalVlmClient(settings), compute)
        sales: ProductSalesPipeline = vlm  # type: ignore[assignment]
    else:
        primary = ClaudeVisionPipeline(settings, claude)
        sales = primary
        vlm = primary
        if settings.vision_escalation_model:
            # 티어링 — 증빙만 붙인다.
            #
            # **근거가 v1.18 로 바뀌었다.** 전에는 "매출 인쇄물에 결정적 검산이 없어 승격
            # 신호가 없다" 였는데, 20.9 에도 검산을 넣어 `needsReview` 라는 같은 신호가
            # 생겼다. 이제 못 붙이는 것이 아니라 **붙일 이득이 없어서 안 붙인다.**
            #
            # 게이트가 2.2%(90표본 중 2건)라 승격 대상이 거의 없고, 그 2건은 이미 정밀도
            # 2/2 로 맞게 잡힌다. 정작 숫자가 틀린 행의 91.2%는 「행 전체가 밀린 것」이라
            # 검산에 걸리지 않으므로 승격되지도 않는다 — 상위 모델을 불러도 그 계열은
            # 신호 자체가 없다(`experiments/analyze_row_shift.py`).
            #
            # 붙일 값어치가 생기는 조건: 매출 인쇄물의 게이트율이 올라가거나, 행 밀림을
            # 잡는 신호가 새로 생길 때. 그때 `TieredProductSalesPipeline` 을 만든다.
            vlm = TieredEvidencePipeline(
                primary,
                ClaudeVisionPipeline(settings, claude, model=settings.vision_escalation_model),
                primary_model=settings.vision_model,
                escalation_model=settings.vision_escalation_model,
            )
    # OCR_PIPELINE 공급자 어댑터는 선정 후 여기서 생성한다 (settings.clova_* / azure_* 참고).
    return PipelineSet(evidence_auto=vlm, evidence_vlm=vlm, evidence_ocr=None, product_sales=sales)
