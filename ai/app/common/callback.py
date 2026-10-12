"""비동기 작업 결과 콜백 — `POST /internal/v1/ai-jobs/{jobId}/result` (명세 20.2).

본문은 공통 Envelope 이다:
- 완료: `{"success": true, "data": {"status": "COMPLETED", "modelName": ..., ...결과}}`
- 실패: `{"success": false, "error": {"code", "message", "detail"}}`
  → API 서버는 Job 을 FAILED 로 바꾸고 error 에 code/message 를 담는다 (공통규약 2.7).

콜백의 X-Request-Id 는 `ai-job-{jobId}-result` 로 고정한다. 재시도해도 같은 ID 라
API 서버가 중복 저장하지 않는다. 최종 실패해도 예외로 끝낸다 — JobSweeper 가 10분 뒤 회수한다.
"""

from __future__ import annotations

import asyncio
import logging
from typing import Any, Literal

import httpx

from app.common.model_meta import ModelMeta
from app.config import Settings

log = logging.getLogger(__name__)

RESULT_PATH = "/internal/v1/ai-jobs/{job_id}/result"

# 결과 본문의 `data.status` (명세 20.2). Job 상태(`JobStatus`)와 **다른 축**이다 —
# 백엔드는 PARTIAL 도 Job 은 COMPLETED 로 저장한다 (구현현황 §931).
ResultStatus = Literal["COMPLETED", "PARTIAL"]


class CallbackError(Exception):
    pass


class CallbackRejected(CallbackError):
    """4xx — 재시도해도 결과가 같다."""


class CallbackFailed(CallbackError):
    """재시도를 다 썼다 (5xx·연결 오류)."""


def completed(
    meta: ModelMeta, result: dict[str, Any], *, status: ResultStatus = "COMPLETED"
) -> dict[str, Any]:
    """성공 콜백. `status`를 넘기지 않으면 `COMPLETED` 다 — 기존 호출부는 그대로 둔다.

    `PARTIAL` 은 **결과가 있는데 일부를 버렸다**는 뜻이다 (명세 20.2). 브리핑에서 환각
    숫자가 든 문장을 폐기했을 때 쓴다 (20.7). 실패가 아니므로 `failed()` 로 보내면 안 된다 —
    백엔드가 `PARTIAL` 도 Job `COMPLETED` 로 저장하고 `resultRef.partial` 로 구분한다
    (구현현황 §931). `FAILED` 로 접으면 쓸 수 있는 결과를 버리게 된다.
    """
    data = {"status": status, **meta.model_dump(by_alias=True), **result}
    return {"success": True, "data": data, "error": None}


def failed(code: str, message: str, detail: dict[str, Any] | None = None) -> dict[str, Any]:
    error: dict[str, Any] = {"code": str(code), "message": message}
    if detail:
        error["detail"] = detail
    return {"success": False, "data": None, "error": error}


class CallbackClient:
    def __init__(self, settings: Settings) -> None:
        headers = {"Content-Type": "application/json"}
        if settings.service_token is not None:
            headers["Authorization"] = f"Bearer {settings.service_token.get_secret_value()}"
        self._client = httpx.AsyncClient(
            base_url=settings.backend_base_url,
            timeout=settings.callback_timeout_seconds,
            headers=headers,
        )
        self._max_retries = settings.callback_max_retries
        self._backoff = settings.callback_backoff_seconds

    async def aclose(self) -> None:
        await self._client.aclose()

    async def send(self, job_id: int, body: dict[str, Any]) -> None:
        url = RESULT_PATH.format(job_id=job_id)
        headers = {"X-Request-Id": f"ai-job-{job_id}-result"}
        last_error = ""
        for attempt in range(self._max_retries + 1):
            try:
                response = await self._client.post(url, json=body, headers=headers)
            except httpx.TransportError as exc:  # 연결·타임아웃 — 재시도 가능
                last_error = type(exc).__name__
            else:
                if response.is_success:
                    log.info("콜백 전송 완료 jobId=%s http=%s", job_id, response.status_code)
                    return
                if response.status_code < 500:
                    raise CallbackRejected(f"jobId={job_id} http={response.status_code}")
                last_error = f"HTTP {response.status_code}"
            if attempt < self._max_retries:
                await asyncio.sleep(self._backoff * (2**attempt))
        raise CallbackFailed(f"jobId={job_id} {last_error}")
