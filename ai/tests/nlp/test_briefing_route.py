"""20.7 브리핑 라우트 — 접수·콜백·PARTIAL·전부 폐기.

검증 규칙 자체는 `test_briefing_hallucination.py` 가 본다. 여기서는 그 판정이
**콜백 status 로 이어지는지**를 본다.
"""

import json

import httpx
import respx
from fastapi.testclient import TestClient

from app.common.model_meta import ModelMeta
from app.nlp.pipeline import ClassificationError, StubBriefer
from app.nlp.router import BRIEFING_FEATURE, FEATURE
from app.nlp.schemas import BriefingGenerationResult
from tests.conftest import BACKEND, HEADERS, TOKEN

ENDPOINT = "/internal/v1/ai/briefing-generation"
RESULT = "/internal/v1/ai-jobs/9015/result"

# docs/api/20_내부_AI워커.md §20.7 요청 예시 그대로
PAYLOAD = {
    "jobId": 9015,
    "briefingId": 91,
    "periodType": "WEEKLY",
    "metrics": [
        {"label": "화요일 정산 입금", "value": 748000, "changeRate": -0.12},
        {"label": "향후 30일 최저잔액", "value": -420000, "date": "2026-09-14"},
        {"label": "다음 대형 지출", "value": 3000000, "date": "2026-09-14", "title": "급여"},
    ],
    "toneGuide": "확정 표현 금지, 조언은 권장 수준, 입력에 없는 수치 생성 금지",
}

META = ModelMeta(
    model_name="fake", model_version=None, prompt_version="briefing/v1", pipeline="LLM"
)


class _FixedBriefer:
    def __init__(self, headline: str, content: str) -> None:
        self._result = BriefingGenerationResult(headline=headline, content=content)

    async def write(self, request):
        return self._result, META


class _FailingBriefer:
    def __init__(self, *, retryable: bool = False) -> None:
        self._retryable = retryable
        self.calls = 0

    async def write(self, request):
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
    assert response.json() == {"success": True, "data": {"jobId": 9015}, "error": None}

    sent = route.calls.last.request
    assert sent.headers["X-Request-Id"] == "ai-job-9015-result"
    assert sent.headers["Authorization"] == f"Bearer {TOKEN}"

    assert body["success"] is True
    data = body["data"]
    assert data["status"] == "COMPLETED"
    assert data["pipeline"] == "STUB"
    assert data["headline"] and data["content"]


def test_stub_은_지표를_인용해_검증을_통과한다(client):
    """stub 이 숫자를 지어내면 연동 검증에서 PARTIAL 이 떠 원인을 찾느라 시간을 버린다."""
    client.app.state.briefer = StubBriefer()

    _, _, body = _post(client)

    assert body["data"]["status"] == "COMPLETED"


# ---------------------------------------------------------------- PARTIAL


def test_환각_문장이_있으면_PARTIAL_이다(client):
    client.app.state.briefer = _FixedBriefer(
        headline="이번 주 요약",
        content="정산금이 748,000원 들어왔습니다. 재료비가 18.1% 올랐습니다.",
    )

    _, _, body = _post(client)

    assert body["success"] is True, "PARTIAL 은 실패가 아니다 — 남은 문장은 쓸 수 있다"
    data = body["data"]
    assert data["status"] == "PARTIAL"
    assert data["content"] == "정산금이 748,000원 들어왔습니다."
    assert "18.1" not in data["content"]
    # 어느 프롬프트가 환각을 냈는지 알아야 한다
    assert data["promptVersion"] == "briefing/v1"


def test_환각이_없으면_COMPLETED_다(client):
    client.app.state.briefer = _FixedBriefer(
        headline="이번 주 요약",
        content="정산금이 748,000원으로 12% 줄었습니다. 9월 14일을 주의하는 것이 좋겠습니다.",
    )

    _, _, body = _post(client)

    assert body["data"]["status"] == "COMPLETED"


def test_headline_의_환각은_중립_제목으로_바꾼다(client):
    """headline 은 필수라 비울 수 없다. 숫자도 판단도 없는 라벨로 바꾸고 PARTIAL 로 알린다."""
    client.app.state.briefer = _FixedBriefer(
        headline="매출이 4.2% 늘었습니다",
        content="정산금이 748,000원 들어왔습니다.",
    )

    _, _, body = _post(client)

    data = body["data"]
    assert data["headline"] == "이번 주 자금 요약"
    assert data["status"] == "PARTIAL"
    assert data["content"] == "정산금이 748,000원 들어왔습니다."


# ---------------------------------------------------------------- 전부 폐기


def test_문장이_모두_폐기되면_실패로_보낸다(client):
    """ERD `REPORT.narrative` 는 "생성 실패 시 null(지표만 제공)" 이다. 지표 카드는 남는다."""
    client.app.state.briefer = _FixedBriefer(
        headline="이번 주 요약",
        content="매출이 4.2% 늘었습니다. 재료비가 18.1% 올랐습니다.",
    )

    _, _, body = _post(client)

    assert body["success"] is False
    assert body["data"] is None
    error = body["error"]
    assert error["code"] == "AI_ANALYSIS_FAILED"
    assert error["detail"]["discardedSentences"] == 2
    assert error["detail"]["briefingId"] == 91


# ---------------------------------------------------------------- 실패와 차단기


def test_생성_실패는_콜백으로_나간다(client):
    client.app.state.briefer = _FailingBriefer()

    _, _, body = _post(client)

    assert body["success"] is False
    assert body["error"]["code"] == "AI_ANALYSIS_FAILED"
    assert body["error"]["detail"]["stage"] == "model"


def test_차단기가_분류와_따로_돈다(client):
    """§8.7 규칙 6 — "해당 AI 기능만 끄고 나머지는 살린다"."""
    failing = _FailingBriefer(retryable=True)
    client.app.state.briefer = failing

    for _ in range(5):
        _post(client)

    breakers = client.app.state.breakers
    assert breakers.get(BRIEFING_FEATURE).state == "open"
    assert breakers.get(FEATURE).allow() is True, "브리핑이 죽었다고 거래 분류까지 끄면 안 된다"

    _, _, body = _post(client)
    assert failing.calls == 5, "차단기가 열렸는데 모델을 또 불렀다"
    assert body["error"]["detail"]["circuit"] == "open"


# ---------------------------------------------------------------- 202 전에 거절


def test_지표가_없으면_접수하지_않는다(client):
    """Job 이 PROCESSING 에 남지 않도록 202 전에 400 으로 끝낸다."""
    with respx.mock(base_url=BACKEND, assert_all_called=False) as mock:
        route = mock.post(RESULT)
        response = client.post(ENDPOINT, json={**PAYLOAD, "metrics": []}, headers=HEADERS)

    assert response.status_code == 400
    assert response.json()["error"]["code"] == "VALIDATION_FAILED"
    assert not route.called
