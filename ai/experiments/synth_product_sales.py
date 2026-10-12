"""POS 「상품별 매출현황」 합성 생성기 — P1-22 의 유일한 데이터 경로.
(공개 데이터도, 직접 촬영도 없다.)

    uv run python -X utf8 -m experiments.synth_product_sales \\
        --out datasets/product-sales --count 30 --seed 7

정답은 정확하지만 실제 POS 벤더의 레이아웃·폰트는 아니다 — 결과 보고에 한계로 적는다.
명세 20.9 의 요구 4건을 통제 실험으로 만든다.
  1 기간은 헤더에서 — `no_period` 표본은 헤더에 영업일자가 없다 → GT period.source = null.
    출력일시는 항상 남겨 둔다(기간으로 오인하면 틀림).
  2 합계 행 분리 — 합계는 항상 인쇄되고 GT printedTotal 은 **모든 행**의 합이다.
  3 읽은 열만 — 58/80mm 레이아웃은 열이 줄어든다 → GT columnsDetected 는 그 열만,
    나머지 필드는 null.
  4 행을 만들지 않는다 — `blur_rows` 는 그 행을 읽을 수 없게 만든다 → GT items 에서 빠지고
    printedTotal 은 그대로 → 체크섬 불일치가 기대값(meta.extra.expectedChecksumMismatch = true).
여러 장(pages)은 장 경계 행을 한 번 겹쳐(overlap) 병합·중복 제거를 시험한다.
"""

from __future__ import annotations

import argparse
import os
from dataclasses import dataclass
from datetime import UTC, date, datetime, timedelta
from pathlib import Path
from typing import Any

import cv2
import numpy as np
from PIL import Image, ImageDraw, ImageFilter, ImageFont

from app.ocr.schemas import (
    CaptureType,
    PrintedTotal,
    ProductSalesExtraction,
    ProductSalesRow,
    SalesPeriod,
)
from experiments.degrade import degrade
from experiments.labels import LabelMeta, ProductSalesLabelRecord, write_label, write_manifest

GENERATOR_VERSION = "synth_product_sales/v1"

ALL_COLUMNS = [
    "productName",
    "quantity",
    "grossAmount",
    "discountAmount",
    "netAmount",
    "vatAmount",
    "billedAmount",
    "share",
]
COLUMN_TITLES = {
    "productName": "상품명",
    "quantity": "수량",
    "grossAmount": "판매금액",
    "discountAmount": "할인",
    "netAmount": "공급가액",
    "vatAmount": "부가세",
    "billedAmount": "결제금액",
    "share": "구성비",
}
PRODUCTS = [
    "아메리카노", "카페라떼", "바닐라라떼", "콜드브루", "카푸치노", "카라멜마끼아또", "녹차라떼",
    "자몽에이드", "레몬에이드", "딸기스무디", "망고스무디", "치즈케이크", "크로플", "플레인베이글",
    "티라미수", "초코쿠키", "아이스티", "유자차", "핫초코", "에스프레소", "플랫화이트", "밀크티",
    "블루베리요거트", "허니브레드", "소금빵", "크루아상", "에그샌드위치", "햄치즈파니니",
    "쌀국수", "김치찌개", "된장찌개", "제육덮밥", "돈까스", "우동", "비빔밥", "칼국수",
]  # fmt: skip
STORES = ["카페 잔고", "커피한잔 광주점", "브런치하우스", "골목식당 본점", "달콤제과"]
_FONT_CANDIDATES = (
    os.environ.get("SYNTH_FONT", ""),
    "C:/Windows/Fonts/malgun.ttf",
    "C:/Windows/Fonts/gulim.ttc",
    "/usr/share/fonts/truetype/nanum/NanumGothic.ttf",
    "/usr/share/fonts/opentype/noto/NotoSansCJK-Regular.ttc",
    "/usr/share/fonts/truetype/noto/NotoSansCJK-Regular.ttc",
)


@dataclass(frozen=True)
class Layout:
    name: str
    capture: CaptureType
    width: int
    columns: list[str]
    font_size: int
    grid: bool
    preset: str  # degrade 프리셋


LAYOUTS: dict[str, Layout] = {
    "screen": Layout("screen", "SCREEN", 1280, ALL_COLUMNS, 22, True, "screen"),
    "printout80": Layout(
        "printout80",
        "PRINTOUT",
        576,
        ["productName", "quantity", "grossAmount", "discountAmount", "billedAmount", "share"],
        18,
        False,
        "thermal",
    ),
    "printout58": Layout(
        "printout58",
        "PRINTOUT",
        384,
        ["productName", "quantity", "billedAmount", "share"],
        16,
        False,
        "thermal",
    ),
}


@dataclass
class Row:
    name: str
    quantity: int
    unit_price: int
    gross: int
    discount: int
    net: int
    vat: int
    billed: int
    share: float = 0.0

    def value(self, column: str) -> int | float | str:
        return {
            "productName": self.name,
            "quantity": self.quantity,
            "grossAmount": self.gross,
            "discountAmount": self.discount,
            "netAmount": self.net,
            "vatAmount": self.vat,
            "billedAmount": self.billed,
            "share": self.share,
        }[column]


def make_rows(rng: np.random.Generator, count: int) -> list[Row]:
    names = rng.choice(PRODUCTS, size=min(count, len(PRODUCTS)), replace=False)
    rows: list[Row] = []
    for name in names:
        quantity = int(rng.integers(1, 121))
        unit_price = int(rng.choice(np.arange(1500, 8001, 500)))
        gross = quantity * unit_price
        discount = (
            0 if rng.random() < 0.6 else int(rng.integers(1, max(2, gross * 0.15)) // 10 * 10)
        )
        billed = gross - discount
        net = round(billed / 1.1)
        rows.append(
            Row(str(name), quantity, unit_price, gross, discount, net, billed - net, billed)
        )
    total_billed = sum(r.billed for r in rows) or 1
    for row in rows:
        row.share = round(row.billed / total_billed, 4)
    return rows


def totals_of(rows: list[Row]) -> dict[str, int | float]:
    return {
        "quantity": sum(r.quantity for r in rows),
        "grossAmount": sum(r.gross for r in rows),
        "discountAmount": sum(r.discount for r in rows),
        "netAmount": sum(r.net for r in rows),
        "vatAmount": sum(r.vat for r in rows),
        "billedAmount": sum(r.billed for r in rows),
        "share": 1.0,
    }


def make_period(rng: np.random.Generator) -> tuple[date, date]:
    start = date(2025, 1, 1) + timedelta(days=int(rng.integers(0, 600)))
    return start, start + timedelta(days=int(rng.integers(13, 31)))


def _fmt(column: str, value: int | float | str) -> str:
    if column == "productName":
        return str(value)
    if column == "share":
        return f"{float(value) * 100:.1f}%"
    return f"{int(value):,}"


def find_font(size: int) -> tuple[ImageFont.FreeTypeFont | ImageFont.ImageFont, str, bool]:
    """한글 폰트를 찾는다. 없으면 PIL 기본 폰트 — 한글이 깨진다(manifest 의 fontFallback)."""
    for candidate in _FONT_CANDIDATES:
        if candidate and Path(candidate).is_file():
            return ImageFont.truetype(candidate, size), candidate, False
    return ImageFont.load_default(size=size), "PIL default", True


@dataclass
class RenderSpec:
    layout: Layout
    rows: list[Row]
    period: tuple[date, date] | None  # None = 헤더에 영업일자 없음
    print_time: datetime
    store: str
    pages: int = 1
    overlap: int = 0
    blur_rows: frozenset[int] = frozenset()  # 읽을 수 없게 만들 행 인덱스
    # 읽히기는 하는 **중간 흐림**. `blur_rows` 와 달리 GT 에 **남는다** — 모델이 값을
    # 맞게 읽으면서도 「흐려서 판독 불가」 경고를 남기는지 재려는 것이다(실서버 관찰,
    # 2026-09-11). 강한 흐림은 90표본에서 0건이라 그 조건을 못 만든다.
    faint_rows: frozenset[int] = frozenset()
    totals_position: str = "bottom"  # bottom | top


def _column_x(
    layout: Layout,
    margin: int,
    font: ImageFont.FreeTypeFont | ImageFont.ImageFont,
    texts: dict[str, list[str]],
) -> list[tuple[int, int]]:
    """열별 (x0, x1).

    숫자 열은 실제로 찍힐 가장 긴 문자열 폭에 맞추고 남는 폭을 상품명이 가진다. 58mm 처럼 좁은
    폭에서 열이 겹치면 사람도 모델도 정답을 읽을 수 없어 라벨이 무의미해진다.
    """
    pad = 10
    widths = {
        column: int(max(font.getlength(text) for text in texts[column]) + 2 * pad)
        for column in layout.columns
        if column != "productName"
    }
    usable = layout.width - 2 * margin
    name_width = usable - sum(widths.values())
    if name_width < 6 * getattr(font, "size", 16):
        raise ValueError(
            f"{layout.name}: 상품명 열이 너무 좁다({name_width}px). 열을 줄이거나 폰트를 줄인다"
        )
    spans, x = [], margin
    for column in layout.columns:
        width = name_width if column == "productName" else widths[column]
        spans.append((x, x + width))
        x += width
    return spans


def _page_row_ranges(total: int, pages: int, overlap: int) -> list[tuple[int, int]]:
    if pages <= 1:
        return [(0, total)]
    per_page = -(-total // pages)  # ceil
    ranges = []
    for page in range(pages):
        start = max(0, page * per_page - (overlap if page else 0))
        end = min(total, (page + 1) * per_page)
        ranges.append((start, end))
    return ranges


def render_pages(
    spec: RenderSpec, font_size_override: int | None = None
) -> tuple[list[Image.Image], dict[str, Any]]:
    layout = spec.layout
    font, font_path, fallback = find_font(font_size_override or layout.font_size)
    small_font, _, _ = find_font(max(10, (font_size_override or layout.font_size) - 4))
    line_h = int((font_size_override or layout.font_size) * 1.7)
    margin = 18 if layout.capture == "PRINTOUT" else 40
    totals = totals_of(spec.rows)
    texts = {
        column: [COLUMN_TITLES[column]]
        + [_fmt(column, row.value(column)) for row in spec.rows]
        + ["합계" if column == "productName" else _fmt(column, totals[column])]
        for column in layout.columns
    }
    spans = _column_x(layout, margin, font, texts)
    ranges = _page_row_ranges(len(spec.rows), spec.pages, spec.overlap)
    pages: list[Image.Image] = []

    def draw_row(canvas: ImageDraw.ImageDraw, values: list[str], yy: int) -> None:
        for (x0, x1), column, text in zip(spans, layout.columns, values, strict=True):
            if column == "productName":
                canvas.text((x0 + 4, yy), text, font=font, fill="black")
            else:
                width = canvas.textlength(text, font=font)
                canvas.text((x1 - 4 - width, yy), text, font=font, fill="black")

    def totals_row(canvas: ImageDraw.ImageDraw, yy: int) -> int:
        values = ["합계" if c == "productName" else _fmt(c, totals[c]) for c in layout.columns]
        canvas.line([(margin, yy - 4), (layout.width - margin, yy - 4)], fill="black", width=2)
        draw_row(canvas, values, yy)
        return yy + line_h

    for page_index, (start, end) in enumerate(ranges):
        is_first, is_last = page_index == 0, page_index == len(ranges) - 1
        header_lines = 4 if is_first else 1
        show_totals = (spec.totals_position == "bottom" and is_last) or (
            spec.totals_position == "top" and is_first
        )
        n_rows = end - start
        height = margin * 2 + line_h * (header_lines + 1 + n_rows + (2 if show_totals else 0) + 1)
        image = Image.new("RGB", (layout.width, height), "white")
        draw = ImageDraw.Draw(image)
        y = margin
        if is_first:
            draw.text((margin, y), "상품별 매출현황", font=font, fill="black")
            y += line_h
            draw.text((margin, y), spec.store, font=small_font, fill="black")
            y += line_h
            if spec.period is not None:
                start_d, end_d = spec.period
                draw.text(
                    (margin, y),
                    f"영업일자 : {start_d.isoformat()} ~ {end_d.isoformat()}",
                    font=small_font,
                    fill="black",
                )
                y += line_h
            else:
                y += line_h  # 빈 줄 — 기간이 없다는 사실 자체가 시험 대상
        draw.text(
            (margin, y),
            f"출력일시 : {spec.print_time.strftime('%Y-%m-%d %H:%M')}"
            + (f"   ({page_index + 1}/{len(ranges)})" if len(ranges) > 1 else ""),
            font=small_font,
            fill="black",
        )
        y += line_h

        if layout.grid:
            draw.rectangle(
                [margin, y - 4, layout.width - margin, y + line_h - 6], fill=(230, 230, 230)
            )
        else:
            draw.line([(margin, y - 4), (layout.width - margin, y - 4)], fill="black", width=1)
        draw_row(draw, [COLUMN_TITLES[c] for c in layout.columns], y)
        y += line_h
        draw.line([(margin, y - 4), (layout.width - margin, y - 4)], fill="black", width=1)

        if show_totals and spec.totals_position == "top":
            y = totals_row(draw, y)

        blurred_boxes: list[tuple[int, int, int, int]] = []
        faint_boxes: list[tuple[int, int, int, int]] = []
        for index in range(start, end):
            row = spec.rows[index]
            draw_row(draw, [_fmt(c, row.value(c)) for c in layout.columns], y)
            if index in spec.blur_rows:
                blurred_boxes.append((margin, y - 2, layout.width - margin, y + line_h - 6))
            elif index in spec.faint_rows:
                faint_boxes.append((margin, y - 2, layout.width - margin, y + line_h - 6))
            if layout.grid:
                draw.line(
                    [(margin, y + line_h - 6), (layout.width - margin, y + line_h - 6)],
                    fill=(200, 200, 200),
                )
            y += line_h

        if show_totals and spec.totals_position == "bottom":
            y = totals_row(draw, y)

        for box in blurred_boxes:  # 읽을 수 없게: 강한 블러 + 옅게
            region = image.crop(box).filter(ImageFilter.GaussianBlur(radius=6))
            region = Image.blend(region, Image.new("RGB", region.size, "white"), 0.45)
            image.paste(region, box[:2])
        for box in faint_boxes:  # 읽히지만 흐리게: 약한 블러 + 살짝 옅게
            region = image.crop(box).filter(ImageFilter.GaussianBlur(radius=1.6))
            region = Image.blend(region, Image.new("RGB", region.size, "white"), 0.22)
            image.paste(region, box[:2])
        pages.append(image)
    return pages, {"font": font_path, "fontFallback": fallback}


def ground_truth(spec: RenderSpec) -> ProductSalesExtraction:
    columns = set(spec.layout.columns)
    visible = [r for i, r in enumerate(spec.rows) if i not in spec.blur_rows]

    def pick(row: Row, column: str) -> Any:
        return row.value(column) if column in columns else None

    items = [
        ProductSalesRow(
            product_name=row.name,
            quantity=pick(row, "quantity"),
            gross_amount=pick(row, "grossAmount"),
            discount_amount=pick(row, "discountAmount"),
            net_amount=pick(row, "netAmount"),
            vat_amount=pick(row, "vatAmount"),
            billed_amount=pick(row, "billedAmount"),
            share=pick(row, "share"),
            confidence=None,
        )
        for row in visible
    ]
    totals = totals_of(spec.rows)  # 인쇄된 합계는 **모든** 행의 합
    printed = PrintedTotal(
        quantity=totals["quantity"] if "quantity" in columns else None,
        gross_amount=totals["grossAmount"] if "grossAmount" in columns else None,
        discount_amount=totals["discountAmount"] if "discountAmount" in columns else None,
        net_amount=totals["netAmount"] if "netAmount" in columns else None,
        vat_amount=totals["vatAmount"] if "vatAmount" in columns else None,
        billed_amount=totals["billedAmount"] if "billedAmount" in columns else None,
    )
    period = (
        SalesPeriod(start=spec.period[0], end=spec.period[1], source="HEADER")
        if spec.period
        else SalesPeriod()
    )
    return ProductSalesExtraction(
        period=period,
        columns_detected=list(spec.layout.columns),
        items=items,
        printed_total=printed,
        warnings=[],
    )


def generate_sample(
    out_dir: Path, sample_id: str, spec: RenderSpec, seed: int
) -> ProductSalesLabelRecord:
    pages, font_info = render_pages(spec)
    out_dir.mkdir(parents=True, exist_ok=True)
    image_names: list[str] = []
    applied: list[dict[str, Any]] = []
    for index, page in enumerate(pages):
        bgr = cv2.cvtColor(np.asarray(page), cv2.COLOR_RGB2BGR)
        degraded, params = degrade(bgr, spec.layout.preset, seed + index)
        name = f"{sample_id}-p{index + 1}.jpg"
        cv2.imwrite(str(out_dir / name), degraded, [cv2.IMWRITE_JPEG_QUALITY, 95])
        image_names.append(name)
        applied.append(params)
    record = ProductSalesLabelRecord(
        images=image_names,
        capture_type=spec.layout.capture,
        extraction=ground_truth(spec),
        meta=LabelMeta(
            source="synthetic",
            source_id=sample_id,
            unlabeled_fields=["items.confidence"],
            notes=[],
            extra={
                "generator": GENERATOR_VERSION,
                "layout": spec.layout.name,
                "seed": seed,
                "pages": spec.pages,
                "overlapRows": spec.overlap if spec.pages > 1 else 0,
                "blurredRows": sorted(spec.rows[i].name for i in spec.blur_rows),
                # GT 에 남아 있다 — 모델이 읽어야 맞는 행이다
                "faintRows": sorted(spec.rows[i].name for i in spec.faint_rows),
                "expectedChecksumMismatch": bool(spec.blur_rows),
                "totalsPosition": spec.totals_position,
                "noPeriod": spec.period is None,
                "printTime": spec.print_time.isoformat(timespec="minutes"),
                "store": spec.store,
                "degrade": applied,
                **font_info,
            },
        ),
    )
    write_label(out_dir / f"{sample_id}.json", record)
    return record


def random_spec(rng: np.random.Generator, layout: Layout, faint_ratio: float = 0.0) -> RenderSpec:
    rows = make_rows(rng, int(rng.integers(6, 19)))
    period = None if rng.random() < 0.2 else make_period(rng)
    anchor = period[1] if period else date(2026, 8, 31)
    print_time = datetime.combine(
        anchor + timedelta(days=int(rng.integers(1, 20))), datetime.min.time()
    )
    print_time = print_time.replace(hour=int(rng.integers(9, 22)), minute=int(rng.integers(0, 60)))
    pages = 2 if (len(rows) >= 10 and rng.random() < 0.5) else 1
    blur = (
        frozenset(
            int(i) for i in rng.choice(len(rows), size=int(rng.integers(0, 3)), replace=False)
        )
        if rng.random() < 0.35
        else frozenset()
    )
    # 중간 흐림은 강한 흐림과 **겹치지 않는 행**에서만 고른다 — 한 행에 둘을 겹치면
    # 무엇 때문에 못 읽었는지 구분이 안 된다.
    faint: frozenset[int] = frozenset()
    if faint_ratio > 0:
        pool = [i for i in range(len(rows)) if i not in blur]
        take = min(len(pool), max(1, round(len(rows) * faint_ratio)))
        if pool:
            faint = frozenset(int(i) for i in rng.choice(pool, size=take, replace=False))
    return RenderSpec(
        layout=layout,
        rows=rows,
        period=period,
        print_time=print_time,
        store=str(rng.choice(STORES)),
        pages=pages,
        overlap=1 if pages > 1 else 0,
        blur_rows=blur,
        faint_rows=faint,
        totals_position="top" if rng.random() < 0.2 else "bottom",
    )


def generate_dataset(
    out: Path, count: int, layouts: list[str], seed: int, faint_ratio: float = 0.0
) -> dict[str, int]:
    rng = np.random.default_rng(seed)
    counts: dict[str, int] = {}
    font_info: dict[str, Any] = {}
    for layout_name in layouts:
        layout = LAYOUTS[layout_name]
        target = out / ("screen" if layout.capture == "SCREEN" else "printout")
        for index in range(count):
            sample_seed = int(rng.integers(0, 2**31))
            spec = random_spec(np.random.default_rng(sample_seed), layout, faint_ratio)
            record = generate_sample(
                target, f"ps-{layout.name}-{seed}-{index:03d}", spec, sample_seed
            )
            counts[layout_name] = counts.get(layout_name, 0) + 1
            font_info = {k: record.meta.extra[k] for k in ("font", "fontFallback")}
    write_manifest(
        out,
        {
            "dataset": "synthetic-product-sales",
            "generator": GENERATOR_VERSION,
            "generatedAt": datetime.now(UTC).isoformat(timespec="seconds"),
            "seed": seed,
            "layouts": {
                name: {"width": LAYOUTS[name].width, "columns": LAYOUTS[name].columns}
                for name in layouts
            },
            "counts": counts,
            **font_info,
            "limitations": "실제 POS 레이아웃·폰트가 아님. 정확도는 합성 기준으로만 보고.",
        },
    )
    return counts


def main() -> int:
    parser = argparse.ArgumentParser(
        description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter
    )
    parser.add_argument("--out", type=Path, default=Path("datasets/product-sales"))
    parser.add_argument("--count", type=int, default=30, help="레이아웃당 표본 수")
    parser.add_argument(
        "--layouts",
        default="screen,printout80,printout58",
        help="쉼표 구분: screen,printout80,printout58",
    )
    parser.add_argument("--seed", type=int, default=7)
    parser.add_argument(
        "--faint-ratio",
        type=float,
        default=0.0,
        help="행의 몇 할을 **읽히는 중간 흐림**으로 만들지 (0~1). GT 에는 남는다 — "
        "모델이 값을 맞게 읽으면서 「흐려서 판독 불가」 경고를 남기는지 재는 용도",
    )
    args = parser.parse_args()
    layouts = [name.strip() for name in args.layouts.split(",") if name.strip()]
    unknown = [name for name in layouts if name not in LAYOUTS]
    if unknown:
        print(f"[실패] 모르는 레이아웃: {unknown} (가능: {sorted(LAYOUTS)})")
        return 2
    counts = generate_dataset(args.out, args.count, layouts, args.seed, args.faint_ratio)
    print(f"생성 완료 → {args.out}: {counts}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
