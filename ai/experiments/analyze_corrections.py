"""사용자 수정 이력으로 **실사용 정확도**를 낸다 — 우리가 아직 못 가진 유일한 지표.

    # 백엔드가 내보낸 로그로 (실데이터)
    uv run python -X utf8 -m experiments.analyze_corrections corrections.jsonl

    # 로그가 없을 때, 기존 측정으로 같은 모양을 만들어 분석기를 검증·시연한다 (토큰 0)
    uv run python -X utf8 -m experiments.analyze_corrections --simulate \\
        datasets/receipts/korie/test artifacts/eval/test-final --out artifacts/eval/corrections-sim

**왜 필요한가.** 지금 우리 수치는 전부 KORIE(라벨 노이즈가 있다)와 합성이다. 상호명 91.3% 가
모델 탓인지 라벨 탓인지, 신뢰도가 정말 변별력이 없는지는 **실사용자가 무엇을 고쳤는지**로만
확정된다. 사용자 확인(13.3 `PATCH .../extraction`, `confirmed: true`)이 그 라벨이다.

**입력 한 줄 = 검토 1건**(확인된 추출 하나). 수정이 없어도 한 줄을 남겨야 한다 —
고친 것만 모으면 분모가 없어서 정확도를 낼 수 없다. 이게 이 기록 설계에서 가장 중요한 점이다.

```json
{"extractionId": 1, "evidenceId": 501, "modelName": "claude-sonnet-5",
 "promptVersion": "evidence-extraction/v4", "extractionMode": "STANDARD",
 "validation": {"needsReview": false, "reviewReasons": []},
 "fieldConfidence": {"totalAmount": 0.99},
 "corrections": [{"field": "totalAmount", "before": "156000", "after": "155000"}],
 "confirmedAt": "2026-09-08T05:00:00Z"}
```

네 가지를 답한다.
1. **필드별 실사용 정확도** = 1 − 수정률. KORIE 수치와 대조하면 라벨 노이즈 몫이 드러난다.
2. **게이트가 맞았나** — `needsReview` 가 실제로 수정된 건을 지목했나(정밀도·재현율).
3. **신뢰도가 변별력이 있나** — 수정된 필드와 안 된 필드의 평균 신뢰도 차이. test 359장에서는
   무작위와 같았는데(정밀도 18~25% vs 기준선 16.7%), 실사용에서 다르면 자동 승인 논의를 다시 연다.
4. **재추출이 실제로 고쳤나** — `extractionMode=THOROUGH` 건의 수정률이 STANDARD 보다 낮은가.
"""

from __future__ import annotations

import argparse
import json
import statistics
from collections import Counter, defaultdict
from dataclasses import dataclass, field
from pathlib import Path
from typing import Any

# 필드 단위로 채점하는 헤더 필드. `items[3].amount` 같은 품목 필드는 `items` 로 묶는다.
SCALAR_FIELDS = (
    "supplierName",
    "supplierBusinessNo",
    "issuedDate",
    "supplyAmount",
    "taxableAmount",
    "taxFreeAmount",
    "vatAmount",
    "discountAmount",
    "totalAmount",
)


@dataclass
class Review:
    """검토 1건 = 확인된 추출 하나."""

    extraction_id: Any
    model_name: str | None
    prompt_version: str | None
    extraction_mode: str
    needs_review: bool
    review_reasons: list[str]
    field_confidence: dict[str, float]
    corrected: set[str]  # 수정된 필드 키 (품목은 "items" 로 묶음)

    @property
    def any_correction(self) -> bool:
        return bool(self.corrected)


def _field_key(raw: str) -> str:
    """`items[3].amount` → `items`. 품목은 행 단위 수정이 많아 필드별로 쪼개면 분모가 흔들린다."""
    return "items" if raw.startswith("items") else raw


def parse_review(row: dict[str, Any]) -> Review:
    validation = row.get("validation") or {}
    return Review(
        extraction_id=row.get("extractionId"),
        model_name=row.get("modelName"),
        prompt_version=row.get("promptVersion"),
        extraction_mode=row.get("extractionMode") or "STANDARD",
        needs_review=bool(validation.get("needsReview")),
        review_reasons=list(validation.get("reviewReasons") or []),
        field_confidence=dict(row.get("fieldConfidence") or {}),
        corrected={_field_key(c["field"]) for c in (row.get("corrections") or [])},
    )


@dataclass
class Summary:
    reviews: list[Review] = field(default_factory=list)

    def field_accuracy(self) -> dict[str, tuple[int, float]]:
        """필드 → (검토 수, 1 − 수정률). 그 필드가 결과에 있었던 건만 분모에 넣는다."""
        out: dict[str, tuple[int, float]] = {}
        for key in (*SCALAR_FIELDS, "items"):
            # 신뢰도에 키가 있으면 모델이 그 필드를 다뤘다고 본다. 없으면 판정에서 뺀다 —
            # 인쇄되지 않은 필드(면세 줄 없는 영수증의 taxFreeAmount)를 오답으로 세면 안 된다.
            judged = [r for r in self.reviews if key in r.field_confidence or key in r.corrected]
            if not judged:
                continue
            wrong = sum(key in r.corrected for r in judged)
            out[key] = (len(judged), 1 - wrong / len(judged))
        return out

    def gate(self) -> dict[str, Any]:
        """needsReview 가 실제 수정을 지목했나."""
        n = len(self.reviews)
        flagged = [r for r in self.reviews if r.needs_review]
        wrong = [r for r in self.reviews if r.any_correction]
        hit = sum(r.needs_review and r.any_correction for r in self.reviews)
        return {
            "reviews": n,
            "flagRate": len(flagged) / n if n else None,
            "correctionRate": len(wrong) / n if n else None,  # 무작위로 찍었을 때의 정밀도
            "precision": hit / len(flagged) if flagged else None,
            "recall": hit / len(wrong) if wrong else None,
        }

    def confidence(self) -> dict[str, Any]:
        """수정된 필드와 안 된 필드의 신뢰도 분포. 겹치면 변별력이 없다."""
        corrected: list[float] = []
        kept: list[float] = []
        for r in self.reviews:
            for key, value in r.field_confidence.items():
                (corrected if key in r.corrected else kept).append(value)
        return {
            "correctedN": len(corrected),
            "keptN": len(kept),
            "meanCorrected": round(statistics.fmean(corrected), 4) if corrected else None,
            "meanKept": round(statistics.fmean(kept), 4) if kept else None,
        }

    def by(self, attr: str) -> dict[str, dict[str, Any]]:
        """모델·프롬프트 버전·재추출 모드별 수정률. v4 → v5 판단을 실사용으로 하게 한다."""
        groups: dict[str, list[Review]] = defaultdict(list)
        for r in self.reviews:
            groups[str(getattr(r, attr))].append(r)
        return {
            key: {
                "reviews": len(rows),
                "correctionRate": round(sum(r.any_correction for r in rows) / len(rows), 4),
            }
            for key, rows in sorted(groups.items())
        }

    def reasons(self) -> Counter[str]:
        counter: Counter[str] = Counter()
        for r in self.reviews:
            counter.update(r.review_reasons)
        return counter


def format_markdown(s: Summary) -> str:
    gate = s.gate()
    conf = s.confidence()
    lines = [f"# 사용자 수정 이력 분석 — 검토 {gate['reviews']}건", ""]
    if not s.reviews:
        return "\n".join([*lines, "기록이 없습니다."])

    lines += ["| 필드 | 검토 | 실사용 정확도 (1 − 수정률) |", "| --- | ---: | ---: |"]
    for key, (n, accuracy) in s.field_accuracy().items():
        lines.append(f"| {key} | {n} | {accuracy:.1%} |")

    lines += [
        "",
        "## 게이트가 맞았나 (`needsReview`)",
        "",
        f"- 검토 대상 지목률 **{gate['flagRate']:.1%}**",
        f"- 실제 수정된 건 **{gate['correctionRate']:.1%}** ← 무작위로 찍었을 때의 정밀도",
        f"- 정밀도 **{_pct(gate['precision'])}** · 재현율 **{_pct(gate['recall'])}**",
        "",
        "## 신뢰도에 변별력이 있나 (`fieldConfidence`)",
        "",
        f"- 수정된 필드 평균 {conf['meanCorrected']} (n={conf['correctedN']})"
        f" · 안 고친 필드 평균 {conf['meanKept']} (n={conf['keptN']})",
        "",
        "두 값이 겹치면 변별력이 없다 — test 359장에서는 그랬다(정밀도가 기준선과 같았다).",
        "실사용에서 벌어지면 자동 승인 논의를 다시 열 수 있다.",
    ]
    for attr, title in (
        ("prompt_version", "프롬프트 버전별"),
        ("model_name", "모델별"),
        ("extraction_mode", "재추출 모드별"),
    ):
        rows = s.by(attr)
        if len(rows) <= 1:
            continue
        lines += ["", f"## {title} 수정률", "", "| 값 | 검토 | 수정률 |", "| --- | ---: | ---: |"]
        lines += [
            f"| {key} | {v['reviews']} | {v['correctionRate']:.1%} |" for key, v in rows.items()
        ]
    if reasons := s.reasons():
        lines += ["", "## 검토 사유 분포", "", "| 사유 | 건수 |", "| --- | ---: |"]
        lines += [f"| {k} | {v} |" for k, v in reasons.most_common()]
    return "\n".join(lines)


def _pct(value: float | None) -> str:
    return f"{value:.1%}" if value is not None else "-"


def simulate(dataset: Path, run: Path) -> list[dict[str, Any]]:
    """기존 측정으로 로그와 같은 모양을 만든다 — 분석기 검증·시연용이다.

    라벨과 예측이 다른 필드를 "사용자가 고쳤다" 로 본다. 실제 사용자 수정과 같지 않다
    (라벨 노이즈가 그대로 들어온다) — 분석기가 도는지 보고 백엔드에 출력 예시를 주는 용도다.
    """
    from app.ocr.schemas import EvidenceExtraction
    from experiments import metrics as m
    from experiments.labels import iter_labels

    meta = {}
    if (run / "run.json").exists():
        meta = json.loads((run / "run.json").read_text(encoding="utf-8"))
    rows: list[dict[str, Any]] = []
    for _, label in iter_labels(dataset):
        path = run / "predictions" / f"{label.meta.source_id}.json"
        if not path.exists():
            continue
        record = json.loads(path.read_text(encoding="utf-8"))
        if not record.get("extraction"):
            continue
        extraction = EvidenceExtraction.model_validate(record["extraction"])
        score = m.score_receipt(label, extraction)
        corrections = [
            {"field": key, "before": "…", "after": "…"}
            for key, outcome in score.fields.items()
            if outcome.correct is False
        ]
        if score.items is not None and (score.items.recall or 1.0) < 1.0:
            corrections.append({"field": "items[?]", "before": "…", "after": "…"})
        from app.ocr.validators import validate_extraction

        report = validate_extraction(extraction)
        rows.append(
            {
                "extractionId": label.meta.source_id,
                "evidenceId": label.meta.source_id,
                "modelName": record.get("model") or meta.get("model"),
                "promptVersion": meta.get("promptVersion"),
                "extractionMode": "STANDARD",
                "validation": report.model_dump(by_alias=True, mode="json"),
                "fieldConfidence": extraction.field_confidence,
                "corrections": corrections,
            }
        )
    return rows


def main() -> int:
    parser = argparse.ArgumentParser(
        description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter
    )
    parser.add_argument("log", type=Path, nargs="?", help="백엔드가 내보낸 JSONL")
    parser.add_argument(
        "--simulate",
        nargs=2,
        metavar=("DATASET", "RUN"),
        help="기존 측정으로 로그 모양을 만들어 분석한다 (토큰 0)",
    )
    parser.add_argument("--out", type=Path, help="요약·시뮬레이션 로그를 쓸 디렉터리")
    args = parser.parse_args()

    if args.simulate:
        rows = simulate(Path(args.simulate[0]), Path(args.simulate[1]))
        print(f"[시뮬레이션] {len(rows)}건 — 실제 사용자 수정이 아니다(라벨 차이를 수정으로 간주)")
    elif args.log:
        rows = [
            json.loads(line) for line in args.log.read_text(encoding="utf-8").splitlines() if line
        ]
    else:
        parser.error("로그 파일 또는 --simulate 가 필요합니다.")
        return 2

    summary = Summary([parse_review(r) for r in rows])
    report = format_markdown(summary)
    if args.out:
        args.out.mkdir(parents=True, exist_ok=True)
        (args.out / "summary.md").write_text(report + "\n", encoding="utf-8")
        if args.simulate:
            with (args.out / "corrections.jsonl").open("w", encoding="utf-8") as handle:
                for row in rows:
                    handle.write(json.dumps(row, ensure_ascii=False) + "\n")
    print()
    print(report)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
