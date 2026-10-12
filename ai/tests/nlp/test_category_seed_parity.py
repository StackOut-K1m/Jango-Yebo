"""카테고리 23종이 백엔드 시드 SQL 과 같은지 대조한다.

## 왜 필요한가

카테고리 코드와 방향은 **두 곳에 복제**되어 있다:

- 워커 `app/nlp/schemas.py` — `TransactionCategory` · `CATEGORY_DIRECTION`
- 백엔드 `V2__seed_transaction_category.sql` — `core.transaction_category`

워커는 백엔드를 호출하지 않으므로 한쪽만 바뀌면 **아무 테스트도 깨지지 않고** 조용히
어긋난다. `schemas.py` 의 `_assert_category_table_complete()` 는 워커 **내부 정합성만**
본다 — Literal 과 방향표가 서로 맞는지까지다.

그 상태에서 백엔드가 카테고리를 하나 추가하면: 워커 테스트는 전부 통과하고, 평가 세트
라벨 300건은 그 카테고리 없이 남고, 채점에서 조용히 빠진다. 이 테스트가 그 조용한
드리프트를 시끄러운 실패로 바꾼다.

시드 SQL 주석이 이 값 집합을 "LLM 분류(P1-01) 요청의 allowedCategories (API 20.3)" 의
단일 기준으로 선언하고 있다. 즉 SQL 이 정본이고 워커가 따라가는 쪽이다.

## 실패하면 무엇을 해야 하나

둘 중 하나다. 어느 쪽인지는 사람이 판단해야 한다.

- 백엔드가 의도적으로 바꿨다 → `schemas.py` 를 맞추고, **평가 세트 라벨을 다시 본다**
  (새 카테고리에 해당하는 건이 기존 라벨에 다른 이름으로 들어가 있을 수 있다)
- 실수로 어긋났다 → 어느 쪽이 맞는지 백엔드 담당과 정한다
"""

import re
from pathlib import Path

import pytest

from app.nlp.schemas import CATEGORY_DIRECTION

# ai/tests/nlp/ → 레포 루트
REPO_ROOT = Path(__file__).resolve().parents[3]
BACKEND_ROOT = REPO_ROOT / "backend"
SEED_SQL = BACKEND_ROOT / "src/main/resources/db/migration/V2__seed_transaction_category.sql"

# 열 순서를 가정하고 파싱한다. 순서가 바뀌면 조용히 빗나가는 대신 이 헤더 검사가 먼저 깨진다.
EXPECTED_COLUMNS = "(code, name, flow_type, sort_order)"
INSERT_HEADER = re.compile(
    r"INSERT\s+INTO\s+core\.transaction_category\s*(\([^)]*\))", re.IGNORECASE
)
# ('CODE', '한글명', 'DEPOSIT', 10) — 가운데 한글명은 건너뛴다
ROW = re.compile(r"\(\s*'([A-Z_]{3,})'\s*,\s*'[^']*'\s*,\s*'(DEPOSIT|WITHDRAWAL)'")


def _seed_rows() -> dict[str, str]:
    """시드 SQL 에서 `{code: flow_type}` 을 뽑는다."""
    sql = SEED_SQL.read_text(encoding="utf-8")

    header = INSERT_HEADER.search(sql)
    assert header is not None, (
        f"{SEED_SQL.name} 에서 core.transaction_category INSERT 문을 찾지 못했습니다. "
        "이 테스트의 파서를 고쳐야 합니다."
    )
    columns = re.sub(r"\s+", " ", header.group(1)).strip()
    assert columns == EXPECTED_COLUMNS, (
        f"시드 SQL 의 열 구성이 바뀌었습니다: {columns} (기대: {EXPECTED_COLUMNS}). "
        "이 테스트의 ROW 파서를 함께 고쳐야 합니다."
    )

    rows = ROW.findall(sql)
    assert rows, "시드 SQL 에서 카테고리 행을 하나도 읽지 못했습니다."
    codes = [code for code, _ in rows]
    assert len(codes) == len(set(codes)), "시드 SQL 에 중복된 code 가 있습니다."
    return dict(rows)


# backend/ 가 없는 컨텍스트(워커만 담은 Docker 이미지 등)에서는 대조할 대상이 없다.
# 다만 backend/ 는 있는데 마이그레이션 파일만 없다면 **파일이 옮겨진 것**이므로 실패시킨다 —
# 조용히 skip 되면 이 테스트가 존재하는 이유가 사라진다.
pytestmark = pytest.mark.skipif(
    not BACKEND_ROOT.is_dir(),
    reason=f"backend/ 가 없어 대조를 건너뜁니다 ({BACKEND_ROOT})",
)


def test_seed_sql_exists():
    assert SEED_SQL.is_file(), (
        f"backend/ 는 있는데 시드 마이그레이션이 없습니다: {SEED_SQL}. "
        "파일이 옮겨졌다면 이 테스트의 경로를 함께 고쳐야 합니다."
    )


def test_category_codes_match_backend_seed():
    seed = _seed_rows()
    worker = set(CATEGORY_DIRECTION)

    only_seed = sorted(set(seed) - worker)
    only_worker = sorted(worker - set(seed))

    assert not only_seed, (
        f"백엔드 시드에만 있는 카테고리: {only_seed}. "
        "app/nlp/schemas.py 에 추가하고 평가 세트 라벨을 다시 확인해야 합니다."
    )
    assert not only_worker, (
        f"워커에만 있는 카테고리: {only_worker}. 백엔드 시드에서 빠졌거나 제거되었습니다."
    )


def test_category_directions_match_backend_seed():
    seed = _seed_rows()
    mismatched = {
        code: {"seed": flow, "worker": CATEGORY_DIRECTION[code]}
        for code, flow in seed.items()
        if code in CATEGORY_DIRECTION and CATEGORY_DIRECTION[code] != flow
    }
    assert not mismatched, f"방향(flow_type)이 어긋난 카테고리: {mismatched}"


def test_seed_has_expected_split():
    """API 8.1 — 입금 7종 + 출금 16종 = 23종.

    개수를 못박아 둔다. 코드 집합 대조만으로는 양쪽이 **함께** 잘못 바뀐 경우를 못 잡는다.
    """
    seed = _seed_rows()
    deposits = [c for c, flow in seed.items() if flow == "DEPOSIT"]
    withdrawals = [c for c, flow in seed.items() if flow == "WITHDRAWAL"]
    assert (len(deposits), len(withdrawals)) == (7, 16), (
        f"카테고리 구성이 바뀌었습니다: 입금 {len(deposits)}종 · 출금 {len(withdrawals)}종. "
        "API 8.1·명세 20.3·평가 세트 README 를 함께 고쳐야 합니다."
    )
