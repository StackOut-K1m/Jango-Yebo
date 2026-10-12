"""명세 20.3 거래 분류 계약 — 스키마 파싱과 응답 검증.

`REQUEST`/`CALLBACK_RESULT`는 `docs/api/20_내부_AI워커.md` 20.3의 JSON 예시를 그대로 옮긴 것이다.
문서가 바뀌면 이 상수도 바뀌어야 한다 — 그게 이 테스트의 목적이다.
"""

from typing import get_args

import pytest
from pydantic import ValidationError

from app.nlp.schemas import (
    CATEGORY_DIRECTION,
    REQUEST_MAX,
    ClassificationResult,
    TransactionCategory,
    TransactionClassificationRequest,
    TransactionClassificationResult,
    invalid_results,
    missing_transaction_ids,
)

# 명세 20.3 요청 예시 (원문 그대로)
REQUEST = {
    "jobId": 9012,
    "business": {"industryType": "FOOD_CAFE"},
    "allowedCategories": ["RENT", "PAYROLL", "MATERIALS", "PLATFORM_SETTLEMENT"],
    "fewShotExamples": [
        {
            "description": "우아한형제들",
            "direction": "DEPOSIT",
            "category": "PLATFORM_SETTLEMENT",
            "merchantName": "배달의민족",
        }
    ],
    "transactions": [
        {
            "transactionId": 88031,
            "description": "김영자",
            "direction": "WITHDRAWAL",
            "amount": 1200000,
            "occurredAt": "2026-08-25T01:00:00Z",
            "cycleHint": "매월 25일 반복 (최근 4회)",
        }
    ],
}

# 명세 20.3 Callback 결과 예시 (원문 그대로. status·ModelMeta 는 completed() 가 붙인다)
CALLBACK_RESULT = {
    "results": [
        {
            "transactionId": 88031,
            "category": "RENT",
            "merchantName": "김영자(임대인 추정)",
            "confidence": 0.72,
            "reason": "매월 25일 동일 금액 반복 출금은 임차료 패턴과 일치합니다.",
        }
    ]
}


def _request(**overrides) -> TransactionClassificationRequest:
    return TransactionClassificationRequest.model_validate({**REQUEST, **overrides})


def _result(**overrides) -> ClassificationResult:
    return ClassificationResult.model_validate({**CALLBACK_RESULT["results"][0], **overrides})


# ---------------------------------------------------------------- 파싱 (완료 기준)


def test_spec_request_example_parses():
    request = _request()
    assert request.job_id == 9012
    assert request.business.industry_type == "FOOD_CAFE"
    assert request.few_shot_examples[0].merchant_name == "배달의민족"

    tx = request.transactions[0]
    assert tx.transaction_id == 88031
    assert tx.direction == "WITHDRAWAL"
    assert tx.amount == 1200000
    assert tx.cycle_hint == "매월 25일 반복 (최근 4회)"
    assert tx.occurred_at.year == 2026 and tx.occurred_at.month == 8


def test_spec_callback_example_parses():
    result = TransactionClassificationResult.model_validate(CALLBACK_RESULT)
    assert len(result.results) == 1
    assert result.results[0].category == "RENT"
    assert result.results[0].confidence == 0.72


def test_json_roundtrip_stays_camel_case():
    """API 서버가 camelCase 로 읽는다 — snake_case 가 새어 나가면 계약이 깨진다."""
    dumped = TransactionClassificationResult.model_validate(CALLBACK_RESULT).model_dump(
        by_alias=True, mode="json"
    )
    assert dumped == CALLBACK_RESULT


def test_optional_fields_may_be_absent():
    """`cycleHint`(반복 미탐지)와 `merchantName`(거래처 추정 실패)은 없을 수 있다."""
    minimal = {
        **REQUEST,
        "fewShotExamples": [],
        "transactions": [
            {
                "transactionId": 1,
                "description": "",
                "direction": "DEPOSIT",
                "amount": 0,
                "occurredAt": "2026-08-25T01:00:00Z",
            }
        ],
    }
    request = TransactionClassificationRequest.model_validate(minimal)
    assert request.transactions[0].cycle_hint is None
    assert request.few_shot_examples == []


# ---------------------------------------------------------------- 카테고리 표


def test_category_table_covers_all_23():
    """API 8.1 의 23종. ERD 가 "API 8.1의 23종"으로 못박고 있다."""
    categories = set(get_args(TransactionCategory))
    assert len(categories) == 23
    assert categories == set(CATEGORY_DIRECTION)
    assert sum(1 for d in CATEGORY_DIRECTION.values() if d == "DEPOSIT") == 7
    assert sum(1 for d in CATEGORY_DIRECTION.values() if d == "WITHDRAWAL") == 16


# ---------------------------------------------------------------- 거절 (Pydantic 층)


def test_unknown_category_is_rejected():
    with pytest.raises(ValidationError):
        _result(category="NOT_A_CATEGORY")


def test_confidence_out_of_range_is_rejected():
    for bad in (-0.1, 1.1):
        with pytest.raises(ValidationError):
            _result(confidence=bad)


def test_batch_over_limit_is_rejected():
    """§6.3 배치 20건. max_tokens 가 20건 기준이라 넘으면 출력이 조용히 잘린다."""
    one = REQUEST["transactions"][0]
    over = [{**one, "transactionId": 1000 + i} for i in range(REQUEST_MAX + 1)]
    with pytest.raises(ValidationError):
        _request(transactions=over)


def test_empty_batch_is_rejected():
    with pytest.raises(ValidationError):
        _request(transactions=[])


# ---------------------------------------------------------------- 폐기 (요청 대조 층)


def test_valid_result_is_not_dropped():
    assert invalid_results(_request(), [_result()]) == {}


def test_category_outside_allowed_is_dropped():
    """명세 20.1 — 허용 카테고리 밖 값은 폐기한다. TAX 는 23종에 있지만 이 요청엔 없다."""
    dropped = invalid_results(_request(), [_result(category="TAX")])
    assert 88031 in dropped
    assert "allowedCategories" in dropped[88031]


def test_direction_mismatch_is_dropped():
    """출금 거래에 입금 카테고리를 붙이면 신뢰도와 무관하게 틀렸다.

    `PLATFORM_SETTLEMENT`(입금)은 allowedCategories 안에 있지만 88031 은 WITHDRAWAL 이다.
    """
    dropped = invalid_results(_request(), [_result(category="PLATFORM_SETTLEMENT")])
    assert "DEPOSIT 카테고리" in dropped[88031]


def test_unknown_transaction_id_is_dropped():
    dropped = invalid_results(_request(), [_result(transactionId=99999)])
    assert dropped[99999] == "요청에 없는 transactionId입니다."


def test_duplicate_transaction_id_is_dropped():
    """같은 거래를 두 번 분류하면 뒤엣것을 버린다 — 서버가 중복 저장하지 않게."""
    dropped = invalid_results(_request(), [_result(), _result(category="PAYROLL")])
    assert 88031 in dropped
    assert "두 번" in dropped[88031]


# ---------------------------------------------------------------- 누락


def test_missing_ids_are_reported():
    """배치 일부가 빠져도 나머지는 살린다 (§6.3 폴백)."""
    two = [REQUEST["transactions"][0], {**REQUEST["transactions"][0], "transactionId": 88032}]
    request = _request(transactions=two)
    assert missing_transaction_ids(request, [_result()]) == [88032]
    assert missing_transaction_ids(request, []) == [88031, 88032]
