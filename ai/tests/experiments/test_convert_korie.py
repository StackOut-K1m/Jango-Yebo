import json
from datetime import date
from pathlib import Path

import pytest
from PIL import Image

from experiments.convert_korie import (
    FormatNotRecognized,
    convert,
    load_records,
    parse_amount,
    parse_date,
)
from experiments.labels import read_label


def _png(path: Path) -> None:
    Image.new("RGB", (60, 80), "white").save(path, format="PNG")


def _entity(label: str, text: str, y: int, x: int = 0, **extra: object) -> dict[str, object]:
    return {"label": label, "text": text, "bbox": [x, y, x + 50, y + 10], **extra}


# ---------------------------------------------------------------- 값 파싱


def test_parse_date_formats():
    assert parse_date("2023.05.04 14:22:31") == date(2023, 5, 4)
    assert parse_date("23-05-04") == date(2023, 5, 4)
    assert parse_date("2023년 5월 4일") == date(2023, 5, 4)
    assert parse_date("20230504") == date(2023, 5, 4)
    assert parse_date("영업일") is None
    assert parse_date("2023.13.40") is None


def test_parse_amount_formats():
    assert parse_amount("12,000원") == 12_000
    assert parse_amount("₩ 1,090") == 1_090
    assert parse_amount("-1,000") == -1_000
    assert parse_amount("합계") is None
    assert parse_amount(None) is None


# ---------------------------------------------------------------- 이미지별 엔티티 리스트 형식


def _make_entity_list_dataset(raw: Path) -> None:
    _png(raw / "IMG00001.png")
    entities = [
        _entity("MerchantName", "서울우유 광주대리점", 10),
        _entity("TransactionDate", "2023.05.04", 20),
        _entity("TransactionTime", "14:22", 20, x=60),
        _entity("Item", "서울우유 1L", 40),
        _entity("Quantity", "2", 40, x=60),
        _entity("Price", "2,900", 40, x=90),
        _entity("TotalPrice", "5,800", 40, x=120),
        _entity("Item", "바나나우유 240ml", 50),
        _entity("Quantity", "1", 50, x=60),
        _entity("TotalPrice", "1,600", 50, x=120),
        _entity("Subtotal", "6,727", 60),
        _entity("TotalTax", "673", 70),
        _entity("Total", "7,400", 80),
    ]
    (raw / "IMG00001.json").write_text(json.dumps(entities, ensure_ascii=False), encoding="utf-8")


def test_entity_list_per_image_is_converted(tmp_path):
    raw, out = tmp_path / "raw", tmp_path / "out"
    raw.mkdir()
    _make_entity_list_dataset(raw)

    stats = convert(raw, out, "test", map_subtotal=True)

    assert stats["변환"] == 1
    label = read_label(out / "test" / "korie-test-IMG00001.json")
    assert (out / "test" / label.image).exists() and label.image == "korie-test-IMG00001.png"
    x = label.extraction
    assert x.supplier_name == "서울우유 광주대리점"
    assert x.issued_date == date(2023, 5, 4)
    assert (x.supply_amount, x.vat_amount, x.total_amount) == (6_727, 673, 7_400)
    assert [i.raw_name for i in x.items] == ["서울우유 1L", "바나나우유 240ml"]
    assert (
        x.items[0].quantity == 2 and x.items[0].unit_price == 2_900 and x.items[0].amount == 5_800
    )
    assert x.items[1].amount == 1_600 and x.items[1].unit_price is None
    assert x.supplier_business_no is None
    assert "supplierBusinessNo" in label.meta.unlabeled_fields
    assert label.meta.source == "korie" and label.meta.split == "test"


def test_subtotal_is_not_mapped_by_default_and_manifest_records_it(tmp_path):
    raw, out = tmp_path / "raw", tmp_path / "out"
    raw.mkdir()
    _make_entity_list_dataset(raw)

    convert(raw, out, "val", source_commit="abc1234")

    label = read_label(out / "val" / "korie-val-IMG00001.json")
    # 등식(Subtotal + TotalTax == Total)이 맞아도 기본은 미매핑 — 변환 가정을 정답에 넣지 않는다
    assert label.extraction.supply_amount is None
    assert "supplyAmount" in label.meta.unlabeled_fields
    assert label.extraction.vat_amount == 673  # TotalTax → vatAmount 는 기본 매핑
    assert not any("Subtotal" in note for note in label.meta.notes)

    manifest = json.loads((out / "val" / "manifest.json").read_text(encoding="utf-8"))
    assert manifest["split"] == "val" and manifest["converted"] == 1
    assert manifest["sourceCommit"] == "abc1234"
    assert any("미매핑" in assumption for assumption in manifest["assumptions"])
    assert manifest["labelFiles"][0]["name"] == "IMG00001.json"
    assert len(manifest["labelFiles"][0]["sha256"]) == 64
    assert manifest["coverage"]["총액"] == 1


def test_subtotal_not_matching_total_is_left_unlabeled(tmp_path):
    raw, out = tmp_path / "raw", tmp_path / "out"
    raw.mkdir()
    _png(raw / "IMG00002.png")
    entities = [
        _entity("Total", "7,400", 80),
        _entity("TotalTax", "673", 70),
        _entity("Subtotal", "7,400", 60),  # 할인 전 합계처럼 보이는 값 — 공급가로 쓰면 안 된다
    ]
    (raw / "IMG00002.json").write_text(json.dumps(entities), encoding="utf-8")

    convert(raw, out, "val", map_subtotal=True)
    label = read_label(out / "val" / "korie-val-IMG00002.json")
    assert label.extraction.supply_amount is None
    assert "supplyAmount" in label.meta.unlabeled_fields
    assert any("Subtotal" in note for note in label.meta.notes)


# ---------------------------------------------------------------- 키-값 / 인덱스 형식


def test_keyvalue_index_file_with_item_rows(tmp_path):
    raw, out = tmp_path / "raw", tmp_path / "out"
    (raw / "images").mkdir(parents=True)
    _png(raw / "images" / "r1.jpg")
    index = [
        {
            "image": "images/r1.jpg",
            "MerchantName": "카페 잔고",
            "TransactionDate": "2026-08-30",
            "Total": "4,500",
            "TotalTax": "409",
            "Subtotal": "4,091",
            "Items": [
                {"Item": "아메리카노", "Quantity": "1", "TotalPrice": "4,500"},
            ],
        }
    ]
    (raw / "ie_test.json").write_text(json.dumps(index, ensure_ascii=False), encoding="utf-8")

    detected, records = load_records(raw)
    assert "인덱스" in detected and len(records) == 1

    stats = convert(raw, out, "test", map_subtotal=True)
    assert stats["변환"] == 1
    label = read_label(out / "test" / "korie-test-r1.json")
    assert label.extraction.supplier_name == "카페 잔고"
    assert label.extraction.supply_amount == 4_091
    assert label.extraction.items[0].raw_name == "아메리카노"


def test_unrecognized_format_reports_keys(tmp_path):
    raw = tmp_path / "raw"
    raw.mkdir()
    (raw / "weird.json").write_text(json.dumps({"foo": 1, "bar": [1, 2]}), encoding="utf-8")
    with pytest.raises(FormatNotRecognized) as excinfo:
        load_records(raw)
    assert "foo" in str(excinfo.value)


def test_missing_label_files_is_reported(tmp_path):
    with pytest.raises(FormatNotRecognized):
        load_records(tmp_path)


# ------------------------------------------------ CSV: 행 = 품목 한 줄 (items_test.csv 추정형)


def test_csv_item_rows_with_repeated_receipt_fields(tmp_path):
    raw, out = tmp_path / "raw", tmp_path / "out"
    (raw / "images").mkdir(parents=True)
    _png(raw / "images" / "IMG00632.png")
    csv_text = "\n".join(
        [
            "image_id,MerchantName,TransactionDate,Item,Quantity,Price,TotalPrice,Subtotal,TotalTax,Total",
            'IMG00632,카페 잔고,2023.05.04,아메리카노,2,"2,000","4,000","6,364",636,"7,000"',
            'IMG00632,카페 잔고,2023.05.04,라떼,1,"3,000","3,000","6,364",636,"7,000"',
        ]
    )
    (raw / "items_test.csv").write_text(csv_text, encoding="utf-8")

    detected, records = load_records(raw)
    assert "items_test.csv" in detected and len(records) == 1

    stats = convert(raw, out, "test", map_subtotal=True)
    assert stats["변환"] == 1
    label = read_label(out / "test" / "korie-test-IMG00632.json")
    x = label.extraction
    assert x.supplier_name == "카페 잔고" and x.issued_date == date(2023, 5, 4)
    assert (x.total_amount, x.vat_amount, x.supply_amount) == (7_000, 636, 6_364)
    assert [i.raw_name for i in x.items] == ["아메리카노", "라떼"]
    assert x.items[0].quantity == 2 and x.items[0].amount == 4_000
    # 반복된 영수증 필드는 같은 값이라 경고 없음. Subtotal 매핑 노트만 남는다
    assert all("Subtotal" in note for note in label.meta.notes)


def test_split_filter_picks_matching_label_files(tmp_path):
    raw = tmp_path / "raw"
    raw.mkdir()
    _png(raw / "A.png")
    _png(raw / "B.png")
    header = "image_id,MerchantName,Total"
    (raw / "items_val.csv").write_text(f"{header}\nA,가게A,1000", encoding="utf-8")
    (raw / "items_test.csv").write_text(f"{header}\nB,가게B,2000", encoding="utf-8")

    _, all_records = load_records(raw)
    _, val_records = load_records(raw, split="val")
    _, test_records = load_records(raw, split="test")

    assert {r.source_id for r in all_records} == {"A", "B"}
    assert [r.source_id for r in val_records] == ["A"]
    assert [r.source_id for r in test_records] == ["B"]


# ------------------------------------------------ 실제 items_val.csv 형식 (2026-09 다운로드 기준)

REAL_HEADER = (
    "image_name,Item_Name,class,Brand,Weight,Number of units,Size of units,"
    "Price,Total Price,Pack,Unit"
)
REAL_ROWS = [
    'IMG00477,"005 청피망 1봉, 23000476, 4,990, 1, 4,990 #",,,,1,,4990.0,4990.0,,',
    'IMG00432,"[1030480], 힙업스트레칭밴드(중), 3,000, 1, [1043158], 3,000",,,,1,,3000.0,3000.0,,',
    'IMG00002,"노브랜드 생감자칩 케, 1,980, 1, 1,980",,,,1,,1980.0,1980.0,,',
    'IMG00002,"001 토스트샌드위치, 8801068912821, 3,600, 1, 3,600",,,,1,,3600.0,3600.0,,',
]


def test_clean_item_name_keeps_only_the_product_name():
    from experiments.convert_korie import clean_item_name

    assert clean_item_name("005 청피망 1봉, 23000476, 4,990, 1, 4,990 #") == "청피망 1봉"
    assert clean_item_name("[1030480], 힙업스트레칭밴드(중), 3,000, 1, [1043158], 3,000") == (
        "힙업스트레칭밴드(중)"
    )
    assert clean_item_name("노브랜드 생감자칩 케, 1,980, 1, 1,980") == "노브랜드 생감자칩 케"
    assert clean_item_name("001 토스트샌드위치, 8801068912821, 3,600, 1, 3,600") == "토스트샌드위치"
    assert clean_item_name("서울우유 1L") == "서울우유 1L"  # 이미 깨끗한 이름은 그대로


def test_real_items_csv_yields_item_only_labels(tmp_path):
    raw, out = tmp_path / "raw", tmp_path / "out"
    raw.mkdir()
    for stem in ("IMG00477", "IMG00432", "IMG00002"):
        _png(raw / f"{stem}.jpg")
    (raw / "items_val.csv").write_text("\n".join([REAL_HEADER, *REAL_ROWS]), encoding="utf-8")

    detected, records = load_records(raw, split="val")
    assert len(records) == 3

    stats = convert(raw, out, "val")
    assert stats["변환"] == 3
    label = read_label(out / "val" / "korie-val-IMG00002.json")
    x = label.extraction
    assert [i.raw_name for i in x.items] == ["노브랜드 생감자칩 케", "토스트샌드위치"]
    assert (
        x.items[1].quantity == 1 and x.items[1].unit_price == 3_600 and x.items[1].amount == 3_600
    )
    # 영수증 단위 필드는 원천에 없다 → 값은 null 이고 평가에서 "판정 불가"로 빠진다
    assert x.total_amount is None and x.issued_date is None and x.supplier_name is None
    for field in ("supplierName", "issuedDate", "totalAmount", "vatAmount", "supplyAmount"):
        assert field in label.meta.unlabeled_fields
    assert "items" not in label.meta.unlabeled_fields  # 품목은 정답이 있다


# ------------------------------------------------ OCR 전사 트랙 병합 (<IMG>_<Entity>.txt)


def test_ocr_transcriptions_fill_receipt_level_fields(tmp_path):
    raw, out = tmp_path / "raw", tmp_path / "out"
    (raw / "det" / "images").mkdir(parents=True)
    (raw / "ocr" / "val").mkdir(parents=True)
    _png(raw / "det" / "images" / "IMG00001.png")
    _png(raw / "det" / "images" / "IMG00002.png")
    (raw / "items_val.csv").write_text(
        "\n".join(
            [
                REAL_HEADER,
                'IMG00001,"001 샌드위치, 8801068912821, 3,600, 1, 3,600",,,,1,,3600.0,3600.0,,',
                'IMG00002,"우유, 1,980, 1, 1,980",,,,1,,1980.0,1980.0,,',
            ]
        ),
        encoding="utf-8",
    )
    ocr = raw / "ocr" / "val"
    (ocr / "IMG00001_Total.txt").write_text("7,400", encoding="utf-8")
    (ocr / "IMG00001_TotalTax.txt").write_text("673원", encoding="utf-8")
    (ocr / "IMG00001_TransactionDate.txt").write_text("23-10-24", encoding="utf-8")
    (ocr / "IMG00001_MerchantName.txt").write_text("주)한국마트", encoding="utf-8")
    (ocr / "IMG00001_Item_barcode.txt").write_text("8801068912821", encoding="utf-8")  # 무시
    (ocr / "IMG00001_Sub_Total_after_discount.txt").write_text("6,000", encoding="utf-8")
    (ocr / "IMG00002_PaymentDate.txt").write_text("2023. 10.23", encoding="utf-8")
    (raw / "det" / "IMG00001.txt").write_text("15 0.2 0.4 0.1 0.02\n", encoding="utf-8")  # YOLO

    detected, records = load_records(raw, split="val")
    assert "OCR 전사 보강 2건" in detected

    convert(raw, out, "val")
    first = read_label(out / "val" / "korie-val-IMG00001.json")
    assert first.extraction.total_amount == 7_400 and first.extraction.vat_amount == 673
    assert first.extraction.issued_date == date(2023, 10, 24)
    assert first.extraction.supplier_name == "주)한국마트"
    assert [i.raw_name for i in first.extraction.items] == ["샌드위치"]  # 품목은 CSV 에서만
    assert first.meta.extra["itemsComplete"] is False  # KORIE 품목 라벨은 부분 라벨
    for field in ("supplierName", "issuedDate", "totalAmount", "vatAmount"):
        assert field not in first.meta.unlabeled_fields
    assert any(note.startswith("OCR 전사 보강") for note in first.meta.notes)

    second = read_label(out / "val" / "korie-val-IMG00002.json")
    assert second.extraction.issued_date == date(2023, 10, 23)  # PaymentDate 대체
    assert any("PaymentDate" in note for note in second.meta.notes)
    assert "totalAmount" in second.meta.unlabeled_fields  # 합계 전사가 없는 영수증
