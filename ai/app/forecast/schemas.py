"""3번 예측 파트 계약 스키마 — 명세 20.11 `forecast-residual`.

**금액과 날짜만 받는다.** 적요·거래처명·계좌번호는 요청에 담기지 않는다 (명세 20.1) —
추론에 필요하지 않고, 받으면 로그 금지 대상만 늘어난다.
"""

from __future__ import annotations

from datetime import date
from typing import Literal

from pydantic import Field, field_validator

from app.common.envelope import CamelModel

Direction = Literal["DEPOSIT", "WITHDRAWAL"]


class Series(CamelModel):
    """잔여 시계열 한 계열. `values[i]` 가 `start_date + i` 일의 잔여 합계다."""

    name: Direction
    start_date: date
    values: list[float]

    @field_validator("values")
    @classmethod
    def _finite_and_nonnegative(cls, v: list[float]) -> list[float]:
        # 잔여는 금액 합이라 음수가 나올 수 없다. NaN 이 들어오면 정규화가 통째로 깨진다.
        for x in v:
            if x != x or x in (float("inf"), float("-inf")):
                raise ValueError("values 에 NaN·무한값이 있습니다.")
            if x < 0:
                raise ValueError("잔여 합계는 음수가 될 수 없습니다.")
        return v


class Calendar(CamelModel):
    """API 서버가 알려주는 추가 공휴일. 비어 있으면 내장 달력만 쓴다."""

    holidays: list[date] = Field(default_factory=list)


class ForecastResidualRequest(CamelModel):
    series: list[Series]
    horizon_days: int = 30
    quantiles: list[float] = Field(default_factory=lambda: [0.1, 0.5, 0.9])
    calendar: Calendar | None = None


class QuantileForecast(CamelModel):
    """한 계열의 분위수 예측. `q10 ≤ q50 ≤ q90` 은 모델 구조가 보장한다."""

    name: Direction
    q10: list[int]
    q50: list[int]
    q90: list[int]


class ForecastResidualResponse(CamelModel):
    model_name: str
    model_version: str
    start_date: date
    """예측 첫날 — **입력 시계열 바로 다음 날**이다 (길이와 무관하게)."""
    context_days: int
    """실제로 쓴 과거 일수. 규격의 `confidence` 등급 판단에 쓴다
    (30일 미만 미적용 · 30~180일 강등)."""
    lag_available: bool
    """「작년 같은 날」을 만들 수 있었나. 컨텍스트가 1년 미만이면 False 다."""
    forecasts: list[QuantileForecast]
    judgment_alpha: float
    """**판정 보수성 α — 이 분위수들을 어떻게 읽어야 하는지.** 잔액 곡선을 만들어
    「0 아래냐」를 판정하는 것은 API 서버(Layer 1)이고, 그 판정을 어느 분위수로
    하느냐가 α 다.

        잔여입금 = q50 + α · (q10 − q50)      적게 들어온다고 본다
        잔여출금 = q50 + α · (q90 − q50)      많이 나간다고 본다

    **채택의 전제라서 응답에 담는다.** α 는 모델 상수가 아니라 **데이터에 딸린 값**이고,
    모델 경로와 통계 폴백 경로에 똑같이 적용되므로 α 를 바꾸면 「모델이 통계보다
    낫다」는 판정 자체가 바뀐다. 실측에서 운영 구간의 게이트는 **α=0.00 에서만**
    통과했다 (`service.JUDGMENT_ALPHA` 참고). 계약 문서가 아니라 응답에 실어야
    재학습해도 값이 모델과 어긋나지 않는다."""
    inference_ms: int
