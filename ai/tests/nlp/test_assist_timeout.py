"""초안 점검의 자체 타임아웃 (명세 20.10 동기 10초).

**백엔드가 끊어도 워커는 계속 돈다.** `asyncio.wait_for` 가 없으면 받을 사람이 없는
응답에 토큰을 태운다. 명세가 정한 10초를 이쪽에서도 지키는지 본다.

실측(2026-09-14 · `evals/p0-12`, Sonnet · effort high, 60회) — 중앙 2.75초, 최대 8.98초.
**꼬리가 10초에 닿는다.** 초기 측정에서는 60회 중 2회가 실제로 초과했다.
"""

import asyncio

import pytest

from app.common.claude import ClaudeClient
from app.config import Settings
from app.nlp.pipeline import ClassificationError, ClaudeAssistant
from tests.nlp.test_assist_grounding import REQUEST


class _SlowClaude:
    """모델이 느린 상황. `parse` 가 상한보다 오래 걸린다."""

    def __init__(self, delay: float) -> None:
        self._delay = delay
        self.calls = 0

    async def parse(self, **kwargs):
        self.calls += 1
        await asyncio.sleep(self._delay)
        raise AssertionError("여기까지 오면 타임아웃이 안 걸린 것이다")


def settings_with(timeout: float) -> Settings:
    return Settings(_env_file=None, app_env="local", assist_timeout_seconds=timeout)


@pytest.mark.anyio
async def test_상한을_넘으면_끊는다() -> None:
    claude = _SlowClaude(delay=1.0)
    assistant = ClaudeAssistant(settings_with(0.05), claude)  # type: ignore[arg-type]

    with pytest.raises(ClassificationError) as exc:
        await assistant.review(REQUEST)

    assert exc.value.stage == "timeout"


@pytest.mark.anyio
async def test_타임아웃은_재시도_가능_실패다() -> None:
    """모델이 느린 것은 다음번에 나을 수 있다. 연속되면 차단기가 알아서 연다 —
    400 처럼 영구 실패로 세면 일시적 지연이 기능을 60초 끄게 된다."""
    claude = _SlowClaude(delay=1.0)
    assistant = ClaudeAssistant(settings_with(0.05), claude)  # type: ignore[arg-type]

    with pytest.raises(ClassificationError) as exc:
        await assistant.review(REQUEST)

    assert exc.value.retryable is True


def test_기본_상한이_명세와_같다() -> None:
    """명세 20.10 — 동기 호출 타임아웃 10초."""
    assert Settings(_env_file=None).assist_timeout_seconds == 10.0


def test_실제_클라이언트로도_조립된다() -> None:
    """`_SlowClaude` 가 진짜 클라이언트와 호환되지 않는 모양이면 위 둘이 거짓 통과한다."""
    settings = Settings(_env_file=None, app_env="local")
    assistant = ClaudeAssistant(settings, ClaudeClient(settings))

    assert assistant is not None
