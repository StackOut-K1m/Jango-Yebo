"""로컬 VLM(Ollama) 어댑터 — Ollama 없이 HTTP 를 모의해 요청 형태와 계약 변환을 검증한다."""

import asyncio
import json

import httpx
import pytest
import respx

from app.common.local_vlm import LocalVlmClient, LocalVlmError
from app.config import Settings
from app.ocr.pipelines.base import ExtractionError
from app.ocr.pipelines.local_vlm import LocalVlmPipeline
from app.ocr.preprocess import PreparedImage
from app.ocr.schemas import EvidenceExtractionRequest

CHAT_URL = "http://localhost:11434/api/chat"

_EVIDENCE_CONTENT = {
    "supplierName": "홈플러스 청주성안점",
    "supplierBusinessNo": "2208160348",
    "issuedDate": "2023-10-15",
    "taxableAmount": 31_236,
    "vatAmount": 3_124,
    "taxFreeAmount": 9_990,
    "discountAmount": 44_350,
    "totalAmount": 44_350,
    "supplyAmount": None,
    "items": [
        {
            "rawName": "토마토스파게티소스",
            "quantity": 2,
            "unit": None,
            "unitPrice": 4_390,
            "amount": 8_780,
            "discountAmount": 4_390,
        }
    ],
    "fieldConfidence": {
        "supplierName": 0.8,
        "supplierBusinessNo": 0.7,
        "issuedDate": 0.9,
        "totalAmount": 0.9,
        "vatAmount": 0.85,
        "items": 0.8,
    },
    "warnings": ["로컬 VLM 테스트"],
}


def _settings(**overrides) -> Settings:
    return Settings(_env_file=None, app_env="local", **overrides)


def _image() -> PreparedImage:
    return PreparedImage(
        data=b"\xff\xd8jpeg", media_type="image/jpeg", width=10, height=20, scale=1.0
    )


def _ollama_response(content: dict) -> httpx.Response:
    return httpx.Response(
        200,
        json={
            "model": "qwen2.5vl:7b",
            "message": {"role": "assistant", "content": json.dumps(content, ensure_ascii=False)},
            "done": True,
            "prompt_eval_count": 1_200,
            "eval_count": 300,
        },
    )


def test_request_shape_and_contract_mapping():
    pipeline = LocalVlmPipeline(_settings(), LocalVlmClient(_settings()))
    request = EvidenceExtractionRequest(
        job_id=0, evidence_id=0, file_object_key="x.jpg", evidence_type_hint="RECEIPT"
    )
    with respx.mock:
        route = respx.post(CHAT_URL).mock(return_value=_ollama_response(_EVIDENCE_CONTENT))
        extraction, meta = asyncio.run(pipeline.extract_evidence([_image(), _image()], request))

    sent = json.loads(route.calls.last.request.content)
    assert sent["model"] == "qwen2.5vl:7b" and sent["stream"] is False
    assert sent["options"]["num_predict"] == _settings().evidence_max_tokens
    assert sent["options"]["temperature"] == 0
    assert len(sent["messages"][1]["images"]) == 2  # 타일 두 장이 한 요청에 실린다
    assert sent["format"]["type"] == "object" and "supplierName" in sent["format"]["properties"]
    # v1.22 — 모델에게 종류를 묻지 않는다. 물으면 품목 행의 숫자가 밀린다(A/B 실측)
    assert "evidenceType" not in sent["format"]["properties"]

    # 계약 스키마로 변환 — 사업자번호 형식 정리, 날짜 파싱, 에누리는 품목 할인으로
    assert extraction.supplier_business_no == "220-81-60348"
    assert str(extraction.issued_date) == "2023-10-15"
    assert extraction.taxable_amount == 31_236 and extraction.tax_free_amount == 9_990
    assert extraction.items[0].discount_amount == 4_390
    assert meta.pipeline == "VLM" and meta.model_name == "qwen2.5vl:7b"


def test_usage_is_tracked_from_ollama_counts():
    client = LocalVlmClient(_settings())
    pipeline = LocalVlmPipeline(_settings(), client)
    request = EvidenceExtractionRequest(
        job_id=0, evidence_id=0, file_object_key="x.jpg", evidence_type_hint="RECEIPT"
    )
    with respx.mock:
        respx.post(CHAT_URL).mock(return_value=_ollama_response(_EVIDENCE_CONTENT))
        asyncio.run(pipeline.extract_evidence([_image()], request))
    snap = client.usage.snapshot()
    assert snap["calls"] == 1 and snap["input_tokens"] == 1_200 and snap["output_tokens"] == 300


def test_missing_model_and_unreachable_server_are_fatal_config_errors():
    client = LocalVlmClient(_settings())
    with respx.mock:
        respx.post(CHAT_URL).mock(
            return_value=httpx.Response(404, json={"error": "model 'qwen2.5vl:7b' not found"})
        )
        with pytest.raises(LocalVlmError) as missing:
            asyncio.run(
                client.parse(
                    model="qwen2.5vl:7b",
                    system="s",
                    user_text="u",
                    images=[_image()],
                    output_format=_output_format(),
                    max_tokens=64,
                )
            )
    assert missing.value.stage == "config"

    with respx.mock:
        respx.post(CHAT_URL).mock(side_effect=httpx.ConnectError("refused"))
        with pytest.raises(LocalVlmError) as down:
            asyncio.run(
                client.parse(
                    model="qwen2.5vl:7b",
                    system="s",
                    user_text="u",
                    images=[_image()],
                    output_format=_output_format(),
                    max_tokens=64,
                )
            )
    assert down.value.stage == "config"


def test_unparseable_output_is_a_model_error():
    client = LocalVlmClient(_settings())
    broken = {**_EVIDENCE_CONTENT, "totalAmount": "사만사천"}  # int 자리에 문자열
    with respx.mock:
        respx.post(CHAT_URL).mock(return_value=_ollama_response(broken))
        with pytest.raises(LocalVlmError) as exc:
            asyncio.run(
                client.parse(
                    model="qwen2.5vl:7b",
                    system="s",
                    user_text="u",
                    images=[_image()],
                    output_format=_output_format(),
                    max_tokens=64,
                )
            )
    assert exc.value.stage == "model"


def test_evaluator_build_pipeline_selects_local():
    from experiments.evaluate_evidence import _pipeline_model, build_pipeline

    pipeline, client = build_pipeline("local", _settings())
    assert isinstance(pipeline, LocalVlmPipeline) and isinstance(client, LocalVlmClient)
    assert _pipeline_model("local", _settings()) == _settings().local_vlm_model
    assert _pipeline_model("vlm", _settings()) == _settings().vision_model


def test_worker_pipeline_selector_uses_local_provider_in_live_mode():
    from app.common.claude import ClaudeClient
    from app.ocr.pipelines.claude_vlm import ClaudeVisionPipeline
    from app.ocr.pipelines.select import build_pipelines

    live_local = _settings(ai_pipeline_mode="live", vlm_provider="local")
    pipes = build_pipelines(live_local, ClaudeClient(live_local))
    assert isinstance(pipes.evidence_vlm, LocalVlmPipeline)
    assert pipes.evidence_ocr is None  # OCR 공급자는 여전히 미선정

    live_claude = _settings(ai_pipeline_mode="live", vlm_provider="claude")
    assert isinstance(
        build_pipelines(live_claude, ClaudeClient(live_claude)).evidence_vlm, ClaudeVisionPipeline
    )


def _output_format():
    from app.ocr.pipelines.vlm_output import LlmEvidence

    return LlmEvidence


def test_share_is_normalised_to_a_ratio_and_columns_are_enum_constrained():
    """실측(sales-001): 모델이 구성비를 백분율로, 열 이름을 한글 헤더로 냈다."""
    from app.ocr.pipelines.vlm_output import LlmProductSales, to_product_sales

    payload = {
        "period": {"start": "2026-08-01", "end": "2026-08-31", "source": "HEADER"},
        "columnsDetected": ["productName", "quantity", "billedAmount", "share"],
        "items": [
            # 인쇄가 7.0% → 0.07 로 맞춘다
            {
                "productName": "아메리카노",
                "quantity": 39,
                "grossAmount": None,
                "discountAmount": None,
                "netAmount": None,
                "vatAmount": None,
                "billedAmount": 214_500,
                "share": 7.0,
                "confidence": 0.9,
            },
            # 이미 0~1 이면 그대로
            {
                "productName": "카페라떼",
                "quantity": 5,
                "grossAmount": None,
                "discountAmount": None,
                "netAmount": None,
                "vatAmount": None,
                "billedAmount": 10_000,
                "share": 0.0704,
                "confidence": 0.9,
            },
            # 단일 상품 100% 는 1.0 이 된다
            {
                "productName": "콜드브루",
                "quantity": 1,
                "grossAmount": None,
                "discountAmount": None,
                "netAmount": None,
                "vatAmount": None,
                "billedAmount": 3_000,
                "share": 100.0,
                "confidence": 0.9,
            },
        ],  # fmt: skip
        "printedTotal": None,
        "warnings": [],
    }
    extraction = to_product_sales(LlmProductSales.model_validate(payload))
    assert [row.share for row in extraction.items] == [0.07, 0.0704, 1.0]

    # 한글 헤더는 스키마가 거부한다 — 구조화 출력이 계약 키를 강제한다
    import pydantic

    with pytest.raises(pydantic.ValidationError):
        LlmProductSales.model_validate({**payload, "columnsDetected": ["상품명", "구성비"]})


def test_local_vlm_holds_the_compute_semaphore():
    """자체 호스팅 경로는 세마포어를 **잡고** 돈다 — 명세 20.1 ④.

    이게 없으면 로컬 VLM 과 예측 모델이 GPU 없는 4 vCPU 에서 동시에 돌고, 예측은 15초
    타임아웃이 있어 먼저 죽는다. 설정 주석은 처음부터 "동시에 돌리지 않는다" 였는데
    코드가 안 지키고 있었다(2026-09-14 발견).
    """
    settings = _settings(ai_pipeline_mode="live", vlm_provider="local")
    shared = asyncio.Semaphore(1)
    held: list[bool] = []

    class _Probe:
        async def parse(self, **_: object):
            held.append(shared.locked())  # 호출 시점에 잡혀 있어야 한다
            raise LocalVlmError("call", "probe")

    pipeline = LocalVlmPipeline(settings, _Probe(), shared)
    request = EvidenceExtractionRequest(
        job_id=0, evidence_id=0, file_object_key="x.jpg", evidence_type_hint="RECEIPT"
    )
    with pytest.raises(ExtractionError):
        asyncio.run(pipeline.extract_evidence([_image()], request))

    assert held == [True], "세마포어를 안 잡고 모델을 불렀다"
    assert not shared.locked(), "호출이 끝났는데 세마포어가 안 풀렸다"


def test_build_pipelines_shares_the_semaphore_with_forecast():
    """`build_pipelines` 가 받은 것을 그대로 넘기는지 — 각자 만들면 제한이 무의미하다."""
    from app.common.claude import ClaudeClient
    from app.ocr.pipelines.select import build_pipelines

    settings = _settings(ai_pipeline_mode="live", vlm_provider="local")
    shared = asyncio.Semaphore(1)
    pipes = build_pipelines(settings, ClaudeClient(settings), shared)
    assert pipes.evidence_vlm._compute is shared
