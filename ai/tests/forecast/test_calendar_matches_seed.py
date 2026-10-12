"""워커 달력과 시드 생성기의 공휴일이 같은가.

**목록이 세 곳에 있다.** 모듈도 언어도 배포 단위도 달라 한 파일을 같이 읽을 수 없다.

    project/scripts/seed/plan.mjs                    HOLIDAYS_2026    (Node)
    project/ai/app/forecast/calendar_kr.py           HOLIDAYS         (Python)  ← 여기
    project/backend/.../common/domain/BusinessCalendar.java           (Java)

세 곳에 적으면 어긋난다. 실제로 어긋나 있었다 — 2026-03-02(삼일절 대체)와
2026-05-25(부처님오신날 대체)가 워커에만 빠져 있었다. 둘 다 원래 공휴일이 **일요일**이라
생기는 대체공휴일이고, 빠지면 워커가 그 월요일을 영업일로 읽어 달력 특징이 틀린다.

그래서 **시드 생성기를 기준으로 삼고** 나머지 둘을 테스트로 묶는다. 자바 쪽은
`BusinessCalendarTests` 가 같은 일을 한다.

시드 생성기를 기준으로 둔 이유는 목록이 거기서 제일 완전하고, 문서화도 되어 있으며,
**시나리오의 공백 위치를 그 날짜들이 직접 정하기 때문**이다. 해가 바뀌면 거기부터 고친다.
"""

import pathlib
import re

import pytest

from app.forecast.calendar_kr import HOLIDAYS

SEED = pathlib.Path(__file__).resolve().parents[3] / "scripts" / "seed" / "plan.mjs"
YEAR = 2026

# **컨테이너에는 이 파일이 없다.** 이미지 빌드 컨텍스트가 `ai/` 하나뿐이라
# (`docker build ... ai/`) `parents[3]` 이 저장소 루트가 아니라 `/` 가 된다.
# 로컬에서는 통과하고 CI 에서만 깨졌다 — `tests/ocr/test_db_schema_conformance.py` 가
# 같은 함정을 먼저 밟았고 같은 방식으로 건너뛴다. `pytest.mark.skipif` 로는 안 된다:
# 테스트만 건너뛰고 모듈 본문은 수집 시점에 그대로 실행된다.
#
# 건너뛰는 동안 **CI 에서는 이 가드가 돌지 않는다.** 돌게 하려면 Jenkins 가 빌드 직전에
# `scripts/seed/plan.mjs` 를 `ai/` 안으로 복사하고 여기서 그 경로도 보게 해야 한다
# (`infra/Jenkinsfile` 수정이라 인프라 담당과 함께 정한다).
if not SEED.exists():
    pytest.skip(f"시드 생성기가 없다: {SEED}", allow_module_level=True)


def seed_holidays() -> set[str]:
    text = SEED.read_text(encoding="utf-8")
    start = text.index("const HOLIDAYS_2026")
    return set(re.findall(r"'(\d{4}-\d{2}-\d{2})'", text[start : text.index("]);", start)]))


def test_시드와_같은_공휴일을_쓴다():
    mine = {d.isoformat() for d in HOLIDAYS if d.year == YEAR}
    theirs = seed_holidays()

    assert mine == theirs, (
        f"공휴일이 어긋난다 ({YEAR}).\n"
        f"  워커에만: {sorted(mine - theirs)}\n"
        f"  시드에만: {sorted(theirs - mine)}\n"
        f"  → scripts/seed/plan.mjs 의 HOLIDAYS_2026 이 기준이다."
    )
