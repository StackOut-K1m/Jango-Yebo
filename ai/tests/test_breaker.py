"""Circuit Breaker 단위 테스트 — 기술스택 §8.6 · §8.7 규칙 6.

시계를 주입해서 잰다. `time.sleep(60)` 을 쓰면 이 파일 하나가 테스트 전체를 1분씩
늘리고, CI 가 느린 날에는 경계에서 흔들린다.

라우트에 실제로 물렸을 때의 동작(콜백을 보내는가, 무엇을 실패로 세는가)은
`tests/nlp/test_transaction_classification_route.py` 가 본다.
"""

import pytest

from app.common.breaker import BreakerRegistry, CircuitBreaker


class _Clock:
    """수동 단조 시계."""

    def __init__(self) -> None:
        self.now = 0.0

    def __call__(self) -> float:
        return self.now

    def advance(self, seconds: float) -> None:
        self.now += seconds


@pytest.fixture
def clock() -> _Clock:
    return _Clock()


@pytest.fixture
def breaker(clock: _Clock) -> CircuitBreaker:
    return CircuitBreaker("test", threshold=5, reset_seconds=60.0, clock=clock)


def test_기본은_닫힘이고_통과시킨다(breaker: CircuitBreaker) -> None:
    assert breaker.state == "closed"
    assert breaker.allow() is True
    assert breaker.retry_after == 0.0


def test_연속_5회_실패에_열린다(breaker: CircuitBreaker) -> None:
    """§8.7 규칙 6 의 숫자 그대로. 4회까지는 열리지 않아야 한다."""
    for _ in range(4):
        breaker.record_failure()
        assert breaker.state == "closed", "4회에 열리면 정상 요청 하나가 기능을 끈다"

    breaker.record_failure()
    assert breaker.state == "open"
    assert breaker.allow() is False


def test_성공이_카운터를_지운다(breaker: CircuitBreaker) -> None:
    """**연속** 5회다. 중간에 한 번 성공하면 처음부터 다시 센다."""
    for _ in range(4):
        breaker.record_failure()
    breaker.record_success()
    for _ in range(4):
        breaker.record_failure()

    assert breaker.state == "closed"


def test_60초가_지나면_한_건만_통과시킨다(breaker: CircuitBreaker, clock: _Clock) -> None:
    """half-open. 창이 열린 순간 밀린 배치가 한꺼번에 나가면 차단기를 단 의미가 없다."""
    for _ in range(5):
        breaker.record_failure()

    clock.advance(59.9)
    assert breaker.allow() is False

    clock.advance(0.2)
    assert breaker.state == "half-open"
    assert breaker.allow() is True, "60초 뒤 첫 건은 통과해야 복구를 알 수 있다"
    assert breaker.allow() is False, "두 번째 건까지 통과하면 몰려 나간다"


def test_probe_가_성공하면_닫힌다(breaker: CircuitBreaker, clock: _Clock) -> None:
    for _ in range(5):
        breaker.record_failure()
    clock.advance(60.1)

    assert breaker.allow() is True
    breaker.record_success()

    assert breaker.state == "closed"
    assert breaker.allow() is True


def test_probe_가_실패하면_다시_60초_닫는다(breaker: CircuitBreaker, clock: _Clock) -> None:
    """실패가 이어지는데 60초마다 한 건씩 새는 것을 막는다."""
    for _ in range(5):
        breaker.record_failure()
    clock.advance(60.1)

    assert breaker.allow() is True
    breaker.record_failure()

    assert breaker.state == "open"
    assert breaker.allow() is False
    clock.advance(59.0)
    assert breaker.state == "open", "재개방은 실패 시점부터 다시 60초다"


def test_retry_after_가_남은_시간을_준다(breaker: CircuitBreaker, clock: _Clock) -> None:
    """백엔드가 언제 다시 걸어도 되는지 알아야 한다 — 콜백 detail 로 나간다."""
    for _ in range(5):
        breaker.record_failure()
    assert breaker.retry_after == 60.0

    clock.advance(25.0)
    assert breaker.retry_after == 35.0

    clock.advance(100.0)
    assert breaker.retry_after == 0.0, "지난 시간이 음수로 새면 안 된다"


def test_임계값은_1_이상이어야_한다() -> None:
    """0 이면 첫 실패도 없이 열린 채로 시작한다 — 설정 오타를 조용히 넘기지 않는다."""
    with pytest.raises(ValueError):
        CircuitBreaker("test", threshold=0)


# ---------------------------------------------------------------- 레지스트리


def test_기능마다_다른_차단기다() -> None:
    """§8.7 규칙 6 — "해당 AI 기능만 끄고 나머지는 살린다"."""
    registry = BreakerRegistry(threshold=1)
    registry.get("transaction-classification").record_failure()

    assert registry.get("transaction-classification").allow() is False
    assert registry.get("briefing-generation").allow() is True, (
        "한 기능이 죽었다고 다른 기능까지 끄면 규칙 6 을 어긴다"
    )


def test_같은_이름은_같은_객체다() -> None:
    """매 요청 새로 만들면 카운터가 매번 0 이라 영원히 열리지 않는다."""
    registry = BreakerRegistry()
    assert registry.get("a") is registry.get("a")


def test_snapshot_이_열린_기능을_보여준다() -> None:
    registry = BreakerRegistry(threshold=1)
    registry.get("a").record_failure()
    registry.get("b")

    assert registry.snapshot() == {"a": "open", "b": "closed"}
