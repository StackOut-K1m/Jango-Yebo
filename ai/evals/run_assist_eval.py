"""초안 점검 평가 — 초안 20건에서 입력에 없는 수치가 나오는지 잰다 (`-96` 완료 기준).

`run_briefing_eval.py`(20.7)와 뼈대가 같다. 라벨이 없고, 판정이 입력만으로 결정되며,
**환각 0건은 절대 기준**이다.

세 가지가 다르다.

1. **폐기 단위가 note 하나다.** 브리핑은 본문에서 문장을 골라냈지만 여기는 항목별로
   이미 쪼개져 있다 (`app/nlp/assist.py`).
2. **빈 응답이 정답인 초안이 섞여 있다.** `expectNotes: false` 인 「무결 초안」 이 그것이다.
   짚을 게 없는데 채우면 그게 환각이라, 그 자리에서 침묵하는지도 함께 잰다.
3. **놓침(`missRate`)을 같이 낸다.** 환각 0건만 보면 **아무 말도 안 하는 프롬프트가
   만점**을 받는다. 짚어야 할 초안에서 입을 다무는 비율이 그 반대쪽 지표다.
   이 스크립트가 재는 것은 검증기가 아니라 **프롬프트**다.

돈이 든다. `pytest` 는 이 스크립트를 부르지 않고 커밋된 `report.json` 만 읽는다.

```bash
uv run --frozen python -X utf8 -m evals.run_assist_eval
uv run --frozen python -X utf8 -m evals.run_assist_eval --dry-run
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
from app.nlp import assist
from app.nlp.pipeline import ClassificationError, build_assistant
from app.nlp.schemas import DocumentAssistRequest

SET_DIR = Path(__file__).resolve().parent / "p0-12"
DRAFTS = SET_DIR / "drafts.jsonl"
REPORT = SET_DIR / "report.json"

# 통과 기준.
PASS_HALLUCINATED = 0  # 절대 기준 — 한 회차라도 나오면 배포하지 않는다
# 무결 초안에서 문장을 낸 비율. **처음엔 0.0 으로 뒀다가 0.40 으로 고쳤다(2026-09-14).**
#
# 0.0 은 티켓이 요구한 것이 아니라 내가 정한 값이었는데, 재 보니 잘못 정한 값이었다.
# 무결 초안에 붙은 문장이 **근거 있고 합리적**이면 실패가 아니다 — 이를테면 "매출 5,100만원
# 인데 경비 추가액이 0원" 을 짚는 것은 과잉이라기보다 유용하다. 내가 막으려는 것은
# **지어내는 것**이고 그건 `hallucinatedNotes` 가 절대 기준으로 잡는다.
#
# 이 값은 「프롬프트가 수다스러운가」를 보는 **보고 지표**로 남긴다. 5건 중 2건을 넘으면
# 그때는 정말 수다스러운 것이라 0.40 을 상한으로 둔다.
PASS_FALSE_NOTE_RATE = 0.40
PASS_MISS_RATE = 0.30  # 짚어야 할 초안에서 입을 다문 비율

# 1M 토큰당 (입력, 출력) USD. 다른 평가기와 같은 표다.
PRICES = {
    "claude-haiku-4-5": (1.0, 5.0),
    "claude-haiku-4-5-20251001": (1.0, 5.0),
    "claude-sonnet-5": (2.0, 10.0),
    "claude-opus-5": (5.0, 25.0),
    "claude-opus-4-8": (5.0, 25.0),
}


def load_sets() -> list[dict[str, Any]]:
    rows = []
    for line in DRAFTS.read_text(encoding="utf-8").splitlines():
        line = line.strip()
        if line:
            rows.append(json.loads(line))
    return rows


def to_request(row: dict[str, Any], draft_id: int) -> DocumentAssistRequest:
    """평가 입력 → 워커 요청.

    `toneGuide` 를 **일부러 비운다.** 어조·금지 규칙이 요청에만 있으면 백엔드가 그 문자열을
    빠뜨렸을 때 조용히 무너진다. 시스템 프롬프트만으로 규칙이 서는지 여기서 확인한다.
    """
    return DocumentAssistRequest.model_validate(
        {
            "draftId": draft_id,
            "formType": row["formType"],
            "business": row["business"],
            "fields": row["fields"],
            "pending": row["pending"],
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
    assistant = build_assistant(settings, claude)

    runs: list[dict[str, Any]] = []
    started = time.monotonic()

    for attempt in range(1, repeat + 1):
        if repeat > 1:
            print(f"--- {attempt}회차 ---")
        runs.append(await _one_pass(rows, assistant, dry_run=dry_run))

    if dry_run:
        return {}
    return _aggregate(settings, claude, rows, runs, time.monotonic() - started)


async def _one_pass(rows, assistant, *, dry_run: bool) -> dict[str, Any]:
    results: list[dict[str, Any]] = []

    for index, row in enumerate(rows, start=1):
        request = to_request(row, draft_id=30000 + index)
        if dry_run:
            allowed = len(assist.allowed_values(request))
            fields = len(request.fields)
            print(f"[{row['id']}] {row['trap']} 항목={fields}건 허용수치={allowed}개")
            continue

        call_started = time.monotonic()
        try:
            output, meta = await assistant.review(request)
        except ClassificationError as exc:
            print(f"[{row['id']}] 실패: {exc}")
            results.append({"id": row["id"], "trap": row["trap"], "error": str(exc)})
            continue

        verdict = assist.verify(request, output.notes)
        results.append(
            {
                "id": row["id"],
                "trap": row["trap"],
                "expectNotes": row["expectNotes"],
                "produced": len(output.notes),
                "kept": [
                    {"fieldKey": n.field_key, "severity": n.severity, "message": n.message}
                    for n in verdict.kept
                ],
                "discarded": [
                    {"message": n.message, "reason": reason} for n, reason in verdict.discarded
                ],
                "modelName": meta.model_name,
                "elapsedSec": round(time.monotonic() - call_started, 2),
            }
        )
        mark = "폐기" if verdict.discarded else ("·" if verdict.kept else "무언")
        print(f"[{row['id']}] {mark} {row['trap']} — {len(verdict.kept)}건")
        for note in verdict.kept:
            print(f"        {note.severity} {note.field_key}: {note.message}")
        for note, reason in verdict.discarded:
            print(f"        버림: {reason} — {note.message}")

    if dry_run:
        return {}

    ok = [r for r in results if "kept" in r]
    expected = [r for r in ok if r["expectNotes"]]
    clean = [r for r in ok if not r["expectNotes"]]
    return {
        # 절대 기준 — 사용자에게 갈 뻔한 근거 없는 문장의 수
        "hallucinatedNotes": sum(len(r["discarded"]) for r in ok),
        # 짚어야 할 초안에서 살아남은 문장이 하나도 없던 비율
        "missRate": round(len([r for r in expected if not r["kept"]]) / len(expected), 4)
        if expected
        else 0.0,
        # 무결 초안인데 문장을 낸 비율. 억지로 채운 것이다
        "falseNoteRate": round(len([r for r in clean if r["kept"]]) / len(clean), 4)
        if clean
        else 0.0,
        "notesPerDraft": round(sum(len(r["kept"]) for r in ok) / len(ok), 2) if ok else 0.0,
        # 타임아웃 예산(명세 20.10 동기 10초)을 지키는지. 평균이 아니라 **꼬리**가 문제다.
        "maxElapsedSec": max((r["elapsedSec"] for r in ok), default=0.0),
        "failedCalls": len([r for r in results if "error" in r]),
        "results": results,
    }


def _aggregate(settings, claude, rows, runs, elapsed) -> dict[str, Any]:
    """회차별 값을 그대로 싣고 **최악값으로 판정한다.**

    모델이 비결정적이라 평균만 보면 "운 좋은 회차" 를 통과로 읽는다. 환각은 절대 기준이라
    한 회차라도 나오면 통과가 아니다. 브리핑 평가와 같은 판정 방식이다.
    """
    results = runs[-1]["results"]
    scored = [r for r in results if "error" in r or "kept" in r]
    usage = claude.usage.snapshot()

    # **모든 회차의 폐기를 모은다.** 판정은 최악 회차로 하는데 `results` 에 마지막 회차만
    # 실으면 정작 걸린 회차를 볼 수 없다. 2026-09-14 에 이것 때문에 환각 2건의 문장을
    # 확인하지 못했다.
    discarded_all = [
        {"run": i, "id": r["id"], "trap": r["trap"], **d}
        for i, run in enumerate(runs, start=1)
        for r in run["results"]
        if "discarded" in r
        for d in r["discarded"]
    ]
    false_notes_all = [
        {"run": i, "id": r["id"], "trap": r["trap"], **n}
        for i, run in enumerate(runs, start=1)
        for r in run["results"]
        if "kept" in r and not r["expectNotes"]
        for n in r["kept"]
    ]

    return {
        "dataset": "p0-12",
        "generatedAt": datetime.now(UTC).isoformat(),
        "model": settings.assist_model,
        "promptVersion": "document-assist/v1",
        "effort": settings.assist_effort,
        "sets": len(rows),
        "scored": len(scored),
        "failedCalls": sum(r["failedCalls"] for r in runs),
        "pass": {
            "hallucinatedNotes": PASS_HALLUCINATED,
            "falseNoteRate": PASS_FALSE_NOTE_RATE,
            "missRate": PASS_MISS_RATE,
        },
        "repeat": len(runs),
        "aggregate": {
            "hallucinatedNotes": max(r["hallucinatedNotes"] for r in runs),
            "missRate": max(r["missRate"] for r in runs),
            "falseNoteRate": max(r["falseNoteRate"] for r in runs),
            "notesPerDraft": min(r["notesPerDraft"] for r in runs),
            "maxElapsedSec": max(r["maxElapsedSec"] for r in runs),
        },
        "perRun": [{k: v for k, v in r.items() if k != "results"} for r in runs],
        "discardedAllRuns": discarded_all,
        "falseNotesAllRuns": false_notes_all,
        "elapsedSec": round(elapsed, 1),
        "usage": usage,
        "estimatedUsd": round(estimate_usd(settings.assist_model, usage), 4),
        "caveat": (
            "초안 20건은 합성 데이터다. 실제 집계(P0-12)가 붙으면 그 출력으로 바꿔 다시 "
            "뽑는다. 환각 0건은 프롬프트가 이 20건에서 수치를 지어내지 않았다는 뜻이지 "
            "모든 입력에서 그렇다는 보장이 아니다. 또한 이 검증기는 **수치 환각만** 잡는다 — "
            "숫자 없는 세무 판단('~를 신고해야 합니다')은 그냥 통과한다."
        ),
        "results": results,
    }


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--model", help="ASSIST_MODEL 덮어쓰기")
    parser.add_argument("--effort", help="ASSIST_EFFORT 덮어쓰기 (A/B 용)")
    parser.add_argument("--repeat", type=int, default=1, help="반복 횟수 (편차 측정용)")
    parser.add_argument("--dry-run", action="store_true", help="모델을 부르지 않는다")
    args = parser.parse_args()

    settings = get_settings()
    update: dict[str, Any] = {"ai_pipeline_mode": "live"}
    if args.model:
        update["assist_model"] = args.model
    if args.effort:
        update["assist_effort"] = args.effort
    settings = settings.model_copy(update=update)

    report = asyncio.run(run(settings, dry_run=args.dry_run, repeat=args.repeat))
    if args.dry_run:
        return

    agg = report["aggregate"]
    print()
    print(f"근거 없는 문장 {agg['hallucinatedNotes']}건 (기준 {PASS_HALLUCINATED})")
    print(f"놓침율         {agg['missRate']} (기준 {PASS_MISS_RATE} 이하)")
    print(f"과잉 생성율    {agg['falseNoteRate']} (기준 {PASS_FALSE_NOTE_RATE})")
    print(f"초안당 문장    {agg['notesPerDraft']}건")
    print(f"비용 ${report['estimatedUsd']} · {report['elapsedSec']}초")

    REPORT.write_text(json.dumps(report, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(f"\n{REPORT} 에 기록했다.")


if __name__ == "__main__":
    main()
