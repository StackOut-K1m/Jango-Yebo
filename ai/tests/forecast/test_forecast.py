"""3번 예측 파트 — 제품 코드 계약 테스트.

**이 파일은 자립해야 한다.** 더미 데이터(`datasets/residual.csv`)도, 학습된 아티팩트
(`artifacts/residual-gru.pt`)도 없이 돌아야 한다. 둘 다 .gitignore 라서 CI 에는 존재하지 않고,
있어야만 도는 테스트는 결국 아무도 안 돌리게 된다.

그래서 모델이 필요한 곳에서는 **시드를 고정한 새 모델**을 쓴다. 재현성·분위수 순서·날짜
정렬은 **학습된 가중치와 무관한 성질**이라 그걸로 충분하다.

학습·더미 생성에 대한 실험 테스트는 여기 없다 — `private/현재/forecast-lab/` 에 있다.
"""

import pathlib
from datetime import date, timedelta
from decimal import Decimal

import numpy as np
import pytest

# **torch 없는 환경에서도 `pytest` 가 통째로 깨지지 않아야 한다.**
# 1·2번(NLP·OCR) 파트는 `forecast` extra 를 설치하지 않는다 (pyproject 참고).
pytest.importorskip("torch", reason="forecast extra 미설치 — uv sync --extra forecast")

import torch  # noqa: E402

from app.forecast import features as ft  # noqa: E402
from app.forecast import service  # noqa: E402
from app.forecast.baseline import forecast_residual  # noqa: E402
from app.forecast.model import ResidualForecaster  # noqa: E402
from app.forecast.residual import (  # noqa: E402
    DEPOSIT,
    WITHDRAWAL,
    Transaction,
    aggregate_residual,
)
from tests.forecast.fixture import build_history  # noqa: E402

START = date(2025, 1, 1)
DAYS = 200
SEED = 20260907


# --------------------------------------------------------------------- 픽스처


@pytest.fixture(autouse=True)
def _상주모델_초기화():
    """`service` 는 **모듈 전역**에 모델을 들고 있다.

    한 테스트가 `service._model` 에 올려둔 것이 다음 테스트로 새면 **순서에 따라 결과가
    달라진다.** 실제로 그랬다 — 「가중치를 못 읽어도 미상주가 된다」가 앞 테스트의
    잔여물 때문에 200 을 받았다 (S15P21C105-76 에서 잡았다).
    """
    yield
    service.unload()


@pytest.fixture(scope="module")
def model():
    """**시드를 고정한 새 모델.** 학습 아티팩트에 기대지 않는다.

    여기서 보는 성질(재현성·분위수 순서·날짜 정렬)은 가중치 값과 무관하다.
    학습된 모델의 성능은 `private/현재/forecast-lab/evaluate.py` 가 잰다.
    """
    torch.set_num_threads(4)
    torch.manual_seed(SEED)
    return ResidualForecaster().eval()


def synth(n: int = ft.LOOKBACK, seed: int = 0) -> tuple[np.ndarray, np.ndarray]:
    """잔여 입금·출금 n 일치. 요일 리듬을 흉내내되 값 자체는 중요하지 않다."""
    rng = np.random.default_rng(seed)
    day = np.arange(n)
    weekly = 1.0 + 0.4 * np.sin(day / 7.0 * 2 * np.pi)
    dep = (rng.random(n) * 400_000 + 100_000) * weekly
    wdr = (rng.random(n) * 300_000 + 80_000) * weekly
    return dep.astype(np.float32), wdr.astype(np.float32)


@pytest.fixture(scope="module")
def window():
    """(x, fut) 한 배치. **제품 경로(`features.build_inputs`)로 만든다** —
    학습 쪽 창 자르기와 어긋나면 여기서 먼저 드러난다."""
    xs, fs = [], []
    for i in range(8):
        dep, wdr = synth(seed=i)
        x, fut, _, _ = ft.build_inputs(dep, wdr, START)
        xs.append(x)
        fs.append(fut)
    return torch.from_numpy(np.stack(xs)), torch.from_numpy(np.stack(fs))


# --------------------------------------------------------------------- 재현성


def test_잔여집계가_두번_같다():
    txs, matched = build_history(START, DAYS)
    end = START + timedelta(days=DAYS - 1)
    a = aggregate_residual(txs, matched, START, end)
    b = aggregate_residual(txs, matched, START, end)
    assert a.deposit == b.deposit and a.withdrawal == b.withdrawal


def test_통계베이스라인이_두번_같다():
    txs, matched = build_history(START, DAYS)
    end = START + timedelta(days=DAYS - 1)
    s = aggregate_residual(txs, matched, START, end)
    d1, w1 = forecast_residual(s, end + timedelta(days=1), 30)
    d2, w2 = forecast_residual(s, end + timedelta(days=1), 30)
    assert (d1.q10, d1.q50, d1.q90) == (d2.q10, d2.q50, d2.q90)
    assert (w1.q10, w1.q50, w1.q90) == (w2.q10, w2.q50, w2.q90)


def test_모델추론이_두번_비트단위로_같다(model, window):
    """명세 13.2 — 같은 입력이면 같은 답이어야 한다."""
    x, f = window
    with torch.no_grad():
        a = model(x, f)
        b = model(x, f)
    assert torch.equal(a, b)


def test_워커가_스레드수를_고정한다():
    """가변이면 부동소수점 합산 순서가 달라져 재현성이 깨진다 (명세 13.2)."""
    from app.config import Settings

    assert isinstance(Settings().forecast_threads, int)
    assert Settings().forecast_threads > 0


# --------------------------------------------------------------------- 잔여 집계


def test_예정거래에_매칭된_건_잔여에서_빠진다():
    """**잔여 = 실제 − 예정거래 매칭분.** 빼기라서 이중계상이 구조적으로 불가능하다."""
    t = Transaction(1, START, Decimal("50000"), DEPOSIT)
    end = START
    without = aggregate_residual([t], set(), START, end)
    with_match = aggregate_residual([t], {1}, START, end)
    assert without.deposit[0] == Decimal("50000")
    assert with_match.deposit[0] == Decimal("0")


def test_예측제외_거래는_잔여로도_안_넘어간다():
    t = Transaction(1, START, Decimal("50000"), WITHDRAWAL, is_excluded=True)
    s = aggregate_residual([t], set(), START, START)
    assert s.withdrawal[0] == Decimal("0")


def test_거래없는_날은_비지_않고_0이다():
    """날짜가 빠지면 뒤의 값이 하루씩 밀린다 — 달력 정렬이 통째로 어긋난다."""
    t = Transaction(1, START, Decimal("1000"), DEPOSIT)
    end = START + timedelta(days=4)
    s = aggregate_residual([t], set(), START, end)
    assert len(s.deposit) == 5
    assert all(v == Decimal("0") for v in s.deposit[1:])


# --------------------------------------------------------------------- 표본 부족


def _forecast_after(days: int):
    """`days` 일치 기록으로 잔여 예보를 낸다."""
    txs, matched = build_history(START, days)
    end = START + timedelta(days=days - 1)
    s = aggregate_residual(txs, matched, START, end)
    return forecast_residual(s, end + timedelta(days=1), 30)[0]


def test_표본_30일미만이면_모델을_태우지_않는다():
    """계약(`docs/예측엔진_설계노트.md` 표본 부족 처리) — 잔여 0 + warnings.

    하한 아래에서는 **근거 없는 숫자를 내느니 「모른다」를 낸다.** 곡선은 예정 거래만으로도
    나오므로 화면이 비지 않는다.
    """
    d = _forecast_after(20)
    assert d.confidence == "UNAVAILABLE"
    assert all(v == Decimal("0") for v in d.q50)
    assert d.warnings


def test_표본_30에서_90일_사이는_값을_내되_신뢰도를_낮춘다():
    """**2026-09-09 에 하한을 90 → 30 으로 내렸다.** 이 구간이 그 변경의 전부다.

    실측(합성 데이터, 창 9,600개)에서 30~89일 구간은 이랬다.

        잔여 0 (옛 계약)   공백 F1 0.457   최초 부족일 오차 19.4일
        잔여 예측 모델             0.676                   9.28일

    옛 계약은 여기서 `UNAVAILABLE` 을 냈다. 그러면 **모델뿐 아니라 통계까지 같이 막혀**
    오차가 두 배가 된다. 싸피 금융망 계좌는 기록이 30~60일이라 **서비스가 실제로 도는
    구간이 바로 여기다.**
    """
    d = _forecast_after(60)
    assert d.confidence == "DEGRADED", "값은 내되 신뢰도는 낮춰야 한다"
    assert any(v > Decimal("0") for v in d.q50), "옛 계약처럼 0 을 내면 안 된다"


def test_표본이_충분하면_신뢰도가_정상이다():
    d = _forecast_after(200)
    assert d.confidence == "NORMAL"


# --------------------------------------------------------------------- 분위수


def test_분위수_순서가_뒤집히지_않는다(model, window):
    """구조로 보장했다(q50 을 먼저 내고 위아래로 벌린다). 구조를 건드리면 여기가 먼저 터진다."""
    x, f = window
    with torch.no_grad():
        p = model(x, f).numpy()
    assert (p[..., 0] <= p[..., 1] + 1e-6).all(), "q10 이 q50 보다 크다"
    assert (p[..., 1] <= p[..., 2] + 1e-6).all(), "q50 이 q90 보다 크다"
    assert (p >= 0).all(), "잔여는 금액 합이라 음수가 나올 수 없다"


# --------------------------------------------------------------------- 날짜 정렬


def test_달력_오프셋이_밀리지_않는다(model):
    """**2026-09-05 에 잡은 버그의 회귀 테스트.**

    입력을 자른 뒤에 오프셋을 재면 항상 0 이 나와 달력 특징이 통째로 밀렸다.
    30일 밀리면 요일이 2칸 어긋나 주 단위 패턴이 전부 틀어진다.
    지표로는 안 잡혔고 눈으로 찾았다.

    불변식: **예측은 입력 시계열 바로 다음 날부터 시작한다.**
    """
    service._model = model
    n = ft.LOOKBACK + 30
    dep, wdr = synth(n, seed=11)
    out = service.infer(dep, wdr, START, 30)
    assert out.start_date == START + timedelta(days=n)


def test_컨텍스트가_LOOKBACK보다_짧아도_날짜가_안_밀린다(model):
    """**2026-09-07 에 잡은 버그의 회귀 테스트.**

    `t0`·달력 길이를 상수 `LOOKBACK` 으로 계산해서, 받은 컨텍스트가 그보다 짧으면
    예측 시작일이 미래로 밀리고 배열 길이가 안 맞아 터졌다.
    `LOOKBACK` 이 90 이고 최소 표본이 90 이던 동안은 우연히 안 걸렸다.

    불변식: **길이와 무관하게 입력 바로 다음 날부터 시작한다.**
    """
    service._model = model
    for n in (ft.MIN_HISTORY, 45, 95, 180, 300, ft.LOOKBACK, ft.LOOKBACK + 60):
        dep, wdr = synth(n, seed=n)
        out = service.infer(dep, wdr, START, 30)
        assert out.start_date == START + timedelta(days=n), f"n={n}"
        assert out.context_days == min(n, ft.LOOKBACK)


# ------------------------------------------------------------------- 버전 문자열


def _checkpoint(tmp: pathlib.Path, model, fingerprint: str | None,
                alpha: float | None = None) -> pathlib.Path:
    """`load()` 가 읽을 최소 아티팩트. **가중치는 아무거나 좋다** — 여기서 보는 건 버전이다."""
    from app.forecast.model import train_config

    ck: dict = {"state_dict": model.state_dict(), "config": train_config()}
    if fingerprint is not None:
        ck["data_fingerprint"] = fingerprint
    if alpha is not None:
        ck["judgment_alpha"] = alpha
    out = tmp / "ck.pt"
    torch.save(ck, out)
    return out


def test_응답버전에_학습데이터_지문이_붙는다(model, tmp_path):
    """계약 20.11 은 「동일 입력 + 동일 `modelVersion` → 동일 결과」를 요구한다.

    구조를 안 바꾸고 데이터만 바꿔 재학습하면 `MODEL_VERSION` 은 그대로인데
    가중치는 달라진다. **버전이 같으면 그 보증이 조용히 깨진다** — 실제로 요일 배수를
    넣고 재학습했을 때 그럴 뻔했다. 그래서 지문을 붙인다.
    """
    try:
        service.load(2, _checkpoint(tmp_path, model, "a82bc981fc806bdc"))
        assert service.model_version() == f"{service.MODEL_VERSION}+a82bc981"

        dep, wdr = synth(ft.LOOKBACK + 30, seed=7)
        assert service.infer(dep, wdr, START, 30).model_version == service.model_version()
    finally:
        service.unload()
    assert service.model_version() == service.MODEL_VERSION


def test_지문없는_옛_아티팩트로도_기동한다(model, tmp_path):
    """지문이 없다고 기동을 막을 이유는 없다 — 버전만 밋밋해진다."""
    try:
        service.load(2, _checkpoint(tmp_path, model, None))
        assert service.model_version() == service.MODEL_VERSION
    finally:
        service.unload()


def test_워커가_같은_요청에_같은_답을_한다(model):
    service._model = model
    dep, wdr = synth(ft.LOOKBACK + 30, seed=3)
    a = service.infer(dep, wdr, START, 30)
    b = service.infer(dep, wdr, START, 30)
    # `inference_ms` 는 텔레메트리다 — 호출마다 다른 게 정상이다.
    # 계약(명세 13.2)이 요구하는 재현성은 **예측 숫자**에 대한 것이다.
    skip = {"inference_ms"}
    assert a.model_dump(exclude=skip) == b.model_dump(exclude=skip)


# --------------------------------------------------------- 작년 같은 날 (lag-365)


def test_작년_같은_날_채널이_정말_작년_값이다():
    """lag 가 엉뚱한 날을 가리키면 지표로는 안 잡히고 조용히 나빠진다."""
    n = ft.LOOKBACK
    dep, wdr = synth(n, seed=7)
    x, fut, t0, scale = ft.build_inputs(dep, wdr, START)
    for k in range(ft.HORIZON):
        j = n + k - ft.LAG_DAYS          # 예측 k 일째의 작년 같은 날
        assert 0 <= j < n
        assert abs(fut[k, ft.CAL_CH] - dep[j] / scale) < 1e-5
        assert abs(fut[k, ft.CAL_CH + 1] - wdr[j] / scale) < 1e-5
        assert fut[k, ft.CAL_CH + 2] == 1.0


def test_짧은_컨텍스트면_작년채널_플래그가_내려간다(model):
    """플래그가 안 내려가면 모델이 0 을 **「작년에 매출 0」** 으로 읽는다 — 없는 것과 다르다."""
    n = 200                                   # 1년보다 짧다
    dep, wdr = synth(n, seed=8)
    _, fut, _, _ = ft.build_inputs(dep, wdr, START)
    assert not fut[:, ft.CAL_CH:ft.CAL_CH + ft.LAG_CH].any()

    service._model = model
    out = service.infer(dep, wdr, START, 30)   # 터지지 않아야 한다
    assert out.lag_available is False
    assert [f.name for f in out.forecasts] == ["DEPOSIT", "WITHDRAWAL"]
    assert all(len(f.q50) == 30 for f in out.forecasts)


def test_계약이_허용하는_모든_길이에서_돈다(model):
    """계약은 컨텍스트 30~512일을 받는다. `LOOKBACK` 을 바꿔도 GRU 는 가변 길이라
    **에러 없이 그냥 돌기 때문에**, 최소한 「깨지지 않는다」를 못으로 박아둔다."""
    for n in (ft.MIN_HISTORY, 45, 60, 90, 120, 180, 240, 300, ft.LOOKBACK):
        dep, wdr = synth(n, seed=n)
        x, fut, _, _ = ft.build_inputs(dep, wdr, START)
        with torch.no_grad():
            o = model(torch.from_numpy(x[None]), torch.from_numpy(fut[None]))
        assert o.shape == (1, ft.HORIZON, 2, 3), n
        assert (o[..., 0] <= o[..., 1] + 1e-6).all(), n
        assert (o[..., 1] <= o[..., 2] + 1e-6).all(), n
        assert torch.isfinite(o).all(), n


# ------------------------------------------------------- 가중치가 없는 배포 (S15P21C105-76)


def test_가중치를_못_읽어도_워커는_뜨고_예측만_미상주가_된다(monkeypatch, tmp_path):
    """가중치는 이미지에 굽지 않고 **볼륨으로 붙인다** (S15P21C105-76 · `ai/artifacts/` 는
    커밋 금지라 빌드 컨텍스트에 없다).

    그래서 마운트가 빠지거나 파일 권한이 틀리면 기동 중 `load()` 가 실패한다. 그걸
    **기동 실패로 만들면 3번 파트의 파일 하나 때문에 1번(NLP)·2번(OCR)까지 내려간다.**
    예측만 「모델 미상주」로 떨어지고 워커는 계속 떠야 한다.
    """
    from fastapi.testclient import TestClient
    from pydantic import SecretStr

    from app.config import Settings
    from app.main import create_app

    def 마운트_없음(*_a, **_k):
        raise FileNotFoundError("artifacts/residual-gru.pt")

    service.unload()
    monkeypatch.setattr(service, "load", 마운트_없음)

    settings = Settings(
        _env_file=None,
        app_env="local",
        service_token=SecretStr("t"),
        backend_base_url="http://backend.test",
        storage_root=tmp_path,
        ai_pipeline_mode="stub",
        callback_backoff_seconds=0.0,
        forecast_mode="live",
    )

    days = [0.0] * ft.MIN_HISTORY
    body = {
        "series": [
            {"name": "DEPOSIT", "startDate": "2026-01-01", "values": days},
            {"name": "WITHDRAWAL", "startDate": "2026-01-01", "values": days},
        ],
        "horizonDays": 30,
    }
    headers = {"Authorization": "Bearer t", "X-Request-Id": "req-76"}

    with TestClient(create_app(settings)) as client:
        assert client.get("/health").status_code == 200, "워커가 죽었다"
        res = client.post("/internal/v1/ai/forecast-residual", json=body, headers=headers)

    # 404 가 아니어야 한다 — 라우터는 등록돼 있고, BE 는 「기능 없음」이 아니라
    # 「지금 못 한다」를 받아야 한다 (구현현황 3.3 의 3상태와 같은 구분).
    assert res.status_code != 404
    assert res.status_code >= 500
    assert not service.is_loaded()


# ------------------------------------------------- 판정 보수성 α (S15P21C105-65)


def test_응답이_판정_알파를_싣는다(model, tmp_path):
    """**α 를 계약에 안 실으면 BE 가 자연히 q50(α=0)을 쓴다.**

    지금 값이 마침 0.00 이라 사고가 안 났을 뿐이다. 「공백 경보니까 안전하게」라며
    하한 쪽으로 올리는 게 훨씬 자연스러운 선택인데, 그러면 **모델을 채택한 근거가
    사라진다** — 운영 구간 게이트가 α=0.00 에서만 통과한다
    (`audit/alpha_gate.py`: 0.00 +0.0162 유의 / 0.05 유의차 없음 / 0.10 유의하게 나쁨).
    """
    service.load(2, _checkpoint(tmp_path, model, "a82bc981fc806bdc"))
    assert service.judgment_alpha() == service.JUDGMENT_ALPHA

    dep, wdr = synth(ft.LOOKBACK + 30, seed=11)
    out = service.infer(dep, wdr, START, 30)
    assert out.judgment_alpha == service.JUDGMENT_ALPHA
    # 계약이 camelCase 로 나가는지 — BE 가 읽는 이름이다
    assert "judgmentAlpha" in out.model_dump(by_alias=True)


def test_아티팩트가_가진_알파가_코드_상수를_이긴다(model, tmp_path):
    """**가중치와 α 는 같이 움직인다.** 재학습하면 최적 α 가 옮겨갈 수 있고, 그때
    코드 상수가 남아 있으면 **옛 α 를 새 가중치에 씌운다.** 실제로 옛 데이터의 0.10 을
    새 데이터에 그대로 썼다가 공백 F1 이 0.687 → 0.612 로 깎인 적이 있다.
    """
    service.load(2, _checkpoint(tmp_path, model, "deadbeef00000000", alpha=0.05))
    assert service.judgment_alpha() == 0.05

    dep, wdr = synth(ft.LOOKBACK + 30, seed=12)
    assert service.infer(dep, wdr, START, 30).judgment_alpha == 0.05


def test_알파는_모델을_내리면_기본값으로_돌아온다(model, tmp_path):
    """상주 모델을 내렸는데 옛 α 가 남아 있으면 다음 모델에 그 값이 새어 간다."""
    service.load(2, _checkpoint(tmp_path, model, None, alpha=0.20))
    assert service.judgment_alpha() == 0.20
    service.unload()
    assert service.judgment_alpha() == service.JUDGMENT_ALPHA
