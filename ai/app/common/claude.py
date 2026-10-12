"""Anthropic 호출 공통 래퍼 — 1번(LLM)·2번(VLM)이 같은 규약(기술스택 §8)으로 부른다.

- 클라이언트는 하나, 지연 생성. 자격 증명은 환경 변수(ANTHROPIC_API_KEY)면 SDK 가 직접
  읽고, .env 에 있으면 Settings 를 거쳐 주입한다. 코드에 키를 두지 않는다.
  원시 HTTP 로 부르지 않는다(§8.1).
- 구조화 출력은 Pydantic 모델을 output_format 으로 넘겨 스키마를 강제한다(§8.2).
  문자열 후처리로 JSON 을 뽑지 않는다.
- thinking 은 기본(adaptive) 그대로 두고 깊이는 output_config.effort 로만 조절한다(§8.5).
  budget_tokens 는 Opus 5 에서 400 이다.
- 서버측 폴백을 켠다(§8.6). content 를 읽기 전에 stop_reason 을 먼저 확인한다.
- 예외는 체인으로 잡아 재시도 가능(429·5xx·네트워크)과 불가능(400·401·404·거절)을 구분한다.
- 원문·이미지는 로그에 남기지 않는다. usage(토큰·캐시 적중)만 남긴다 —
  cache_read_input_tokens 가 계속 0 이면 무언가가 캐시를 깨고 있다(§8.3).

Batch API(§8.4 — P1-01·P1-15·P1-17)는 `client.messages.batches` 로 1번 라우트 착수 시
여기에 덧붙인다.
"""

from __future__ import annotations

import logging
from dataclasses import asdict, dataclass
from typing import Any, Literal

import anthropic
from pydantic import BaseModel

from app.config import Effort, Settings

log = logging.getLogger(__name__)


@dataclass
class UsageTotals:
    """호출 누계 — 평가 스크립트의 장당 비용 실측과 §7.2 가정 검증에 쓴다."""

    calls: int = 0
    input_tokens: int = 0
    output_tokens: int = 0
    cache_read_input_tokens: int = 0
    cache_creation_input_tokens: int = 0

    def add(self, usage: Any) -> None:
        self.calls += 1
        self.input_tokens += int(getattr(usage, "input_tokens", 0) or 0)
        self.output_tokens += int(getattr(usage, "output_tokens", 0) or 0)
        self.cache_read_input_tokens += int(getattr(usage, "cache_read_input_tokens", 0) or 0)
        self.cache_creation_input_tokens += int(
            getattr(usage, "cache_creation_input_tokens", 0) or 0
        )

    def snapshot(self) -> dict[str, int]:
        return asdict(self)


FALLBACK_BETA = "server-side-fallback-2026-07-01"  # `fallbacks="default"` 와 짝인 헤더

# config: 키·URL·모델 이름 문제 / quota: 게이트웨이 키의 토큰 소진(충전·교체 전까지 모든 호출 실패)
# / model: 호출·응답 단계 문제
Stage = Literal["config", "quota", "model"]
FATAL_STAGES: frozenset[str] = frozenset({"config", "quota"})  # 다음 호출도 같은 이유로 실패한다
_QUOTA_MARKERS = ("no token left", "quota", "insufficient", "토큰이 소진")
# 모델마다 받는 파라미터가 다르다 — Haiku 4.5 는 adaptive thinking 도 effort 도 400 을 낸다
# (2026-09-08 확인). 첫 400 에서 배우고 이후 호출에서 뺀다. 400 은 과금되지 않는다.
#
# `fallbacks` 도 같다. §8.6 서버측 폴백은 Opus 5 계열 전용이고 다른 모델은 400 을 낸다 —
# `'claude-haiku-4-5-20251001' does not support the `fallbacks` parameter.` (2026-09-09 확인,
# usage.calls = 0 으로 미과금 확인). 설정 기본값이 켬이라 모델만 바꾸면 첫 호출이 죽는데,
# 여기 한 줄로 배우게 하면 어떤 모델을 넣어도 스스로 맞춘다.
_CAPABILITY_MARKERS = (
    ("adaptive thinking", "thinking"),
    ("effort parameter", "effort"),
    ("`fallbacks` parameter", "fallbacks"),
)


class ClaudeError(Exception):
    """모델 호출 실패. retryable 이면 429·5xx·네트워크처럼 다시 시도할 여지가 있는 종류다."""

    def __init__(self, stage: Stage, message: str, *, retryable: bool) -> None:
        super().__init__(message)
        self.stage = stage
        self.retryable = retryable


def translate_error(exc: anthropic.APIError, model: str) -> ClaudeError:
    """SDK 예외 → 단계·재시도 가능 여부.

    게이트웨이(GMS)는 토큰 소진도 401 로 돌려주므로 본문으로 구분한다 — 이걸 '인증 실패'로 두면
    평가·운영에서 키를 의심하며 헛돌고, 남은 호출을 전부 태운다.
    """
    text = str(exc).lower()
    if isinstance(exc, anthropic.AuthenticationError):
        if any(marker in text for marker in _QUOTA_MARKERS):
            return ClaudeError(
                "quota",
                "게이트웨이 키의 토큰이 소진되었습니다 (충전·교체 전까지 실패)",
                retryable=False,
            )
        return ClaudeError(
            "config", "Anthropic 인증에 실패했습니다 (ANTHROPIC_API_KEY 확인).", retryable=False
        )
    if isinstance(exc, anthropic.NotFoundError):
        return ClaudeError("config", f"모델을 찾을 수 없습니다: {model}", retryable=False)
    if isinstance(exc, anthropic.RateLimitError):
        return ClaudeError("model", "요청 한도를 초과했습니다.", retryable=True)
    if isinstance(exc, anthropic.APIStatusError):
        # 본문을 붙인다 — "HTTP 400" 만으로는 무엇이 틀렸는지 알 수 없다
        return ClaudeError(
            "model",
            f"모델 호출 실패 (HTTP {exc.status_code}): {str(exc)[:300]}",
            retryable=exc.status_code >= 500,
        )
    return ClaudeError("model", "모델 서버에 연결할 수 없습니다.", retryable=True)


class ClaudeClient:
    def __init__(self, settings: Settings) -> None:
        self._settings = settings
        self._client: anthropic.AsyncAnthropic | None = None  # 지연 생성 — 키가 없어도 앱은 뜬다
        self.usage = UsageTotals()
        # 모델별 누계 — 티어링(싼 모델 1차 + 강한 모델 승격)의 혼합 비용을 정확히 내려면
        # 모델마다 단가가 달라 합계만으로는 계산할 수 없다.
        self.usage_by_model: dict[str, UsageTotals] = {}
        # 모델별로 400 을 내는 파라미터를 기억한다 (예: Haiku 4.5 의 thinking·effort)
        self._unsupported: dict[str, set[str]] = {}

    def client_kwargs(self) -> dict[str, Any]:
        """SDK 클라이언트 생성 인자. .env 값이 있으면 넘기고, 없으면 SDK 가 환경에서 읽게 둔다."""
        settings = self._settings
        kwargs: dict[str, Any] = {}
        if settings.anthropic_api_key is not None:
            kwargs["api_key"] = settings.anthropic_api_key.get_secret_value()
        if settings.anthropic_auth_token is not None:
            kwargs["auth_token"] = settings.anthropic_auth_token.get_secret_value()
        if settings.anthropic_base_url:
            kwargs["base_url"] = settings.anthropic_base_url.rstrip("/")  # 게이트웨이(GMS 등)
        return kwargs

    def _client_or_raise(self) -> anthropic.AsyncAnthropic:
        if self._client is None:
            try:
                self._client = anthropic.AsyncAnthropic(**self.client_kwargs())
            except anthropic.AnthropicError as exc:
                raise ClaudeError(
                    "config", "Anthropic 자격 증명이 없습니다.", retryable=False
                ) from exc
        return self._client

    def _learn_unsupported(self, model: str, exc: anthropic.APIStatusError) -> str | None:
        """400 본문이 '이 모델은 X 를 지원하지 않는다'면 X 를 기억하고 그 이름을 돌려준다.

        ⚠ **이미 알고 있어도 이름을 돌려준다.** 전에는 처음 배울 때만 돌려줬는데, 그러면
        동시에 나간 호출들이 무더기로 죽는다 — 셋이 함께 출발해 셋 다 400 을 받으면
        **먼저 도착한 하나만 재시도하고 나머지는 「배울 게 없다」로 그대로 실패**한다.
        같은 파라미터를 빼고 다시 부르면 될 상황인데도 그렇다.

        2026-09-16 실호출에서 3건 중 2건이 이렇게 죽었다. 배포 직후 첫 묶음이 통째로
        실패하는 모양이라, `--max-structure 200` 이면 최대 199건이 FAILED 로 남는다.

        재시도가 무한히 돌지 않는 이유는 기억한 뒤에는 `parse_kwargs` 가 그 파라미터를
        빼고 만들어 **같은 400 이 다시 날 수 없기** 때문이다.
        """
        if exc.status_code != 400:
            return None
        text = str(exc).lower()
        known = self._unsupported.setdefault(model, set())
        for marker, capability in _CAPABILITY_MARKERS:
            if marker not in text:
                continue
            if capability not in known:
                known.add(capability)
                log.info("%s 는 %s 를 지원하지 않아 이후 호출에서 뺀다", model, capability)
            return capability
        return None

    def parse_kwargs(
        self,
        *,
        model: str,
        messages: list[dict[str, Any]],
        output_format: type[BaseModel],
        max_tokens: int,
        effort: Effort,
        system: str | None,
    ) -> dict[str, Any]:
        unsupported = self._unsupported.get(model, frozenset())
        kwargs: dict[str, Any] = {
            "model": model,
            "max_tokens": max_tokens,
            "messages": messages,
            "output_format": output_format,
        }
        if "effort" not in unsupported:
            kwargs["output_config"] = {"effort": effort}
        if "thinking" not in unsupported:
            # §8.5 adaptive thinking. Opus 5 는 기본값과 같고, Opus 4.8/4.7/4.6 은 생략 시
            # thinking 이 꺼지므로 명시한다. Haiku 4.5 는 아예 받지 않아 첫 400 뒤 빠진다.
            kwargs["thinking"] = {"type": "adaptive"}
        if self._settings.claude_server_fallback and "fallbacks" not in unsupported:
            kwargs["betas"] = [FALLBACK_BETA]
            kwargs["fallbacks"] = "default"
        if system is not None:
            # §8.3 프롬프트 캐시 — 시스템 프롬프트(+앞의 출력 스키마)를 캐시 접두로. 이미지·힌트는
            # 뒤에 온다. 1,024 토큰 미만이면 API 가 조용히 캐시하지 않는다(오류 아님).
            kwargs["system"] = (
                [{"type": "text", "text": system, "cache_control": {"type": "ephemeral"}}]
                if self._settings.claude_prompt_cache
                else system
            )
        return kwargs

    async def parse(
        self,
        *,
        model: str,
        messages: list[dict[str, Any]],
        output_format: type[BaseModel],
        max_tokens: int,
        effort: Effort,
        system: str | None = None,
    ) -> Any:
        """구조화 출력 한 번 호출.

        반환값은 SDK 응답이다 — `parsed_output` 은 output_format 인스턴스, `model` 은 실제로
        응답한 모델(폴백이 일어났으면 폴백 모델), `usage` 는 토큰 수.
        """
        client = self._client_or_raise()
        # 폴백(beta)을 켠 경우만 beta 네임스페이스를 탄다. 게이트웨이가 beta 를 막으면 끄고 쓴다
        endpoint = (
            client.beta.messages if self._settings.claude_server_fallback else client.messages
        )
        # 모델이 안 받는 파라미터는 첫 400 에서 배워 빼고 다시 부른다(400 은 과금되지 않는다).
        # thinking·effort 두 가지라 최대 두 번 더 시도한다.
        for _ in range(len(_CAPABILITY_MARKERS) + 1):
            kwargs = self.parse_kwargs(
                model=model,
                messages=messages,
                output_format=output_format,
                max_tokens=max_tokens,
                effort=effort,
                system=system,
            )
            try:
                response = await endpoint.parse(**kwargs)
                break
            except anthropic.APIStatusError as exc:
                if self._learn_unsupported(model, exc) is not None:
                    continue
                raise translate_error(exc, model) from exc
            except anthropic.APIError as exc:
                raise translate_error(exc, model) from exc
        else:  # pragma: no cover — 위 루프가 배울 게 없으면 이미 raise 된다
            raise ClaudeError("model", f"{model} 호출 인자를 맞추지 못했습니다.", retryable=False)

        # content 를 읽기 전에 stop_reason 부터 본다 (§8.6)
        if response.stop_reason == "refusal":
            raise ClaudeError("model", "모델이 요청을 거절했습니다.", retryable=False)
        if response.stop_reason == "max_tokens":
            raise ClaudeError(
                "model",
                f"출력이 max_tokens({max_tokens}) 에서 잘렸습니다. 상한을 올린다.",
                retryable=False,
            )

        usage = response.usage
        self.usage.add(usage)
        self.usage_by_model.setdefault(response.model, UsageTotals()).add(usage)
        log.info(
            "claude model=%s in=%s out=%s cache_read=%s cache_write=%s",
            response.model,
            usage.input_tokens,
            usage.output_tokens,
            getattr(usage, "cache_read_input_tokens", None),
            getattr(usage, "cache_creation_input_tokens", None),
        )
        if response.parsed_output is None:
            raise ClaudeError("model", "구조화 출력을 해석할 수 없습니다.", retryable=False)
        return response
