"""Circuit Breaker — 연속 5회 실패 시 60초 open (기술스택 §8.6 · §8.7 규칙 6).

## 이 워커에는 차단기가 **두 개** 필요하고, 여기 있는 것은 그중 하나다

| 어느 구간 | 누가 만드나 | 근거 |
| --- | --- | --- |
| 백엔드 → 워커 (동기 4종) | 백엔드 (resilience4j) | 공통규약 23장 · `99_구현현황.md` |
| **워커 → Anthropic API** | **여기** | 기술스택 §8.6 |

§8.6 이 "재시도 가능(429·5xx·네트워크)과 불가능(400·404)을 구분해야 Circuit Breaker 가
제대로 돈다" 고 적은 자리가 **워커가 Claude 를 부르는 구간**이다. 백엔드 쪽 차단기는
워커가 죽었을 때 화면을 살리는 것이고, 이쪽은 Anthropic 이 흔들릴 때 **같은 실패를
20건씩 반복해서 돈을 태우지 않게** 하는 것이다. 둘 다 있어야 하고 서로를 대신하지 못한다.

## 왜 `ClaudeClient` 안에 넣지 않았나

`app.state.claude` 는 1번(NLP)·2번(OCR)이 **함께 쓰는 하나의 인스턴스**다 (`main.py`).
거기에 차단기를 박으면 분류가 5번 실패했을 때 증빙 추출까지 같이 멈춘다.

§8.7 규칙 6 은 "**해당 AI 기능만** 끄고 나머지는 살린다" 다. 그래서 기능 이름으로 키를
나눈 레지스트리를 두고, 각 라우트가 자기 이름으로 꺼내 쓴다. 지금 실제로 물려 있는 것은
`transaction-classification` 하나뿐이다 — 2번·3번 파트 라우트는 손대지 않았다.

## 무엇을 실패로 세나

**재시도 가능한 실패만 센다** (`ClaudeError.retryable`). 400·404 는 몇 번을 더 불러도
같은 답이 오므로 차단기를 열어 봐야 얻는 게 없고, 오히려 내 요청이 잘못된 것을 두고
"AI 가 죽었다" 고 사용자에게 알리게 된다. 인증 실패(401)도 마찬가지다 — 60초 뒤에
저절로 낫지 않는다. 그런 것은 로그와 콜백 오류로 드러나야 한다.

## 상태

```
closed ──(연속 5회 재시도가능 실패)──> open ──(60초)──> half-open
   ^                                                      │
   └──────────(probe 성공)──────────────────────────────────┤
                                              (probe 실패) │
   open <──────────────────────────────────────────────────┘
```

half-open 에서는 **한 건만** 통과시킨다. 60초가 지난 순간 대기 중이던 배치가 한꺼번에
Anthropic 으로 몰리면 차단기를 단 의미가 없다.

## 동시성

이벤트 루프 안에서만 쓴다. `allow()` · `record_*()` 안에 `await` 가 없어 그 사이에
다른 태스크로 넘어가지 않으므로 락을 두지 않았다. **스레드에서 부르지 마라** — 그때는
이 가정이 깨진다.
"""

from __future__ import annotations

import logging
import time
from collections.abc import Callable
from typing import Literal

log = logging.getLogger(__name__)

State = Literal["closed", "open", "half-open"]


class CircuitBreaker:
    """기능 하나를 지키는 차단기. `name` 은 로그와 콜백 detail 에 그대로 실린다."""

    def __init__(
        self,
        name: str,
        *,
        threshold: int = 5,
        reset_seconds: float = 60.0,
        clock: Callable[[], float] = time.monotonic,
    ) -> None:
        if threshold < 1:
            raise ValueError("threshold 는 1 이상이어야 한다")
        self.name = name
        self._threshold = threshold
        self._reset_seconds = reset_seconds
        # 벽시계가 아니라 단조 시계다. NTP 보정이나 서머타임으로 시간이 뒤로 가면
        # `opened_at` 비교가 뒤집혀 차단기가 영원히 열려 있거나 즉시 닫힌다.
        self._clock = clock
        self._failures = 0
        self._opened_at: float | None = None
        self._probing = False

    # ---------------------------------------------------------------- 상태 조회

    @property
    def state(self) -> State:
        if self._opened_at is None:
            return "closed"
        if self._clock() - self._opened_at < self._reset_seconds:
            return "open"
        return "half-open"

    @property
    def retry_after(self) -> float:
        """열려 있으면 다시 열릴 때까지 남은 초. 닫혀 있으면 0."""
        if self._opened_at is None:
            return 0.0
        remaining = self._reset_seconds - (self._clock() - self._opened_at)
        return round(max(remaining, 0.0), 1)

    # ---------------------------------------------------------------- 판정·기록

    def allow(self) -> bool:
        """호출해도 되는가. half-open 에서는 첫 한 건에만 True 를 준다."""
        state = self.state
        if state == "closed":
            return True
        if state == "open":
            return False
        if self._probing:
            return False
        self._probing = True
        return True

    def record_success(self) -> None:
        if self._opened_at is not None:
            log.info("차단기 닫힘 name=%s", self.name)
        self._failures = 0
        self._opened_at = None
        self._probing = False

    def record_failure(self) -> None:
        """**재시도 가능한** 실패만 넘긴다. 판단은 호출부가 한다 (위 「무엇을 실패로 세나」)."""
        self._probing = False
        if self._opened_at is not None:
            # half-open probe 가 실패했다. 카운터는 그대로 두고 창만 다시 연다.
            self._opened_at = self._clock()
            log.warning("차단기 재개방 name=%s %.0f초", self.name, self._reset_seconds)
            return
        self._failures += 1
        if self._failures >= self._threshold:
            self._opened_at = self._clock()
            log.warning(
                "차단기 열림 name=%s 연속 %d회 실패 → %.0f초 차단",
                self.name,
                self._failures,
                self._reset_seconds,
            )

    def reset(self) -> None:
        """테스트·운영 수동 복구용. 실패 카운터까지 지운다."""
        self._failures = 0
        self._opened_at = None
        self._probing = False


class BreakerRegistry:
    """기능 이름 → 차단기. 없으면 만들어 준다 (`app.state.breakers`)."""

    def __init__(self, *, threshold: int = 5, reset_seconds: float = 60.0) -> None:
        self._threshold = threshold
        self._reset_seconds = reset_seconds
        self._breakers: dict[str, CircuitBreaker] = {}

    def get(self, name: str) -> CircuitBreaker:
        breaker = self._breakers.get(name)
        if breaker is None:
            breaker = CircuitBreaker(
                name, threshold=self._threshold, reset_seconds=self._reset_seconds
            )
            self._breakers[name] = breaker
        return breaker

    def snapshot(self) -> dict[str, State]:
        """지금 열려 있는 기능이 무엇인지 — 헬스체크·로그용."""
        return {name: breaker.state for name, breaker in self._breakers.items()}
