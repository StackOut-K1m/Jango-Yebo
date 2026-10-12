"""시연 자료를 만든다 — 자기완결 HTML 하나. 네트워크·서버·API 호출이 필요 없다.

    uv run --frozen python -X utf8 -m scripts.make_demo

산출물은 `artifacts/demo/index.html` 이다(gitignore 대상 — 이 스크립트가 정본이고 언제든 다시
만든다). 이미지는 data URI 로 박아 넣어 파일 하나만 들고 다니면 되고, 발표장 와이파이가 없어도
열린다. **발표장에서 모델을 부르지 않는다** — 저장된 예측(`artifacts/eval/synth-001`)을 읽는다.

시연에 KORIE 이미지를 쓰지 않는다. 라이선스가 표기되지 않은 데이터라 내부 평가용으로만 두고,
화면에 띄우는 것은 **우리가 만든 합성 이미지**뿐이다(`synth_evidence.py`, seed 11).

큐레이션 — 실측에서 실제로 이렇게 나온 건들이다. 정상 한 장으로 시작해 검사 3종이 각각 무엇을
잡는지 보인다.

| 표본 | 보여줄 것 |
| --- | --- |
| 세금계산서 (정상) | 필드·품목 전부 정확, 검산 통과, 검토 불필요 |
| 현금영수증 009 | **금액 칸 밀림** — 합계 검산은 통과하는데 세율 검사가 잡는다 (v1.14) |
| 현금영수증 005 | **품목 이름 열 밀림** — 산술이 다 통과, 구조 검사만 잡는다 (v1.15) |
| 현금영수증 012 | 사업자번호 한 자리 오독 — 체크섬이 잡는다 |

009 는 "고친 뒤" 검산 결과도 미리 계산해 넣는다. 화면의 버튼이 그것으로 바꿔 사유가 사라지는 것을
보인다 — 실제 서비스에서는 `POST /internal/v1/ai/evidence-validation`(모델 호출 없음)이 그 역할이다.
JS 로 검산식을 다시 구현하지 않는다. 같은 식이 두 곳에 살면 드리프트한다.
"""

from __future__ import annotations

import argparse
import base64
import html
import json
from dataclasses import dataclass
from pathlib import Path
from typing import Any

from app.ocr.prompts import EVIDENCE_PROMPT_VERSION
from app.ocr.schemas import EvidenceExtraction, ValidationReport
from app.ocr.validators import validate_extraction
from experiments.labels import iter_labels
from scripts.demo_shell import SHELL_CSS, SHELL_JS

# (source_id, 한 줄 설명, 이 표본으로 보여줄 검사)
CURATED = (
    ("synth-tax-invoice-000", "정상 — 전자세금계산서", "검산 전부 통과, 검토 불필요"),
    ("synth-cash-receipt-009", "금액 칸이 밀렸다", "합계 검산은 통과 · 부가세 세율 검사가 잡음"),
    ("synth-cash-receipt-005", "품목 이름 열이 밀렸다", "산술은 전부 통과 · 구조 검사만 잡음"),
    ("synth-cash-receipt-012", "사업자번호 한 자리 오독", "체크섬이 잡음"),
)
FIELD_LABELS = {
    "supplierName": "상호",
    "supplierBusinessNo": "사업자등록번호",
    "issuedDate": "발행일",
    "supplyAmount": "공급가액",
    "taxableAmount": "과세물품",
    "taxFreeAmount": "면세물품",
    "vatAmount": "부가세",
    "discountAmount": "할인",
    "totalAmount": "합계",
}
CHECK_LABELS = {
    "amountsConsistent": "금액 검산 (과세+부가세+면세=합계)",
    "vatRatioConsistent": "부가세 = 과세분 × 10%",
    "itemsSumConsistent": "품목 합 ≒ 합계",
    "itemsAligned": "품목 행 정렬",
    "businessNoValid": "사업자번호 체크섬",
    "dateValid": "발행일 범위",
}


@dataclass
class Sample:
    source_id: str
    headline: str
    shows: str
    image_data_uri: str
    evidence_type: str
    predicted: EvidenceExtraction
    truth: EvidenceExtraction
    report: ValidationReport
    fixed_report: ValidationReport | None  # 사용자가 고친 뒤 (밀림 표본만)


def _data_uri(path: Path) -> str:
    encoded = base64.standard_b64encode(path.read_bytes()).decode("ascii")
    return f"data:image/jpeg;base64,{encoded}"


def _corrected(predicted: EvidenceExtraction, truth: EvidenceExtraction) -> EvidenceExtraction:
    """사용자가 검토 화면에서 틀린 헤더 금액·날짜·상호를 바로잡은 상태."""
    fixed = predicted.model_copy(deep=True)
    for key in FIELD_LABELS:
        snake = "".join(f"_{c.lower()}" if c.isupper() else c for c in key)
        setattr(fixed, snake, getattr(truth, snake))
    return fixed


def collect(dataset: Path, run: Path) -> list[Sample]:
    labels = {label.meta.source_id: label for _, label in iter_labels(dataset)}
    samples: list[Sample] = []
    for source_id, headline, shows in CURATED:
        label = labels.get(source_id)
        prediction_path = run / "predictions" / f"{source_id}.json"
        if label is None or not prediction_path.exists():
            print(f"  [건너뜀] {source_id} — 라벨 또는 저장된 예측이 없다")
            continue
        raw = json.loads(prediction_path.read_text(encoding="utf-8")).get("extraction")
        if not raw:
            continue
        predicted = EvidenceExtraction.model_validate(raw)
        report = validate_extraction(predicted)
        fixed_report = None
        if report.needs_review:
            fixed_report = validate_extraction(_corrected(predicted, label.extraction))
        image = next(iter(dataset.rglob(label.image)), None)
        if image is None:
            print(f"  [건너뜀] {source_id} — 이미지 {label.image} 를 찾지 못했다")
            continue
        samples.append(
            Sample(
                source_id=source_id,
                headline=headline,
                shows=shows,
                image_data_uri=_data_uri(image),
                evidence_type=label.evidence_type,
                predicted=predicted,
                truth=label.extraction,
                report=report,
                fixed_report=fixed_report,
            )
        )
    return samples


def _won(value: Any) -> str:
    return f"{value:,}" if isinstance(value, int) else "—"


def _field_rows(sample: Sample) -> str:
    rows = []
    for key, name in FIELD_LABELS.items():
        snake = "".join(f"_{c.lower()}" if c.isupper() else c for c in key)
        got = getattr(sample.predicted, snake)
        want = getattr(sample.truth, snake)
        if got is None and want is None:
            continue
        shown = _won(got) if isinstance(got, int) or got is None else html.escape(str(got))
        expected = _won(want) if isinstance(want, int) or want is None else html.escape(str(want))
        wrong = got != want
        confidence = sample.predicted.field_confidence.get(key)
        low = confidence is not None and confidence < 0.7
        badge = f'<span class="conf">{confidence:.2f}</span>' if confidence is not None else ""
        rows.append(
            f'<tr class="{"low" if low else ""}"><th>{name}</th>'
            f'<td class="v">{shown}{badge}</td>'
            f'<td class="truth {"wrong" if wrong else ""}">{expected if wrong else ""}</td></tr>'
        )
    return "\n".join(rows)


def _item_rows(sample: Sample) -> str:
    rows = []
    for item in sample.predicted.items:
        rows.append(
            f"<tr><td>{html.escape(item.raw_name)}</td>"
            f"<td class='n'>{item.quantity if item.quantity is not None else '—'}</td>"
            f"<td class='n'>{_won(item.unit_price)}</td>"
            f"<td class='n'>{_won(item.amount)}</td></tr>"
        )
    return "\n".join(rows)


def _checks(report: ValidationReport) -> str:
    cells = []
    data = report.model_dump(by_alias=True)
    for key, name in CHECK_LABELS.items():
        value = data.get(key)
        mark, cls = {True: ("통과", "ok"), False: ("불일치", "bad"), None: ("판정 불가", "na")}[
            value
        ]
        cells.append(f'<li class="{cls}"><span>{name}</span><b>{mark}</b></li>')
    return "\n".join(cells)


def _panel(report: ValidationReport, *, fixed: bool) -> str:
    state = (
        '<div class="gate need">검토 필요</div>'
        if report.needs_review
        else '<div class="gate fine">검토 불필요</div>'
    )
    issues = "".join(f"<li>{html.escape(i)}</li>" for i in report.issues)
    reasons = "".join(f"<code>{r}</code>" for r in report.review_reasons)
    return (
        f'<div class="panel{" fixed" if fixed else ""}">{state}'
        f'<div class="reasons">{reasons or "<span class=none>사유 없음</span>"}</div>'
        f'<ul class="issues">{issues}</ul>'
        f'<ul class="checks">{_checks(report)}</ul></div>'
    )


def provenance(run: Path) -> str:
    """화면에 **실제 예측을 만든** 모델·프롬프트를 적는다.

    전에는 코드 상수 `EVIDENCE_PROMPT_VERSION` 을 찍었다. 시연은 저장된 예측을 재생하는데
    그 예측이 옛 프롬프트로 만들어졌으면 **화면이 거짓말을 한다** — 실제로 v3 예측을 두고
    "프롬프트 v4" 라고 적고 있었다(2026-09-10 발견).
    """
    meta = json.loads((run / "run.json").read_text(encoding="utf-8"))
    models = " · ".join(meta.get("modelNames") or ["?"])
    prompts = " · ".join(meta.get("promptVersions") or ["?"])
    live = EVIDENCE_PROMPT_VERSION
    tail = "" if prompts == live else f' <span style="color:#b45309">(현재 코드는 {live})</span>'
    return f"모델 <b>{models}</b> · 프롬프트 <b>{prompts}</b>{tail}"


def render(samples: list[Sample], *, cost_per_image: str, provenance: str) -> str:
    tabs = "".join(
        f'<button class="tab{" on" if i == 0 else ""}" data-i="{i}">'
        f"{html.escape(s.headline)}</button>"
        for i, s in enumerate(samples)
    )
    cards = []
    for i, s in enumerate(samples):
        fixed = (
            f'<button class="apply" data-i="{i}">사용자가 바로잡음 →</button>'
            if s.fixed_report is not None
            else ""
        )
        fixed_panel = _panel(s.fixed_report, fixed=True) if s.fixed_report is not None else ""
        cards.append(f"""
<section class="card" data-i="{i}" {"hidden" if i else ""}>
  <div class="meta"><b>{html.escape(s.headline)}</b> · {s.evidence_type}
    · <span class="shows">{html.escape(s.shows)}</span>
    · <span class="sid">{s.source_id}</span></div>
  <div class="split">
    <figure><img src="{s.image_data_uri}" alt="증빙 이미지"></figure>
    <div class="right">
      <table class="fields"><thead><tr><th></th><th>AI 가 읽은 값</th>
        <th class="th-truth">실제</th></tr></thead><tbody>{_field_rows(s)}</tbody></table>
      <table class="items"><thead><tr><th>품목</th><th class="n">수량</th>
        <th class="n">단가</th><th class="n">금액</th></tr></thead>
        <tbody>{_item_rows(s)}</tbody></table>
    </div>
  </div>
  <div class="panels">{_panel(s.report, fixed=False)}{fixed_panel}</div>
  {fixed}
</section>""")

    return f"""<!doctype html>
<html lang="ko"><head><meta charset="utf-8">
<title>증빙 인식 시연 — 잔고예보</title>
<style>{SHELL_CSS}
.fields th {{ width: 34%; color: #555; font-weight: 500; }}
.fields .v {{ font-variant-numeric: tabular-nums; }}
.conf {{ margin-left: 8px; font-size: 11px; color: #8a8f98; }}
tr.low .v {{ background: #fff7ed; box-shadow: inset 3px 0 0 #f59e0b; }}
tr.low th::after {{ content: " ⚠"; color: #f59e0b; }}
.items .n {{ text-align: right; font-variant-numeric: tabular-nums; }}
.apply {{ margin-top: 14px; padding: 9px 16px; border: 0; border-radius: 8px; background: #1f2937;
  color: #fff; font: inherit; font-size: 13px; cursor: pointer; }}
</style></head><body>
<header>
  <h1>증빙 인식 시연 — 촬영 한 장에서 항목 추출 + 검산</h1>
  <div class="sub">{provenance} · 장당 <b>{cost_per_image}</b> · 합성 증빙(우리가 생성)
    · 저장된 결과를 재생하므로 발표 중 모델을 부르지 않습니다</div>
</header>
<div class="bar">
  <button id="reveal">실제 값 보기</button>
  <span>AI 가 틀린 곳이 빨간 글씨로 나타납니다. 실제 서비스 화면에는 이 열이 없습니다.</span>
</div>
<div class="tabs">{tabs}</div>
{"".join(cards)}
<footer>검산 규칙은 워커 한 곳(<code>app/ocr/validators.py</code>)에만 있습니다. 사용자가 값을
고친 뒤의 재검산은 <code>POST /internal/v1/ai/evidence-validation</code>(모델 호출 없음)이
담당하며, 이 페이지는 그 결과를 미리 계산해 담았습니다 — 화면에서 검산식을 다시 구현하지 않습니다.
</footer>
<script>{SHELL_JS}</script></body></html>
"""


def main() -> int:
    parser = argparse.ArgumentParser(
        description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter
    )
    parser.add_argument("--dataset", type=Path, default=Path("datasets/receipts/synthetic"))
    parser.add_argument("--run", type=Path, default=Path("artifacts/eval/synth-001"))
    parser.add_argument("--out", type=Path, default=Path("artifacts/demo"))
    parser.add_argument("--cost", default="$0.0106")
    args = parser.parse_args()

    if not (args.run / "predictions").exists():
        print(f"[실패] {args.run}/predictions 가 없다. 먼저 측정하거나 다른 --run 을 준다.")
        return 2
    samples = collect(args.dataset, args.run)
    if not samples:
        print("[실패] 큐레이션 표본을 하나도 만들지 못했다.")
        return 2

    args.out.mkdir(parents=True, exist_ok=True)
    target = args.out / "index.html"
    target.write_text(
        render(samples, cost_per_image=args.cost, provenance=provenance(args.run)),
        encoding="utf-8",
    )
    size = target.stat().st_size / 1024
    print(f"시연 자료 {len(samples)}표본 → {target} ({size:.0f} KB, 자기완결)")
    for s in samples:
        gate = "검토 필요" if s.report.needs_review else "검토 불필요"
        print(f"  {s.source_id:26} {gate:8} {','.join(s.report.review_reasons) or '-'}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
