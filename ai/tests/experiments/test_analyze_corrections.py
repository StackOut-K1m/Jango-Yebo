"""사용자 수정 이력 분석기 — 기록 형태가 지켜지지 않으면 데이터가 쌓여도 못 쓴다.

이 테스트는 분석기 계산뿐 아니라 **기록 설계의 요구**를 고정한다. 특히 "수정이 없어도 한 줄을
남겨야 한다" 는 조건이 깨지면 정확도가 0% 로 나온다는 것을 보인다.
"""

from experiments.analyze_corrections import Summary, parse_review

CLEAN = {
    "extractionId": 1,
    "modelName": "claude-sonnet-5",
    "promptVersion": "evidence-extraction/v4",
    "extractionMode": "STANDARD",
    "validation": {"needsReview": False, "reviewReasons": []},
    "fieldConfidence": {"totalAmount": 0.99, "issuedDate": 0.97},
    "corrections": [],
}
FIXED_TOTAL = {
    **CLEAN,
    "extractionId": 2,
    "validation": {"needsReview": True, "reviewReasons": ["AMOUNTS_INCONSISTENT"]},
    "fieldConfidence": {"totalAmount": 0.62, "issuedDate": 0.97},
    "corrections": [{"field": "totalAmount", "before": "156000", "after": "155000"}],
}
FIXED_ROWS = {
    **CLEAN,
    "extractionId": 3,
    "corrections": [
        {"field": "items[0].amount", "before": "1", "after": "2"},
        {"field": "items[2].rawName", "before": "a", "after": "b"},
    ],
}


def _summary(*rows) -> Summary:
    return Summary([parse_review(r) for r in rows])


def test_item_corrections_collapse_to_one_key():
    """품목은 행 단위 수정이 많다 — 필드별로 쪼개면 분모가 흔들린다."""
    review = parse_review(FIXED_ROWS)
    assert review.corrected == {"items"}
    assert review.any_correction is True


def test_field_accuracy_uses_reviews_as_the_denominator():
    s = _summary(CLEAN, CLEAN, FIXED_TOTAL, CLEAN)
    accuracy = s.field_accuracy()
    assert accuracy["totalAmount"] == (4, 0.75)  # 4건 중 1건 수정
    assert accuracy["issuedDate"] == (4, 1.0)


def test_fields_the_model_never_produced_are_not_counted_wrong():
    """면세 줄이 없는 영수증의 taxFreeAmount 를 오답으로 세면 안 된다."""
    assert "taxFreeAmount" not in _summary(CLEAN, CLEAN).field_accuracy()


def test_gate_precision_and_recall_against_real_corrections():
    s = _summary(CLEAN, CLEAN, FIXED_TOTAL, FIXED_ROWS)
    gate = s.gate()
    assert gate["reviews"] == 4
    assert gate["flagRate"] == 0.25  # needsReview 는 FIXED_TOTAL 하나
    assert gate["correctionRate"] == 0.5  # 실제 수정은 둘 — 무작위 정밀도 기준선
    assert gate["precision"] == 1.0  # 지목한 1건이 실제로 수정됐다
    assert gate["recall"] == 0.5  # 수정된 2건 중 1건만 지목


def test_confidence_split_separates_corrected_fields():
    conf = _summary(CLEAN, FIXED_TOTAL).confidence()
    assert conf["correctedN"] == 1 and conf["meanCorrected"] == 0.62
    assert conf["keptN"] == 3 and conf["meanKept"] > 0.9


def test_logging_only_corrections_makes_accuracy_meaningless():
    """기록 설계의 핵심 요구 — 수정 없는 확인도 남겨야 한다.

    고친 것만 모으면 모든 필드가 100% 수정된 것처럼 보인다. 이 테스트가 깨지면
    백엔드 구현이 분모를 빼먹은 것이다.
    """
    only_wrong = _summary(FIXED_TOTAL)
    assert only_wrong.field_accuracy()["totalAmount"] == (1, 0.0)
    assert only_wrong.gate()["correctionRate"] == 1.0

    with_clean = _summary(CLEAN, CLEAN, CLEAN, FIXED_TOTAL)
    assert with_clean.field_accuracy()["totalAmount"] == (4, 0.75)


def test_grouping_compares_prompt_versions_and_retry_mode():
    thorough = {**CLEAN, "extractionId": 4, "extractionMode": "THOROUGH"}
    v5 = {**FIXED_TOTAL, "extractionId": 5, "promptVersion": "evidence-extraction/v5"}
    s = _summary(CLEAN, FIXED_TOTAL, thorough, v5)
    assert s.by("extraction_mode")["THOROUGH"]["correctionRate"] == 0.0
    assert s.by("prompt_version")["evidence-extraction/v5"]["correctionRate"] == 1.0


def test_missing_optional_keys_do_not_crash():
    minimal = {"extractionId": 9, "corrections": []}
    review = parse_review(minimal)
    assert review.needs_review is False and review.extraction_mode == "STANDARD"
    assert _summary(minimal).gate()["reviews"] == 1
