"""증빙 유형별 합성 생성기 — 세금계산서·거래명세서·현금영수증 (P1-02 유형 힌트 분기 평가용).

    uv run python -X utf8 -m experiments.synth_evidence --out datasets/receipts/synthetic \\
        --count 15 --seed 11

KORIE 에는 마트·편의점·식당 영수증만 있다. 나머지 증빙 유형은 공개 데이터가 없고 직접 수집하지
않으므로 합성으로만 만든다. 정답이 **완전**하다(품목 전부 라벨, `itemsComplete = true`) →
정밀도·환각률을 잴 수 있는 유일한 증빙 세트다.

유형별로 검산 구조가 다르다 — 이것이 프롬프트 v2 의 "줄 → 필드 대응표"를 시험하는 지점이다.
- TAX_INVOICE 세금계산서: 공급가액·세액·합계금액 세 줄. 품목 행 금액은 **공급가액**(부가세 제외).
- INVOICE 거래명세서: 공급가액·세액·합계 또는 합계만. 품목 행 금액은 공급가액.
- CASH_RECEIPT 현금영수증: 마트형 합계 구역(과세물품·부가세·면세물품·합계) 또는 합계 한 줄.
  소득공제/지출증빙, 승인번호, 식별번호(마스킹) 줄이 있다 — 승인번호를 사업자번호로 읽으면 틀림.

한계: 실제 국세청 전자세금계산서 출력물·업체별 거래명세서 양식과 같지 않다. 결과 보고에 명시한다.
"""

from __future__ import annotations

import argparse
from dataclasses import dataclass, field
from datetime import UTC, date, datetime, timedelta
from pathlib import Path
from typing import Any, Literal

import cv2
import numpy as np
from PIL import Image, ImageDraw, ImageFont

from app.ocr.schemas import EvidenceExtraction, EvidenceType, ExtractedItem
from experiments.degrade import degrade
from experiments.labels import LabelMeta, LabelRecord, write_label, write_manifest
from experiments.synth_product_sales import find_font

GENERATOR_VERSION = "synth_evidence/v1"
Kind = Literal["TAX_INVOICE", "INVOICE", "CASH_RECEIPT"]
KIND_DIRS: dict[Kind, str] = {
    "TAX_INVOICE": "tax-invoice",
    "INVOICE": "invoice",
    "CASH_RECEIPT": "cash-receipt",
}
_WEIGHTS = (1, 3, 7, 1, 3, 7, 1, 3, 5)

SUPPLIERS = [
    ("서울우유 광주대리점", "도소매", "낙농품"),
    ("한빛식자재", "도소매", "식자재"),
    ("(주)그린농산", "도소매", "농산물"),
    ("대성포장", "제조", "포장재"),
    ("미래커피로스터스", "제조", "커피원두"),
    ("청정수산", "도소매", "수산물"),
    ("행복베이커리원료", "도소매", "제과재료"),
    ("남도청과", "도소매", "청과"),
    ("광주종합식품", "도소매", "가공식품"),
]
BUYERS = ["카페 잔고", "골목식당 본점", "브런치하우스", "달콤제과", "커피한잔 광주점"]
OWNERS = ["김민수", "이서연", "박지훈", "최유진", "정우성", "한지민", "오세훈", "윤아름"]
ADDRESSES = [
    "광주광역시 북구 첨단과기로 123",
    "광주광역시 광산구 수완로 45, 2층",
    "전남 나주시 빛가람로 760",
    "광주광역시 서구 상무중앙로 58",
    "광주광역시 동구 금남로 1 (충장동)",
]
# (품목명, 단가, 면세 여부) — 면세는 미가공 농·축·수산물
#
# **뒤쪽 6종은 앞 상품과 이름이 비슷하고 규격만 다른 "짝"이다** (2026-09-09 추가). 품목 정규화
# 3차(§6.5)를 자동으로 채점하려고 넣었다 — 유사도 0.70~0.92 구간이 없으면 3차 후보가 아예
# 생기지 않아 정확도를 잴 수 없다(합성 45장에서 후보 0건이었다).
#
# 짝 중 하나가 사전에서 빠지면(`--withhold`) 나머지가 후보로 걸리고 **정답은 미매칭(null)** 이다
# — 용량·종류가 다르면 다른 상품이라고 프롬프트가 지시하므로 3차는 거절해야 한다. 이 방향이
# 안전상 더 중요하다(오매칭이 매칭 실패보다 나쁘다). 반대 방향(같은 상품, 표기만 다름)은
# 자동 측정이 불가능하다 — 인쇄된 표기가 곧 라벨이라 사전의 alias 가 되어 완전 일치로 끝난다.
ITEMS = [
    ("서울우유 1L", 2900, False), ("바나나우유 240ml", 1600, False),
    ("에스프레소 원두 1kg", 28000, False), ("생크림 1L", 7800, False),
    ("백설탕 15kg", 24000, False), ("강력분 20kg", 32000, False),
    ("냉동 딸기 1kg", 9500, False), ("종이컵 12oz 1000개", 45000, False),
    ("테이크아웃 홀더 500개", 18000, False), ("식용유 18L", 52000, False),
    ("점보롤 화장지 16롤", 19800, False), ("니트릴 장갑 100매", 12000, False),
    ("양파 15kg", 21000, True), ("대파 1단", 3500, True), ("계란 30구", 7900, True),
    ("감자 20kg", 33000, True), ("고등어 10kg", 48000, True), ("상추 4kg", 16000, True),
    # ↓ 이름이 비슷하고 규격만 다른 짝 — 3차 후보를 만들기 위한 것
    ("서울우유 500ml", 1700, False), ("에스프레소 원두 500g", 15000, False),
    ("점보롤 화장지 30롤", 34000, False), ("종이컵 8oz 1000개", 38000, False),
    ("양파 5kg", 8000, True), ("계란 10구", 3200, True),
]  # fmt: skip


def checksum_digit(nine: str) -> int:
    d = [int(c) for c in nine]
    total = sum(a * w for a, w in zip(d, _WEIGHTS, strict=True)) + (d[8] * 5) // 10
    return (10 - total % 10) % 10


def valid_business_no(rng: np.random.Generator, *, corporate: bool) -> str:
    office = int(rng.integers(101, 700))
    kind = int(rng.choice([81, 86, 87, 88])) if corporate else int(rng.integers(1, 80))
    serial = int(rng.integers(0, 10_000))
    nine = f"{office:03d}{kind:02d}{serial:04d}"
    return f"{nine[:3]}-{nine[3:5]}-{nine[5:]}{checksum_digit(nine)}"


@dataclass(frozen=True)
class Party:
    business_no: str
    name: str
    owner: str
    address: str
    sector: str
    category: str
    phone: str


@dataclass(frozen=True)
class Line:
    name: str
    quantity: int
    unit_price: int
    tax_free: bool

    @property
    def supply(self) -> int:  # 세금계산서·거래명세서 행 금액 (부가세 제외)
        return self.quantity * self.unit_price

    @property
    def vat(self) -> int:  # 행 세액 — 원 단위 절사
        return 0 if self.tax_free else self.supply // 10

    @property
    def gross(self) -> int:  # 현금영수증 행 금액 (소비자 가격, 부가세 포함)
        return self.supply + self.vat


@dataclass
class EvidenceSpec:
    kind: Kind
    supplier: Party
    buyer: Party
    issued: date
    rows: list[Line]
    # TAX_INVOICE: electronic|paper · INVOICE: with-vat|total-only
    # CASH_RECEIPT: taxable|tax-free|simple
    variant: str
    approval_no: str = ""
    purpose: str = "지출증빙"  # 현금영수증: 소득공제 | 지출증빙
    extra: dict[str, Any] = field(default_factory=dict)

    @property
    def supply_total(self) -> int:
        return sum(r.supply for r in self.rows)

    @property
    def vat_total(self) -> int:
        return sum(r.vat for r in self.rows)


def _party(rng: np.random.Generator, name: str, sector: str, category: str) -> Party:
    return Party(
        business_no=valid_business_no(rng, corporate=name.startswith("(주)")),
        name=name,
        owner=str(rng.choice(OWNERS)),
        address=str(rng.choice(ADDRESSES)),
        sector=sector,
        category=category,
        phone=f"062-{int(rng.integers(200, 999))}-{int(rng.integers(1000, 9999))}",
    )


def random_spec(rng: np.random.Generator, kind: Kind) -> EvidenceSpec:
    supplier_name, sector, category = SUPPLIERS[int(rng.integers(len(SUPPLIERS)))]
    supplier = _party(rng, supplier_name, sector, category)
    buyer = _party(rng, str(rng.choice(BUYERS)), "음식점", "커피·음료")
    issued = date(2026, 1, 1) + timedelta(days=int(rng.integers(0, 240)))
    count = int(rng.integers(2, 6)) if kind != "CASH_RECEIPT" else int(rng.integers(1, 7))
    picks = rng.choice(len(ITEMS), size=count, replace=False)
    if kind == "CASH_RECEIPT":
        variant = str(rng.choice(["taxable", "tax-free", "simple"]))
    elif kind == "TAX_INVOICE":
        variant = str(rng.choice(["electronic", "paper"]))
    else:
        variant = str(rng.choice(["with-vat", "total-only"]))
    rows = []
    for index in picks:
        name, unit_price, tax_free = ITEMS[int(index)]
        # 세금계산서·거래명세서는 과세 품목만 (면세 세금계산서는 별도 양식이라 범위 밖)
        if kind != "CASH_RECEIPT":
            tax_free = False
        elif variant != "tax-free":
            tax_free = False
        quantity = int(rng.integers(1, 12)) if kind == "CASH_RECEIPT" else int(rng.integers(1, 40))
        rows.append(Line(name, quantity, unit_price, tax_free))
    if kind == "CASH_RECEIPT" and variant == "tax-free" and not any(r.tax_free for r in rows):
        name, unit_price, _ = ITEMS[int(rng.choice([12, 13, 14, 15, 16, 17]))]
        rows.append(Line(name, int(rng.integers(1, 5)), unit_price, True))
    return EvidenceSpec(
        kind=kind,
        supplier=supplier,
        buyer=buyer,
        issued=issued,
        rows=rows,
        variant=variant,
        approval_no=f"{int(rng.integers(10**8, 10**9 - 1))}",
        purpose=str(rng.choice(["소득공제", "지출증빙"])),
    )


# ---------------------------------------------------------------- 정답


def ground_truth(spec: EvidenceSpec) -> EvidenceExtraction:
    s = spec.supplier
    if spec.kind == "CASH_RECEIPT":
        items = [
            ExtractedItem(raw_name=r.name, quantity=r.quantity, unit_price=r.gross // r.quantity,
                          amount=r.gross)
            for r in spec.rows
        ]  # fmt: skip
        total = sum(r.gross for r in spec.rows)
        tax_free = sum(r.gross for r in spec.rows if r.tax_free)
        taxable = sum(r.supply for r in spec.rows if not r.tax_free)
        vat = sum(r.vat for r in spec.rows)
        if spec.variant == "simple":  # 합계 한 줄만 인쇄 — 과세·부가세 줄 없음
            return EvidenceExtraction(
                supplier_name=s.name, supplier_business_no=s.business_no, issued_date=spec.issued,
                total_amount=total, items=items,
            )  # fmt: skip
        return EvidenceExtraction(
            supplier_name=s.name,
            supplier_business_no=s.business_no,
            issued_date=spec.issued,
            taxable_amount=taxable,
            vat_amount=vat,
            tax_free_amount=tax_free if spec.variant == "tax-free" else 0,
            total_amount=total,
            items=items,
        )
    items = [
        ExtractedItem(
            raw_name=r.name, quantity=r.quantity, unit_price=r.unit_price, amount=r.supply
        )
        for r in spec.rows
    ]
    supply, vat = spec.supply_total, spec.vat_total
    if spec.kind == "INVOICE" and spec.variant == "total-only":
        # 합계만 인쇄된 거래명세서 — 공급가액·세액 줄이 없다 (검산 판정 불가가 정답)
        return EvidenceExtraction(
            supplier_name=s.name, supplier_business_no=s.business_no, issued_date=spec.issued,
            total_amount=supply + vat, items=items,
        )  # fmt: skip
    return EvidenceExtraction(
        supplier_name=s.name,
        supplier_business_no=s.business_no,
        issued_date=spec.issued,
        supply_amount=supply,
        vat_amount=vat,
        total_amount=supply + vat,
        items=items,
    )


# ---------------------------------------------------------------- 렌더링


def _won(value: int) -> str:
    return f"{value:,}"


class _Canvas:
    def __init__(self, width: int, height: int, font_size: int) -> None:
        self.image = Image.new("RGB", (width, height), (255, 255, 255))
        self.draw = ImageDraw.Draw(self.image)
        self.font, self.font_path, self.fallback = find_font(font_size)
        self.small, _, _ = find_font(max(10, font_size - 4))
        self.big, _, _ = find_font(font_size + 10)
        # 그린 텍스트와 그 박스(px). `boundingBox` 벤치마크의 정답 — 어느 필드인지는
        # 채점할 때 값 문자열로 맞춘다(여기서 태깅하면 렌더러마다 손대야 한다).
        self.texts: list[tuple[str, tuple[int, int, int, int]]] = []

    def text(self, xy: tuple[int, int], s: str, *, font: ImageFont.ImageFont | None = None,
             align: str = "left", width: int = 0) -> None:  # fmt: skip
        font = font or self.font
        x, y = xy
        if align == "right":
            x = x + width - int(self.draw.textlength(s, font=font))
        elif align == "center":
            x = x + (width - int(self.draw.textlength(s, font=font))) // 2
        self.draw.text((x, y), s, fill=(20, 20, 20), font=font)
        if s.strip():
            self.texts.append((s, self.draw.textbbox((x, y), s, font=font)))

    def box(self, x0: int, y0: int, x1: int, y1: int, *, width: int = 1) -> None:
        self.draw.rectangle((x0, y0, x1, y1), outline=(40, 40, 40), width=width)

    def hline(self, x0: int, x1: int, y: int, *, dashed: bool = False) -> None:
        if not dashed:
            self.draw.line((x0, y, x1, y), fill=(40, 40, 40), width=1)
            return
        for x in range(x0, x1, 12):
            self.draw.line((x, y, min(x + 6, x1), y), fill=(40, 40, 40), width=1)


def _party_block(c: _Canvas, x: int, y: int, w: int, title: str, p: Party, *, buyer: bool) -> int:
    rows = [
        ("등록번호", p.business_no),
        ("상호", p.name),
        ("성명", p.owner),
        ("사업장주소", p.address),
        ("업태", f"{p.sector}    종목 {p.category}"),
    ]
    row_h = 34
    c.box(x, y, x + w, y + row_h * (len(rows) + 1), width=2)
    c.text((x, y + 6), title, align="center", width=w)
    for i, (label, value) in enumerate(rows, start=1):
        yy = y + row_h * i
        c.hline(x, x + w, yy)
        c.text((x + 10, yy + 7), label, font=c.small)
        c.text((x + 120, yy + 6), value if not (buyer and label == "성명") else p.owner)
    return y + row_h * (len(rows) + 1)


def render_tax_invoice(spec: EvidenceSpec) -> _Canvas:
    c = _Canvas(1400, 1000, 20)
    title = "전자세금계산서" if spec.variant == "electronic" else "세 금 계 산 서"
    c.text((0, 40), title, font=c.big, align="center", width=1400)
    if spec.variant == "electronic":
        approval = f"{spec.approval_no}-{spec.issued:%Y%m%d}-{spec.approval_no[:5]}"
        c.text((980, 50), f"승인번호  {approval}", font=c.small)
    c.text((60, 60), "(공급받는자 보관용)" if spec.variant == "paper" else "", font=c.small)
    y = 110
    _party_block(c, 60, y, 620, "공 급 자", spec.supplier, buyer=False)
    y_end = _party_block(c, 720, y, 620, "공 급 받 는 자", spec.buyer, buyer=True)
    # 작성일자 · 공급가액 · 세액
    y = y_end + 20
    cols = [
        ("작성일자", f"{spec.issued:%Y-%m-%d}", 300),
        ("공급가액", _won(spec.supply_total), 440),
        ("세액", _won(spec.vat_total), 340),
        ("수정사유", "", 200),
    ]
    x = 60
    c.box(60, y, 1340, y + 70, width=2)
    for label, value, w in cols:
        c.text((x, y + 6), label, font=c.small, align="center", width=w)
        c.hline(x, x + w, y + 30)
        c.text((x + 12, y + 38), value, align="right", width=w - 24)
        x += w
        c.draw.line((x, y, x, y + 70), fill=(40, 40, 40), width=1)
    # 품목 표
    y += 90
    heads = [("월", 60), ("일", 60), ("품목", 380), ("규격", 120), ("수량", 100), ("단가", 160),
             ("공급가액", 200), ("세액", 160), ("비고", 40)]  # fmt: skip
    row_h = 36
    table_rows = max(4, len(spec.rows))
    c.box(60, y, 1340, y + row_h * (table_rows + 1), width=2)
    x = 60
    for label, w in heads:
        c.text((x, y + 8), label, font=c.small, align="center", width=w)
        c.draw.line((x, y, x, y + row_h * (table_rows + 1)), fill=(40, 40, 40), width=1)
        x += w
    for i in range(table_rows + 1):
        c.hline(60, 1340, y + row_h * i)
    for i, r in enumerate(spec.rows, start=1):
        yy = y + row_h * i + 7
        values = [f"{spec.issued.month:02d}", f"{spec.issued.day:02d}", r.name, "", str(r.quantity),
                  _won(r.unit_price), _won(r.supply), _won(r.vat), ""]  # fmt: skip
        x = 60
        for (label, w), value in zip(heads, values, strict=True):
            if label in {"월", "일", "품목", "규격", "비고"}:
                c.text((x + 10, yy), value)
            else:
                c.text((x + 10, yy), value, align="right", width=w - 20)
            x += w
    # 합계 줄
    y += row_h * (table_rows + 1) + 20
    total = spec.supply_total + spec.vat_total
    foots = [
        ("합계금액", _won(total), 300),
        ("현금", "", 240),
        ("수표", "", 240),
        ("어음", "", 240),
        ("외상미수금", _won(total), 240),
    ]
    c.box(60, y, 1340, y + 70, width=2)
    x = 60
    for label, value, w in foots:
        c.text((x, y + 6), label, font=c.small, align="center", width=w)
        c.hline(x, x + w, y + 30)
        c.text((x + 12, y + 38), value, align="right", width=w - 24)
        x += w
        c.draw.line((x, y, x, y + 70), fill=(40, 40, 40), width=1)
    c.text((60, y + 90), "이 금액을 (청구) 함", align="right", width=1280)
    return c


def render_statement(spec: EvidenceSpec) -> _Canvas:
    c = _Canvas(1000, 1250, 20)
    c.text((0, 40), "거 래 명 세 서", font=c.big, align="center", width=1000)
    c.text((60, 110), f"거래일자 : {spec.issued:%Y년 %m월 %d일}")
    c.text((60, 145), f"공급받는자 : {spec.buyer.name}  귀하")
    s = spec.supplier
    c.box(540, 100, 950, 290, width=2)
    lines = [("등록번호", s.business_no), ("상 호", s.name), ("대 표", s.owner),
             ("주 소", s.address), ("전 화", s.phone)]  # fmt: skip
    for i, (label, value) in enumerate(lines):
        c.text((552, 110 + i * 36), label, font=c.small)
        c.text((640, 108 + i * 36), value)
    y = 330
    with_vat = spec.variant == "with-vat"
    if with_vat:
        heads = [("No", 60), ("품 목", 380), ("수량", 100), ("단 가", 150), ("공급가액", 150)]
        heads.append(("세액", 100))
    else:
        heads = [("No", 60), ("품 목", 420), ("수량", 110), ("단 가", 170), ("금 액", 180)]
    row_h = 40
    table_rows = max(6, len(spec.rows))
    width = sum(w for _, w in heads)
    c.box(60, y, 60 + width, y + row_h * (table_rows + 1), width=2)
    x = 60
    for label, w in heads:
        c.text((x, y + 9), label, align="center", width=w)
        c.draw.line((x, y, x, y + row_h * (table_rows + 1)), fill=(40, 40, 40), width=1)
        x += w
    for i in range(table_rows + 1):
        c.hline(60, 60 + width, y + row_h * i)
    for i, r in enumerate(spec.rows, start=1):
        yy = y + row_h * i + 9
        values = [str(i), r.name, str(r.quantity), _won(r.unit_price), _won(r.supply)]
        if with_vat:
            values.append(_won(r.vat))
        x = 60
        for (label, w), value in zip(heads, values, strict=True):
            if label.strip() in {"No", "품 목"}:
                c.text((x + 10, yy), value)
            else:
                c.text((x + 10, yy), value, align="right", width=w - 20)
            x += w
    y += row_h * (table_rows + 1) + 24
    total = spec.supply_total + spec.vat_total
    if with_vat:
        c.text((60, y), f"공급가액 합계  {_won(spec.supply_total)}")
        c.text((60, y + 36), f"세액 합계      {_won(spec.vat_total)}")
        c.text((60, y + 72), f"총 합계        {_won(total)}", font=c.big)
    else:
        c.text((60, y), f"합계 금액 (부가세 포함)   {_won(total)}", font=c.big)
    c.text((60, y + 140), "위와 같이 거래(납품)하였습니다.        인수자 : ______________ (인)")
    return c


def render_cash_receipt(spec: EvidenceSpec) -> _Canvas:
    rows_h = 26
    height = 560 + rows_h * (len(spec.rows) + 1) + (80 if spec.variant != "simple" else 0)
    c = _Canvas(576, height, 19)
    s = spec.supplier
    y = 24
    c.text((0, y), s.name, font=c.big, align="center", width=576)
    y += 44
    for line in (f"사업자번호: {s.business_no}   대표: {s.owner}", s.address, f"TEL {s.phone}"):
        c.text((0, y), line, font=c.small, align="center", width=576)
        y += 24
    c.hline(16, 560, y + 6, dashed=True)
    y += 20
    c.text((0, y), f"[ 현금영수증 ({spec.purpose}) ]", align="center", width=576)
    y += 30
    hh, mm, ss = (int(spec.approval_no[i : i + 2]) for i in (0, 2, 4))
    c.text((16, y), f"거래일시: {spec.issued:%Y-%m-%d} {hh % 24:02d}:{mm % 60:02d}:{ss % 60:02d}")
    y += 30
    c.hline(16, 560, y, dashed=True)
    y += 10
    c.text((16, y), "상품명", font=c.small)
    c.text((300, y), "단가", font=c.small, align="right", width=90)
    c.text((400, y), "수량", font=c.small, align="right", width=50)
    c.text((460, y), "금액", font=c.small, align="right", width=100)
    y += rows_h
    for r in spec.rows:
        c.text((16, y), ("*" if r.tax_free else "") + r.name)
        c.text((300, y), _won(r.gross // r.quantity), align="right", width=90)
        c.text((400, y), str(r.quantity), align="right", width=50)
        c.text((460, y), _won(r.gross), align="right", width=100)
        y += rows_h
    if any(r.tax_free for r in spec.rows):
        c.text((16, y), "* 표시 상품은 부가세 면세품목입니다.", font=c.small)
        y += rows_h
    c.hline(16, 560, y + 4, dashed=True)
    y += 16
    total = sum(r.gross for r in spec.rows)
    if spec.variant != "simple":
        taxable = sum(r.supply for r in spec.rows if not r.tax_free)
        vat = sum(r.vat for r in spec.rows)
        tax_free = sum(r.gross for r in spec.rows if r.tax_free)
        for label, value in (("과세물품", taxable), ("부 가 세", vat), ("면세물품", tax_free)):
            c.text((16, y), label)
            c.text((360, y), _won(value), align="right", width=200)
            y += rows_h
    c.text((16, y), "합    계", font=c.big)
    c.text((300, y), _won(total), font=c.big, align="right", width=260)
    y += 46
    c.hline(16, 560, y, dashed=True)
    y += 12
    ident = (f"{s.business_no}" if spec.purpose == "지출증빙"
             else f"010-****-{int(spec.approval_no[-4:]):04d}")  # fmt: skip
    footer = (
        f"승인번호: {spec.approval_no}",
        f"식별번호: {ident}",
        f"현금({spec.purpose}) 승인",
        "국세청 현금영수증 홈페이지 hometax.go.kr",
    )
    for line in footer:
        c.text((16, y), line, font=c.small)
        y += 24
    return c


RENDERERS = {
    "TAX_INVOICE": (render_tax_invoice, "light"),
    "INVOICE": (render_statement, "light"),
    "CASH_RECEIPT": (render_cash_receipt, "thermal"),
}


# ---------------------------------------------------------------- 표본 · 데이터셋


def generate_sample(
    out_dir: Path,
    sample_id: str,
    spec: EvidenceSpec,
    seed: int,
    *,
    degrade_image: bool = True,
    with_boxes: bool = False,
) -> LabelRecord:
    """`with_boxes` 면 그려진 텍스트의 박스를 비율 좌표로 라벨에 남긴다.

    좌표 정답을 쓸 때는 `degrade_image=False` 를 권한다 — 열화의 회전(<1도)이 박스를 몇 px
    흔들어 IoU 를 깎는다. "좌표를 낼 수 있나" 는 깨끗한 렌더에서 먼저 답할 문제다.
    """
    render, preset = RENDERERS[spec.kind]
    canvas = render(spec)
    out_dir.mkdir(parents=True, exist_ok=True)
    bgr = cv2.cvtColor(np.asarray(canvas.image), cv2.COLOR_RGB2BGR)
    params: dict[str, object] = {"preset": None}
    degraded = bgr
    if degrade_image:
        degraded, params = degrade(bgr, preset, seed)
    image_name = f"{sample_id}.jpg"
    cv2.imwrite(str(out_dir / image_name), degraded, [cv2.IMWRITE_JPEG_QUALITY, 93])
    evidence_type: EvidenceType = spec.kind
    record = LabelRecord(
        image=image_name,
        evidence_type=evidence_type,
        extraction=ground_truth(spec),
        meta=LabelMeta(
            source="synthetic",
            source_id=sample_id,
            unlabeled_fields=["items.unit"],
            notes=[],
            extra={
                "itemsComplete": True,
                "generator": GENERATOR_VERSION,
                "kind": spec.kind,
                "variant": spec.variant,
                "seed": seed,
                # 세금계산서·거래명세서 행 금액은 공급가액(부가세 제외) — 품목 합 = supplyAmount
                "itemAmountBasis": "gross" if spec.kind == "CASH_RECEIPT" else "supply",
                "degrade": params,
                **(
                    {
                        "textLayer": [
                            {
                                "text": text,
                                "box": [
                                    round(box[0] / canvas.image.width, 5),
                                    round(box[1] / canvas.image.height, 5),
                                    round((box[2] - box[0]) / canvas.image.width, 5),
                                    round((box[3] - box[1]) / canvas.image.height, 5),
                                ],
                            }
                            for text, box in canvas.texts
                        ]
                    }
                    if with_boxes
                    else {}
                ),
                "fontPath": canvas.font_path,
                "fontFallback": canvas.fallback,
            },
        ),
    )
    write_label(out_dir / f"{sample_id}.json", record)
    return record


def generate_dataset(
    out: Path,
    count: int,
    kinds: list[str],
    seed: int,
    *,
    degrade_image: bool = True,
    with_boxes: bool = False,
) -> dict[str, int]:
    rng = np.random.default_rng(seed)
    counts: dict[str, int] = {}
    for kind in kinds:
        slug = KIND_DIRS[kind]  # type: ignore[index]
        directory = out / slug
        for index in range(count):
            spec = random_spec(rng, kind)  # type: ignore[arg-type]
            generate_sample(
                directory,
                f"synth-{slug}-{index:03d}",
                spec,
                seed * 1000 + index,
                degrade_image=degrade_image,
                with_boxes=with_boxes,
            )
        counts[kind] = count
        write_manifest(
            directory,
            {
                "dataset": f"synthetic-{slug}",
                "generator": GENERATOR_VERSION,
                "kind": kind,
                "count": count,
                "seed": seed,
                "generatedAt": datetime.now(UTC).isoformat(timespec="seconds"),
                "note": "합성 양식 — 실제 국세청·업체 양식과 다르다. 품목 라벨 완전(itemsComplete)",
            },
        )
    return counts


def main() -> int:
    parser = argparse.ArgumentParser(
        description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter
    )
    parser.add_argument("--out", type=Path, required=True)
    parser.add_argument("--count", type=int, default=15, help="유형당 장수")
    parser.add_argument("--kinds", nargs="*", default=list(KIND_DIRS), choices=list(KIND_DIRS))
    parser.add_argument("--seed", type=int, default=11)
    parser.add_argument(
        "--with-boxes",
        action="store_true",
        help="그려진 텍스트의 박스를 라벨에 남긴다 (boundingBox 벤치마크용)",
    )
    parser.add_argument(
        "--no-degrade", action="store_true", help="열화를 끈다 — 좌표 정답을 쓸 때 권장"
    )
    args = parser.parse_args()
    counts = generate_dataset(
        args.out,
        args.count,
        args.kinds,
        args.seed,
        degrade_image=not args.no_degrade,
        with_boxes=args.with_boxes,
    )
    print(f"생성: {counts} → {args.out}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
