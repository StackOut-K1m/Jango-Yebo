"""품목 정규화 3단 캐스케이드 채점 — §6.5 "규칙이 90% 이상을 처리" 를 실제로 센다.

    # 1) 토큰 0 — 1·2차만 돌려 단계 분포와 오매칭률을 본다
    uv run --frozen python -X utf8 -m experiments.evaluate_item_normalization \
        datasets/receipts/korie/test artifacts/eval/test-final --out artifacts/eval/norm-001

    # 2) 유료 — 애매한 건(유사도 0.70~0.92)에 3차 LLM 을 붙인다. 영수증당 1회 호출(운영과 동일)
    uv run --frozen python -X utf8 -m experiments.evaluate_item_normalization \
        datasets/receipts/korie/test artifacts/eval/test-final --out artifacts/eval/norm-002 \
        --llm --limit 40 --stride 9

표본을 어떻게 만드나 — 실제 운영 모양을 그대로 흉내낸다.
- **사전(`knownItems`)**: 라벨 품목명을 `normalize_name` 키로 묶어 만든 사업장 품목 마스터.
  같은 키의 다른 표기는 `aliases` 로 넣는다(§6.5 "aliases 를 먼저 채운다"). 전 영수증에서 모은
  전역 사전이라 **다른 상품이 방해물로 섞인다** — 오매칭 위험을 재려면 이게 있어야 한다.
- **입력(`rawNames`)**: 그 영수증에서 **OCR 이 실제로 읽은** 품목명(캐시된 예측).
  실제 오독 노이즈가 그대로 들어 있다.
- **정답**: 채점기(`metrics.match_items`)가 같은 영수증 안에서 라벨 행과 예측 행을 1:1 로 맞춘
  결과. 짝의 라벨 이름이 속한 사전 항목이 정답 `itemId` 다.
- **미매칭 정답**: 사전에서 일부(`--withhold`)를 일부러 빼 "아직 사전에 없는 신규 품목" 을 만든다.
  짝이 없는 예측 행(환각·신규)도 미매칭이 정답이다.

한계 — 정답 짝짓기가 이름 유사도 0.8 이상에서만 성립하므로(채점기 규칙), **심한 오독은 표본에
아예 들어오지 않는다.** 여기 수치는 "읽기는 그럭저럭 읽은 이름을 사전에 붙일 수 있나" 에 대한
답이고, 오독 자체의 난이도는 증빙 추출 쪽 `품목명 exact`(test 359장 80.0%) 가 답한다.
"""

from __future__ import annotations

import argparse
import asyncio
import json
import re
import time
from collections import Counter, defaultdict
from dataclasses import dataclass, field
from pathlib import Path
from typing import Any

from rapidfuzz import fuzz, utils

from app.common.claude import ClaudeClient
from app.config import Settings
from app.ocr.llm_normalization import (
    NORMALIZATION_PROMPT_VERSION,
    merge_decisions,
    resolve_candidates,
)
from app.ocr.normalization import (
    AUTO_MATCH_THRESHOLD,
    LLM_CANDIDATE_THRESHOLD,
    match_items,
    normalize_name,
)
from app.ocr.schemas import ItemMapping, KnownItem
from experiments import metrics as m
from experiments.evaluate_evidence import PredictionRecord, total_cost_usd
from experiments.labels import LabelRecord, iter_labels

# 사전 크기. 소상공인 품목 마스터의 현실적인 규모이고, 너무 크면 방해물이 비현실적으로 많아진다.
DICTIONARY_SIZE = 200

# 사전 묶음 키에서 벗기는 것 — **상품 정체성을 바꾸지 않는 꼬리만** 벗긴다.
#
# ① 등급 `(특)` `(중)`: 같은 상품의 품질 등급이다.
# ② 선행 브랜드 `삼립)` `남양)`: 한국 영수증이 찍는 접두사다. 프롬프트도 브랜드가 사전 이름의
#    하위면 같은 상품으로 보라고 지시한다.
#
# **포장·규격 숫자는 벗기지 않는다.** 처음엔 `가지(특) 3개/1팩` 을 `가지` 로 합치려고 `N개`·`N롤`
# 같은 꼬리를 벗겼는데, 그러면 `점보롤 화장지 16롤` 과 `30롤` 도 합쳐진다 — 그건 실제로 다른
# 상품(다른 SKU)이다. 게다가 그 병합은 원래 목표(3차 채점)를 해결하지도 못했다. `가지(특)` 의
# 정답이 None 이던 진짜 이유는 **KORIE 품목 라벨이 부분 라벨**이라 그 행이 라벨에 아예 없던
# 것이었다(2026-09-09 확인). 그래서 되돌렸다 — 벗기는 규칙은 적을수록 안전하다.
_GRADE_TOKENS = frozenset({"특", "상", "중", "하", "대", "소", "특품", "중품", "상품"})
# 괄호를 포함하지 않는 토큰만 브랜드로 본다 — `^\S+\)` 로 두면 `가지(특)` 전체를 먹는다.
_BRAND_PREFIX = re.compile(r"^[^\s()]{1,10}\)\s*")


def dictionary_key(raw: str) -> str:
    """사전 묶음 키 — 정규화 후 선행 브랜드와 등급 토큰만 벗긴다. 남는 게 없으면 원래 키."""
    base = normalize_name(_BRAND_PREFIX.sub("", raw.strip()))
    kept = [token for token in base.split() if token not in _GRADE_TOKENS]
    return " ".join(kept) or base


@dataclass
class Task:
    """영수증 1건 = 20.5 요청 1건."""

    source_id: str
    raw_names: list[str]
    expected: dict[str, int | None]  # rawName → 정답 itemId (없으면 미매칭이 정답)


@dataclass
class Outcome:
    """rawName 하나의 판정 결과."""

    stage: str  # RULE / SIMILARITY / LLM / UNMATCHED
    correct: bool
    mismatched: bool  # 틀린 itemId 에 붙었다 — 미매칭보다 나쁘다
    missed: bool  # 정답이 있는데 미매칭으로 뒀다
    matchable: bool  # 사전에 정답 항목이 있었나. False 면 미매칭이 정답이다


@dataclass
class Report:
    outcomes: list[Outcome] = field(default_factory=list)
    llm_candidates: int = 0
    receipts_with_candidates: int = 0
    llm_decided: int = 0  # 3차가 itemId 를 확정한 수
    llm_correct: int = 0
    llm_calls: int = 0
    # 3차 후보 하나하나의 판정 기록. 오매칭이 모델 탓인지 채점 사전의 결함인지 가리려면
    # 정답 항목의 '이름'까지 나란히 봐야 한다 (val 에서만 들여다본다 — test 는 오염)
    candidate_log: list[dict[str, Any]] = field(default_factory=list)
    llm_elapsed_ms: list[int] = field(default_factory=list)


def build_dictionary(labels: list[LabelRecord], *, size: int = DICTIONARY_SIZE) -> list[KnownItem]:
    """라벨 품목명 → 사업장 품목 마스터. 정규화 키로 묶고 다른 표기는 aliases 로.

    정규화 **문자열**만으로 묶으면 같은 상품이 여러 항목으로 쪼개진다 — `가지` 와
    `가지(특) 3개/1팩` 이 서로 다른 itemId 가 되어 "정답 itemId" 자체가 성립하지 않는다
    (val 손검증에서 확인). 품목 마스터에 같은 상품이 두 번 있는 것은 우리 채점기의 결함이므로
    가까운 키를 하나로 합친다. **주의**: 합치는 임계값이 2차 자동매칭과 같은 0.92 라서
    2차 처리율은 낙관적으로 편향된다 — 이 수치는 상한으로 읽는다.
    """
    groups: dict[str, Counter[str]] = defaultdict(Counter)
    for label in labels:
        for item in label.extraction.items:
            key = dictionary_key(item.raw_name)
            if key:
                groups[key][item.raw_name] += 1
    ranked = sorted(groups.items(), key=lambda kv: (-sum(kv[1].values()), kv[0]))
    merged: list[tuple[str, Counter[str]]] = []
    for key, surfaces in ranked:
        hit = next(
            (
                bucket
                for head, bucket in merged
                if fuzz.WRatio(key, head, processor=utils.default_process) / 100
                >= AUTO_MATCH_THRESHOLD
            ),
            None,
        )
        if hit is None:
            merged.append((key, surfaces.copy()))
        else:
            hit.update(surfaces)
        if len(merged) >= size:
            break
    known = []
    for item_id, (_key, surfaces) in enumerate(merged[:size], start=1):
        canonical, *rest = [name for name, _ in surfaces.most_common()]
        known.append(KnownItem(item_id=item_id, canonical_name=canonical, aliases=rest))
    return known


def build_tasks(
    dataset: Path,
    run: Path,
    *,
    withhold: float,
    size: int,
    use_labels: bool = False,
    withhold_names: tuple[str, ...] = (),
) -> tuple[list[Task], list[KnownItem], dict[str, Any]]:
    """`use_labels` 면 rawNames 를 라벨 품목명에서 만든다 — OCR 을 거치지 않는다.

    3차 LLM 의 **판정 품질**만 재고 싶을 때 쓴다. 예측을 쓰면 OCR 오독까지 섞여 무엇을 재는지
    흐려지고 추출 비용(장당 $0.0106)도 든다. 이 모드는 라벨만 읽어 **추출 비용이 0** 이다.
    파이프라인 전체 성능은 예측을 쓰는 기본 모드로 재고, 이 모드는 3차 전용이다.

    `withhold_names` 는 사전에서 **지목해서** 빼는 항목이다. 비율(`withhold`)만 쓰면 어느 항목이
    빠지는지가 itemId 나머지로 정해져, 이름이 비슷한 짝(`서울우유 1L` ↔ `서울우유 500ml`)을
    우연히 하나만 맞춘다. 3차 거절 능력을 재려면 짝의 한쪽을 의도적으로 빼야 한다.
    """
    labels = [label for _, label in iter_labels(dataset)]
    dictionary = build_dictionary(labels, size=size)
    # 정규화 키 → itemId. 사전을 만든 키만 매칭 가능하다
    by_key: dict[str, int] = {}
    for item in dictionary:
        for surface in (item.canonical_name, *item.aliases):
            by_key.setdefault(dictionary_key(surface), item.item_id)
    # 일부를 "아직 사전에 없는 품목" 으로 뺀다. itemId 로 나눠 실행마다 같은 집합이 되게
    step = max(1, round(1 / withhold)) if withhold > 0 else 0
    withheld = {item.item_id for item in dictionary if step and item.item_id % step == 0}
    if withhold_names:
        withheld |= {
            item.item_id
            for item in dictionary
            for needle in withhold_names
            if needle and needle in item.canonical_name
        }
    offered = [item for item in dictionary if item.item_id not in withheld]

    tasks: list[Task] = []
    for label in labels:
        if use_labels:
            if "items" in set(label.meta.unlabeled_fields) or not label.extraction.items:
                continue
            direct: dict[str, int | None] = {}
            for item in label.extraction.items:
                found = by_key.get(dictionary_key(item.raw_name))
                direct[item.raw_name] = None if found in withheld else found
            names = [i.raw_name for i in label.extraction.items if i.raw_name]
            tasks.append(Task(label.meta.source_id, names, direct))
            continue
        path = run / "predictions" / f"{label.meta.source_id}.json"
        if not path.exists() or "items" in set(label.meta.unlabeled_fields):
            continue
        record = PredictionRecord.model_validate_json(path.read_text(encoding="utf-8"))
        if record.extraction is None or not record.extraction.items:
            continue
        pairs = m.match_items(label.extraction.items, record.extraction.items)
        truth: dict[str, int | None] = {}
        for gt, pred in pairs.matched:
            item_id = by_key.get(dictionary_key(gt.raw_name))
            truth[pred.raw_name] = None if item_id in withheld else item_id
        for extra in pairs.hallucinated:  # 짝이 없는 예측 행 — 미매칭이 정답
            truth.setdefault(extra.raw_name, None)
        raw_names = [item.raw_name for item in record.extraction.items if item.raw_name]
        if raw_names:
            tasks.append(Task(label.meta.source_id, raw_names, truth))

    meta = {
        "dictionaryTotal": len(dictionary),
        "dictionaryOffered": len(offered),
        "dictionaryWithheld": len(withheld),
        "aliasesFilled": sum(1 for item in dictionary if item.aliases),
    }
    return tasks, offered, meta


def _judge(mapping: ItemMapping, expected: int | None, stage: str) -> Outcome:
    matched = mapping.matched_item_id
    matchable = expected is not None
    if matched is None:
        return Outcome(
            stage, not matchable, mismatched=False, missed=matchable, matchable=matchable
        )
    if matched == expected:
        return Outcome(stage, True, mismatched=False, missed=False, matchable=matchable)
    return Outcome(stage, False, mismatched=True, missed=False, matchable=matchable)


def _stage_of(mapping: ItemMapping) -> str:
    return mapping.matched_by or "UNMATCHED"


async def score_task(
    task: Task,
    dictionary: list[KnownItem],
    *,
    claude: ClaudeClient | None,
    settings: Settings,
    report: Report,
    auto_threshold: float = AUTO_MATCH_THRESHOLD,
) -> list[ItemMapping]:
    mappings, candidates = match_items(task.raw_names, dictionary, auto_threshold=auto_threshold)
    report.llm_candidates += len(candidates)
    report.receipts_with_candidates += 1 if candidates else 0
    names = {item.item_id: item.canonical_name for item in dictionary}
    if candidates and claude is not None:
        started = time.monotonic()
        decisions = await resolve_candidates(claude, settings, candidates, dictionary)
        report.llm_calls += 1
        report.llm_elapsed_ms.append(round((time.monotonic() - started) * 1000))
        before = {mp.raw_name: mp.matched_item_id for mp in mappings}
        mappings = merge_decisions(mappings, decisions)
        for mp in mappings:
            if mp.matched_by == "LLM" and before.get(mp.raw_name) is None:
                report.llm_decided += 1
                if mp.matched_item_id == task.expected.get(mp.raw_name):
                    report.llm_correct += 1
        by_raw = {mp.raw_name: mp for mp in mappings}
        for cand in candidates:
            mp = by_raw.get(cand.raw_name)
            expected = task.expected.get(cand.raw_name)
            report.candidate_log.append(
                {
                    "sourceId": task.source_id,
                    "rawName": cand.raw_name,
                    "candidate": cand.canonical_name,
                    "similarity": cand.score,
                    "llmMatchedItemId": mp.matched_item_id if mp else None,
                    "llmMatchedName": names.get(mp.matched_item_id) if mp else None,
                    "llmConfidence": mp.confidence if mp else None,
                    "expectedItemId": expected,
                    "expectedName": names.get(expected) if expected else None,
                    "suggested": mp.suggested_canonical_name if mp else None,
                }
            )
    for mp in mappings:
        if mp.raw_name in task.expected:
            report.outcomes.append(_judge(mp, task.expected[mp.raw_name], _stage_of(mp)))
    return mappings


def aggregate(
    report: Report, meta: dict[str, Any], *, auto_threshold: float = AUTO_MATCH_THRESHOLD
) -> dict[str, Any]:
    n = len(report.outcomes)
    stages = Counter(o.stage for o in report.outcomes)
    rule_like = stages["RULE"] + stages["SIMILARITY"]
    # §6.5 의 분모는 "사전에 정답이 있는 건" 이다. 신규 품목은 미매칭이 정답이라 3단 캐스케이드가
    # 처리할 대상이 아니다 — 전체를 분모로 쓰면 사전이 작을수록 처리율이 낮게 나온다
    matchable = [o for o in report.outcomes if o.matchable]
    solved = [o for o in matchable if o.correct and o.stage != "UNMATCHED"]
    by_rule = [o for o in solved if o.stage in ("RULE", "SIMILARITY")]
    # 오매칭 두 종류: 사전에 정답이 있었는데 다른 항목에 붙인 것(위험) vs 정답이 사전에 없어
    # 비슷한 다른 항목에 붙은 것(--withhold 로 만든 상황. 운영에서는 그 항목이 사전에 있다)
    wrong_item = sum(o.mismatched and o.matchable for o in report.outcomes)
    wrong_new = sum(o.mismatched and not o.matchable for o in report.outcomes)
    return {
        "promptVersion": NORMALIZATION_PROMPT_VERSION,
        "autoMatchThreshold": auto_threshold,
        "llmCandidateThreshold": LLM_CANDIDATE_THRESHOLD,
        **meta,
        "rawNames": n,
        "stages": {key: stages[key] for key in ("RULE", "SIMILARITY", "LLM", "UNMATCHED")},
        # §6.5 의 주장: 규칙(1차) + 유사도(2차) 가 90% 이상을 처리한다
        "ruleHandledRate": round(rule_like / n, 4) if n else None,
        "matchableRawNames": len(matchable),
        "solvedRate": round(len(solved) / len(matchable), 4) if matchable else None,
        "ruleHandledRateOnMatchable": round(len(by_rule) / len(solved), 4) if solved else None,
        "mismatchWrongItemRate": round(wrong_item / n, 4) if n else None,
        "mismatchNewItemRate": round(wrong_new / n, 4) if n else None,
        "accuracy": round(sum(o.correct for o in report.outcomes) / n, 4) if n else None,
        # 오매칭이 매칭 실패보다 나쁘다 (§6.5) — 이 값이 0 에 가까워야 한다
        "mismatchRate": round(sum(o.mismatched for o in report.outcomes) / n, 4) if n else None,
        "missedRate": round(sum(o.missed for o in report.outcomes) / n, 4) if n else None,
        "llm": {
            "candidates": report.llm_candidates,
            "receiptsWithCandidates": report.receipts_with_candidates,
            "calls": report.llm_calls,
            "decided": report.llm_decided,
            "correct": report.llm_correct,
            "meanElapsedMs": round(sum(report.llm_elapsed_ms) / len(report.llm_elapsed_ms))
            if report.llm_elapsed_ms
            else None,
        },
    }


def format_markdown(result: dict[str, Any]) -> str:
    stages, llm = result["stages"], result["llm"]
    total = result["rawNames"] or 1

    def pct(key: str) -> str:
        return f"{stages[key] / total:.1%} ({stages[key]})"

    lines = [
        f"# 품목 정규화 채점 — rawName {result['rawNames']}건, "
        f"사전 {result['dictionaryOffered']}개 (숨김 {result['dictionaryWithheld']}개, "
        f"aliases 있는 항목 {result['aliasesFilled']}개)",
        "",
        "| 처리 단계 | 비율 |",
        "| --- | ---: |",
        f"| 1차 규칙 (정규화 후 동일) | {pct('RULE')} |",
        f"| 2차 유사도 (≥ {result['autoMatchThreshold']}) | {pct('SIMILARITY')} |",
        f"| 3차 LLM | {pct('LLM')} |",
        f"| 미매칭 (신규 품목 제안) | {pct('UNMATCHED')} |",
        "",
        f"전체 정확도 {result['accuracy']:.1%} "
        f"(미매칭이 정답인 신규 품목 {result['rawNames'] - result['matchableRawNames']}건 포함)",
        "",
        f"**사전에 정답이 있는 {result['matchableRawNames']}건** 중 붙인 것이 "
        f"{result['solvedRate']:.1%}, 그중 규칙+유사도가 처리한 비율 = "
        f"**{result['ruleHandledRateOnMatchable']:.1%}** (§6.5 주장: 90% 이상)",
        "",
        f"오매칭 — 사전에 정답이 있는데 다른 항목에 붙임 "
        f"**{result['mismatchWrongItemRate']:.1%}**, 정답이 사전에 없어 비슷한 항목에 붙임 "
        f"{result['mismatchNewItemRate']:.1%} · 놓친 매칭 {result['missedRate']:.1%}",
        "",
        "| 3차 LLM | 값 |",
        "| --- | ---: |",
        f"| 후보 (유사도 {result['llmCandidateThreshold']}~{result['autoMatchThreshold']}) "
        f"| {llm['candidates']}건 ({llm['receiptsWithCandidates']}장) |",
        f"| 호출 수 (영수증당 1회) | {llm['calls']} |",
        f"| 확정 | {llm['decided']}건 |",
        f"| 확정 중 정답 | {llm['correct']}건 |",
        f"| 평균 응답 | {llm['meanElapsedMs']} ms |",
    ]
    return "\n".join(lines)


async def run(args: argparse.Namespace) -> int:
    settings = Settings()
    tasks, dictionary, meta = build_tasks(
        args.dataset,
        args.run,
        withhold=args.withhold,
        size=args.dictionary_size,
        use_labels=args.use_labels,
        withhold_names=tuple(n.strip() for n in args.withhold_names.split(",") if n.strip()),
    )
    if args.only_candidates:
        # 후보가 없는 영수증은 LLM 을 부르지 않으므로 유료 실행에서 표본만 희석시킨다
        tasks = [
            t
            for t in tasks
            if match_items(t.raw_names, dictionary, auto_threshold=args.auto_threshold)[1]
        ]
    tasks = tasks[:: args.stride][: args.limit] if args.limit else tasks[:: args.stride]
    if not tasks:
        print("[실패] 채점할 영수증이 없습니다. evaluate_evidence 를 먼저 돌리세요.")
        return 2

    claude = ClaudeClient(settings) if args.llm else None
    if claude is not None:
        print(
            f"3차 LLM 켬 — 모델 {settings.normalization_model}, "
            f"effort {settings.normalization_effort}"
        )
    report = Report()
    for index, task in enumerate(tasks, start=1):
        await score_task(
            task,
            dictionary,
            claude=claude,
            settings=settings,
            report=report,
            auto_threshold=args.auto_threshold,
        )
        spent = total_cost_usd(claude) if claude is not None else None
        if index % 20 == 0 or index == len(tasks):
            suffix = f", 추정 ${spent}" if spent is not None else ""
            print(f"  {index}/{len(tasks)}{suffix}")
        if spent is not None and spent >= args.budget_usd:
            print(f"[중단] 비용 상한 ${args.budget_usd} 도달 — {index}/{len(tasks)} 에서 멈춤")
            break

    result = aggregate(report, meta, auto_threshold=args.auto_threshold)
    result["receipts"] = len(tasks)
    result["model"] = settings.normalization_model if claude is not None else None
    result["estimatedCostUsd"] = total_cost_usd(claude) if claude is not None else None
    if claude is not None:
        result["usage"] = {
            model: totals.snapshot() for model, totals in claude.usage_by_model.items()
        }

    args.out.mkdir(parents=True, exist_ok=True)
    (args.out / "metrics.json").write_text(
        json.dumps(result, ensure_ascii=False, indent=2) + "\n", encoding="utf-8"
    )
    if report.candidate_log:
        with (args.out / "llm-candidates.jsonl").open("w", encoding="utf-8") as handle:
            for row in report.candidate_log:
                handle.write(json.dumps(row, ensure_ascii=False) + "\n")
    summary = format_markdown(result)
    (args.out / "summary.md").write_text(summary + "\n", encoding="utf-8")
    print()
    print(summary)
    if result["estimatedCostUsd"] is not None:
        print(f"\n비용 추정 ${result['estimatedCostUsd']} ({report.llm_calls}회 호출)")
    return 0


def main() -> int:
    parser = argparse.ArgumentParser(
        description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter
    )
    parser.add_argument("dataset", type=Path, help="라벨 폴더")
    parser.add_argument(
        "run",
        type=Path,
        nargs="?",
        default=Path("."),
        help="evaluate_evidence 의 --out (캐시된 예측). --use-labels 면 필요 없다",
    )
    parser.add_argument(
        "--use-labels",
        action="store_true",
        help="rawNames 를 라벨에서 만든다 (OCR 거치지 않음, 추출 비용 0). 3차 판정만 잴 때",
    )
    parser.add_argument(
        "--withhold-names",
        default="",
        help="사전에서 지목해서 뺄 항목 (쉼표 구분, 부분 문자열). 짝의 한쪽을 빼 3차 거절을 잰다",
    )
    parser.add_argument("--out", type=Path, required=True)
    parser.add_argument(
        "--llm", action="store_true", help="3차 LLM 을 실제로 부른다 (유료). 기본은 1·2차만"
    )
    parser.add_argument("--limit", type=int, default=None)
    parser.add_argument(
        "--only-candidates",
        action="store_true",
        help="3차 후보가 있는 영수증만 채점 — 유료 실행에서 호출 1회당 표본을 최대로",
    )
    parser.add_argument("--stride", type=int, default=1)
    parser.add_argument(
        "--withhold",
        type=float,
        default=0.2,
        help="사전에서 빼 '신규 품목' 으로 둘 비율 (기본 0.2). 미매칭 정답을 만들기 위한 것",
    )
    parser.add_argument("--dictionary-size", type=int, default=DICTIONARY_SIZE)
    parser.add_argument(
        "--auto-threshold",
        type=float,
        default=AUTO_MATCH_THRESHOLD,
        help=f"2차 자동매칭 임계값 (기본 {AUTO_MATCH_THRESHOLD}). 올리면 오매칭 대신 놓침이 는다",
    )
    parser.add_argument("--budget-usd", type=float, default=1.0, help="실행당 비용 상한 (기본 1.0)")
    return asyncio.run(run(parser.parse_args()))


if __name__ == "__main__":
    raise SystemExit(main())
