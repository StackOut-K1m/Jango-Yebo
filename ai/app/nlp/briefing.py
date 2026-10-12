"""브리핑 본문의 환각 숫자를 잡아 문장 단위로 폐기한다 (명세 20.7 · `-95`).

## 이 파일이 있는 이유

브리핑의 위험은 문장력이 아니라 숫자다. *"매출이 4.2% 늘었는데 재료비가 18.1% 올라 남는
돈은 오히려 줄었습니다"* 에서 **숫자 하나가 틀리면 사장님이 그 숫자로 판단한다.**

도메인 규칙 2 가 "수치 계산은 백엔드 엔진이, LLM 은 자연어 변환만" 이라고 못박았으므로,
본문에 있어도 되는 숫자는 **요청 `metrics` 에서 온 것뿐**이다. 그 밖의 숫자는 모델이
지어낸 것이고, 그 문장을 폐기한 뒤 `status = PARTIAL` 로 돌려준다.

**LLM 을 심판으로 쓰지 않는다.** 판정이 결정적이어야 하고(같은 입력 → 같은 결과), 무료여야
하고, 심판이 또 환각을 내면 검증이 아무 의미가 없다.

## 판정 방향 — 모르면 버린다

숫자 표현을 해석하지 못하면 **통과가 아니라 폐기**다. 돈을 세는 화면에서 "확인 못 했지만
아마 맞겠지" 는 허용되지 않는다. 폐기는 문장 하나를 잃는 것이고, 오통과는 틀린 금액을
사장님에게 보여주는 것이다. 비용이 다르다.

같은 이유로 **반올림·근사도 환각으로 센다.** 입력이 `748000` 인데 "약 75만원" 이라고 쓰면
그 75만은 입력에 없는 숫자다. 대신 프롬프트가 "입력에 적힌 숫자를 그대로 쓰라" 고 지시해
폐기가 실제로는 드물게 일어나도록 맞춘다.

## 허용하는 표기

`1,340,000원` 과 `120~140만원` 둘 다 팀 UI 규칙(루트 CLAUDE.md)이 쓰는 형태라 양쪽을 읽는다.

| 본문 | 해석 |
| --- | --- |
| `748,000` · `748000` | 748000 |
| `74만 8천` · `74만8천` | 748000 |
| `1억 2천만` | 120000000 |
| `12%` · `12.5%` | 비율 — `changeRate` 의 절댓값 × 100 과 대조 |
| `2026년 9월 14일` · `9월 14일` · `9/14` | 날짜 — `metrics[].date` 와 대조 |

`label`·`title` 에 든 숫자도 허용한다. `"향후 30일 최저잔액"` 을 인용한 `30일` 은
지어낸 것이 아니다.
"""

from __future__ import annotations

import re
from dataclasses import dataclass
from datetime import date
from decimal import Decimal

from app.nlp.grounding import NUMBER_EXPR, PERCENT, numbers_in, split_sentences, to_decimal
from app.nlp.schemas import BriefingGenerationRequest

_DATE_FULL = re.compile(r"(\d{4})\s*년\s*(\d{1,2})\s*월\s*(\d{1,2})\s*일")
# `metrics[].date` 가 ISO 라 모델이 그대로 베껴 쓰는 일이 잦다. 이것을 날짜로 읽지 못하면
# `2026` 이 일반 숫자로 새어 **입력을 그대로 인용한 가장 정직한 문장이 폐기된다.**
# 2026-09-11 평가 30건에서 폐기 5건이 전부 이 오탐이었다.
_DATE_ISO = re.compile(r"(\d{4})-(\d{1,2})-(\d{1,2})")
_DATE_MD = re.compile(r"(\d{1,2})\s*월\s*(\d{1,2})\s*일")
_DATE_SLASH = re.compile(r"\b(\d{1,2})/(\d{1,2})\b")
# 한쪽만 쓴 날짜. 전체 날짜를 지운 **뒤에** 본다 — 순서를 바꾸면 `9월 14일` 의 9 를 여기서
# 먼저 먹는다. `30일` 처럼 기간을 뜻하는 표기와 겹치지만, 그쪽은 라벨 숫자로 이미 허용된다.
_YEAR = re.compile(r"(\d{4})\s*년")
_MONTH = re.compile(r"(\d{1,2})\s*월")
_DAY = re.compile(r"(\d{1,2})\s*일")
# 숫자와 단위를 **순서대로** 뽑는다. 단위만 홀로 오는 `2천만` 의 `만` 도 토큰이어야 한다.


@dataclass(frozen=True)
class Verdict:
    """검증 결과. `kept` 를 이어 붙인 것이 사용자에게 나가는 본문이다."""

    kept: list[str]
    discarded: list[tuple[str, str]]  # (문장, 사유)

    @property
    def is_partial(self) -> bool:
        return bool(self.discarded)

    @property
    def content(self) -> str:
        return " ".join(self.kept)


def allowed_values(request: BriefingGenerationRequest) -> set[Decimal]:
    """본문에 나와도 되는 수. `metrics` 에서만 나온다."""
    allowed: set[Decimal] = set()
    for metric in request.metrics:
        allowed.add(Decimal(metric.value))
        allowed.add(abs(Decimal(metric.value)))  # "−420,000" 을 "42만원 부족" 으로 쓴다
        allowed |= numbers_in(metric.label)
        if metric.title:
            allowed |= numbers_in(metric.title)
        if metric.change_rate is not None:
            rate = Decimal(metric.change_rate)
            allowed |= {rate, abs(rate), abs(rate) * 100, rate * 100}
    return allowed


def label_numbers(request: BriefingGenerationRequest) -> set[Decimal]:
    """`label`·`title` **글자에** 든 수만 따로 모은다.

    날짜 자리 판정에 쓴다. `향후 30일` 의 30 은 기간이지 날짜가 아닌데 `_DAY` 에 걸리므로
    빠져나갈 구멍이 필요하다. 그렇다고 허용 수 전체를 열어 주면 `changeRate -0.12` 에서
    나온 12 가 `12월` 까지 통과시킨다 — 2026-09-11 에 실제로 걸렸다.

    **출처가 다르다.** 라벨은 사용자에게 그대로 보이는 문장이라 모델이 인용하는 것이
    자연스럽고, 비율·금액에서 파생된 수는 그렇지 않다.
    """
    found: set[Decimal] = set()
    for metric in request.metrics:
        found |= numbers_in(metric.label)
        if metric.title:
            found |= numbers_in(metric.title)
    return found


def allowed_dates(request: BriefingGenerationRequest) -> set[date]:
    return {m.date for m in request.metrics if m.date is not None}


def _check_dates(sentence: str, allowed: set[date], labels: set[Decimal]) -> str | None:
    """날짜를 **맥락으로** 본다. `9월 14일` 의 9·14 가 일반 숫자로 새면 안 된다.

    연·월·일 성분을 허용 *수* 집합에 넣지 않는다. 그렇게 하면 `changeRate -0.12` 때문에
    허용된 12 가 `12월` 까지 통과시킨다 — 2026-09-11 에 실제로 걸렸다. 숫자는 쓰인
    자리에 따라 뜻이 다르므로 날짜 자리는 날짜 자리에서만 판정한다.
    """
    years = {d.year for d in allowed}
    months = {d.month for d in allowed}
    days = {d.day for d in allowed}
    months_days = {(d.month, d.day) for d in allowed}

    for pattern in (_DATE_FULL, _DATE_ISO):
        for year, month, day in pattern.findall(sentence):
            try:
                parsed = date(int(year), int(month), int(day))
            except ValueError:
                return f"날짜로 읽을 수 없는 표기: {year}-{month}-{day}"
            if parsed not in allowed:
                return f"입력에 없는 날짜: {year}-{month}-{day}"

    masked = _DATE_ISO.sub(" ", _DATE_FULL.sub(" ", sentence))
    for month, day in _DATE_MD.findall(masked) + _DATE_SLASH.findall(masked):
        if (int(month), int(day)) not in months_days:
            return f"입력에 없는 날짜: {month}월 {day}일"

    # `9월 중순`·`25일 급여` 처럼 한쪽만 쓰는 것은 입력을 인용한 것이지 창작이 아니다.
    # 2026-09-11 평가에서 headline 폐기 2건이 전부 `9월` 이었다.
    #
    # **라벨 글자에 있던 수면 통과시킨다.** `향후 30일` 의 30 은 기간이지 날짜가 아니다.
    # 허용 수 전체를 열면 비율에서 나온 12 가 `12월` 까지 통과한다 (`label_numbers` 주석).
    bare = _DATE_MD.sub(" ", _DATE_SLASH.sub(" ", masked))
    for pattern, pool, unit in ((_YEAR, years, "년"), (_MONTH, months, "월"), (_DAY, days, "일")):
        for raw in pattern.findall(bare):
            if int(raw) not in pool and Decimal(raw) not in labels:
                return f"입력에 없는 날짜: {raw}{unit}"
    return None


def _strip_verified(sentence: str, allowed: set[Decimal]) -> tuple[str, str | None]:
    """검증이 끝난 날짜·비율을 지우고, 문제가 있으면 사유를 낸다."""
    masked = _DATE_FULL.sub(" ", sentence)
    masked = _DATE_ISO.sub(" ", masked)
    masked = _DATE_MD.sub(" ", masked)
    masked = _DATE_SLASH.sub(" ", masked)
    # 날짜 자리에서 이미 판정했다. 남겨 두면 `9월` 의 9 를 금액으로 다시 검사한다.
    masked = _YEAR.sub(" ", masked)
    masked = _MONTH.sub(" ", masked)
    masked = _DAY.sub(" ", masked)

    for raw in PERCENT.findall(masked):
        value = to_decimal(raw)
        if value is None or value not in allowed:
            return masked, f"입력에 없는 비율: {raw}%"
    return PERCENT.sub(" ", masked), None


def verify(request: BriefingGenerationRequest, content: str) -> Verdict:
    """본문을 문장으로 끊어 검사한다. 통과한 문장만 남긴다.

    배치 전체가 아니라 **문장 단위로 버린다** — 명세 20.1 의 "해당 항목만 제외" 와 같은
    태도다. 한 문장이 틀렸다고 나머지 브리핑까지 버릴 이유가 없다.
    """
    values = allowed_values(request)
    labels = label_numbers(request)
    dates = allowed_dates(request)

    kept: list[str] = []
    discarded: list[tuple[str, str]] = []

    for sentence in split_sentences(content):
        reason = _check_dates(sentence, dates, labels)
        if reason is None:
            masked, reason = _strip_verified(sentence, values)
        if reason is None:
            for match in NUMBER_EXPR.finditer(masked):
                value = to_decimal(match.group())
                if value is None:
                    reason = f"해석하지 못한 숫자 표기: {match.group()}"
                    break
                if value not in values:
                    reason = f"입력에 없는 수: {match.group()}"
                    break

        if reason is None:
            kept.append(sentence)
        else:
            discarded.append((sentence, reason))

    return Verdict(kept=kept, discarded=discarded)
