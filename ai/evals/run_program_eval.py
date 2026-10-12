"""공고 구조화 평가 — 원문에 없는 조건을 지어내는지 잰다 (P1-15 · 20.6).

브리핑(`run_briefing_eval.py`)과 다른 점이 하나 있다. 브리핑은 "입력 `metrics` 에 없는
숫자인가" 로 **결정적으로** 판정되지만, 공고는 원문이 자유 문장이라 그렇게 못 한다
(`app/nlp/pipeline.py` `run_structuring_job` 주석).

그래서 이 스크립트는 **판정하지 않고 선별한다.** 출력의 값마다 원문에서 근거를 찾아 보고,
못 찾은 것만 `unsupported` 로 모아 사람에게 넘긴다. 사람이 읽어야 할 양이 32건 전부에서
몇 건으로 줄어드는 것이 이 선별의 값어치다.

**「환각 0건」 만 보면 안 된다.** 모델이 모든 필드를 비우고 `uncertainConditions` 만 채우면
환각은 0 이지만 화면에는 「확인 필요」만 뜬다. 그래서 채움률과 과신율을 함께 낸다 —
둘 사이의 균형이 프롬프트·모델 품질의 실제 지표다.

돈이 든다. `pytest` 는 이 스크립트를 부르지 않고 커밋된 `report.json` 만 읽는다.

```bash
uv run --frozen python -X utf8 -m evals.run_program_eval
uv run --frozen python -X utf8 -m evals.run_program_eval --dry-run
```

모델은 워커 설정(`app/config.py`)을 따른다. 모델을 바꿔 비교하려면 그 설정을 바꿔 두 번
돌리고 `--out` 으로 보고서를 나눠 받는다.
"""

from __future__ import annotations

import argparse
import asyncio
import json
import re
import time
from datetime import UTC, datetime
from pathlib import Path
from typing import Any

from app.common.claude import ClaudeClient
from app.config import Settings, get_settings
from app.nlp.pipeline import ClassificationError, build_structurer
from app.nlp.schemas import ProgramStructuringRequest, ProgramStructuringResult

SET_DIR = Path(__file__).resolve().parent / "p1-15"
PROGRAMS = SET_DIR / "programs.jsonl"
REPORT = SET_DIR / "report.json"

# 통과 기준.
#
# unsupported 는 **절대 기준**이다 — 사람이 확인해 진짜 환각이면 하나라도 못 쓴다.
# 자격이 안 되는 사장님에게 지원금을 추천하는 실패라 비용으로 상쇄되지 않는다.
PASS_UNSUPPORTED = 0
# 「공고문 참조」라고 적힌 공고에서 확신하면 거의 확실히 지어낸 것이다.
PASS_OVERCONFIDENT_RATE = 0.10
# 반대쪽 실패 — 전부 「확인 필요」로 넘기면 환각은 0 이지만 기능도 0 이다.
PASS_ALL_UNSURE_RATE = 0.30

# 1M 토큰당 (입력, 출력) USD. `evals/run_eval.py` 와 같은 표다.
PRICES = {
    "claude-haiku-4-5": (1.0, 5.0),
    "claude-haiku-4-5-20251001": (1.0, 5.0),
    "claude-sonnet-5": (2.0, 10.0),
    "claude-opus-5": (5.0, 25.0),
}


def load_sets() -> list[dict[str, Any]]:
    rows = []
    for line in PROGRAMS.read_text(encoding="utf-8").splitlines():
        line = line.strip()
        if line:
            rows.append(json.loads(line))
    return rows


def to_request(row: dict[str, Any], job_id: int) -> ProgramStructuringRequest:
    return ProgramStructuringRequest.model_validate(
        {
            "jobId": job_id,
            "programId": job_id,
            "rawText": row["rawText"],
            "sourceUrl": row["sourceUrl"],
        }
    )


# ---------------------------------------------------------------- 근거 선별


def _haystack(row: dict[str, Any]) -> str:
    """근거를 찾을 자리. 원문 + 해시태그 + 기간 문자열."""
    return f"{row['rawText']} {row.get('hashtags', '')} {row.get('period', '')}"


# 표기만 다르고 뜻은 같은 글자들. **가운뎃점이 세 벌이라는 것이 실제로 문제가 됐다** —
# 공고 원문은 한글 가운뎃점 `ㆍ`(U+318D)을 쓰고 모델은 라틴 `·`(U+00B7)로 받아 적는다.
# 2026-09-16 첫 실행에서 「휴ㆍ폐업체 제외」가 원문에 **그대로 있는데도** 환각 후보로
# 올라왔다. 사람에게 넘기는 목록이 이런 것으로 채워지면 진짜를 못 찾는다.
_NOISE = re.compile(r"[\s()\[\]·ㆍ・‧,，\-/]+")


def _flatten(text: str) -> str:
    return _NOISE.sub("", text)


def _number_supported(value: int, hay: str) -> bool:
    """숫자가 원문에 있나.

    표기가 여러 가지다 — `300000000` 이 원문에는 `3억`·`3억원`·`300,000,000` 으로 적힌다.
    찾는 쪽을 넓게 잡는다. **여기서 걸리지 않는 것만 사람에게 넘기므로, 넓게 잡는 쪽이
    사람의 일을 줄인다.** 반대로 좁게 잡으면 멀쩡한 값이 환각 후보로 쏟아진다.

    첫 실행에서 두 표기를 놓쳤다. 둘 다 흔한 표기다.

    | 값 | 원문 | 왜 놓쳤나 |
    | --- | --- | --- |
    | `10000000` | `1,000만원` | 한글 단위 앞에도 천 단위 쉼표가 붙는다 |
    | `250000000` | `2억 5천만원` | 억으로 나누어떨어지지 않아 억 표기를 안 만들었다 |
    """
    flat = _flatten(hay)
    forms = {str(value), f"{value:,}"}
    units = (("억", 100_000_000), ("천만", 10_000_000), ("백만", 1_000_000), ("만", 10_000))
    for unit, scale in units:
        if value % scale == 0:
            forms.add(f"{value // scale}{unit}")
            # `1,000만` 처럼 한글 단위 앞에도 쉼표가 온다. 평탄화한 건초더미와 맞추려면
            # 쉼표 없는 형태면 충분하지만, 원문 그대로도 함께 본다.
            forms.add(f"{value // scale:,}{unit}")
    # 억 + 나머지 복합 표기(`2억5천만`·`1억2000만`). 억 단위 공고에서 흔하다.
    if value >= 100_000_000:
        billions, rest = divmod(value, 100_000_000)
        if rest:
            for unit, scale in (("천만", 10_000_000), ("백만", 1_000_000), ("만", 10_000)):
                if rest % scale == 0:
                    forms.add(f"{billions}억{rest // scale}{unit}")
    return any(form in hay or _flatten(form) in flat for form in forms)


# 제외 대상·업종은 **원문 그대로 오지 않는다.** 모델이 문장을 항목으로 바꾸기 때문이다 —
# 「권고사직 이력이 **없는** 기업」(자격)은 excludes 에서 「이력이 **있는** 기업」(제외)이 되고,
# 「중소 제조기업」은 업종 「제조업」이 된다. 둘 다 옳은 변환이다.
#
# 통째 일치를 요구하면 **이런 정당한 변환이 전부 환각 후보로 쏟아져** 사람이 읽을 목록이
# 오히려 늘고, 통과 기준(근거 없는 값 0)은 영영 못 넘는다. 그래서 「원문에 뿌리가 있나」
# 정도로 본다 — 이어진 여섯 글자가 원문에 있으면 지어낸 말이 아니다.
_GROUNDING_NGRAM = 6


def _text_supported(value: str, hay: str) -> bool:
    """지역·업종·제외 항목이 원문에 뿌리가 있나. **판정이 아니라 선별이다.**"""
    token = _flatten(value)
    if len(token) < 2:
        return True  # 너무 짧아 판단 근거가 안 된다
    flat = _flatten(hay)
    if token in flat:
        return True

    # 「경기 안양시」처럼 두 토막을 붙여 적은 값은 통째로는 안 맞고 토막으로 맞는다.
    parts = [p for p in re.split(r"[/·ㆍ・]", value) if len(_flatten(p)) >= 2]
    if any(_flatten(part) in flat for part in parts):
        return True

    # 「제조업」 → 「제조」. 꼬리말을 떼면 「중소 제조기업」에 닿는다.
    stem = re.sub(r"(기업|업체|사업자|업)$", "", token)
    if len(stem) >= 2 and stem in flat:
        return True

    # 가운뎃점 축약을 푼 값. 공고는 「휴ㆍ폐업체」로 줄여 쓰고 모델은 「휴업체」·「폐업체」
    # 두 항목으로 편다 — **옳은 변환이고 한국어 공고에 흔한 표기**다(「생산ㆍ근로환경」도
    # 같다). 편 쪽만 보면 원문에 없으니, 원문에서 접힌 형태를 찾아 준다.
    # 접히는 자리는 첫 글자 뒤라고 정해져 있지 않다 — 「휴ㆍ폐업체」는 한 글자 뒤지만
    # 「생산ㆍ근로환경」은 두 글자 뒤다. 갈라질 수 있는 자리를 전부 본다.
    if len(token) >= 3:
        spaced = _NOISE.sub(" ", hay)
        for cut in range(1, len(token)):
            folded = re.compile(
                re.escape(token[:cut]) + r"[·ㆍ・‧]\s*\S{0,6}" + re.escape(token[cut:])
            )
            if folded.search(hay) or folded.search(spaced):
                return True

    # 이어진 여섯 글자가 원문에 있나 — 문장을 항목으로 바꾼 값의 뿌리를 본다.
    if len(token) >= _GROUNDING_NGRAM:
        return any(
            token[i : i + _GROUNDING_NGRAM] in flat
            for i in range(len(token) - _GROUNDING_NGRAM + 1)
        )
    return False


def screen(row: dict[str, Any], result: Any) -> list[dict[str, str]]:
    """원문에서 근거를 못 찾은 값을 모은다. **판정이 아니라 선별이다.**"""
    hay = _haystack(row)
    found: list[dict[str, str]] = []

    def flag(field: str, value: Any) -> None:
        found.append({"field": field, "value": str(value)})

    eligibility = result.eligibility
    for field in ("max_annual_revenue", "min_years_in_business", "max_years_in_business"):
        value = getattr(eligibility, field, None)
        if value is not None and not _number_supported(int(value), hay):
            flag(field, value)
    if result.max_amount is not None and not _number_supported(int(result.max_amount), hay):
        flag("max_amount", result.max_amount)

    # **계약이 메워진 뒤 새로 생긴 환각 자리다** (2026-09-16).
    #
    # 이 둘은 원문에 거의 없다 — 32건 중 지급 시기를 말한 공고가 1건이다. 없는 것을
    # 채우기가 가장 쉬운 자리이고, 채워지면 17.3 이 「지원금이 공백 전에 도착한다」를
    # 말한다. 지어낸 숫자로 그 문장이 나가면 사장님이 다른 자금 대책을 접는다.
    if result.expected_disbursement_days is not None and not _number_supported(
        int(result.expected_disbursement_days), hay
    ):
        flag("expected_disbursement_days", result.expected_disbursement_days)
    # 금리는 소수라 정수 표기 탐색이 안 통한다. 원문에 그 수가 글자로 있는지만 본다
    # (`2.5` · `2.50` 둘 다 받는다 — 표기가 갈리는 만큼 넓게 잡아야 사람 일이 는다).
    if result.interest_rate is not None:
        rate = result.interest_rate
        forms = {f"{rate}", f"{rate:f}".rstrip("0").rstrip("."), f"{rate:.1f}", f"{rate:.2f}"}
        if not any(form in hay for form in forms):
            flag("interest_rate", rate)

    for field in ("regions", "industries", "excludes"):
        for item in getattr(eligibility, field, []) or []:
            if not _text_supported(str(item), hay):
                flag(field, item)

    for field in ("apply_start", "apply_end"):
        value = getattr(result, field, None)
        if value is not None and str(value).replace("-", "") not in hay.replace("-", ""):
            flag(field, value)

    return found


def _is_all_unsure(result: Any) -> bool:
    """조건은 하나도 못 뽑고 「확인 필요」만 남았나."""
    e = result.eligibility
    filled = bool(e.regions or e.industries or e.excludes)
    filled = filled or any(
        getattr(e, f) is not None
        for f in ("max_annual_revenue", "min_years_in_business", "max_years_in_business")
    )
    return not filled


def _says_see_the_document(row: dict[str, Any]) -> bool:
    return bool(re.search(r"공고문\s*참조|자세한\s*(사항|내용)", row["rawText"]))


# ---------------------------------------------------------------- 실행


def estimate_usd(model: str, usage: dict[str, int]) -> float:
    price_in, price_out = PRICES.get(model, (0.0, 0.0))
    return (
        usage.get("input_tokens", 0) * price_in + usage.get("output_tokens", 0) * price_out
    ) / 1_000_000


async def run(settings: Settings, *, dry_run: bool) -> dict[str, Any]:
    rows = load_sets()
    claude = ClaudeClient(settings)
    structurer = build_structurer(settings, claude)

    started = time.monotonic()
    results: list[dict[str, Any]] = []

    for index, row in enumerate(rows, start=1):
        if dry_run:
            traps = ",".join(row["traps"])
            print(f"[{row['id']}] 함정={traps} 원문={len(row['rawText'])}자 — 호출 안 함")
            continue

        try:
            result, meta = await structurer.structure(to_request(row, job_id=91000 + index))
        except ClassificationError as exc:
            print(f"[{row['id']}] 실패: {exc}")
            results.append({"id": row["id"], "traps": row["traps"], "error": str(exc)})
            continue

        unsupported = screen(row, result)
        uncertain = list(result.eligibility.uncertain_conditions)
        overconfident = _says_see_the_document(row) and not uncertain
        results.append(
            {
                "id": row["id"],
                "programId": row["programId"],
                "traps": row["traps"],
                "supportType": result.support_type,
                "uncertainConditions": uncertain,
                "unsupported": unsupported,
                "overconfident": overconfident,
                "allUnsure": _is_all_unsure(result),
                "modelName": meta.model_name,
                "pipeline": meta.pipeline,
                # **모델이 낸 것을 통째로 남긴다.** 선별기는 앞으로도 고쳐진다 —
                # 첫 실행에서 가운뎃점 한 글자 때문에 멀쩡한 값 여섯 개가 환각 후보로
                # 올라왔고, 고친 뒤 다시 재려면 **32건을 유료로 또 불러야 했다.**
                # 출력을 들고 있으면 선별기 수정은 `--rescreen` 으로 공짜가 된다.
                "output": result.model_dump(mode="json", by_alias=True),
            }
        )
        mark = "환각후보" if unsupported else ("과신" if overconfident else "·")
        print(f"[{row['id']}] {mark} 종류={result.support_type} 확인필요={len(uncertain)}건")
        for item in unsupported:
            print(f"        근거 없음: {item['field']} = {item['value']}")

    if dry_run:
        total = sum(len(r["rawText"]) for r in rows)
        print(f"\n{len(rows)}건 · 원문 합계 {total:,}자 — 호출하지 않았다")
        return {}

    return _aggregate(settings, claude, results, time.monotonic() - started)


def _aggregate(
    settings: Settings, claude: ClaudeClient, results: list[dict[str, Any]], elapsed: float
) -> dict[str, Any]:
    ok = [r for r in results if "unsupported" in r]
    # ⚠ **여기서 이름을 틀려 첫 실행의 비용이 기록되지 않았다** (2026-09-16).
    # `getattr(claude, "usage_totals", lambda: {})()` 로 써 두어 없는 이름에 조용히
    # 빈 값이 잡혔고, `estimatedUsd` 가 `null` 로 남았다. 실제 이름은 `usage.snapshot()`
    # 이고 `evals/run_eval.py` 가 처음부터 그렇게 쓰고 있었다.
    usage = claude.usage.snapshot()
    model = ok[0]["modelName"] if ok else getattr(settings, "structuring_model", "unknown")

    summary = _summarize(results, usage, model)
    summary["pipeline"] = ok[0]["pipeline"] if ok else None
    summary["elapsedSeconds"] = round(elapsed, 1)
    return summary


def _summarize(results: list[dict[str, Any]], usage: Any, model: str) -> dict[str, Any]:
    """지표를 낸다. **호출 경로와 재선별 경로가 같은 계산을 쓴다** — 갈라지면 두 보고서를
    나란히 놓고 비교할 수 없다."""
    ok = [r for r in results if "unsupported" in r]
    n = len(ok) or 1

    summary = {
        "generatedAt": datetime.now(UTC).isoformat(),
        "model": model,
        "count": len(results),
        "errors": len(results) - len(ok),
        # **사람이 확인해야 하는 목록이다.** 여기 값이 0 이 아니면 그 건들을 원문과
        # 대조해 진짜 환각인지 본다. 진짜면 그 모델은 못 쓴다.
        "unsupportedValues": sum(len(r["unsupported"]) for r in ok),
        "unsupportedRows": len([r for r in ok if r["unsupported"]]),
        # 「공고문 참조」인데 확신한 비율. 거의 확실히 지어낸 것이다.
        "overconfidentRate": round(len([r for r in ok if r["overconfident"]]) / n, 4),
        # 반대쪽 실패 — 환각은 0 이지만 기능도 0 인 상태.
        "allUnsureRate": round(len([r for r in ok if r["allUnsure"]]) / n, 4),
        "usage": usage,
        "estimatedUsd": round(estimate_usd(model, usage), 4) if usage else None,
        "rows": ok,
    }
    summary["passed"] = (
        summary["unsupportedValues"] <= PASS_UNSUPPORTED
        and summary["overconfidentRate"] <= PASS_OVERCONFIDENT_RATE
        and summary["allUnsureRate"] <= PASS_ALL_UNSURE_RATE
    )
    return summary


def rescreen(report_path: Path) -> dict[str, Any]:
    """**호출 없이** 저장된 출력을 다시 선별한다.

    선별기는 앞으로도 고쳐진다 — 첫 실행에서 가운뎃점 한 글자(`ㆍ` ↔ `·`) 때문에 원문에
    그대로 있는 값이 환각 후보로 올라왔다. 그때 고친 선별기를 확인하려고 32건을 유료로
    다시 부르는 것은 낭비다. **판정 기준을 손보는 일과 모델을 재는 일은 분리한다.**

    `usage` 와 `model` 은 원래 실행의 것을 그대로 물려받는다 — 이 함수는 돈을 쓰지 않으므로
    새로 잴 것이 없다.
    """
    old = json.loads(report_path.read_text(encoding="utf-8"))
    rows = {r["id"]: r for r in load_sets()}

    results: list[dict[str, Any]] = []
    for row in old["rows"]:
        if "output" not in row:
            raise SystemExit(
                f"{report_path} 는 모델 출력을 담고 있지 않습니다 — 유료 실행이 먼저입니다."
            )
        result = ProgramStructuringResult.model_validate(row["output"])
        source = rows[row["id"]]
        unsupported = screen(source, result)
        uncertain = list(result.eligibility.uncertain_conditions)
        results.append(
            {
                **row,
                "uncertainConditions": uncertain,
                "unsupported": unsupported,
                "overconfident": _says_see_the_document(source) and not uncertain,
                "allUnsure": _is_all_unsure(result),
            }
        )
        mark = "환각후보" if unsupported else "·"
        print(f"[{row['id']}] {mark} 확인필요={len(uncertain)}건")
        for item in unsupported:
            print(f"        근거 없음: {item['field']} = {item['value']}")

    summary = _summarize(results, old.get("usage") or {}, old.get("model", "unknown"))
    summary["pipeline"] = old.get("pipeline")
    summary["elapsedSeconds"] = old.get("elapsedSeconds")
    summary["rescreenedFrom"] = old.get("generatedAt")
    return summary


def main() -> None:
    parser = argparse.ArgumentParser(description="공고 구조화 평가 (P1-15)")
    parser.add_argument("--dry-run", action="store_true", help="호출하지 않고 입력만 확인한다")
    parser.add_argument(
        "--rescreen",
        action="store_true",
        help="저장된 출력을 다시 선별한다 (호출 없음 · 선별기를 고쳤을 때)",
    )
    parser.add_argument("--out", type=Path, default=REPORT, help="보고서 경로")
    args = parser.parse_args()

    if args.rescreen:
        summary = rescreen(args.out)
    else:
        settings = get_settings()
        summary = asyncio.run(run(settings, dry_run=args.dry_run))
    if not summary:
        return

    args.out.write_text(json.dumps(summary, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(
        f"\n근거 없는 값 {summary['unsupportedValues']}개({summary['unsupportedRows']}건) · "
        f"과신 {summary['overconfidentRate']:.0%} · 전부확인필요 {summary['allUnsureRate']:.0%} · "
        f"{'통과' if summary['passed'] else '미달'}"
    )
    if summary["unsupportedValues"]:
        print("⚠ 위 목록을 원문과 대조하세요. 진짜 환각이면 그 모델은 쓸 수 없습니다.")
    print(f"→ {args.out}")


if __name__ == "__main__":
    main()
