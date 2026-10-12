"""브리핑 평가 — 지표 세트 30건에서 환각 숫자가 나오는지 잰다 (`-95` 완료 기준).

`evals/run_eval.py`(거래 분류)와 두 가지가 다르다.

1. **라벨이 없다.** 재는 것이 "좋은 문장인가" 가 아니라 "입력에 없는 숫자를 지어냈는가" 이고,
   그 판정은 입력 `metrics` 만으로 결정적으로 내려진다 (`app/nlp/briefing.py`).
2. **"환각 0건" 만 보면 안 된다.** 검증기가 다 버리면 사용자에게 가는 환각은 0 이지만
   브리핑도 0 이다. 그래서 **폐기율**을 함께 낸다 — 그쪽이 프롬프트 품질의 실제 지표다.
   이 스크립트가 재는 것은 검증기가 아니라 **프롬프트**다.

돈이 든다. `pytest` 는 이 스크립트를 부르지 않고 커밋된 `report.json` 만 읽는다.

```bash
uv run --frozen python -X utf8 -m evals.run_briefing_eval
uv run --frozen python -X utf8 -m evals.run_briefing_eval --dry-run
```
"""

from __future__ import annotations

import argparse
import asyncio
import json
import time
from datetime import UTC, datetime
from pathlib import Path
from typing import Any

from app.common.claude import ClaudeClient
from app.config import Settings, get_settings
from app.nlp import briefing
from app.nlp.pipeline import ClassificationError, build_briefer
from app.nlp.schemas import BriefingGenerationRequest

SET_DIR = Path(__file__).resolve().parent / "p1-17"
METRICS = SET_DIR / "metrics.jsonl"
REPORT = SET_DIR / "report.json"

# 통과 기준. 환각은 **절대 기준**이라 0 이고, 나머지는 프롬프트 품질의 하한이다.
PASS_HALLUCINATED = 0
PASS_TOTAL_LOSS_RATE = 0.0  # 본문이 통째로 사라진 브리핑이 하나도 없어야 한다
PASS_PARTIAL_RATE = 0.20  # 5건에 1건 넘게 깎이면 프롬프트를 고친다

# 1M 토큰당 (입력, 출력) USD. `evals/run_eval.py` 와 같은 표다.
PRICES = {
    "claude-haiku-4-5": (1.0, 5.0),
    "claude-haiku-4-5-20251001": (1.0, 5.0),
    "claude-sonnet-5": (2.0, 10.0),
    "claude-opus-5": (5.0, 25.0),
    "claude-opus-4-8": (5.0, 25.0),
}


def load_sets() -> list[dict[str, Any]]:
    rows = []
    for line in METRICS.read_text(encoding="utf-8").splitlines():
        line = line.strip()
        if line:
            rows.append(json.loads(line))
    return rows


def to_request(row: dict[str, Any], job_id: int) -> BriefingGenerationRequest:
    """평가 입력 → 워커 요청.

    `toneGuide` 를 **일부러 비운다.** 어조 규칙이 요청에만 있으면 백엔드가 그 문자열을
    빠뜨렸을 때 조용히 무너진다. 시스템 프롬프트만으로 규칙이 서는지 여기서 확인한다.
    """
    return BriefingGenerationRequest.model_validate(
        {
            "jobId": job_id,
            "briefingId": job_id,
            "periodType": row["periodType"],
            "metrics": row["metrics"],
        }
    )


def estimate_usd(model: str, usage: dict[str, int]) -> float:
    price_in, price_out = PRICES.get(model, (0.0, 0.0))
    return (
        usage.get("input_tokens", 0) * price_in + usage.get("output_tokens", 0) * price_out
    ) / 1_000_000


async def run(settings: Settings, *, dry_run: bool, repeat: int = 1) -> dict[str, Any]:
    rows = load_sets()
    claude = ClaudeClient(settings)
    briefer = build_briefer(settings, claude)

    runs: list[dict[str, Any]] = []
    started = time.monotonic()

    for attempt in range(1, repeat + 1):
        if repeat > 1:
            print(f"--- {attempt}회차 ---")
        runs.append(await _one_pass(rows, briefer, dry_run=dry_run))

    if dry_run:
        return {}
    return _aggregate(settings, claude, rows, runs, time.monotonic() - started)


async def _one_pass(rows, briefer, *, dry_run: bool) -> dict[str, Any]:
    results: list[dict[str, Any]] = []

    for index, row in enumerate(rows, start=1):
        request = to_request(row, job_id=90000 + index)
        if dry_run:
            print(f"[{row['id']}] 함정={row['trap']} 지표={len(request.metrics)}건 — 호출 안 함")
            continue

        try:
            output, meta = await briefer.write(request)
        except ClassificationError as exc:
            print(f"[{row['id']}] 실패: {exc}")
            results.append({"id": row["id"], "trap": row["trap"], "error": str(exc)})
            continue

        verdict = briefing.verify(request, output.content)
        headline_dropped = bool(briefing.verify(request, output.headline).discarded)
        results.append(
            {
                "id": row["id"],
                "trap": row["trap"],
                "headline": output.headline,
                "content": output.content,
                "kept": verdict.kept,
                "discarded": [{"sentence": s, "reason": r} for s, r in verdict.discarded],
                "headlineDropped": headline_dropped,
                "modelName": meta.model_name,
            }
        )
        mark = "·" if not verdict.discarded and not headline_dropped else "폐기"
        print(f"[{row['id']}] {mark} {output.headline}")
        for sentence, reason in verdict.discarded:
            print(f"        버림: {reason} — {sentence}")

    if dry_run:
        return {}

    ok = [r for r in results if "kept" in r]
    partial = [r for r in ok if r["discarded"] or r["headlineDropped"]]
    return {
        "hallucinatedSentences": sum(len(r["discarded"]) for r in ok),
        "partialRate": round(len(partial) / len(ok), 4) if ok else 0.0,
        "totalLossRate": round(len([r for r in ok if not r["kept"]]) / len(ok), 4) if ok else 0.0,
        "headlineDropRate": round(len([r for r in ok if r["headlineDropped"]]) / len(ok), 4)
        if ok
        else 0.0,
        "failedCalls": len([r for r in results if "error" in r]),
        "results": results,
    }


def _aggregate(settings, claude, rows, runs, elapsed) -> dict[str, Any]:
    """회차별 값을 그대로 싣고 최악값으로 판정한다.

    모델이 비결정적이라 평균만 보면 "운 좋은 회차" 를 통과로 읽는다. 환각은 절대 기준이므로
    **한 회차라도 나오면 통과가 아니다.** 분류 평가(`run_eval.py`)가 평균을 쓰는 것과 다르다.
    """
    results = runs[-1]["results"]
    scored = [r for r in results if "error" in r or "kept" in r]

    hallucinated = max(r["hallucinatedSentences"] for r in runs)
    usage = claude.usage.snapshot()
    return {
        "dataset": "p1-17",
        "generatedAt": datetime.now(UTC).isoformat(),
        "model": settings.briefing_model,
        "promptVersion": "briefing-generation/v1",
        "effort": settings.briefing_effort,
        "sets": len(rows),
        "scored": len(scored),
        "failedCalls": sum(r["failedCalls"] for r in runs),
        "pass": {
            "hallucinatedSentences": PASS_HALLUCINATED,
            "totalLossRate": PASS_TOTAL_LOSS_RATE,
            "partialRate": PASS_PARTIAL_RATE,
        },
        "repeat": len(runs),
        "aggregate": {
            "hallucinatedSentences": hallucinated,
            "partialRate": max(r["partialRate"] for r in runs),
            "totalLossRate": max(r["totalLossRate"] for r in runs),
            "headlineDropRate": max(r["headlineDropRate"] for r in runs),
        },
        "perRun": [{k: v for k, v in r.items() if k != "results"} for r in runs],
        "elapsedSec": round(elapsed, 1),
        "usage": usage,
        "estimatedUsd": round(estimate_usd(settings.briefing_model, usage), 4),
        "caveat": (
            "지표 세트는 합성 데이터다. 실제 계산 엔진(P1-19·P1-22)이 붙으면 그 출력으로 "
            "바꿔 다시 뽑는다. 환각 0건은 프롬프트가 이 30건에서 숫자를 지어내지 않았다는 "
            "뜻이지, 모든 입력에서 그렇다는 보장이 아니다."
        ),
        "results": results,
    }


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--model", help="BRIEFING_MODEL 덮어쓰기")
    parser.add_argument("--repeat", type=int, default=1, help="반복 횟수 (편차 측정용)")
    parser.add_argument("--dry-run", action="store_true", help="모델을 부르지 않는다")
    args = parser.parse_args()

    settings = get_settings()
    update: dict[str, Any] = {"ai_pipeline_mode": "live"}
    if args.model:
        update["briefing_model"] = args.model
    settings = settings.model_copy(update=update)

    report = asyncio.run(run(settings, dry_run=args.dry_run, repeat=args.repeat))
    if args.dry_run:
        return

    agg = report["aggregate"]
    print()
    print(f"환각 문장     {agg['hallucinatedSentences']}건 (기준 {PASS_HALLUCINATED})")
    print(f"부분 폐기율   {agg['partialRate']} (기준 {PASS_PARTIAL_RATE} 이하)")
    print(f"전부 폐기율   {agg['totalLossRate']} (기준 {PASS_TOTAL_LOSS_RATE})")
    print(f"headline 폐기 {agg['headlineDropRate']}")
    print(f"비용 ${report['estimatedUsd']} · {report['elapsedSec']}초")

    REPORT.write_text(json.dumps(report, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(f"\n{REPORT} 에 기록했다.")


if __name__ == "__main__":
    main()
