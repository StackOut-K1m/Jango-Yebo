"""내보내기 SQL 이 분석기 입력과 같은 모양인지 — 두 곳에 사는 계약을 묶는다.

`experiments/sql/export_corrections.sql` 이 만드는 JSON 키와 `analyze_corrections.py` 가 읽는
키가 갈라지면, 데이터가 쌓인 뒤에야(그것도 조용히 빈 결과로) 드러난다. DB 없이 검증할 수
있는 부분은 **키 이름**이라 그것만 본다.

SQL 은 DB 컬럼(`before_value`·`after_value`)을 분석기 계약(`before`·`after`)으로 바꾸는데,
그 매핑이 여기 걸린다.
"""

from __future__ import annotations

import re
from pathlib import Path

import pytest

SQL = Path(__file__).resolve().parents[2] / "experiments/sql/export_corrections.sql"


def _json_keys(text: str, builder: str) -> set[str]:
    """`json_build_object('a', …, 'b', …)` 에서 키 이름만."""
    start = text.index(builder)
    opened = text.index("(", start)  # 괄호 세기는 **여는 괄호부터** 시작한다
    depth, end = 0, len(text)
    for i, ch in enumerate(text[opened:], opened):
        depth += (ch == "(") - (ch == ")")
        if depth == 0:
            end = i
            break
    return set(re.findall(r"'(\w+)',", text[opened:end]))


def test_export_produces_the_keys_the_analyzer_reads():
    text = SQL.read_text(encoding="utf-8")
    produced = _json_keys(text, "json_build_object(\n    'extractionId'")
    assert produced == {
        "extractionId",
        "evidenceId",
        "modelName",
        "promptVersion",
        "extractionMode",
        "validation",
        "fieldConfidence",
        "corrections",
        "confirmedAt",
    }, sorted(produced)


def test_corrections_use_the_analyzer_key_names_not_the_db_ones():
    """DB 는 before_value·after_value, 분석기 계약은 before·after 다. SQL 이 그 사이를 잇는다."""
    text = SQL.read_text(encoding="utf-8")
    inner = _json_keys(text, "json_build_object(\n        'field'")
    assert inner == {"field", "before", "after"}, sorted(inner)
    assert "before_value" in text and "after_value" in text  # 원본 컬럼도 실제로 읽는다


def test_denominator_comes_from_confirmed_extractions_not_corrections():
    """수정이 없는 검토도 한 줄이어야 한다 — 분모가 없으면 정확도를 낼 수 없다(합의 ⑦)."""
    text = SQL.read_text(encoding="utf-8")
    assert "FROM core.evidence_extraction" in text
    assert "LEFT JOIN LATERAL" in text  # correction 이 없어도 행이 남는다
    assert "confirmed_at IS NOT NULL" in text  # 아직 안 본 추출은 분모 밖
    assert "is_current" in text  # 재추출된 옛 행은 세지 않는다


def test_analyzer_reads_exactly_these_keys():
    """분석기 쪽에서도 같은 키를 읽는지 — 한쪽만 고치는 것을 막는다."""
    analyzer = (
        Path(__file__).resolve().parents[2] / "experiments/analyze_corrections.py"
    ).read_text(encoding="utf-8")
    for key in ("fieldConfidence", "corrections", "validation", "promptVersion"):
        assert f'"{key}"' in analyzer, key
    assert '"before"' in analyzer or 'c["field"]' in analyzer


@pytest.mark.parametrize("missing", ["extraction_mode", "extractionMode NOT NULL"])
def test_extraction_mode_is_knowingly_empty(missing: str):
    """`extractionMode` 는 비운다 — 요청 필드라 콜백·테이블 어디에도 없다.

    분석기의 네 번째 질문(재추출이 실제로 고쳤나)이 지금 답이 안 나오는 이유다. 나중에
    컬럼이 생기면 이 테스트가 먼저 깨져서 SQL 을 고치라고 알려 준다.
    """
    text = SQL.read_text(encoding="utf-8")
    assert "'extractionMode', NULL" in text
    assert "요청 필드" in text  # 왜 비우는지가 SQL 에 적혀 있어야 한다
