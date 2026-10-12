"""X-Request-Id (00_공통규약 2.8, 20.1).

내부 API 의 모든 변경 요청에 필수. 워커는 무상태라 중복 제거는 API 서버(Redis)가 하고,
워커는 (1) 없으면 거절 (2) 응답 헤더로 되돌려 주고 (3) 로그에 붙인다.
"""

import contextvars
from typing import Annotated

from fastapi import Header, Response

from app.common import errors

request_id_var: contextvars.ContextVar[str] = contextvars.ContextVar("request_id", default="-")

_MAX_LENGTH = 128


def require_request_id(
    response: Response,
    x_request_id: Annotated[str | None, Header()] = None,
) -> str:
    value = (x_request_id or "").strip()
    if not value or len(value) > _MAX_LENGTH:
        raise errors.AppError(
            errors.ErrorCode.VALIDATION_FAILED, "X-Request-Id 헤더가 필요합니다 (명세 20.1)."
        )
    request_id_var.set(value)
    response.headers["X-Request-Id"] = value
    return value
