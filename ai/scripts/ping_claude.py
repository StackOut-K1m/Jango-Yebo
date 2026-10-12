"""Anthropic 연결 점검 — 직접 키든 게이트웨이(SSAFY GMS)든 우리가 쓰는 기능이 통과하는지 본다.

    uv run --frozen python -X utf8 -m scripts.ping_claude

    1) 텍스트 1회            — 인증·base URL·모델 허용 여부
    2) 구조화 출력(parse)    — output_config / output_format 통과 여부
    3) 서버측 폴백(beta)     — anthropic-beta 헤더 + fallbacks="default" 통과 여부
                              (CLAUDE_SERVER_FALLBACK=true 일 때만)
    4) 이미지 1장(64×64)     — 이미지 입력 통과 여부

각 단계는 max_tokens 를 작게 잡아 비용이 합쳐서 1센트 안팎이다. 실패하면 어느 단계에서 왜 막혔는지와
`.env` 에서 무엇을 바꾸면 되는지(예: CLAUDE_SERVER_FALLBACK=false, VISION_MODEL 교체)를 찍는다.
"""

from __future__ import annotations

import asyncio
import base64
import io

import anthropic
from PIL import Image
from pydantic import BaseModel, ConfigDict

from app.common.claude import FALLBACK_BETA, ClaudeClient
from app.config import Settings


class _Ping(BaseModel):
    model_config = ConfigDict(extra="forbid")
    ok: bool
    echo: str


def _print(step: str, ok: bool, detail: str) -> None:
    print(f"[{'OK' if ok else 'FAIL'}] {step}: {detail}")


def _explain(exc: Exception) -> str:
    if isinstance(exc, anthropic.APIStatusError):
        return f"HTTP {exc.status_code} — {getattr(exc, 'message', exc)}"
    return f"{type(exc).__name__}: {exc}"


async def run() -> int:
    settings = Settings()
    wrapper = ClaudeClient(settings)
    kwargs = wrapper.client_kwargs()
    shown = {k: ("***" if k in ("api_key", "auth_token") else v) for k, v in kwargs.items()}
    print(
        f"client kwargs: {shown or '(환경 변수에 위임)'} | model={settings.vision_model} "
        f"| server_fallback={settings.claude_server_fallback}"
    )
    client = anthropic.AsyncAnthropic(**kwargs)
    print(f"base_url = {client.base_url}")
    failures = 0

    # 1) 텍스트
    try:
        response = await client.messages.create(
            model=settings.vision_model,
            max_tokens=16,
            messages=[{"role": "user", "content": "OK 라고만 답하라."}],
        )
        _print(
            "1 텍스트",
            True,
            f"model={response.model} stop={response.stop_reason} "
            f"in/out={response.usage.input_tokens}/{response.usage.output_tokens}",
        )
    except Exception as exc:  # noqa: BLE001 — 점검 스크립트는 모든 실패를 보고한다
        failures += 1
        _print("1 텍스트", False, _explain(exc))
        print(
            "   → 인증/URL/모델 문제. 401 이면 키, 404 이면 base URL 또는 모델 이름(VISION_MODEL), "
            "403/429 면 게이트웨이 권한·쿼터를 본다. 이 단계가 실패하면 나머지는 의미가 없다."
        )
        return 2

    # 2) 구조화 출력
    try:
        response = await client.messages.parse(
            model=settings.vision_model,
            max_tokens=64,
            messages=[{"role": "user", "content": "ok=true, echo='pong' 으로 답하라."}],
            output_format=_Ping,
            output_config={"effort": "low"},
        )
        _print("2 구조화 출력", True, f"parsed={response.parsed_output}")
    except Exception as exc:  # noqa: BLE001
        failures += 1
        _print("2 구조화 출력", False, _explain(exc))
        print("   → output_config/output_format 이 막힘. 게이트웨이가 요청 본문을 변형하는지 확인.")

    # 3) 서버측 폴백(beta)
    if settings.claude_server_fallback:
        try:
            response = await client.beta.messages.parse(
                model=settings.vision_model,
                max_tokens=64,
                messages=[{"role": "user", "content": "ok=true, echo='pong' 으로 답하라."}],
                output_format=_Ping,
                output_config={"effort": "low"},
                betas=[FALLBACK_BETA],
                fallbacks="default",
            )
            _print("3 서버측 폴백(beta)", True, f"model={response.model}")
        except Exception as exc:  # noqa: BLE001
            failures += 1
            _print("3 서버측 폴백(beta)", False, _explain(exc))
            print(
                "   → beta 헤더/fallbacks 가 막힘. .env 에 CLAUDE_SERVER_FALLBACK=false 를 넣으면 "
                "비-beta 경로로 동작한다(§8.6 폴백만 잃는다)."
            )
    else:
        print("[SKIP] 3 서버측 폴백 — CLAUDE_SERVER_FALLBACK=false")

    # 4) 이미지
    try:
        buffer = io.BytesIO()
        Image.new("RGB", (64, 64), "white").save(buffer, format="JPEG")
        data = base64.standard_b64encode(buffer.getvalue()).decode("ascii")
        response = await client.messages.create(
            model=settings.vision_model,
            max_tokens=16,
            messages=[
                {
                    "role": "user",
                    "content": [
                        {
                            "type": "image",
                            "source": {"type": "base64", "media_type": "image/jpeg", "data": data},
                        },
                        {"type": "text", "text": "이미지가 보이면 OK 라고만 답하라."},
                    ],
                }
            ],
        )
        _print(
            "4 이미지 입력",
            True,
            f"in={response.usage.input_tokens} out={response.usage.output_tokens}",
        )
    except Exception as exc:  # noqa: BLE001
        failures += 1
        _print("4 이미지 입력", False, _explain(exc))
        print(
            "   → 이미지 본문이 막힘(요청 크기 제한 가능). IMAGE_LONG_EDGE 를 낮추거나 "
            "게이트웨이 제한을 확인."
        )

    print(
        f"\n결과: 실패 {failures}건. 0 이면 이 키·URL 로 smoke_vlm → evaluate_evidence 로 "
        "진행할 수 있다."
    )
    return 1 if failures else 0


def main() -> int:
    return asyncio.run(run())


if __name__ == "__main__":
    raise SystemExit(main())
