"""평가 세트 라벨 규격 — 사람이 손으로 채우는 파일이라 검증이 곧 품질이다."""

from pathlib import Path

import pytest
from pydantic import ValidationError

from evals import labels
from evals.sample import attach_cycle_hints, parse_amount, read_csv, stratified

RAW = {
    "transactionId": 1,
    "description": "김영자",
    "direction": "WITHDRAWAL",
    "amount": 1200000,
    "occurredAt": "2026-08-25T01:00:00Z",
    "cycleHint": "매월 25일 반복 (최근 4회)",
    "industryType": "FOOD_CAFE",
}

CSV = """거래일시,적요,입금액,출금액,거래후잔액
2026-08-25 01:00:00,김영자,,"1,200,000",3800000
2026-08-25 10:00:00,우아한형제들,748000,,4548000
2026-08-26 09:00:00,스타벅스,,4500,4543500
2026-08-27 09:00:00,스타벅스,,5000,4538500
"""


def _record(**overrides) -> labels.LabelRecord:
    return labels.LabelRecord.model_validate({**RAW, **overrides})


# ---------------------------------------------------------------- 라벨 레코드


def test_unlabeled_record_is_valid_but_not_labeled():
    """표본을 뽑은 직후 상태 — 세 칸이 비어 있어도 파일은 읽혀야 한다."""
    record = _record()
    assert record.labeled is False
    assert record.category is None


def test_labeled_record():
    record = _record(category="RENT", merchantName="김영자(임대인 추정)")
    assert record.labeled is True


def test_direction_mismatch_is_rejected():
    """출금 거래에 입금 카테고리. 사람이 300건 타이핑하면 반드시 나오는 실수다."""
    with pytest.raises(ValidationError) as exc:
        _record(category="PLATFORM_SETTLEMENT")
    assert "DEPOSIT 카테고리" in str(exc.value)


def test_unknown_category_is_rejected():
    with pytest.raises(ValidationError):
        _record(category="NOT_A_CATEGORY")


# ---------------------------------------------------------------- 파일 입출력


def test_load_reports_line_number_on_bad_line(tmp_path: Path):
    """어느 줄이 틀렸는지 알려주지 않으면 300줄에서 찾을 수 없다."""
    path = tmp_path / "labels.jsonl"
    good = _record().model_dump_json(by_alias=True)
    path.write_text(good + "\n" + '{"transactionId": 2}\n', encoding="utf-8")

    with pytest.raises(ValueError) as exc:
        labels.load(path)
    assert "labels.jsonl:2" in str(exc.value)


def test_dump_sorts_by_transaction_id(tmp_path: Path):
    """diff가 안정적이어야 라벨 수정 이력이 리뷰에서 읽힌다."""
    path = tmp_path / "labels.jsonl"
    labels.dump([_record(transactionId=3), _record(transactionId=1)], path)
    ids = [r.transaction_id for r in labels.load(path)]
    assert ids == [1, 3]


def test_roundtrip_preserves_labels(tmp_path: Path):
    path = tmp_path / "labels.jsonl"
    labels.dump([_record(category="RENT", note="반복 출금이라 임차료로 봤다")], path)
    loaded = labels.load(path)[0]
    assert loaded.category == "RENT"
    assert loaded.note == "반복 출금이라 임차료로 봤다"


# ---------------------------------------------------------------- 리포트


def test_source_defaults_and_is_reported():
    """합성 라벨과 실제 라벨이 섞이면 구분할 방법이 있어야 한다.

    없으면 나중에 "0.87" 이 무엇을 잰 점수인지 알 수 없어진다.
    """
    assert _record().source == "unknown"

    rep = labels.report(
        [
            _record(transactionId=1, source="synthetic-20260908"),
            _record(transactionId=2, source="synthetic-20260908"),
            _record(transactionId=3, source="bank-csv"),
        ]
    )
    assert rep.by_source == {"synthetic-20260908": 2, "bank-csv": 1}


def test_report_counts_progress_and_duplicates():
    records = [
        _record(transactionId=1, category="RENT"),
        _record(transactionId=2),
        _record(transactionId=2),
    ]
    rep = labels.report(records)
    assert rep.total == 3
    assert rep.labeled == 1
    assert rep.unlabeled_ids == [2, 2]
    assert rep.duplicate_ids == [2]
    assert rep.by_category == {"RENT": 1}


# ---------------------------------------------------------------- CSV 읽기


def test_parse_amount_strips_separators_and_currency():
    assert parse_amount("1,200,000") == 1200000
    assert parse_amount("  4500원 ") == 4500
    assert parse_amount("₩3,000") == 3000
    assert parse_amount("") == 0


def test_read_csv_infers_direction_from_amount_columns(tmp_path: Path):
    path = tmp_path / "bank.csv"
    path.write_text(CSV, encoding="utf-8")

    rows = read_csv(path)
    assert len(rows) == 4
    assert rows[0].direction == "WITHDRAWAL" and rows[0].amount == 1200000
    assert rows[1].direction == "DEPOSIT" and rows[1].amount == 748000


def test_read_csv_accepts_a_plain_string_path(tmp_path: Path):
    """인계 문서(evals/p1-01/거래_CSV_생성_규격.md) 10장의 검증 스니펫이 문자열로 부른다.

    거기서 Path 를 import 하게 만들 이유가 없다. 이 테스트가 깨지면 그 문서의
    명령이 AttributeError 로 죽는다 — 받는 사람이 규격을 의심하게 된다.
    """
    path = tmp_path / "bank.csv"
    path.write_text(CSV, encoding="utf-8")

    assert len(read_csv(str(path))) == 4


def test_read_csv_reads_bom_prefixed_file(tmp_path: Path):
    """Excel 로 열리게 하려고 시드 CSV 에 BOM 을 붙였다 (datasets/gen_seed.py).

    한국어 Windows 의 Excel 은 .csv 를 cp949 로 열어서 BOM 이 없으면 한글이 깨진다.
    BOM 을 벗기지 않으면 첫 헤더가 "﻿거래일시" 가 되어 헤더 검사에서 걸린다 —
    즉 이 테스트가 깨지는 순간 시드 CSV 를 아예 못 읽는다.
    """
    path = tmp_path / "bank.csv"
    path.write_text(CSV, encoding="utf-8-sig")

    rows = read_csv(path)
    assert len(rows) == 4
    assert rows[0].description == "김영자"


def test_read_csv_rejects_wrong_header(tmp_path: Path):
    path = tmp_path / "bad.csv"
    path.write_text("날짜,내용\n2026-08-25,커피\n", encoding="utf-8")
    with pytest.raises(ValueError, match="헤더가 규격과 다릅니다"):
        read_csv(path)


# ---------------------------------------------------------------- 층화 추출


def test_stratified_is_reproducible_for_same_seed(tmp_path: Path):
    path = tmp_path / "bank.csv"
    path.write_text(CSV, encoding="utf-8")
    rows = read_csv(path)

    first = [r.description for r in stratified(rows, 3, seed=1)]
    second = [r.description for r in stratified(rows, 3, seed=1)]
    assert first == second


def test_stratified_covers_both_directions(tmp_path: Path):
    """입금 7종 / 출금 16종으로 카테고리 체계가 갈린다. 한쪽만 뽑으면 절반을 못 본다."""
    path = tmp_path / "bank.csv"
    path.write_text(CSV, encoding="utf-8")
    rows = read_csv(path)

    picked = stratified(rows, 2, seed=1)
    assert {r.direction for r in picked} == {"DEPOSIT", "WITHDRAWAL"}


# ---------------------------------------------------------------- 반복 주기


def _rows_from(csv_text: str, tmp_path: Path) -> list:
    path = tmp_path / "bank.csv"
    path.write_text(csv_text, encoding="utf-8")
    return read_csv(path)


def test_monthly_cycle_is_detected(tmp_path: Path):
    """`김영자 120만원 출금` 한 줄로는 임차료인지 알 수 없다. 주기가 판단의 핵심이다."""
    text = "거래일시,적요,입금액,출금액\n" + "".join(
        f"2026-0{m}-25 09:00:00,김영자,,1200000\n" for m in (6, 7, 8)
    )
    rows = _rows_from(text, tmp_path)
    attach_cycle_hints(rows)
    assert rows[0].cycle_hint == "매월 25일 반복 (최근 3회)"


def test_weekly_cycle_is_detected(tmp_path: Path):
    """플랫폼 정산은 매주 같은 요일에 들어온다."""
    text = "거래일시,적요,입금액,출금액\n" + "".join(
        f"2026-08-{d:02d} 10:00:00,우아한형제들,748000,\n" for d in (4, 11, 18, 25)
    )
    rows = _rows_from(text, tmp_path)
    attach_cycle_hints(rows)
    assert rows[0].cycle_hint == "매주 화요일 반복 (최근 4회)"


def test_shifted_monthly_cycle_is_still_monthly(tmp_path: Path):
    """주말·공휴일에 밀리면 하루이틀 흔들린다. 그래도 월 주기다."""
    text = "거래일시,적요,입금액,출금액\n" + "".join(
        f"2026-0{m}-{d} 09:00:00,대성빌딩관리단,,180000\n" for m, d in ((6, 25), (7, 26), (8, 24))
    )
    rows = _rows_from(text, tmp_path)
    attach_cycle_hints(rows)
    assert rows[0].cycle_hint == "매월 24~26일 반복 (최근 3회)"


def test_same_description_different_amount_band_is_split(tmp_path: Path):
    """`구글` 이 구독료(2,900원 정기)와 광고비(35만원 부정기)로 함께 빠진다.

    금액을 무시하고 묶으면 "주기 불규칙" 이 되어 구독료의 월 주기가 사라진다.
    그러면 라벨러가 구독료로 판단할 근거를 잃는다.
    """
    text = (
        "거래일시,적요,입금액,출금액\n"
        + "".join(f"2026-0{m}-03 07:00:00,구글,,2900\n" for m in (5, 6, 7, 8))
        + "2026-07-19 14:00:00,구글,,355700\n"
    )

    rows = _rows_from(text, tmp_path)
    attach_cycle_hints(rows)

    subscription = [r for r in rows if r.amount == 2900]
    advertising = [r for r in rows if r.amount == 355700]
    assert subscription[0].cycle_hint == "매월 3일 반복 (최근 4회)"
    assert advertising[0].cycle_hint is None  # 1건뿐이라 반복이 아니다


def test_too_few_occurrences_gets_no_hint(tmp_path: Path):
    """2회는 반복이라 부를 수 없다. 없는 주기를 만들어 주면 라벨러를 오도한다."""
    text = (
        "거래일시,적요,입금액,출금액\n"
        "2026-06-25 09:00:00,한지민,50000,\n"
        "2026-07-25 09:00:00,한지민,50000,\n"
    )
    rows = _rows_from(text, tmp_path)
    assert attach_cycle_hints(rows) == 0
    assert rows[0].cycle_hint is None


def test_stratified_caps_repeated_description(tmp_path: Path):
    """같은 적요가 표본을 잡아먹으면 다양성이 죽는다."""
    path = tmp_path / "bank.csv"
    rows_text = "거래일시,적요,입금액,출금액\n" + "".join(
        f"2026-08-{day:02d} 09:00:00,스타벅스,,4500\n" for day in range(1, 21)
    )
    path.write_text(rows_text, encoding="utf-8")

    picked = stratified(read_csv(path), 20, seed=1)
    assert len(picked) <= 4  # MAX_PER_DESCRIPTION
