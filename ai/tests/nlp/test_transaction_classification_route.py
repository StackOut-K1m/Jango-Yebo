"""20.3 거래 분류 라우트 — 접수·콜백·폐기·실패.

스키마 단위 검증(`invalid_results` 등)은 `test_transaction_classification_schema.py` 에 있다.
여기서는 그 규칙이 **라우트를 통과할 때 실제로 적용되는지**를 본다.

인증·X-Request-Id·Envelope 은 `tests/test_auth.py` 가 전 라우트 공통으로 덮는다.
"""

import json

import httpx
import respx
from fastapi.testclient import TestClient

from app.common.claude import ClaudeClient
from app.common.model_meta import ModelMeta
from app.config import Settings
from app.main import create_app
from app.nlp.pipeline import ClassificationError, ClaudeClassifier, StubClassifier, build_classifier
from app.nlp.router import FEATURE
from app.nlp.schemas import REQUEST_MAX, ClassificationResult
from tests.conftest import BACKEND, HEADERS, TOKEN

ENDPOINT = "/internal/v1/ai/transaction-classification"
RESULT = "/internal/v1/ai-jobs/9012/result"

# 명세 20.3 요청 예시. 출금 1건 + 입금 1건 — stub 이 방향별로 다른 카테고리를 고르는지 본다.
PAYLOAD = {
    "jobId": 9012,
    "business": {"industryType": "FOOD_CAFE"},
    "allowedCategories": ["RENT", "PAYROLL", "MATERIALS", "PLATFORM_SETTLEMENT"],
    "fewShotExamples": [
        {
            "description": "우아한형제들",
            "direction": "DEPOSIT",
            "category": "PLATFORM_SETTLEMENT",
            "merchantName": "배달의민족",
        }
    ],
    "transactions": [
        {
            "transactionId": 88031,
            "description": "김영자",
            "direction": "WITHDRAWAL",
            "amount": 1200000,
            "occurredAt": "2026-08-25T01:00:00Z",
            "cycleHint": "매월 25일 반복 (최근 4회)",
        },
        {
            "transactionId": 88032,
            "description": "우아한형제들",
            "direction": "DEPOSIT",
            "amount": 430000,
            "occurredAt": "2026-08-26T01:00:00Z",
        },
    ],
}


def _tx(transaction_id: int, direction: str = "WITHDRAWAL") -> dict:
    return {
        "transactionId": transaction_id,
        "description": "테스트",
        "direction": direction,
        "amount": 1000,
        "occurredAt": "2026-08-25T01:00:00Z",
    }


class _FixedClassifier:
    """호출부(라우터)의 폐기·누락 처리를 검증하기 위한 고정 응답 파이프라인."""

    def __init__(self, results: list[ClassificationResult]) -> None:
        self._results = results
        self.meta = ModelMeta(
            model_name="fake", model_version=None, prompt_version="test/v1", pipeline="LLM"
        )

    async def classify(self, request):
        return list(self._results), self.meta


class _FailingClassifier:
    def __init__(self, stage: str = "model", *, retryable: bool = False) -> None:
        self._stage = stage
        self._retryable = retryable
        self.calls = 0

    async def classify(self, request):
        self.calls += 1
        raise ClassificationError(self._stage, "모델 호출 실패", retryable=self._retryable)


def _post(client: TestClient, payload: dict | None = None):
    """202 를 받고 콜백 본문을 돌려준다. 콜백이 오지 않으면 None."""
    with respx.mock(base_url=BACKEND, assert_all_called=False) as mock:
        route = mock.post(RESULT).mock(return_value=httpx.Response(200, json={"success": True}))
        response = client.post(ENDPOINT, json=payload or PAYLOAD, headers=HEADERS)
    body = json.loads(route.calls.last.request.content) if route.called else None
    return response, route, body


# ---------------------------------------------------------------- 접수와 콜백


def test_stub_job_reaches_completed_via_callback(client):
    response, route, body = _post(client)

    assert response.status_code == 202
    assert response.json() == {"success": True, "data": {"jobId": 9012}, "error": None}

    assert route.call_count == 1
    sent = route.calls.last.request
    assert sent.headers["X-Request-Id"] == "ai-job-9012-result"
    assert sent.headers["Authorization"] == f"Bearer {TOKEN}"

    assert body["success"] is True
    data = body["data"]
    assert data["status"] == "COMPLETED"
    assert data["pipeline"] == "STUB" and data["modelName"] == "stub"
    assert data["promptVersion"] is None

    # 방향이 맞는 허용 카테고리 중 첫 번째 — 출금은 RENT, 입금은 PLATFORM_SETTLEMENT
    results = {r["transactionId"]: r for r in data["results"]}
    assert results[88031]["category"] == "RENT"
    assert results[88032]["category"] == "PLATFORM_SETTLEMENT"
    # 0.85(§20.3 임계값) 미만이라 API 서버가 전건에 needsConfirmation 을 세운다
    assert all(r["confidence"] < 0.85 for r in data["results"])


def test_stub_omits_transactions_with_no_matching_direction(client):
    """출금 거래인데 허용 목록이 입금 카테고리뿐 — 규격을 어기는 대신 그 건을 뺀다."""
    payload = {
        **PAYLOAD,
        "allowedCategories": ["PLATFORM_SETTLEMENT"],
        "transactions": [_tx(88031, "WITHDRAWAL")],
    }
    response, _, body = _post(client, payload)

    assert response.status_code == 202
    # 전건이 빠져도 COMPLETED 다 — 배치 일부만 살리는 것이 계약이다 (§6.3 폴백)
    assert body["data"]["status"] == "COMPLETED"
    assert body["data"]["results"] == []


# ---------------------------------------------------------------- 폐기 (§20.1)


def test_route_discards_category_outside_allowed(client):
    """허용 목록 밖 카테고리는 라우트가 버린다. 나머지 건은 살아남는다."""
    client.app.state.classifier = _FixedClassifier(
        [
            # UTILITIES 는 allowedCategories 에 없다
            ClassificationResult(
                transaction_id=88031,
                category="UTILITIES",
                confidence=0.9,
                reason="허용 목록 밖",
            ),
            ClassificationResult(
                transaction_id=88032,
                category="PLATFORM_SETTLEMENT",
                confidence=0.9,
                reason="정상",
            ),
        ]
    )
    _, _, body = _post(client)

    results = body["data"]["results"]
    assert [r["transactionId"] for r in results] == [88032]
    assert body["data"]["status"] == "COMPLETED"


def test_route_discards_direction_mismatch(client):
    """입금 거래에 출금 카테고리를 붙인 결과는 신뢰도와 무관하게 버린다."""
    client.app.state.classifier = _FixedClassifier(
        [
            ClassificationResult(
                transaction_id=88032,  # DEPOSIT 거래
                category="RENT",  # WITHDRAWAL 카테고리
                confidence=1.0,
                reason="방향 불일치",
            )
        ]
    )
    _, _, body = _post(client)

    assert body["data"]["results"] == []
    assert body["data"]["status"] == "COMPLETED"


def test_route_keeps_model_meta_from_pipeline(client):
    client.app.state.classifier = _FixedClassifier([])
    _, _, body = _post(client)

    data = body["data"]
    assert data["modelName"] == "fake"
    assert data["promptVersion"] == "test/v1"
    assert data["pipeline"] == "LLM"


# ---------------------------------------------------------------- 실패


def test_classifier_failure_becomes_failed_callback(client):
    client.app.state.classifier = _FailingClassifier(stage="model")
    _, _, body = _post(client)

    assert body["success"] is False
    assert body["data"] is None
    error = body["error"]
    assert error["code"] == "AI_ANALYSIS_FAILED"
    assert error["detail"]["stage"] == "model"
    assert error["detail"]["jobId"] == 9012
    assert error["detail"]["transactionCount"] == 2


def test_unexpected_error_still_sends_failed_callback(client):
    class _Boom:
        async def classify(self, request):
            raise RuntimeError("예기치 않은 오류")

    client.app.state.classifier = _Boom()
    _, _, body = _post(client)

    assert body["success"] is False
    assert body["error"]["code"] == "AI_ANALYSIS_FAILED"


# ---------------------------------------------------------------- Circuit Breaker


def test_재시도_가능한_실패_5회가_차단기를_연다(client):
    """§8.7 규칙 6. 5회째까지는 모델을 부르고, 6회째부터 안 부른다."""
    failing = _FailingClassifier(stage="model", retryable=True)
    client.app.state.classifier = failing

    for _ in range(5):
        _post(client)
    assert failing.calls == 5
    assert client.app.state.breakers.get(FEATURE).state == "open"

    _, _, body = _post(client)
    assert failing.calls == 5, "차단기가 열렸는데도 모델을 불렀다 — 돈이 계속 샌다"
    assert body["error"]["detail"]["circuit"] == "open"


def test_차단기가_열려도_콜백은_간다(client):
    """조용히 끝내면 Job 이 PROCESSING 에 남아 JobSweeper 가 10분을 기다린다 (23장)."""
    client.app.state.classifier = _FailingClassifier(retryable=True)
    for _ in range(5):
        _post(client)

    _, route, body = _post(client)

    assert route.called, "차단기 open 을 이유로 콜백을 건너뛰면 안 된다"
    assert body["success"] is False
    assert body["error"]["code"] == "AI_ANALYSIS_FAILED"
    detail = body["error"]["detail"]
    assert detail["jobId"] == 9012
    # 백엔드가 언제 다시 걸어도 되는지 알아야 한다
    assert 0 < detail["retryAfterSeconds"] <= 60.0


def test_재시도_불가능한_실패는_세지_않는다(client):
    """400·404·401 은 60초 뒤에도 같은 답이다. 세면 내 버그가 기능을 끈다 (§8.6)."""
    failing = _FailingClassifier(stage="config", retryable=False)
    client.app.state.classifier = failing

    for _ in range(10):
        _post(client)

    assert client.app.state.breakers.get(FEATURE).state == "closed"
    assert failing.calls == 10, "안 세기로 했으면 계속 불러야 한다"


def test_성공하면_카운터가_지워진다(client):
    """**연속** 5회다. 4회 실패 뒤 한 번 성공하면 처음부터 다시 센다."""
    failing = _FailingClassifier(retryable=True)
    client.app.state.classifier = failing
    for _ in range(4):
        _post(client)

    client.app.state.classifier = StubClassifier()
    _post(client)

    client.app.state.classifier = failing
    for _ in range(4):
        _post(client)

    assert client.app.state.breakers.get(FEATURE).state == "closed"


def test_차단기는_기능별이다(client):
    """분류가 죽어도 증빙 추출·브리핑은 살아 있어야 한다 (§8.7 규칙 6)."""
    client.app.state.classifier = _FailingClassifier(retryable=True)
    for _ in range(5):
        _post(client)

    breakers = client.app.state.breakers
    assert breakers.get(FEATURE).state == "open"
    assert breakers.get("evidence-extraction").allow() is True


def test_차단기가_202_를_막지_않는다(client):
    """접수까지 막으면 백엔드가 이미 만든 Job 이 갈 곳을 잃는다."""
    client.app.state.classifier = _FailingClassifier(retryable=True)
    for _ in range(5):
        _post(client)

    response, _, _ = _post(client)
    assert response.status_code == 202
    assert response.json()["data"] == {"jobId": 9012}


# ---------------------------------------------------------------- 202 전에 거절


def test_request_over_limit_is_rejected_before_accepting(client):
    """상한은 `REQUEST_MAX` 다. `BATCH_MAX` 를 넘는 것은 워커가 잘라 쓰므로 정상이다."""
    payload = {**PAYLOAD, "transactions": [_tx(i) for i in range(REQUEST_MAX + 1)]}
    with respx.mock(base_url=BACKEND, assert_all_called=False) as mock:
        route = mock.post(RESULT)
        response = client.post(ENDPOINT, json=payload, headers=HEADERS)

    assert response.status_code == 400
    assert response.json()["error"]["code"] == "VALIDATION_FAILED"
    # Job 이 PROCESSING 에 남지 않아야 한다 — 콜백을 보내지 않고 4xx 로 끝낸다
    assert not route.called


def test_empty_allowed_categories_is_rejected(client):
    payload = {**PAYLOAD, "allowedCategories": []}
    response = client.post(ENDPOINT, json=payload, headers=HEADERS)
    assert response.status_code == 400
    assert response.json()["error"]["code"] == "VALIDATION_FAILED"


# ---------------------------------------------------------------- 파이프라인 선택


def test_stub_mode_builds_stub_classifier(settings: Settings):
    claude = ClaudeClient(settings)
    assert isinstance(build_classifier(settings, claude), StubClassifier)


def test_live_mode_builds_claude_classifier(settings: Settings):
    live = settings.model_copy(update={"ai_pipeline_mode": "live"})
    claude = ClaudeClient(live)
    assert isinstance(build_classifier(live, claude), ClaudeClassifier)


def test_live_mode_wires_claude_classifier_into_app(settings: Settings):
    live = settings.model_copy(update={"ai_pipeline_mode": "live"})
    with TestClient(create_app(live)) as client:
        assert isinstance(client.app.state.classifier, ClaudeClassifier)


# ---------------------------------------------------------------- 청킹 (2026-09-11 백엔드 회신)


class _CountingClassifier:
    """청크마다 받은 건수를 기록한다. 결과는 받은 거래를 그대로 분류한 것처럼 낸다."""

    def __init__(self, *, fail_on: set[int] | None = None, retryable: bool = True) -> None:
        self.chunk_sizes: list[int] = []
        self._fail_on = fail_on or set()
        self._retryable = retryable
        self.meta = ModelMeta(
            model_name="fake", model_version=None, prompt_version="test/v1", pipeline="LLM"
        )

    async def classify(self, request):
        self.chunk_sizes.append(len(request.transactions))
        if len(self.chunk_sizes) in self._fail_on:
            raise ClassificationError("model", "청크 실패", retryable=self._retryable)
        return [
            ClassificationResult(
                transaction_id=t.transaction_id,
                category="RENT",
                confidence=0.9,
                reason="테스트",
            )
            for t in request.transactions
        ], self.meta


def _many(count: int) -> dict:
    return {**PAYLOAD, "transactions": [_tx(i) for i in range(count)]}


def test_요청을_BATCH_MAX_씩_잘라_부른다(client):
    """`1 Job ↔ 1 콜백` 이라 쪼개는 일이 요청 안에서 끝나야 한다."""
    counting = _CountingClassifier()
    client.app.state.classifier = counting

    _, route, body = _post(client, _many(50))

    assert counting.chunk_sizes == [20, 20, 10], "20건씩 잘라 3번 불러야 한다"
    assert route.call_count == 1, "콜백은 한 번뿐이어야 한다 — 여러 번이면 백엔드가 버린다"
    assert len(body["data"]["results"]) == 50
    assert body["data"]["status"] == "COMPLETED"


def test_청크가_순차로_돈다(client):
    """동시에 던지면 429 를 받고 차단기가 열려 남은 청크가 전부 죽는다."""
    order: list[str] = []

    class _Recorder:
        meta = ModelMeta(
            model_name="fake", model_version=None, prompt_version="test/v1", pipeline="LLM"
        )

        async def classify(self, request):
            order.append("시작")
            order.append("끝")
            return [], self.meta

    client.app.state.classifier = _Recorder()
    _post(client, _many(60))

    # 순차면 시작/끝이 번갈아 나온다. 동시면 시작이 몰린다.
    assert order == ["시작", "끝"] * 3


def test_일부_청크가_실패하면_PARTIAL_이고_성공분은_남는다(client):
    """일시적 429 하나로 나머지를 버리지 않는다 — 남은 건은 8.5 재실행이 주워간다."""
    counting = _CountingClassifier(fail_on={2})
    client.app.state.classifier = counting

    _, _, body = _post(client, _many(60))

    assert len(counting.chunk_sizes) == 3, "실패한 청크에서 멈추지 않는다"
    assert body["success"] is True, "PARTIAL 은 실패가 아니다"
    assert body["data"]["status"] == "PARTIAL"
    assert len(body["data"]["results"]) == 40, "실패한 20건만 빠진다"


def test_차단기가_열리면_남은_청크를_건너뛴다(client):
    """피해 상한은 차단기가 정한다 — 5연속 실패면 남은 청크를 부르지 않는다."""
    counting = _CountingClassifier(fail_on={1, 2, 3, 4, 5}, retryable=True)
    client.app.state.classifier = counting

    _, _, body = _post(client, _many(200))  # 10청크

    assert len(counting.chunk_sizes) == 5, "5연속 실패 뒤에는 모델을 부르지 않는다"
    assert body["success"] is False, "한 청크도 성공하지 못했다"
    assert body["error"]["detail"]["failedChunks"] == 10


def test_전건_성공이면_COMPLETED_다(client):
    counting = _CountingClassifier()
    client.app.state.classifier = counting

    _, _, body = _post(client, _many(20))

    assert counting.chunk_sizes == [20]
    assert body["data"]["status"] == "COMPLETED"
    assert "failedChunks" not in body["data"]
