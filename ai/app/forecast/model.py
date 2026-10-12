"""잔여 예측 모델 — GRU 로 과거 1년을 읽고 앞으로 30일의 분위수를 **하루씩** 낸다.

**왜 분위수를 내나** — 곡선의 min/expected/max 세 계열과 1:1로 맞아야 한다.
평균 하나만 내면 "범위"를 다시 지어내야 하고, 그건 근거가 없다.

**분위수가 뒤집히면 안 된다.** q10 이 q50 보다 크면 곡선이 무너진다.
그래서 q50 을 먼저 내고 거기서 위아래로 벌리는 방식으로 구조를 잡았다 —
학습이 어떻게 되든 순서가 구조적으로 보장된다.

    q50 = softplus(a)          ≥ 0   (잔여는 금액 합이라 음수가 없다)
    q10 = q50 · sigmoid(b)     0 ≤ q10 ≤ q50
    q90 = q50 + softplus(c)    q90 ≥ q50
"""

import pathlib

import torch
import torch.nn as nn
import torch.nn.functional as F

QUANTILES = (0.1, 0.5, 0.9)

# 학습 아티팩트. artifacts/ 는 .gitignore 다 — 레포에 올리지 않는다.
# train.py 가 여기에 저장하고 워커가 여기서 읽는다.
ARTIFACT = pathlib.Path(__file__).resolve().parents[2] / "artifacts" / "residual-gru.pt"


class ResidualForecaster(nn.Module):
    """과거 시계열을 GRU 로 한 번 요약하고, **그 요약을 30일에 복제해 하루씩 예측한다.**

    ## 왜 하루씩인가

    예전 구조는 `[GRU 상태 64 + 미래정보 96] = 160` 차원에서 `30일 × 2 × 3 = 180` 개를
    **한 번에** 뽑았다. 그러면 하루하루의 요일·작년값이 **공유 벡터 하나를 통과해야** 한다 —
    12월 25일이 쉬는 날이라는 정보가 나머지 29일과 같은 160차원에 뭉개진다.

    그 구조로는 **요일 중앙값(20줄짜리 통계)조차 못 이겼다.** 실측:

        가게별 상수 하나(과거 평균)   공백 F1 0.700
        요일별 분위수 (통계)          공백 F1 0.722
        신경망 101,108개              공백 F1 0.706   ← 통계보다 낮다

    상수 하나와 0.006 차이라는 건 **모델이 평균 내는 것 이상을 못 배웠다**는 뜻이다.

    지금 구조는 이렇다.

        문맥 = f(과거 365일)                       한 번만 계산
        하루 k 예측 = g(문맥, 그날 달력·작년값, k)   **날마다 같은 g 를 적용**

    `g` 의 가중치를 30일이 공유한다(time-distributed). 그래서

      · 각 날이 **자기 요일·자기 작년값을 직접** 쓴다 — 뭉개지지 않는다
      · 「이런 날은 이렇다」 는 규칙을 30일이 함께 학습한다 — 표본이 30배로 늘어난 셈이다
      · 예측 지평 `k` 를 넣어 **먼 날일수록 범위를 넓힐 수** 있다

    파라미터는 88,390 개로 이전(101,108)보다 **적다.** 용량을 늘려서가 아니라
    **어디에 쓰는지를 바꿔서** 나아지는지를 봐야 하기 때문이다.
    """

    def __init__(self, horizon: int = 30, in_ch: int = 10, cal_ch: int = 11,
                 hidden: int = 64, ctx: int = 128, day: int = 192,
                 lookback_ref: int = 365):
        # cal_ch=11 — 달력 8 + 작년 같은 날 3 (dataset.FUT_CH)
        super().__init__()
        self.horizon = horizon
        self.lookback_ref = lookback_ref      # 컨텍스트 길이를 0~1 로 만들 때의 기준
        self.gru = nn.GRU(in_ch, hidden, num_layers=1, batch_first=True)
        self.ctx = nn.Sequential(nn.Linear(hidden, ctx), nn.ReLU())
        # +2 = 예측 지평 위치(k/29) + **컨텍스트 길이**(받은 일수 / LOOKBACK).
        #
        # 길이를 알려줘야 한다. 30일치와 365일치는 전혀 다른 조건인데
        # 모델이 그걸 모르면 양쪽에서 어중간해진다 —
        # 「30일밖에 못 봤으니 범위를 넓히자」를 배울 수가 없다.
        self.day = nn.Sequential(
            nn.Linear(ctx + cal_ch + 2, day), nn.ReLU(),
            nn.Linear(day, day), nn.ReLU(),
            nn.Linear(day, 2 * 3),                  # (입금·출금) × (a·b·c)
        )

    def forward(self, x: torch.Tensor, future_cal: torch.Tensor) -> torch.Tensor:
        """(B, 365, 10) + (B, 30, 11) → (B, 30, 2, 3)  마지막 축이 q10·q50·q90"""
        b, hz, _ = future_cal.shape
        _, h = self.gru(x)
        ctx = self.ctx(h[-1])                                   # (B, ctx)

        # 문맥을 30일에 복제하고 **그날의 정보 · 지평 위치 · 컨텍스트 길이**를 붙인다
        pos = torch.linspace(0.0, 1.0, hz, device=x.device, dtype=x.dtype)
        pos = pos.view(1, hz, 1).expand(b, hz, 1)
        clen = torch.full((b, hz, 1), x.shape[1] / float(self.lookback_ref),
                          device=x.device, dtype=x.dtype)
        per_day = torch.cat([ctx.unsqueeze(1).expand(b, hz, -1), future_cal, pos, clen], dim=2)

        raw = self.day(per_day).view(b, hz, 2, 3)

        q50 = F.softplus(raw[..., 1])
        q10 = q50 * torch.sigmoid(raw[..., 0])
        q90 = q50 + F.softplus(raw[..., 2])
        return torch.stack([q10, q50, q90], dim=-1)


def pinball_loss(pred: torch.Tensor, target: torch.Tensor) -> torch.Tensor:
    """분위수 손실.

    q50 이면 위아래 벌칙이 같아 중앙값을 배우고,
    q90 이면 과소예측에 9배 벌칙이라 위쪽을 배운다.
    MSE 로는 이게 안 된다 — 평균 하나만 나온다.
    """
    losses = []
    for i, q in enumerate(QUANTILES):
        err = target - pred[..., i]
        losses.append(torch.maximum(q * err, (q - 1) * err))
    return torch.stack(losses).mean()


def cumulative_loss(pred: torch.Tensor, target: torch.Tensor) -> torch.Tensor:
    """**누적 잔액 오차.**

    잔고는 누적값이다. 하루 오차가 작아도 방향이 한쪽으로 치우치면
    30일 쌓이면서 곡선이 통째로 밀리고, 그러면 공백 판정일이 어긋난다.
    pinball 은 하루하루만 보므로 이 편향을 잡지 못한다.

    그래서 **q50 의 누적 순증감**이 실제 누적과 맞도록 직접 벌준다.
    q10·q90 에는 걸지 않는다 — 분위수의 의미가 흐려진다.
    """
    pred_net = pred[..., 0, 1] - pred[..., 1, 1]      # (B, H) 입금 q50 − 출금 q50
    true_net = target[..., 0] - target[..., 1]
    err = torch.cumsum(pred_net, dim=1) - torch.cumsum(true_net, dim=1)
    return err.abs().mean() / pred.shape[1]           # 하루 단위 크기로 되돌린다


def combined_loss(pred, target, lam: float = 0.5) -> torch.Tensor:
    return pinball_loss(pred, target) + lam * cumulative_loss(pred, target)


def count_params(model: nn.Module) -> int:
    return sum(p.numel() for p in model.parameters())


# --------------------------------------------------------------- 아티팩트 로딩

def train_config() -> dict:
    """지금 코드가 만드는 창 설정. 체크포인트에 같이 저장해 나중에 대조한다."""
    from app.forecast.features import CAL_CH, FUT_CH, HORIZON, LAG_DAYS, LOOKBACK, PAST_CH
    return {"arch": "per-day-v2-ctxlen", "lookback": LOOKBACK, "horizon": HORIZON,
            "past_ch": PAST_CH, "fut_ch": FUT_CH, "cal_ch": CAL_CH, "lag_days": LAG_DAYS}


class StaleArtifact(RuntimeError):
    """체크포인트가 지금 설정과 안 맞는다."""


def load_trained(path: "str | pathlib.Path" = ARTIFACT) -> "ResidualForecaster":
    """학습된 모델을 불러오면서 **학습 때 창 설정과 지금 설정이 같은지 확인한다.**

    **왜 필요한가** — `LOOKBACK` 을 바꿔도 GRU 는 가변 길이를 받고
    `future` 층의 크기는 `HORIZON × FUT_CH` 라 창 길이와 무관하다.
    그래서 **730일로 학습한 모델에 365일을 먹여도 에러가 하나도 안 난다.**
    테스트도 통과하고 평가도 돌아가는데 숫자만 틀린다 — 가장 나쁜 실패 방식이다.
    실제로 2026-09-07 에 그렇게 될 뻔했다.
    """
    import torch as _t
    ck = _t.load(path, map_location="cpu")
    want, got = train_config(), ck.get("config")
    if got != want:
        diff = ", ".join(f"{k}: 학습 {got.get(k) if got else '기록없음'} → 지금 {v}"
                         for k, v in want.items() if not got or got.get(k) != v)
        raise StaleArtifact(
            f"{path} 가 지금 설정과 다르다 — train.py 를 다시 돌린다.  ({diff})")
    m = ResidualForecaster()
    m.load_state_dict(ck["state_dict"])
    m.eval()
    return m


def artifact_meta(path: "str | pathlib.Path" = ARTIFACT) -> dict:
    """아티팩트의 **가중치가 아닌 것**들 — 창 설정 · 학습 데이터 지문 · 검증 손실.

    `modelVersion` 에 데이터 지문을 붙이는 데 쓴다. 계약(20.11)이
    「동일 입력 + 동일 `modelVersion` → 동일 결과」를 요구하는데,
    **재학습해서 가중치가 바뀌었는데 버전 문자열이 같으면 그 보증이 조용히 깨진다.**
    """
    import torch as _t

    ck = _t.load(path, map_location="cpu")
    return {k: v for k, v in ck.items() if k != "state_dict"}
