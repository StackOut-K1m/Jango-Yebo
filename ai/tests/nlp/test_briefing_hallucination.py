"""환각 숫자 검증 — `-95` 의 완료 기준("폐기 로직이 테스트로 고정된다").

이 파일이 지키는 것은 하나다. **입력 `metrics` 에 없는 수·날짜가 든 문장은 사용자에게
나가지 않는다.** 브리핑의 위험은 문장력이 아니라 숫자다 — 틀린 숫자 하나로 사장님이
자금 판단을 한다.

라우트 수준 동작(PARTIAL 콜백·전부 폐기 경로)은 `test_briefing_route.py` 가 본다.
"""

from decimal import Decimal

import pytest

from app.nlp.briefing import allowed_values, verify
from app.nlp.grounding import split_sentences, to_decimal
from app.nlp.schemas import BriefingGenerationRequest

REQUEST = BriefingGenerationRequest.model_validate(
    {
        "jobId": 9015,
        "briefingId": 91,
        "periodType": "WEEKLY",
        "metrics": [
            {"label": "화요일 정산 입금", "value": 748000, "changeRate": -0.12},
            {"label": "향후 30일 최저잔액", "value": -420000, "date": "2026-09-14"},
            {"label": "다음 대형 지출", "value": 3000000, "date": "2026-09-14", "title": "급여"},
        ],
    }
)


def _reasons(content: str) -> list[str]:
    return [reason for _, reason in verify(REQUEST, content).discarded]


# ---------------------------------------------------------------- 한국어 수 읽기


@pytest.mark.parametrize(
    ("expr", "expected"),
    [
        ("748,000", 748000),
        ("748000", 748000),
        ("74만 8천", 748000),
        ("74만8천", 748000),
        ("3,000,000", 3000000),
        ("3백만", 3000000),
        ("1만 2천", 12000),
        ("0.12", "0.12"),
        # `만`·`억` 은 앞 구간 전체를 곱한다. 이것을 놓치면 1억 2천만이 100,002,000 이 되고
        # **맞는 문장이 환각으로 폐기된다.**
        ("2천만", 20000000),
        ("1억 2천만", 120000000),
    ],
)
def test_한국어_수_표기를_값으로_읽는다(expr: str, expected: object) -> None:
    assert str(to_decimal(expr)) == str(expected)


def test_해석하지_못하면_None_이다() -> None:
    """None 은 호출부에서 폐기로 이어진다 — 모르면 통과가 아니라 버린다."""
    assert to_decimal("") is None
    assert to_decimal("원") is None


# ---------------------------------------------------------------- 허용 집합


def test_지표에서만_허용_수가_나온다() -> None:
    allowed = allowed_values(REQUEST)

    assert Decimal(748000) in allowed
    assert Decimal(3000000) in allowed
    # 음수 지표를 "42만원 부족" 으로 쓰는 것은 자연스럽다
    assert Decimal(420000) in allowed and Decimal(-420000) in allowed
    # changeRate -0.12 → "12% 줄었다"
    assert Decimal(12) in allowed
    # label 의 숫자도 인용이지 창작이 아니다 — "향후 30일"
    assert Decimal(30) in allowed
    # 입력 어디에도 없는 수
    assert Decimal(1234567) not in allowed


# ---------------------------------------------------------------- 통과해야 하는 문장


@pytest.mark.parametrize(
    "sentence",
    [
        "화요일 정산금이 748,000원으로 12% 줄었습니다.",
        "9월 14일 이후 잔액이 420,000원 부족할 가능성이 있습니다.",
        "향후 30일 최저잔액은 -420,000원입니다.",
        # 팀 UI 규칙이 만원 축약을 쓴다(루트 CLAUDE.md). 값이 같으면 표기는 자유다.
        "정산금이 74만 8천원 들어왔습니다.",
        "2026년 9월 14일에 급여 3,000,000원이 나갑니다.",
        "숫자가 없는 문장은 검사할 것이 없습니다.",
    ],
)
def test_지표에_있는_수는_통과한다(sentence: str) -> None:
    assert verify(REQUEST, sentence).discarded == []


# ---------------------------------------------------------------- 폐기해야 하는 문장


@pytest.mark.parametrize(
    ("sentence", "hint"),
    [
        ("재료비가 18.1% 올랐습니다.", "비율"),
        ("9월 20일에 큰 지출이 있습니다.", "날짜"),
        ("잔액이 1,234,567원 남습니다.", "수"),
        # 반올림도 환각이다 — 748,000 을 "약 75만원" 으로 바꾸면 그 75만은 입력에 없다
        ("정산금이 약 75만원 들어왔습니다.", "수"),
        # 두 지표를 더한 값. LLM 이 계산하면 안 된다는 것이 도메인 규칙 2 다
        ("이번 주 합계는 3,748,000원입니다.", "수"),
    ],
)
def test_지표에_없는_수는_폐기한다(sentence: str, hint: str) -> None:
    reasons = _reasons(sentence)

    assert len(reasons) == 1
    assert hint in reasons[0]


# ---------------------------------------------------------------- 폐기 단위


def test_문장_단위로만_버린다() -> None:
    """한 문장이 틀렸다고 브리핑 전체를 버리지 않는다 (명세 20.1 "해당 항목만 제외")."""
    content = "화요일 정산금이 748,000원으로 12% 줄었습니다. 재료비가 18.1% 올랐습니다."

    verdict = verify(REQUEST, content)

    assert verdict.kept == ["화요일 정산금이 748,000원으로 12% 줄었습니다."]
    assert len(verdict.discarded) == 1
    assert verdict.is_partial is True
    assert "18.1" not in verdict.content


def test_모두_통과하면_PARTIAL_이_아니다() -> None:
    verdict = verify(REQUEST, "정산금이 748,000원입니다. 9월 14일을 주의하세요.")

    assert verdict.is_partial is False
    assert len(verdict.kept) == 2


def test_모두_폐기되면_남는_문장이_없다() -> None:
    verdict = verify(REQUEST, "매출이 4.2% 늘었습니다. 재료비가 18.1% 올랐습니다.")

    assert verdict.kept == []
    assert len(verdict.discarded) == 2
    assert verdict.content == ""


def test_줄바꿈도_문장_경계다() -> None:
    """모델이 마침표 없이 줄로 끊어 쓰는 일이 잦다."""
    assert len(split_sentences("첫 줄입니다\n둘째 줄입니다")) == 2


def test_소수점은_문장_경계가_아니다() -> None:
    """`4.2%` 의 마침표에서 끊으면 숫자가 반으로 쪼개져 엉뚱한 판정이 난다."""
    assert len(split_sentences("재료비가 4.2% 올랐습니다.")) == 1


def test_천단위_쉼표가_숫자를_쪼개지_않는다() -> None:
    """`748,000` 을 748 과 000 으로 읽으면 맞는 문장이 폐기된다."""
    assert verify(REQUEST, "정산금 748,000원").discarded == []


# ---------------------------------------------------------------- ISO 날짜


@pytest.mark.parametrize(
    "sentence",
    [
        "2026-09-14에 급여 3,000,000원이 나갑니다.",
        "2026년 9월 14일에 급여가 나갑니다.",
        "9월 14일을 주의하세요.",
    ],
)
def test_입력에_있는_날짜는_표기가_달라도_통과한다(sentence: str) -> None:
    """`metrics[].date` 가 ISO 라 모델이 그대로 베껴 쓰는 일이 잦다.

    2026-09-11 평가 30건에서 폐기 5건이 **전부** 이 오탐이었다 — 입력을 그대로 인용한
    가장 정직한 문장이 `2026` 때문에 버려졌다.
    """
    assert verify(REQUEST, sentence).discarded == []


def test_ISO_표기의_틀린_날짜도_잡는다() -> None:
    assert "날짜" in _reasons("2026-09-20에 큰 지출이 있습니다.")[0]


def test_달력에_없는_날짜는_폐기한다() -> None:
    """`2026-02-30` 같은 표기를 통과시키면 파싱 예외로 라우트가 죽는다."""
    assert _reasons("2026-02-30에 지출이 있습니다.") != []


def test_일_없는_달_표기가_통과한다() -> None:
    """`9월 중순` 은 입력 날짜(2026-09-14)의 달을 인용한 것이다.

    2026-09-11 평가에서 headline 폐기 2건이 전부 이 형태였다.
    """
    assert verify(REQUEST, "9월 중순에 자금이 빠듯할 수 있습니다.").discarded == []
    assert verify(REQUEST, "14일에 급여가 나갑니다.").discarded == []
    assert verify(REQUEST, "2026년 실적입니다.").discarded == []


def test_입력에_없는_달은_여전히_잡는다() -> None:
    assert _reasons("12월에 큰 지출이 있습니다.") != []


def test_만큼의_만을_만_단위로_읽지_않는다() -> None:
    """`0.115만큼` 의 `만` 은 조사다. 단위로 읽으면 0.115 가 1,150 이 된다.

    2026-09-11 평가에서 실제로 이 오독이 나왔다.
    """
    assert str(to_decimal("0.115만큼")) == "0.115"
    assert str(to_decimal("3일만에")) == "3"
    # 진짜 단위는 그대로 읽는다
    assert str(to_decimal("74만원")) == "740000"
    assert str(to_decimal("74만 8천원")) == "748000"
