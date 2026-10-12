import pytest
from fastapi.testclient import TestClient
from pydantic import SecretStr

from app.config import Settings
from app.main import app, create_app


def test_health_is_public_and_uses_envelope():
    response = TestClient(app).get("/health")
    assert response.status_code == 200
    body = response.json()
    assert body["success"] is True
    assert body["error"] is None
    assert body["data"]["status"] == "UP"
    assert body["data"]["pipelineMode"] in {"stub", "live"}


@pytest.fixture
def no_ambient_credential(monkeypatch: pytest.MonkeyPatch) -> None:
    """SDK 가 환경에서 읽는 경로를 지운다 — 개발자 셸의 키가 테스트 결과를 바꾸면 안 된다."""
    monkeypatch.delenv("ANTHROPIC_API_KEY", raising=False)
    monkeypatch.delenv("ANTHROPIC_AUTH_TOKEN", raising=False)


def _health(settings: Settings) -> dict:
    with TestClient(create_app(settings)) as client:
        return client.get("/health").json()["data"]


def test_live_without_credential_is_degraded(no_ambient_credential: None):
    """자격 증명이 빠진 채로 배포되면 **요청이 오기 전에** 드러나야 한다.

    2026-09-09~15 서버 ai.env 누락 사건이 이 테스트의 이유다.
    """
    data = _health(Settings(_env_file=None, app_env="local", ai_pipeline_mode="live"))

    assert data["status"] == "DEGRADED"
    assert data["degraded"] == ["nlp", "ocr"]


def test_live_with_api_key_is_up(no_ambient_credential: None):
    data = _health(
        Settings(
            _env_file=None,
            app_env="local",
            ai_pipeline_mode="live",
            anthropic_api_key=SecretStr("sk-ant-test"),
        )
    )

    assert data["status"] == "UP"
    assert data["degraded"] == []


def test_gateway_auth_token_counts_as_credential(no_ambient_credential: None):
    """게이트웨이(GMS 등)는 api_key 없이 auth_token 만 쓴다. 이걸 못 보면 거짓 경보가 난다."""
    data = _health(
        Settings(
            _env_file=None,
            app_env="local",
            ai_pipeline_mode="live",
            anthropic_auth_token=SecretStr("gateway-token"),
        )
    )

    assert data["status"] == "UP"


def test_env_credential_counts_when_settings_empty(monkeypatch: pytest.MonkeyPatch):
    """설정에 없어도 SDK 는 환경에서 읽는다. 해석 순서를 claude.py 와 맞춘다."""
    monkeypatch.delenv("ANTHROPIC_AUTH_TOKEN", raising=False)
    monkeypatch.setenv("ANTHROPIC_API_KEY", "sk-ant-from-env")
    data = _health(Settings(_env_file=None, app_env="local", ai_pipeline_mode="live"))

    assert data["status"] == "UP"


def test_stub_mode_does_not_need_credential(no_ambient_credential: None):
    """stub 은 Claude 를 부르지 않는다 — 키가 없다고 DEGRADED 로 찍으면 개발 환경이 늘 빨갛다."""
    data = _health(Settings(_env_file=None, app_env="local", ai_pipeline_mode="stub"))

    assert data["status"] == "UP"
    assert data["degraded"] == []
