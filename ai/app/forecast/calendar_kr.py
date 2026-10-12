"""공휴일·영업일 — 실제 통장을 흉내내려면 이게 있어야 한다.

명절은 소상공인 현금흐름을 가장 크게 흔드는 요인이다.
  · **직전 며칠은 대목** — 매출이 1.5~2배
  · **당일·다음날은 휴업** — 매출 0
  · **정산이 밀린다** — 연휴 동안 못 받고 다음 영업일에 몰려서 들어온다

이 셋이 없으면 데이터가 너무 규칙적이어서 어떤 모델이든 쉽게 맞춘다.
"""

from datetime import date, timedelta

# 2022~2026 주요 공휴일 (대체공휴일 포함)
#
# 데이터가 5년으로 늘면서 2022·2023 이 필요해졌다. 둘 다 **이미 지난 해**라
# 음력 공휴일이 확정값이다 — 미래로 늘리면 추정해야 한다. 그래서 START 를 뒤로 당겼다.
HOLIDAYS: set[date] = {
    date(2022, 1, 1),
    *(date(2022, 1, d) for d in (31,)),                 # 설날 연휴 시작
    *(date(2022, 2, d) for d in (1, 2)),                # 설날
    date(2022, 3, 1), date(2022, 3, 9),                 # 삼일절 · 대통령선거
    date(2022, 5, 5), date(2022, 5, 8),                 # 어린이날 · 석가탄신일
    date(2022, 6, 1), date(2022, 6, 6),                 # 지방선거 · 현충일
    date(2022, 8, 15),
    *(date(2022, 9, d) for d in (9, 10, 11, 12)),       # 추석 + 대체공휴일
    date(2022, 10, 3), date(2022, 10, 10),              # 개천절 · 한글날 대체
    date(2022, 12, 25),

    date(2023, 1, 1),
    *(date(2023, 1, d) for d in (21, 22, 23, 24)),      # 설날 + 대체공휴일
    date(2023, 3, 1),
    date(2023, 5, 5), date(2023, 5, 27), date(2023, 5, 29),   # 어린이날 · 석가탄신일 · 대체
    date(2023, 6, 6), date(2023, 8, 15),
    *(date(2023, 9, d) for d in (28, 29, 30)),          # 추석
    date(2023, 10, 2), date(2023, 10, 3), date(2023, 10, 9),  # 임시공휴일 · 개천절 · 한글날
    date(2023, 12, 25),

    date(2024, 1, 1),
    *(date(2024, 2, d) for d in (9, 10, 11, 12)),      # 설날
    date(2024, 3, 1), date(2024, 4, 10),
    date(2024, 5, 5), date(2024, 5, 6), date(2024, 5, 15),
    date(2024, 6, 6), date(2024, 8, 15),
    *(date(2024, 9, d) for d in (16, 17, 18)),          # 추석
    date(2024, 10, 3), date(2024, 10, 9), date(2024, 12, 25),

    date(2025, 1, 1),
    *(date(2025, 1, d) for d in (28, 29, 30)),          # 설날
    date(2025, 3, 3), date(2025, 5, 5), date(2025, 5, 6),
    date(2025, 6, 6), date(2025, 8, 15),
    date(2025, 10, 3),
    *(date(2025, 10, d) for d in (5, 6, 7, 8, 9)),      # 추석 + 한글날
    date(2025, 12, 25),

    date(2026, 1, 1),
    *(date(2026, 2, d) for d in (16, 17, 18)),          # 설날
    date(2026, 3, 1), date(2026, 3, 2),                  # 삼일절(일) + 대체공휴일
    date(2026, 5, 5),
    date(2026, 5, 24), date(2026, 5, 25),               # 부처님오신날(일) + 대체공휴일
    date(2026, 6, 6), date(2026, 8, 15),
    *(date(2026, 9, d) for d in (24, 25, 26)),          # 추석
    date(2026, 10, 3), date(2026, 10, 9), date(2026, 12, 25),
}

# 연휴가 3일 이상 이어지는 구간의 첫날 — 명절로 본다
def _long_breaks() -> list[tuple[date, date]]:
    days = sorted(HOLIDAYS)
    runs, cur = [], [days[0]]
    for d in days[1:]:
        if (d - cur[-1]).days <= 1:
            cur.append(d)
        else:
            runs.append(cur)
            cur = [d]
    runs.append(cur)
    return [(r[0], r[-1]) for r in runs if len(r) >= 3]


LONG_BREAKS = _long_breaks()


def is_holiday(d: date) -> bool:
    return d in HOLIDAYS


def is_business_day(d: date) -> bool:
    """일요일과 공휴일은 정산이 없다."""
    return d.weekday() != 6 and d not in HOLIDAYS


def next_business_day(d: date) -> date:
    while not is_business_day(d):
        d += timedelta(days=1)
    return d


def break_distance(d: date, cap: int = 14) -> tuple[float, float]:
    """다음 긴 연휴까지 · 직전 긴 연휴로부터 거리를 0~1 로 준다.

    0 이면 연휴에 붙어 있고 1 이면 멀다. `cap` 일을 넘으면 전부 1 이다.
    대목(직전)과 반동(직후)이 방향이 반대라 두 값을 따로 준다.
    """
    to = from_ = cap
    for start, end in LONG_BREAKS:
        if start <= d <= end:
            return 0.0, 0.0
        if d < start:
            to = min(to, (start - d).days)
        if d > end:
            from_ = min(from_, (d - end).days)
    return min(to, cap) / cap, min(from_, cap) / cap


def festive_factor(d: date) -> float:
    """명절 대목·휴업 계수.

    직전 3일은 1.4~1.9배, 연휴 당일은 0.15배(사실상 휴업),
    끝난 직후 이틀은 0.7배(대목 뒤 반동).
    """
    for start, end in LONG_BREAKS:
        if start <= d <= end:
            return 0.15
        lead = (start - d).days
        if 1 <= lead <= 3:
            return 1.9 - 0.15 * lead          # D-1 1.75 · D-2 1.60 · D-3 1.45
        after = (d - end).days
        if 1 <= after <= 2:
            return 0.7
    if d in HOLIDAYS:                          # 단일 공휴일
        return 0.55
    return 1.0
