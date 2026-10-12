"""커밋된 `evals/p0-12/report.json` 이 기준을 지키는지 (`-96` 완료 기준).

**이 테스트는 모델을 부르지 않는다.** 채점은 `evals/run_assist_eval.py` 가 따로 하고
돈이 든다. 여기서는 그 결과물만 읽는다 — 테스트가 유료가 되면 아무도 안 돌린다.

`-96` 이 요구한 것은 평가 통과만이 아니라 **"절대 기준이 테스트에서 실패로 잡힌다"** 다.
그래서 기준을 코드로 박아 두고, 리포트가 바뀌면 빨개지게 한다.
"""

import json
from pathlib import Path

import pytest

from evals.run_assist_eval import PASS_FALSE_NOTE_RATE, PASS_HALLUCINATED, PASS_MISS_RATE

REPORT = Path(__file__).resolve().parents[2] / "evals" / "p0-12" / "report.json"


@pytest.fixture(scope="module")
def report() -> dict:
    assert REPORT.exists(), f"{REPORT} 가 없다. `python -m evals.run_assist_eval` 을 돌린다."
    return json.loads(REPORT.read_text(encoding="utf-8"))


def test_절대_기준_근거_없는_문장이_0건이다(report: dict) -> None:
    """**한 건이라도 나오면 통과가 아니다.** 비율이 아니라 절대 기준이다.

    이 수는 검증기가 버린 문장의 수다. 사용자에게 나가지는 않았지만, 프롬프트가
    입력에 없는 수를 만들었다는 뜻이다. 0 이 아니면 프롬프트를 고친다.
    """
    assert report["aggregate"]["hallucinatedNotes"] == PASS_HALLUCINATED


def test_무결_초안에서_문장을_내지_않는다(report: dict) -> None:
    """짚을 것이 없는데 채우면 그게 환각이다."""
    assert report["aggregate"]["falseNoteRate"] <= PASS_FALSE_NOTE_RATE


def test_놓침율이_기준_안이다(report: dict) -> None:
    """**환각 0건만 보면 아무 말도 안 하는 프롬프트가 만점을 받는다.**

    짚어야 할 초안에서 입을 다무는 비율이 그 반대쪽 지표다. 이게 없으면 "빈 목록만
    내는 모델" 이 최고 점수를 받는다.
    """
    assert report["aggregate"]["missRate"] <= PASS_MISS_RATE


def test_최악_회차로_판정한다(report: dict) -> None:
    """평균은 "운 좋은 회차" 를 통과로 읽는다. 2026-09-14 에 1회차가 0건이었는데
    3회로 늘리자 1건이 나온 적이 있다 — 그래서 반복이 필요하고 최악값이 필요하다."""
    assert report["repeat"] >= 3, "편차를 보려면 3회 이상 돌린다"
    assert report["aggregate"]["hallucinatedNotes"] == max(
        run["hallucinatedNotes"] for run in report["perRun"]
    )


def test_전_회차_폐기가_기록된다(report: dict) -> None:
    """`results` 는 마지막 회차만 싣는다. 최악 회차에서 걸린 문장을 보려면 따로 모아야
    한다 — 이게 없어서 환각 2건의 문장을 확인하지 못한 적이 있다."""
    assert "discardedAllRuns" in report
    assert len(report["discardedAllRuns"]) == sum(
        run["hallucinatedNotes"] for run in report["perRun"]
    )


def test_모든_초안이_채점됐다(report: dict) -> None:
    """실패한 호출이 섞이면 분모가 줄어 점수가 좋아 보인다."""
    assert report["scored"] == report["sets"] == 20
    assert report["failedCalls"] == 0


def test_실제로_모델을_불렀다(report: dict) -> None:
    """stub 으로 돌린 리포트가 커밋되면 0건이 자동으로 나온다 — 아무것도 재지 못한다."""
    assert report["usage"]["input_tokens"] > 0
    assert report["estimatedUsd"] > 0
    for row in report["results"]:
        assert row.get("modelName", "").startswith("claude"), row["id"]


def test_리포트가_지금_설정과_같은_모델이다(report: dict) -> None:
    """설정을 바꾸고 리포트를 안 다시 뽑으면 점수가 다른 모델 것이 된다."""
    from app.config import Settings

    assert report["model"] == Settings(_env_file=None).assist_model
    assert report["effort"] == Settings(_env_file=None).assist_effort


def test_한계가_기록돼_있다(report: dict) -> None:
    """합성 데이터라는 것과 검증기가 수치만 본다는 것. 대외 자료에 절대 수치로 쓰지 말라는
    경고가 리포트 안에 함께 있어야 한다."""
    caveat = report["caveat"]

    assert "합성" in caveat
    assert "수치 환각만" in caveat
