import json

import httpx
import respx
from fastapi.testclient import TestClient

from app.main import create_app
from tests.conftest import BACKEND, HEADERS, TOKEN

ENDPOINT = "/internal/v1/ai/evidence-extraction"
RESULT = "/internal/v1/ai-jobs/9013/result"
PAYLOAD = {
    "jobId": 9013,
    "evidenceId": 501,
    "fileObjectKey": "evidences/11/501.jpg",
    "evidenceTypeHint": "RECEIPT",
    "preferredPipeline": "AUTO",
}


def test_stub_job_reaches_completed_via_callback(client):
    with respx.mock(base_url=BACKEND) as mock:
        route = mock.post(RESULT).mock(return_value=httpx.Response(200, json={"success": True}))
        response = client.post(ENDPOINT, json=PAYLOAD, headers=HEADERS)

    assert response.status_code == 202
    assert response.json() == {"success": True, "data": {"jobId": 9013}, "error": None}

    assert route.call_count == 1
    sent = route.calls.last.request
    assert sent.headers["X-Request-Id"] == "ai-job-9013-result"
    assert sent.headers["Authorization"] == f"Bearer {TOKEN}"

    body = json.loads(sent.content)
    assert body["success"] is True
    data = body["data"]
    assert data["status"] == "COMPLETED"
    assert data["pipeline"] == "STUB" and data["modelName"] == "stub"
    assert data["evidenceId"] == 501
    # v1.22 — 항상 null 이다. live 가 못 내므로 스텁도 내지 않는다(boundingBox 와 같은 이유).
    assert data["evidenceType"] is None
    assert data["supplierBusinessNo"] == "124-81-00998"
    assert data["issuedDate"] == "2026-08-30"
    # v1.16 — live 가 항상 null 이므로 스텁도 null 이어야 한다. 연동 픽스처가
    # 운영에 없는 좌표를 보여 주면 그 값에 맞춘 화면이 만들어진다.
    assert all(item["boundingBox"] is None for item in data["items"])
    assert data["validation"]["amountsConsistent"] is True
    assert data["validation"]["itemsSumConsistent"] is True
    assert data["validation"]["businessNoValid"] is True
    assert data["validation"]["needsReview"] is False
    assert data["validation"]["reviewReasons"] == []
    assert data["discountAmount"] is None and data["taxFreeAmount"] is None  # v1.10 확장 필드


def test_callback_retries_on_5xx(client):
    with respx.mock(base_url=BACKEND) as mock:
        route = mock.post(RESULT).mock(
            side_effect=[httpx.Response(503), httpx.Response(200, json={})]
        )
        client.post(ENDPOINT, json=PAYLOAD, headers=HEADERS)
    assert route.call_count == 2


def test_callback_4xx_is_not_retried(client):
    with respx.mock(base_url=BACKEND) as mock:
        route = mock.post(RESULT).mock(return_value=httpx.Response(409))
        response = client.post(ENDPOINT, json=PAYLOAD, headers=HEADERS)
    assert response.status_code == 202  # 접수는 이미 끝났다. 콜백 실패는 로그 + JobSweeper 회수
    assert route.call_count == 1


def test_live_mode_rejects_missing_file_before_accepting(settings):
    live = settings.model_copy(update={"ai_pipeline_mode": "live"})
    with (
        TestClient(create_app(live)) as client,
        respx.mock(base_url=BACKEND, assert_all_called=False) as mock,
    ):
        route = mock.post(RESULT)
        response = client.post(ENDPOINT, json=PAYLOAD, headers=HEADERS)
    assert response.status_code == 400
    assert response.json()["error"]["code"] == "VALIDATION_FAILED"
    assert not route.called


def test_live_mode_reports_unconfigured_ocr_pipeline(settings):
    live = settings.model_copy(update={"ai_pipeline_mode": "live"})
    with TestClient(create_app(live)) as client:
        payload = {**PAYLOAD, "preferredPipeline": "OCR_PIPELINE"}
        response = client.post(ENDPOINT, json=payload, headers=HEADERS)
    assert response.status_code == 422
    error = response.json()["error"]
    assert error["code"] == "AI_ANALYSIS_FAILED"
    assert error["detail"] == {"reason": "PIPELINE_UNAVAILABLE", "pipeline": "OCR_PIPELINE"}
