"""저장된 콜백 본문을 **진짜 백엔드에 쏜다** — 합동 검증(97 「승인 후 순서」 2번)용.

지금까지 수신기만 셋이었다(`fake_backend`·`callback_sink`·`sample_callback` 은 전부 받거나
찍기만 한다). 합동 단계에서 정작 필요한 것은 **보내는 쪽**이라 여기 만든다.

    # 증빙 (20.4)
    uv run --frozen python -X utf8 -m scripts.send_callback evidence --job-id 9013

    # 매출 인쇄물 (20.9)
    uv run --frozen python -X utf8 -m scripts.send_callback product-sales --job-id 9401

본문은 `sample_callback.py` 와 **같은 함수**(`router.evidence_result` · `product_sales_result`)
로 만든다 — 픽스처와 실제 콜백이 갈라지는 것을 이미 한 번 겪었다(매출 쪽에서 `validation` 이
빠졌다). 모델을 부르지 않으므로 비용 0 이다.

## 백엔드 쪽 전제

- `jobId` 가 **실제로 존재해야** 한다. `JOB` 행이 없으면 백엔드가 거절한다
- 매출 인쇄물은 `job_type = 'PRODUCT_SALES_OCR'`, 증빙은 `'EVIDENCE_EXTRACTION'`
- 14.7(업로드)이 아직 없어 매출 Job 을 만들 경로가 없다 — 그때까지는 행을 직접 넣는다

`--job-id` 를 주면 본문의 `jobId` 와 `X-Request-Id`(`ai-job-{id}-result`)가 함께 바뀐다.
멱등 키가 규약대로 붙는지도 이 경로로 확인된다(명세 20.2).
"""

from __future__ import annotations

import argparse
import asyncio
import json
import sys
from pathlib import Path
from typing import Any

import httpx

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

from app.config import Settings  # noqa: E402
from scripts.sample_callback import evidence_body, product_sales_body  # noqa: E402


async def build(which: str, job_id: int | None) -> tuple[int, dict[str, Any]]:
    default_id, body = await (evidence_body() if which == "evidence" else product_sales_body())
    return (job_id or default_id), body


async def run(which: str, job_id: int | None, base_url: str | None, dry_run: bool) -> int:
    settings = Settings()
    target = base_url or str(settings.backend_base_url or "")
    resolved, body = await build(which, job_id)

    if dry_run:
        print(f"[dry-run] POST {target}/internal/v1/ai-jobs/{resolved}/result")
        print(json.dumps(body, ensure_ascii=False, indent=2))
        return 0

    if not target:
        print("[실패] BACKEND_BASE_URL 이 없습니다. --base-url 로 주세요.")
        return 1
    token = settings.service_token
    if token is None:
        print("[실패] SERVICE_TOKEN 이 없습니다 — 백엔드가 401 로 거절합니다.")
        return 1

    url = f"{target.rstrip('/')}/internal/v1/ai-jobs/{resolved}/result"
    headers = {
        "Authorization": f"Bearer {token.get_secret_value()}",
        "X-Request-Id": f"ai-job-{resolved}-result",  # 명세 20.2 멱등 키
        "Content-Type": "application/json",
    }
    print(f"POST {url}")
    async with httpx.AsyncClient(timeout=15) as client:
        response = await client.post(url, json=body, headers=headers)

    print(f"← {response.status_code}")
    text = response.text.strip()
    if text:
        print(text[:600])
    if response.status_code == 404:
        print(f"\n힌트: jobId={resolved} 인 JOB 행이 없을 수 있습니다.")
    elif response.status_code == 401:
        print("\n힌트: SERVICE_TOKEN 이 백엔드 설정과 다릅니다.")
    return 0 if response.status_code < 400 else 1


def main() -> int:
    parser = argparse.ArgumentParser(
        description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter
    )
    parser.add_argument("which", choices=("evidence", "product-sales"))
    parser.add_argument("--job-id", type=int, default=None, help="백엔드에 실제로 있는 jobId")
    parser.add_argument("--base-url", default=None, help="기본값은 BACKEND_BASE_URL")
    parser.add_argument("--dry-run", action="store_true", help="보내지 않고 본문만 찍는다")
    args = parser.parse_args()
    return asyncio.run(run(args.which, args.job_id, args.base_url, args.dry_run))


if __name__ == "__main__":
    raise SystemExit(main())
