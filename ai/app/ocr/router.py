"""2번 OCR 파트 엔드포인트 — 명세 20.2.

| Method | Endpoint                                  | 처리   |
| POST   | /internal/v1/ai/evidence-extraction       | 비동기 → 콜백 (20.4) |
| POST   | /internal/v1/ai/item-normalization        | 동기, 10초 (20.5)   |
| POST   | /internal/v1/ai/product-sales-extraction  | 비동기 → 콜백 (20.9) |

비동기는 202 + {jobId} 를 먼저 돌려주고, 백그라운드에서 추출 → 검산 → 콜백을 보낸다.
파일이 없거나 파이프라인이 없으면 202 전에 4xx 로 끝낸다 — Job 이 PROCESSING 에 남지 않게.
"""

from __future__ import annotations

import logging
from typing import Any

import anyio
from fastapi import APIRouter, BackgroundTasks, Depends, Request
from starlette.datastructures import State

from app.common import callback as cb
from app.common.auth import require_service_token
from app.common.envelope import Envelope, ok
from app.common.errors import AppError, ErrorCode
from app.common.request_id import require_request_id
from app.ocr.llm_normalization import merge_decisions, resolve_candidates
from app.ocr.normalization import match_items
from app.ocr.pipelines.base import EvidencePipeline, ExtractionError, ProductSalesPipeline
from app.ocr.preprocess import PreparedImage, prepare_evidence_images, prepare_image
from app.ocr.sales_validators import validate_product_sales
from app.ocr.schemas import (
    AcceptedJob,
    EvidenceExtraction,
    EvidenceExtractionRequest,
    EvidenceValidationRequest,
    ItemNormalizationRequest,
    ItemNormalizationResponse,
    ProductSalesExtraction,
    ProductSalesExtractionRequest,
    ValidationReport,
)
from app.ocr.storage import read_object
from app.ocr.validators import validate_extraction

log = logging.getLogger(__name__)


def evidence_result(evidence_id: int, extraction: EvidenceExtraction) -> dict[str, Any]:
    """20.4 콜백의 `data`. **픽스처 스크립트(`scripts/sample_callback.py`)가 같은 함수를 쓴다** —
    따로 조립하면 갈라진다. 실제로 매출 쪽이 그렇게 갈라져 `validation` 이 빠진 픽스처를
    백엔드에 넘겼다(2026-09-10 발견)."""
    return {
        "evidenceId": evidence_id,
        **extraction.model_dump(by_alias=True, mode="json"),
        "validation": validate_extraction(extraction).model_dump(by_alias=True, mode="json"),
    }


def product_sales_result(extraction: ProductSalesExtraction) -> dict[str, Any]:
    """20.9 콜백의 `data` (검산 포함 — 계약 v1.18)."""
    return {
        **extraction.model_dump(by_alias=True, mode="json"),
        "validation": validate_product_sales(extraction).model_dump(by_alias=True, mode="json"),
    }


router = APIRouter(
    prefix="/internal/v1/ai",
    tags=["ocr"],
    dependencies=[Depends(require_service_token), Depends(require_request_id)],
)


def _load_files(state: State, keys: list[str]) -> list[bytes] | None:
    """live 모드에서만 읽는다. stub 은 공유 볼륨 없이도 백엔드 연동을 검증해야 한다."""
    if state.settings.ai_pipeline_mode != "live":
        return None
    return [read_object(state.settings.storage_root, key) for key in keys]


async def _prepare_all(state: State, raws: list[bytes] | None) -> list[PreparedImage]:
    if raws is None:
        return []
    settings = state.settings
    # OpenCV 는 CPU 작업 — 이벤트 루프를 막지 않게 스레드로 보낸다
    return [
        await anyio.to_thread.run_sync(
            lambda raw=raw: prepare_image(
                raw, settings.image_long_edge, crop_margins=settings.image_crop_margins
            )
        )
        for raw in raws
    ]


async def _prepare_evidence(
    state: State, raws: list[bytes] | None, *, thorough: bool = False
) -> list[PreparedImage]:
    """증빙 1장 → 이미지 목록. 긴 영수증 타일링이 켜지면 여러 장.

    타일링은 기본 off 다(전건에 쓰면 비용 +52%). 재추출(`extractionMode=THOROUGH`)에서만
    켠다 — 실측에서 오독 3건 중 2건을 고쳤고 열화 이미지의 품목명 exact 를 76.3 → 83.3% 로
    올렸다. 환경 변수 `EVIDENCE_TILING` 으로 전건에 강제할 수도 있다(실험용).
    """
    if raws is None:
        return []
    settings = state.settings
    return await anyio.to_thread.run_sync(
        lambda: prepare_evidence_images(
            raws[0],
            settings.image_long_edge,
            tile_tall=settings.evidence_tiling or thorough,
            max_aspect=settings.evidence_tile_max_aspect,
            crop_margins=settings.image_crop_margins,
        )
    )


async def _deliver(state: State, job_id: int, body: dict[str, Any]) -> None:
    try:
        await state.callback.send(job_id, body)
    except cb.CallbackError as exc:
        # 여기서 끝난 Job 은 API 서버의 JobSweeper 가 10분 뒤 회수한다 (공통규약 23장)
        log.error("콜백 전송 실패 jobId=%s: %s", job_id, exc)


def _failed(code: ErrorCode | str, message: str, detail: dict[str, Any]) -> dict[str, Any]:
    return cb.failed(str(code), message, detail)


# ------------------------------------------------------------------ 20.4 증빙 추출


@router.post("/evidence-extraction", status_code=202, response_model=Envelope[AcceptedJob])
async def evidence_extraction(
    payload: EvidenceExtractionRequest, request: Request, background: BackgroundTasks
) -> dict[str, Any]:
    state = request.app.state
    pipeline: EvidencePipeline = state.pipelines.evidence(payload.preferred_pipeline)
    raws = _load_files(state, [payload.file_object_key])
    background.add_task(run_evidence_job, state, pipeline, payload, raws)
    return ok(AcceptedJob(job_id=payload.job_id))


async def run_evidence_job(
    state: State,
    pipeline: EvidencePipeline,
    payload: EvidenceExtractionRequest,
    raws: list[bytes] | None,
) -> None:
    detail = {"evidenceId": payload.evidence_id}
    try:
        images = await _prepare_evidence(
            state, raws, thorough=payload.extraction_mode == "THOROUGH"
        )
        extraction, meta = await pipeline.extract_evidence(images, payload)
        body = cb.completed(meta, evidence_result(payload.evidence_id, extraction))
    except ExtractionError as exc:
        log.warning("증빙 추출 실패 jobId=%s stage=%s: %s", payload.job_id, exc.stage, exc)
        body = _failed(
            ErrorCode.AI_ANALYSIS_FAILED,
            "증빙 추출에 실패했습니다.",
            {**detail, "stage": exc.stage},
        )
    except AppError as exc:
        body = _failed(exc.code, exc.message, detail)
    except Exception:
        log.exception("증빙 추출 중 예기치 않은 오류 jobId=%s", payload.job_id)
        body = _failed(ErrorCode.AI_ANALYSIS_FAILED, "증빙 추출에 실패했습니다.", detail)
    await _deliver(state, payload.job_id, body)


# ------------------------------------- 20.4 검산만 다시 (동기, 모델 호출 없음 — 확장 v1.17)


@router.post("/evidence-validation", response_model=Envelope[ValidationReport])
async def evidence_validation(payload: EvidenceValidationRequest) -> dict[str, Any]:
    """§6.4 검산을 다시 돌린다. 모델을 부르지 않으므로 비용 0·즉시 응답이다.

    사용자가 검토 화면에서 금액을 고친 직후 "이제 검산이 맞습니다" 를 보여주려면 규칙을 다시
    돌려야 한다. 그 규칙(과세+부가세+면세=합계, 부가세=과세×10%, Σ품목, 품목 행 정렬,
    사업자번호 체크섬)을 화면이나 백엔드가 복제하면 워커와 어긋난다 — 같은 식이 두 곳에
    살면 반드시 드리프트한다. 그래서 규칙은 여기 한 곳에 두고 결과만 돌려준다.
    """
    report = validate_extraction(payload.extraction, low_confidence=payload.low_confidence)
    return ok(report)


# ------------------------------------------------------------------ 20.5 품목 정규화 (동기)


@router.post("/item-normalization", response_model=Envelope[ItemNormalizationResponse])
async def item_normalization(payload: ItemNormalizationRequest, request: Request) -> dict[str, Any]:
    state = request.app.state
    mappings, llm_candidates = match_items(payload.raw_names, payload.known_items)
    if llm_candidates and state.settings.ai_pipeline_mode == "live":
        # 3차: 애매한 건만 묶어서 한 번. 실패·시간 초과면 미매칭 그대로 → 사용자 확인
        decisions = await resolve_candidates(
            state.claude, state.settings, llm_candidates, payload.known_items
        )
        mappings = merge_decisions(mappings, decisions)
        log.info("품목 정규화 3차 %d건 중 %d건 판정", len(llm_candidates), len(decisions))
    elif llm_candidates:
        log.info("품목 정규화 LLM 후보 %d건 — stub 모드라 미매칭으로 반환", len(llm_candidates))
    return ok(ItemNormalizationResponse(mappings=mappings))


# ------------------------------------------------------------------ 20.9 매출현황 인쇄물


@router.post("/product-sales-extraction", status_code=202, response_model=Envelope[AcceptedJob])
async def product_sales_extraction(
    payload: ProductSalesExtractionRequest, request: Request, background: BackgroundTasks
) -> dict[str, Any]:
    state = request.app.state
    pipeline: ProductSalesPipeline = state.pipelines.product_sales
    raws = _load_files(state, payload.file_object_keys)
    background.add_task(run_product_sales_job, state, pipeline, payload, raws)
    return ok(AcceptedJob(job_id=payload.job_id))


async def run_product_sales_job(
    state: State,
    pipeline: ProductSalesPipeline,
    payload: ProductSalesExtractionRequest,
    raws: list[bytes] | None,
) -> None:
    detail = {"businessId": payload.business_id, "captureType": payload.capture_type}
    try:
        images = await _prepare_all(state, raws)
        extraction, meta = await pipeline.extract_product_sales(images, payload)
        body = cb.completed(meta, product_sales_result(extraction))
    except ExtractionError as exc:
        log.warning("매출 인쇄물 추출 실패 jobId=%s stage=%s: %s", payload.job_id, exc.stage, exc)
        body = _failed(
            ErrorCode.AI_ANALYSIS_FAILED,
            "매출 인쇄물 추출에 실패했습니다.",
            {**detail, "stage": exc.stage},
        )
    except AppError as exc:
        body = _failed(exc.code, exc.message, detail)
    except Exception:
        log.exception("매출 인쇄물 추출 중 예기치 않은 오류 jobId=%s", payload.job_id)
        body = _failed(ErrorCode.AI_ANALYSIS_FAILED, "매출 인쇄물 추출에 실패했습니다.", detail)
    await _deliver(state, payload.job_id, body)
