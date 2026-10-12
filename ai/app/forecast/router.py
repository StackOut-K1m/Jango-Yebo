"""3번 예측 파트 엔드포인트 — 명세 20.11.

| Method | Endpoint                              | 처리 |
| POST   | /internal/v1/ai/forecast-residual     | **동기**, 15초 |

비동기가 아닌 이유 — 잔액 곡선 조회(10.1)가 이 값을 기다린다. Job·콜백을 끼우면
사용자가 화면에서 두 번 기다리게 된다. 대신 **실패는 예측 실패가 아니다**:
타임아웃·오류면 API 서버가 통계 베이스라인으로 곡선을 산출한다 (명세 20.1 ③).
그래서 여기서는 실패를 숨기지 않고 그대로 4xx/5xx 로 돌려준다.

**무거운 추론은 `app.state.compute_semaphore` 안에서만 돈다** — 명세 20.1 ④.
OCR 과 CPU 를 동시에 잡으면 API 응답까지 밀린다.
"""

from __future__ import annotations

import logging
from typing import Any

import anyio
import numpy as np
from fastapi import APIRouter, Depends, Request

from app.common.auth import require_service_token
from app.common.envelope import Envelope, ok
from app.common.errors import AppError, ErrorCode
from app.common.request_id import require_request_id
from app.forecast import features as ft
from app.forecast import service
from app.forecast.schemas import ForecastResidualRequest, ForecastResidualResponse, Series

log = logging.getLogger(__name__)

router = APIRouter(
    prefix="/internal/v1/ai",
    tags=["forecast"],
    dependencies=[Depends(require_service_token), Depends(require_request_id)],
)

# 규격이 허용하는 horizonDays. 응답 배열 길이와 일치해야 한다 (명세 20.11).
ALLOWED_HORIZONS = (7, 14, 30)


def _bad(message: str, **detail: Any) -> AppError:
    return AppError(ErrorCode.VALIDATION_FAILED, message, detail=detail or None)


def _pair(series: list[Series]) -> tuple[Series, Series]:
    """DEPOSIT·WITHDRAWAL 두 계열을 꺼내면서 정합성을 본다.

    두 계열의 시작일·길이가 다르면 **날짜 정렬이 어긋난 채로 계산된다.**
    지표로는 잘 안 잡히고 곡선만 조용히 틀리므로 여기서 막는다.
    """
    by_name = {s.name: s for s in series}
    missing = {"DEPOSIT", "WITHDRAWAL"} - by_name.keys()
    if missing:
        raise _bad("DEPOSIT·WITHDRAWAL 두 계열이 모두 필요합니다.", missing=sorted(missing))
    dep, wdr = by_name["DEPOSIT"], by_name["WITHDRAWAL"]
    if dep.start_date != wdr.start_date:
        raise _bad(
            "두 계열의 시작일이 같아야 합니다.",
            depositStartDate=dep.start_date.isoformat(),
            withdrawalStartDate=wdr.start_date.isoformat(),
        )
    if len(dep.values) != len(wdr.values):
        raise _bad(
            "두 계열의 길이가 같아야 합니다.",
            depositDays=len(dep.values),
            withdrawalDays=len(wdr.values),
        )
    return dep, wdr


@router.post("/forecast-residual", response_model=Envelope[ForecastResidualResponse])
async def forecast_residual(payload: ForecastResidualRequest, request: Request) -> dict[str, Any]:
    dep_s, wdr_s = _pair(payload.series)

    if len(dep_s.values) < ft.MIN_HISTORY:
        # 표본 부족은 요청 잘못이 아니라 **아직 예측할 수 없는 상태**다.
        # API 서버는 이걸 받으면 잔여 0 으로 두고 warnings 를 붙인다 (설계노트 표본 부족 처리).
        raise AppError(
            ErrorCode.VALIDATION_FAILED,
            f"과거 {ft.MIN_HISTORY}일 미만은 예측하지 않습니다.",
            status=422,
            detail={"contextDays": len(dep_s.values), "minHistory": ft.MIN_HISTORY},
        )
    if payload.horizon_days not in ALLOWED_HORIZONS:
        raise _bad(
            f"horizonDays 는 {'·'.join(map(str, ALLOWED_HORIZONS))} 중 하나여야 합니다.",
            horizonDays=payload.horizon_days,
        )
    if not service.is_loaded():
        raise AppError(ErrorCode.AI_ANALYSIS_FAILED, "예측 모델이 상주하지 않았습니다.")

    dep = np.asarray(dep_s.values, dtype=np.float32)
    wdr = np.asarray(wdr_s.values, dtype=np.float32)

    # 세마포어 1 — 이미지 인식 워커와 CPU 를 동시에 잡지 않는다 (명세 20.1 ④)
    async with request.app.state.compute_semaphore:
        try:
            result = await anyio.to_thread.run_sync(
                service.infer, dep, wdr, dep_s.start_date, payload.horizon_days
            )
        except AppError:
            raise
        except Exception:
            log.exception("잔여 예측 실패 contextDays=%d", len(dep_s.values))
            raise AppError(ErrorCode.AI_ANALYSIS_FAILED, "잔여 예측에 실패했습니다.") from None

    return ok(result)
