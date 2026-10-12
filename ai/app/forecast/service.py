"""예측 추론 — 모델 상주와 단일 창 추론. 라우터는 이걸 스레드로 부른다.

지켜야 할 것 넷. 전부 `docs/예측엔진_설계노트.md` 「서버 예산」과 「함정」에서 왔다.

  · 모델은 **기동 시 한 번 올려 상주**시킨다 — 요청마다 로드하면 첫 응답이 수십 초다
  · **torch 스레드 수를 고정**한다 — 가변이면 부동소수점 합산 순서가 달라져 재현성이 깨진다
  · **세마포어 1 로 직렬화**한다 — OCR 과 동시에 CPU 를 잡으면 API 응답까지 밀린다
    (라우터가 `app.state.compute_semaphore` 를 쓴다. 여기서 따로 만들지 않는다)
  · **금액과 날짜만** 다룬다 — 적요·거래처명은 받지도 로그에 남기지도 않는다
"""

from __future__ import annotations

import logging
import pathlib
import time
from datetime import date

import numpy as np
import torch

from app.forecast import features as ft
from app.forecast.model import (
    ARTIFACT,
    ResidualForecaster,
    StaleArtifact,
    artifact_meta,
    load_trained,
)
from app.forecast.schemas import (
    ForecastResidualResponse,
    QuantileForecast,
)

log = logging.getLogger(__name__)

MODEL_NAME = "residual-gru"
MODEL_VERSION = "0.3.0"
"""아티팩트 구조·학습 방식이 바뀌면 올린다. 명세 20.1 ①이 응답에 이 값을 요구하고,
API 서버가 `FORECAST_RUN.model_version` 에 기록해 재현성을 보장한다.

  0.1.0  과거 90일 · 30일 뭉치 출력
  0.2.0  과거 365일 · **하루씩 예측** · 작년 같은 날 채널
  0.3.0  학습 데이터에 **요일별 매출 배수**가 들어갔다 (구조는 0.2.0 과 같다)

**여기에 학습 데이터 지문을 덧붙여 응답한다** (`model_version()`). 구조가 같아도
데이터가 바뀌면 가중치가 달라지는데 버전 문자열이 같으면 계약의 재현성 보증이
**조용히 깨진다.** 실제로 요일 배수를 넣고 재학습했을 때 그럴 뻔했다."""

JUDGMENT_ALPHA = 0.00
"""판정 보수성 α. **채택의 전제이고 계약(20.11)에 나간다** — S15P21C105-65.

α 는 이 워커가 쓰지 않는다. 분위수를 그대로 내보내고, 곡선을 만들어 「0 아래냐」를
판정하는 것은 API 서버(Layer 1)다. 그 판정을 어느 분위수로 하느냐가 α 다.

    잔여입금 = q50 + α · (q10 − q50)      적게 들어온다고 본다
    잔여출금 = q50 + α · (q90 − q50)      많이 나간다고 본다

**적어 두지 않으면 API 서버가 자연히 q50(α=0)을 쓴다.** 지금 값이 마침 0 이라 사고가
안 났을 뿐이고, 「공백 경보니까 안전하게」라며 하한 쪽으로 올리는 것이 훨씬 자연스러운
선택이다. 그런데 그러면 **모델을 채택한 근거가 사라진다.**

실측 (`private/현재/forecast-lab/audit/alpha_gate.py` · 창 9,600개 · 데이터 a82bc981).
운영 구간(과거 30일~1년)에서 모델 − 통계 베이스라인:

    α=0.00   +0.0162 [+0.0080, +0.0244] 유의        게이트 통과
    α=0.05   +0.0055 [-0.0027, +0.0131] 유의차 없음   게이트 탈락
    α=0.10   -0.0096 [-0.0169, -0.0027] 유의하게 나쁨  게이트 탈락

α 를 올리는 것은 **정밀도를 팔아 경보를 앞당기는 거래**다 (모델 기준 0.00 → 0.05 에서
최초 부족일 오차 9.14 → 7.63일, 정밀도 0.708 → 0.637 — 양쪽 다 유의하다). 그 거래를
하고 싶으면 **채택 판정을 다시 돌려야 하고**, 지금 숫자로는 그때 쓸 것은 통계 쪽이다.

**모델 상수가 아니라 데이터에 딸린 값이다.** 옛 데이터에서 정한 0.10 을 새 데이터에
그대로 썼다가 공백 F1 이 0.687 → 0.612 로 깎인 적이 있다. **재학습하면 다시 잰다.**

*표본 하한과 묶여 있다.* 과거 7~30일 구간은 α=0.00 이면 게이트를 떨어지고 0.05 면
통과한다. 지금은 `features.MIN_HISTORY=30` 이라 그 구간이 호출되지 않아 문제가 없지만,
**하한을 내리면 α 도 같이 다시 정해야 한다.**"""

_model: ResidualForecaster | None = None
_version: str = MODEL_VERSION
_alpha: float = JUDGMENT_ALPHA


def judgment_alpha() -> float:
    """응답에 나가는 α. 아티팩트가 자기 값을 갖고 있으면 그것을 쓴다.

    **가중치와 α 는 같이 움직인다.** 재학습하면 최적 α 가 옮겨갈 수 있으므로,
    아티팩트 안에 있으면 코드 상수보다 그쪽을 믿는다 — 지문(`data_fingerprint`)을
    믿는 것과 같은 이유다.
    """
    return _alpha


def model_version() -> str:
    """응답에 실제로 나가는 버전 — `0.3.0+a82bc981` 처럼 학습 데이터 지문이 붙는다.

    아티팩트에 지문이 없으면(옛 아티팩트) 기본 버전만 돌려준다.
    """
    return _version


def load(threads: int = 2, path: str | pathlib.Path = ARTIFACT) -> ResidualForecaster:
    """모델을 올려 상주시킨다. 기동 시 한 번만 부른다.

    아티팩트가 지금 코드 설정과 다르면 `StaleArtifact` 를 그대로 올린다 —
    **조용히 틀린 숫자를 내는 것보다 기동 실패가 낫다.**

    응답 버전에 **학습 데이터 지문**을 붙인다. 아티팩트에 지문이 없으면(옛 아티팩트)
    기본 버전만 쓴다 — 지문이 없다고 기동을 막을 이유는 없다.
    """
    global _model, _version, _alpha
    torch.set_num_threads(threads)
    _model = load_trained(path)
    meta = artifact_meta(path)
    fp = meta.get("data_fingerprint")
    _version = f"{MODEL_VERSION}+{fp[:8]}" if fp else MODEL_VERSION
    _alpha = float(meta.get("judgment_alpha", JUDGMENT_ALPHA))
    log.info("예측 모델 상주 — %s %s (threads=%d · 판정 α=%.2f)",
             MODEL_NAME, _version, threads, _alpha)
    return _model


def unload() -> None:
    global _model, _version, _alpha
    _model = None
    _version = MODEL_VERSION
    _alpha = JUDGMENT_ALPHA


def is_loaded() -> bool:
    return _model is not None


def infer(dep: np.ndarray, wdr: np.ndarray, start: date, horizon: int) -> ForecastResidualResponse:
    """받은 컨텍스트(최대 `LOOKBACK` 일)를 보고 앞으로 `horizon` 일의 분위수를 낸다.

    정규화는 학습과 **같은 함수**(`features.build_inputs`)를 쓴다. 여기가 어긋나면
    금액 규모가 다른 가게에서 값이 통째로 틀린다.
    """
    if _model is None:
        raise RuntimeError("예측 모델이 상주하지 않았습니다 (service.load 를 먼저 부릅니다).")

    began = time.perf_counter()
    x, fut, t0, scale = ft.build_inputs(dep, wdr, start, ft.HORIZON)
    with torch.no_grad():
        out = _model(torch.from_numpy(x[None]), torch.from_numpy(fut[None])).numpy()[0]
    out = out[:horizon] * scale

    def axis(i: int, name: str) -> QuantileForecast:
        return QuantileForecast(
            name=name,
            q10=[int(v) for v in out[:, i, 0]],
            q50=[int(v) for v in out[:, i, 1]],
            q90=[int(v) for v in out[:, i, 2]],
        )

    return ForecastResidualResponse(
        model_name=MODEL_NAME,
        model_version=_version,
        start_date=t0,
        context_days=min(len(dep), ft.LOOKBACK),
        lag_available=bool(fut[:, ft.CAL_CH + 2].any()),
        forecasts=[axis(0, "DEPOSIT"), axis(1, "WITHDRAWAL")],
        judgment_alpha=_alpha,
        inference_ms=int((time.perf_counter() - began) * 1000),
    )


__all__ = [
    "JUDGMENT_ALPHA",
    "MODEL_NAME",
    "MODEL_VERSION",
    "StaleArtifact",
    "infer",
    "is_loaded",
    "judgment_alpha",
    "load",
    "model_version",
    "unload",
]
