"""콜백이 명세 20.2 를 지키는지, **가짜 API 서버가 쓰는 검사기로** 확인한다.

`scripts/callback_contract.py` 의 `check_contract` 는 명세 20.2 를 워커 코드와 따로 한 번 더 적은
것이다. 그런데 그 검사기는 내가 서버를 손으로 띄울 때만 돌았다 — 콜백 모양이 어긋나면
테스트는 전부 통과하고 수동 실행에서만 드러난다. 그래서 두 비동기 엔드포인트의 **실제 콜백
본문**을 같은 검사기에 통과시킨다.

검사기 자체도 함께 시험한다. 아무것도 안 잡는 검사기라면 이 가드는 무의미하다.
"""

import json

import httpx
import respx

from scripts.callback_contract import check_contract
from tests.conftest import BACKEND, HEADERS

EVIDENCE = (
    "/internal/v1/ai/evidence-extraction",
    9013,
    {"jobId": 9013, "evidenceId": 501, "fileObjectKey": "evidences/11/501.jpg"},
)
PRODUCT_SALES = (
    "/internal/v1/ai/product-sales-extraction",
    9401,
    {
        "jobId": 9401,
        "businessId": 11,
        "captureType": "PRINTOUT",
        "fileObjectKeys": ["product-sales/11/tmp-202612-1.jpg"],
    },
)


def _callback(client, endpoint: str, job_id: int, payload: dict) -> tuple[dict, httpx.Headers]:
    with respx.mock(base_url=BACKEND) as mock:
        route = mock.post(f"/internal/v1/ai-jobs/{job_id}/result").mock(
            return_value=httpx.Response(200, json={"success": True})
        )
        assert client.post(endpoint, json=payload, headers=HEADERS).status_code == 202
    assert route.call_count == 1
    request = route.calls.last.request
    return json.loads(request.content), request.headers


def test_evidence_callback_passes_the_fake_backend_contract_check(client):
    endpoint, job_id, payload = EVIDENCE
    body, headers = _callback(client, endpoint, job_id, payload)
    assert check_contract(job_id, headers, body) == []


def test_product_sales_callback_passes_the_fake_backend_contract_check(client):
    endpoint, job_id, payload = PRODUCT_SALES
    body, headers = _callback(client, endpoint, job_id, payload)
    assert check_contract(job_id, headers, body) == []


def test_the_contract_check_is_not_vacuous(client):
    """어긋난 본문을 정말 잡는지 — 아무것도 안 잡는 검사기면 위 두 테스트가 무의미하다."""
    endpoint, job_id, payload = EVIDENCE
    body, headers = _callback(client, endpoint, job_id, payload)

    # 멱등 키가 다른 경우
    assert check_contract(job_id, httpx.Headers({"x-request-id": "ai-job-1-result"}), body)
    # Envelope 가 아닌 경우
    assert check_contract(job_id, headers, {"data": body["data"]})
    # status 가 계약에 없는 값인 경우
    assert check_contract(job_id, headers, {**body, "data": {**body["data"], "status": "DONE"}})
    # 모델 메타가 빠진 경우
    assert check_contract(job_id, headers, {**body, "data": {**body["data"], "pipeline": None}})
    # 성공인데 error 가 채워진 경우
    assert check_contract(job_id, headers, {**body, "error": {"code": "X"}})
    # 실패 콜백에 error.code 가 없는 경우
    assert check_contract(job_id, headers, {"success": False, "data": None, "error": {}})


def test_the_fixture_script_prints_exactly_what_the_worker_sends(client):
    """`sample_callback.py` 는 백엔드에 넘기는 픽스처다 — 실제 콜백과 같아야 한다.

    전에는 매출 쪽이 따로 조립돼 **`validation`(v1.18)이 빠진 채로** 픽스처가 나갔다.
    백엔드가 그 모양에 맞춰 만들면 검산 결과를 받을 자리가 없다.
    """
    import asyncio

    from scripts.sample_callback import evidence_body, product_sales_body

    for endpoint, job_id, payload, builder in (
        (*EVIDENCE, evidence_body),
        (*PRODUCT_SALES, product_sales_body),
    ):
        sent, _ = _callback(client, endpoint, job_id, payload)
        _, fixture = asyncio.run(builder())
        assert set(sent["data"]) == set(fixture["data"]), endpoint
        assert "validation" in sent["data"], endpoint
