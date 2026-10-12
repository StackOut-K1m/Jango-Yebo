"""평가 세트 라벨 형식 — 출처(KORIE·합성·CORD)가 달라도 같은 모양으로 저장한다.

한 표본 = 이미지 파일(들) + 같은 이름의 `.json`. 정답은 계약 스키마와 같은 필드를 쓰고,
원천에 없는 필드는 `meta.unlabeledFields` 에 적어 평가에서 "틀림"이 아니라 "판정 불가"로 뺀다.
라벨을 나중에 바꾸면 이전 측정이 전부 무효가 되므로 이 형식은 고정한다(필드 추가만 허용).

라벨 교정(`overrides.json`)
- 변환기 출력은 원천 그대로 두고(재현 가능), 사람이 이미지로 확인한 교정값은 라벨 폴더의
  `overrides.json` 에 따로 적는다. `iter_labels` 가 읽을 때 덧씌운다.
- 항목마다 `reason`(근거)이 필수다. 어떤 필드가 교정됐는지 `meta.extra.overrides` 에 남아
  채점기가 "라벨 오류율"로 보고한다.
- 정의 차이(예: KORIE `Total` = 할인 전 총합계)는 교정이 아니라 채점 규칙의 몫이다.
"""

from __future__ import annotations

import json
from collections.abc import Iterator
from pathlib import Path
from typing import Any

from pydantic import Field

from app.common.envelope import CamelModel
from app.ocr.schemas import CaptureType, EvidenceExtraction, EvidenceType, ProductSalesExtraction

IMAGE_SUFFIXES = {".jpg", ".jpeg", ".png", ".webp"}
MANIFEST_NAME = "manifest.json"
OVERRIDES_NAME = "overrides.json"
NON_LABEL_FILES = {MANIFEST_NAME, OVERRIDES_NAME}


class LabelMeta(CamelModel):
    source: str  # korie | korie-mobile-sim | synthetic | cord
    source_id: str
    split: str | None = None
    # 원천 데이터에 정답이 없는 필드. 평가에서 제외한다 (예: KORIE 는 사업자등록번호가 없다)
    unlabeled_fields: list[str] = Field(default_factory=list)
    # 이미지에 있지만 사람이 읽을 수 없는 필드(흐림·퇴색). 값은 null 로 두고 exact match 에서
    # 제외한다. 모델이 여기서 높은 신뢰도로 값을 내면 "과신" 으로 따로 센다.
    # ("없음"과 다르다 — 없는 필드는 null 이 정답이라 exact match 에 포함된다)
    unreadable_fields: list[str] = Field(default_factory=list)
    notes: list[str] = Field(default_factory=list)
    # 생성기·열화 파라미터 등 출처별 부가 정보 (평가 지표에는 쓰지 않는다)
    extra: dict[str, Any] = Field(default_factory=dict)


class LabelRecord(CamelModel):
    """증빙(P1-02) 라벨 — 이미지 1장."""

    image: str  # 라벨 파일과 같은 폴더에 있는 이미지 파일명
    evidence_type: EvidenceType = "RECEIPT"
    extraction: EvidenceExtraction
    meta: LabelMeta


class ProductSalesLabelRecord(CamelModel):
    """매출현황 인쇄물(P1-22) 라벨 — 스크롤·길이 때문에 여러 장일 수 있다(순서대로)."""

    images: list[str]
    capture_type: CaptureType
    extraction: ProductSalesExtraction
    meta: LabelMeta


class LabelOverride(CamelModel):
    """`overrides.json` 의 한 항목. `fields` 는 계약 스키마의 camelCase 키 → 교정값."""

    fields: dict[str, Any] = Field(default_factory=dict)
    reason: str
    verified: str | None = None  # image | receipt-text | ...
    unlabeled_fields: list[str] = Field(default_factory=list)  # 추가로 판정 불가 처리할 필드
    unreadable_fields: list[str] = Field(default_factory=list)
    # 원천에 없던 필드를 사람이 추가 라벨링했을 때 — unlabeledFields 에서 빼서 채점에 넣는다.
    # 값이 없는 영수증(인쇄 안 됨)은 fields 에 null 을 적고 여기 이름을 넣으면 null 이 정답이 된다.
    labeled_fields: list[str] = Field(default_factory=list)


def _dump(record: CamelModel) -> str:
    return json.dumps(record.model_dump(by_alias=True, mode="json"), ensure_ascii=False, indent=2)


def write_label(path: Path, record: LabelRecord | ProductSalesLabelRecord) -> None:
    path.write_text(_dump(record) + "\n", encoding="utf-8")


def read_label(path: Path) -> LabelRecord:
    return LabelRecord.model_validate_json(path.read_text(encoding="utf-8"))


def read_product_sales_label(path: Path) -> ProductSalesLabelRecord:
    return ProductSalesLabelRecord.model_validate_json(path.read_text(encoding="utf-8"))


def label_paths(directory: Path) -> list[Path]:
    """라벨 파일만 (manifest.json·overrides.json 제외), 정렬."""
    return sorted(p for p in directory.rglob("*.json") if p.name not in NON_LABEL_FILES)


def read_overrides(directory: Path) -> dict[str, LabelOverride]:
    """라벨 폴더의 `overrides.json` → sourceId 별 교정. 없으면 빈 dict."""
    path = directory / OVERRIDES_NAME
    if not path.is_file():
        return {}
    raw = json.loads(path.read_text(encoding="utf-8"))
    return {
        source_id: LabelOverride.model_validate(entry)
        for source_id, entry in raw.get("records", {}).items()
    }


def apply_override(label: LabelRecord, override: LabelOverride) -> LabelRecord:
    """교정값을 덧씌운 새 레코드. 원본은 바꾸지 않는다. 알 수 없는 필드는 오류."""
    record = label.model_copy(deep=True)
    data = record.extraction.model_dump(by_alias=True, mode="json")
    unknown = sorted(set(override.fields) - set(data))
    if unknown:
        raise ValueError(f"{label.meta.source_id}: overrides 에 알 수 없는 필드 {unknown}")
    data.update(override.fields)
    record.extraction = EvidenceExtraction.model_validate(data)
    record.meta.unlabeled_fields = sorted(
        (set(record.meta.unlabeled_fields) | set(override.unlabeled_fields))
        - set(override.labeled_fields)
    )
    record.meta.unreadable_fields = sorted(
        set(record.meta.unreadable_fields) | set(override.unreadable_fields)
    )
    record.meta.notes.append(f"override: {override.reason}")
    record.meta.extra["overrides"] = sorted(
        set(override.fields) | set(override.unlabeled_fields) | set(override.unreadable_fields)
    )
    if override.labeled_fields:
        record.meta.extra["labeledByOverride"] = sorted(override.labeled_fields)
    return record


def iter_labels(
    directory: Path, *, apply_overrides: bool = True
) -> Iterator[tuple[Path, LabelRecord]]:
    """디렉터리 아래 모든 증빙 라벨을 (경로, 레코드) 로 돈다. 이미지는 라벨과 같은 폴더에 있다.

    `apply_overrides=False` 면 원천 라벨 그대로 — 라벨 오류율을 잴 때 쓴다.
    """
    overrides = read_overrides(directory) if apply_overrides else {}
    for path in label_paths(directory):
        label = read_label(path)
        override = overrides.get(label.meta.source_id)
        yield path, (apply_override(label, override) if override else label)


def iter_product_sales_labels(directory: Path) -> Iterator[tuple[Path, ProductSalesLabelRecord]]:
    for path in label_paths(directory):
        yield path, read_product_sales_label(path)


def read_manifest(directory: Path) -> dict[str, Any] | None:
    path = directory / MANIFEST_NAME
    if not path.is_file():
        return None
    return json.loads(path.read_text(encoding="utf-8"))


def write_manifest(directory: Path, manifest: dict[str, Any]) -> None:
    directory.mkdir(parents=True, exist_ok=True)
    (directory / MANIFEST_NAME).write_text(
        json.dumps(manifest, ensure_ascii=False, indent=2) + "\n", encoding="utf-8"
    )
