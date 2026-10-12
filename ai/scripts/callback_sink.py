"""콜백 수신기 — 백엔드 자리에 세우는 개발용 스텁.

    uv run --frozen uvicorn scripts.callback_sink:app --port 8099
    BACKEND_BASE_URL=http://localhost:8099 uv run --frozen uvicorn app.main:app --port 8000

워커가 비동기 작업을 끝내면 `POST /internal/v1/ai-jobs/{jobId}/result` 로 결과를 보낸다
(명세 20.2). 백엔드가 아직 그 핸들러를 안 만들었으므로, **워커 쪽 계약이 실제로 도는지**
확인하려면 받아 줄 무언가가 필요하다. 이 파일이 그것이다.

받은 본문을 `artifacts/callback/job-{jobId}.json` 에 그대로 저장한다. 그 JSON 이 곧
백엔드가 구현해야 할 입력이고, `98_AI_백엔드_합의.md` 에 붙일 픽스처다.

## 검증하는 것

명세가 못박은 콜백 규약을 확인한다. 조용히 어긋나면 나중에 백엔드에서 중복 저장 사고가 난다.
**규칙은 `callback_contract.py` 한 곳에 있다** — `fake_backend.py`(CLI 수신기)와 함께 쓴다.
전에는 두 수신기가 같은 규약을 서로 다른 엄격도로 적고 있어서, 한쪽에서 통과한 본문이
다른 쪽에서는 위반이었다.

**틀려도 거절하지 않는다.** 여기서 4xx 를 내면 워커가 영구 실패로 보고 재시도를 포기해
(`callback.py` — `< 500` 은 즉시 포기) 무엇이 왔는지 볼 수 없게 된다. 문제는 화면에 적고
200 을 준다. 진짜 검증은 백엔드가 한다.
"""

from __future__ import annotations

import json
from datetime import datetime
from pathlib import Path
from typing import Any

from fastapi import FastAPI, Request

from scripts.callback_contract import check_contract

OUT_ROOT = Path(__file__).resolve().parent.parent / "artifacts" / "callback"

app = FastAPI(title="Callback Sink (dev)", docs_url=None, redoc_url=None)


@app.post("/internal/v1/ai-jobs/{job_id}/result")
async def receive_result(job_id: int, request: Request) -> dict[str, Any]:
    body = await request.json()
    problems = check_contract(job_id, request.headers, body)

    OUT_ROOT.mkdir(parents=True, exist_ok=True)
    path = OUT_ROOT / f"job-{job_id}.json"
    path.write_text(json.dumps(body, ensure_ascii=False, indent=2), encoding="utf-8")

    stamp = datetime.now().strftime("%H:%M:%S")
    data = body.get("data") or {}
    results = data.get("results") or []
    print(f"\n{'=' * 78}")
    print(f"[{stamp}] 콜백 수신 jobId={job_id}")
    print(f"  X-Request-Id : {request.headers.get('x-request-id')}")
    print(f"  Authorization: {'있음' if request.headers.get('authorization') else '없음'}")
    print(f"  success      : {body.get('success')}")
    if body.get("success"):
        print(f"  status       : {data.get('status')}")
        print(f"  modelName    : {data.get('modelName')}")
        print(f"  promptVersion: {data.get('promptVersion')}")
        print(f"  pipeline     : {data.get('pipeline')}")
        print(f"  results      : {len(results)}건")
    else:
        print(f"  error        : {body.get('error')}")
    for problem in problems:
        print(f"  [규약 위반] {problem}")
    if not problems:
        print("  규약 검사 통과")
    print(f"  → {path}")
    print("=" * 78)

    # 워커는 < 500 을 영구 실패로 본다. 규약 위반이 있어도 200 을 줘야 본문을 볼 수 있다.
    return {"success": True, "data": None, "error": None}


@app.get("/health")
async def health() -> dict[str, str]:
    return {"status": "ok"}
