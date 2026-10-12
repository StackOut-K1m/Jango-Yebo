"""`/openapi.json` 에 1번 파트 계약이 실리는가 (`-89` 완료 기준의 세 번째).

**스키마 파일만 있으면 OpenAPI 에 실리지 않는다.** 라우트에 붙어야 계약이 밖으로 나가고,
그 문서가 백엔드에 전달하는 산출물이다. 스키마와 라우트가 따로 놀아도 테스트는 각자
통과하므로, 둘이 이어져 있는지는 여기서만 잡힌다.
"""

import pytest

PREFIX = "/internal/v1/ai"

ASYNC_ROUTES = [
    f"{PREFIX}/transaction-classification",  # 20.3
    f"{PREFIX}/program-structuring",  # 20.6
    f"{PREFIX}/briefing-generation",  # 20.7
]
SYNC_ROUTES = [f"{PREFIX}/document-assist"]  # 20.10


@pytest.fixture
def openapi(client):
    response = client.get("/openapi.json")
    assert response.status_code == 200
    return response.json()


def test_라우트_4종이_모두_실린다(openapi):
    paths = openapi["paths"]

    for route in ASYNC_ROUTES + SYNC_ROUTES:
        assert route in paths, f"{route} 가 OpenAPI 에 없다"
        assert "post" in paths[route]


@pytest.mark.parametrize("route", ASYNC_ROUTES)
def test_비동기_셋은_202로_선언된다(openapi, route):
    """백엔드가 응답 코드로 비동기 여부를 읽는다. 200 으로 선언되면 콜백을 안 기다린다."""
    responses = openapi["paths"][route]["post"]["responses"]

    assert "202" in responses, f"{route} 가 202 로 선언되지 않았다"
    assert "200" not in responses


@pytest.mark.parametrize("route", SYNC_ROUTES)
def test_동기_하나는_200이다(openapi, route):
    responses = openapi["paths"][route]["post"]["responses"]

    assert "200" in responses
    assert "202" not in responses


@pytest.mark.parametrize(
    "schema",
    [
        # 20.3 거래 분류 — 요청과 **콜백 본문**
        "TransactionClassificationRequest",
        "ClassificationCallbackData",
        "ClassificationResult",
        # 20.6 공고 구조화
        "ProgramStructuringRequest",
        "ProgramStructuringCallbackData",
        "Eligibility",
        "RequiredDocument",
        # 20.7 브리핑
        "BriefingGenerationRequest",
        "BriefingCallbackData",
        "BriefingMetric",
        # 20.10 초안 점검
        "DocumentAssistRequest",
        "DocumentAssistResult",
        "DraftFieldSummary",
        "PendingItem",
        "ReviewNote",
    ],
)
def test_계약_모델이_실린다(openapi, schema):
    assert schema in openapi["components"]["schemas"], f"{schema} 가 OpenAPI 에 없다"


def test_콜백_본문이_평면이다(openapi):
    """`completed()` 가 `{"status": …, **meta, **result}` 로 **펼치기** 때문이다.

    그래서 선에 실리는 것은 `TransactionClassificationResult` 가 아니라
    `ClassificationCallbackData` 다. 내부 래퍼 이름을 문서에 싣지 않는다 — 백엔드가
    있지도 않은 중첩 객체를 만들게 된다.
    """
    data = openapi["components"]["schemas"]["ClassificationCallbackData"]["properties"]

    assert set(data) == {
        "modelName",
        "modelVersion",
        "promptVersion",
        "pipeline",
        "status",
        "results",
    }


@pytest.mark.parametrize("route", ASYNC_ROUTES)
def test_비동기_셋이_콜백을_선언한다(openapi, route):
    """202 응답은 `{jobId}` 뿐이라, 콜백을 선언하지 않으면 **결과 모양이 문서에 안 실린다.**
    백엔드가 소스를 직접 읽어 DTO 를 만들게 된 원인이 그것이었다."""
    callbacks = openapi["paths"][route]["post"].get("callbacks")

    assert callbacks, f"{route} 에 콜백 선언이 없다"
    path = next(iter(next(iter(callbacks.values())).values()))
    assert "$request.body#/jobId" in str(callbacks)
    assert "requestBody" in path["post"]


def test_동기_하나는_콜백이_없다(openapi):
    """20.10 은 응답으로 결과를 돌려준다. 콜백을 선언하면 백엔드가 기다린다."""
    assert "callbacks" not in openapi["paths"][SYNC_ROUTES[0]]["post"]


def test_camel_case로_발행된다(openapi):
    """백엔드가 이 문서를 보고 DTO 를 만든다. snake_case 로 나가면 그대로 어긋난다."""
    properties = openapi["components"]["schemas"]["TransactionClassificationRequest"]["properties"]

    assert "jobId" in properties
    assert "allowedCategories" in properties
    assert "job_id" not in properties


def test_민감_키가_문서에도_없다(openapi):
    """`-88` 의 부재 보장이 발행되는 계약에서도 성립하는지 본다 — 백엔드는 이 문서를
    보고 DTO 를 만들므로, 여기 없으면 저쪽에도 안 생긴다."""
    document = repr(openapi["components"]["schemas"]["DocumentAssistRequest"])
    nested = repr(openapi["components"]["schemas"]["DraftFieldSummary"])

    for banned in ("residentRegistrationNumber", "accountNumber", "homeAddress", "birthDate"):
        assert banned not in document
        assert banned not in nested
