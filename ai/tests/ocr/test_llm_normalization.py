"""품목 정규화 3차(LLM) — 네트워크 없이 ClaudeClient.parse 를 가짜로 바꿔 판정 병합을 검증한다."""

import asyncio
from types import SimpleNamespace

from fastapi.testclient import TestClient
from pydantic import SecretStr

from app.common.claude import ClaudeClient, ClaudeError
from app.main import create_app
from app.ocr import llm_normalization as llm
from app.ocr.normalization import LlmCandidate, match_items
from app.ocr.schemas import ItemMapping, KnownItem
from tests.conftest import HEADERS

KNOWN = [
    KnownItem(item_id=601, canonical_name="우유 1L", aliases=["서울우유 1L"]),
    KnownItem(item_id=602, canonical_name="삼겹살", aliases=[]),
]
CANDIDATES = [
    LlmCandidate("매일우유 1L", 601, "우유 1L", 0.83),
    LlmCandidate("삼겹살(냉장) 500g", 602, "삼겹살", 0.78),
    LlmCandidate("두유 1L", 601, "우유 1L", 0.75),
]


def _settings(**overrides):
    from app.config import Settings

    return Settings(_env_file=None, app_env="local", anthropic_api_key=SecretStr("k"), **overrides)


def _fake_parse(decisions):
    async def parse(self, **kwargs):
        assert kwargs["effort"] == "low" and kwargs["output_format"] is llm._LlmDecisions
        return SimpleNamespace(parsed_output=llm._LlmDecisions(decisions=decisions))

    return parse


def test_decisions_are_guarded_against_hallucinated_ids_and_low_confidence(monkeypatch):
    monkeypatch.setattr(
        ClaudeClient,
        "parse",
        _fake_parse(
            [
                llm._LlmDecision(
                    rawName="매일우유 1L",
                    matchedItemId=601,
                    confidence=0.93,
                    suggestedCanonicalName=None,
                    suggestedCategory=None,
                ),
                # 후보(602)가 아닌 id 를 냈다 → 버리고 제안만 남긴다
                llm._LlmDecision(
                    rawName="삼겹살(냉장) 500g",
                    matchedItemId=999,
                    confidence=0.9,
                    suggestedCanonicalName="삼겹살 500g",
                    suggestedCategory="MATERIALS",
                ),
                # 같다고 했지만 확신이 낮다 → 미매칭
                llm._LlmDecision(
                    rawName="두유 1L",
                    matchedItemId=601,
                    confidence=0.55,
                    suggestedCanonicalName="두유 1L",
                    suggestedCategory="MATERIALS",
                ),
                llm._LlmDecision(
                    rawName="입력에 없는 이름",
                    matchedItemId=601,
                    confidence=0.99,
                    suggestedCanonicalName=None,
                    suggestedCategory=None,
                ),
            ]  # fmt: skip
        ),
    )
    client = ClaudeClient(_settings())
    decisions = asyncio.run(llm.resolve_candidates(client, _settings(), CANDIDATES, KNOWN))
    assert set(decisions) == {"매일우유 1L", "삼겹살(냉장) 500g", "두유 1L"}
    assert decisions["매일우유 1L"].matched_item_id == 601
    assert decisions["매일우유 1L"].matched_by == "LLM"
    assert decisions["삼겹살(냉장) 500g"].matched_item_id is None
    assert decisions["삼겹살(냉장) 500g"].suggested_category == "MATERIALS"
    assert decisions["두유 1L"].matched_item_id is None


def test_failures_and_timeouts_fall_back_to_unmatched(monkeypatch):
    async def boom(self, **kwargs):
        raise ClaudeError("quota", "토큰 소진", retryable=False)

    monkeypatch.setattr(ClaudeClient, "parse", boom)
    assert (
        asyncio.run(
            llm.resolve_candidates(ClaudeClient(_settings()), _settings(), CANDIDATES, KNOWN)
        )
        == {}
    )

    async def slow(self, **kwargs):
        await asyncio.sleep(0.2)

    monkeypatch.setattr(ClaudeClient, "parse", slow)
    settings = _settings(normalization_timeout_seconds=0.01)
    assert (
        asyncio.run(llm.resolve_candidates(ClaudeClient(settings), settings, CANDIDATES, KNOWN))
        == {}
    )
    assert asyncio.run(llm.resolve_candidates(ClaudeClient(settings), settings, [], KNOWN)) == {}


def test_merge_keeps_stage_two_results_and_fills_suggestions():
    mappings, candidates = match_items(["우유 1000ml", "매일우유 1L", "대파 한 단"], KNOWN)
    assert mappings[0].matched_by == "RULE"
    assert [c.raw_name for c in candidates] == ["매일우유 1L"]
    merged = llm.merge_decisions(
        mappings,
        {
            "매일우유 1L": ItemMapping(
                raw_name="매일우유 1L", matched_item_id=601, confidence=0.93, matched_by="LLM"
            )
        },
    )
    assert merged[0].matched_item_id == 601 and merged[0].matched_by == "RULE"  # 2차 확정은 그대로
    assert merged[1].matched_item_id == 601 and merged[1].matched_by == "LLM"
    assert merged[2].matched_item_id is None and merged[2].suggested_canonical_name == "대파 한 단"


def test_live_endpoint_uses_stage_three_and_stub_does_not(settings, monkeypatch):
    payload = {
        "businessId": 11,
        "knownItems": [k.model_dump(by_alias=True) for k in KNOWN],
        "rawNames": ["매일우유 1L"],
    }
    # stub 모드: 3차를 부르지 않고 미매칭
    with TestClient(create_app(settings)) as client:
        body = client.post("/internal/v1/ai/item-normalization", json=payload, headers=HEADERS)
        assert body.json()["data"]["mappings"][0]["matchedItemId"] is None

    async def fake_resolve(claude, settings_, candidates, known):
        assert [c.raw_name for c in candidates] == ["매일우유 1L"]
        return {
            "매일우유 1L": ItemMapping(
                raw_name="매일우유 1L", matched_item_id=601, confidence=0.9, matched_by="LLM"
            )
        }

    monkeypatch.setattr("app.ocr.router.resolve_candidates", fake_resolve)
    live = settings.model_copy(update={"ai_pipeline_mode": "live"})
    with TestClient(create_app(live)) as client:
        mapping = client.post(
            "/internal/v1/ai/item-normalization", json=payload, headers=HEADERS
        ).json()["data"]["mappings"][0]
    assert mapping["matchedItemId"] == 601 and mapping["matchedBy"] == "LLM"


def test_thresholds_are_tunable_but_default_to_the_operating_values():
    """실측 조정용으로 열어 둔 인자다 — 기본값은 §6.5 운영값이어야 한다.

    test 359장 스윕(2026-09-08): 0.92 → 1.00 으로 올리면 신규 품목 오인이 13.6% → 11.7% 로
    1.9pp 줄지만 정답을 놓치는 비율이 0.9% → 1.9% 로 두 배가 된다. 그래서 0.92 를 유지한다.
    """
    # `서울우유 1L 팩` 은 alias 와 완전히 같지는 않아 2차 유사도(0.95)로 붙는다
    default, _ = match_items(["서울우유 1L 팩"], KNOWN)
    assert default[0].matched_item_id == 601 and default[0].matched_by == "SIMILARITY"

    # 완전 일치만 허용하면 같은 건이 미매칭으로 떨어지고 3차 후보로 넘어간다
    strict, candidates = match_items(["서울우유 1L 팩"], KNOWN, auto_threshold=1.0)
    assert strict[0].matched_item_id is None
    assert [c.raw_name for c in candidates] == ["서울우유 1L 팩"]

    # 완전 일치는 임계값을 올려도 1차 규칙으로 붙는다 (score 1.0 >= 1.0)
    exact, _ = match_items(["우유 1000ml"], KNOWN, auto_threshold=1.0)
    assert exact[0].matched_by == "RULE"

    # 후보 하한을 올리면 애매한 건이 3차로도 가지 않는다 (LLM 호출 자체를 줄일 때)
    _, none_left = match_items(["삼겹살(냉장) 500g"], KNOWN, candidate_threshold=0.95)
    assert none_left == []
