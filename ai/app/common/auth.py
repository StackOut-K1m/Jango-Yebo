"""내부 인증 — `Authorization: Bearer {serviceToken}` (명세 20.1, 공통규약 21절).

외부 클라이언트에는 노출하지 않는 서비스 간 고정 토큰이다. 회전 정책은 별도.
"""

import hmac
from typing import Annotated

from fastapi import Header, Request

from app.common.errors import AppError, ErrorCode


def require_service_token(
    request: Request,
    authorization: Annotated[str | None, Header()] = None,
) -> None:
    settings = request.app.state.settings
    if settings.service_token is None:
        # 토큰이 없으면 "누구나 통과"가 아니라 "아무도 통과 못 함"이어야 한다.
        raise AppError(
            ErrorCode.INTERNAL_AUTH_FAILED, "서비스 토큰이 설정되지 않았습니다 (SERVICE_TOKEN)."
        )
    expected = settings.service_token.get_secret_value().encode()
    scheme, _, token = (authorization or "").partition(" ")
    if scheme.lower() != "bearer" or not token:
        raise AppError(ErrorCode.INTERNAL_AUTH_FAILED, "서비스 간 인증에 실패했습니다.")
    if not hmac.compare_digest(token.strip().encode(), expected):
        raise AppError(ErrorCode.INTERNAL_AUTH_FAILED, "서비스 간 인증에 실패했습니다.")
