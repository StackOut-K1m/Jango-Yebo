import json

import httpx
import respx

from tests.conftest import BACKEND, HEADERS

ENDPOINT = "/internal/v1/ai/product-sales-extraction"
RESULT = "/internal/v1/ai-jobs/9401/result"
PAYLOAD = {
    "jobId": 9401,
    "businessId": 11,
    "captureType": "PRINTOUT",
    "fileObjectKeys": ["product-sales/11/tmp-202612-1.jpg", "product-sales/11/tmp-202612-2.jpg"],
    "knownProducts": ["카페라떼", "아메리카노", "콜드브루"],
}


def test_stub_job_returns_spec_shape_via_callback(client):
    with respx.mock(base_url=BACKEND) as mock:
        route = mock.post(RESULT).mock(return_value=httpx.Response(200, json={"success": True}))
        response = client.post(ENDPOINT, json=PAYLOAD, headers=HEADERS)

    assert response.status_code == 202
    assert response.json()["data"] == {"jobId": 9401}
    assert route.call_count == 1

    data = json.loads(route.calls.last.request.content)["data"]
    assert data["status"] == "COMPLETED" and data["pipeline"] == "STUB"
    assert data["period"] == {"start": "2022-12-01", "end": "2022-12-14", "source": "HEADER"}
    assert "productName" in data["columnsDetected"]
    assert data["items"][0]["productName"] == "카페라떼"
    assert data["printedTotal"]["billedAmount"] == 670_450
    assert data["warnings"]


def test_capture_type_is_validated(client):
    response = client.post(ENDPOINT, json={**PAYLOAD, "captureType": "PHOTO"}, headers=HEADERS)
    assert response.status_code == 400
    assert response.json()["error"]["code"] == "VALIDATION_FAILED"


def test_at_least_one_file_key_is_required(client):
    response = client.post(ENDPOINT, json={**PAYLOAD, "fileObjectKeys": []}, headers=HEADERS)
    assert response.status_code == 400


def test_reversed_period_is_dropped_not_sent():
    """뒤집힌 기간은 버린다 — 그대로 보내면 백엔드 CHECK 에 걸려 추출 전체가 사라진다.

    V8 의 `CHECK (period_end >= period_start)` 가 INSERT 를 거절하므로, 기간만 잃는 편이
    행·검산·모델 메타를 통째로 잃는 것보다 낫다. `source = null` 은 요구 1 의 정상 상태다.
    """
    from app.ocr.pipelines.vlm_output import (
        LlmPeriod,
        LlmPrintedTotal,
        LlmProductSales,
        to_product_sales,
    )

    out = LlmProductSales(
        period=LlmPeriod(start="2026-01-30", end="2026-01-07", source="HEADER"),
        columnsDetected=["productName", "billedAmount"],
        items=[],
        printedTotal=LlmPrintedTotal(
            quantity=None,
            grossAmount=None,
            discountAmount=None,
            netAmount=None,
            vatAmount=None,
            billedAmount=None,
        ),
        warnings=[],
    )
    extraction = to_product_sales(out)
    assert extraction.period.start is None
    assert extraction.period.end is None
    assert extraction.period.source is None
    assert any("뒤집" in w for w in extraction.warnings)


def test_normal_period_survives():
    """정상 기간까지 버리면 안 된다 — 위 가드가 과하게 걸리는지 본다."""
    from app.ocr.pipelines.vlm_output import (
        LlmPeriod,
        LlmPrintedTotal,
        LlmProductSales,
        to_product_sales,
    )

    out = LlmProductSales(
        period=LlmPeriod(start="2026-01-07", end="2026-01-30", source="HEADER"),
        columnsDetected=["productName", "billedAmount"],
        items=[],
        printedTotal=LlmPrintedTotal(
            quantity=None,
            grossAmount=None,
            discountAmount=None,
            netAmount=None,
            vatAmount=None,
            billedAmount=None,
        ),
        warnings=[],
    )
    extraction = to_product_sales(out)
    assert extraction.period.source == "HEADER"
    assert str(extraction.period.start) == "2026-01-07"
