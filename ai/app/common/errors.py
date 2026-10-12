"""오류 코드와 예외 → Envelope 변환.

코드는 00_공통규약 22절의 45종 중 워커가 내는 것만 쓴다. 새 코드를 만들지 않는다 —
백엔드 ErrorCode enum 에 없는 값은 결국 AI_ANALYSIS_FAILED 로 뭉개진다.
"""

from __future__ import annotations

import logging
from enum import StrEnum
from typing import Any

from fastapi import FastAPI, Request
from fastapi.exceptions import RequestValidationError
from fastapi.responses import JSONResponse
from starlette.exceptions import HTTPException as StarletteHTTPException

from app.common.envelope import fail
from app.common.request_id import request_id_var

log = logging.getLogger(__name__)


class ErrorCode(StrEnum):
    INTERNAL_AUTH_FAILED = "INTERNAL_AUTH_FAILED"  # 401 서비스 간 인증 실패
    VALIDATION_FAILED = "VALIDATION_FAILED"  # 400 요청값 검증 실패
    AI_ANALYSIS_FAILED = "AI_ANALYSIS_FAILED"  # 502 모델 호출·분석 실패
    INTERNAL_ERROR = "INTERNAL_ERROR"  # 500


_HTTP_STATUS: dict[ErrorCode, int] = {
    ErrorCode.INTERNAL_AUTH_FAILED: 401,
    ErrorCode.VALIDATION_FAILED: 400,
    ErrorCode.AI_ANALYSIS_FAILED: 502,
    ErrorCode.INTERNAL_ERROR: 500,
}


class AppError(Exception):
    def __init__(
        self,
        code: ErrorCode,
        message: str,
        *,
        status: int | None = None,
        detail: dict[str, Any] | None = None,
    ) -> None:
        super().__init__(message)
        self.code = code
        self.message = message
        self.status = status or _HTTP_STATUS[code]
        self.detail = detail


def _headers(request: Request) -> dict[str, str]:
    """오류 응답에도 X-Request-Id 를 되돌려 준다.

    본문 디코딩 실패처럼 의존성(require_request_id)까지 가지 못한 요청은 컨텍스트 변수가
    비어 있으므로 원본 헤더에서 직접 읽는다.
    """
    request_id = request_id_var.get()
    if request_id == "-":
        request_id = (request.headers.get("X-Request-Id") or "").strip()[:128]
    return {"X-Request-Id": request_id} if request_id and request_id != "-" else {}


def register_error_handlers(app: FastAPI) -> None:
    @app.exception_handler(AppError)
    async def _app_error(request: Request, exc: AppError) -> JSONResponse:
        return JSONResponse(
            status_code=exc.status,
            content=fail(exc.code, exc.message, exc.detail),
            headers=_headers(request),
        )

    @app.exception_handler(RequestValidationError)
    async def _validation(request: Request, exc: RequestValidationError) -> JSONResponse:
        # 입력값(input)은 담지 않는다 — 요청 본문에 민감 정보가 있을 수 있다.
        errors = [
            {
                "loc": ".".join(str(p) for p in e.get("loc", ())),
                "msg": e.get("msg"),
                "type": e.get("type"),
            }
            for e in exc.errors()
        ]
        return JSONResponse(
            status_code=400,
            content=fail(
                ErrorCode.VALIDATION_FAILED, "요청값 검증에 실패했습니다.", {"errors": errors}
            ),
            headers=_headers(request),
        )

    @app.exception_handler(StarletteHTTPException)
    async def _http_exception(request: Request, exc: StarletteHTTPException) -> JSONResponse:
        # 프레임워크가 직접 던지는 것들 — 본문 디코딩 실패(400), 없는 경로(404), 허용되지 않은
        # 메서드(405) 등. 기본 `{"detail": ...}` 대신 Envelope 으로 통일한다.
        if exc.status_code == 401:
            code = ErrorCode.INTERNAL_AUTH_FAILED
        elif exc.status_code >= 500:
            code = ErrorCode.INTERNAL_ERROR
        else:
            code = ErrorCode.VALIDATION_FAILED
        message = exc.detail if isinstance(exc.detail, str) else "요청을 처리할 수 없습니다."
        return JSONResponse(
            status_code=exc.status_code,
            content=fail(code, message),
            headers={**_headers(request), **(exc.headers or {})},
        )

    @app.exception_handler(Exception)
    async def _unhandled(request: Request, exc: Exception) -> JSONResponse:
        log.exception("처리되지 않은 예외: %s", type(exc).__name__)
        return JSONResponse(
            status_code=500,
            content=fail(ErrorCode.INTERNAL_ERROR, "서버 오류가 발생했습니다."),
            headers=_headers(request),
        )
