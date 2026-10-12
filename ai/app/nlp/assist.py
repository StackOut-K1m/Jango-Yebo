"""초안 점검 결과의 근거 검증 (명세 20.10).

명세가 *"응답 문장은 요청 본문에 있는 수치만 인용할 수 있다. 입력에 없는 수치가 있으면
해당 문장을 폐기한다(20.7과 동일)"* 라고 적었다. 그 폐기를 하는 곳이다.

## 브리핑(20.7)과 무엇이 같고 무엇이 다른가

같은 것 — 허용 수치가 **구조화된 입력에서** 온다. 그래서 대조가 기계적이고, 판정에
LLM 이 끼지 않는다. 숫자 표기를 읽는 부품은 `grounding.py` 를 함께 쓴다.

다른 것 셋이다.

1. **폐기 단위가 문장이 아니라 `note` 하나다.** 브리핑의 `content` 는 문장을 이어 붙인
   한 덩어리라 문장을 골라내야 했지만, `notes[]` 는 이미 항목별로 쪼개져 있다.
   문제가 있는 note 를 통째로 버리는 것이 명세 20.1 의 "해당 항목만 제외" 와도 맞는다.
2. **날짜를 허용하지 않는다.** 요청(`fields[]`·`pending[]`)에 날짜가 없다. 그러니 note 에
   나온 날짜·연도는 무조건 근거가 없다. 별도 규칙을 둘 필요 없이 숫자 대조에서 걸린다.
3. **`fieldKey` 도 본다.** 요청에 없는 키로 낸 문장은 초안의 어느 칸에도 붙지 못한다.
   분류의 `invalid_results()` 와 같은 성격이다 — 모델이 지어낸 대상은 버린다.

## 무엇을 못 잡는가

**숫자가 없는 거짓은 못 잡는다.** "지급명세서를 제출해야 합니다" 같은 세무 판단은
숫자가 없어서 이 검증기를 그냥 통과한다. 그걸 막는 것은 프롬프트(`severity` 에 `ERROR`
가 없는 것)와 스키마이지 여기가 아니다. 이 검증기는 **수치 환각 하나**만 책임진다.
"""

from __future__ import annotations

import re
from dataclasses import dataclass
from decimal import Decimal

from app.nlp.grounding import numbers_in
from app.nlp.schemas import DocumentAssistRequest, ReviewNote


@dataclass(frozen=True)
class Verdict:
    kept: list[ReviewNote]
    discarded: list[tuple[ReviewNote, str]]


def allowed_values(request: DocumentAssistRequest) -> set[Decimal]:
    """note 에 나와도 되는 수.

    금액과 건수를 한 집합에 섞는다. 나누면 "36 은 건수 자리에서만 쓸 수 있다" 같은
    규칙이 생기는데, 그걸 지키려면 문장에서 숫자의 **역할**을 알아야 한다. 그건 LLM
    판단이라 결정론적 검증기의 범위 밖이다.

    섞어서 느슨해지는 만큼은 감수한다 — 이 검증기가 막으려는 것은 **입력에 없는 수를
    만들어 내는 것**이지 있는 수를 엉뚱한 자리에 쓰는 것이 아니다.
    """
    allowed: set[Decimal] = set()
    for field in request.fields:
        for value in (
            field.value,
            field.transaction_count,
            field.evidence_missing_count,
            field.unreviewed_count,
            field.months_covered,
        ):
            if value is not None:
                allowed.add(Decimal(value))
    for item in request.pending:
        for value in (item.amount, item.count):
            if value is not None:
                allowed.add(Decimal(value))
    return allowed


def allowed_field_keys(request: DocumentAssistRequest) -> set[str]:
    keys = {field.field_key for field in request.fields}
    keys |= {item.field_key for item in request.pending}
    return keys


# 숫자를 품은 **고유명사**. 수치 주장이 아니므로 세기 전에 지운다.
#
# 2026-09-14 평가에서 "원천징수영수증이나 **4대보험** 신고 자료가 준비되어 있는지 확인해
# 보세요" 가 `4` 때문에 폐기됐다. 정확하고 유용한 문장이었다. 브리핑의 `만큼`(`_NOT_UNIT`)과
# 같은 종류의 오탐이다.
#
# **관찰한 것만 넣는다.** 가릴 때마다 검증기에 구멍이 하나씩 생기므로, 있을 법하다는
# 이유로 미리 넣지 않는다. 새 오탐이 나오면 그때 근거와 함께 더한다.
_FIXED_TERMS = re.compile(r"4\s*대\s*보험")


def _check(note: ReviewNote, allowed: set[Decimal], keys: set[str]) -> str | None:
    """문제가 있으면 사유, 없으면 None."""
    if note.field_key not in keys:
        return f"요청에 없는 항목: {note.field_key}"

    message = _FIXED_TERMS.sub(" ", note.message)
    for value in numbers_in(message):
        if value not in allowed:
            return f"입력에 없는 수치: {value}"
    return None


def verify(request: DocumentAssistRequest, notes: list[ReviewNote]) -> Verdict:
    """근거 없는 note 를 버린다. 남은 것만 초안에 붙는다.

    **고치지 않고 버린다.** 사람이 검수하지 않는 경로라 "아마 맞겠지" 로 넘기면
    사장님이 그 숫자를 신고서에 옮겨 적는다.
    """
    allowed = allowed_values(request)
    keys = allowed_field_keys(request)

    kept: list[ReviewNote] = []
    discarded: list[tuple[ReviewNote, str]] = []
    for note in notes:
        reason = _check(note, allowed, keys)
        if reason is None:
            kept.append(note)
        else:
            discarded.append((note, reason))
    return Verdict(kept=kept, discarded=discarded)
