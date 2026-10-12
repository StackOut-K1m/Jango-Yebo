"""20.10 근거 검증 — 입력에 없는 수치가 든 note 를 버리는가 (`-96` 절대 기준).

여기서 재는 것은 **모델이 아니라 검증기**다. 그래서 입력과 기대를 내가 다 써도 규칙
위반이 아니다 — `test_briefing_hallucination.py` 와 같은 성격이다. 모델이 실제로 얼마나
지어내는지는 `evals/p0-12` 가 잰다.

검증기가 **오탐**을 내면 맞는 문장이 버려지고, 그러면 평가가 모델이 아니라 검증기를
재게 된다. 브리핑에서 오탐 3라운드를 겪었다. 그래서 「버리면 안 되는 것」 절이 「버려야
하는 것」 만큼 길다.
"""

from decimal import Decimal

import pytest

from app.nlp import assist
from app.nlp.schemas import DocumentAssistRequest, ReviewNote

REQUEST = DocumentAssistRequest.model_validate(
    {
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
    }
)


def note(message: str, field_key: str = "PAYROLL", severity: str = "WARN") -> ReviewNote:
    return ReviewNote(field_key=field_key, severity=severity, message=message)


def verify_one(message: str, field_key: str = "PAYROLL"):
    return assist.verify(REQUEST, [note(message, field_key)])


# ---------------------------------------------------------------- 허용 집합


def test_금액과_건수가_모두_허용된다() -> None:
    allowed = assist.allowed_values(REQUEST)

    for value in (28_600_000, 36, 3, 0, 14_400_000, 12, 3_400_000, 2):
        assert Decimal(value) in allowed


def test_보류_항목의_수치도_허용된다() -> None:
    """`pending[]` 은 초안 금액에 안 들어갔지만 **요청 본문에는 있다.** 빼면
    "설비 2건 340만원은 넣지 않았습니다" 라는 정직한 문장이 폐기된다."""
    allowed = assist.allowed_values(REQUEST)

    assert Decimal(3_400_000) in allowed
    assert Decimal(2) in allowed


def test_항목_키_집합에_보류도_들어간다() -> None:
    assert assist.allowed_field_keys(REQUEST) == {"PAYROLL", "RENT", "DEPRECIATION"}


# ---------------------------------------------------------------- 버려야 하는 것


def test_뺄셈_결과를_버린다() -> None:
    """36 − 3 = 33. 둘 다 입력에 있지만 **33 은 없다.**

    이 기능에서 가장 나오기 쉬운 환각이다. 모델이 친절하려고 "나머지 33건은 증빙이
    있습니다" 를 덧붙이는데, 그 33 은 아무도 검산하지 않은 수다.
    """
    verdict = verify_one("급여 36건 중 33건은 증빙이 연결됐습니다.")

    assert not verdict.kept
    assert "33" in verdict.discarded[0][1]


def test_합산_결과를_버린다() -> None:
    """28,600,000 + 14,400,000 = 43,000,000."""
    verdict = verify_one("급여와 임차료를 합쳐 43,000,000원입니다.")

    assert not verdict.kept


def test_비율_계산을_버린다() -> None:
    verdict = verify_one("급여 항목의 증빙 누락률이 8.3%입니다.")

    assert not verdict.kept


def test_반올림을_버린다() -> None:
    """`약 2,900만원` 은 입력에 없는 수다. 정확한 값이 있는데 뭉개는 쪽이 더 나쁘다."""
    verdict = verify_one("급여 항목은 약 2,900만원입니다.")

    assert not verdict.kept


def test_날짜를_버린다() -> None:
    """요청에 날짜가 없다. 그러니 note 에 나온 연도·월은 무조건 근거가 없다."""
    verdict = verify_one("2026년 귀속분 급여입니다.")

    assert not verdict.kept


def test_요청에_없는_항목_키를_버린다() -> None:
    """초안의 어느 칸에도 붙지 못하는 문장이다."""
    verdict = verify_one("매출액을 확인하세요.", field_key="SALES")

    assert not verdict.kept
    assert "SALES" in verdict.discarded[0][1]


def test_문제가_있는_note_만_버린다() -> None:
    """명세 20.1 — "해당 항목만 제외한다". 하나가 틀렸다고 전부 버리지 않는다."""
    good = note("급여 36건 중 3건은 원천세 자료가 연결되지 않았습니다.")
    bad = note("나머지 33건은 확인됐습니다.")

    verdict = assist.verify(REQUEST, [good, bad, good])

    assert len(verdict.kept) == 2
    assert len(verdict.discarded) == 1


# ---------------------------------------------------------------- 버리면 안 되는 것


@pytest.mark.parametrize(
    "message",
    [
        "급여 36건 중 3건은 원천세 신고 자료가 연결되지 않았습니다.",
        "급여 항목은 28,600,000원입니다.",
        "임차료는 12개월분 12건이 집계됐습니다.",
        "미검토 0건입니다.",
        "설비 구입 2건은 항목 금액에 넣지 않았습니다.",
        "설비 구입 3,400,000원은 감가상각 검토 대상입니다.",
    ],
)
def test_입력을_그대로_인용한_문장은_남는다(message: str) -> None:
    assert verify_one(message).kept


@pytest.mark.parametrize(
    "message",
    [
        "급여 항목은 2,860만원입니다.",
        "설비 구입은 340만원입니다.",
    ],
)
def test_한국어_표기도_같은_수로_읽는다(message: str) -> None:
    """`2,860만원` 은 28,600,000 이다. 이걸 못 읽으면 **맞는 문장이 폐기된다.**"""
    assert verify_one(message).kept


def test_숫자가_없는_문장은_그냥_통과한다() -> None:
    """숫자 없는 거짓은 이 검증기가 못 잡는다. 막는 것은 프롬프트와 스키마다."""
    assert verify_one("지급명세서 제출 여부를 확인하세요.").kept


def test_빈_목록이_정상이다() -> None:
    verdict = assist.verify(REQUEST, [])

    assert verdict.kept == []
    assert verdict.discarded == []


# ---------------------------------------------------------------- 고유명사 오탐


@pytest.mark.parametrize(
    "message",
    [
        "원천징수영수증이나 4대보험 신고 자료가 준비되어 있는지 확인해 보세요.",
        "4대 보험 자료를 함께 보세요.",
    ],
)
def test_숫자를_품은_고유명사를_수치로_세지_않는다(message: str) -> None:
    """2026-09-14 평가에서 실제로 폐기됐던 문장이다.

    `4대보험` 의 `4` 는 수치 주장이 아니라 고유명사다. 정확하고 유용한 문장이 이것 때문에
    버려졌다 — 브리핑의 `만큼` 과 같은 종류의 오탐이다.
    """
    assert verify_one(message).kept


def test_고유명사를_가려도_다른_수치는_걸린다() -> None:
    """마스킹은 검증기에 구멍을 낸다. 그 구멍이 문장 전체로 번지지 않는지 본다."""
    verdict = verify_one("4대보험 자료와 함께 나머지 33건도 확인하세요.")

    assert not verdict.kept
    assert "33" in verdict.discarded[0][1]
