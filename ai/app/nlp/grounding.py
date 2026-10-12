"""한국어 텍스트에서 수치를 뽑는다 — 근거 검증의 공용 부품.

`briefing.py`(20.7)에서 먼저 만들었고 `assist.py`(20.10)가 같은 것을 쓴다. 두 기능이
재는 것은 다르지만 **"이 문장에 든 숫자가 무엇인가"** 를 알아내는 일은 같다.

여기에는 **무엇이 허용되는지에 대한 판단이 없다.** 텍스트 → 수치 집합까지만 한다.
허용 집합을 만들고 대조하는 일은 기능별 모듈이 한다 — 브리핑은 지표에서, 초안 점검은
항목 합계·건수에서, 공고 구조화(`-94`)는 원문에서 가져온다.

## 왜 이게 어려운가

한국어 금액 표기는 자리 올림이 두 층이다. `억`·`만` 은 **앞 구간 전체를 곱하고**
`천`·`백`·`십` 은 구간 안에서만 곱한다. `2천만` 은 2000 + 10000 이 아니라 2000 × 10000 이다.
이 규칙을 놓치면 `1억 2천만` 이 100,002,000 이 되고 **맞는 문장이 환각으로 폐기된다.**
"""

from __future__ import annotations

import re
from decimal import Decimal, InvalidOperation

# 한국어는 마침표 없이 줄바꿈으로 끊기는 일이 많아 둘 다 경계로 본다.
# 소수점(4.2)·천단위 쉼표와 겹치지 않도록 마침표 뒤에 공백·끝을 요구한다.
SENTENCE_SPLIT = re.compile(r"(?<=[.!?])\s+|\n+")

# 구간을 닫는 단위 — 앞 구간 전체를 곱해 합계로 넘긴다. 조는 이 도메인에 없지만
# 넣어 두면 파싱 실패(= 폐기)로 빠지지 않는다.
GROUP_UNIT = {"조": 10**12, "억": 10**8, "만": 10**4}
# 구간 안에서만 곱하는 단위.
SECTION_UNIT = {"천": 10**3, "백": 10**2, "십": 10}
_UNITS = "".join((*GROUP_UNIT, *SECTION_UNIT))
# 단위 글자가 **다른 낱말의 첫 글자**인 경우를 뺀다. `0.115만큼` 의 `만` 은 만 단위가
# 아니라 조사 `만큼` 이다. 2026-09-11 평가에서 이것 때문에 0.115 가 1,150 으로 읽혔다.
# `만원`·`만 원` 은 단위가 맞으므로 `원` 은 제외 목록에 넣지 않는다.
_NOT_UNIT = r"(?![큼에])"

# `74만 8천` 처럼 공백을 사이에 둔 복합 표기까지 한 덩어리로 잡는다.
NUMBER_EXPR = re.compile(
    rf"\d[\d,]*(?:\.\d+)?(?:\s*[{_UNITS}]{_NOT_UNIT})?"
    rf"(?:\s*\d[\d,]*(?:\s*[{_UNITS}]{_NOT_UNIT})?)*"
)
PERCENT = re.compile(r"(\d[\d,]*(?:\.\d+)?)\s*(?:%|퍼센트|％)")

_TOKEN = re.compile(rf"(\d[\d,]*(?:\.\d+)?)|([{_UNITS}]{_NOT_UNIT})")


def split_sentences(text: str) -> list[str]:
    return [s for s in (part.strip() for part in SENTENCE_SPLIT.split(text)) if s]


def to_decimal(expr: str) -> Decimal | None:
    """`74만 8천` → 748000, `1억 2천만` → 120000000. 해석 못 하면 None(= 폐기).

    구간(`section`)을 쌓다가 `억`·`만` 을 만나면 통째로 곱해 합계로 넘긴다. 머리말의
    두 층 규칙이 그것이다.
    """
    total = section = Decimal(0)
    pending: Decimal | None = None
    seen = False

    for raw, unit in _TOKEN.findall(expr):
        if raw:
            if pending is not None:
                section += pending
            try:
                pending = Decimal(raw.replace(",", ""))
            except InvalidOperation:
                return None
            seen = True
            continue

        if pending is not None:
            section += pending
            pending = None
        if unit in SECTION_UNIT:
            # 구간 안에서만 곱한다. 바로 앞 숫자가 없으면 1 로 본다(`천원`).
            factor = SECTION_UNIT[unit]
            section = section * factor if section else Decimal(factor)
        else:
            if not section:
                section = Decimal(1)  # `만원` 처럼 홀로 오는 경우
            total += section * GROUP_UNIT[unit]
            section = Decimal(0)
        seen = True

    if pending is not None:
        section += pending
    return total + section if seen else None


def numbers_in(text: str) -> set[Decimal]:
    """문자열에 든 숫자 표현을 값으로 모은다."""
    found: set[Decimal] = set()
    for match in NUMBER_EXPR.finditer(text):
        value = to_decimal(match.group())
        if value is not None:
            found.add(value)
    return found
