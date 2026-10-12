"""20.6 공고 구조화 라우트 — 접수·콜백·차단기.

계약 모양 자체는 `test_program_structuring_schema.py` 가 본다. 여기서는 **라우트에
붙어서 실제로 오가는지**를 본다 — 스키마 파일만 있으면 OpenAPI 에도 안 실리고
백엔드가 부를 수도 없다 (`-89` 완료 기준).
"""

import json

import httpx
import respx
from fastapi.testclient import TestClient

from app.common.model_meta import ModelMeta
from app.nlp.pipeline import ClassificationError, StubStructurer
from app.nlp.router import STRUCTURING_FEATURE
from app.nlp.schemas import Eligibility, ProgramStructuringResult
from tests.conftest import BACKEND, HEADERS, TOKEN

ENDPOINT = "/internal/v1/ai/program-structuring"
RESULT = "/internal/v1/ai-jobs/9014/result"

# docs/api/20_내부_AI워커.md §20.6 요청 예시 그대로
PAYLOAD = {
    "jobId": 9014,
    "programId": 1201,
    "rawText": "공고 원문 전문...",
    "sourceUrl": "https://www.bizinfo.go.kr/...",
}

META = ModelMeta(model_name="fake", model_version=None, prompt_version="program/v1", pipeline="LLM")


class _FixedStructurer:
    def __init__(self, result: ProgramStructuringResult) -> None:
        self._result = result

    async def structure(self, request):
        return self._result, META


class _FailingStructurer:
    def __init__(self, *, retryable: bool = False) -> None:
        self._retryable = retryable
        self.calls = 0

    async def structure(self, request):
        self.calls += 1
        raise ClassificationError("model", "모델 호출 실패", retryable=self._retryable)


def _post(client: TestClient, payload: dict | None = None):
    with respx.mock(base_url=BACKEND, assert_all_called=False) as mock:
        route = mock.post(RESULT).mock(return_value=httpx.Response(200, json={"success": True}))
        response = client.post(ENDPOINT, json=payload or PAYLOAD, headers=HEADERS)
    body = json.loads(route.calls.last.request.content) if route.called else None
    return response, route, body


# ---------------------------------------------------------------- 접수와 콜백


def test_stub_이_콜백까지_간다(client):
    response, route, body = _post(client)

    assert response.status_code == 202
    assert response.json() == {"success": True, "data": {"jobId": 9014}, "error": None}

    sent = route.calls.last.request
    assert sent.headers["X-Request-Id"] == "ai-job-9014-result"
    assert sent.headers["Authorization"] == f"Bearer {TOKEN}"

    assert body["success"] is True
    data = body["data"]
    assert data["status"] == "COMPLETED"
    assert data["pipeline"] == "STUB"
    assert data["supportType"] == "OTHER"


def test_stub_은_조건을_지어내지_않는다(client):
    """브리핑 stub 과 정반대 규칙이다. 그럴듯한 지역·업종을 채우면 연동 검증에서
    "모델이 공고를 읽었다" 로 오인된다."""
    client.app.state.structurer = StubStructurer()

    _, _, body = _post(client)
    eligibility = body["data"]["eligibility"]

    assert eligibility["regions"] == []
    assert eligibility["industries"] == []
    assert eligibility["maxAnnualRevenue"] is None
    assert eligibility["uncertainConditions"]  # 비어 있으면 "확실하다" 는 선언이 된다


def test_명세_17_2_구조가_그대로_콜백에_실린다(client):
    client.app.state.structurer = _FixedStructurer(
        ProgramStructuringResult(
            eligibility=Eligibility(
                regions=["광주"],
                max_annual_revenue=300_000_000,
                uncertain_conditions=["공동사업자 신청 가능 여부"],
            ),
            support_type="SUBSIDY",
            max_amount=3_000_000,
            apply_start="2026-09-01",
            apply_end="2026-09-19",
            required_documents=[{"documentName": "사업자등록증명", "issuer": "홈택스"}],
        )
    )

    _, _, body = _post(client)
    data = body["data"]

    assert data["supportType"] == "SUBSIDY"
    assert data["maxAmount"] == 3_000_000
    assert data["applyStart"] == "2026-09-01"
    assert data["eligibility"]["regions"] == ["광주"]
    assert data["eligibility"]["uncertainConditions"] == ["공동사업자 신청 가능 여부"]
    assert data["requiredDocuments"][0]["documentName"] == "사업자등록증명"


def test_partial_이_없다(client):
    """살릴 것과 버릴 것을 가르는 기준이 없으면 부분 성공이라는 상태도 성립하지 않는다.

    분류·브리핑은 폐기 기준이 입력에 있었지만(허용 카테고리 / 입력 지표의 숫자) 공고는
    원문이 자유 문장이라 "이 조건이 원문에 있었나" 를 대조할 수 없다.
    """
    _, _, body = _post(client)

    assert body["data"]["status"] == "COMPLETED"


# ---------------------------------------------------------------- 요청 검증


def test_원문이_비면_202_전에_거부한다(client):
    """Job 이 PROCESSING 에 남지 않게 Pydantic 이 접수 전에 끝낸다."""
    response = client.post(ENDPOINT, json={**PAYLOAD, "rawText": ""}, headers=HEADERS)

    assert response.status_code == 400


def test_원문_링크가_없으면_거부한다(client):
    payload = {k: v for k, v in PAYLOAD.items() if k != "sourceUrl"}
    response = client.post(ENDPOINT, json=payload, headers=HEADERS)

    assert response.status_code == 400


def test_토큰이_없으면_거부한다(client):
    response = client.post(ENDPOINT, json=PAYLOAD, headers={"X-Request-Id": "req-1"})

    assert response.status_code == 401


# ---------------------------------------------------------------- 실패와 차단기


def test_모델_실패는_콜백으로_전달된다(client):
    client.app.state.structurer = _FailingStructurer()

    response, _, body = _post(client)

    assert response.status_code == 202  # 접수는 이미 끝났다
    assert body["success"] is False
    assert body["error"]["code"] == "AI_ANALYSIS_FAILED"
    assert body["error"]["detail"]["stage"] == "model"


def test_재시도_불가_실패는_차단기를_세지_않는다(client):
    """400·404·401 을 세면 내 요청이 잘못됐을 때 60초 동안 기능이 꺼진다 (§8.6)."""
    client.app.state.structurer = _FailingStructurer(retryable=False)
    breaker = client.app.state.breakers.get(STRUCTURING_FEATURE)

    for _ in range(6):
        _post(client)

    assert breaker.allow()


def test_재시도_가능_실패가_쌓이면_차단기가_열린다(client):
    client.app.state.structurer = _FailingStructurer(retryable=True)
    breaker = client.app.state.breakers.get(STRUCTURING_FEATURE)

    for _ in range(5):
        _post(client)

    assert not breaker.allow()


def test_차단기가_열리면_모델을_부르지_않는다(client):
    failing = _FailingStructurer(retryable=True)
    client.app.state.structurer = failing
    for _ in range(5):
        _post(client)
    calls_before = failing.calls

    _, _, body = _post(client)

    assert failing.calls == calls_before  # 모델을 건너뛰었다
    assert body["success"] is False
    assert body["error"]["detail"]["circuit"] == "open"


def test_차단기가_열려도_콜백은_보낸다(client):
    """조용히 return 하면 Job 이 PROCESSING 에 남아 스위퍼가 10분을 기다린다 —
    차단기를 달아서 사용자 대기가 오히려 길어지는 셈이다."""
    client.app.state.structurer = _FailingStructurer(retryable=True)
    for _ in range(5):
        _post(client)

    _, route, _ = _post(client)

    assert route.called


def test_차단기는_기능별로_따로다(client):
    """§8.7 규칙 6 — "해당 AI 기능만 끄고 나머지는 살린다"."""
    client.app.state.structurer = _FailingStructurer(retryable=True)
    for _ in range(5):
        _post(client)

    assert not client.app.state.breakers.get(STRUCTURING_FEATURE).allow()
    assert client.app.state.breakers.get("transaction-classification").allow()
    assert client.app.state.breakers.get("briefing-generation").allow()
