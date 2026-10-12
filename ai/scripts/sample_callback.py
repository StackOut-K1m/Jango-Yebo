"""워커가 실제로 보내는 콜백 본문을 그대로 찍는다 — 백엔드 영속화·연동 테스트의 픽스처.

    uv run --frozen python -X utf8 -m scripts.sample_callback              # 둘 다
    uv run --frozen python -X utf8 -m scripts.sample_callback evidence     # 20.4 만
    uv run --frozen python -X utf8 -m scripts.sample_callback --out artifacts/fixtures

모델을 부르지 않는다(STUB 파이프라인 + 실제 검산기). 계약이 바뀌면 이 출력도 같이 바뀌므로
문서에 손으로 베낀 예시와 달리 낡지 않는다. `POST /internal/v1/ai-jobs/{jobId}/result` 의
요청 본문이 곧 이 JSON 이다 — 헤더는 `Authorization: Bearer <서비스 토큰>` 과
`X-Request-Id: ai-job-{jobId}-result`(고정) 다.
"""

from __future__ import annotations

import argparse
import asyncio
import json
from pathlib import Path
from typing import Any

from app.common import callback as cb
from app.ocr.pipelines.stub import StubPipeline
from app.ocr.router import evidence_result, product_sales_result
from app.ocr.schemas import EvidenceExtractionRequest, ProductSalesExtractionRequest

JOB_ID = 9013


async def evidence_body() -> tuple[int, dict[str, Any]]:
    """20.4 증빙 추출 — router.run_evidence_job 과 같은 순서로 조립한다."""
    request = EvidenceExtractionRequest(
        job_id=JOB_ID, evidence_id=501, file_object_key="evidences/11/501.jpg"
    )
    extraction, meta = await StubPipeline().extract_evidence([], request)
    return request.job_id, cb.completed(meta, evidence_result(request.evidence_id, extraction))


async def product_sales_body() -> tuple[int, dict[str, Any]]:
    """20.9 매출 인쇄물 — router.run_product_sales_job 과 같은 순서."""
    request = ProductSalesExtractionRequest(
        job_id=JOB_ID + 1,
        business_id=1,
        capture_type="PRINTOUT",
        file_object_keys=["product-sales/1/9014-1.jpg"],
    )
    extraction, meta = await StubPipeline().extract_product_sales([], request)
    return request.job_id, cb.completed(meta, product_sales_result(extraction))


async def main() -> int:
    parser = argparse.ArgumentParser(
        description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter
    )
    parser.add_argument("which", nargs="?", choices=("evidence", "product-sales"), default=None)
    parser.add_argument("--out", type=Path, help="디렉터리에 <이름>.json 으로도 저장")
    args = parser.parse_args()

    bodies = {"evidence": await evidence_body(), "product-sales": await product_sales_body()}
    if args.which:
        bodies = {args.which: bodies[args.which]}
    for name, (job_id, body) in bodies.items():
        text = json.dumps(body, ensure_ascii=False, indent=2)
        print(f"// POST /internal/v1/ai-jobs/{job_id}/result  ({name})")
        print(text)
        if args.out:
            args.out.mkdir(parents=True, exist_ok=True)
            (args.out / f"callback-{name}.json").write_text(text + "\n", encoding="utf-8")
            print(f"// → {args.out / f'callback-{name}.json'}")
    return 0


if __name__ == "__main__":
    raise SystemExit(asyncio.run(main()))
