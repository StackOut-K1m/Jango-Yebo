"""20.6 `ClaudeStructurer` — **버린 값이 조용히 사라지지 않는가** (`-94`).

이 경로에서 무서운 실패는 예외가 아니라 **침묵**이다. 모델이 금리를 250 으로,
기간을 「예산 소진시까지」로 주면 계약을 벗어나는데, 그때 두 갈래가 다 나쁘다.

- 통째로 던지면 → 그 공고는 구조화되지 않고 **검색에서 아예 빠진다.** 지역·업종·
  제외 대상까지 같이 잃는다. 사장님은 그런 지원금이 있었다는 것도 모른다
- 조용히 버리면 → 화면은 「확인할 것 없음」으로 보인다. 빈 `uncertainConditions` 가
  곧 「전부 확실하다」는 선언이라(`Eligibility` 머리말) **없는 확신을 만든 것**이다

그래서 좁히되 **버렸다는 사실을 `uncertainConditions` 에 남긴다.** 여기서 재는 것이
그것이다.

모델을 부르지 않는다 — 가짜 응답을 넣고 좁히기만 본다.
"""

from __future__ import annotations

from datetime import date
from decimal import Decimal
from types import SimpleNamespace
from typing import Any

import pytest

from app.config import Settings
from app.nlp import pipeline as pl
from app.nlp.schemas import ProgramStructuringRequest

REQUEST = ProgramStructuringRequest.model_validate(
    {
        "jobId": 9014,
        "programId": 1201,
        "rawText": "2026년 광주 소상공인 임대료 특별지원 공고 ...",
        "sourceUrl": "https://www.bizinfo.go.kr/example",
    }
)

MINIMAL: dict[str, Any] = {
    "eligibility": {
        "regions": ["전남광주"],
        "industries": ["전 업종"],
        "excludes": ["휴·폐업자"],
        "uncertainConditions": [],
    },
    "supportType": "SUBSIDY",
}


class _FakeClaude:
    """`parse` 한 번을 흉내낸다. 실제 호출은 없다."""

    def __init__(self, payload: dict[str, Any]) -> None:
        self._payload = payload
        self.seen: dict[str, Any] = {}

    async def parse(self, **kwargs: Any) -> Any:
        self.seen = kwargs
        return SimpleNamespace(
            parsed_output=pl._LlmProgram.model_validate(self._payload),
            model=kwargs["model"],
        )


async def _structure(payload: dict[str, Any]) -> tuple[Any, Any, _FakeClaude]:
    claude = _FakeClaude(payload)
    settings = Settings(ai_pipeline_mode="live")
    structurer = pl.ClaudeStructurer(settings, claude)  # type: ignore[arg-type]
    result, meta = await structurer.structure(REQUEST)
    return result, meta, claude


# ------------------------------------------------------------------ 기본 동작


@pytest.mark.anyio
async def test_설정한_모델과_프롬프트_판을_쓴다() -> None:
    _, meta, claude = await _structure(MINIMAL)

    assert claude.seen["model"] == Settings(ai_pipeline_mode="live").structuring_model
    assert meta.pipeline == "LLM"
    assert meta.prompt_version == "program-structuring/v1"


@pytest.mark.anyio
async def test_지역_표기를_그대로_옮긴다() -> None:
    """기업마당은 전남과 광주를 「전남광주」 한 덩어리로 준다.

    나눠 적는 순간 원문에 없는 두 값이 생긴다 — 검색 쪽(17.1)이 가장 넓은 한 토막으로
    줄여 흡수하므로 나눌 이유도 없다.
    """
    result, _, _ = await _structure(MINIMAL)

    assert result.eligibility.regions == ["전남광주"]
    assert result.eligibility.excludes == ["휴·폐업자"]


@pytest.mark.anyio
async def test_확실하면_확인필요가_비어_있다() -> None:
    """빈 목록이 곧 「확실하다」는 선언이다. 멀쩡한 공고를 확인 대상으로 몰지 않는다."""
    result, _, _ = await _structure(MINIMAL)

    assert result.eligibility.uncertain_conditions == []


# ------------------------------------------------------------------ 좁히기


@pytest.mark.anyio
async def test_계약_밖_지원종류는_OTHER_로_둔다() -> None:
    """던지면 자격 조건까지 통째로 잃는다. OTHER 는 「분류하지 않았다」는 뜻이다."""
    result, _, _ = await _structure({**MINIMAL, "supportType": "GRANT"})

    assert result.support_type == "OTHER"
    # 종류를 못 고른 것은 조건의 불확실이 아니다 — 확인 목록을 오염시키지 않는다.
    assert result.eligibility.uncertain_conditions == []


@pytest.mark.anyio
async def test_범위를_벗어난_금리는_버리고_남긴다() -> None:
    result, _, _ = await _structure({**MINIMAL, "interestRate": "250"})

    assert result.interest_rate is None
    assert any("250" in note for note in result.eligibility.uncertain_conditions)


@pytest.mark.anyio
async def test_멀쩡한_금리는_그대로_통과한다() -> None:
    """연 % 단위다 — 대출 쪽(`V23`)과 같은 약속이다."""
    result, _, _ = await _structure({**MINIMAL, "interestRate": "2.5"})

    assert result.interest_rate == Decimal("2.5")
    assert result.eligibility.uncertain_conditions == []


@pytest.mark.anyio
async def test_말도_안_되는_지급소요일은_버리고_남긴다() -> None:
    result, _, _ = await _structure({**MINIMAL, "expectedDisbursementDays": 5000})

    assert result.expected_disbursement_days is None
    assert any("5000" in note for note in result.eligibility.uncertain_conditions)


@pytest.mark.anyio
async def test_지급소요일은_대개_비어_있다() -> None:
    """실측 32건 중 지급 시기를 말한 공고가 1건이었다. 비는 것이 정상이다."""
    result, _, _ = await _structure(MINIMAL)

    assert result.expected_disbursement_days is None
    assert result.eligibility.uncertain_conditions == []


# ------------------------------------------------------------------ 기간


@pytest.mark.anyio
async def test_날짜가_아닌_기간은_버리고_남긴다() -> None:
    """「예산 소진시까지」를 억지로 날짜로 만들면 마감된 공고가 열려 보인다."""
    result, _, _ = await _structure({**MINIMAL, "applyEnd": "예산 소진시까지"})

    assert result.apply_end is None
    assert any("예산 소진시까지" in note for note in result.eligibility.uncertain_conditions)


@pytest.mark.anyio
async def test_뒤집힌_기간은_둘_다_버린다() -> None:
    """뒤집힌 기간은 DB 제약(ck_sp_period)에 걸려 **적재가 통째로 실패한다.**"""
    result, _, _ = await _structure(
        {**MINIMAL, "applyStart": "2026-09-30", "applyEnd": "2026-09-01"}
    )

    assert result.apply_start is None
    assert result.apply_end is None
    assert any("뒤집" in note for note in result.eligibility.uncertain_conditions)


@pytest.mark.anyio
async def test_멀쩡한_기간은_그대로_통과한다() -> None:
    result, _, _ = await _structure(
        {**MINIMAL, "applyStart": "2026-09-01", "applyEnd": "2026-09-19"}
    )

    assert result.apply_start == date(2026, 9, 1)
    assert result.apply_end == date(2026, 9, 19)
    assert result.eligibility.uncertain_conditions == []


# ------------------------------------------------------------------ 서류


@pytest.mark.anyio
async def test_서류는_이름_발급처_발급방법까지만_싣는다() -> None:
    """`issueUrl`·`fee` 는 서류 사전(17.4)이 붙인다.

    공고 본문의 주소를 옮기면 죽은 링크나 다른 기관 페이지가 그대로 나간다.
    계약에 그 두 칸이 없으므로 모델이 보내도 들어갈 자리가 없다.
    """
    result, _, _ = await _structure(
        {
            **MINIMAL,
            "requiredDocuments": [
                {"documentName": "사업자등록증명", "issuer": "홈택스", "method": "온라인"}
            ],
        }
    )

    document = result.required_documents[0]
    assert document.document_name == "사업자등록증명"
    assert not hasattr(document, "issue_url")
    assert not hasattr(document, "fee")


# ------------------------------------------------------------------ 배선


def test_stub_모드는_여전히_고정응답이다() -> None:
    """`live` 가 아닐 때 모델을 부르면 테스트와 CI 에서 돈이 나간다."""
    built = pl.build_structurer(Settings(ai_pipeline_mode="stub"), claude=None)  # type: ignore[arg-type]

    assert isinstance(built, pl.StubStructurer)


def test_live_모드는_Claude_경로다() -> None:
    built = pl.build_structurer(Settings(ai_pipeline_mode="live"), claude=None)  # type: ignore[arg-type]

    assert isinstance(built, pl.ClaudeStructurer)
