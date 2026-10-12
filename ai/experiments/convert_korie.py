"""KORIE(한국 소매 영수증 벤치마크) IE 라벨 → 우리 평가 라벨(`experiments.labels`) 변환.

    uv run python -X utf8 -m experiments.convert_korie datasets/korie/raw --split val --dry-run
    uv run python -X utf8 -m experiments.convert_korie datasets/korie/raw --split val
    uv run python -X utf8 -m experiments.convert_korie datasets/korie/raw --split test   # freeze 후

분할 규칙
- train: 컨버터 디버깅
- val:   프롬프트·스키마·검산 조정 (개발). 빠른 반복은 val 안의 고정 50장 dev subset 으로
- test:  모든 설정을 freeze 한 뒤 **최종 측정 1회**. test 로 개발하면 수치가 오염된다

동작
- 입력: Drive 에서 내려받아 푼 폴더. 공개 문서에 라벨 형식이 없어 **파일에서 자동 감지**한다 —
  이미지(.png/.jpg/.jpeg/.webp)와 라벨(.json/.jsonl/.csv)을 재귀로 찾고, 파일명에 분할 이름이
  있으면(`items_test.csv` 등) 그 분할의 파일만 쓴다.
- 출력: `<out>/<split>/korie-<split>-<id>.<ext>` + 같은 이름의 `.json`, 그리고 `manifest.json`
  (소스 커밋·변환 일시·분할·장수·라벨 파일 sha256·변환 가정). 결과 보고에는 이 manifest 를 인용한다.
- 매핑: KORIE 엔티티 17종 → 우리 필드. 사업자등록번호·품목 단위는 KORIE 에 없다 → `unlabeledFields`.
  `TotalTax → vatAmount` 는 기본(가정으로 manifest 에 기록). `Subtotal → supplyAmount` 는
  **기본 미매핑** — `--map-subtotal` 을 켠 경우에만, 그것도 `Subtotal + TotalTax == Total` 일 때만.
  변환 가정이 정답에 섞이면 모델 오류와 변환 오류를 가를 수 없다.
- 형식을 인식하지 못하면 첫 레코드의 키(CSV 는 헤더)를 보여주고 종료 코드 2 로 끝난다.
- 데이터는 레포에 올리지 않는다(`datasets/` 는 .gitignore). 라이선스 미표기 — 내부 평가용으로만.
"""

from __future__ import annotations

import argparse
import csv
import hashlib
import json
import re
import shutil
from collections import Counter
from dataclasses import dataclass, field
from datetime import UTC, date, datetime
from pathlib import Path
from typing import Any

from app.ocr.schemas import EvidenceExtraction, ExtractedItem
from experiments.labels import IMAGE_SUFFIXES, LabelMeta, LabelRecord, write_label

CONVERTER_VERSION = "convert_korie/v1"
KORIE_REPOSITORY = "https://github.com/MahmoudSalah/KORIE"
_SPLIT_ALIASES = {
    "train": ("train",),
    "val": ("val", "valid", "validation", "dev"),
    "test": ("test",),
}

# ---------------------------------------------------------------- KORIE 엔티티 → 정규 이름

_CANONICAL = {
    "merchantname": "MerchantName",
    "merchantaddress": "MerchantAddress",
    "merchantphonenumber": "MerchantPhoneNumber",
    "transactiondate": "TransactionDate",
    "paymentdate": "PaymentDate",  # OCR 전사 트랙 — TransactionDate 가 없을 때 issuedDate 대체
    "transactiontime": "TransactionTime",
    "receiptnumber": "ReceiptNumber",
    "item": "Item",
    "itemname": "Item",  # items_*.csv 의 `Item_Name`
    "description": "Description",
    "quantity": "Quantity",
    "numberofunits": "Quantity",  # items_*.csv 의 `Number of units`
    "price": "Price",
    "unitprice": "Price",
    "totalprice": "TotalPrice",
    "itembarcode": "Item_barcode",
    "productcode": "ProductCode",
    "subtotal": "Subtotal",
    "totaltax": "TotalTax",
    "total": "Total",
    "tip": "Tip",
}
ITEM_LABELS = {
    "Item",
    "Description",
    "Quantity",
    "Price",
    "TotalPrice",
    "Item_barcode",
    "ProductCode",
}

LABEL_KEYS = ("label", "category", "class", "entity", "key", "field", "tag", "type")
TEXT_KEYS = ("text", "transcription", "value", "content", "word", "ocr")
BOX_KEYS = ("bbox", "box", "points", "polygon", "coordinates", "quad")
IMAGE_KEYS = (
    "image",
    "image_path",
    "image_name",
    "image_file",
    "image_id",
    "img",
    "img_name",
    "file_name",
    "filename",
    "file",
    "path",
    "receipt_id",
    "receipt",
    "id",
)
_BOX_COLUMN_SETS = (
    ("x1", "y1", "x2", "y2"),
    ("xmin", "ymin", "xmax", "ymax"),
    ("left", "top", "right", "bottom"),
)
ENTITY_LIST_KEYS = (
    "entities",
    "annotations",
    "fields",
    "labels",
    "form",
    "shapes",
    "objects",
    "items",
)
GROUP_KEYS = ("group", "group_id", "item_id", "line", "line_id", "row", "linking")


def canonical_label(raw: str) -> str | None:
    return _CANONICAL.get(re.sub(r"[\s_\-]", "", str(raw)).lower())


@dataclass
class Entity:
    label: str
    text: str
    box: tuple[float, float, float, float] | None = None  # x1, y1, x2, y2
    group: str | None = None


@dataclass
class RawRecord:
    source_id: str
    image: Path | None
    entities: list[Entity] = field(default_factory=list)
    origin: str = ""
    notes: list[str] = field(default_factory=list)


class FormatNotRecognized(Exception):
    pass


# ---------------------------------------------------------------- 값 파싱

_DATE_PATTERNS = (
    re.compile(r"(?P<y>\d{4})\s*[.\-/년]\s*(?P<m>\d{1,2})\s*[.\-/월]\s*(?P<d>\d{1,2})"),
    re.compile(r"(?<!\d)(?P<y>\d{2})\s*[.\-/]\s*(?P<m>\d{1,2})\s*[.\-/]\s*(?P<d>\d{1,2})(?!\d)"),
    re.compile(r"(?<!\d)(?P<y>\d{4})(?P<m>\d{2})(?P<d>\d{2})(?!\d)"),
)


def parse_date(text: str | None) -> date | None:
    """`2023.05.04 14:22` `23-05-04` `2023년 5월 4일` `20230504` → date. 못 읽으면 None."""
    if not text:
        return None
    for pattern in _DATE_PATTERNS:
        match = pattern.search(text)
        if not match:
            continue
        year, month, day = (int(match["y"]), int(match["m"]), int(match["d"]))
        if year < 100:
            year += 2000
        try:
            return date(year, month, day)
        except ValueError:
            return None
    return None


def parse_amount(text: str | None) -> int | None:
    """`12,000원` `₩12,000` `-1,000` → 원 단위 정수. 숫자가 없으면 None."""
    if text is None:
        return None
    cleaned = re.sub(r"[^\d\-.]", "", str(text).replace(",", ""))
    if not cleaned or not re.search(r"\d", cleaned):
        return None
    try:
        return int(round(float(cleaned)))
    except ValueError:
        return None


def parse_quantity(text: str | None) -> float | None:
    if text is None:
        return None
    match = re.search(r"\d+(?:\.\d+)?", str(text).replace(",", ""))
    return float(match.group()) if match else None


_CODE_TOKEN = re.compile(r"^\[?\d[\d,.\-]*\]?\s*[#*]*$")  # 가격·바코드·[상품코드]·끝 기호
_LEADING_INDEX = re.compile(r"^\d{1,3}\s+(?=\S)")  # "005 청피망" 의 행 번호
_TRAILING_MARK = re.compile(r"\s*[#*]+$")
_HAS_LETTER = re.compile(r"[^\d\s,.\-\[\]#*]")


def clean_item_name(text: str) -> str:
    """KORIE `Item_Name` 은 영수증 한 줄 전체다 — `005 청피망 1봉, 23000476, 4,990, 1, 4,990 #`.

    쉼표로 나눠 가격·바코드·[코드]·기호 토큰을 버리고, 첫 글자 토큰에서 행 번호를 떼어
    상품명만 남긴다. 이미 깨끗한 이름(`서울우유 1L`)은 그대로 통과한다.
    """
    for token in (t.strip() for t in text.split(",")):
        if not token or _CODE_TOKEN.match(token):
            continue
        candidate = _TRAILING_MARK.sub("", _LEADING_INDEX.sub("", token)).strip()
        if _HAS_LETTER.search(candidate):
            return candidate
    return text.strip()


# ---------------------------------------------------------------- 엔티티 dict 해석


def _norm_key(key: str) -> str:
    return re.sub(r"[\s_\-]", "", str(key)).lower()


def _first_key(record: dict[str, Any], candidates: tuple[str, ...]) -> str | None:
    """대소문자·공백·밑줄을 무시하고 후보와 맞는 첫 키 (`File Name` == `file_name`)."""
    normalized = {_norm_key(key): key for key in record}
    for candidate in candidates:
        if _norm_key(candidate) in normalized:
            return normalized[_norm_key(candidate)]
    return None


def _box_from_columns(record: dict[str, Any]) -> tuple[float, float, float, float] | None:
    """`x1,y1,x2,y2` 처럼 좌표가 열로 흩어진 경우."""
    for names in _BOX_COLUMN_SETS:
        keys = [_first_key(record, (name,)) for name in names]
        if all(keys):
            return _box_from([record[key] for key in keys])  # type: ignore[index]
    return None


def _box_from(value: Any) -> tuple[float, float, float, float] | None:
    try:
        flat = [float(v) for v in _flatten(value)]
    except (TypeError, ValueError):
        return None
    if len(flat) == 4 and flat[2] >= flat[0] and flat[3] >= flat[1]:
        return flat[0], flat[1], flat[2], flat[3]
    if len(flat) >= 6 and len(flat) % 2 == 0:  # 폴리곤 → 외접 상자
        xs, ys = flat[0::2], flat[1::2]
        return min(xs), min(ys), max(xs), max(ys)
    return None


def _flatten(value: Any) -> list[Any]:
    if isinstance(value, dict):
        value = [value.get(k) for k in ("x", "y", "x1", "y1", "x2", "y2", "width", "height")]
        value = [v for v in value if v is not None]
    if isinstance(value, list | tuple):
        out: list[Any] = []
        for item in value:
            out.extend(_flatten(item))
        return out
    return [value]


def entity_from_dict(record: dict[str, Any]) -> Entity | None:
    label_key = _first_key(record, LABEL_KEYS)
    text_key = _first_key(record, TEXT_KEYS)
    if label_key is None or text_key is None:
        return None
    label = canonical_label(record[label_key])
    if label is None:
        return None
    box_key = _first_key(record, BOX_KEYS)
    group_key = _first_key(record, GROUP_KEYS)
    text = record[text_key]
    if isinstance(text, list):
        text = " ".join(str(t) for t in text)
    return Entity(
        label=label,
        text=str(text).strip(),
        box=_box_from(record[box_key]) if box_key else _box_from_columns(record),
        group=str(record[group_key]) if group_key and record[group_key] is not None else None,
    )


def entities_from_keyvalue(record: dict[str, Any]) -> list[Entity]:
    """`{"MerchantName": "...", "Total": "12,000", "Items": [{"Item": ..}]}` 모양."""
    entities: list[Entity] = []
    for key, value in record.items():
        label = canonical_label(key)
        if label is not None and not isinstance(value, dict | list):
            if value not in (None, ""):
                entities.append(Entity(label=label, text=str(value).strip()))
            continue
        if isinstance(value, list) and value and all(isinstance(v, dict) for v in value):
            for index, row in enumerate(value):
                row_entities = entities_from_keyvalue(row)
                for entity in row_entities:
                    entity.group = f"{key}:{index}"
                entities.extend(row_entities)
    return entities


def entities_from_any(payload: Any) -> list[Entity]:
    """리스트(엔티티 dict 들) 또는 dict(엔티티 리스트 키 / 키-값 모양)에서 엔티티를 뽑는다."""
    if isinstance(payload, list):
        parsed = [entity_from_dict(p) for p in payload if isinstance(p, dict)]
        return [e for e in parsed if e is not None]
    if isinstance(payload, dict):
        list_key = _first_key(payload, ENTITY_LIST_KEYS)
        if list_key and isinstance(payload[list_key], list):
            found = entities_from_any(payload[list_key])
            if found:
                return found
        return entities_from_keyvalue(payload)
    return []


# ---------------------------------------------------------------- 파일 탐색과 형식 감지


def _image_index(raw_dir: Path) -> dict[str, Path]:
    index: dict[str, Path] = {}
    for path in sorted(raw_dir.rglob("*")):
        if path.suffix.lower() in IMAGE_SUFFIXES:
            index.setdefault(path.stem, path)
            index.setdefault(path.name, path)
    return index


def _resolve_image(ref: Any, images: dict[str, Path]) -> Path | None:
    if ref is None:
        return None
    name = Path(str(ref)).name
    return images.get(name) or images.get(Path(name).stem)


def _load_json_any(path: Path) -> Any:
    text = path.read_text(encoding="utf-8-sig")
    if path.suffix.lower() == ".jsonl":
        return [json.loads(line) for line in text.splitlines() if line.strip()]
    return json.loads(text)


def _records_from_index_payload(
    payload: Any, images: dict[str, Path], origin: str
) -> list[RawRecord]:
    """한 파일에 여러 영수증이 든 인덱스 형식(리스트 또는 {id: {...}})."""
    records: list[RawRecord] = []
    rows: list[tuple[str | None, Any]]
    if isinstance(payload, dict):
        rows = [(str(key), value) for key, value in payload.items()]
    elif isinstance(payload, list):
        rows = [(None, value) for value in payload]
    else:
        return records
    for key, value in rows:
        if not isinstance(value, dict):
            continue
        image_key = _first_key(value, IMAGE_KEYS)
        image_ref = value.get(image_key) if image_key else key
        entities = entities_from_any(value)
        if not entities:
            continue
        source_id = Path(str(image_ref or key or len(records))).stem
        records.append(
            RawRecord(source_id, _resolve_image(image_ref or key, images), entities, origin)
        )
    return records


def _csv_header(path: Path) -> list[str]:
    with path.open(encoding="utf-8-sig", newline="") as handle:
        return next(csv.reader(handle), [])


def _records_from_csv(path: Path, images: dict[str, Path]) -> list[RawRecord]:
    """CSV 세 모양을 다룬다.

    (a) 한 행 = 엔티티 하나 — `label, text[, x1, y1, x2, y2]` 열
    (b) 한 행 = 영수증 하나 — 엔티티 이름이 열
    (c) 한 행 = 품목 한 줄, 영수증 필드(MerchantName·Total…)가 행마다 반복 —
        `items_test.csv` 추정형. 품목 열은 행 단위로 묶고, 반복되는 영수증 필드는 첫 값만
        남긴다(값이 서로 다르면 notes 에 남김).
    """
    with path.open(encoding="utf-8-sig", newline="") as handle:
        rows = list(csv.DictReader(handle))
    if not rows:
        return []
    image_key = _first_key(rows[0], IMAGE_KEYS)
    if image_key is None:
        raise FormatNotRecognized(f"{path.name}: 이미지 열을 찾지 못했습니다. 열: {list(rows[0])}")
    by_image: dict[str, RawRecord] = {}
    for index, row in enumerate(rows):
        ref = row[image_key]
        record = by_image.setdefault(
            ref, RawRecord(Path(ref).stem, _resolve_image(ref, images), [], path.name)
        )
        entity = entity_from_dict(row)
        if entity is not None:  # (a)
            record.entities.append(entity)
            continue
        seen = {(e.label, e.text) for e in record.entities if e.label not in ITEM_LABELS}
        for entity in entities_from_keyvalue(row):  # (b)·(c)
            if entity.label in ITEM_LABELS:
                entity.group = entity.group or f"{path.name}:{index}"
                record.entities.append(entity)
                continue
            if (entity.label, entity.text) in seen:
                continue
            if any(e.label == entity.label for e in record.entities):
                record.notes.append(f"{entity.label} 값이 행마다 다름 — 첫 값 사용")
                continue
            seen.add((entity.label, entity.text))
            record.entities.append(entity)
    return [r for r in by_image.values() if r.entities]


_OCR_CROP = re.compile(r"^(?P<image>[A-Za-z]+\d+)_(?P<entity>[A-Za-z_]+)$")


def ocr_transcriptions(raw_dir: Path) -> dict[str, list[Entity]]:
    """OCR 트랙: `<이미지>_<엔티티>.txt`(전사) → 영수증 단위 엔티티.

    KORIE 의 IE CSV 는 품목 행만 있고, 상호명·날짜·합계·부가세 텍스트는 OCR 트랙의 crop 전사에만
    있다. 파일명이 `IMG00077_TotalTax.txt` 꼴이라 이미지·엔티티를 이름으로 안다. 품목 엔티티
    (Item_barcode 등)는 crop 이 하나뿐이라 버리고 CSV 에 맡긴다. YOLO 라벨(`IMG00640.txt`)은
    `_엔티티` 가 없어 걸러진다.
    """
    found: dict[str, list[Entity]] = {}
    for path in raw_dir.rglob("*.txt"):
        match = _OCR_CROP.match(path.stem)
        if not match:
            continue
        label = canonical_label(match["entity"])
        if label is None or label in ITEM_LABELS:
            continue
        try:
            text = path.read_text(encoding="utf-8", errors="replace").strip()
        except OSError:
            continue
        if text:
            found.setdefault(match["image"], []).append(Entity(label=label, text=text))
    return found


def _enrich_with_ocr(raw_dir: Path, records: list[RawRecord]) -> int:
    """레코드에 없는 영수증 단위 엔티티를 OCR 전사로 채운다. 보강된 레코드 수를 돌려준다."""
    transcriptions = ocr_transcriptions(raw_dir)
    enriched = 0
    for record in records:
        present = {e.label for e in record.entities}
        added = [e for e in transcriptions.get(record.source_id, []) if e.label not in present]
        if added:
            record.entities.extend(added)
            record.notes.append("OCR 전사 보강: " + ", ".join(sorted({e.label for e in added})))
            enriched += 1
    return enriched


def label_files_for(raw_dir: Path, split: str | None = None) -> list[Path]:
    """라벨 파일 목록. 파일명에 분할 이름이 든 파일이 있으면 그 분할만 (`items_test.csv`)."""
    files = [
        p for p in sorted(raw_dir.rglob("*")) if p.suffix.lower() in {".json", ".jsonl", ".csv"}
    ]
    if split:
        tokens = _SPLIT_ALIASES.get(split.lower(), (split.lower(),))
        matched = [
            p
            for p in files
            if any(re.search(rf"(?<![a-z]){t}(?![a-z])", p.stem.lower()) for t in tokens)
        ]
        if matched:
            return matched
    return files


def load_records(raw_dir: Path, split: str | None = None) -> tuple[str, list[RawRecord]]:
    """(감지한 형식 설명, 레코드) 를 돌려준다. 인식 실패 시 FormatNotRecognized."""
    images = _image_index(raw_dir)
    label_files = label_files_for(raw_dir, split)
    if not label_files:
        raise FormatNotRecognized(f"{raw_dir}: .json/.jsonl/.csv 라벨 파일이 없습니다.")

    per_image: list[RawRecord] = []
    index_records: list[RawRecord] = []
    first_payload_keys: list[str] = []
    csv_columns: list[str] = []
    for path in label_files:
        if path.suffix.lower() == ".csv":
            header = _csv_header(path)
            first_payload_keys = first_payload_keys or header[:20]
            csv_columns.append(f"{path.name} 열: {header[:15]}")
            index_records.extend(_records_from_csv(path, images))
            continue
        payload = _load_json_any(path)
        if isinstance(payload, dict) and not first_payload_keys:
            first_payload_keys = list(payload)[:20]
        elif isinstance(payload, list) and payload and isinstance(payload[0], dict):
            first_payload_keys = first_payload_keys or list(payload[0])[:20]
        matched_image = images.get(path.stem)
        if matched_image is not None:  # 이미지 1장 = 라벨 파일 1개
            entities = entities_from_any(payload)
            if entities:
                per_image.append(RawRecord(path.stem, matched_image, entities, path.name))
                continue
        index_records.extend(_records_from_index_payload(payload, images, path.name))

    if per_image:
        detected, records = "이미지별 라벨 파일", per_image
    elif index_records:
        detail = f" · {'; '.join(csv_columns)}" if csv_columns else ""
        detected, records = f"인덱스 파일(여러 영수증){detail}", index_records
    else:
        raise FormatNotRecognized(
            "라벨 형식을 인식하지 못했습니다. 첫 레코드 키: "
            f"{first_payload_keys or '(없음)'} — 이 출력으로 파서를 보탠다."
        )
    enriched = _enrich_with_ocr(raw_dir, records)
    if enriched:
        detected += f" · OCR 전사 보강 {enriched}건"
    return detected, records


# ---------------------------------------------------------------- 우리 스키마로 변환


def _order(entities: list[Entity]) -> list[Entity]:
    if all(e.box is not None for e in entities) and entities:
        return sorted(entities, key=lambda e: (round(e.box[1] / 10), e.box[0]))  # type: ignore[index]
    return entities


def build_items(entities: list[Entity]) -> tuple[list[ExtractedItem], list[str]]:
    """품목 엔티티를 행으로 묶는다. 명시적 그룹이 있으면 그것을, 없으면 읽기 순서로."""
    notes: list[str] = []
    item_entities = [e for e in entities if e.label in ITEM_LABELS]
    rows: list[dict[str, str]] = []
    if any(e.group for e in item_entities):
        by_group: dict[str, dict[str, str]] = {}
        for entity in item_entities:
            by_group.setdefault(entity.group or "", {})[entity.label] = entity.text
        rows = list(by_group.values())
    else:
        current: dict[str, str] = {}
        for entity in _order(item_entities):
            if entity.label == "Item" or entity.label in current:
                if current:
                    rows.append(current)
                current = {}
            current[entity.label] = entity.text
        if current:
            rows.append(current)
    items: list[ExtractedItem] = []
    for row in rows:
        name = row.get("Item") or row.get("Description")
        if not name:
            notes.append("이름 없는 품목 행을 건너뜀")
            continue
        items.append(
            ExtractedItem(
                raw_name=clean_item_name(name),
                quantity=parse_quantity(row.get("Quantity")),
                unit=None,
                unit_price=parse_amount(row.get("Price")),
                amount=parse_amount(row.get("TotalPrice")),
            )
        )
    return items, notes


def to_label(record: RawRecord, split: str, *, map_subtotal: bool = False) -> LabelRecord:
    fields: dict[str, list[str]] = {}
    for entity in record.entities:
        fields.setdefault(entity.label, []).append(entity.text)

    def one(label: str) -> str | None:
        """이름처럼 여러 상자로 쪼개질 수 있는 값 — 이어 붙인다."""
        values = fields.get(label)
        return " ".join(values).strip() if values else None

    def first_amount(label: str) -> int | None:
        """금액·날짜는 이어 붙이면 망가진다 — 해석되는 첫 값만 쓴다."""
        for value in fields.get(label, []):
            parsed = parse_amount(value)
            if parsed is not None:
                return parsed
        return None

    total = first_amount("Total")
    tax = first_amount("TotalTax")
    subtotal = first_amount("Subtotal")
    unlabeled = ["supplierBusinessNo", "items.unit"]  # KORIE 에 없는 필드
    notes: list[str] = []
    supply: int | None = None
    arithmetic_ok = (
        subtotal is not None and tax is not None and total is not None and subtotal + tax == total
    )
    if map_subtotal and arithmetic_ok:
        supply = subtotal
        notes.append("Subtotal → supplyAmount (Subtotal + TotalTax == Total 확인됨)")
    else:
        unlabeled.append("supplyAmount")
        if map_subtotal and subtotal is not None:
            notes.append("Subtotal 이 Total-TotalTax 와 달라 supplyAmount 로 쓰지 않음")
    if tax is None:
        unlabeled.append("vatAmount")
    # 원천에 그 엔티티가 아예 없으면 "없음(null 이 정답)"이 아니라 "판정 불가"다.
    # items_*.csv 는 품목 행만 있어 상호명·날짜·합계가 전부 여기에 해당한다.
    if "MerchantName" not in fields:
        unlabeled.append("supplierName")
    if "Total" not in fields:
        unlabeled.append("totalAmount")
    # 거래일 전사가 없으면 결제일(PaymentDate)로 대신한다 — 영수증에서는 보통 같은 날이다
    if "TransactionDate" in fields:
        date_values = fields["TransactionDate"]
    elif "PaymentDate" in fields:
        date_values = fields["PaymentDate"]
        notes.append("issuedDate ← PaymentDate (TransactionDate 전사 없음)")
    else:
        date_values = []
        unlabeled.append("issuedDate")

    items, item_notes = build_items(record.entities)
    issued_date = next((d for d in map(parse_date, date_values) if d is not None), None)
    extraction = EvidenceExtraction(
        supplier_name=one("MerchantName"),
        supplier_business_no=None,
        issued_date=issued_date,
        supply_amount=supply,
        vat_amount=tax,
        total_amount=total,
        items=items,
    )
    if extraction.issued_date is None and date_values:
        notes.append(f"날짜 해석 실패: {' | '.join(date_values)!r}")
    return LabelRecord(
        image=record.image.name if record.image else "",
        evidence_type="RECEIPT",
        extraction=extraction,
        meta=LabelMeta(
            source="korie",
            source_id=record.source_id,
            split=split,
            unlabeled_fields=unlabeled,
            notes=record.notes + notes + item_notes,
            # IE CSV 는 영수증당 평균 1.6 행만 라벨돼 있다(실측). 라벨에 없는 행이 실제 영수증에는
            # 있으므로 품목은 재현율만 평가한다 — 환각률은 완전 라벨(합성 POS·CORD)에서 잰다.
            extra={"itemsComplete": False},
        ),
    )


def _sha256(path: Path) -> str:
    return hashlib.sha256(path.read_bytes()).hexdigest()


def convert(
    raw_dir: Path,
    out_dir: Path,
    split: str,
    *,
    limit: int | None = None,
    map_subtotal: bool = False,
    source_commit: str | None = None,
) -> dict[str, int]:
    """`<out_dir>/<split>/` 에 이미지·라벨·manifest.json 을 쓴다. 반환값은 건수 통계."""
    detected, records = load_records(raw_dir, split)
    label_files = label_files_for(raw_dir, split)
    print(f"[감지] {detected} — 레코드 {len(records)}건")
    target_dir = out_dir / split
    target_dir.mkdir(parents=True, exist_ok=True)
    stats: Counter[str] = Counter()
    for record in records[:limit]:
        if record.image is None:
            stats["이미지 없음(건너뜀)"] += 1
            continue
        label = to_label(record, split, map_subtotal=map_subtotal)
        target = target_dir / f"korie-{split}-{record.source_id}{record.image.suffix.lower()}"
        shutil.copy2(record.image, target)
        label.image = target.name
        write_label(target.with_suffix(".json"), label)
        stats["변환"] += 1
        for name, value in (
            ("총액", label.extraction.total_amount),
            ("날짜", label.extraction.issued_date),
            ("부가세", label.extraction.vat_amount),
            ("공급가", label.extraction.supply_amount),
        ):
            if value is not None:
                stats[f"정답 있음:{name}"] += 1
        if label.extraction.items:
            stats["정답 있음:품목"] += 1

    # 재현성 — "KORIE 150장" 이 아니라 어느 커밋·어느 파일·몇 장·어떤 가정으로 만든 라벨인지 남긴다.
    manifest = {
        "dataset": "KORIE",
        "repository": KORIE_REPOSITORY,
        "sourceCommit": source_commit,
        "convertedAt": datetime.now(UTC).isoformat(timespec="seconds"),
        "converter": CONVERTER_VERSION,
        "split": split,
        "detectedFormat": detected,
        "labelFiles": [
            {"name": p.name, "bytes": p.stat().st_size, "sha256": _sha256(p)} for p in label_files
        ],
        "sources": [
            "IE 트랙 items_<split>.csv — 품목 행 (레코드·분할의 기준)",
            "OCR 트랙 <이미지>_<엔티티>.txt — 상호명·날짜·합계·부가세 전사 (분할 무관하게 병합)",
            "Detection 트랙 이미지 — 어느 분할 폴더에 있든 이름으로 매칭",
        ],
        "records": len(records),
        "converted": stats["변환"],
        "skippedNoImage": stats["이미지 없음(건너뜀)"],
        "coverage": {k.split(":", 1)[1]: v for k, v in stats.items() if k.startswith("정답 있음:")},
        "assumptions": [
            "품목 라벨은 부분 라벨(itemsComplete=false) — 재현율만 평가, 정밀도·환각률 평가 불가",
            "TotalTax → vatAmount",
            (
                "Subtotal → supplyAmount (Subtotal + TotalTax == Total 일 때만)"
                if map_subtotal
                else "Subtotal 미매핑 — supplyAmount 는 판정 불가(unlabeledFields)"
            ),
        ],
        "alwaysUnlabeled": ["supplierBusinessNo", "items.unit"],
    }
    (target_dir / "manifest.json").write_text(
        json.dumps(manifest, ensure_ascii=False, indent=2) + "\n", encoding="utf-8"
    )
    return dict(stats)


def main() -> int:
    parser = argparse.ArgumentParser(
        description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter
    )
    parser.add_argument("raw_dir", type=Path, help="Drive 에서 내려받아 푼 KORIE 폴더")
    parser.add_argument(
        "--split",
        default="val",
        help="train=컨버터 디버깅 / val=개발(기본) / test=freeze 후 최종 측정 1회",
    )
    parser.add_argument("--out", type=Path, default=Path("datasets/receipts/korie"))
    parser.add_argument("--limit", type=int, default=None, help="앞에서 N건만 변환")
    parser.add_argument(
        "--map-subtotal",
        action="store_true",
        help="Subtotal + TotalTax == Total 이면 Subtotal 을 supplyAmount 로 쓴다 (기본 미매핑)",
    )
    parser.add_argument(
        "--source-commit", default=None, help="KORIE 레포 커밋 해시 — manifest 에 기록 (재현성)"
    )
    parser.add_argument("--dry-run", action="store_true", help="형식 감지와 필드 커버리지만 출력")
    args = parser.parse_args()
    if args.split.lower() == "test":
        print("[주의] test 분할은 모든 설정을 freeze 한 뒤 최종 측정에만 쓴다. 개발은 val 로.")
    try:
        if args.dry_run:
            detected, records = load_records(args.raw_dir, args.split)
            coverage: Counter[str] = Counter()
            for record in records:
                for label in {e.label for e in record.entities}:
                    coverage[label] += 1
            print(
                f"[감지] {detected} — 레코드 {len(records)}건, 이미지 매칭 "
                f"{sum(r.image is not None for r in records)}건"
            )
            for label, count in coverage.most_common():
                print(f"  {label:<20} {count}")
            return 0
        stats = convert(
            args.raw_dir,
            args.out,
            args.split,
            limit=args.limit,
            map_subtotal=args.map_subtotal,
            source_commit=args.source_commit,
        )
    except FormatNotRecognized as exc:
        print(f"[실패] {exc}")
        return 2
    for key, value in sorted(stats.items()):
        print(f"  {key:<16} {value}")
    print(f"  manifest         {args.out / args.split / 'manifest.json'}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
