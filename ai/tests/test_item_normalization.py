from app.ocr.normalization import normalize_name
from tests.conftest import HEADERS

NORMALIZE = "/internal/v1/ai/item-normalization"
KNOWN = [
    {"itemId": 601, "canonicalName": "우유 1L", "aliases": ["서울우유 1L"]},
    {"itemId": 602, "canonicalName": "A4 용지", "aliases": []},
]


def test_normalize_name_unit_rules():
    assert normalize_name("우유 1,000ml") == "우유 1L"
    assert normalize_name("설탕 1000g") == "설탕 1kg"
    assert normalize_name("콜라 500ML") == "콜라 500ml"
    assert normalize_name("계란 30EA") == "계란 30개"
    assert normalize_name("  삼겹살(냉장)  ") == "삼겹살 냉장"


def test_rule_and_alias_matches(client):
    payload = {
        "businessId": 11,
        "knownItems": KNOWN,
        "rawNames": ["우유 1000ml", "서울우유 1L", "대파 한 단"],
    }
    response = client.post(NORMALIZE, json=payload, headers=HEADERS)
    assert response.status_code == 200
    mappings = response.json()["data"]["mappings"]

    assert mappings[0]["matchedItemId"] == 601 and mappings[0]["confidence"] == 1.0
    assert mappings[1]["matchedItemId"] == 601 and mappings[1]["confidence"] == 1.0
    assert mappings[0]["matchedBy"] == "RULE" and mappings[1]["matchedBy"] == "RULE"

    unmatched = mappings[2]
    assert unmatched["matchedItemId"] is None
    assert unmatched["confidence"] is None
    assert unmatched["suggestedCanonicalName"] == "대파 한 단"


def test_similar_but_different_item_is_not_auto_matched(client):
    # 오매칭이 매칭 실패보다 나쁘다 — 한 글자 차이(두유/우유)는 확정하지 않는다
    payload = {"businessId": 11, "knownItems": KNOWN, "rawNames": ["두유 1L"]}
    mapping = client.post(NORMALIZE, json=payload, headers=HEADERS).json()["data"]["mappings"][0]
    assert mapping["matchedItemId"] is None
