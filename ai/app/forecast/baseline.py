"""통계 베이스라인 곡선 — S15P21C105-67 (표본 분기는 -68 의 시작)

seasonal naive + 요일 median. 요일별로 과거 잔여를 모아 분위수를 낸다.

**이건 임시방편이 아니다.** 모델 채택 게이트의 기준선이자 영구 폴백이다.
모델이 다운스트림 지표(공백 F1 · 최초 부족일 오차)에서 이걸 못 이기면 채택하지 않고,
채택해도 이 경로는 지우지 않는다. 모델이 죽거나 사용자가 토글을 끄면 여기로 돌아온다.

출력이 q10/q50/q90 인 이유 — 곡선의 balance_min/expected/max 3계열과 1:1로 대응한다.
지금까지 통계로 지어내던 폭을 근거 있는 값으로 바꾸는 게 목적이다.
"""

import math
from dataclasses import dataclass
from datetime import date, timedelta
from decimal import ROUND_HALF_UP, Decimal

from app.forecast.residual import ResidualSeries

MIN_SAMPLE_DAYS = 30
"""잔여를 추정하기 시작하는 하한. **워커의 `features.MIN_HISTORY` 와 같은 값이다.**

2026-09-09 에 90 → 30 으로 내렸다. 90 은 「표본이 적으면 근거 없는 숫자를 내느니
모른다고 하자」는 원칙에서 나온 값인데, **그 원칙은 맞고 90 이라는 값이 틀렸다.**
실측(합성 데이터, 창 9,600개)에서 30~89일 구간은 이랬다.

    잔여 0 (옛 계약이 강제)   공백 F1 0.457   최초 부족일 오차 19.4일
    통계 (요일 중앙값)                0.652                   9.30일
    잔여 예측 모델                    0.676                   9.28일

30~89일의 추정은 「근거 없는 숫자」가 아니다. 오차가 **19.4일 → 9.3일로 절반
이하**가 된다. 그리고 이 구간을 막으면 모델뿐 아니라 **통계까지 같이 막힌다** —
둘 다 못 쓰고 0.457 로 떨어진다.

**두 하한이 하나로 합쳐졌다.** 이전에는 워커 30 · 통계 90 이라 그 사이 구간에서
통계만 침묵하는 비대칭이 있었다.
"""

DEGRADED_SAMPLE_DAYS = 180
"""여기 미만이면 `confidence` 를 한 단계 내린다. **하한을 내린 만큼 강등 구간이
넓어졌다** (30~179일) — 값을 내되 「덜 믿어라」를 같이 말한다.
"""

NORMAL = "NORMAL"
DEGRADED = "DEGRADED"
UNAVAILABLE = "UNAVAILABLE"


@dataclass(frozen=True)
class ResidualForecast:
    """horizon 일수만큼의 잔여 분위수."""

    start_date: date
    q10: list[Decimal]
    q50: list[Decimal]
    q90: list[Decimal]
    confidence: str
    warnings: list[str]
    model_name: str = "weekday-median"
    model_version: str = "0.1"

    def __len__(self) -> int:
        return len(self.q50)


def _percentile(values: list[Decimal], p: float) -> Decimal:
    """선형 보간 분위수. numpy 기본값과 같은 방식이라 나중에 갈아끼워도 값이 안 바뀐다."""
    if not values:
        return Decimal("0")
    s = sorted(values)
    if len(s) == 1:
        return s[0]
    k = (len(s) - 1) * p
    lo, hi = math.floor(k), math.ceil(k)
    if lo == hi:
        return s[int(k)]
    weight = Decimal(str(k - lo))
    return (s[lo] * (1 - weight) + s[hi] * weight).quantize(
        Decimal("1"), rounding=ROUND_HALF_UP
    )


def _by_weekday(series_values: list[Decimal], start_date: date) -> dict[int, list[Decimal]]:
    """요일(0=월 … 6=일)별로 값을 모은다."""
    buckets: dict[int, list[Decimal]] = {w: [] for w in range(7)}
    for i, v in enumerate(series_values):
        buckets[(start_date + timedelta(days=i)).weekday()].append(v)
    return buckets


def _forecast_axis(
    values: list[Decimal],
    start_date: date,
    forecast_from: date,
    horizon_days: int,
) -> tuple[list[Decimal], list[Decimal], list[Decimal]]:
    buckets = _by_weekday(values, start_date)
    table = {
        w: (
            _percentile(vals, 0.10),
            _percentile(vals, 0.50),
            _percentile(vals, 0.90),
        )
        for w, vals in buckets.items()
    }
    q10, q50, q90 = [], [], []
    for i in range(horizon_days):
        lo, mid, hi = table[(forecast_from + timedelta(days=i)).weekday()]
        q10.append(lo)
        q50.append(mid)
        q90.append(hi)
    return q10, q50, q90


def forecast_residual(
    series: ResidualSeries,
    forecast_from: date,
    horizon_days: int = 30,
) -> tuple[ResidualForecast, ResidualForecast]:
    """잔여 입금·출금 예보를 만든다.

    표본이 부족하면 모델을 태우지 않고 0 을 낸다. 근거 없는 숫자를 내느니
    "모른다"를 내는 편이 낫다 — 곡선은 예정 거래만으로도 나온다.
    """
    days = len(series)
    warnings: list[str] = []

    if days < MIN_SAMPLE_DAYS:
        warnings.append(
            f"과거 데이터가 {days}일뿐이라 잔여 예측을 적용하지 않았습니다 "
            f"(최소 {MIN_SAMPLE_DAYS}일)."
        )
        zero = [Decimal("0")] * horizon_days
        blank = ResidualForecast(
            start_date=forecast_from,
            q10=list(zero),
            q50=list(zero),
            q90=list(zero),
            confidence=UNAVAILABLE,
            warnings=warnings,
        )
        return blank, blank

    confidence = NORMAL
    if days < DEGRADED_SAMPLE_DAYS:
        confidence = DEGRADED
        warnings.append(
            f"과거 데이터가 {days}일이라 신뢰도를 한 단계 낮췄습니다 "
            f"({DEGRADED_SAMPLE_DAYS}일 이상 권장)."
        )

    d10, d50, d90 = _forecast_axis(series.deposit, series.start_date, forecast_from, horizon_days)
    w10, w50, w90 = _forecast_axis(
        series.withdrawal, series.start_date, forecast_from, horizon_days
    )

    deposit = ResidualForecast(
        start_date=forecast_from,
        q10=d10, q50=d50, q90=d90,
        confidence=confidence,
        warnings=list(warnings),
    )
    withdrawal = ResidualForecast(
        start_date=forecast_from,
        q10=w10, q50=w50, q90=w90,
        confidence=confidence,
        warnings=list(warnings),
    )
    return deposit, withdrawal


def compose_balance(
    opening_balance: Decimal,
    planned_delta: list[Decimal],
    residual_deposit: ResidualForecast,
    residual_withdrawal: ResidualForecast,
) -> tuple[list[Decimal], list[Decimal], list[Decimal]]:
    """Layer 1 회계 항등식 — 잔액 = 시작잔액 + Σ입금 − Σ지출 (날짜별 누적).

    **운영에서는 이 함수가 Java 쪽에 있다.** 여기 둔 것은 잔여 예보가 곡선을
    실제로 어떻게 바꾸는지 눈으로 보기 위한 것뿐이다.

    모델 출력을 인자로 받는다는 점이 중요하다 — 함수 안에서 추론을 부르면
    "동일 입력에 동일 결과" 인수 조건이 그 순간 깨진다.

    min 은 잔여 입금 q10 · 출금 q90 (가장 나쁜 조합),
    max 는 잔여 입금 q90 · 출금 q10 (가장 좋은 조합)으로 만든다.
    """
    horizon = len(planned_delta)
    lo = mid = hi = opening_balance
    balance_min, balance_expected, balance_max = [], [], []

    for i in range(horizon):
        lo += planned_delta[i] + residual_deposit.q10[i] - residual_withdrawal.q90[i]
        mid += planned_delta[i] + residual_deposit.q50[i] - residual_withdrawal.q50[i]
        hi += planned_delta[i] + residual_deposit.q90[i] - residual_withdrawal.q10[i]
        balance_min.append(lo)
        balance_expected.append(mid)
        balance_max.append(hi)

    return balance_min, balance_expected, balance_max
