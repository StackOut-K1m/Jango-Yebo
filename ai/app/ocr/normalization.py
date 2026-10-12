"""품목 정규화 1차(단위 규칙) + 2차(유사도) — §6.5.

3차(LLM)는 `llm_normalization.py` — 여기서 `LlmCandidate` 로 넘긴 애매한 건만 묶어서 한 번 묻는다.
규칙 + 유사도가 90% 이상을 처리하도록 ITEM.aliases 를 먼저 채운다.
오매칭이 매칭 실패보다 나쁘다 — 임계값은 보수적으로, 애매하면 None 을 낸다.
"""

from __future__ import annotations

import re
from dataclasses import dataclass

from rapidfuzz import fuzz, utils

from app.ocr.schemas import ItemMapping, KnownItem

AUTO_MATCH_THRESHOLD = 0.92  # 이상이면 matchedItemId 확정
LLM_CANDIDATE_THRESHOLD = 0.70  # 이 사이는 3차(LLM) 후보. live 모드에서만 LLM 에 묻는다

_THOUSANDS = re.compile(r"(?<=\d),(?=\d{3})")
_KG = re.compile(r"(\d+(?:\.\d+)?)\s*(?:kg|킬로그램|킬로)", re.IGNORECASE)
_ML = re.compile(r"(\d+(?:\.\d+)?)\s*(?:ml|㎖|밀리리터)", re.IGNORECASE)
_L = re.compile(r"(\d+(?:\.\d+)?)\s*(?:l|ℓ|리터)(?![a-z])", re.IGNORECASE)
_G = re.compile(r"(\d+(?:\.\d+)?)\s*(?:g|그램|그람)(?![a-z])", re.IGNORECASE)
_COUNT = re.compile(r"(\d+)\s*(?:개|ea|pcs?)(?![a-z])", re.IGNORECASE)
_SEPARATORS = re.compile(r"[\s\-_/·,()\[\]]+")


def _fmt(value: float) -> str:
    return str(int(value)) if value == int(value) else f"{value:g}"


def _volume(ml: float) -> str:
    return f"{int(ml // 1000)}L" if ml >= 1000 and ml % 1000 == 0 else f"{_fmt(ml)}ml"


def _weight(g: float) -> str:
    return f"{int(g // 1000)}kg" if g >= 1000 and g % 1000 == 0 else f"{_fmt(g)}g"


def normalize_name(raw: str) -> str:
    """`우유 1,000ml` → `우유 1L`. 규칙으로 끝나는 변환은 LLM 에 보내지 않는다."""
    text = _THOUSANDS.sub("", raw.strip())
    text = _KG.sub(lambda m: f"{_fmt(float(m[1]))}kg", text)  # g 패턴이 kg 의 g 를 먹지 않게 먼저
    text = _ML.sub(lambda m: _volume(float(m[1])), text)
    text = _L.sub(lambda m: f"{_fmt(float(m[1]))}L", text)
    text = _G.sub(lambda m: _weight(float(m[1])), text)
    text = _COUNT.sub(lambda m: f"{m[1]}개", text)
    return _SEPARATORS.sub(" ", text).strip()


@dataclass(frozen=True)
class LlmCandidate:
    """2차에서 애매하게 걸린 건. 3차(LLM) 묶음 호출의 입력이 된다."""

    raw_name: str
    item_id: int
    canonical_name: str
    score: float


def _best_match(normalized: str, known_items: list[KnownItem]) -> tuple[KnownItem | None, float]:
    best_item: KnownItem | None = None
    best_score = 0.0
    for item in known_items:
        for name in (item.canonical_name, *item.aliases):
            candidate = normalize_name(name)
            if candidate == normalized:
                return item, 1.0
            score = fuzz.WRatio(normalized, candidate, processor=utils.default_process) / 100
            if score > best_score:
                best_item, best_score = item, score
    return best_item, best_score


def match_items(
    raw_names: list[str],
    known_items: list[KnownItem],
    *,
    auto_threshold: float = AUTO_MATCH_THRESHOLD,
    candidate_threshold: float = LLM_CANDIDATE_THRESHOLD,
) -> tuple[list[ItemMapping], list[LlmCandidate]]:
    """1차 규칙 + 2차 유사도. 임계값은 실측 조정을 위해 열어 두되 기본값이 운영값이다."""
    mappings: list[ItemMapping] = []
    llm_candidates: list[LlmCandidate] = []
    for raw in raw_names:
        normalized = normalize_name(raw)
        item, score = _best_match(normalized, known_items)
        if item is not None and score >= auto_threshold:
            mappings.append(
                ItemMapping(
                    raw_name=raw,
                    matched_item_id=item.item_id,
                    confidence=round(score, 2),
                    matched_by="RULE" if score == 1.0 else "SIMILARITY",
                )
            )
            continue
        if item is not None and score >= candidate_threshold:
            llm_candidates.append(
                LlmCandidate(raw, item.item_id, item.canonical_name, round(score, 2))
            )
        # 미매칭: matchedItemId/confidence 는 null, 정리한 이름을 신규 품목 제안으로 돌려준다
        mappings.append(ItemMapping(raw_name=raw, suggested_canonical_name=normalized or None))
    return mappings, llm_candidates
