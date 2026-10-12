"""`needsReview` 게이트를 저장된 예측만으로 다시 재본다 — API 호출 0, 토큰 0.

    uv run python -X utf8 -m experiments.analyze_review_gate datasets/receipts/korie/test \
        artifacts/eval/test-final

"신뢰도 임계값을 몇으로 둘까"를 답하기 위한 도구다. 세 가지를 낸다.
① 임계값 스윕 — 임계값별 검토율 / 오류 검출률 / 검토 대상 중 실제 오류 비율(정밀도).
② 오류 정의별 게이트 성능 — 스칼라 필드 / 회계 영향(날짜·합계·사업자번호) / 품목.
③ 필드별 변별력 — 신뢰도 < 임계값 구간의 오류율이 전체 오류율의 몇 배인가.

**집계만 낸다.** 영수증 id·라벨값·예측값을 하나도 찍지 않아 test 분할에서도 쓸 수 있다 —
개별 실패를 들여다보는 `analyze_failures` 와 달리 이 스크립트로는 test 를 오염시킬 수 없다.
정밀도를 읽을 때는 데이터 자체의 오류율(무작위로 찍었을 때의 정밀도)과 비교해야 한다.
"""

from __future__ import annotations

import argparse
from collections import Counter
from pathlib import Path

from app.ocr.schemas import EvidenceExtraction
from app.ocr.validators import validate_extraction
from experiments import metrics as m
from experiments.evaluate_evidence import PredictionRecord
from experiments.labels import iter_labels

# 라벨 노이즈가 많은 필드 — val 50장에서 교정된 11장이 전부 이 둘이었다(부가세 6·상호명 5).
NOISY_FIELDS = {"supplierName", "vatAmount"}
THRESHOLDS = (None, 0.5, 0.6, 0.7, 0.8, 0.85, 0.9, 0.95)


def _wrong_fields(score: m.ReceiptScore) -> set[str]:
    return {key for key, outcome in score.fields.items() if outcome.correct is False}


def _items_wrong(score: m.ReceiptScore) -> bool:
    if score.items is None:
        return False
    return score.items.recall < 1.0 or score.item_amount_correct < len(score.items.matched)


def _label(threshold: float | None) -> str:
    return "끔 (결정적 검사만)" if threshold is None else f"{threshold}"


def _rows(dataset: Path, out: Path) -> list[tuple[m.ReceiptScore, EvidenceExtraction]]:
    rows = []
    for _, label in iter_labels(dataset):
        path = out / "predictions" / f"{label.meta.source_id}.json"
        if not path.exists():
            continue
        record = PredictionRecord.model_validate_json(path.read_text(encoding="utf-8"))
        if record.extraction is not None:
            rows.append((m.score_receipt(label, record.extraction), record.extraction))
    return rows


def analyze(dataset: Path, out: Path) -> str:
    rows = _rows(dataset, out)
    if not rows:
        return "예측이 없습니다. evaluate_evidence 를 먼저 실행하세요."
    n = len(rows)
    flags = {
        t: [
            validate_extraction(x, **({} if t is None else {"low_confidence": t})).needs_review
            for _, x in rows
        ]
        for t in THRESHOLDS
    }
    definitions = {
        "스칼라 필드 오류 (전체)": lambda s: bool(_wrong_fields(s)),
        "회계 영향 오류 (날짜·합계·사업자번호)": lambda s: bool(_wrong_fields(s) - NOISY_FIELDS),
        "품목 누락·금액 오독": _items_wrong,
    }

    lines = [f"# needsReview 게이트 분석 — {out.name} ({n}장, {m.SCORING_RULES_VERSION})", ""]

    wrong = [bool(_wrong_fields(s)) for s, _ in rows]
    n_wrong = sum(wrong)
    lines += [
        f"오류 영수증(스칼라 필드 기준) {n_wrong}/{n} = **{n_wrong / n:.1%}**.",
        "무작위로 찍었을 때의 정밀도가 이 값이다 — 아래 정밀도는 이 기준선과 비교해서 읽는다.",
        "",
        "## ① 임계값 스윕",
        "",
        "| 신뢰도 임계값 | 검토율 | 오류 검출률 | 정밀도 |",
        "| --- | ---: | ---: | ---: |",
    ]
    for t in THRESHOLDS:
        flagged = flags[t]
        hit = sum(f and w for f, w in zip(flagged, wrong, strict=True))
        n_flagged = sum(flagged)
        lines.append(
            f"| {_label(t)} | {n_flagged / n:.1%} ({n_flagged}) "
            f"| {hit / n_wrong:.1%} ({hit}/{n_wrong}) | {hit / n_flagged:.0%} |"
        )

    lines += [
        "",
        "## ② 오류 정의별 게이트(결정적 검사만) 성능",
        "",
        "| 오류 정의 | 해당 장수 | 게이트가 잡은 수 | 검출률 | 정밀도 |",
        "| --- | ---: | ---: | ---: | ---: |",
    ]
    gate = flags[None]
    n_gate = sum(gate)
    for name, is_wrong in definitions.items():
        marks = [is_wrong(s) for s, _ in rows]
        hit = sum(f and w for f, w in zip(gate, marks, strict=True))
        n_marks = sum(marks)
        detected = f"{hit / n_marks:.0%}" if n_marks else "-"
        lines.append(
            f"| {name} | {n_marks} ({n_marks / n:.1%}) | {hit} | {detected} | {hit / n_gate:.0%} |"
        )

    lines += [
        "",
        "### 회계 영향 오류를 임계값별로 (게이트가 못 잡는 부분을 신뢰도가 잡는가)",
        "",
        "| 신뢰도 임계값 | 검토율 | 회계 영향 오류 검출 |",
        "| --- | ---: | ---: |",
    ]
    acct = [bool(_wrong_fields(s) - NOISY_FIELDS) for s, _ in rows]
    n_acct = sum(acct)
    for t in THRESHOLDS:
        flagged = flags[t]
        hit = sum(f and a for f, a in zip(flagged, acct, strict=True))
        lines.append(f"| {_label(t)} | {sum(flagged) / n:.1%} | {hit}/{n_acct} |")

    # 자동 승인은 하지 않는다(팀 결정 2026-09-08). 그 결정의 근거를 숫자로 남긴다 —
    # 명세 13.2 는 모든 추출을 EXTRACTED(사용자 확인 대기)로 보내므로 지금 FAR 는 구조적으로 0 이다.
    # "게이트를 통과하면 자동 승인" 으로 바꿨을 때 무엇을 놓치게 되는지가 아래 표다.
    passed = [i for i, f in enumerate(gate) if not f]
    held = [i for i, f in enumerate(gate) if f]
    lines += [
        "",
        "### 만약 자동 승인을 한다면 (게이트 통과 = 자동 승인 가정)",
        "",
        f"자동 승인 {len(passed)}장 / 보류 {len(held)}장 (재검증률 {len(held) / n:.1%})",
        "",
        "| 오류 기준 | FAR (자동승인 중 오류) | 놓친 오류 | 불필요 보류 |",
        "| --- | ---: | ---: | ---: |",
    ]
    for name, marks in (
        ("스칼라 필드 오류", [bool(_wrong_fields(s)) for s, _ in rows]),
        ("**회계 영향 오류** (날짜·합계·사업자번호)", acct),
    ):
        far = sum(marks[i] for i in passed)
        missed_all = sum(marks)
        useless = sum(not marks[i] for i in held)
        lines.append(
            f"| {name} | {far}/{len(passed)} = **{far / len(passed):.1%}** "
            f"| {far}/{missed_all} = {far / missed_all:.0%} "
            f"| {useless}/{len(held)} = {useless / len(held):.0%} |"
        )
    lines += [
        "",
        "회계 영향 오류를 결정적 검사가 하나도 못 잡으므로, 자동 승인으로 바꾸면 날짜·합계가 틀린",
        "영수증이 그 비율만큼 그대로 통과한다. 신뢰도로 보완하는 길도 위 스윕에서 막혔다 —",
        "정밀도가 데이터 자체의 오류율(= 무작위)과 거의 같다. **그래서 자동 승인을 넣지 않는다.**",
    ]

    lines += [
        "",
        "## ③ 필드별 변별력 (신뢰도 < 0.7 구간)",
        "",
        "| 필드 | 전체 오류율 | 저신뢰 구간 오류율 | 변별력 | 저신뢰 건수 |",
        "| --- | ---: | ---: | ---: | ---: |",
    ]
    for key in m.SCALAR_FIELDS:
        judged = [
            (s.fields[key].correct is False, x.field_confidence.get(key))
            for s, x in rows
            if key in s.fields and s.fields[key].correct is not None
        ]
        if not judged:
            lines.append(f"| {key} | (채점 가능한 라벨 없음) | - | - | 0 |")
            continue
        base = sum(bad for bad, _ in judged) / len(judged)
        low = [bad for bad, conf in judged if conf is not None and conf < 0.7]
        if not low:
            lines.append(f"| {key} | {base:.1%} | - | - | 0 |")
            continue
        low_rate = sum(low) / len(low)
        lift = f"{low_rate / base:.1f}x" if base else "∞"
        lines.append(f"| {key} | {base:.1%} | {low_rate:.1%} | {lift} | {len(low)} |")

    lines += ["", "## 게이트 사유 분포 (결정적 검사만)", ""]
    lines += ["| 사유 | 영수증 수 |", "| --- | ---: |"]
    reasons: Counter[str] = Counter()
    for _, x in rows:
        reasons.update(validate_extraction(x).review_reasons)
    lines += [f"| {key} | {value} |" for key, value in reasons.most_common()]
    return "\n".join(lines)


def main() -> int:
    parser = argparse.ArgumentParser(
        description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter
    )
    parser.add_argument("dataset", type=Path)
    parser.add_argument("out", type=Path, help="evaluate_evidence 의 --out 디렉터리")
    args = parser.parse_args()
    report = analyze(args.dataset, args.out)
    (args.out / "review-gate.md").write_text(report + "\n", encoding="utf-8")
    print(report)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
