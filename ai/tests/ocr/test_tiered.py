"""모델 티어링 — 1차 결과가 검산을 통과하면 승격하지 않고, 걸리면 강한 모델로 재추출한다."""

import asyncio
from datetime import date

import pytest

from app.common.model_meta import ModelMeta
from app.ocr.pipelines.base import ExtractionError
from app.ocr.pipelines.tiered import TieredEvidencePipeline
from app.ocr.schemas import EvidenceExtraction, EvidenceExtractionRequest, ExtractedItem
from app.ocr.validators import validate_extraction

REQUEST = EvidenceExtractionRequest(
    job_id=1, evidence_id=501, file_object_key="r.jpg", evidence_type_hint="RECEIPT"
)


def _clean() -> EvidenceExtraction:
    """검산을 모두 통과하는 결과 (홈플러스 IMG00020 실측 구조)."""
    return EvidenceExtraction(
        supplier_name="홈플러스 청주성안점",
        issued_date=date(2026, 8, 30),
        taxable_amount=31_236,
        vat_amount=3_124,
        tax_free_amount=9_990,
        discount_amount=44_350,
        total_amount=44_350,
        items=[
            ExtractedItem(raw_name="토마토스파게티소스", amount=8_780, discount_amount=4_390),
            ExtractedItem(raw_name="흰강낭콩", amount=19_980, discount_amount=9_990),
            ExtractedItem(raw_name="직수입구운캐슈너트", amount=59_940, discount_amount=29_970),
        ],
        field_confidence={"supplierName": 0.95},
    )


def _dirty() -> EvidenceExtraction:
    """검산에 걸리는 결과 — 합계를 못 읽었다."""
    return EvidenceExtraction(
        supplier_name="홈플러스 청주성안점",
        issued_date=date(2026, 8, 30),
        total_amount=None,
        field_confidence={"supplierName": 0.95},
    )


class _Stage:
    def __init__(self, extraction: EvidenceExtraction | None, model: str) -> None:
        self._extraction = extraction
        self.model = model
        self.calls = 0

    async def extract_evidence(self, images, request):
        self.calls += 1
        if self._extraction is None:
            raise ExtractionError("model", f"{self.model} 실패")
        return self._extraction.model_copy(deep=True), ModelMeta(
            model_name=self.model, model_version=None, prompt_version="p", pipeline="VLM"
        )


def _tiered(primary, escalation) -> TieredEvidencePipeline:
    return TieredEvidencePipeline(
        primary, escalation, primary_model="cheap", escalation_model="strong"
    )


def test_clean_primary_result_does_not_escalate():
    primary, escalation = _Stage(_clean(), "cheap"), _Stage(_clean(), "strong")
    extraction, meta = asyncio.run(_tiered(primary, escalation).extract_evidence([], REQUEST))
    assert (primary.calls, escalation.calls) == (1, 0)  # 88% 가 여기서 끝난다
    assert meta.model_name == "cheap"
    assert validate_extraction(extraction).needs_review is False
    assert extraction.warnings == []


def test_failed_validation_escalates_and_records_why():
    primary, escalation = _Stage(_dirty(), "cheap"), _Stage(_clean(), "strong")
    extraction, meta = asyncio.run(_tiered(primary, escalation).extract_evidence([], REQUEST))
    assert (primary.calls, escalation.calls) == (1, 1)
    assert meta.model_name == "strong"  # 결과 메타는 실제로 답한 모델
    assert extraction.total_amount == 44_350
    assert any("cheap" in w and "strong" in w for w in extraction.warnings)
    assert any("TOTAL_MISSING" in w for w in extraction.warnings)


def test_escalation_failure_keeps_the_primary_result():
    primary, escalation = _Stage(_dirty(), "cheap"), _Stage(None, "strong")
    extraction, meta = asyncio.run(_tiered(primary, escalation).extract_evidence([], REQUEST))
    assert (primary.calls, escalation.calls) == (1, 1)
    assert meta.model_name == "cheap"  # 승격이 실패해도 1차 결과는 버리지 않는다
    assert any("재추출에 실패" in w for w in extraction.warnings)


def test_primary_failure_propagates():
    primary, escalation = _Stage(None, "cheap"), _Stage(_clean(), "strong")
    with pytest.raises(ExtractionError):
        asyncio.run(_tiered(primary, escalation).extract_evidence([], REQUEST))
    assert escalation.calls == 0


def test_selector_builds_tiering_only_when_escalation_model_is_set():
    from pydantic import SecretStr

    from app.common.claude import ClaudeClient
    from app.config import Settings
    from app.ocr.pipelines.claude_vlm import ClaudeVisionPipeline
    from app.ocr.pipelines.select import build_pipelines

    def settings(**over):
        return Settings(
            _env_file=None,
            app_env="local",
            ai_pipeline_mode="live",
            anthropic_api_key=SecretStr("k"),
            **over,
        )

    plain = settings()
    assert isinstance(
        build_pipelines(plain, ClaudeClient(plain)).evidence_vlm, ClaudeVisionPipeline
    )

    tiered = settings(
        vision_model="claude-haiku-4-5-20251001", vision_escalation_model="claude-opus-4-8"
    )
    pipes = build_pipelines(tiered, ClaudeClient(tiered))
    assert isinstance(pipes.evidence_vlm, TieredEvidencePipeline)
    # 매출 인쇄물에는 결정적 검산이 없어 티어링을 걸지 않는다
    assert isinstance(pipes.product_sales, ClaudeVisionPipeline)


def test_evaluator_cost_sums_per_model():
    from app.common.claude import UsageTotals
    from experiments.evaluate_evidence import total_cost_usd

    class _Client:
        def __init__(self):
            self.usage_by_model = {
                "claude-haiku-4-5-20251001": UsageTotals(
                    calls=88, input_tokens=1_000_000, output_tokens=0
                ),
                "claude-opus-4-8": UsageTotals(calls=12, input_tokens=1_000_000, output_tokens=0),
            }

    # Haiku 입력 $1/Mtok + Opus 입력 $5/Mtok = $6
    assert total_cost_usd(_Client()) == 6.0
    assert total_cost_usd(object()) is None


def test_thorough_skips_the_cheap_model_entirely():
    """재추출 요청은 1차를 건너뛴다 — 사용자가 이미 그 결과가 틀렸다고 했다."""
    primary, escalation = _Stage(_clean(), "cheap"), _Stage(_clean(), "strong")
    request = REQUEST.model_copy(update={"extraction_mode": "THOROUGH"})
    _, meta = asyncio.run(_tiered(primary, escalation).extract_evidence([], request))
    assert (primary.calls, escalation.calls) == (0, 1)
    assert meta.model_name == "strong"


def test_thorough_failure_propagates_instead_of_falling_back():
    """1차로 폴백하면 사용자가 방금 틀렸다고 한 값을 다시 주게 된다 — 감추지 않고 실패한다."""
    primary, escalation = _Stage(_clean(), "cheap"), _Stage(None, "strong")
    request = REQUEST.model_copy(update={"extraction_mode": "THOROUGH"})
    with pytest.raises(ExtractionError):
        asyncio.run(_tiered(primary, escalation).extract_evidence([], request))
    assert primary.calls == 0
