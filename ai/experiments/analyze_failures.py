"""평가 결과의 실패를 유형별로 묶는다 — 프롬프트를 고칠 때 "무엇이 왜 틀렸나"를 한눈에 보기 위해.

    uv run python -X utf8 -m experiments.analyze_failures datasets/receipts/korie/val \
        artifacts/eval/val-001

읽는 것: `<out>/predictions/*.json` 과 라벨(overrides 적용). 쓰는 것: `<out>/analysis.md`.
val/train 에서만 쓴다 — test 의 실패를 들여다보는 순간 그 수치는 최종 수치가 아니다.

유형
- 상호명: 유사도로 정답 / 포함 매칭으로 정답(라벨 로고 ⊂ 예측 매장명) / 근접 오독 / 다른 값 / null
- 날짜: null 예측 / 연·월·일 중 무엇이 다른지 / 1일 차이(시간대·결제일)
- 합계: 라벨이 할인 전 총합계(= 예측 합계 + 할인, 정답 처리) / 품목 합·공급가를 읽음 / 그 외
- 검산: 통과 / 판정 불가(과세·공급가 줄 없음) / 불일치 — 불일치는 잔차와 인쇄값을 함께 보인다
- needsReview 사유 분포
- 품목: 누락 행 수 분포, 라벨 행 이름과 가장 비슷한 예측 행(이름은 있었는데 매칭 실패인지)
"""

from __future__ import annotations

import argparse
from collections import Counter
from datetime import date
from pathlib import Path
from typing import Any

from rapidfuzz import fuzz

from app.ocr.normalization import normalize_name
from app.ocr.validators import validate_extraction
from experiments import metrics as m
from experiments.evaluate_evidence import PredictionRecord, _is_test_split
from experiments.labels import LabelRecord, iter_labels


def _load_pairs(dataset: Path, out: Path) -> list[tuple[LabelRecord, PredictionRecord]]:
    pairs = []
    for _, label in iter_labels(dataset):
        path = out / "predictions" / f"{label.meta.source_id}.json"
        if path.exists():
            pairs.append(
                (label, PredictionRecord.model_validate_json(path.read_text(encoding="utf-8")))
            )
    return pairs


def _supplier_bucket(gt: str | None, pred: str | None) -> str:
    if gt is None and pred is None:
        return "둘 다 null"
    if gt is None:
        return "라벨 null 인데 예측함"
    if pred is None:
        return "예측 null"
    matched, via = m.supplier_names_match(gt, pred)
    if matched:
        return "정답 (유사도)" if via == "similarity" else "정답 (포함 매칭: 라벨 ⊂ 예측)"
    if m.name_similarity(gt, pred) >= 0.8:
        return "근접 오독 (유사도 0.8~0.9)"
    return "다른 값 (유사도 < 0.8)"


def _date_bucket(gt: date | None, pred: date | None) -> str:
    if gt is None and pred is None:
        return "둘 다 null"
    if gt is None:
        return "라벨 null 인데 예측함"
    if pred is None:
        return "예측 null"
    if gt == pred:
        return "정답"
    if abs((gt - pred).days) == 1:
        return "1일 차이"
    if gt.year != pred.year:
        return "연도 다름"
    if gt.month != pred.month:
        return "월 다름"
    return "일 다름"


def _total_bucket(label: LabelRecord, pred_ex: Any) -> str:
    gt, pred = label.extraction.total_amount, pred_ex.total_amount if pred_ex else None
    if gt is None:
        return "라벨 없음"
    if pred is None:
        return "예측 null"
    if gt == pred:
        return "정답"
    matched, via = m.total_matches(gt, pred_ex)
    if matched and via == "gross":
        return "라벨 = 할인 전 총합계 (예측 합계 + 할인) → 정답 처리"
    items_sum = sum(i.amount or 0 for i in pred_ex.items)
    if items_sum and pred == items_sum:
        return "품목 합을 합계로 읽음"
    if pred_ex.supply_amount is not None and pred == pred_ex.supply_amount:
        return "공급가를 합계로 읽음"
    return "그 외 불일치"


def _reconciliation_bucket(x: Any) -> tuple[str, str | None]:
    """(유형, 예시 문자열). 인쇄값을 그대로 보여 잔차의 출처를 짚을 수 있게 한다."""
    report = validate_extraction(x)
    printed = (
        f"과세 {x.taxable_amount} + 부가세 {x.vat_amount} + 면세 {x.tax_free_amount}"
        f" | 공급가 {x.supply_amount} | 할인 {x.discount_amount} | 합계 {x.total_amount}"
    )
    if report.amounts_consistent is None:
        return "판정 불가 (과세·공급가 줄 없음 또는 부가세·합계 없음)", printed
    if report.amounts_consistent:
        return "통과", None
    base = x.taxable_amount if x.taxable_amount is not None else x.supply_amount
    residual = x.total_amount - base - x.vat_amount - (x.tax_free_amount or 0)
    if x.discount_amount and residual == -x.discount_amount:
        return "불일치 — 잔차 = 할인 (합계가 할인 전 값)", f"{printed} → 잔차 {residual}"
    return "불일치 — 그 외", f"{printed} → 잔차 {residual}"


def analyze(dataset: Path, out: Path) -> str:
    pairs = _load_pairs(dataset, out)
    if not pairs:
        return "예측이 없습니다. evaluate_evidence 를 먼저 실행하세요."
    scores = [m.score_receipt(label, pred.extraction) for label, pred in pairs]

    supplier: Counter[str] = Counter()
    dates: Counter[str] = Counter()
    totals: Counter[str] = Counter()
    recon: Counter[str] = Counter()
    review: Counter[str] = Counter()
    missed_hist: Counter[int] = Counter()
    errors: Counter[str] = Counter()
    supplier_examples: list[str] = []
    date_examples: list[str] = []
    total_examples: list[str] = []
    recon_examples: list[str] = []
    missed_examples: list[str] = []

    for (label, pred), score in zip(pairs, scores, strict=True):
        if pred.extraction is None:
            errors[pred.error_stage or "unknown"] += 1
            continue
        x, y = pred.extraction, label.extraction
        unlabeled = set(label.meta.unlabeled_fields)

        if "supplierName" not in unlabeled:
            bucket = _supplier_bucket(y.supplier_name, x.supplier_name)
            supplier[bucket] += 1
            if not bucket.startswith("정답") and len(supplier_examples) < 12:
                supplier_examples.append(
                    f"{label.meta.source_id}: 라벨 {y.supplier_name!r} → 예측 {x.supplier_name!r}"
                )
        if "issuedDate" not in unlabeled:
            bucket = _date_bucket(y.issued_date, x.issued_date)
            dates[bucket] += 1
            if bucket != "정답" and len(date_examples) < 12:
                date_examples.append(
                    f"{label.meta.source_id}: 라벨 {y.issued_date} → 예측 {x.issued_date}"
                )
        if "totalAmount" not in unlabeled:
            bucket = _total_bucket(label, x)
            totals[bucket] += 1
            if bucket != "정답" and len(total_examples) < 12:
                total_examples.append(
                    f"{label.meta.source_id}: 라벨 {y.total_amount} → 예측 {x.total_amount}"
                    f" (할인 {x.discount_amount})"
                )
        bucket, example = _reconciliation_bucket(x)
        recon[bucket] += 1
        if example and bucket.startswith("불일치") and len(recon_examples) < 12:
            recon_examples.append(f"{label.meta.source_id}: {example}")
        for reason in validate_extraction(x).review_reasons:
            review[reason] += 1
        if score.items is not None:
            missed_hist[len(score.items.missed)] += 1
            for missed in score.items.missed:
                if len(missed_examples) >= 12:
                    break
                best = max(
                    (
                        (
                            fuzz.WRatio(
                                normalize_name(missed.raw_name), normalize_name(p.raw_name)
                            ),
                            p.raw_name,
                        )
                        for p in x.items
                    ),
                    default=(0, None),
                )
                missed_examples.append(
                    f"{label.meta.source_id}: 라벨 {missed.raw_name!r} — 가장 비슷한 예측 "
                    f"{best[1]!r} (유사도 {best[0] / 100:.2f})"
                )

    def section(title: str, counter: Counter[Any], examples: list[str]) -> list[str]:
        lines = [f"## {title}", "", "| 유형 | 건수 |", "| --- | ---: |"]
        lines += [f"| {k} | {v} |" for k, v in counter.most_common()]
        if examples:
            lines += ["", "예:", *[f"- {e}" for e in examples]]
        return lines + [""]

    lines = [f"# 실패 분석 — {out.name} ({len(pairs)}장, {m.SCORING_RULES_VERSION})", ""]
    if errors:
        lines += section("처리 실패", errors, [])
    lines += section("상호명", supplier, supplier_examples)
    lines += section("날짜", dates, date_examples)
    lines += section("합계", totals, total_examples)
    lines += section("검산 (인쇄된 금액)", recon, recon_examples)
    lines += section(
        "needsReview 사유 (영수증 수)", review or Counter({"(사유 없음)": len(pairs)}), []
    )
    lines += section(
        "품목 누락 (영수증당 누락 행 수)",
        Counter({f"{k}행 누락": v for k, v in sorted(missed_hist.items())}),
        missed_examples,
    )
    return "\n".join(lines)


def main() -> int:
    parser = argparse.ArgumentParser(
        description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter
    )
    parser.add_argument("dataset", type=Path)
    parser.add_argument("out", type=Path, help="evaluate_evidence 의 --out 디렉터리")
    args = parser.parse_args()
    if _is_test_split(args.dataset):
        print("[거절] test 분할의 실패는 들여다보지 않는다. val/train 에서만 쓴다.")
        return 2
    report = analyze(args.dataset, args.out)
    (args.out / "analysis.md").write_text(report + "\n", encoding="utf-8")
    print(report)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
