"""공통 응답 Envelope (00_공통규약 2.4). 요청·응답·콜백 본문 모두 이 모양을 쓴다."""

from typing import Any

from pydantic import BaseModel, ConfigDict
from pydantic.alias_generators import to_camel


class CamelModel(BaseModel):
    """JSON 은 camelCase, Python 은 snake_case. 모든 계약 스키마의 기반."""

    model_config = ConfigDict(alias_generator=to_camel, populate_by_name=True, extra="ignore")


class ApiError(CamelModel):
    code: str
    message: str
    detail: dict[str, Any] | None = None


class Envelope[T](CamelModel):
    success: bool
    data: T | None = None
    error: ApiError | None = None


def ok(data: Any) -> dict[str, Any]:
    return {"success": True, "data": data, "error": None}


def fail(code: str, message: str, detail: dict[str, Any] | None = None) -> dict[str, Any]:
    error: dict[str, Any] = {"code": str(code), "message": message}
    if detail:
        error["detail"] = detail
    return {"success": False, "data": None, "error": error}
