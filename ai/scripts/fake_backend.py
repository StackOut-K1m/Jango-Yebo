"""가짜 API 서버 — 워커 콜백을 받아 계약을 검사한다. 백엔드·프론트 없이 HTTP 경로를 통째로 본다.

    # 1) 이 수신기를 띄운다 (기본 8001)
    uv run --frozen python -X utf8 -m scripts.fake_backend

    # 2) 다른 창에서 워커를 띄운다 — 콜백을 이쪽으로 보내게 한다
    BACKEND_BASE_URL=http://127.0.0.1:8001 AI_PIPELINE_MODE=stub \
        uv run --frozen uvicorn app.main:app --port 8000

    # 3) 또 다른 창에서 비동기 엔드포인트를 부른다
    curl -X POST http://127.0.0.1:8000/internal/v1/ai/evidence-extraction \
      -H "Authorization: Bearer $SERVICE_TOKEN" -H "X-Request-Id: t-1" \
      -H "Content-Type: application/json" \
      -d '{"jobId":9013,"evidenceId":501,"fileObjectKey":"e/501.jpg"}'

**무엇을 검사하나 (명세 20.2).** 그냥 받아 주면 계약 위반을 못 잡으므로, 받은 것을 뜯어본다.
규칙은 `callback_contract.py` 한 곳에 있다 — `callback_sink.py` 와 함께 쓴다.
- `Authorization: Bearer <SERVICE_TOKEN>` — 토큰이 틀리면 401 로 거절한다(워커가 진짜로 붙이는지)
- `X-Request-Id` == `ai-job-{jobId}-result` — 멱등 키 규약
- 본문이 공통 Envelope 인가 (`success`·`data`·`error`), `data.status` 가 COMPLETED/PARTIAL 인가
- 성공 본문에 `modelName`·`pipeline` 이 있는가

어긋난 것은 **경고로 찍고 그래도 200 을 준다** — 워커의 재시도 로직이 아니라 계약을 보려는 것이므로.
`--strict` 를 주면 어긋나면 400 을 돌려 워커의 4xx 처리(즉시 포기)도 함께 볼 수 있다.

`--out DIR` 을 주면 받은 본문을 `callback-{jobId}.json` 으로 저장한다 — 백엔드 픽스처로 쓸 수 있다.
`scripts/sample_callback.py` 는 "보낼 본문"을 미리 찍고, 이 스크립트는 "실제로 온 것"을 받는다.
"""

from __future__ import annotations

import argparse
import json
from pathlib import Path
from typing import Any

import uvicorn
from fastapi import FastAPI, Request, Response

from app.config import Settings
from scripts.callback_contract import check_contract


def create_app(*, token: str | None, out: Path | None, strict: bool) -> FastAPI:
    app = FastAPI(title="fake-backend")
    app.state.received = 0

    @app.post("/internal/v1/ai-jobs/{job_id}/result")
    async def result(job_id: int, request: Request) -> Response:
        auth = request.headers.get("authorization")
        if token and auth != f"Bearer {token}":
            print(f"\n[401] jobId={job_id} — Authorization 이 {auth!r}")
            return Response(status_code=401)

        body = await request.json()
        problems = check_contract(job_id, request.headers, body)
        app.state.received += 1
        mark = "OK " if not problems else "위반"
        print(f"\n[{mark}] 콜백 {app.state.received}건째 — jobId={job_id}")
        for problem in problems:
            print(f"   ! {problem}")
        print(json.dumps(body, ensure_ascii=False, indent=2))

        if out is not None:
            out.mkdir(parents=True, exist_ok=True)
            target = out / f"callback-{job_id}.json"
            target.write_text(json.dumps(body, ensure_ascii=False, indent=2) + "\n", "utf-8")
            print(f"   → {target}")

        if problems and strict:
            return Response(status_code=400)
        return Response(status_code=200, content=b'{"success":true}', media_type="application/json")

    @app.get("/health")
    async def health() -> dict[str, Any]:
        return {"received": app.state.received}

    return app


def main() -> int:
    parser = argparse.ArgumentParser(
        description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter
    )
    parser.add_argument("--port", type=int, default=8001)
    parser.add_argument("--out", type=Path, help="받은 본문을 저장할 디렉터리")
    parser.add_argument(
        "--strict", action="store_true", help="계약 위반이면 400 — 워커의 4xx 포기 동작도 본다"
    )
    parser.add_argument("--no-token", action="store_true", help="Authorization 검사를 끈다")
    args = parser.parse_args()

    settings = Settings()
    token = None
    if not args.no_token and settings.service_token is not None:
        token = settings.service_token.get_secret_value()
    print(
        f"가짜 API 서버 :{args.port} — 콜백 대기. "
        f"토큰 검사 {'켬' if token else '끔'}, 위반 시 {'400' if args.strict else '200'}"
    )
    print(f"워커를 이렇게 띄운다: BACKEND_BASE_URL=http://127.0.0.1:{args.port}")
    uvicorn.run(create_app(token=token, out=args.out, strict=args.strict), port=args.port)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
