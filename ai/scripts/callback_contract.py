"""콜백 규약(명세 20.2) 검사 — **한 곳에만 둔다.**

개발용 콜백 수신기가 두 개 있다. `callback_sink.py`(모듈 앱, 1번 파트)와
`fake_backend.py`(CLI, 2번 파트)가 같은 날 각자 만들어졌고, **같은 규약을 서로 다른
엄격도로** 적고 있었다.

| | callback_sink 옛 `_check` | fake_backend `check_contract` |
| --- | --- | --- |
| `X-Request-Id` | 봄 | 봄 |
| Authorization | 있는지·Bearer 형식만 | 토큰 값까지(401 로 거절) |
| Envelope | `success` 키가 있는지 | 키 집합이 정확히 셋인지 |
| `data.status` | **안 봄** | COMPLETED/PARTIAL |
| `modelName`·`pipeline` | **안 봄** | 있어야 함 |
| success ↔ error 정합 | **안 봄** | 봄 |

그래서 한쪽에서 "규약 검사 통과"가 뜨는 본문이 다른 쪽에서는 위반이었다. 규칙이 두 곳에
살면 반드시 이렇게 갈라진다 — 워커에 `evidence-validation`(20.4 확장)을 만든 이유와 같은
문제다. 두 수신기가 이 파일을 함께 쓴다.

**엄격한 쪽을 정본으로 둔다.** 덜 보는 검사기는 계약 위반을 통과시켜 나중에 백엔드에서
중복 저장·null 필드로 드러난다. 토큰 **값** 검사만 여기 없다 — 그건 401 로 거절하는
동작이라 수신기 쪽 정책이다(`fake_backend` 는 거절하고 `callback_sink` 는 안 한다).
"""

from __future__ import annotations

from typing import Any, Protocol

EXPECTED_STATUS = {"COMPLETED", "PARTIAL"}


class _Headers(Protocol):
    """`httpx.Headers`·Starlette `Headers` 둘 다 만족한다 (소문자 키 조회)."""

    def get(self, key: str, default: Any = None) -> Any: ...


def check_contract(job_id: int, headers: _Headers, body: Any) -> list[str]:
    """명세 20.2 위반 목록. 빈 리스트면 통과."""
    problems: list[str] = []

    want_request_id = f"ai-job-{job_id}-result"
    got_request_id = headers.get("x-request-id")
    if got_request_id != want_request_id:
        problems.append(f"X-Request-Id 가 {got_request_id!r} — {want_request_id!r} 여야 한다")

    # 값은 보지 않는다 — 붙여 보내는지만 본다. 백엔드 InternalSecurityConfig 가 요구한다.
    auth = headers.get("authorization")
    if not auth:
        problems.append("Authorization 헤더가 없다 (백엔드가 거절한다)")
    elif not str(auth).startswith("Bearer "):
        problems.append(f"Authorization 이 Bearer 형식이 아니다: {str(auth)[:12]}…")

    if not isinstance(body, dict):
        problems.append("본문이 JSON 객체가 아니다")
        return problems
    if set(body) != {"success", "data", "error"}:
        problems.append(f"Envelope 키가 {sorted(body)} — success·data·error 여야 한다")

    if body.get("success"):
        data = body.get("data") or {}
        if data.get("status") not in EXPECTED_STATUS:
            problems.append(f"data.status 가 {data.get('status')!r} — COMPLETED/PARTIAL 여야 한다")
        for key in ("modelName", "pipeline"):
            if not data.get(key):
                problems.append(f"data.{key} 가 없다 (모델 메타는 항상 붙어야 한다)")
        if body.get("error") is not None:
            problems.append("success=true 인데 error 가 null 이 아니다")
    else:
        error = body.get("error") or {}
        if not error.get("code"):
            problems.append("실패 콜백에 error.code 가 없다")
        if body.get("data") is not None:
            problems.append("success=false 인데 data 가 null 이 아니다")
    return problems
