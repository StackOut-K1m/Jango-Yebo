"""모델 입력 규격 — 창 크기·채널 수·달력 특징. **학습과 서빙이 같은 함수를 쓴다.**

학습 쪽(`experiments/forecast_residual/dataset.py`)과 워커가 특징을 각각 만들면
조용히 어긋난다. 실제로 그 계열의 버그를 두 번 잡았다 (달력 오프셋·짧은 컨텍스트).
그래서 상수와 특징 생성을 여기 한 곳에 두고, 양쪽이 같은 값을 내는지 테스트로 묶는다
(`tests/forecast/test_features_match_dataset.py`).

    과거 입력  (LOOKBACK, PAST_CH) = (365, 10)  — 잔여입금·잔여출금 + 달력 8개
    미래 정보  (HORIZON,  FUT_CH)  = ( 30, 11)  — 달력 8개 + 작년 같은 날 3개
    출력       (HORIZON, 2, 3)                  — (입금·출금) × (q10·q50·q90)
"""

from __future__ import annotations

import math
from datetime import date, timedelta

import numpy as np

from app.forecast.calendar_kr import break_distance, is_business_day, is_holiday

# --- 창 크기 -----------------------------------------------------------------

LOOKBACK = 365
"""모델이 한 번에 보는 과거 일수.

규격(`docs/api/20_내부_AI워커.md` 20.11)이 컨텍스트 **180~512일을 권장**한다.
365 로 둔 이유는 **창이 t0 보다 정확히 1년 전에서 시작**하기 때문이다 —
창 앞머리 30일이 곧 「예측할 달의 작년 같은 달」이고, 1년치 변화가 한 창에 들어온다.

730일(2년)도 해봤는데 **더 나빴다.** GRU 가 730 스텝을 순차로 돌면서 앞쪽 정보가
뒤까지 못 가고, 통계 베이스라인 대비 +0.005 → −0.016 으로 뒤집혔다.
"""

HORIZON = 30
"""예측 일수. 규격이 `horizonDays` 를 **7·14·30 중 하나**로 고정한다 (응답 배열 길이와 일치)."""

LAG_DAYS = 365
"""「작년 같은 날」을 며칠 전에서 가져오나."""

MIN_HISTORY = 30
"""모델을 태우는 최소 과거 일수.

**계약도 30일이다** (2026-09-09 에 90 → 30 으로 내렸다 — `baseline.MIN_SAMPLE_DAYS`
설명 참고). 싸피 금융망 계좌는 프로젝트 중에 만든 것이라 실제로 30~60일밖에 없어서,
**서비스가 실제로 도는 구간이 바로 여기다.**

실측 근거는 `experiments/forecast_residual/context.py` 가 낸다 —
30일 컨텍스트에서 잔여=0(0.474) 대비 모델 0.664 · 통계 0.648 이었다.
**계약을 실제로 내릴지는 팀이 정한다 (미결).**
"""

# --- 채널 -------------------------------------------------------------------

CAL_CH = 8
"""달력 채널 수."""

LAG_CH = 3
"""작년 같은 날: 잔여입금 · 잔여출금 · **사용가능 플래그**.

플래그가 왜 필요한가 — 컨텍스트가 1년보다 짧으면 작년 값을 만들 수 없다.
그때 0 을 넣으면 모델이 **「작년에 매출 0」** 으로 읽는다.
「값이 없다」와 「값이 0 이다」는 다르므로 반드시 구분해서 알려준다.
"""

FUT_CH = CAL_CH + LAG_CH
PAST_CH = 2 + CAL_CH

CALENDAR_NAMES = (
    "요일sin", "요일cos", "월중sin", "월중cos",
    "공휴일", "영업일", "연휴전거리", "연휴후거리",
)
LAG_NAMES = ("작년입금", "작년출금", "작년사용가능")


# --- 특징 -------------------------------------------------------------------


def calendar_features(d: date) -> tuple[float, ...]:
    """달력 8개. 요일·월중일은 각도로, 나머지는 0~1 로.

    **공휴일은 예측할 대상이 아니라 이미 아는 값이다** — 12월 25일이 쉬는 날인 건
    달력을 펴면 나온다. 그래서 과거 시퀀스와 별개로 예측 구간의 달력을 따로 넣는다.
    이게 없으면 모델은 「앞으로 30일 중 어디가 연휴인지」를 알 방법이 없다.
    """
    w = d.weekday() / 7.0 * 2 * math.pi
    m = (d.day - 1) / 31.0 * 2 * math.pi
    to_break, from_break = break_distance(d)
    return (
        math.sin(w), math.cos(w),
        math.sin(m), math.cos(m),
        1.0 if is_holiday(d) else 0.0,
        1.0 if is_business_day(d) else 0.0,
        to_break, from_break,
    )


def calendar_block(start: date, days: int) -> np.ndarray:
    """(days, CAL_CH). `start` 부터 `days` 일."""
    return np.array(
        [calendar_features(start + timedelta(days=i)) for i in range(days)],
        dtype=np.float32,
    )


def lag_block(dep: np.ndarray, wdr: np.ndarray, scale: float, horizon: int = HORIZON) -> np.ndarray:
    """(horizon, LAG_CH). `dep`/`wdr` 는 창 시작일부터의 배열이고 길이가 곧 컨텍스트 일수다.

    예측 k 일째(창 시작 + len + k)의 작년 값은 인덱스 `len + k - LAG_DAYS` 다.
    범위를 벗어나면 0 으로 두고 플래그를 내린다 (`LAG_CH` 설명 참고).
    """
    have = len(dep)
    out = np.zeros((horizon, LAG_CH), dtype=np.float32)
    for k in range(horizon):
        j = have + k - LAG_DAYS
        if 0 <= j < have:
            out[k] = (dep[j] / scale, wdr[j] / scale, 1.0)
    return out


def window_scale(dep: np.ndarray, wdr: np.ndarray) -> float:
    """창 정규화 배수. **가게마다 금액 규모가 10배씩 차이나서** 그대로 넣으면 큰 가게만 학습된다.

    창 구간 평균으로 나눠 넣고 예측 뒤 다시 곱한다. 학습과 서빙이 이 함수를 같이 쓴다 —
    어긋나면 금액 규모가 다른 가게에서 값이 통째로 틀린다.
    """
    return max(float(dep.mean() + wdr.mean()), 1000.0)


def build_inputs(
    dep: np.ndarray, wdr: np.ndarray, start: date, horizon: int = HORIZON
) -> tuple[np.ndarray, np.ndarray, date, float]:
    """받은 잔여 시계열 → `(x, fut, t0, scale)`. 워커가 쓰는 단일 창 경로.

    `start` 는 `dep[0]` 의 날짜다. 컨텍스트가 `LOOKBACK` 보다 길면 **최근 쪽을 남긴다.**

    **`LOOKBACK` 이 아니라 실제로 받은 길이를 쓴다.** 규격이 짧은 컨텍스트를 허용하므로
    `LOOKBACK` 을 그대로 쓰면 `t0` 가 미래로 밀리고 달력이 통째로 어긋난다.
    `LOOKBACK` 이 90 이고 최소 표본이 90 이던 동안은 우연히 안 걸렸던 버그다.
    """
    offset = max(0, len(dep) - LOOKBACK)
    window_start = start + timedelta(days=offset)
    dep, wdr = dep[-LOOKBACK:], wdr[-LOOKBACK:]
    have = len(dep)
    t0 = window_start + timedelta(days=have)

    scale = window_scale(dep, wdr)
    past_cal = calendar_block(window_start, have)
    x = np.concatenate(
        [(dep / scale)[:, None], (wdr / scale)[:, None], past_cal], axis=1
    ).astype(np.float32)
    fut = np.concatenate(
        [calendar_block(t0, horizon), lag_block(dep, wdr, scale, horizon)], axis=1
    ).astype(np.float32)
    return x, fut, t0, scale
