"""§6.4 검산 재실행 엔드포인트 — 모델을 부르지 않는 동기 경로 (확장 v1.17).

검토 화면에서 사용자가 값을 고친 뒤 검산이 해소됐는지 확인하는 용도다. 규칙을 화면·백엔드가
복제하지 않게 하려고 두었으므로, **검산 결과가 `validate_extraction` 과 같아야** 한다.
"""

from datetime import date

from app.ocr.schemas import EvidenceExtraction, ExtractedItem
from app.ocr.validators import validate_extraction
from tests.conftest import HEADERS

ENDPOINT = "/internal/v1/ai/evidence-validation"

# 금액 칸이 밀린 결과 — 합계 검산은 통과하고 세율 검산만 걸린다 (v1.14)
SHIFTED = {
    "supplierName": "가게",
    "issuedDate": "2026-02-28",
    "taxableAmount": 528_000,
    "taxFreeAmount": 22_050,
    "vatAmount": 220_500,
    "totalAmount": 770_550,
    "items": [{"rawName": "우유", "amount": 770_550}],
}


def test_validation_runs_the_same_rules_without_calling_a_model(client):
    response = client.post(ENDPOINT, json={"extraction": SHIFTED}, headers=HEADERS)
    assert response.status_code == 200
    data = response.json()["data"]

    assert data["amountsConsistent"] is True  # 덧셈은 순열을 못 본다
    assert data["vatRatioConsistent"] is False  # 세율이 잡는다
    assert data["needsReview"] is True
    assert data["reviewReasons"] == ["VAT_RATIO_INCONSISTENT"]

    # 워커 내부 함수와 결과가 같아야 한다 — 규칙이 한 곳에 있다는 것이 이 엔드포인트의 존재 이유다
    direct = validate_extraction(EvidenceExtraction.model_validate(SHIFTED))
    assert data["reviewReasons"] == direct.review_reasons


def test_user_fixing_the_values_clears_the_review_flag(client):
    """사용자가 밀린 칸을 바로잡으면 사유가 사라진다 — 화면이 이걸 즉시 보여줄 수 있다."""
    fixed = {**SHIFTED, "taxableAmount": 220_500, "taxFreeAmount": 528_000, "vatAmount": 22_050}
    data = client.post(ENDPOINT, json={"extraction": fixed}, headers=HEADERS).json()["data"]
    assert data["needsReview"] is False
    assert data["reviewReasons"] == []


def test_low_confidence_is_opt_in(client):
    body = {"extraction": {**SHIFTED, "fieldConfidence": {"issuedDate": 0.4}}}
    off = client.post(ENDPOINT, json=body, headers=HEADERS).json()["data"]
    assert "LOW_CONFIDENCE" not in off["reviewReasons"]

    on = client.post(ENDPOINT, json={**body, "lowConfidence": 0.7}, headers=HEADERS).json()["data"]
    assert "LOW_CONFIDENCE" in on["reviewReasons"]


def test_validation_requires_the_service_token(client):
    assert client.post(ENDPOINT, json={"extraction": SHIFTED}).status_code == 401


def test_items_alignment_is_reported_too(client):
    """v1.15 구조 검사도 같은 경로로 나온다."""
    shifted_rows = {
        **SHIFTED,
        "taxableAmount": 220_500,
        "taxFreeAmount": 528_000,
        "vatAmount": 22_050,
        "items": [
            {"rawName": "상품명", "amount": 500_000},
            {"rawName": "계란 30구", "amount": 270_550},
        ],
    }
    data = client.post(ENDPOINT, json={"extraction": shifted_rows}, headers=HEADERS).json()["data"]
    assert data["itemsAligned"] is False
    assert data["reviewReasons"] == ["ITEM_ROWS_MISALIGNED"]


def test_extracted_item_shape_is_accepted(client):
    """계약 스키마를 그대로 받는다 — 화면이 PATCH 로 보낼 본문을 그대로 검산할 수 있다."""
    extraction = EvidenceExtraction(
        supplier_name="가게",
        issued_date=date(2026, 8, 30),
        supply_amount=141_818,
        vat_amount=14_182,
        total_amount=156_000,
        items=[ExtractedItem(raw_name="원두", amount=156_000)],
    )
    body = {"extraction": extraction.model_dump(by_alias=True, mode="json")}
    data = client.post(ENDPOINT, json=body, headers=HEADERS).json()["data"]
    assert data["amountsConsistent"] is True and data["needsReview"] is False
