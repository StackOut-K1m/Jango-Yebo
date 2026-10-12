"""거래 분류 채점 — Macro F1 · 카테고리별 P/R · 수락률 (명세 10.2 통과 기준).

    uv run --frozen python -X utf8 -m evals.run_eval p1-01
    uv run --frozen python -X utf8 -m evals.run_eval p1-01 --repeat 3 --budget-usd 1
    uv run --frozen python -X utf8 -m evals.run_eval p1-01 --dry-run     # 모델 호출 없음

**유료다.** 300건이면 배치 15회다. 실행 전에 `--dry-run` 으로 배치 구성과 예상 비용을 본다.
`--budget-usd` 에 닿으면 멈추고 거기까지의 결과로 채점한다(기본 $2).

## 왜 점수를 두 벌 내는가

**거래 단위**가 명세 10.2 의 기준이고, **거래처 단위**가 운영에 가깝다.

루트 `CLAUDE.md` 도메인 규칙 3 — *"확정된 거래처 ↔ 분류 매핑은 DB 에 캐시해서 재사용한다.
LLM 호출은 처음 보는 패턴에만."* 즉 운영에서 `동서식품` 9건은 LLM 에 한 번만 간다. 거래
단위로 채점하면 그 9건을 9번 세어 **반복이 많은 거래처가 점수를 지배**하고, 한 번 틀리면
운영에서는 9건이 다 틀리는데 채점에서는 1/300 로 희석된다.

## 왜 반복 실행이 필요한가

`experiments/p1-01-거래분류.md` 실측 — 같은 입력 3회에 19건 중 2건이 매번 달랐다.
**1회 결과로 판정하면 안 된다.** `--repeat` 을 주면 회차별 점수와 편차를 함께 낸다.

## 배치는 날짜순이다

운영에서 백엔드가 거래를 날짜순으로 모아 20건씩 보낸다. 그래야 「같은 날 여러 명에게
개인 이름 출금 = 급여」 같은 배치 내 맥락이 살아난다. 적요별로 묶어 보내면 실제보다
쉬운 문제를 재게 된다.

## 이 점수를 어떻게 인용하는가

평가 세트가 **합성 데이터**라 실제보다 높게 나온다(`evals/p1-01/라벨_분포표.md`).
**프롬프트 A/B 비교에 쓰고 절대 판정에는 쓰지 않는다.** 리포트에도 그 경고를 함께 적는다.
"""

from __future__ import annotations

import argparse
import asyncio
import json
import statistics
import sys
import time
from collections import Counter, defaultdict
from datetime import UTC, datetime
from pathlib import Path
from typing import Any

from app.common.claude import ClaudeClient, ClaudeError
from app.config import Settings
from app.nlp.pipeline import ClassificationError, ClaudeClassifier
from app.nlp.schemas import (
    BATCH_MAX,
    CATEGORY_DIRECTION,
    BusinessContext,
    TransactionClassificationRequest,
    invalid_results,
)
from evals import labels
from evals.labels import LabelRecord
from evals.sample import AMOUNT_BANDS

EVALS_ROOT = Path(__file__).resolve().parent
OUT_ROOT = EVALS_ROOT.parent / "artifacts" / "eval"

# §20.3 — API 서버가 이 값 미만이면 needsConfirmation 을 세워 사용자 확인 큐로 보낸다.
CONFIRM_THRESHOLD = 0.85

# 통과 기준 (명세 10.2). 미달은 `tests/nlp/test_eval_report.py` 가 실패로 잡는다.
PASS_MACRO_F1 = 0.85
PASS_ACCEPTANCE = 0.80

# 비용 추정용 단가(USD / 1M 토큰). **모델 가격은 바뀐다** — 리포트에 모델명과 토큰 수를
# 함께 남기므로, 단가가 어긋나도 토큰 수로 다시 계산할 수 있다.
PRICES = {
    "claude-haiku-4-5": (1.0, 5.0),
    "claude-haiku-4-5-20251001": (1.0, 5.0),
    "claude-sonnet-5": (2.0, 10.0),
    "claude-opus-5": (5.0, 25.0),
    "claude-opus-4-8": (5.0, 25.0),
}


def amount_band(amount: int) -> int:
    for index, upper in enumerate(AMOUNT_BANDS):
        if amount < upper:
            return index
    return len(AMOUNT_BANDS)


def merchant_key(record: LabelRecord) -> tuple[str, str, int]:
    """운영에서 거래처 사전이 캐시하는 단위. `evals.triage` 와 같은 기준이다."""
    return (record.description, record.direction, amount_band(record.amount))


def estimate_cost(model: str, input_tokens: int, output_tokens: int) -> float | None:
    for name, (pin, pout) in PRICES.items():
        if model.startswith(name):
            return input_tokens * pin / 1e6 + output_tokens * pout / 1e6
    return None


def build_batches(records: list[LabelRecord], size: int) -> list[list[LabelRecord]]:
    """날짜순으로 잘라 배치를 만든다 — 운영에서 백엔드가 보내는 모양이다."""
    ordered = sorted(records, key=lambda r: (r.occurred_at, r.transaction_id))
    return [ordered[i : i + size] for i in range(0, len(ordered), size)]


def to_request(batch: list[LabelRecord], job_id: int) -> TransactionClassificationRequest:
    """라벨 레코드를 워커 요청으로.

    **`category` 는 싣지 않는다** — 정답을 보내면 채점이 무의미하다.
    """
    return TransactionClassificationRequest.model_validate(
        {
            "jobId": job_id,
            "business": BusinessContext(industry_type=batch[0].industry_type).model_dump(
                by_alias=True
            ),
            # 업종별 부분집합이 아니라 23종 전부를 준다. 부분집합은 백엔드가 정하는 값이고,
            # 여기서 좁히면 모델이 아니라 내 목록을 재게 된다.
            "allowedCategories": list(CATEGORY_DIRECTION),
            # few-shot 은 비운다 — 정정 이력이 없는 최초 상태가 이 모양이다 (§6.3).
            # few-shot 효과를 재려면 별도 실행으로 A/B 한다.
            "fewShotExamples": [],
            "transactions": [
                {
                    "transactionId": r.transaction_id,
                    "description": r.description,
                    "direction": r.direction,
                    "amount": r.amount,
                    "occurredAt": r.occurred_at.isoformat(),
                    "cycleHint": r.cycle_hint,
                }
                for r in batch
            ],
        }
    )


def macro_f1(truth: dict[int, str], pred: dict[int, str]) -> tuple[float, dict[str, dict]]:
    """카테고리별 P/R/F1 과 그 평균.

    **정답에 등장하는 카테고리만 평균에 넣는다.** 표본에 없는 카테고리를 0 으로 세면
    평균이 표본 구성에 좌우된다 — `evals.check` 가 "표본에 없는 카테고리는 채점에서
    제외된다"고 알리는 것과 같은 규칙이다.
    """
    present = sorted(set(truth.values()))
    per: dict[str, dict] = {}
    scores = []
    for category in present:
        tp = sum(1 for i, t in truth.items() if t == category and pred.get(i) == category)
        fp = sum(1 for i, p in pred.items() if p == category and truth.get(i) != category)
        fn = sum(1 for i, t in truth.items() if t == category and pred.get(i) != category)
        precision = tp / (tp + fp) if tp + fp else 0.0
        recall = tp / (tp + fn) if tp + fn else 0.0
        f1 = 2 * precision * recall / (precision + recall) if precision + recall else 0.0
        per[category] = {
            "support": tp + fn,
            "precision": round(precision, 4),
            "recall": round(recall, 4),
            "f1": round(f1, 4),
        }
        scores.append(f1)
    return (sum(scores) / len(scores) if scores else 0.0), per


def score_run(
    records: list[LabelRecord],
    predictions: dict[int, dict[str, Any]],
) -> dict[str, Any]:
    """한 회차의 점수. 거래 단위와 거래처 단위를 함께 낸다."""
    truth = {r.transaction_id: r.category for r in records if r.category}
    pred = {i: p["category"] for i, p in predictions.items()}
    scored = {i: t for i, t in truth.items() if i in pred}

    tx_macro, per_category = macro_f1(scored, pred)
    correct = sum(1 for i, t in scored.items() if pred[i] == t)

    # 수락률 — 명세가 정의를 안 줘서 여기서 정한다.
    #
    # §20.3 은 confidence < 0.85 인 결과에 needsConfirmation 을 세워 사용자 확인 큐로
    # 보낸다. 그러니 **사용자가 그냥 받아들이는 것은 임계 이상인 건**이고, 수락률은
    # 「자동 확정된 건 중 정답과 일치하는 비율」로 읽는 것이 규격과 맞물린다.
    # 자신 있게 틀리는 것이 가장 나쁜 실패 모드이고, 이 지표가 그것을 잡는다.
    auto = [i for i in scored if predictions[i]["confidence"] >= CONFIRM_THRESHOLD]
    auto_correct = sum(1 for i in auto if pred[i] == truth[i])
    acceptance = auto_correct / len(auto) if auto else 0.0

    # 거래처 단위 — 운영에서 LLM 이 실제로 판단하는 횟수 (도메인 규칙 3)
    by_merchant: dict[tuple, list[int]] = defaultdict(list)
    for r in records:
        if r.transaction_id in scored:
            by_merchant[merchant_key(r)].append(r.transaction_id)
    m_truth, m_pred = {}, {}
    for index, (_key, ids) in enumerate(sorted(by_merchant.items())):
        m_truth[index] = truth[ids[0]]
        # 그 거래처의 예측 중 최빈값 — 캐시에 굳는 값에 해당한다
        m_pred[index] = Counter(pred[i] for i in ids).most_common(1)[0][0]
    merchant_macro, _ = macro_f1(m_truth, m_pred)
    merchant_correct = sum(1 for i in m_truth if m_pred[i] == m_truth[i])

    return {
        "scored": len(scored),
        "transaction": {
            "macroF1": round(tx_macro, 4),
            "accuracy": round(correct / len(scored), 4) if scored else 0.0,
            "correct": correct,
        },
        "merchant": {
            "macroF1": round(merchant_macro, 4),
            "accuracy": round(merchant_correct / len(m_truth), 4) if m_truth else 0.0,
            "groups": len(m_truth),
            "correct": merchant_correct,
        },
        "acceptance": {
            "rate": round(acceptance, 4),
            "autoConfirmed": len(auto),
            "autoCorrect": auto_correct,
            "needsConfirmation": len(scored) - len(auto),
        },
        "perCategory": per_category,
    }


async def run_once(
    settings: Settings,
    claude: ClaudeClient,
    batches: list[list[LabelRecord]],
    budget_usd: float,
) -> tuple[dict[int, dict[str, Any]], dict[str, Any]]:
    classifier = ClaudeClassifier(settings, claude)
    predictions: dict[int, dict[str, Any]] = {}
    discarded_total, failed_batches, stopped = 0, 0, None
    before = claude.usage.snapshot()
    started = time.perf_counter()

    for number, batch in enumerate(batches, start=1):
        now = claude.usage.snapshot()
        spent = estimate_cost(
            settings.classification_model,
            now["input_tokens"] - before["input_tokens"],
            now["output_tokens"] - before["output_tokens"],
        )
        if spent is not None and spent >= budget_usd:
            stopped = f"예산 ${budget_usd} 도달 — 배치 {number - 1}/{len(batches)} 까지만 채점"
            print(f"\n[중단] {stopped}")
            break

        request = to_request(batch, job_id=number)
        try:
            results, _meta = await classifier.classify(request)
        except (ClassificationError, ClaudeError) as exc:
            failed_batches += 1
            print(f"  배치 {number}/{len(batches)} 실패: {exc}")
            continue

        # 라우터가 거는 것과 같은 폐기 검사. 폐기된 건은 예측 없음으로 남는다.
        bad = invalid_results(request, results)
        discarded_total += len(bad)
        for r in results:
            if r.transaction_id not in bad:
                predictions[r.transaction_id] = {
                    "category": r.category,
                    "confidence": r.confidence,
                    "merchantName": r.merchant_name,
                    "reason": r.reason,
                }
        done = len(predictions)
        print(f"  배치 {number}/{len(batches)} · 누적 예측 {done}건", flush=True)

    after = claude.usage.snapshot()
    used = {k: after[k] - before[k] for k in after}
    meta = {
        "elapsedSec": round(time.perf_counter() - started, 1),
        "usage": used,
        "estimatedUsd": estimate_cost(
            settings.classification_model, used["input_tokens"], used["output_tokens"]
        ),
        "discarded": discarded_total,
        "failedBatches": failed_batches,
        "stopped": stopped,
    }
    return predictions, meta


def print_report(report: dict[str, Any]) -> None:
    agg = report["aggregate"]
    print("\n" + "=" * 72)
    print(f"[{report['dataset']}] {report['model']} · 프롬프트 {report['promptVersion']}")
    print(f"거래 {report['labeled']}건 · 배치 {report['batches']}회 · {report['repeat']}회 반복")
    print("=" * 72)
    for label, key, threshold in (
        ("Macro F1 (거래 단위)", "transactionMacroF1", PASS_MACRO_F1),
        ("Macro F1 (거래처 단위)", "merchantMacroF1", None),
        ("수락률", "acceptanceRate", PASS_ACCEPTANCE),
        ("정확도 (거래 단위)", "transactionAccuracy", None),
    ):
        stat = agg[key]
        mark = ""
        if threshold is not None:
            mark = "  ✅ 통과" if stat["mean"] >= threshold else f"  ✗ 기준 {threshold}"
        spread = f" ± {stat['stdev']:.4f}" if stat["stdev"] else ""
        print(f"  {label:<22} {stat['mean']:.4f}{spread}{mark}")

    print(
        f"\n  자동 확정 {agg['autoConfirmed']['mean']:.0f}건 · "
        f"확인 필요 {agg['needsConfirmation']['mean']:.0f}건 (임계 {CONFIRM_THRESHOLD})"
    )
    cost = report["totalEstimatedUsd"]
    print(
        f"  지연 {report['totalElapsedSec']:.0f}초 · "
        f"비용 {f'약 ${cost:.4f}' if cost is not None else '단가 미상'}"
    )

    worst = sorted(report["runs"][-1]["perCategory"].items(), key=lambda kv: kv[1]["f1"])[:5]
    print("\n  가장 낮은 카테고리 5종 (마지막 회차)")
    for category, m in worst:
        print(
            f"    {category:<22} F1 {m['f1']:.3f}  P {m['precision']:.3f}  "
            f"R {m['recall']:.3f}  (n={m['support']})"
        )
    print(f"\n  ⚠ {report['caveat']}")


async def main_async(args: argparse.Namespace) -> int:
    path = EVALS_ROOT / args.dataset / args.file
    if not path.exists():
        print(f"라벨 파일이 없습니다: {path}")
        return 1
    records = [r for r in labels.load(path) if r.labeled]
    if not records:
        print("라벨이 하나도 채워지지 않았습니다 — 먼저 evals.triage 로 채우세요.")
        return 1

    batches = build_batches(records, args.batch_size)
    settings = Settings()
    if args.model:
        settings.classification_model = args.model

    print(
        f"[{args.dataset}] 라벨 {len(records)}건 → 배치 {len(batches)}회 "
        f"(날짜순 {args.batch_size}건씩) × {args.repeat}회 반복"
    )
    print(f"모델 {settings.classification_model} · effort {settings.classification_effort}")
    if args.dry_run:
        rough = estimate_cost(
            settings.classification_model, 3300 * len(batches), 1100 * len(batches)
        )
        print("\n--dry-run — 모델을 부르지 않았습니다.")
        print(
            f"1회 예상 비용: {f'약 ${rough:.3f}' if rough else '단가 미상'} "
            f"(배치당 입력 3,300 · 출력 1,100 토큰 가정)"
        )
        return 0

    claude = ClaudeClient(settings)
    runs, metas = [], []
    for attempt in range(1, args.repeat + 1):
        print(f"\n--- {attempt}/{args.repeat} 회차 ---")
        predictions, meta = await run_once(settings, claude, batches, args.budget_usd)
        if not predictions:
            print("예측이 하나도 없습니다 — 중단합니다.")
            return 1
        run = score_run(records, predictions)
        run["meta"] = meta
        runs.append(run)
        metas.append((predictions, meta))
        print(
            f"  → Macro F1 {run['transaction']['macroF1']:.4f} · "
            f"수락률 {run['acceptance']['rate']:.4f}"
        )

    def agg(getter) -> dict[str, float]:
        values = [getter(r) for r in runs]
        return {
            "mean": round(statistics.fmean(values), 4),
            "stdev": round(statistics.stdev(values), 4) if len(values) > 1 else 0.0,
            "runs": values,
        }

    report = {
        "dataset": args.dataset,
        "generatedAt": datetime.now(UTC).isoformat(timespec="seconds"),
        "model": settings.classification_model,
        "promptVersion": __import__(
            "app.nlp.prompts", fromlist=["x"]
        ).CLASSIFICATION_PROMPT_VERSION,
        "effort": settings.classification_effort,
        "labeled": len(records),
        "batches": len(batches),
        "batchSize": args.batch_size,
        "repeat": args.repeat,
        "confirmThreshold": CONFIRM_THRESHOLD,
        "pass": {"macroF1": PASS_MACRO_F1, "acceptanceRate": PASS_ACCEPTANCE},
        "aggregate": {
            "transactionMacroF1": agg(lambda r: r["transaction"]["macroF1"]),
            "merchantMacroF1": agg(lambda r: r["merchant"]["macroF1"]),
            "transactionAccuracy": agg(lambda r: r["transaction"]["accuracy"]),
            "acceptanceRate": agg(lambda r: r["acceptance"]["rate"]),
            "autoConfirmed": agg(lambda r: r["acceptance"]["autoConfirmed"]),
            "needsConfirmation": agg(lambda r: r["acceptance"]["needsConfirmation"]),
        },
        "totalElapsedSec": round(sum(r["meta"]["elapsedSec"] for r in runs), 1),
        "totalEstimatedUsd": (
            round(sum(r["meta"]["estimatedUsd"] or 0 for r in runs), 4)
            if all(r["meta"]["estimatedUsd"] is not None for r in runs)
            else None
        ),
        "runs": runs,
        "caveat": (
            "합성 데이터라 실제보다 점수가 높게 나온다. 프롬프트 A/B 비교에 쓰고 "
            "절대 판정에는 쓰지 않는다 (evals/p1-01/라벨_분포표.md)."
        ),
    }

    print_report(report)

    committed = EVALS_ROOT / args.dataset / "report.json"
    committed.write_text(json.dumps(report, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(f"\n→ {committed}  (커밋 대상 — tests/nlp 가 이 점수를 검사한다)")

    stamp = datetime.now().strftime("%Y%m%d-%H%M%S")
    raw_dir = OUT_ROOT / f"{args.dataset}-{stamp}"
    raw_dir.mkdir(parents=True, exist_ok=True)
    for index, (predictions, _m) in enumerate(metas, start=1):
        (raw_dir / f"run{index}-predictions.json").write_text(
            json.dumps(predictions, ensure_ascii=False, indent=2), encoding="utf-8"
        )
    print(f"→ {raw_dir}  (원본 예측 — gitignore)")
    return 0


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__.split("\n")[0])
    parser.add_argument("dataset", help="세트 이름 (예: p1-01)")
    parser.add_argument("--file", default="labels.jsonl")
    parser.add_argument("--model", help="CLASSIFICATION_MODEL 덮어쓰기")
    parser.add_argument("--repeat", type=int, default=1, help="반복 횟수 (편차 측정용)")
    parser.add_argument("--batch-size", type=int, default=BATCH_MAX)
    parser.add_argument("--budget-usd", type=float, default=2.0, help="닿으면 멈춘다")
    parser.add_argument("--dry-run", action="store_true", help="모델을 부르지 않는다")
    return asyncio.run(main_async(parser.parse_args(argv)))


if __name__ == "__main__":
    sys.exit(main())
