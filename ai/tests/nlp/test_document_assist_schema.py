"""20.10 신고서 초안 점검 계약 — 명세 예시가 그대로 파싱되는가 (`-88` 완료 기준).

거래 분류·브리핑과 같은 방식으로 명세 JSON 을 **한 글자도 고치지 않고** 붙여 두고 잰다.

여기에는 하나가 더 있다. `-88` 완료 기준이 "민감 키가 모델에 존재하지 않음을 검증하는
테스트" 를 따로 요구한다 — 명세 20.1 의 *"신고서 초안(14.3)의 민감 항목은 워커에 전달
자체를 하지 않는다"* 를 코드로 묶어 두는 자리다. 「민감 정보」 절이 그것이다.
"""

import pytest
from pydantic import ValidationError

from app.nlp.schemas import (
    FIELDS_MAX,
    SENSITIVE_FIELD_KEYS,
    VALUE_ABS_MAX,
    DocumentAssistRequest,
    DocumentAssistResult,
    DraftFieldSummary,
    PendingItem,
    ReviewNote,
)

# docs/api/20_내부_AI워커.md §20.10 의 요청 예시 그대로.
SPEC_REQUEST = {
    "draftId": 301,
    "formType": "SIMPLE_LEDGER",
    "business": {"industryType": "FOOD_CAFE"},
    "fields": [
        {
            "fieldKey": "PAYROLL",
            "value": 28600000,
            "transactionCount": 36,
            "evidenceMissingCount": 3,
            "unreviewedCount": 0,
        },
        {"fieldKey": "RENT", "value": 14400000, "transactionCount": 12, "monthsCovered": 12},
    ],
    "pending": [
        {
            "fieldKey": "DEPRECIATION",
            "reason": "EQUIPMENT_PURCHASE",
            "amount": 3400000,
            "count": 2,
        }
    ],
    "toneGuide": "확정 표현 금지, 세무 판단 확정 금지, 입력에 없는 수치 생성 금지",
}

# §20.10 의 응답 예시 `data` 그대로.
SPEC_RESPONSE_DATA = {
    "notes": [
        {
            "fieldKey": "PAYROLL",
            "severity": "WARN",
            "message": (
                "급여 36건 중 3건은 원천세 신고 자료가 연결되지 않았습니다. "
                "지급명세서 제출 여부를 확인하세요."
            ),
        },
        {
            "fieldKey": "DEPRECIATION",
            "severity": "INFO",
            "message": "설비 구입 2건은 감가상각 대상일 수 있어 항목 금액에 넣지 않았습니다.",
        },
    ]
}


# ---------------------------------------------------------------- 요청


def test_명세_예시가_그대로_파싱된다() -> None:
    request = DocumentAssistRequest.model_validate(SPEC_REQUEST)

    assert request.draft_id == 301
    assert request.form_type == "SIMPLE_LEDGER"
    assert request.business.industry_type == "FOOD_CAFE"
    assert [f.field_key for f in request.fields] == ["PAYROLL", "RENT"]
    assert request.fields[0].evidence_missing_count == 3
    assert request.fields[0].months_covered is None
    assert request.fields[1].months_covered == 12
    assert request.pending[0].reason == "EQUIPMENT_PURCHASE"
    assert request.tone_guide is not None


def test_동기_호출이라_job_id가_없다() -> None:
    """20.2 목록에서 이 경로만 콜백을 쓰지 않는다. `jobId` 를 받는 자리를 두면
    백엔드가 비동기로 착각해 콜백을 기다린다."""
    assert "job_id" not in DocumentAssistRequest.model_fields

    request = DocumentAssistRequest.model_validate({**SPEC_REQUEST, "jobId": 9016})

    assert not hasattr(request, "job_id")


def test_항목이_하나도_없으면_거부한다() -> None:
    """점검할 것이 없는데 LLM 을 부르는 요청이다. 돈만 쓰고 낼 문장이 없다."""
    with pytest.raises(ValidationError):
        DocumentAssistRequest.model_validate({**SPEC_REQUEST, "fields": []})


def test_항목이_상한을_넘으면_거부한다() -> None:
    field = SPEC_REQUEST["fields"][0]
    with pytest.raises(ValidationError):
        DocumentAssistRequest.model_validate({**SPEC_REQUEST, "fields": [field] * (FIELDS_MAX + 1)})


def test_보류_항목은_없어도_된다() -> None:
    payload = {k: v for k, v in SPEC_REQUEST.items() if k != "pending"}
    request = DocumentAssistRequest.model_validate(payload)

    assert request.pending == []


def test_명세_14_3의_서식_유형_넷을_모두_받는다() -> None:
    for form_type in ("SIMPLE_LEDGER", "STANDARD_RATE", "SIMPLE_RATE", "DOUBLE_ENTRY"):
        request = DocumentAssistRequest.model_validate({**SPEC_REQUEST, "formType": form_type})
        assert request.form_type == form_type


def test_모르는_서식_유형을_거부한다() -> None:
    with pytest.raises(ValidationError):
        DocumentAssistRequest.model_validate({**SPEC_REQUEST, "formType": "VAT_SIMPLE"})


def test_값이_없는_항목도_받는다() -> None:
    """14.3 의 `DEPRECIATION` 은 `value: null` 로 온다. 거부하면 정작 점검이 가장
    필요한 빈 항목을 못 보낸다."""
    field = DraftFieldSummary.model_validate({"fieldKey": "DEPRECIATION", "value": None})

    assert field.value is None
    assert field.transaction_count is None


def test_음수_건수를_거부한다() -> None:
    with pytest.raises(ValidationError):
        DraftFieldSummary.model_validate({"fieldKey": "RENT", "transactionCount": -1})


def test_보류_사유를_열거값으로_묶지_않는다() -> None:
    """명세 전체에서 `EQUIPMENT_PURCHASE` 한 값만 나오고 허용 집합을 적은 곳이 없다.
    추측해서 묶으면 백엔드가 두 번째 사유를 넣는 날 요청이 통째로 400 이 된다."""
    item = PendingItem.model_validate(
        {"fieldKey": "DEPRECIATION", "reason": "ASSET_REVIEW", "amount": 3400000}
    )

    assert item.reason == "ASSET_REVIEW"


# ---------------------------------------------------------------- 민감 정보 (`-88` 완료 기준)


def _all_field_names() -> set[str]:
    """요청에 닿는 모든 모델의 필드 이름 — 별칭(camelCase)까지 함께 본다."""
    names: set[str] = set()
    for model in (DocumentAssistRequest, DraftFieldSummary, PendingItem):
        for name, field in model.model_fields.items():
            names.add(name)
            if field.alias:
                names.add(field.alias)
    return names


@pytest.mark.parametrize(
    "banned",
    [
        # 주민등록번호
        "resident_registration_number",
        "residentRegistrationNumber",
        "rrn",
        "ssn",
        # 계좌번호
        "account_number",
        "accountNumber",
        "account_no",
        "refund_account",
        "refundAccount",
        # 주소지
        "home_address",
        "homeAddress",
        "address",
        # 생년월일
        "birth_date",
        "birthDate",
        "birthday",
        # 부양가족
        "dependents",
        "dependent_count",
        "dependentCount",
        "family",
    ],
)
def test_민감_필드가_모델에_아예_없다(banned: str) -> None:
    """마스킹이 아니라 **부재**다. 필드가 없으면 백엔드가 실수로 담아도 담길 곳이 없고,
    나중에 누가 프롬프트를 고쳐도 모델에 흘려보낼 방법이 없다 (명세 20.1)."""
    assert banned not in _all_field_names()


def test_민감_항목_키를_보내면_요청을_거부한다() -> None:
    """조용히 버리지 않는다. 버리면 백엔드는 자기가 민감 항목을 보내고 있다는 것을
    영영 모른다 — 알려 줄 수 있는 곳이 여기뿐이다."""
    for key in sorted(SENSITIVE_FIELD_KEYS):
        with pytest.raises(ValidationError, match="민감 항목"):
            DocumentAssistRequest.model_validate(
                {**SPEC_REQUEST, "fields": [{"fieldKey": key, "value": None}]}
            )


def test_보류_목록에_섞인_민감_항목도_거부한다() -> None:
    with pytest.raises(ValidationError, match="민감 항목"):
        DocumentAssistRequest.model_validate(
            {**SPEC_REQUEST, "pending": [{"fieldKey": "BIRTH_DATE", "reason": "USER_INPUT"}]}
        )


def test_14_3이_민감으로_표시한_항목이_모두_막혀_있다() -> None:
    """14.3 `USER_SENSITIVE` 는 주소지(①)·생년월일(④)이다. 성명(③)은 `sensitive`
    표시가 없지만 사람 이름이고 20.10 점검에 필요하지 않아 같이 막았다."""
    assert {"HOME_ADDRESS", "BIRTH_DATE"} <= SENSITIVE_FIELD_KEYS
    assert "NAME" in SENSITIVE_FIELD_KEYS


@pytest.mark.parametrize(
    "value",
    ["정유선", "1985-03-12", "광주광역시 서구 상무대로 123", "110-25-12345"],
)
def test_숫자가_아닌_값을_거부한다(value: str) -> None:
    """두 번째 방어선 — 키 이름을 못 맞혀도 값이 걸린다.

    주민등록번호·환급금 계좌는 별지 제40호 항목이라 14.3 에 키가 없어서 이름으로는
    막을 수 없다. 20.10 이 "항목 합계·건수" 만 보낸다고 했으니 금액 아닌 값은
    들어올 이유가 없고, 사람을 가리키는 값은 대부분 정수가 아니다.
    """
    with pytest.raises(ValidationError):
        DraftFieldSummary.model_validate({"fieldKey": "SOME_NEW_FIELD", "value": value})


@pytest.mark.parametrize(
    "value",
    [
        8503121234567,  # 주민등록번호 13자리
        1002345678901,  # 계좌번호 13자리
    ],
)
def test_숫자로만_된_식별번호를_거부한다(value: int) -> None:
    """문자열 차단만으로는 못 막는 구멍이다 — 하이픈 없는 주민등록번호·계좌번호는
    `int` 로 조용히 파싱된다.

    간편장부대상자는 직전연도 수입금액 1억 5천만원 미만이라(14.3) 항목 금액이 조
    단위가 될 수 없다. 13자리 숫자가 오면 금액이 아니라 식별번호다.
    """
    with pytest.raises(ValidationError):
        DraftFieldSummary.model_validate({"fieldKey": "SOME_NEW_FIELD", "value": value})


def test_상한_안의_금액은_통과한다() -> None:
    """막는 것은 금액이 아니라 식별번호다. 실제로 올 수 있는 금액은 다 통과해야 한다."""
    for amount in (0, -700_000, 98_500_000, VALUE_ABS_MAX, -VALUE_ABS_MAX):
        field = DraftFieldSummary.model_validate({"fieldKey": "SALES", "value": amount})
        assert field.value == amount


# ---------------------------------------------------------------- 응답


def test_명세_응답_예시가_그대로_파싱된다() -> None:
    result = DocumentAssistResult.model_validate(SPEC_RESPONSE_DATA)

    assert [n.field_key for n in result.notes] == ["PAYROLL", "DEPRECIATION"]
    assert result.notes[0].severity == "WARN"
    assert result.notes[1].severity == "INFO"
    assert "지급명세서" in result.notes[0].message


def test_심각도에_error가_없다() -> None:
    """세무 판단을 확정하지 않는다 (도메인 규칙 4 · 14.3 `disclaimer`).

    「이건 잘못됐다」 라고 말할 수 있는 자리를 만들어 두면 모델이 결국 거기에 쓴다.
    낼 수 있는 것은 확인 요청(`WARN`)과 설명(`INFO`)뿐이다.
    """
    with pytest.raises(ValidationError):
        ReviewNote.model_validate({"fieldKey": "PAYROLL", "severity": "ERROR", "message": "틀렸다"})

    for severity in ("CRITICAL", "FATAL", "error", "warn"):
        with pytest.raises(ValidationError):
            ReviewNote.model_validate(
                {"fieldKey": "PAYROLL", "severity": severity, "message": "문장"}
            )


def test_점검할_것이_없으면_빈_목록이다() -> None:
    """억지로 한 문장 채우면 그게 곧 환각이다."""
    result = DocumentAssistResult.model_validate({"notes": []})

    assert result.notes == []


def test_빈_문장을_거부한다() -> None:
    with pytest.raises(ValidationError):
        ReviewNote.model_validate({"fieldKey": "PAYROLL", "severity": "INFO", "message": ""})


def test_camel_case로_직렬화한다() -> None:
    result = DocumentAssistResult.model_validate(SPEC_RESPONSE_DATA)
    dumped = result.model_dump(by_alias=True, mode="json")

    assert dumped["notes"][0]["fieldKey"] == "PAYROLL"
    assert dumped["notes"][0]["severity"] == "WARN"
    assert dumped == SPEC_RESPONSE_DATA


def test_모델_식별_정보를_담지_않는다() -> None:
    """20.10 예시가 `notes` 하나만 담고 있고, 20.1 의 `modelName`·`modelVersion` 요구는
    예측 모델(20.11~12)에 한정된 규약이다. 계약 밖 필드를 임의로 얹지 않는다."""
    assert set(DocumentAssistResult.model_fields) == {"notes"}
