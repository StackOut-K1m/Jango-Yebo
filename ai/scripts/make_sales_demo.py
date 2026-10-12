"""매출 인쇄물 시연(P1-22, 명세 20.9) — POS 「상품별 매출현황」 한 장에서 표를 읽는다.

증빙 시연(`make_demo.py`)과 짝이다. 껍데기는 `demo_shell.py` 를 함께 쓴다.

    uv run --frozen python -X utf8 -m scripts.make_sales_demo

**발표장에서 모델을 부르지 않는다** — `artifacts/eval/sales-003` 의 저장된 예측을 재생한다.

## 무엇을 보이나

증빙과 이야기가 다르다. 증빙은 「값 몇 개를 정확히 읽는가」이고, 매출 인쇄물은 **「표를 통째로,
열이 줄어드는 종이에서도 읽는가」** 다. 그래서 네 표본을 이 순서로 고른다.

1. **정상 (80mm, 6열)** — 17행을 그대로 읽는다. 검산 통과
2. **좁은 종이 (58mm, 4열)** — 감열지가 좁아 열이 줄어든다. 없는 열을 지어내지 않는 것이 요구 3
3. **검산이 잡은 것** — 행 안의 금액이 서로 맞지 않는다. 사용자가 볼 곳을 짚어 준다
4. **검산이 못 잡는 것** — 행 전체가 밀렸다. **한계를 감추지 않는다**

4번을 넣는 이유가 중요하다. 실측에서 숫자가 틀린 57행 중 52행(91.2%)이 이 계열이고, 열이 함께
움직여 행 안의 산술이 그대로 성립한다 — 어떤 검산으로도 볼 수 없다. 발표에서 "다 잡습니다" 라고
말할 수 없는 이유를 화면으로 보이는 편이 낫다.
"""

from __future__ import annotations

import argparse
import base64
import html
import json
import sys
from dataclasses import dataclass
from pathlib import Path
from typing import Any

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

from app.ocr.sales_validators import validate_product_sales  # noqa: E402
from app.ocr.schemas import ProductSalesExtraction, ProductSalesValidation  # noqa: E402
from experiments import evaluate_product_sales as eps  # noqa: E402
from experiments.labels import iter_product_sales_labels  # noqa: E402
from scripts.demo_shell import SHELL_CSS, SHELL_JS  # noqa: E402

#: (표본, 제목, 무엇을 보이나). 근거는 모듈 docstring.
CURATED = (
    ("ps-printout80-7-000", "정상 — 80mm 인쇄물 6열", "17행을 그대로 읽고 검산도 통과합니다"),
    ("ps-printout58-7-002", "좁은 종이 — 58mm 4열", "열이 줄어든 것을 알아채고 없는 열을 비웁니다"),
    ("ps-printout80-7-012", "검산이 잡았다", "행 안의 금액이 서로 맞지 않습니다"),
    ("ps-screen-7-021", "검산이 못 잡는다", "행 전체가 밀렸습니다 — 행 안의 산술은 성립합니다"),
)

COLUMN_NAMES = {
    "productName": "상품명",
    "quantity": "수량",
    "grossAmount": "판매금액",
    "discountAmount": "할인",
    "netAmount": "공급가액",
    "vatAmount": "부가세",
    "billedAmount": "결제금액",
    "share": "구성비",
}
#: 계약의 camelCase 열 이름 → `ProductSalesRow` 필드.
FIELDS = {
    "quantity": "quantity",
    "grossAmount": "gross_amount",
    "discountAmount": "discount_amount",
    "netAmount": "net_amount",
    "vatAmount": "vat_amount",
    "billedAmount": "billed_amount",
    "share": "share",
}


@dataclass
class Sample:
    source_id: str
    headline: str
    shows: str
    layout: str
    image_data_uris: list[str]
    predicted: ProductSalesExtraction
    truth: ProductSalesExtraction
    report: ProductSalesValidation
    wrong_rows: set[int]


def _data_uri(path: Path) -> str:
    return f"data:image/jpeg;base64,{base64.b64encode(path.read_bytes()).decode()}"


def _same_numbers(left: Any, right: Any) -> bool:
    columns = ("quantity", "gross_amount", "discount_amount", "net_amount",
               "vat_amount", "billed_amount", "share")  # fmt: skip
    return all(
        eps._values_equal(c, getattr(left, c), getattr(right, c)) is not False for c in columns
    )


def collect(dataset: Path, run: Path) -> list[Sample]:
    """큐레이션한 표본만 저장된 예측에서 꺼낸다. 모델을 부르지 않는다."""
    wanted = {sid: (headline, shows) for sid, headline, shows in CURATED}
    # 이미지는 라벨 파일과 같은 디렉터리에 있다(`printout/` · `screen/`).
    homes = {
        label.meta.source_id: path.parent for path, label in iter_product_sales_labels(dataset)
    }
    found: dict[str, Sample] = {}
    for label, record in eps.load_predictions(dataset, run):
        source_id = label.meta.source_id
        if source_id not in wanted or record.extraction is None:
            continue
        predicted, truth = record.extraction, label.extraction
        matched, _, _ = eps.match_rows(truth.items, predicted.items)
        wrong = {
            pred_index
            for label_index, pred_index in matched
            if not _same_numbers(truth.items[label_index], predicted.items[pred_index])
        }
        headline, shows = wanted[source_id]
        home = homes[source_id]
        found[source_id] = Sample(
            source_id=source_id,
            headline=headline,
            shows=shows,
            layout=source_id.split("-")[1],
            image_data_uris=[_data_uri(home / name) for name in label.images],
            predicted=predicted,
            truth=truth,
            report=validate_product_sales(predicted),
            wrong_rows=wrong,
        )
    missing = [sid for sid, _, _ in CURATED if sid not in found]
    if missing:
        raise SystemExit(f"[실패] 저장된 예측에 없다: {missing} — {run} 를 먼저 만든다")
    return [found[sid] for sid, _, _ in CURATED]


def _num(value: Any, column: str) -> str:
    if value is None:
        return '<span class="na">·</span>'
    if column == "share":
        return f"{value * 100:.1f}%"
    if column == "quantity":
        return f"{value:,.0f}"
    return f"{value:,.0f}"


def _rows(sample: Sample) -> str:
    columns = [c for c in sample.predicted.columns_detected if c != "productName"]
    out = []
    for index, row in enumerate(sample.predicted.items):
        cells = "".join(
            f'<td class="n">{_num(getattr(row, FIELDS[c]), c)}</td>'
            if c in COLUMN_NAMES
            else "<td></td>"
            for c in columns
        )
        classes = []
        if index in sample.report.inconsistent_rows:
            classes.append("flag")
        if index in sample.wrong_rows:
            classes.append("wrong")
        cls = f' class="{" ".join(classes)}"' if classes else ""
        out.append(f"<tr{cls}><td>{html.escape(row.product_name)}</td>{cells}</tr>")
    return "\n".join(out)


def _checks(report: ProductSalesValidation) -> str:
    items = (
        ("행 안의 산술", report.row_arithmetic_consistent),
        ("합계 행 체크섬", report.printed_total_consistent),
    )
    out = []
    for name, value in items:
        cls = "na" if value is None else ("ok" if value else "bad")
        mark = "판정 불가" if value is None else ("통과" if value else "불일치")
        out.append(f'<li class="{cls}"><span>{name}</span><b>{mark}</b></li>')
    return "\n".join(out)


def _panel(sample: Sample) -> str:
    report = sample.report
    gate = "need" if report.needs_review else "fine"
    label = "검토 필요" if report.needs_review else "검토 불필요"
    reasons = (
        "".join(f"<code>{r}</code>" for r in report.review_reasons)
        or '<span class="none">사유 없음</span>'
    )
    issues = "".join(f"<li>{html.escape(i)}</li>" for i in report.issues)
    return f"""<div class="panel">
  <span class="gate {gate}">{label}</span>
  <div class="reasons">{reasons}</div>
  {f'<ul class="issues">{issues}</ul>' if issues else ""}
  <ul class="checks">{_checks(report)}</ul>
</div>"""


def _period(extraction: ProductSalesExtraction) -> str:
    period = extraction.period
    if period.source is None:
        return "기간 <b>인쇄 안 됨</b> — 추론하지 않습니다"
    return f"기간 <b>{period.start} ~ {period.end}</b> (헤더에서 읽음)"


def provenance(run: Path) -> str:
    """예측을 **실제로 만든** 모델·프롬프트를 적는다 (`make_demo.py` 와 같은 이유)."""
    meta = json.loads((run / "run.json").read_text(encoding="utf-8"))
    models = " · ".join(meta.get("modelNames") or ["?"])
    prompts = " · ".join(meta.get("promptVersions") or ["?"])
    return f"모델 <b>{models}</b> · 프롬프트 <b>{prompts}</b>"


def render(samples: list[Sample], *, cost: str, source: str) -> str:
    tabs = "".join(
        f'<button class="tab{" on" if i == 0 else ""}" data-i="{i}">'
        f"{html.escape(s.headline)}</button>"
        for i, s in enumerate(samples)
    )
    cards = []
    for i, s in enumerate(samples):
        columns = [c for c in s.predicted.columns_detected if c != "productName"]
        head = "".join(f'<th class="n">{COLUMN_NAMES.get(c, c)}</th>' for c in columns)
        total = s.predicted.printed_total
        total_row = (
            "".join(
                f'<td class="n">{_num(getattr(total, FIELDS[c], None), c)}</td>'
                if c in FIELDS
                else "<td></td>"
                for c in columns
            )
            if total
            else '<td colspan="9">합계 행을 읽지 못했습니다</td>'
        )
        cards.append(f"""
<section class="card" data-i="{i}"{"" if i == 0 else " hidden"}>
  <div class="meta"><b>{html.escape(s.headline)}</b> · {s.layout}
    · <span class="shows">{html.escape(s.shows)}</span>
    · <span class="sid">{s.source_id}</span></div>
  <div class="meta">{_period(s.predicted)} · 읽은 열 <b>{len(columns) + 1}개</b>
    · 상품 <b>{len(s.predicted.items)}행</b></div>
  <div class="split">
    <figure>{"".join(f'<img src="{u}" alt="매출 인쇄물">' for u in s.image_data_uris)}</figure>
    <div class="right">
      <table class="rows"><thead><tr><th>상품명</th>{head}</tr></thead>
        <tbody>{_rows(s)}</tbody>
        <tfoot><tr class="total"><td>합계 행</td>{total_row}</tr></tfoot>
      </table>
    </div>
  </div>
  <div class="panels">{_panel(s)}</div>
</section>""")

    return f"""<!doctype html>
<html lang="ko"><head><meta charset="utf-8">
<title>매출 인쇄물 시연 — 잔고예보</title>
<style>{SHELL_CSS}
.rows .n {{ text-align: right; font-variant-numeric: tabular-nums; }}
.rows .na {{ color: #c8ccd2; }}
.rows tr.flag td {{ background: #fef2f2; }}
.rows tr.flag td:first-child::after {{ content: " ⚠"; color: #b91c1c; }}
.rows tr.wrong td {{ box-shadow: inset 0 -1px 0 #fca5a5; }}
body.reveal .rows tr.wrong td {{ background: #fff7ed; }}
body.reveal .rows tr.wrong td:first-child::after {{ content: " ← 라벨과 다름";
  color: #b45309; font-size: 11px; }}
.rows tfoot .total td {{ border-top: 2px solid #d6d9df; font-weight: 600; }}
</style></head><body>
<header>
  <h1>매출 인쇄물 시연 — POS 한 장에서 상품별 표를 통째로</h1>
  <div class="sub">{source} · 표본당 <b>{cost}</b> · 합성 인쇄물(우리가 생성)
    · 저장된 결과를 재생하므로 발표 중 모델을 부르지 않습니다</div>
</header>
<div class="bar">
  <button id="reveal">라벨과 다른 행 보기</button>
  <span>AI 가 숫자를 잘못 읽은 행이 주황색으로 나타납니다. 붉은 행은 <b>검산이 스스로 걸러낸</b>
    것이고, 둘이 다르다는 점이 이 시연의 요점입니다.</span>
</div>
<div class="tabs">{tabs}</div>
{"".join(cards)}
<footer>검산은 워커 한 곳(<code>app/ocr/sales_validators.py</code>)에만 있습니다. 합성 90표본
1,059 라벨 행에서 검산식은 전부 성립하고, 검토 게이트는 <b>2.2%</b>(정밀도 2/2)입니다. 다만
숫자가 틀린 행의 <b>91.2%는 「행 전체가 밀린 것」</b>이라 행 안의 산술이 그대로 성립합니다 —
검산으로 볼 수 없고, 네 번째 표본이 그 경우입니다.
</footer>
<script>{SHELL_JS}</script></body></html>
"""


def main() -> int:
    parser = argparse.ArgumentParser(
        description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter
    )
    parser.add_argument("--dataset", type=Path, default=Path("datasets/product-sales"))
    parser.add_argument("--run", type=Path, default=Path("artifacts/eval/sales-003"))
    parser.add_argument("--out", type=Path, default=Path("artifacts/demo-sales"))
    parser.add_argument("--cost", default="$0.024")
    args = parser.parse_args()

    samples = collect(args.dataset, args.run)
    args.out.mkdir(parents=True, exist_ok=True)
    target = args.out / "index.html"
    target.write_text(
        render(samples, cost=args.cost, source=provenance(args.run)), encoding="utf-8"
    )
    size = target.stat().st_size // 1024
    print(f"매출 인쇄물 시연 {len(samples)}표본 → {target} ({size} KB, 자기완결)")
    for s in samples:
        gate = "검토 필요" if s.report.needs_review else "검토 불필요"
        print(f"  {s.source_id:22s} {gate:8s} 행 {len(s.predicted.items):2d} "
              f"· 라벨과 다른 행 {len(s.wrong_rows):2d}")  # fmt: skip
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
