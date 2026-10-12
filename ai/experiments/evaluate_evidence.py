"""증빙 추출 평가 — 라벨 디렉터리를 파이프라인에 돌려 예측을 저장하고 채점한다.

    uv run python -X utf8 -m experiments.evaluate_evidence datasets/receipts/korie/val \\
        --out artifacts/eval/val-001 --pipeline vlm
    uv run python -X utf8 -m experiments.evaluate_evidence datasets/receipts/korie/val \\
        --out artifacts/eval/val-001 --rescore            # API 재호출 없이 다시 채점
    uv run python -X utf8 -m experiments.evaluate_evidence datasets/receipts/korie/val \\
        --out artifacts/eval/smoke --pipeline stub        # 키 없이 흐름만 확인

- 예측은 `<out>/predictions/<id>.json` 에 저장된다. 이미 있으면 건너뛰므로(재개) 중단돼도 비용이
  이중으로 들지 않는다. `--force` 로 다시 호출한다.
- 산출물: `metrics.json`(정본), `summary.md`(요약표), `run.json`(모델·프롬프트 버전·effort·토큰·
  비용 추정·데이터 manifest), `failures.jsonl`(틀린 케이스 — **val/train 만**).
- 디렉터리 이름에 `test` 가 들어가면 경고를 찍고 failures.jsonl 을 쓰지 않는다. test 의 실패
  케이스를 들여다보는 순간 그 수치는 최종 수치가 아니다.
- 비용 추정은 기술스택 §5.1 단가 스냅샷(2026-06-24)을 쓴다. 도입 시점에 재확인한다.
- 라벨 폴더의 `overrides.json`(사람이 이미지로 확인한 라벨 교정)은 기본으로 덧씌운다.
  `--no-overrides` 로 원천 라벨 그대로 채점하면 두 결과의 차이가 곧 라벨 오류율이다.
"""

from __future__ import annotations

import argparse
import asyncio
import json
import time
from collections.abc import Callable
from pathlib import Path
from typing import Any

from app.common.claude import FATAL_STAGES, ClaudeClient
from app.common.envelope import CamelModel
from app.common.local_vlm import LocalVlmClient
from app.common.model_meta import ModelMeta
from app.config import Settings
from app.ocr.pipelines.base import EvidencePipeline, ExtractionError
from app.ocr.pipelines.claude_vlm import ClaudeVisionPipeline
from app.ocr.pipelines.local_vlm import LocalVlmPipeline
from app.ocr.pipelines.stub import StubPipeline
from app.ocr.pipelines.tiered import TieredEvidencePipeline
from app.ocr.preprocess import prepare_evidence_images
from app.ocr.schemas import EvidenceExtraction, EvidenceExtractionRequest
from experiments import metrics as m
from experiments.labels import LabelRecord, iter_labels, read_manifest

# 단가 스냅샷 2026-06-24 (USD / 1M 토큰, 입력·출력). 캐시 읽기는 입력 단가로 상한 추정한다.
# 기술스택 §5.1 의 3종 + SSAFY GMS 가 열어 둔 모델(Opus 4.x·Sonnet 4.x). 앞부분 일치로 찾는다.
PRICE_PER_MTOK = {
    "claude-opus-5": (5.0, 25.0),
    "claude-sonnet-5": (2.0, 10.0),
    "claude-haiku-4-5": (1.0, 5.0),
    "claude-opus-4-8": (5.0, 25.0),
    "claude-opus-4-7": (5.0, 25.0),
    "claude-opus-4-6": (5.0, 25.0),
    "claude-sonnet-4-6": (3.0, 15.0),
    "claude-sonnet-4-5": (3.0, 15.0),
}


class PredictionRecord(CamelModel):
    source_id: str
    image: str
    extraction: EvidenceExtraction | None = None
    model: ModelMeta | None = None
    error: str | None = None
    error_stage: str | None = None
    tiles: int = 1  # 모델에 보낸 이미지 수 (긴 영수증 타일링)
    elapsed_ms: int = 0


def build_pipeline(kind: str, settings: Settings) -> tuple[EvidencePipeline, Any]:
    """(파이프라인, usage 를 가진 클라이언트|None). local 은 토큰 0 개발 경로(Ollama).

    `VISION_ESCALATION_MODEL` 이 있으면 vlm 경로가 티어링(1차 싼 모델 → 검산 실패만 승격)이 된다.
    """
    if kind == "stub":
        return StubPipeline(), None
    if kind == "local":
        client = LocalVlmClient(settings)
        return LocalVlmPipeline(settings, client), client
    claude = ClaudeClient(settings)
    primary = ClaudeVisionPipeline(settings, claude)
    if settings.vision_escalation_model:
        tiered = TieredEvidencePipeline(
            primary,
            ClaudeVisionPipeline(settings, claude, model=settings.vision_escalation_model),
            primary_model=settings.vision_model,
            escalation_model=settings.vision_escalation_model,
        )
        return tiered, claude
    return primary, claude


def _pipeline_model(kind: str, settings: Settings) -> str:
    return settings.local_vlm_model if kind == "local" else settings.vision_model


async def predict_one(
    pipeline: EvidencePipeline,
    settings: Settings,
    label_path: Path,
    label: LabelRecord,
    *,
    tiling: bool = False,
) -> PredictionRecord:
    image_path = label_path.with_name(label.image)
    started = time.perf_counter()
    try:
        prepared = prepare_evidence_images(
            image_path.read_bytes(),
            settings.image_long_edge,
            tile_tall=tiling,
            max_aspect=settings.evidence_tile_max_aspect,
            crop_margins=settings.image_crop_margins,
        )
        request = EvidenceExtractionRequest(
            job_id=0,
            evidence_id=0,
            file_object_key=label.image,
            evidence_type_hint=label.evidence_type,
        )
        extraction, meta = await pipeline.extract_evidence(prepared, request)
        return PredictionRecord(
            source_id=label.meta.source_id,
            image=label.image,
            extraction=extraction,
            model=meta,
            tiles=len(prepared),
            elapsed_ms=int((time.perf_counter() - started) * 1000),
        )
    except ExtractionError as exc:
        return PredictionRecord(
            source_id=label.meta.source_id,
            image=label.image,
            error=str(exc),
            error_stage=exc.stage,
            elapsed_ms=int((time.perf_counter() - started) * 1000),
        )
    except Exception as exc:  # 평가는 한 장 때문에 멈추지 않는다 — 실패로 기록하고 계속
        return PredictionRecord(
            source_id=label.meta.source_id,
            image=label.image,
            error=f"{type(exc).__name__}: {exc}",
            error_stage="unexpected",
            elapsed_ms=int((time.perf_counter() - started) * 1000),
        )


async def run_predictions(
    dataset: Path,
    out: Path,
    pipeline: EvidencePipeline,
    settings: Settings,
    *,
    limit: int | None,
    force: bool,
    concurrency: int,
    apply_overrides: bool = True,
    tiling: bool = False,
    stride: int = 1,
    budget_usd: float | None = None,
    cost_so_far: Callable[[], float | None] | None = None,
) -> list[tuple[LabelRecord, PredictionRecord]]:
    """예측을 만들거나 캐시에서 읽는다.

    `budget_usd` 는 실행당 비용 상한(추정 USD)이다 — 한 장이 끝날 때마다 `cost_so_far()` 를 보고
    상한에 닿으면 남은 장을 호출하지 않는다. GMS 쿼터는 사용자 개인 자원이라 기본값을 낮게 둔다.
    """
    predictions_dir = out / "predictions"
    predictions_dir.mkdir(parents=True, exist_ok=True)
    # stride 를 먼저 — 정렬이 점포별로 뭉쳐 있어 앞에서 N 장만 자르면 한쪽만 잡힌다
    every = max(1, stride)
    labels = list(iter_labels(dataset, apply_overrides=apply_overrides))[::every][:limit]
    semaphore = asyncio.Semaphore(max(1, concurrency))
    results: list[tuple[LabelRecord, PredictionRecord]] = []
    aborted = asyncio.Event()  # 키·쿼터 문제는 다음 호출도 실패한다 — 남은 장을 태우지 않는다

    async def worker(label_path: Path, label: LabelRecord) -> None:
        target = predictions_dir / f"{label.meta.source_id}.json"
        if target.exists() and not force:
            record = PredictionRecord.model_validate_json(target.read_text(encoding="utf-8"))
            results.append((label, record))
            return
        async with semaphore:
            if aborted.is_set():
                return
            record = await predict_one(pipeline, settings, label_path, label, tiling=tiling)
        if record.error_stage in FATAL_STAGES:
            if not aborted.is_set():
                print(f"[중단] {record.error} — 남은 영수증은 호출하지 않는다 (캐시에 남기지 않음)")
            aborted.set()
            return  # 실패 기록을 캐시하면 다음 실행이 그 장을 건너뛰어 버린다
        if record.extraction is not None:
            # 성공만 캐시한다. 실패를 남기면 원인을 고친 뒤 재실행해도 그 장을 건너뛴다.
            target.write_text(
                json.dumps(
                    record.model_dump(by_alias=True, mode="json"), ensure_ascii=False, indent=2
                ),
                encoding="utf-8",
            )
        results.append((label, record))
        spent = cost_so_far() if cost_so_far else None
        if budget_usd is not None and spent is not None and spent >= budget_usd:
            if not aborted.is_set():
                reached = f"[중단] 예산 ${budget_usd:.2f} 도달 (추정 ${spent:.2f})"
                print(reached + ". 남은 장은 안 부른다")
            aborted.set()

    await asyncio.gather(*(worker(p, lb) for p, lb in labels))
    if aborted.is_set():
        print(f"[중단] 예측 {len(results)}/{len(labels)}장만 있음. 같은 명령으로 재개")
    results.sort(key=lambda pair: pair[0].meta.source_id)
    return results


def load_predictions(
    dataset: Path, out: Path, *, apply_overrides: bool = True
) -> list[tuple[LabelRecord, PredictionRecord]]:
    pairs: list[tuple[LabelRecord, PredictionRecord]] = []
    for _, label in iter_labels(dataset, apply_overrides=apply_overrides):
        path = out / "predictions" / f"{label.meta.source_id}.json"
        if path.exists():
            pairs.append(
                (label, PredictionRecord.model_validate_json(path.read_text(encoding="utf-8")))
            )
    return pairs


def score_all(
    pairs: list[tuple[LabelRecord, PredictionRecord]], *, confidence_threshold: float
) -> tuple[dict[str, Any], list[m.ReceiptScore]]:
    scores = [
        m.score_receipt(label, pred.extraction, confidence_threshold=confidence_threshold)
        for label, pred in pairs
    ]
    return m.aggregate(scores), scores


def _is_test_split(dataset: Path) -> bool:
    return any(part.lower() == "test" for part in dataset.parts)


def write_failures(
    out: Path, pairs: list[tuple[LabelRecord, PredictionRecord]], scores: list[m.ReceiptScore]
) -> int:
    """틀린 필드·누락·환각 행이 있는 영수증만 한 줄씩. 프롬프트를 고칠 때 보는 파일."""
    count = 0
    with (out / "failures.jsonl").open("w", encoding="utf-8") as handle:
        for (label, pred), score in zip(pairs, scores, strict=True):
            wrong = {k: v.correct for k, v in score.fields.items() if v.correct is False}
            missed = [i.raw_name for i in score.items.missed] if score.items else []
            # 부분 라벨(itemsComplete=false)에서는 라벨에 없는 행이 환각이 아니다 — 실패로 안 적는다
            halluc = (
                [i.raw_name for i in score.items.hallucinated]
                if score.items and score.items_complete
                else []
            )
            if not (score.failed or wrong or missed or halluc):
                continue
            count += 1
            row = {
                "sourceId": label.meta.source_id,
                "image": label.image,
                "error": pred.error,
                "wrongFields": {
                    k: {
                        "label": _json_value(getattr(label.extraction, m._snake(k))),
                        "pred": _json_value(getattr(pred.extraction, m._snake(k)))
                        if pred.extraction
                        else None,
                    }
                    for k in wrong
                },
                "missedItems": missed,
                "hallucinatedItems": halluc,
                "validationIssues": score.validation_issues,
            }
            handle.write(json.dumps(row, ensure_ascii=False) + "\n")
    return count


def _json_value(value: Any) -> Any:
    return value.isoformat() if hasattr(value, "isoformat") else value


def total_cost_usd(client: Any) -> float | None:
    """모델별 누계로 비용을 합산한다. 티어링은 단가가 다른 두 모델을 쓰므로 합계만으로는 못 낸다."""
    by_model = getattr(client, "usage_by_model", None)
    if not by_model:
        return None
    known = [
        cost
        for model, totals in by_model.items()
        if (cost := estimate_cost_usd(model, totals.snapshot())) is not None
    ]
    return round(sum(known), 4) if known else None


def estimate_cost_usd(model_name: str | None, usage: dict[str, int]) -> float | None:
    if not model_name:
        return None
    price = next((p for key, p in PRICE_PER_MTOK.items() if model_name.startswith(key)), None)
    if price is None:
        return None
    # 캐시 읽기 = 입력 단가의 10%, 캐시 쓰기(5분) = 125%. input_tokens 에는 캐시분이 빠져 있다
    cost = (
        usage["input_tokens"] * price[0]
        + usage.get("cache_read_input_tokens", 0) * price[0] * 0.1
        + usage.get("cache_creation_input_tokens", 0) * price[0] * 1.25
        + usage["output_tokens"] * price[1]
    )
    return round(cost / 1_000_000, 4)


def run(args: argparse.Namespace) -> int:
    settings = Settings()
    dataset: Path = args.dataset
    out: Path = args.out
    out.mkdir(parents=True, exist_ok=True)
    is_test = _is_test_split(dataset)
    if is_test:
        print("[주의] test 분할 — 설정을 freeze 한 뒤에만 돌린다. failures.jsonl 은 쓰지 않는다.")

    client: Any = None  # ClaudeClient | LocalVlmClient | None — usage 만 쓴다
    previous_usage: dict[str, int] | None = None
    apply_overrides = not getattr(args, "no_overrides", False)
    if args.rescore:
        pairs = load_predictions(dataset, out, apply_overrides=apply_overrides)
        if not pairs:
            print("[실패] 저장된 예측이 없습니다. --rescore 없이 먼저 실행하세요.")
            return 2
        # 재채점은 API 를 부르지 않으므로 토큰 사용량은 직전 실행의 run.json 에서 이어받는다
        previous_run = out / "run.json"
        if previous_run.is_file():
            previous_usage = json.loads(previous_run.read_text(encoding="utf-8")).get("usage")
    else:
        pipeline, client = build_pipeline(args.pipeline, settings)
        budget = getattr(args, "budget_usd", None)
        if budget is not None:
            print(f"실행 예산 상한 ${budget:.2f} (추정치). 넘으면 멈춘다 — --budget-usd 로 조정")
        pairs = asyncio.run(
            run_predictions(
                dataset,
                out,
                pipeline,
                settings,
                limit=args.limit,
                force=args.force,
                concurrency=args.concurrency,
                apply_overrides=apply_overrides,
                tiling=bool(getattr(args, "tiling", False) or settings.evidence_tiling),
                stride=getattr(args, "stride", 1),
                budget_usd=budget,
                # local 모델은 단가표에 없어 None 이 나오고, 그러면 예산은 걸리지 않는다(무료)
                cost_so_far=((lambda: total_cost_usd(client)) if client is not None else None),
            )
        )
    if not pairs:
        print("[실패] 라벨이 없습니다.")
        return 2

    metrics, scores = score_all(pairs, confidence_threshold=args.confidence_threshold)
    (out / "metrics.json").write_text(
        json.dumps(metrics, ensure_ascii=False, indent=2) + "\n", encoding="utf-8"
    )
    summary = m.format_markdown(metrics)
    (out / "summary.md").write_text(summary + "\n", encoding="utf-8")

    models = {p.model.model_name for _, p in pairs if p.model}
    prompt_versions = {p.model.prompt_version for _, p in pairs if p.model}
    usage = client.usage.snapshot() if client else previous_usage
    run_info = {
        "dataset": str(dataset),
        "datasetManifest": read_manifest(dataset),
        "isTestSplit": is_test,
        "pipeline": args.pipeline if not args.rescore else "rescore",
        "scoringRules": m.SCORING_RULES_VERSION,
        "labelOverridesApplied": apply_overrides,
        "labelOverrides": metrics["labelOverrides"],
        "modelNames": sorted(x for x in models if x),
        "promptVersions": sorted(x for x in prompt_versions if x),
        "effort": settings.evidence_effort,
        "imageLongEdge": settings.image_long_edge,
        "tiling": bool(getattr(args, "tiling", False) or settings.evidence_tiling),
        "cropMargins": settings.image_crop_margins,
        "escalationModel": settings.vision_escalation_model,
        "escalatedReceipts": sum(
            1
            for _, p in pairs
            if p.extraction and any("재추출" in w for w in p.extraction.warnings)
        ),
        "usageByModel": (
            {m: t.snapshot() for m, t in client.usage_by_model.items()}
            if client is not None and getattr(client, "usage_by_model", None)
            else None
        ),
        "budgetUsd": getattr(args, "budget_usd", None),
        "tileMaxAspect": settings.evidence_tile_max_aspect,
        "meanTiles": round(sum(p.tiles for _, p in pairs) / len(pairs), 2),
        "confidenceThreshold": args.confidence_threshold,
        "receipts": len(pairs),
        "stride": getattr(args, "stride", 1),
        "usage": usage,
        "estimatedCostUsd": total_cost_usd(client) if client is not None else None,
        "meanElapsedMs": round(sum(p.elapsed_ms for _, p in pairs) / len(pairs)),
    }
    (out / "run.json").write_text(
        json.dumps(run_info, ensure_ascii=False, indent=2) + "\n", encoding="utf-8"
    )
    print(summary)
    if usage:
        print(
            f"\n토큰 in={usage['input_tokens']} out={usage['output_tokens']} "
            f"cache_read={usage['cache_read_input_tokens']} → 추정 ${run_info['estimatedCostUsd']}"
            f" ({len(pairs)}장)"
        )
    if not is_test:
        failures = write_failures(out, pairs, scores)
        print(f"\nfailures.jsonl: {failures}건 → {out / 'failures.jsonl'}")
    return 0


def main() -> int:
    parser = argparse.ArgumentParser(
        description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter
    )
    parser.add_argument(
        "dataset", type=Path, help="라벨 디렉터리 (예: datasets/receipts/korie/val)"
    )
    parser.add_argument(
        "--out", type=Path, required=True, help="결과 디렉터리 (artifacts/eval/...)"
    )
    parser.add_argument(
        "--pipeline",
        choices=["vlm", "stub", "local"],
        default="vlm",
        help="vlm=Claude · local=Ollama(토큰 0, 개발용) · stub=고정 응답",
    )
    parser.add_argument("--limit", type=int, default=None, help="N장만 (dev subset)")
    parser.add_argument(
        "--stride",
        type=int,
        default=1,
        help="N 개마다 하나씩 골라 고르게 표집 (기본 1). --limit 만 쓰면 앞쪽 한 종류만 잡힌다",
    )
    parser.add_argument("--concurrency", type=int, default=2, help="동시 호출 수 (기본 2)")
    parser.add_argument(
        "--budget-usd",
        type=float,
        default=3.0,
        help="실행당 비용 상한(추정 USD). 닿으면 남은 장을 부르지 않는다 (기본 3.0)",
    )
    parser.add_argument("--force", action="store_true", help="저장된 예측을 무시하고 다시 호출")
    parser.add_argument("--rescore", action="store_true", help="저장된 예측만 다시 채점")
    parser.add_argument(
        "--confidence-threshold", type=float, default=0.85, help="과신 판정 임계값 (기본 0.85)"
    )
    parser.add_argument(
        "--tiling", action="store_true", help="긴 영수증을 세로 타일로 나눠 보낸다 (해상도 실험)"
    )
    parser.add_argument(
        "--no-overrides",
        action="store_true",
        help="라벨 폴더의 overrides.json 을 무시하고 원천 라벨 그대로 채점 (라벨 오류율 측정)",
    )
    return run(parser.parse_args())


if __name__ == "__main__":
    raise SystemExit(main())
