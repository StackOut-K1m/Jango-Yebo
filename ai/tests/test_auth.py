from fastapi.testclient import TestClient

from app.main import create_app
from tests.conftest import HEADERS, TOKEN

NORMALIZE = "/internal/v1/ai/item-normalization"
BODY = {"businessId": 11, "knownItems": [], "rawNames": ["우유"]}


def test_health_needs_no_auth(client):
    assert client.get("/health").status_code == 200


def test_missing_token_is_401_envelope(client):
    response = client.post(NORMALIZE, json=BODY, headers={"X-Request-Id": "r1"})
    assert response.status_code == 401
    body = response.json()
    assert body["success"] is False
    assert body["data"] is None
    assert body["error"]["code"] == "INTERNAL_AUTH_FAILED"


def test_wrong_token_is_401(client):
    headers = {"Authorization": "Bearer nope", "X-Request-Id": "r1"}
    assert client.post(NORMALIZE, json=BODY, headers=headers).status_code == 401


def test_missing_request_id_is_400(client):
    response = client.post(NORMALIZE, json=BODY, headers={"Authorization": f"Bearer {TOKEN}"})
    assert response.status_code == 400
    assert response.json()["error"]["code"] == "VALIDATION_FAILED"


def test_request_id_is_echoed(client):
    response = client.post(NORMALIZE, json=BODY, headers=HEADERS)
    assert response.status_code == 200
    assert response.headers["X-Request-Id"] == HEADERS["X-Request-Id"]


def test_unset_service_token_rejects_everyone(settings):
    unset = settings.model_copy(update={"service_token": None})
    with TestClient(create_app(unset)) as client:
        assert client.post(NORMALIZE, json=BODY, headers=HEADERS).status_code == 401


def test_non_utf8_body_is_still_an_envelope(client):
    # FastAPI 는 UTF-8 이 아닌 본문에 HTTPException(400, "There was an error parsing the body")
    # 를 던진다 — 기본 {"detail": ...} 가 아니라 Envelope 으로 나가야 한다
    headers = {**HEADERS, "Content-Type": "application/json"}
    body = b'{"businessId":11,"rawNames":["\xbf\xec\xc0\xaf"]}'  # cp949 bytes
    response = client.post(NORMALIZE, content=body, headers=headers)
    assert response.status_code == 400
    assert response.json()["error"]["code"] == "VALIDATION_FAILED"
    assert response.headers["X-Request-Id"] == HEADERS["X-Request-Id"]


def test_unknown_path_is_an_envelope(client):
    response = client.get("/internal/v1/ai/nope")
    assert response.status_code == 404
    assert response.json()["success"] is False


def test_validation_error_uses_envelope_without_input_echo(client):
    response = client.post(NORMALIZE, json={"businessId": 11, "rawNames": []}, headers=HEADERS)
    assert response.status_code == 400
    error = response.json()["error"]
    assert error["code"] == "VALIDATION_FAILED"
    assert error["detail"]["errors"]
    assert all("input" not in e for e in error["detail"]["errors"])
