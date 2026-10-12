"""모델 티어링 — 싼 모델로 먼저 뽑고, 검산이 걸린 건만 강한 모델로 다시 뽑는다.

실 서비스 비용의 가장 큰 지렛대다. 증빙 추출은 §6.4 검산(과세+부가세+면세=합계, 품목합, 사업자번호
체크섬, 날짜, 필수 필드)이 **결정적**이라 "1차 결과를 믿어도 되는가"를 모델에게 묻지 않고 계산으로
판정할 수 있다. 그래서 승격 신호가 이미 있다 — `ValidationReport.needsReview`.

실측(KORIE val 열화 세트 50장, 2026-09-08)에서 needsReview 는 12% 였다. 나머지 88% 를 싼 모델로
끝내면 Opus 단독 대비 Sonnet 기준 약 45%, Haiku 기준 약 75% 가 빠진다. 품질은 검산이 지키므로
싼 모델이 틀리면 자동으로 강한 모델이 다시 읽는다.

매출 인쇄물(20.9)에는 적용하지 않는다 — 거기엔 아직 결정적 검산이 없어 승격 신호가 없다.
"""

from __future__ import annotations

import logging

from app.common.model_meta import ModelMeta
from app.ocr.pipelines.base import EvidencePipeline, ExtractionError
from app.ocr.preprocess import PreparedImage
from app.ocr.schemas import EvidenceExtraction, EvidenceExtractionRequest
from app.ocr.validators import validate_extraction

log = logging.getLogger(__name__)


class TieredEvidencePipeline:
    """1차(싼 모델) → needsReview 면 2차(강한 모델). 2차가 실패하면 1차 결과를 그대로 쓴다."""

    def __init__(
        self,
        primary: EvidencePipeline,
        escalation: EvidencePipeline,
        *,
        primary_model: str,
        escalation_model: str,
    ) -> None:
        self._primary = primary
        self._escalation = escalation
        self._primary_model = primary_model
        self._escalation_model = escalation_model

    async def extract_evidence(
        self, images: list[PreparedImage], request: EvidenceExtractionRequest
    ) -> tuple[EvidenceExtraction, ModelMeta]:
        if request.extraction_mode == "THOROUGH":
            # 재추출 요청이다 — 1차 결과가 이미 틀렸다고 사용자가 말했으므로 싼 모델을 건너뛴다.
            # 실패는 감추지 않는다: 1차로 폴백하면 사용자가 방금 틀렸다고 한 값을 다시 준다.
            log.info(
                "재추출(THOROUGH) evidenceId=%s → %s",
                request.evidence_id,
                self._escalation_model,
            )
            return await self._escalation.extract_evidence(images, request)

        extraction, meta = await self._primary.extract_evidence(images, request)
        report = validate_extraction(extraction)
        if not report.needs_review:
            return extraction, meta

        reasons = ", ".join(report.review_reasons)
        log.info(
            "티어링 승격 evidenceId=%s %s → %s (%s)",
            request.evidence_id,
            self._primary_model,
            self._escalation_model,
            reasons,
        )
        try:
            escalated, escalated_meta = await self._escalation.extract_evidence(images, request)
        except ExtractionError as exc:
            # 승격이 실패해도 1차 결과는 살린다. 어차피 needsReview 로 사람이 본다.
            log.warning("티어링 승격 실패, 1차 결과 사용: %s", exc)
            extraction.warnings.append(
                f"{self._primary_model} 결과가 검산에 걸렸으나 재추출에 실패해 1차 결과를 씁니다."
            )
            return extraction, meta

        # 둘 다 검산에 걸리면 강한 모델 쪽을 쓴다 — 인쇄 자체가 검산과 안 맞는 영수증도 있다.
        escalated.warnings.append(
            f"{self._primary_model} 결과가 검산에 걸려({reasons}) "
            f"{self._escalation_model} 로 재추출했습니다."
        )
        return escalated, escalated_meta
