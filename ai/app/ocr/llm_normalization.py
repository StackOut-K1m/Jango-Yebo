"""품목 정규화 3차 — 2차에서 애매하게 걸린 건(유사도 0.70~0.92)만 묶어서 한 번 LLM 에 묻는다 (§6.5).

동기 엔드포인트(10초)라 지연이 사용자에게 보인다. 그래서
- 후보가 없으면 호출하지 않고, 있으면 **한 번**만 묶어 부른다 (`rawNames` 배열이 이미 그 모양).
- 시간 제한(`normalization_timeout_seconds`) 안에 못 오면 후보를 미매칭으로 두고 응답한다 — 폴백은
  `matchedItemId: null` + `suggestedCanonicalName` (사용자 확인).
- 오매칭이 매칭 실패보다 나쁘다: 모델이 낸 itemId 가 후보에 없거나 confidence 가 낮으면 버린다.
"""

from __future__ import annotations

import asyncio
import json
import logging
from typing import Literal

from pydantic import BaseModel, ConfigDict

from app.common.claude import ClaudeClient, ClaudeError
from app.config import Settings
from app.ocr.normalization import LlmCandidate
from app.ocr.schemas import ItemMapping, KnownItem

log = logging.getLogger(__name__)

NORMALIZATION_PROMPT_VERSION = "item-normalization/v1"
ItemCategory = Literal["MATERIALS", "SUPPLIES", "EQUIPMENT", "SERVICE", "OTHER"]

SYSTEM = """\
당신은 소상공인 가게의 품목 사전 관리자다. 영수증에서 읽은 품목명(rawName)이 사전에 이미 있는 품목(candidate)과 같은 상품인지 판정한다.

규칙
- 같은 상품이면 matchedItemId 에 candidate 의 itemId 를 적는다. 다르거나 애매하면 null — 오매칭이 매칭 실패보다 나쁘다.
- 브랜드가 사전 이름의 하위(서울우유 1L ↔ 우유 1L)면 같은 상품으로 본다. 용량·규격·종류가 다르면(우유 1L ↔ 두유 1L, 500ml ↔ 1L) 다른 상품이다.
- 표기 차이(띄어쓰기, 약어, 한/영, 단위 표기, 포장 문구)만 다른 것은 같은 상품이다.
- null 이면 suggestedCanonicalName 에 사전에 넣을 이름을 제안한다: 브랜드·용량은 남기고 행사·광고 문구·행 번호·바코드는 뺀다.
- suggestedCategory 는 MATERIALS(식자재·원재료) / SUPPLIES(소모품·포장) / EQUIPMENT(비품·장비) / SERVICE(서비스·수수료) / OTHER 중 하나.
- confidence 는 0~1. 판정에 확신이 없으면 낮게 적는다.
- 입력의 rawName 마다 정확히 하나의 decision 을, 같은 순서로 돌려준다.
"""


class _Strict(BaseModel):
    model_config = ConfigDict(extra="forbid")


class _LlmDecision(_Strict):
    rawName: str
    matchedItemId: int | None
    confidence: float
    suggestedCanonicalName: str | None
    suggestedCategory: ItemCategory | None


class _LlmDecisions(_Strict):
    decisions: list[_LlmDecision]


def _payload(candidates: list[LlmCandidate], known_items: list[KnownItem]) -> str:
    aliases = {item.item_id: item.aliases for item in known_items}
    rows = [
        {
            "rawName": c.raw_name,
            "candidate": {
                "itemId": c.item_id,
                "canonicalName": c.canonical_name,
                "aliases": aliases.get(c.item_id, []),
                "similarity": c.score,
            },
        }
        for c in candidates
    ]
    return json.dumps(rows, ensure_ascii=False)


async def resolve_candidates(
    claude: ClaudeClient,
    settings: Settings,
    candidates: list[LlmCandidate],
    known_items: list[KnownItem],
) -> dict[str, ItemMapping]:
    """rawName → 3차 판정. 호출 실패·시간 초과·이상 응답은 빈 dict (호출자가 미매칭으로 둔다)."""
    if not candidates:
        return {}
    allowed = {c.raw_name: c.item_id for c in candidates}
    try:
        response = await asyncio.wait_for(
            claude.parse(
                model=settings.normalization_model,
                system=SYSTEM,
                messages=[{"role": "user", "content": _payload(candidates, known_items)}],
                output_format=_LlmDecisions,
                max_tokens=settings.normalization_max_tokens,
                effort=settings.normalization_effort,
            ),
            timeout=settings.normalization_timeout_seconds,
        )
    except TimeoutError:
        log.warning(
            "품목 정규화 3차 시간 초과 (%ss) — %d건 미매칭",
            settings.normalization_timeout_seconds,
            len(candidates),
        )
        return {}
    except ClaudeError as exc:
        log.warning("품목 정규화 3차 실패 stage=%s: %s", exc.stage, exc)
        return {}

    decisions: dict[str, ItemMapping] = {}
    for decision in response.parsed_output.decisions:
        expected = allowed.get(decision.rawName)
        if expected is None:
            continue  # 입력에 없는 rawName — 무시
        confident = decision.confidence >= settings.normalization_accept_confidence
        if decision.matchedItemId == expected and confident:
            decisions[decision.rawName] = ItemMapping(
                raw_name=decision.rawName,
                matched_item_id=expected,
                confidence=round(decision.confidence, 2),
                matched_by="LLM",
            )
            continue
        if decision.matchedItemId not in (None, expected):
            log.info("3차가 후보에 없는 itemId 를 냈다 — 버림 rawName=%r", decision.rawName)
        decisions[decision.rawName] = ItemMapping(
            raw_name=decision.rawName,
            suggested_canonical_name=decision.suggestedCanonicalName,
            suggested_category=decision.suggestedCategory,
        )
    return decisions


def merge_decisions(
    mappings: list[ItemMapping], decisions: dict[str, ItemMapping]
) -> list[ItemMapping]:
    """2차 결과 위에 3차 판정을 덮는다. 3차가 답하지 않은 건은 2차 결과(미매칭 + 제안) 그대로."""
    merged: list[ItemMapping] = []
    for mapping in mappings:
        decision = decisions.get(mapping.raw_name)
        if decision is None or mapping.matched_item_id is not None:
            merged.append(mapping)
            continue
        merged.append(
            decision.model_copy(
                update={
                    "suggested_canonical_name": decision.suggested_canonical_name
                    or mapping.suggested_canonical_name
                }
            )
        )
    return merged
