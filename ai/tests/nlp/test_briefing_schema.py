"""20.7 브리핑 생성 계약 — 명세 예시가 그대로 파싱되는가 (`-87` 완료 기준).

거래 분류(`test_transaction_classification_schema.py`)와 같은 방식이다. 명세에 적힌 JSON을
**한 글자도 고치지 않고** 붙여 두고 그것으로 잰다. 손으로 다듬은 픽스처로 재면 "내가 만든
모델이 내가 만든 JSON을 읽는다"가 되어 계약을 검증하지 못한다.
"""

from datetime import date
from decimal import Decimal

import pytest
from pydantic import ValidationError

from app.common import callback as cb
from app.common.model_meta import ModelMeta
from app.nlp.schemas import (
    METRICS_MAX,
    BriefingGenerationRequest,
    BriefingGenerationResult,
)

# docs/api/20_내부_AI워커.md §20.7 의 요청 예시 그대로.
SPEC_REQUEST = {
    "jobId": 9015,
    "briefingId": 91,
    "periodType": "WEEKLY",
    "metrics": [
        {"label": "화요일 정산 입금", "value": 748000, "changeRate": -0.12},
        {"label": "향후 30일 최저잔액", "value": -420000, "date": "2026-09-14"},
        {"label": "다음 대형 지출", "value": 3000000, "date": "2026-09-14", "title": "급여"},
    ],
    "toneGuide": "확정 표현 금지, 조언은 권장 수준, 입력에 없는 수치 생성 금지",
}

META = ModelMeta(
    model_name="fake", model_version=None, prompt_version="briefing/v1", pipeline="LLM"
)


# ---------------------------------------------------------------- 요청


def test_명세_예시가_그대로_파싱된다() -> None:
    request = BriefingGenerationRequest.model_validate(SPEC_REQUEST)

    assert request.job_id == 9015
    assert request.briefing_id == 91
    assert request.period_type == "WEEKLY"
    assert len(request.metrics) == 3
    assert request.tone_guide is not None


def test_지표의_선택_필드가_제각각이어도_된다() -> None:
    """명세 예시의 세 지표가 각각 다른 필드를 갖는다 — changeRate / date / date+title."""
    metrics = BriefingGenerationRequest.model_validate(SPEC_REQUEST).metrics

    assert metrics[0].change_rate == Decimal("-0.12")
    assert metrics[0].date is None and metrics[0].title is None

    assert metrics[1].date == date(2026, 9, 14)
    assert metrics[1].change_rate is None

    assert metrics[2].title == "급여"


def test_금액이_소수점으로_늘어나지_않는다() -> None:
    """`float` 이면 748000 이 748000.0 이 되어 프롬프트에 "748000.0원" 이라고 쓴다.

    `-95` 의 환각 숫자 검증이 출력 숫자를 입력과 문자열로 대조하므로, 여기서 표기가 바뀌면
    **맞는 숫자를 환각으로 잡는다.**
    """
    metrics = BriefingGenerationRequest.model_validate(SPEC_REQUEST).metrics

    assert str(metrics[0].value) == "748000"
    assert str(metrics[1].value) == "-420000"
    assert str(metrics[0].change_rate) == "-0.12"


def test_원본_거래를_받는_필드가_없다() -> None:
    """도메인 규칙 2 — 거래를 주면 모델이 직접 세기 시작한다. 구조로 막는다."""
    fields = set(BriefingGenerationRequest.model_fields)

    assert "transactions" not in fields
    assert fields == {"job_id", "briefing_id", "period_type", "metrics", "tone_guide"}


def test_지표가_없으면_거절한다() -> None:
    """지표 0건이면 문장으로 옮길 것이 없다. 빈 브리핑을 생성하지 않는다."""
    with pytest.raises(ValidationError):
        BriefingGenerationRequest.model_validate({**SPEC_REQUEST, "metrics": []})


def test_지표_상한을_넘으면_거절한다() -> None:
    """조용히 자르지 않는다 — 무엇을 뺄지는 계산 엔진이 정할 일이다."""
    one = SPEC_REQUEST["metrics"][0]
    payload = {**SPEC_REQUEST, "metrics": [one] * (METRICS_MAX + 1)}

    with pytest.raises(ValidationError):
        BriefingGenerationRequest.model_validate(payload)

    BriefingGenerationRequest.model_validate({**SPEC_REQUEST, "metrics": [one] * METRICS_MAX})


def test_기간_유형은_둘뿐이다() -> None:
    with pytest.raises(ValidationError):
        BriefingGenerationRequest.model_validate({**SPEC_REQUEST, "periodType": "DAILY"})


def test_toneGuide_는_없어도_된다() -> None:
    """어조 규칙을 요청에만 의존하지 않는다 — 워커 시스템 프롬프트가 기본을 갖는다."""
    payload = {k: v for k, v in SPEC_REQUEST.items() if k != "toneGuide"}

    assert BriefingGenerationRequest.model_validate(payload).tone_guide is None


# ---------------------------------------------------------------- 결과


def test_결과는_headline_과_content_다() -> None:
    result = BriefingGenerationResult.model_validate(
        {"headline": "정산 입금은 줄고 급여 출금을 앞두고 있어요", "content": "이번 주 요약: …"}
    )

    assert result.headline.startswith("정산")
    # `status`·ModelMeta 는 callback.completed() 가 붙인다 — 결과 모델에 두지 않는다
    assert "status" not in BriefingGenerationResult.model_fields


def test_빈_문장을_결과로_받지_않는다() -> None:
    with pytest.raises(ValidationError):
        BriefingGenerationResult.model_validate({"headline": "", "content": "내용"})


def test_잘린_출력은_상한에서_걸린다() -> None:
    """`max_tokens` 에 걸려 끊긴 문장을 조용히 넘기지 않는다."""
    with pytest.raises(ValidationError):
        BriefingGenerationResult.model_validate({"headline": "제목", "content": "가" * 1001})


# ---------------------------------------------------------------- PARTIAL


def test_기본_status_는_COMPLETED_다() -> None:
    """기존 호출부(분류·증빙)는 `status` 를 넘기지 않는다. 동작이 바뀌면 안 된다."""
    body = cb.completed(META, {"headline": "제목", "content": "내용"})

    assert body["data"]["status"] == "COMPLETED"
    assert body["success"] is True


def test_PARTIAL_은_실패가_아니다() -> None:
    """환각 문장을 폐기해도 남은 결과는 쓸 수 있다 (명세 20.2 · 구현현황 §931).

    `failed()` 로 보내면 백엔드가 Job 을 FAILED 로 접어 **쓸 수 있는 브리핑을 버린다.**
    """
    body = cb.completed(META, {"headline": "제목", "content": "내용"}, status="PARTIAL")

    assert body["success"] is True, "PARTIAL 은 성공 콜백이다"
    assert body["data"]["status"] == "PARTIAL"
    assert body["error"] is None
    # 모델 메타는 PARTIAL 에도 그대로 실린다 — 어느 프롬프트가 환각을 냈는지 알아야 한다
    assert body["data"]["promptVersion"] == "briefing/v1"
