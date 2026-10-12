"""VLM 이 `boundingBox` 를 낼 수 있나 — 좌표 정확도 실측 (명세 13.2 하이라이트).

    # 1) 좌표 정답이 있는 세트를 만든다 (토큰 0)
    uv run python -X utf8 -m experiments.synth_evidence \
        --out datasets/receipts/synthetic-boxes --count 5 --seed 11 --with-boxes --no-degrade

    # 2) 측정 (유료)
    uv run python -X utf8 -m experiments.measure_boxes datasets/receipts/synthetic-boxes \
        --out artifacts/eval/boxes-001

**운영 프롬프트·파이프라인을 건드리지 않는다.** 여기서만 쓰는 프롬프트와 출력 스키마로 직접
호출한다 — 가능성 판정이 먼저고, 되면 그때 계약에 넣는다.

정답은 생성기가 남긴 텍스트 레이어(`meta.extra.textLayer`)다. 어느 항목이 어느 필드인지는
값으로 맞춘다 — 금액·사업자번호·날짜는 숫자만 뽑아 포함 관계로, 상호는 문자열 포함으로.
한 값이 두 곳에 인쇄되어 있으면 **둘 다 정답**으로 본다(어느 쪽을 가리켜도 옳은 하이라이트다).

두 가지로 채점한다.
- **IoU** — 박스가 얼마나 겹치나. 0.5 이상이면 통상 "맞다" 로 본다.
- **중심 적중** — 예측 박스의 중심이 정답 박스 안에 들어가나. 하이라이트의 실질 기준이다.
  박스가 조금 크거나 작아도 사람이 볼 곳을 가리키면 쓸 수 있다.

값 정확도도 함께 센다. 좌표를 같이 요구하면 값 추출이 나빠질 수 있어서다.
"""

from __future__ import annotations

import argparse
import asyncio
import base64
import json
import re
from dataclasses import dataclass, field
from pathlib import Path
from typing import Any

from pydantic import BaseModel, ConfigDict

from app.common.claude import ClaudeClient, ClaudeError
from app.config import Settings
from app.ocr.preprocess import prepare_image
from experiments.evaluate_evidence import estimate_cost_usd
from experiments.labels import LabelRecord, iter_labels

BOX_PROMPT_VERSION = "evidence-boxes/v1"

# 필드 → 라벨에서 정답 값을 꺼내는 경로. 헤더 필드만 본다 (가능성 판정에 충분하고 출력이 짧다).
FIELDS = ("supplierName", "supplierBusinessNo", "issuedDate", "totalAmount", "vatAmount")

SYSTEM = """\
당신은 증빙 이미지에서 값을 읽고 **그 값이 인쇄된 위치**를 함께 알려주는 도우미다.

각 필드마다 value 와 box 를 낸다.
- value: 인쇄된 값. 금액은 원 단위 정수(쉼표 제거), 날짜는 YYYY-MM-DD, 사업자번호는 000-00-00000.
- box: 그 값의 **글자를 둘러싸는 사각형**. 이미지 크기에 대한 비율 0~1 로 적는다.
  x 는 왼쪽 가장자리, y 는 위쪽 가장자리(이미지 맨 위가 0), width·height 는 폭과 높이.
  라벨 문구("합계", "사업자번호")가 아니라 **값 자체**를 감싼다.
- 보이지 않는 필드는 value 와 box 를 모두 null 로 둔다. 위치를 모르면 box 만 null 로 둔다.

박스는 값의 글자에 딱 맞게 잡는다. 줄 전체나 표 전체를 감싸지 않는다.
"""


class _Strict(BaseModel):
    model_config = ConfigDict(extra="forbid")


class _Box(_Strict):
    x: float
    y: float
    width: float
    height: float


class _Field(_Strict):
    value: str | None
    box: _Box | None


class _Boxes(_Strict):
    supplierName: _Field
    supplierBusinessNo: _Field
    issuedDate: _Field
    totalAmount: _Field
    vatAmount: _Field


@dataclass
class FieldScore:
    judged: int = 0  # 정답 박스를 찾을 수 있었던 건
    value_correct: int = 0
    box_present: int = 0
    center_hit: int = 0
    iou_half: int = 0  # IoU >= 0.5
    iou_sum: float = 0.0


@dataclass
class Report:
    per_field: dict[str, FieldScore] = field(default_factory=dict)
    samples: int = 0
    failures: list[str] = field(default_factory=list)

    def of(self, key: str) -> FieldScore:
        return self.per_field.setdefault(key, FieldScore())


def _digits(text: str) -> str:
    return re.sub(r"\D", "", text)


def _label_value(label: LabelRecord, key: str) -> Any:
    mapping = {
        "supplierName": label.extraction.supplier_name,
        "supplierBusinessNo": label.extraction.supplier_business_no,
        "issuedDate": label.extraction.issued_date,
        "totalAmount": label.extraction.total_amount,
        "vatAmount": label.extraction.vat_amount,
    }
    return mapping[key]


def _narrow(box: list[float] | tuple[float, ...], text: str, start: int, span: int) -> tuple:
    """줄 전체 박스에서 값이 차지하는 가로 구간만 남긴다.

    생성기가 `거래일시: 2026-03-31 09:28:05` 처럼 라벨과 값을 한 문자열로 그린 곳이 있어,
    그대로 두면 **모델이 값을 정확히 가리켜도 오답**이 된다(정답 박스가 줄 전체다).
    비례 폰트라 글자 수 비례는 근사지만, 줄 전체를 정답으로 쓰는 것보다 훨씬 낫다.
    """
    x, y, w, h = box
    if not text or span <= 0 or span >= len(text):
        return (x, y, w, h)
    return (x + w * (start / len(text)), y, w * (span / len(text)), h)


def _value_span(text: str, needle_digits: str) -> tuple[int, int] | None:
    """`text` 안에서 숫자가 `needle_digits` 와 같아지는 **가장 짧은** 구간 (start, span)."""
    best: tuple[int, int] | None = None
    for start in range(len(text)):
        for end in range(start + 1, len(text) + 1):
            if _digits(text[start:end]) == needle_digits:
                if best is None or end - start < best[1]:
                    best = (start, end - start)
                break
    return best


def truth_boxes(label: LabelRecord, key: str) -> list[tuple[float, float, float, float]]:
    """텍스트 레이어에서 이 필드의 값이 인쇄된 박스들. 없으면 빈 목록(채점 제외).

    항목 문자열에 값 말고 다른 글자가 섞여 있으면 값 구간만 남긴다(`_narrow`).
    """
    layer = label.meta.extra.get("textLayer") or []
    value = _label_value(label, key)
    if value is None:
        return []
    if key == "supplierName":
        needle = str(value)
        return [
            _narrow(e["box"], e["text"], e["text"].index(needle), len(needle))
            for e in layer
            if needle in e["text"]
        ]
    else:
        needle = _digits(str(value))
        if not needle:
            return []
        # 사업자번호·날짜는 구분자가 다를 수 있고 금액엔 쉼표가 붙어 숫자만으로 대조한다.
        # 다만 **정확히 그 숫자인 항목이 있으면 그것만** 쓴다 — 작은 금액은 다른 큰 숫자에
        # 부분 포함되어 정답 박스가 부풀고, 그러면 채점이 느슨해진다("아무 곳이나 맞으면 정답").
        exact = [e for e in layer if _digits(e["text"]) == needle]
        hits = exact or [e for e in layer if needle in _digits(e["text"])]
    boxes = []
    for e in hits:
        span = _value_span(e["text"], needle)
        boxes.append(
            _narrow(e["box"], e["text"], *span) if span else tuple(e["box"])  # type: ignore[misc]
        )
    return boxes


def _iou(a: tuple[float, float, float, float], b: tuple[float, float, float, float]) -> float:
    ax0, ay0, aw, ah = a
    bx0, by0, bw, bh = b
    ax1, ay1, bx1, by1 = ax0 + aw, ay0 + ah, bx0 + bw, by0 + bh
    ix = max(0.0, min(ax1, bx1) - max(ax0, bx0))
    iy = max(0.0, min(ay1, by1) - max(ay0, by0))
    inter = ix * iy
    union = aw * ah + bw * bh - inter
    return inter / union if union > 0 else 0.0


def _center_in(a: tuple[float, float, float, float], b: tuple[float, float, float, float]) -> bool:
    cx, cy = a[0] + a[2] / 2, a[1] + a[3] / 2
    return b[0] <= cx <= b[0] + b[2] and b[1] <= cy <= b[1] + b[3]


def _values_match(key: str, truth: Any, predicted: str | None) -> bool:
    if predicted is None:
        return False
    if key in ("totalAmount", "vatAmount", "supplierBusinessNo", "issuedDate"):
        return _digits(str(truth)) == _digits(predicted)
    return str(truth).strip() == predicted.strip()


def score_sample(label: LabelRecord, parsed: _Boxes, report: Report) -> None:
    for key in FIELDS:
        truth = truth_boxes(label, key)
        if not truth:
            continue  # 정답 박스를 못 찾으면 채점하지 않는다 (라벨의 한계)
        score = report.of(key)
        score.judged += 1
        got: _Field = getattr(parsed, key)
        if _values_match(key, _label_value(label, key), got.value):
            score.value_correct += 1
        if got.box is None:
            continue
        score.box_present += 1
        box = (got.box.x, got.box.y, got.box.width, got.box.height)
        best = max((_iou(box, t) for t in truth), default=0.0)
        score.iou_sum += best
        if best >= 0.5:
            score.iou_half += 1
        if any(_center_in(box, t) for t in truth):
            score.center_hit += 1


def format_markdown(report: Report, extra: dict[str, Any]) -> str:
    lines = [
        f"# boundingBox 실측 — {report.samples}장 ({BOX_PROMPT_VERSION}, {extra.get('model')})",
        "",
        "| 필드 | 채점 | 값 정확 | 박스 냈나 | 중심 적중 | IoU ≥ 0.5 | 평균 IoU |",
        "| --- | ---: | ---: | ---: | ---: | ---: | ---: |",
    ]
    total = FieldScore()
    for key in FIELDS:
        s = report.per_field.get(key)
        if s is None or not s.judged:
            lines.append(f"| {key} | 0 | - | - | - | - | - |")
            continue
        for attr in ("judged", "value_correct", "box_present", "center_hit", "iou_half"):
            setattr(total, attr, getattr(total, attr) + getattr(s, attr))
        total.iou_sum += s.iou_sum
        lines.append(
            f"| {key} | {s.judged} | {s.value_correct / s.judged:.0%} "
            f"| {s.box_present / s.judged:.0%} | {s.center_hit / s.judged:.0%} "
            f"| {s.iou_half / s.judged:.0%} | {s.iou_sum / s.judged:.2f} |"
        )
    if total.judged:
        lines.append(
            f"| **합계** | {total.judged} | {total.value_correct / total.judged:.0%} "
            f"| {total.box_present / total.judged:.0%} | **{total.center_hit / total.judged:.0%}** "
            f"| **{total.iou_half / total.judged:.0%}** | {total.iou_sum / total.judged:.2f} |"
        )
    if report.failures:
        lines += ["", f"처리 실패 {len(report.failures)}건: {', '.join(report.failures[:5])}"]
    return "\n".join(lines)


async def run(args: argparse.Namespace) -> int:
    settings = Settings()
    claude = ClaudeClient(settings)
    labels = [label for _, label in iter_labels(args.dataset)]
    labels = labels[:: args.stride][: args.limit] if args.limit else labels[:: args.stride]
    if not labels:
        print("[실패] 라벨이 없습니다.")
        return 2
    if not any(label.meta.extra.get("textLayer") for label in labels):
        print("[실패] textLayer 가 없습니다 — synth_evidence 를 --with-boxes 로 다시 생성하세요.")
        return 2

    report = Report()
    args.out.mkdir(parents=True, exist_ok=True)
    predictions = args.out / "predictions"
    predictions.mkdir(exist_ok=True)
    model = settings.vision_model
    if args.rescore:
        print(f"재채점 — {len(labels)}장 (API 호출 없음)")
    else:
        print(f"좌표 실측 — {len(labels)}장, 모델 {model}, effort {settings.evidence_effort}")

    for index, label in enumerate(labels, start=1):
        report.samples += 1
        matches = list(args.dataset.rglob(label.image))
        if not matches:
            report.failures.append(f"{label.meta.source_id}(이미지 없음)")
            continue
        prepared = prepare_image(matches[0].read_bytes(), settings.image_long_edge)
        content = [
            {
                "type": "image",
                "source": {
                    "type": "base64",
                    "media_type": prepared.media_type,
                    "data": base64.standard_b64encode(prepared.data).decode("ascii"),
                },
            },
            {"type": "text", "text": f"증빙 유형: {label.evidence_type}. 스키마대로 반환하라."},
        ]
        cached = predictions / f"{label.meta.source_id}.json"
        if args.rescore:
            if not cached.exists():
                continue
            score_sample(label, _Boxes.model_validate_json(cached.read_text("utf-8")), report)
            continue
        try:
            response = await claude.parse(
                model=model,
                system=SYSTEM,
                messages=[{"role": "user", "content": content}],
                output_format=_Boxes,
                max_tokens=args.max_tokens,
                effort=settings.evidence_effort,  # type: ignore[arg-type]
            )
        except ClaudeError as exc:
            report.failures.append(f"{label.meta.source_id}({exc.stage})")
            print(f"  [실패] {label.meta.source_id}: {exc}")
            if exc.stage in ("config", "quota"):
                break
            continue
        parsed: _Boxes = response.parsed_output
        cached.write_text(parsed.model_dump_json(indent=2), encoding="utf-8")
        score_sample(label, parsed, report)
        spent = _cost(claude)
        if index % 5 == 0 or index == len(labels):
            print(f"  {index}/{len(labels)}, 추정 ${spent}")
        if spent is not None and spent >= args.budget_usd:
            print(f"[중단] 비용 상한 ${args.budget_usd} 도달")
            break

    extra = {"model": model, "estimatedCostUsd": _cost(claude), "promptVersion": BOX_PROMPT_VERSION}
    summary = format_markdown(report, extra)
    (args.out / "summary.md").write_text(summary + "\n", encoding="utf-8")
    (args.out / "metrics.json").write_text(
        json.dumps(
            {
                **extra,
                "samples": report.samples,
                "fields": {
                    key: {
                        "judged": s.judged,
                        "valueAccuracy": s.value_correct / s.judged if s.judged else None,
                        "boxPresentRate": s.box_present / s.judged if s.judged else None,
                        "centerHitRate": s.center_hit / s.judged if s.judged else None,
                        "iouHalfRate": s.iou_half / s.judged if s.judged else None,
                        "meanIou": s.iou_sum / s.judged if s.judged else None,
                    }
                    for key, s in report.per_field.items()
                },
                "usage": {m: t.snapshot() for m, t in claude.usage_by_model.items()},
            },
            ensure_ascii=False,
            indent=2,
        )
        + "\n",
        encoding="utf-8",
    )
    print()
    print(summary)
    print(f"\n비용 추정 ${extra['estimatedCostUsd']}")
    return 0


def _cost(claude: ClaudeClient) -> float | None:
    known = [
        cost
        for model, totals in claude.usage_by_model.items()
        if (cost := estimate_cost_usd(model, totals.snapshot())) is not None
    ]
    return round(sum(known), 4) if known else None


def main() -> int:
    parser = argparse.ArgumentParser(
        description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter
    )
    parser.add_argument("dataset", type=Path, help="--with-boxes 로 만든 라벨 폴더")
    parser.add_argument("--out", type=Path, required=True)
    parser.add_argument("--limit", type=int, default=None)
    parser.add_argument("--stride", type=int, default=1)
    parser.add_argument("--max-tokens", type=int, default=4096)
    parser.add_argument("--budget-usd", type=float, default=0.40)
    parser.add_argument(
        "--rescore", action="store_true", help="저장된 예측만 다시 채점 (API 호출 없음)"
    )
    return asyncio.run(run(parser.parse_args()))


if __name__ == "__main__":
    raise SystemExit(main())
