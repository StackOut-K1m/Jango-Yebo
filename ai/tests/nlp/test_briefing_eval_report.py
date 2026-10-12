"""브리핑 평가 게이트 — 커밋된 `report.json` 이 기준을 지키는지 본다 (`-95` 완료 기준).

`test_eval_report.py`(거래 분류)와 같은 구조다. **모델을 부르지 않는다** — 채점은 유료라
`evals.run_briefing_eval` 이 따로 돌고 결과만 커밋된다.

차이가 하나 있다. 분류는 평균으로 판정하지만 **여기는 최악값으로 판정한다.** 환각은
절대 기준이라 한 회차라도 나오면 통과가 아니다.
"""

from __future__ import annotations

import json
from pathlib import Path

import pytest

from evals.run_briefing_eval import (
    PASS_HALLUCINATED,
    PASS_PARTIAL_RATE,
    PASS_TOTAL_LOSS_RATE,
)

REPORT = Path(__file__).resolve().parents[2] / "evals" / "p1-17" / "report.json"


@pytest.fixture(scope="module")
def report() -> dict:
    if not REPORT.exists():
        pytest.skip("report.json 이 없다 — `python -m evals.run_briefing_eval` 로 만든다")
    return json.loads(REPORT.read_text(encoding="utf-8"))


def test_통과_기준값이_코드와_같다(report: dict) -> None:
    """리포트에 박힌 기준이 코드 상수와 어긋나면 게이트가 조용히 느슨해진다."""
    assert report["pass"]["hallucinatedSentences"] == PASS_HALLUCINATED
    assert report["pass"]["totalLossRate"] == PASS_TOTAL_LOSS_RATE
    assert report["pass"]["partialRate"] == PASS_PARTIAL_RATE


def test_환각_숫자가_한_건도_없다(report: dict) -> None:
    """`-95` 의 절대 기준. 숫자 하나가 틀리면 사장님이 그 숫자로 자금 판단을 한다."""
    agg = report["aggregate"]
    assert agg["hallucinatedSentences"] == PASS_HALLUCINATED, (
        f"환각 문장 {agg['hallucinatedSentences']}건 "
        f"(모델 {report['model']}, 프롬프트 {report['promptVersion']}, {report['repeat']}회). "
        "검증기가 버렸으므로 사용자에게 나가지는 않았지만, 프롬프트가 숫자를 지어내고 있다."
    )


def test_본문이_통째로_사라진_브리핑이_없다(report: dict) -> None:
    """전부 폐기되면 지표 카드만 남는다 — 검증기는 통과지만 기능은 실패다."""
    assert report["aggregate"]["totalLossRate"] <= PASS_TOTAL_LOSS_RATE


def test_부분_폐기율이_기준_아래다(report: dict) -> None:
    """**"환각 0건" 만 보면 안 되는 이유가 이것이다.**

    검증기가 다 버리면 사용자에게 가는 환각은 0 이지만 브리핑도 0 이다. 폐기율이 프롬프트
    품질의 실제 지표다.
    """
    assert report["aggregate"]["partialRate"] <= PASS_PARTIAL_RATE


def test_모든_지표_세트가_채점됐다(report: dict) -> None:
    """호출 실패로 표본이 조용히 줄면 "환각 0건" 이 쉬워진다."""
    assert report["scored"] == report["sets"]
    assert report["failedCalls"] == 0


def test_회차가_한_번보다_많다(report: dict) -> None:
    """모델이 비결정적이다. 1회 결과로 절대 기준을 주장할 수 없다 —
    실제로 같은 프롬프트에서 회차별 환각이 0 → 2 → 0 으로 흔들린 적이 있다."""
    assert report["repeat"] >= 2
    assert len(report["perRun"]) == report["repeat"]
