"""20.10 초안 점검 라우트 — **넷 중 유일한 동기 경로**.

계약 모양은 `test_document_assist_schema.py` 가 본다. 여기서는 라우트의 두 가지가
다른 셋과 어떻게 다른지를 본다 — `jobId`·콜백이 없다는 것, 그리고 **실패해도 200 을
낸다**는 것.

후자가 이 파일의 중심이다. 20.10 이 *"워커 실패 또는 Circuit Breaker open 시 초안은
`reviewNote` 없이 정상 반환된다. 초안 생성은 이 호출에 의존하지 않는다"* 라고 적었고,
그 계약을 코드로 묶어 두는 자리가 여기다.
"""

import httpx
import respx
from fastapi.testclient import TestClient

from app.common.model_meta import ModelMeta
from app.nlp.pipeline import ClassificationError, StubAssistant
from app.nlp.router import ASSIST_FEATURE
from app.nlp.schemas import DocumentAssistResult, ReviewNote
from tests.conftest import BACKEND, HEADERS

ENDPOINT = "/internal/v1/ai/document-assist"

# docs/api/20_내부_AI워커.md §20.10 요청 예시 그대로
PAYLOAD = {
    "draftId": 301,
    "formType": "SIMPLE_LEDGER",
    "business": {"industryType": "FOOD_CAFE"},
    "fields": [
        {
            "fieldKey": "PAYROLL",
            "value": 28600000,
            "transactionCount": 36,
            "evidenceMissingCount": 3,
            "unreviewedCount": 0,
        },
        {"fieldKey": "RENT", "value": 14400000, "transactionCount": 12, "monthsCovered": 12},
    ],
    "pending": [
        {"fieldKey": "DEPRECIATION", "reason": "EQUIPMENT_PURCHASE", "amount": 3400000, "count": 2}
    ],
    "toneGuide": "확정 표현 금지, 세무 판단 확정 금지, 입력에 없는 수치 생성 금지",
}

META = ModelMeta(model_name="fake", model_version=None, prompt_version="assist/v1", pipeline="LLM")


class _FixedAssistant:
    def __init__(self, result: DocumentAssistResult) -> None:
        self._result = result

    async def review(self, request):
        return self._result, META


class _FailingAssistant:
    def __init__(self, *, retryable: bool = False) -> None:
        self._retryable = retryable
        self.calls = 0

    async def review(self, request):
        self.calls += 1
        raise ClassificationError("model", "모델 호출 실패", retryable=self._retryable)


class _ExplodingAssistant:
    async def review(self, request):
        raise RuntimeError("예상 못 한 오류")


def _post(client: TestClient, payload: dict | None = None):
    return client.post(ENDPOINT, json=payload or PAYLOAD, headers=HEADERS)


# ---------------------------------------------------------------- 동기 응답


def test_stub_이_즉시_응답한다(client):
    response = _post(client)

    assert response.status_code == 200
    assert response.json() == {"success": True, "data": {"notes": []}, "error": None}


def test_콜백을_보내지_않는다(client):
    """비동기 셋과 갈리는 지점이다. 콜백을 보내면 백엔드가 있지도 않은 Job 을 찾는다."""
    with respx.mock(base_url=BACKEND, assert_all_called=False) as mock:
        route = mock.post(url__startswith="/internal/v1/ai-jobs").mock(
            return_value=httpx.Response(200, json={"success": True})
        )
        _post(client)

    assert not route.called


def test_stub_은_점검_문장을_지어내지_않는다(client):
    """점검 문장을 지어내면 사장님이 세무 판단으로 읽는다. 빈 목록은 계약상 정상이다."""
    client.app.state.assistant = StubAssistant()

    assert _post(client).json()["data"]["notes"] == []


def test_명세_20_10_응답이_그대로_실린다(client):
    client.app.state.assistant = _FixedAssistant(
        DocumentAssistResult(
            notes=[
                ReviewNote(
                    field_key="PAYROLL",
                    severity="WARN",
                    message="급여 36건 중 3건은 원천세 신고 자료가 연결되지 않았습니다.",
                ),
                ReviewNote(
                    field_key="DEPRECIATION",
                    severity="INFO",
                    message="설비 구입 2건은 감가상각 대상일 수 있어 항목 금액에 넣지 않았습니다.",
                ),
            ]
        )
    )

    notes = _post(client).json()["data"]["notes"]

    assert [n["fieldKey"] for n in notes] == ["PAYROLL", "DEPRECIATION"]
    assert notes[0]["severity"] == "WARN"
    assert notes[1]["severity"] == "INFO"


# ---------------------------------------------------------------- 실패해도 200


def test_모델이_실패해도_200에_빈_목록이다(client):
    """20.10 — "초안 생성은 이 호출에 의존하지 않는다".

    5xx 를 내면 백엔드가 예외 처리를 한 겹 더 짜야 하고, 그 처리가 빠진 날 초안 화면이
    통째로 죽는다. P0 경로에 AI 실패가 번지면 안 된다.
    """
    client.app.state.assistant = _FailingAssistant()

    response = _post(client)

    assert response.status_code == 200
    assert response.json()["data"]["notes"] == []
    assert response.json()["error"] is None


def test_예기치_않은_오류도_200이다(client):
    client.app.state.assistant = _ExplodingAssistant()

    response = _post(client)

    assert response.status_code == 200
    assert response.json()["data"]["notes"] == []


def test_차단기가_열려도_200이다(client):
    failing = _FailingAssistant(retryable=True)
    client.app.state.assistant = failing
    for _ in range(5):
        _post(client)
    calls_before = failing.calls

    response = _post(client)

    assert response.status_code == 200
    assert response.json()["data"]["notes"] == []
    assert failing.calls == calls_before  # 모델을 건너뛰었다


# ---------------------------------------------------------------- 차단기


def test_재시도_가능_실패가_쌓이면_차단기가_열린다(client):
    client.app.state.assistant = _FailingAssistant(retryable=True)
    breaker = client.app.state.breakers.get(ASSIST_FEATURE)

    for _ in range(5):
        _post(client)

    assert not breaker.allow()


def test_재시도_불가_실패는_차단기를_세지_않는다(client):
    client.app.state.assistant = _FailingAssistant(retryable=False)
    breaker = client.app.state.breakers.get(ASSIST_FEATURE)

    for _ in range(6):
        _post(client)

    assert breaker.allow()


def test_차단기는_기능별로_따로다(client):
    client.app.state.assistant = _FailingAssistant(retryable=True)
    for _ in range(5):
        _post(client)

    assert not client.app.state.breakers.get(ASSIST_FEATURE).allow()
    assert client.app.state.breakers.get("transaction-classification").allow()
    assert client.app.state.breakers.get("program-structuring").allow()


# ---------------------------------------------------------------- 요청 검증


def test_민감_항목이_섞이면_거부한다(client):
    """**여기는 200 으로 넘기지 않는다.** 실패를 삼키는 것은 모델이 죽었을 때의 규칙이고,
    민감 항목은 백엔드가 알아야 고칠 수 있다 (`_reject_sensitive_key`)."""
    payload = {**PAYLOAD, "fields": [{"fieldKey": "BIRTH_DATE", "value": None}]}

    assert _post(client, payload).status_code == 400


def test_항목이_없으면_거부한다(client):
    assert _post(client, {**PAYLOAD, "fields": []}).status_code == 400


def test_토큰이_없으면_거부한다(client):
    response = client.post(ENDPOINT, json=PAYLOAD, headers={"X-Request-Id": "req-1"})

    assert response.status_code == 401


# ---------------------------------------------------------------- 근거 검증 (`-96`)


def test_입력에_없는_수치가_든_문장을_버린다(client):
    """36 − 3 = 33. 둘 다 입력에 있지만 33 은 없다."""
    client.app.state.assistant = _FixedAssistant(
        DocumentAssistResult(
            notes=[
                ReviewNote(
                    field_key="PAYROLL", severity="WARN", message="나머지 33건은 확인됐습니다."
                )
            ]
        )
    )

    assert _post(client).json()["data"]["notes"] == []


def test_문제가_있는_문장만_버린다(client):
    """명세 20.1 — "해당 항목만 제외한다"."""
    client.app.state.assistant = _FixedAssistant(
        DocumentAssistResult(
            notes=[
                ReviewNote(
                    field_key="PAYROLL",
                    severity="WARN",
                    message="급여 36건 중 3건은 원천세 자료가 연결되지 않았습니다.",
                ),
                ReviewNote(
                    field_key="PAYROLL", severity="INFO", message="나머지 33건은 확인됐습니다."
                ),
            ]
        )
    )

    notes = _post(client).json()["data"]["notes"]

    assert len(notes) == 1
    assert "원천세" in notes[0]["message"]


def test_전부_폐기돼도_200이다(client):
    """빈 목록은 계약상 정상이다. 500 을 내면 초안 화면이 AI 실패에 물린다 —
    이 라우트가 막으려는 바로 그 상황이다."""
    client.app.state.assistant = _FixedAssistant(
        DocumentAssistResult(
            notes=[
                ReviewNote(
                    field_key="PAYROLL", severity="WARN", message="합계는 43,000,000원입니다."
                )
            ]
        )
    )

    response = _post(client)

    assert response.status_code == 200
    assert response.json()["data"]["notes"] == []
    assert response.json()["error"] is None


def test_stub_도_검증을_거친다(client):
    """stub 이 규격을 어겨도 여기서 걸린다. 검증을 파이프라인이 아니라 라우트에 둔 이유다."""
    client.app.state.assistant = _FixedAssistant(
        DocumentAssistResult(
            notes=[ReviewNote(field_key="UNKNOWN_KEY", severity="INFO", message="문장")]
        )
    )

    assert _post(client).json()["data"]["notes"] == []
