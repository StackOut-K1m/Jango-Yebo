"""평가 점수 게이트 — 커밋된 `report.json` 이 통과 기준을 지키는지 본다 (명세 10.2).

**이 테스트는 모델을 부르지 않는다.** 채점은 유료라 `evals.run_eval` 이 따로 돌고,
그 결과를 `evals/<세트>/report.json` 으로 커밋한다. 여기서는 그 파일만 읽는다.
`pytest` 가 외부 API 를 부르지 않는 구조를 지키기 위한 분리다 (`-93` 분할 검토).

리포트가 아직 없으면 **건너뛴다** — 라벨링·채점 전에도 나머지 테스트가 돌아야 한다.
있는데 기준 미달이면 **실패한다.** 그것이 이 파일의 목적이다.
"""

from __future__ import annotations

import json
from pathlib import Path

import pytest

from evals.run_eval import PASS_ACCEPTANCE, PASS_MACRO_F1

REPORT = Path(__file__).resolve().parents[2] / "evals" / "p1-01" / "report.json"


@pytest.fixture(scope="module")
def report() -> dict:
    if not REPORT.exists():
        pytest.skip("report.json 이 없다 — `python -m evals.run_eval p1-01` 로 만든다")
    return json.loads(REPORT.read_text(encoding="utf-8"))


def test_통과_기준값이_명세와_같다(report: dict) -> None:
    """리포트에 박힌 기준이 코드 상수와 어긋나면 게이트가 조용히 느슨해진다."""
    assert report["pass"]["macroF1"] == PASS_MACRO_F1
    assert report["pass"]["acceptanceRate"] == PASS_ACCEPTANCE


def test_macro_f1_이_기준을_넘는다(report: dict) -> None:
    stat = report["aggregate"]["transactionMacroF1"]
    assert stat["mean"] >= PASS_MACRO_F1, (
        f"Macro F1 {stat['mean']:.4f} < {PASS_MACRO_F1} "
        f"(회차 {stat['runs']}, 모델 {report['model']}, 프롬프트 {report['promptVersion']}). "
        "모델을 올리지 말고 규칙·사전 보강과 few-shot 을 먼저 손본다 — 명세 10.2 실패 시 대응."
    )


def test_수락률이_기준을_넘는다(report: dict) -> None:
    """자동 확정된 건이 맞는 비율. 낮으면 **자신 있게 틀리고 있다**는 뜻이다."""
    stat = report["aggregate"]["acceptanceRate"]
    assert stat["mean"] >= PASS_ACCEPTANCE, (
        f"수락률 {stat['mean']:.4f} < {PASS_ACCEPTANCE} "
        f"(회차 {stat['runs']}). confidence 임계 {report['confirmThreshold']} 이상인데 "
        "틀린 건이 많다 — 프롬프트에서 신뢰도를 정직하게 내도록 손본다."
    )


def test_채점된_건수가_라벨_수와_같다(report: dict) -> None:
    """폐기·누락으로 표본이 조용히 줄면 점수가 부풀 수 있다."""
    scored = report["runs"][-1]["scored"]
    assert scored == report["labeled"], (
        f"라벨 {report['labeled']}건 중 {scored}건만 채점됐다. "
        f"폐기 {report['runs'][-1]['meta']['discarded']}건 · "
        f"실패 배치 {report['runs'][-1]['meta']['failedBatches']}회"
    )
