"""매출현황 인쇄물(P1-22, 명세 20.9) 평가 — 합성 세트를 파이프라인에 돌려 요구 4건을 채점한다.

    uv run python -X utf8 -m experiments.evaluate_product_sales datasets/product-sales \\
        --out artifacts/eval/sales-001 --pipeline vlm
    uv run python -X utf8 -m experiments.evaluate_product_sales datasets/product-sales \\
        --out artifacts/eval/sales-smoke --pipeline stub

명세 20.9 의 요구 4건이 그대로 지표다 (라벨은 합성이라 완전하다 → 정밀도·환각률이 정확하다).
  1 기간은 헤더에서만 — 헤더에 없으면 period.source = null 이어야 한다. 출력일시로 추론하면 위반.
  2 합계 행 분리 — printedTotal 이 있고 items 에 합계 행이 없어야 한다.
  3 읽은 열만 — columnsDetected 가 라벨과 같고, 없는 열에 값(0 포함)을 채우면 위반.
  4 행을 만들지 않는다 — 환각 행 0 이 절대 기준. 흐린 행(blurredRows)을 읽어 내면 환각.
여러 장(pages)은 장 경계에서 겹친 행이 한 번만 나와야 한다(정밀도로 잡힌다).

예측은 `<out>/predictions/<id>.json` 에 저장·재개된다. `--known-products all` 은 라벨의 상품명을
knownProducts 로 넘겨 스냅 동작을 본다(기본 none — 신규 상품 처리를 본다).
"""

from __future__ import annotations

import argparse
import asyncio
import json
import re
import time
from collections import Counter
from collections.abc import Callable
from dataclasses import dataclass, field
from pathlib import Path
from typing import Any

from rapidfuzz import fuzz

from app.common.claude import FATAL_STAGES
from app.common.envelope import CamelModel
from app.common.model_meta import ModelMeta
from app.config import Settings
from app.ocr.normalization import normalize_name
from app.ocr.pipelines.base import ExtractionError, ProductSalesPipeline
from app.ocr.preprocess import prepare_image
from app.ocr.sales_validators import validate_product_sales
from app.ocr.schemas import ProductSalesExtraction, ProductSalesExtractionRequest, ProductSalesRow
from experiments.evaluate_evidence import _is_test_split, build_pipeline, total_cost_usd
from experiments.labels import ProductSalesLabelRecord, iter_product_sales_labels, read_manifest

ROW_NAME_THRESHOLD = 0.8
AMOUNT_COLUMNS = (
    "quantity",
    "grossAmount",
    "discountAmount",
    "netAmount",
    "vatAmount",
    "billedAmount",
    "share",
)
_TOTAL_ROW = re.compile(r"합\s*계|총\s*계|total", re.IGNORECASE)


class SalesPredictionRecord(CamelModel):
    source_id: str
    images: list[str]
    extraction: ProductSalesExtraction | None = None
    model: ModelMeta | None = None
    error: str | None = None
    error_stage: str | None = None
    elapsed_ms: int = 0


def _snake(key: str) -> str:
    return re.sub(r"([A-Z])", lambda m: "_" + m[1].lower(), key)


def _value(row: ProductSalesRow, column: str) -> Any:
    return getattr(row, _snake(column))


def _values_equal(column: str, gt: Any, pred: Any) -> bool:
    if gt is None or pred is None:
        return gt is None and pred is None
    if column == "share":
        return abs(float(gt) - float(pred)) <= 0.005  # 구성비는 소수 둘째 자리 반올림 차이 허용
    return float(gt) == float(pred)


def match_rows(
    labeled: list[ProductSalesRow], predicted: list[ProductSalesRow]
) -> tuple[list[tuple[int, int]], list[int], list[int]]:
    """상품명 유사도로 1:1 탐욕 매칭 → (매칭 인덱스 쌍, 누락 라벨 인덱스, 예측에만 있는 인덱스)."""
    candidates: list[tuple[float, int, int]] = []
    for i, gt in enumerate(labeled):
        gt_name = normalize_name(gt.product_name)
        for j, pred in enumerate(predicted):
            score = fuzz.WRatio(gt_name, normalize_name(pred.product_name)) / 100
            if score >= ROW_NAME_THRESHOLD:
                candidates.append((score, i, j))
    candidates.sort(key=lambda c: -c[0])
    used_gt: set[int] = set()
    used_pred: set[int] = set()
    pairs: list[tuple[int, int]] = []
    for _, i, j in candidates:
        if i in used_gt or j in used_pred:
            continue
        used_gt.add(i)
        used_pred.add(j)
        pairs.append((i, j))
    missed = [i for i in range(len(labeled)) if i not in used_gt]
    extra = [j for j in range(len(predicted)) if j not in used_pred]
    return pairs, missed, extra


@dataclass
class SalesScore:
    source_id: str
    failed: bool
    capture_type: str = ""
    # 요구 1
    period_source_correct: bool | None = None
    period_dates_correct: bool | None = None
    period_inferred: bool = False  # 헤더에 기간이 없는데 날짜를 냈다
    # 요구 2
    total_present: bool | None = None
    total_correct: bool | None = None
    total_row_in_items: bool = False
    # 요구 3
    columns_exact: bool | None = None
    phantom_values: int = 0  # 라벨에 없는 열에 값을 채운 셀 수
    # 요구 4 + 행 정확도
    matched: int = 0
    missed: int = 0
    hallucinated: int = 0
    blurred_hallucinated: int = 0  # 읽을 수 없게 만든 행을 읽어 냈다
    blurred_rows: int = 0
    warned_when_blurred: bool | None = None
    column_correct: dict[str, int] = field(default_factory=dict)
    column_n: dict[str, int] = field(default_factory=dict)
    name_exact: int = 0


def score_sample(
    label: ProductSalesLabelRecord, prediction: ProductSalesExtraction | None
) -> SalesScore:
    score = SalesScore(
        source_id=label.meta.source_id, failed=prediction is None, capture_type=label.capture_type
    )
    if prediction is None:
        return score
    gt, pred = label.extraction, prediction

    # §6.8 검산 (v1.18). 라벨을 보지 않고 예측만으로 나오므로 운영에서 나올 값과 같다.
    report = validate_product_sales(pred)
    score.needs_review = report.needs_review
    score.review_reasons = list(report.review_reasons)
    score.inconsistent_rows = len(report.inconsistent_rows)

    # 요구 1 — 기간
    if gt.period.source is None:
        score.period_source_correct = pred.period.source is None
        score.period_inferred = pred.period.start is not None or pred.period.end is not None
    else:
        score.period_source_correct = pred.period.source == "HEADER"
        score.period_dates_correct = (
            pred.period.start == gt.period.start and pred.period.end == gt.period.end
        )

    # 요구 2 — 합계 행
    if gt.printed_total is not None:
        score.total_present = pred.printed_total is not None
        if pred.printed_total is not None:
            fields = [
                c
                for c in AMOUNT_COLUMNS
                if c != "share" and getattr(gt.printed_total, _snake(c)) is not None
            ]
            score.total_correct = all(
                _values_equal(
                    c, getattr(gt.printed_total, _snake(c)), getattr(pred.printed_total, _snake(c))
                )
                for c in fields
            )
    score.total_row_in_items = any(_TOTAL_ROW.search(row.product_name) for row in pred.items)

    # 요구 3 — 읽은 열만
    label_columns = set(gt.columns_detected)
    score.columns_exact = set(pred.columns_detected) == label_columns
    for row in pred.items:
        for column in AMOUNT_COLUMNS:
            if column not in label_columns and _value(row, column) is not None:
                score.phantom_values += 1

    # 요구 4 — 행
    pairs, missed, extra = match_rows(gt.items, pred.items)
    score.matched, score.missed, score.hallucinated = len(pairs), len(missed), len(extra)
    blurred = [normalize_name(n) for n in label.meta.extra.get("blurredRows", [])]
    score.blurred_rows = len(blurred)
    if blurred:
        score.warned_when_blurred = bool(pred.warnings)
        for j in extra:
            name = normalize_name(pred.items[j].product_name)
            if any(fuzz.WRatio(name, b) / 100 >= ROW_NAME_THRESHOLD for b in blurred):
                score.blurred_hallucinated += 1
    for i, j in pairs:
        gt_row, pred_row = gt.items[i], pred.items[j]
        if normalize_name(gt_row.product_name) == normalize_name(pred_row.product_name):
            score.name_exact += 1
        for column in gt.columns_detected:
            if column == "productName":
                continue
            score.column_n[column] = score.column_n.get(column, 0) + 1
            if _values_equal(column, _value(gt_row, column), _value(pred_row, column)):
                score.column_correct[column] = score.column_correct.get(column, 0) + 1
    return score


def _rate(numerator: int, denominator: int) -> float | None:
    return round(numerator / denominator, 4) if denominator else None


def _bool_rate(values: list[bool | None]) -> tuple[float | None, int]:
    judged = [v for v in values if v is not None]
    return _rate(sum(judged), len(judged)), len(judged)


def aggregate(scores: list[SalesScore]) -> dict[str, Any]:
    ok = [s for s in scores if not s.failed]
    matched = sum(s.matched for s in ok)
    missed = sum(s.missed for s in ok)
    hallucinated = sum(s.hallucinated for s in ok)
    precision = _rate(matched, matched + hallucinated)
    recall = _rate(matched, matched + missed)
    columns: dict[str, Any] = {}
    for column in AMOUNT_COLUMNS:
        n = sum(s.column_n.get(column, 0) for s in ok)
        if n:
            columns[column] = {
                "n": n,
                "accuracy": _rate(sum(s.column_correct.get(column, 0) for s in ok), n),
            }
    period_source, period_n = _bool_rate([s.period_source_correct for s in ok])
    period_dates, dates_n = _bool_rate([s.period_dates_correct for s in ok])
    total_present, total_n = _bool_rate([s.total_present for s in ok])
    total_correct, total_correct_n = _bool_rate([s.total_correct for s in ok])
    columns_exact, columns_n = _bool_rate([s.columns_exact for s in ok])
    warned, warned_n = _bool_rate([s.warned_when_blurred for s in ok])
    by_capture: dict[str, dict[str, Any]] = {}
    for capture in sorted({s.capture_type for s in ok}):
        subset = [s for s in ok if s.capture_type == capture]
        m = sum(s.matched for s in subset)
        by_capture[capture] = {
            "samples": len(subset),
            "recall": _rate(m, m + sum(s.missed for s in subset)),
            "precision": _rate(m, m + sum(s.hallucinated for s in subset)),
            "hallucinatedRows": sum(s.hallucinated for s in subset),
        }
    return {
        "samples": len(scores),
        "processingFailureRate": _rate(len(scores) - len(ok), len(scores)),
        "req1PeriodSourceCorrectRate": period_source,
        "req1PeriodSourceN": period_n,
        "req1PeriodDatesCorrectRate": period_dates,
        "req1PeriodDatesN": dates_n,
        "req1PeriodInferredWhenAbsent": sum(s.period_inferred for s in ok),
        "req2PrintedTotalPresentRate": total_present,
        "req2PrintedTotalCorrectRate": total_correct,
        "req2PrintedTotalN": total_correct_n or total_n,
        "req2TotalRowLeakedIntoItems": sum(s.total_row_in_items for s in ok),
        "req3ColumnsExactRate": columns_exact,
        "req3ColumnsN": columns_n,
        "req3PhantomValues": sum(s.phantom_values for s in ok),
        "req4HallucinatedRows": hallucinated,
        "req4HallucinatedRowRate": _rate(hallucinated, matched + hallucinated),
        "req4SamplesWithHallucination": sum(1 for s in ok if s.hallucinated),
        "req4BlurredRowsHallucinated": sum(s.blurred_hallucinated for s in ok),
        "req4BlurredRows": sum(s.blurred_rows for s in ok),
        "req4WarnedWhenBlurredRate": warned,
        "req4WarnedN": warned_n,
        # §6.8 검산 (v1.18) — 라벨 없이 예측만 보고 나온다. 검토율은 사용자
        # 부담이라 낮을수록 좋다. 걸린 것이 실제 오류인지(정밀도)는 라벨과
        # 대조해야 알 수 있어 여기서는 세지 않는다.
        "needsReviewRate": _rate(sum(1 for s in ok if s.needs_review), len(ok)),
        "reviewReasons": dict(
            Counter(reason for s in ok for reason in s.review_reasons).most_common()
        ),
        "inconsistentRows": sum(s.inconsistent_rows for s in ok),
        "rows": {
            "labeled": matched + missed,
            "matched": matched,
            "missed": missed,
            "recall": recall,
            "precision": precision,
            "f1": (
                round(2 * precision * recall / (precision + recall), 4)
                if precision and recall
                else None
            ),
            "nameExactOnMatched": _rate(sum(s.name_exact for s in ok), matched),
        },
        "columns": columns,
        "byCaptureType": by_capture,
    }


def format_markdown(metrics: dict[str, Any]) -> str:
    rows = metrics["rows"]
    lines = [
        f"| 표본 | {metrics['samples']} | 처리 실패율 | {_pct(metrics['processingFailureRate'])} |",
        "| --- | --- | --- | --- |",
        f"| 요구1 기간 출처 | {_pct(metrics['req1PeriodSourceCorrectRate'])} "
        f"(n={metrics['req1PeriodSourceN']}) | 헤더 없는데 기간 추론 | "
        f"{metrics['req1PeriodInferredWhenAbsent']}건 |",
        f"| 요구1 기간 날짜 | {_pct(metrics['req1PeriodDatesCorrectRate'])} "
        f"(n={metrics['req1PeriodDatesN']}) | | |",
        f"| 요구2 합계 행 분리·정확 | {_pct(metrics['req2PrintedTotalPresentRate'])} / "
        f"{_pct(metrics['req2PrintedTotalCorrectRate'])} | 합계가 items 에 섞임 | "
        f"{metrics['req2TotalRowLeakedIntoItems']}건 |",
        f"| 요구3 열 집합 일치 | {_pct(metrics['req3ColumnsExactRate'])} "
        f"(n={metrics['req3ColumnsN']}) | 없는 열에 값 채움 | {metrics['req3PhantomValues']}셀 |",
        f"| 요구4 환각 행 | **{metrics['req4HallucinatedRows']}** "
        f"({_pct(metrics['req4HallucinatedRowRate'])}, 표본 "
        f"{metrics['req4SamplesWithHallucination']}건) | 흐린 행을 읽어 냄 | "
        f"{metrics['req4BlurredRowsHallucinated']}/{metrics['req4BlurredRows']} "
        f"(경고 남김 {_pct(metrics['req4WarnedWhenBlurredRate'])}) |",
        f"| 행 P/R/F1 | {_pct(rows['precision'])} / {_pct(rows['recall'])} / {_pct(rows['f1'])} "
        f"| 상품명 exact | {_pct(rows['nameExactOnMatched'])} |",
        f"| §6.8 검토 게이트 | {_pct(metrics['needsReviewRate'])} | 사유 | "
        f"{_reasons(metrics['reviewReasons'])} |",
    ]
    for column, value in metrics["columns"].items():
        lines.append(f"| 열 {column} | {_pct(value['accuracy'])} | n={value['n']} | |")
    for capture, value in metrics["byCaptureType"].items():
        lines.append(
            f"| {capture} | R {_pct(value['recall'])} / P {_pct(value['precision'])} | "
            f"환각 {value['hallucinatedRows']}행 | {value['samples']}표본 |"
        )
    return "\n".join(lines)


def _pct(value: float | None) -> str:
    return "-" if value is None else f"{value * 100:.1f}%"


def _reasons(counts: dict[str, int]) -> str:
    return ", ".join(f"{name} {n}" for name, n in counts.items()) or "없음"


# ---------------------------------------------------------------- 실행


async def predict_one(
    pipeline: ProductSalesPipeline,
    settings: Settings,
    label_path: Path,
    label: ProductSalesLabelRecord,
    *,
    known_products: str,
) -> SalesPredictionRecord:
    started = time.perf_counter()
    try:
        images = [
            prepare_image(
                label_path.with_name(name).read_bytes(),
                settings.image_long_edge,
                crop_margins=settings.image_crop_margins,
            )
            for name in label.images
        ]
        known = (
            [row.product_name for row in label.extraction.items] if known_products == "all" else []
        )
        request = ProductSalesExtractionRequest(
            job_id=0,
            business_id=0,
            capture_type=label.capture_type,
            file_object_keys=label.images,
            known_products=known,
        )
        extraction, meta = await pipeline.extract_product_sales(images, request)
        return SalesPredictionRecord(
            source_id=label.meta.source_id,
            images=label.images,
            extraction=extraction,
            model=meta,
            elapsed_ms=int((time.perf_counter() - started) * 1000),
        )
    except ExtractionError as exc:
        return SalesPredictionRecord(
            source_id=label.meta.source_id,
            images=label.images,
            error=str(exc),
            error_stage=exc.stage,
            elapsed_ms=int((time.perf_counter() - started) * 1000),
        )
    except Exception as exc:  # 한 장 때문에 멈추지 않는다
        return SalesPredictionRecord(
            source_id=label.meta.source_id,
            images=label.images,
            error=f"{type(exc).__name__}: {exc}",
            error_stage="unexpected",
            elapsed_ms=int((time.perf_counter() - started) * 1000),
        )


async def run_predictions(
    dataset: Path,
    out: Path,
    pipeline: ProductSalesPipeline,
    settings: Settings,
    *,
    limit: int | None,
    force: bool,
    concurrency: int,
    known_products: str,
    stride: int = 1,
    budget_usd: float | None = None,
    cost_so_far: Callable[[], float | None] | None = None,
) -> list[tuple[ProductSalesLabelRecord, SalesPredictionRecord]]:
    predictions_dir = out / "predictions"
    predictions_dir.mkdir(parents=True, exist_ok=True)
    # stride 를 먼저 — 정렬이 레이아웃별로 뭉쳐 있어(printout58 → printout80 → screen)
    # --limit 만 쓰면 앞의 한 레이아웃만 잡힌다
    labels = list(iter_product_sales_labels(dataset))[:: max(1, stride)][:limit]
    semaphore = asyncio.Semaphore(max(1, concurrency))
    results: list[tuple[ProductSalesLabelRecord, SalesPredictionRecord]] = []
    aborted = asyncio.Event()  # 키·쿼터 문제는 다음 호출도 실패한다 — 남은 표본을 태우지 않는다

    async def worker(label_path: Path, label: ProductSalesLabelRecord) -> None:
        target = predictions_dir / f"{label.meta.source_id}.json"
        if target.exists() and not force:
            record = SalesPredictionRecord.model_validate_json(target.read_text(encoding="utf-8"))
            results.append((label, record))
            return
        async with semaphore:
            if aborted.is_set():
                return
            record = await predict_one(
                pipeline, settings, label_path, label, known_products=known_products
            )
        if record.error_stage in FATAL_STAGES:
            if not aborted.is_set():
                print(f"[중단] {record.error} — 남은 표본은 호출하지 않는다 (캐시에 남기지 않음)")
            aborted.set()
            return
        if record.extraction is not None:
            # 성공만 캐시한다 (실패를 남기면 고친 뒤 재실행해도 그 표본을 건너뛴다)
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
                print(reached + ". 남은 표본은 안 부른다")
            aborted.set()

    await asyncio.gather(*(worker(p, lb) for p, lb in labels))
    if aborted.is_set():
        print(f"[중단] 예측 {len(results)}/{len(labels)}표본만 있음. 같은 명령으로 재개된다")
    results.sort(key=lambda pair: pair[0].meta.source_id)
    return results


def load_predictions(
    dataset: Path, out: Path
) -> list[tuple[ProductSalesLabelRecord, SalesPredictionRecord]]:
    pairs = []
    for _, label in iter_product_sales_labels(dataset):
        path = out / "predictions" / f"{label.meta.source_id}.json"
        if path.exists():
            pairs.append(
                (label, SalesPredictionRecord.model_validate_json(path.read_text(encoding="utf-8")))
            )
    return pairs


def write_failures(
    out: Path,
    pairs: list[tuple[ProductSalesLabelRecord, SalesPredictionRecord]],
    scores: list[SalesScore],
) -> int:
    count = 0
    with (out / "failures.jsonl").open("w", encoding="utf-8") as handle:
        for (label, pred), score in zip(pairs, scores, strict=True):
            violations = {
                "failed": score.failed,
                "periodInferred": score.period_inferred,
                "periodWrong": score.period_source_correct is False
                or score.period_dates_correct is False,
                "totalMissingOrWrong": score.total_present is False or score.total_correct is False,
                "totalRowInItems": score.total_row_in_items,
                "columnsMismatch": score.columns_exact is False,
                "phantomValues": score.phantom_values,
                "hallucinated": score.hallucinated,
                "missed": score.missed,
            }
            if not any(violations.values()):
                continue
            count += 1
            row = {
                "sourceId": label.meta.source_id,
                "images": label.images,
                "captureType": label.capture_type,
                "error": pred.error,
                "violations": violations,
                "labelRows": [r.product_name for r in label.extraction.items],
                "predictedRows": (
                    [r.product_name for r in pred.extraction.items] if pred.extraction else None
                ),
                "warnings": pred.extraction.warnings if pred.extraction else None,
            }
            handle.write(json.dumps(row, ensure_ascii=False) + "\n")
    return count


def run(args: argparse.Namespace) -> int:
    settings = Settings()
    dataset: Path = args.dataset
    out: Path = args.out
    out.mkdir(parents=True, exist_ok=True)
    client = None
    previous_usage = None
    if args.rescore:
        pairs = load_predictions(dataset, out)
        if not pairs:
            print("[실패] 저장된 예측이 없습니다. --rescore 없이 먼저 실행하세요.")
            return 2
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
                pipeline,  # type: ignore[arg-type]  # stub·VLM 구현체는 두 프로토콜을 모두 만족
                settings,
                limit=args.limit,
                force=args.force,
                concurrency=args.concurrency,
                known_products=args.known_products,
                stride=getattr(args, "stride", 1),
                budget_usd=budget,
                cost_so_far=((lambda: total_cost_usd(client)) if client is not None else None),
            )
        )
    if not pairs:
        print("[실패] 라벨이 없습니다.")
        return 2
    scores = [score_sample(label, pred.extraction) for label, pred in pairs]
    metrics = aggregate(scores)
    (out / "metrics.json").write_text(
        json.dumps(metrics, ensure_ascii=False, indent=2) + "\n", encoding="utf-8"
    )
    summary = format_markdown(metrics)
    (out / "summary.md").write_text(summary + "\n", encoding="utf-8")
    models = {p.model.model_name for _, p in pairs if p.model}
    usage = client.usage.snapshot() if client else previous_usage
    run_info = {
        "dataset": str(dataset),
        "datasetManifest": read_manifest(dataset),
        "isTestSplit": _is_test_split(dataset),
        "pipeline": args.pipeline if not args.rescore else "rescore",
        "knownProducts": args.known_products,
        "budgetUsd": getattr(args, "budget_usd", None),
        "modelNames": sorted(x for x in models if x),
        "promptVersions": sorted({p.model.prompt_version for _, p in pairs if p.model} - {None}),
        "effort": settings.product_sales_effort,
        "imageLongEdge": settings.image_long_edge,
        "samples": len(pairs),
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
            f"\n토큰 in={usage['input_tokens']} out={usage['output_tokens']} → 추정 "
            f"${run_info['estimatedCostUsd']} ({len(pairs)}표본)"
        )
    failures = write_failures(out, pairs, scores)
    print(f"\nfailures.jsonl: {failures}건 → {out / 'failures.jsonl'}")
    return 0


def main() -> int:
    parser = argparse.ArgumentParser(
        description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter
    )
    parser.add_argument("dataset", type=Path, help="라벨 디렉터리 (예: datasets/product-sales)")
    parser.add_argument("--out", type=Path, required=True)
    parser.add_argument(
        "--pipeline",
        choices=["vlm", "stub", "local"],
        default="vlm",
        help="vlm=Claude · local=Ollama(토큰 0, 개발용) · stub=고정 응답",
    )
    parser.add_argument("--limit", type=int, default=None)
    parser.add_argument(
        "--stride",
        type=int,
        default=1,
        help="N 개마다 하나씩 골라 고르게 표집 (기본 1). --limit 만 쓰면 앞쪽 한 종류만 잡힌다",
    )
    parser.add_argument("--concurrency", type=int, default=2)
    parser.add_argument(
        "--budget-usd",
        type=float,
        default=3.0,
        help="실행당 비용 상한(추정 USD). 닿으면 남은 표본을 부르지 않는다 (기본 3.0)",
    )
    parser.add_argument("--force", action="store_true")
    parser.add_argument("--rescore", action="store_true")
    parser.add_argument(
        "--known-products",
        choices=["none", "all"],
        default="none",
        help="knownProducts 로 라벨 상품명을 넘길지 (all = 스냅 동작 확인)",
    )
    return run(parser.parse_args())


if __name__ == "__main__":
    raise SystemExit(main())
