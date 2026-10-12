"""숫자가 틀린 행을 두 계열로 가른다 — 「행 밀림」과 「진짜 오독」.

§6.8 검산(계약 v1.18)을 넣을 때 검출률을 **2/57 = 3.5%** 로 보고했다. 그 숫자는 검사를
부당하게 깎는다. 분모 57 안에 **행 내부 검사로는 원리적으로 볼 수 없는 행**이 섞여 있다.

**가르는 방법.** 예측한 숫자 뭉치(수치 7열)를 **이웃 라벨 행의 뭉치와 대조**한다. 그대로
일치하면 값을 잘못 읽은 것이 아니라 **다른 행의 값을 받아온 것**이다. 열이 통째로 움직였으니
행 안의 산술(판매−할인=공급가+부가세 등)이 그대로 성립한다 — 어떤 검산도 못 본다.

비교 기준은 평가기(`evaluate_product_sales`)의 `_values_equal` 을 그대로 쓴다. "틀린 행" 의
정의가 두 곳에서 갈라지면 두 측정을 비교할 수 없다.

**구성비(share) 정합 검사도 함께 재 본다.** `결제금액 / 구성비` 는 표본 안에서 일정해야 하고
(둘 다 같은 기간 합계를 분모로 하므로), 합계 행을 안 쓰니 `Σ품목 = 합계 행` 이 깨지는 문제도
비켜간다. 쓸 수 있는 식인지, 이 계열을 잡는지 확인한다.

라벨과 **캐시된 예측만** 읽는다. 모델을 부르지 않아 비용 0 이다.

    uv run --frozen python -X utf8 -m experiments.analyze_row_shift \
        datasets/product-sales --out artifacts/eval/sales-003
"""

from __future__ import annotations

import argparse
import sys
from collections import Counter
from pathlib import Path
from statistics import median

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

from app.ocr.sales_validators import check_rows  # noqa: E402
from app.ocr.schemas import ProductSalesExtraction, ProductSalesRow  # noqa: E402
from experiments import evaluate_product_sales as eps  # noqa: E402

#: 수치 열. 상품명은 뺀다 — 이름은 제자리에 있고 숫자만 움직이는 것이 이 계열의 정의다.
NUMERIC_COLUMNS = (
    "quantity",
    "gross_amount",
    "discount_amount",
    "net_amount",
    "vat_amount",
    "billed_amount",
    "share",
)

#: 몇 칸까지 밀린 것으로 볼지. 앞뒤 3행이면 실제 관측(±1)을 넉넉히 덮는다.
SHIFT_RANGE = (1, -1, 2, -2, 3, -3)


def same_numbers(left: ProductSalesRow, right: ProductSalesRow) -> bool:
    """수치 열이 전부 같은가. 평가기와 같은 허용범위를 쓴다."""
    return all(
        eps._values_equal(column, getattr(left, column), getattr(right, column)) is not False
        for column in NUMERIC_COLUMNS
    )


def shift_distance(
    labeled: list[ProductSalesRow], index: int, predicted: ProductSalesRow
) -> int | None:
    """예측 행이 몇 칸 떨어진 라벨 행의 숫자 뭉치를 받아왔나. 아니면 None."""
    for distance in SHIFT_RANGE:
        neighbor = index + distance
        if 0 <= neighbor < len(labeled) and same_numbers(labeled[neighbor], predicted):
            return distance
    return None


def share_outliers(extraction: ProductSalesExtraction, *, slack: float = 1.6) -> set[int]:
    """`결제금액 / 구성비` 가 표본 안의 중앙값에서 벗어난 행.

    구성비는 0.1%p 로 반올림되어 인쇄되므로 작은 구성비 행은 상대오차가 크다 —
    허용범위를 `0.05 / 구성비` 로 잡아야 정상 행을 잡지 않는다.
    """
    rows = [
        (index, row.billed_amount / row.share, 0.05 / row.share)
        for index, row in enumerate(extraction.items)
        if row.share and row.billed_amount
    ]
    if len(rows) < 3:  # 중앙값을 합의로 쓸 수 없다
        return set()
    consensus = median(value for _, value, _ in rows)
    return {
        index
        for index, value, relative in rows
        if abs(value - consensus) / consensus > max(relative * slack, 0.005)
    }


def analyze(dataset: Path, out: Path, *, slack: float) -> int:
    pairs = eps.load_predictions(dataset, out)
    if not pairs:
        print(f"[실패] 저장된 예측이 없습니다: {out / 'predictions'}")
        return 1

    shifts: Counter[int] = Counter()
    misreads = 0
    caught_shift = caught_misread = 0
    share_caught_shift = share_caught_misread = share_false = 0
    by_layout: Counter[str] = Counter()
    label_rows_by_layout: Counter[str] = Counter()
    column_combos: Counter[tuple[str, ...]] = Counter()
    samples_hit: set[str] = set()

    for label, record in pairs:
        layout = label.meta.source_id.split("-")[1]
        label_rows_by_layout[layout] += len(label.extraction.items)
        prediction = record.extraction
        if prediction is None:
            continue

        matched, _, _ = eps.match_rows(label.extraction.items, prediction.items)
        _, arithmetic_flagged = check_rows(prediction.items)
        arithmetic = set(arithmetic_flagged)
        share = share_outliers(prediction, slack=slack)
        wrong_in_sample: set[int] = set()

        for label_index, pred_index in matched:
            labeled = label.extraction.items[label_index]
            predicted = prediction.items[pred_index]
            if same_numbers(labeled, predicted):
                continue
            wrong_in_sample.add(pred_index)
            by_layout[layout] += 1
            samples_hit.add(label.meta.source_id)
            column_combos[
                tuple(
                    column
                    for column in NUMERIC_COLUMNS
                    if eps._values_equal(
                        column, getattr(labeled, column), getattr(predicted, column)
                    )
                    is False
                )
            ] += 1

            distance = shift_distance(label.extraction.items, label_index, predicted)
            if distance is None:
                misreads += 1
                caught_misread += pred_index in arithmetic
                share_caught_misread += pred_index in share
            else:
                shifts[distance] += 1
                caught_shift += pred_index in arithmetic
                share_caught_shift += pred_index in share

        share_false += len(share - wrong_in_sample)

    _report(
        shifts=shifts,
        misreads=misreads,
        caught_shift=caught_shift,
        caught_misread=caught_misread,
        share_caught=share_caught_shift + share_caught_misread,
        share_false=share_false,
        by_layout=by_layout,
        label_rows_by_layout=label_rows_by_layout,
        column_combos=column_combos,
        samples_hit=len(samples_hit),
        samples=len(pairs),
    )
    return 0


def _pct(numerator: int, denominator: int) -> str:
    return "-" if not denominator else f"{numerator / denominator * 100:.1f}%"


def _report(
    *,
    shifts: Counter[int],
    misreads: int,
    caught_shift: int,
    caught_misread: int,
    share_caught: int,
    share_false: int,
    by_layout: Counter[str],
    label_rows_by_layout: Counter[str],
    column_combos: Counter[tuple[str, ...]],
    samples_hit: int,
    samples: int,
) -> None:
    shifted = sum(shifts.values())
    wrong = shifted + misreads

    print(f"\n## 숫자가 틀린 행 {wrong}행 ({samples_hit}/{samples} 표본)\n")
    print("| 계열 | 행 | 비중 | 행 산술이 잡은 것 |")
    print("| --- | ---: | ---: | ---: |")
    print(
        f"| 이웃 행의 숫자 뭉치를 그대로 받음 (**행 밀림**) | {shifted} | "
        f"{_pct(shifted, wrong)} | {caught_shift} |"
    )
    print(
        f"| 값 자체를 잘못 읽음 (**진짜 오독**) | {misreads} | "
        f"{_pct(misreads, wrong)} | **{caught_misread}** |"
    )

    print(
        "\n밀린 방향·칸수 (양수 = 뒤쪽 행의 숫자를 받음): "
        + ", ".join(f"{d:+d}칸 {n}행" for d, n in sorted(shifts.items()))
    )

    print("\n### 검출률을 어떻게 적어야 하나\n")
    print(
        f"- 전체 기준 **{_pct(caught_shift + caught_misread, wrong)}** "
        f"({caught_shift + caught_misread}/{wrong}) — 분모에 행 밀림 {shifted}행이 들어 있다."
    )
    print(
        f"- 검사가 겨냥한 계열 기준 **{_pct(caught_misread, misreads)}** "
        f"({caught_misread}/{misreads}) — 이것이 실제 성능이다."
    )
    print("- 행 밀림은 열이 함께 움직여 행 안의 산술이 성립한다. 검산으로 잡을 수 없고,")
    print("  좌표(v1.16 실측: 중심 적중 33%)나 재판독 같은 **행 밖의 근거**가 필요하다.")

    print("\n### 레이아웃별\n")
    print("| 레이아웃 | 라벨 행 | 숫자 오답 | 비율 |")
    print("| --- | ---: | ---: | ---: |")
    for layout in sorted(label_rows_by_layout):
        rows = label_rows_by_layout[layout]
        print(f"| {layout} | {rows} | {by_layout[layout]} | {_pct(by_layout[layout], rows)} |")

    print("\n### 어느 열이 함께 틀리나 (상위 5)\n")
    for combo, count in column_combos.most_common(5):
        print(f"- {count:3d}행  {', '.join(combo) if combo else '(없음)'}")

    print("\n### 구성비 정합 검사를 세워 보면\n")
    print(f"- 이 계열 검출 **{share_caught}행** / {wrong}행 · 맞는 행을 잡은 것 {share_false}행")
    if share_caught == 0:
        print("- **쓸 이유가 없다.** 구성비도 밀린 뭉치에 같이 실려 오므로 행 안에서는 일관적이다.")
        print("  오탐이 0인 것은 식이 옳다는 뜻이지, 이 계열을 본다는 뜻이 아니다.")


def main() -> int:
    parser = argparse.ArgumentParser(
        description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter
    )
    parser.add_argument("dataset", type=Path, help="라벨 디렉터리 (예: datasets/product-sales)")
    parser.add_argument("--out", type=Path, required=True, help="예측이 저장된 실행 디렉터리")
    parser.add_argument("--slack", type=float, default=1.6, help="구성비 허용범위 배수 (기본 1.6)")
    args = parser.parse_args()
    return analyze(args.dataset, args.out, slack=args.slack)


if __name__ == "__main__":
    raise SystemExit(main())
