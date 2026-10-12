"""콜백이 백엔드 테이블에 **실제로 들어가는가** — 마이그레이션 DDL 을 읽어서 대조한다.

합의 ⑤(`pipeline` 에 STUB 없음 · `model_version` NOT NULL · `prompt_version` 없음)를 내가 ERD 를
눈으로 읽어서 찾았다. 그대로 뒀으면 stub 연동의 첫 INSERT 가 CHECK·NOT NULL 위반으로 깨졌고,
그건 백엔드·워커를 API 키 없이 잇는 유일한 경로다. 다음에는 테스트가 찾게 한다.

**DDL 을 베끼지 않고 파싱한다.** 제약을 여기 옮겨 적으면 백엔드가 컬럼을 바꿔도 테스트는
통과한다 — 같은 규칙이 두 곳에 사는 문제다(`scripts/callback_contract.py` 와 같은 이유).

**CI 에서는 기본적으로 건너뛴다.** Jenkins 의 AI 테스트는 `docker build ... ai/` 라 빌드
컨텍스트가 `ai/` 하나뿐이고 백엔드 경로가 이미지에 없다. `docker build` 시점이라 볼륨 마운트도
못 쓴다. 그래서 컨테이너에서도 돌리려면 **파일이 빌드 컨텍스트 안에 있어야** 한다.

`infra/Jenkinsfile` 의 `Test · AI` 스테이지에 두 줄을 더하면 켜진다 — 담당자 몫이다
(`ai/CLAUDE.md` 「남의 파일은 같이 고치지 말고 담당자에게 넘긴다」).

    cp -r backend/src/main/resources/db/migration ai/.ci-migration
    docker build --target test --build-arg UV_VERSION="$UV_VERSION" -t "$AI_IMAGE:test-$TAG" ai/
    rm -rf ai/.ci-migration

워커 쪽 준비는 끝났다 — 위 `_migration_dir()` 이 그 경로를 스스로 찾는다. 환경변수를 넘길
필요도 없다. 켜지 않으면 지금처럼 건너뛰고, 그때도 **다른 테스트를 막지는 않는다.**
"""

from __future__ import annotations

import json
import os
import re
from pathlib import Path
from typing import Any, get_args

import httpx
import pytest
import respx

from tests.conftest import BACKEND, HEADERS


def _migration_dir() -> Path:
    """마이그레이션을 어디서 찾나 — 순서대로 본다.

    1. `AI_TEST_MIGRATION_DIR` 환경변수 (명시적 지정)
    2. 저장소 전체 체크아웃의 `backend/src/main/resources/db/migration`
    3. `<ai>/.ci-migration` — **컨테이너 빌드용 훅.** 빌드 컨텍스트가 `ai/` 하나뿐이라
       백엔드 경로가 이미지에 없다. Jenkins 가 빌드 직전에 한 줄 복사해 두면 이 가드가
       컨테이너에서도 돈다(아래 참고). 테스트가 pytest 안에서 도는 동안 볼륨은 못 쓴다 —
       `docker build` 시점이라 마운트가 불가능하다.
    """
    override = os.environ.get("AI_TEST_MIGRATION_DIR")
    if override:
        return Path(override)
    here = Path(__file__).resolve()
    repo = here.parents[3] / "backend/src/main/resources/db/migration"
    return repo if repo.is_dir() else here.parents[2] / ".ci-migration"


MIGRATIONS = sorted(_migration_dir().glob("V*.sql"))

# **모듈 수준에서 건너뛴다.** `pytest.mark.skipif` 는 테스트만 건너뛰고 아래 `TABLES = _tables()`
# 는 수집 시점에 그대로 실행된다 — 파일이 없으면 수집이 통째로 깨진다. 실제로 CI 를 세웠다:
# 컨테이너 빌드는 `ai/` 만 복사하므로 `parents[3]` 이 `/` 가 되어 백엔드 경로가 없다.
if not MIGRATIONS:
    pytest.skip(f"백엔드 마이그레이션이 없다: {_migration_dir()}", allow_module_level=True)


# ---------------------------------------------------------------- DDL 읽기

_COLUMN = re.compile(
    r"^\s{4}(?P<name>[a-z_]+)\s+(?P<type>[A-Z]+(?:\(\d+(?:,\s*\d+)?\))?)(?P<rest>.*)$"
)
_CHECK_IN = re.compile(r"CHECK\s*\((?P<col>[a-z_]+)\s+IN\s*\((?P<values>[^)]*)\)", re.I)


def _tables() -> dict[str, dict[str, dict[str, Any]]]:
    """마이그레이션 전체의 `CREATE TABLE` 을 {테이블: {컬럼: 스펙}} 으로.

    한 파일만 읽지 않는다 — 증빙은 `V6`, 매출 인쇄물은 `V8` 에 있고 다음
    테이블이 어느 번호로 들어올지 모른다. 파일 이름을 여기 적으면 그때마다
    테스트를 고쳐야 한다.
    """
    sql = chr(10).join(path.read_text(encoding="utf-8") for path in MIGRATIONS)
    tables: dict[str, dict[str, dict[str, Any]]] = {}
    for block in re.finditer(r"CREATE TABLE core\.(?P<name>\w+)\s*\((?P<body>.*?)\n\);", sql, re.S):
        columns: dict[str, dict[str, Any]] = {}
        body = block.group("body")
        for line in body.split("\n"):
            hit = _COLUMN.match(line.rstrip().rstrip(","))
            if not hit or hit.group("name").upper() in {"CONSTRAINT", "PRIMARY"}:
                continue
            raw_type = hit.group("type")
            size = re.search(r"\((\d+)", raw_type)
            scale = re.search(r"\(\d+,\s*(\d+)\)", raw_type)
            columns[hit.group("name")] = {
                "type": raw_type.split("(")[0],
                "max": int(size.group(1)) if size else None,
                "scale": int(scale.group(1)) if scale else None,
                "nullable": "NOT NULL" not in hit.group("rest"),
                "allowed": None,
            }
        for check in _CHECK_IN.finditer(body):
            column = check.group("col")
            if column in columns:
                columns[column]["allowed"] = {
                    value.strip().strip("'") for value in check.group("values").split(",")
                }
        tables[block.group("name")] = columns
    return tables


TABLES = _tables()


def test_the_ddl_parser_actually_found_the_tables():
    """파서가 빈손이면 아래 검사들이 조용히 전부 통과한다 — 그것부터 막는다."""
    assert set(TABLES) >= {
        "evidence_extraction",
        "evidence_item",
        "product_sales_extraction",
    }, sorted(TABLES)
    extraction = TABLES["evidence_extraction"]
    assert extraction["pipeline"]["allowed"] == {"OCR_PIPELINE", "VLM", "STUB"}
    assert extraction["status"]["allowed"] == {"COMPLETED", "PARTIAL"}
    assert extraction["model_name"]["nullable"] is False
    assert extraction["model_version"]["nullable"] is True  # ⑤ — 워커는 항상 null 을 보낸다
    assert TABLES["evidence_item"]["raw_name"]["max"] == 255


# ---------------------------------------------------------------- 값 대조

#: 백엔드 `EvidenceExtractionService` 가 콜백 키를 컬럼에 넣는 방식 그대로.
EXTRACTION_COLUMNS = {
    "model_name": "modelName",
    "model_version": "modelVersion",
    "prompt_version": "promptVersion",
    "pipeline": "pipeline",
    "status": "status",
}
ITEM_COLUMNS = {
    "raw_name": "rawName",
    "quantity": "quantity",
    "unit": "unit",
    "unit_price": "unitPrice",
    "amount": "amount",
    "discount_amount": "discountAmount",
    "supply_amount": "supplyAmount",
    "vat_amount": "vatAmount",
}


def violations(table: str, mapping: dict[str, str], row: dict[str, Any]) -> list[str]:
    """이 값들을 그대로 INSERT 하면 무엇이 깨지나."""
    problems: list[str] = []
    for column, key in mapping.items():
        spec = TABLES[table][column]
        value = row.get(key)
        if value is None:
            if not spec["nullable"]:
                msg = f"{table}.{column} 은 NOT NULL 인데 {key} 가 없다"
                problems.append(msg)
            continue
        if spec["type"] == "VARCHAR" and len(str(value)) > spec["max"]:
            problems.append(
                f"{table}.{column} 은 VARCHAR({spec['max']}) 인데 {key} 가 {len(str(value))}자다"
            )
        if spec["allowed"] is not None and value not in spec["allowed"]:
            problems.append(
                f"{table}.{column} CHECK 는 {sorted(spec['allowed'])} 인데 {key}={value!r}"
            )
        # NUMERIC(p, s) — 정수부가 p-s 자리를 넘으면 INSERT 에서 넘친다
        if spec["type"] == "NUMERIC" and spec["max"] is not None:
            limit = 10 ** (spec["max"] - (spec["scale"] or 0))
            if abs(float(value)) >= limit:
                problems.append(
                    f"{table}.{column} 은 NUMERIC({spec['max']},{spec['scale']}) 인데 "
                    f"{key}={value} 는 정수부가 넘친다"
                )
    return problems


def _stub_callback(client, endpoint: str, job_id: int, payload: dict) -> dict[str, Any]:
    with respx.mock(base_url=BACKEND) as mock:
        route = mock.post(f"/internal/v1/ai-jobs/{job_id}/result").mock(
            return_value=httpx.Response(200, json={"success": True})
        )
        assert client.post(endpoint, json=payload, headers=HEADERS).status_code == 202
    return json.loads(route.calls.last.request.content)["data"]


def test_stub_callback_fits_every_v6_column(client):
    """⑤ 가 이 테스트다. pipeline=STUB · modelVersion=null · promptVersion 이 들어가야 한다."""
    data = _stub_callback(
        client,
        "/internal/v1/ai/evidence-extraction",
        9013,
        {"jobId": 9013, "evidenceId": 501, "fileObjectKey": "evidences/11/501.jpg"},
    )
    assert violations("evidence_extraction", EXTRACTION_COLUMNS, data) == []
    for item in data["items"]:
        assert violations("evidence_item", ITEM_COLUMNS, item) == [], item["rawName"]


def test_numeric_overflow_is_caught():
    """검사기가 실제로 잡는지 — 아무것도 안 잡으면 위 테스트가 무의미하다."""
    assert violations("evidence_item", ITEM_COLUMNS, {"rawName": "우유", "amount": 10**12})
    assert violations("evidence_item", ITEM_COLUMNS, {"rawName": "우유", "unit": "가" * 21})
    assert violations("evidence_item", ITEM_COLUMNS, {})  # rawName 이 NOT NULL
    assert violations("evidence_extraction", EXTRACTION_COLUMNS, {
        "modelName": "m", "pipeline": "MAGIC", "status": "COMPLETED",
    })  # fmt: skip


def test_worker_schema_caps_the_lengths_v6_requires():
    """워커가 길이를 막지 않으면 긴 상품명이 INSERT 에서 터진다 — 계약 쪽에서 막아야 한다.

    모델이 낸 값을 그대로 흘려보내면 `raw_name VARCHAR(255)`·`unit VARCHAR(20)` 를 넘길 수
    있다. 검산·게이트를 다 통과한 결과가 저장 단계에서만 죽으면 원인을 찾기 어렵다.
    """
    from app.ocr.pipelines.vlm_output import LlmEvidence, LlmFieldConfidence, LlmItem, to_evidence

    confidence = LlmFieldConfidence(
        supplierName=0.9,
        supplierBusinessNo=0.9,
        issuedDate=0.9,
        totalAmount=0.9,
        vatAmount=0.9,
        items=0.9,
    )
    out = LlmEvidence(
        supplierName="가게",
        supplierBusinessNo=None,
        issuedDate=None,
        taxableAmount=None,
        vatAmount=None,
        taxFreeAmount=None,
        discountAmount=None,
        totalAmount=None,
        supplyAmount=None,
        items=[
            LlmItem(
                rawName="상" * 400,
                quantity=None,
                unit="개" * 40,
                unitPrice=None,
                amount=None,
                discountAmount=None,
            )
        ],
        fieldConfidence=confidence,
        warnings=[],
    )
    extraction = to_evidence(out)
    row = extraction.items[0].model_dump(by_alias=True, mode="json")
    assert violations("evidence_item", ITEM_COLUMNS, row) == []
    # 버리지 않고 자른다 — 나머지 값은 멀쩡하다. 자른 사실은 남긴다.
    assert row["rawName"].startswith("상상")
    assert any("품목명" in w for w in extraction.warnings)
    assert any("단위" in w for w in extraction.warnings)


def test_worker_limits_still_match_the_ddl():
    """한도는 워커가 들고 있어야 한다(다른 컨테이너라 DDL 을 못 읽는다). 어긋나면 여기서 운다."""
    from app.ocr.schemas import MAX_RAW_NAME, MAX_UNIT

    assert MAX_RAW_NAME == TABLES["evidence_item"]["raw_name"]["max"]
    assert MAX_UNIT == TABLES["evidence_item"]["unit"]["max"]


# ------------------------------------------------- 매출 인쇄물 (20.9 → V8)

#: 백엔드가 20.9 콜백 키를 `product_sales_extraction` 에 넣는 방식.
#: **상품 행은 여기 없다** — 합의 ④ 대로 행은 저장하지 않는다(14.7 "OCR 결과는 후보").
#: `business_id`·`capture_type` 은 콜백이 아니라 Job·요청에서 온다(합의 ⑦).
SALES_COLUMNS = {
    "model_name": "modelName",
    "model_version": "modelVersion",
    "prompt_version": "promptVersion",
    "pipeline": "pipeline",
    "status": "status",
}


SALES_JOB = 9401
SALES_REQUEST = {
    "jobId": SALES_JOB,
    "businessId": 11,
    "captureType": "PRINTOUT",
    "fileObjectKeys": ["product-sales/11/tmp-202612-1.jpg"],
}


def _sales_callback(client) -> dict[str, Any]:
    endpoint, job_id, payload = (
        "/internal/v1/ai/product-sales-extraction",
        SALES_JOB,
        SALES_REQUEST,
    )
    with respx.mock(base_url=BACKEND) as mock:
        route = mock.post(f"/internal/v1/ai-jobs/{job_id}/result").mock(
            return_value=httpx.Response(200, json={"success": True})
        )
        assert client.post(endpoint, json=payload, headers=HEADERS).status_code == 202
    return json.loads(route.calls.last.request.content)["data"]


def test_product_sales_callback_fits_v8(client):
    data = _sales_callback(client)
    assert violations("product_sales_extraction", SALES_COLUMNS, data) == []


def test_period_source_can_only_be_header(client):
    """V8 은 `period_source` 를 `HEADER` 하나로 좁혔다 — 「워커는 추론하지 않는다」의 스키마 표현.

    `_CHECK_IN` 이 못 읽는 모양이라(`IS NULL OR = 'HEADER'`) 따로 본다. 계약의
    `SalesPeriod.source` 가 넓어지면 여기서 운다.
    """
    from app.ocr.schemas import SalesPeriod

    allowed = set(get_args(SalesPeriod.model_fields["source"].annotation)) - {type(None)}
    literal = {value for member in allowed for value in get_args(member)}
    assert literal == {"HEADER"}, literal

    source = _sales_callback(client)["period"]["source"]
    assert source is None or source == "HEADER"


def test_capture_type_matches_the_v8_check(client):
    """`capture_type` 은 요청에서 오지만 CHECK 은 V8 이 갖는다 — 두 값 집합이 같아야 한다."""
    from app.ocr.schemas import CaptureType

    assert (
        set(get_args(CaptureType)) == TABLES["product_sales_extraction"]["capture_type"]["allowed"]
    )
