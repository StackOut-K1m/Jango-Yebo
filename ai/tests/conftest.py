from collections.abc import Iterator
from pathlib import Path

import pytest
from fastapi.testclient import TestClient
from pydantic import SecretStr

from app.config import Settings
from app.main import create_app

TOKEN = "test-service-token"
BACKEND = "http://backend.test"
HEADERS = {"Authorization": f"Bearer {TOKEN}", "X-Request-Id": "req-test-1"}


@pytest.fixture
def settings(tmp_path: Path) -> Settings:
    return Settings(
        _env_file=None,
        app_env="local",
        service_token=SecretStr(TOKEN),
        backend_base_url=BACKEND,
        storage_root=tmp_path,
        ai_pipeline_mode="stub",
        callback_backoff_seconds=0.0,
    )


@pytest.fixture
def client(settings: Settings) -> Iterator[TestClient]:
    with TestClient(create_app(settings)) as test_client:
        yield test_client
