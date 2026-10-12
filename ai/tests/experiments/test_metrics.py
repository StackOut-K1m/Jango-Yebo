from datetime import date

from app.ocr.schemas import EvidenceExtraction, ExtractedItem
from experiments.labels import LabelMeta, LabelRecord
from experiments.metrics import (
    aggregate,
    match_items,
    score_receipt,
    supplier_names_match,
    total_matches,
    values_equal,
)


def _label(**overrides) -> LabelRecord:
    extraction = EvidenceExtraction(
        supplier_name="서울우유 광주대리점",
        supplier_business_no="124-81-00998",
        issued_date=date(2026, 8, 30),
        supply_amount=141_818,
        vat_amount=14_182,
        total_amount=156_000,
        items=[
            ExtractedItem(raw_name="서울우유 1L", quantity=40, amount=116_000),
            ExtractedItem(raw_name="바나나우유 240ml", quantity=25, amount=40_000),
        ],
    )
    meta = LabelMeta(source="test", source_id="r1", **overrides)
    return LabelRecord(image="r1.jpg", extraction=extraction, meta=meta)


def _prediction(**changes) -> EvidenceExtraction:
    base = _label().extraction.model_copy(deep=True)
    for key, value in changes.items():
        setattr(base, key, value)
    base.field_confidence = {"supplierName": 0.95, "totalAmount": 0.99, "issuedDate": 0.9}
    return base


def test_values_equal_rules():
    assert values_equal("supplierName", "서울우유 광주대리점", "서울우유광주대리점")
    assert values_equal("supplierBusinessNo", "124-81-00998", "1248100998")
    assert not values_equal("totalAmount", 156_000, 156_001)
    assert values_equal("vatAmount", None, None)
    assert not values_equal("vatAmount", None, 0)


def test_perfect_prediction_scores_all_correct():
    score = score_receipt(_label(), _prediction())
    assert not score.failed
    assert all(o.correct is True for o in score.fields.values())
    assert score.items is not None and len(score.items.matched) == 2
    assert score.items.hallucinated == [] and score.items.missed == []
    assert score.item_amount_correct == 2
    assert score.amount_reconciled is True and score.checksum_valid is True


def test_unlabeled_and_unreadable_fields_are_excluded():
    label = _label(unlabeled_fields=["supplierBusinessNo"], unreadable_fields=["issuedDate"])
    pred = _prediction(supplier_business_no="999-99-99999", issued_date=date(2020, 1, 1))
    score = score_receipt(label, pred, confidence_threshold=0.85)
    assert score.fields["supplierBusinessNo"].correct is None
    assert score.fields["issuedDate"].correct is None
    assert score.fields["issuedDate"].overconfident is True  # 읽을 수 없는데 0.9 로 값을 냄
    assert score.checksum_valid is False  # 체크섬은 별개로 본다


def test_hallucinated_and_missed_items():
    pred = _prediction(
        items=[
            ExtractedItem(raw_name="서울우유 1L", quantity=40, amount=116_000),
            ExtractedItem(raw_name="초코파이", quantity=1, amount=1_000),  # 라벨에 없음
        ]
    )
    score = score_receipt(_label(), pred)
    assert score.items is not None
    assert [i.raw_name for i in score.items.hallucinated] == ["초코파이"]
    assert [i.raw_name for i in score.items.missed] == ["바나나우유 240ml"]


def test_match_items_is_one_to_one_by_name_similarity():
    labeled = [ExtractedItem(raw_name="아메리카노"), ExtractedItem(raw_name="카페라떼")]
    predicted = [ExtractedItem(raw_name="아메리카노 (ICE)"), ExtractedItem(raw_name="카페 라떼")]
    result = match_items(labeled, predicted)
    assert len(result.matched) == 2 and not result.missed and not result.hallucinated


def test_aggregate_reports_rates_and_calibration():
    good = score_receipt(_label(), _prediction())
    bad = score_receipt(_label(), _prediction(total_amount=150_000))
    failed = score_receipt(_label(), None)
    metrics = aggregate([good, bad, failed])

    assert metrics["receipts"] == 3
    assert metrics["processingFailureRate"] == round(1 / 3, 4)
    total = metrics["fields"]["totalAmount"]
    assert (total["n"], total["correct"], total["accuracy"]) == (2, 1, 0.5)
    assert total["meanConfidenceCorrect"] == 0.99 and total["meanConfidenceWrong"] == 0.99
    assert metrics["items"]["f1"] == 1.0 and metrics["items"]["hallucinated"] == 0
    assert metrics["amountReconciliationRate"] == 0.5  # 150,000 은 검산 실패
    assert metrics["businessNoChecksumPassRate"] == 1.0


def test_incomplete_item_labels_skip_precision_and_hallucination():
    # KORIE 처럼 품목이 일부만 라벨된 데이터: 라벨에 없는 예측 행을 환각으로 세면 안 된다
    label = _label(extra={"itemsComplete": False})
    pred = _prediction(
        items=[
            ExtractedItem(raw_name="서울우유 1L", quantity=40, amount=116_000),
            ExtractedItem(raw_name="라벨에 없는 진짜 상품", quantity=1, amount=1_000),
        ]
    )
    score = score_receipt(label, pred)
    assert score.items_complete is False
    metrics = aggregate([score])
    assert metrics["items"]["recall"] == 0.5  # 라벨 2행 중 1행 찾음
    assert metrics["items"]["precision"] is None and metrics["items"]["f1"] is None
    assert metrics["items"]["hallucinated"] is None
    assert metrics["items"]["itemsCompleteReceipts"] == 0


def test_supplier_containment_and_brand_alias_rules():
    # 라벨이 로고·브랜드, 예측이 매장명 줄 — KORIE val 50장에서 상호명 불일치 9건 중 4건이 이 유형
    assert supplier_names_match("Homeplus 홈플러스", "홈플러스 청주성안점") == (True, "contains")
    assert supplier_names_match("Emart", "이마트 청주점") == (True, "contains")
    assert supplier_names_match("포시애를-청주", "포시애를-청주본점")[0] is True
    assert supplier_names_match("주) 한국마트", "주)한국마트")[0] is True
    # 다른 상호는 여전히 오답 (같은 지점명이라도 브랜드가 다르면)
    assert supplier_names_match("마이스", "(주)아성다이소_청주본점") == (False, None)
    assert supplier_names_match("이마트 청주점", "롯데마트 청주점")[0] is False
    assert values_equal("supplierName", "Emart", "이마트 청주점") is True


def test_total_matches_gross_when_discount_explains_the_gap():
    pred = _prediction(total_amount=44_350, discount_amount=44_350)
    assert total_matches(44_350, pred) == (True, None)
    assert total_matches(88_700, pred) == (True, "gross")  # 라벨 = 할인 전 총합계
    assert total_matches(90_000, pred) == (False, None)
    assert total_matches(88_700, _prediction(total_amount=44_350)) == (False, None)  # 할인 없음

    label = _label()
    label.extraction.total_amount = 200_000
    score = score_receipt(label, _prediction(total_amount=156_000, discount_amount=44_000))
    assert score.fields["totalAmount"].correct is True
    assert score.fields["totalAmount"].via == "gross"
    metrics = aggregate([score])
    assert metrics["fields"]["totalAmount"]["correctVia"] == {"gross": 1}


def test_label_overrides_and_name_exact_are_reported():
    label = _label(extra={"overrides": ["vatAmount"]})
    pred = _prediction(
        items=[
            ExtractedItem(raw_name="서울우유 1L", quantity=40, amount=116_000),
            # 정규화(normalize_name) 후 라벨과 같은 이름
            ExtractedItem(raw_name="바나나우유 240ML", quantity=25, amount=40_000),
        ]
    )
    score = score_receipt(label, pred)
    assert score.overridden_fields == ["vatAmount"]
    assert score.item_name_exact == 2 and score.needs_review is False
    metrics = aggregate([score, score_receipt(_label(), _prediction())])
    assert metrics["labelOverrides"] == {"receipts": 1, "fields": {"vatAmount": 1}}
    assert metrics["items"]["nameExactOnMatched"] == 1.0
    assert metrics["needsReviewRate"] == 0.0
    assert metrics["scoringRules"] == "scoring/v2"
