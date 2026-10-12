"""20.6 공고 구조화 계약 — 명세 예시가 그대로 파싱되는가 (`-86` 완료 기준).

거래 분류·브리핑 테스트와 같은 방식이다. 명세에 적힌 JSON 을 **한 글자도 고치지 않고**
붙여 두고 그것으로 잰다. 손으로 다듬은 픽스처로 재면 "내가 만든 모델이 내가 만든 JSON 을
읽는다" 가 되어 계약을 검증하지 못한다.

여기서는 붙여야 할 문서가 둘이다 — 요청은 §20.6, 결과 구조는 20.6 이 가리키는 §17.2 다.
"""

from datetime import date

import pytest
from pydantic import ValidationError

from app.common import callback as cb
from app.common.model_meta import ModelMeta
from app.nlp.schemas import (
    RAW_TEXT_MAX,
    Eligibility,
    ProgramStructuringRequest,
    ProgramStructuringResult,
    RequiredDocument,
)

# docs/api/20_내부_AI워커.md §20.6 의 요청 예시 그대로.
SPEC_REQUEST = {
    "jobId": 9014,
    "programId": 1201,
    "rawText": "공고 원문 전문...",
    "sourceUrl": "https://www.bizinfo.go.kr/...",
}

# docs/api/17_지원제도_문서.md §17.2 의 `data` 그대로.
# 워커가 안 만드는 필드(`programId` · `rawSummary` · `expectedDisbursementDays` ·
# 서류의 `issueUrl` · `fee`)까지 그대로 뒀다 — 빼고 붙이면 "들어와도 안 깨지는가" 를
# 못 재고, 그게 실제로 이 JSON 을 백엔드에서 되돌려 받을 때의 모양이다.
SPEC_DETAIL = {
    "programId": 1201,
    "rawSummary": "공고 원문 요약...",
    "eligibility": {
        "regions": ["광주"],
        "industries": ["전 업종"],
        "maxAnnualRevenue": 300000000,
        "minYearsInBusiness": 1,
        "maxYearsInBusiness": None,
        "excludes": ["휴·폐업자", "국세 체납자"],
        "uncertainConditions": ["공동사업자 신청 가능 여부"],
    },
    "expectedDisbursementDays": 30,
    "requiredDocuments": [
        {
            "documentName": "사업자등록증명",
            "issuer": "홈택스",
            "issueUrl": "https://hometax.go.kr/...",
            "method": "온라인 즉시 발급",
            "fee": 0,
        },
        {
            "documentName": "부가가치세과세표준증명",
            "issuer": "홈택스",
            "issueUrl": "https://hometax.go.kr/...",
            "method": "온라인 즉시 발급",
            "fee": 0,
        },
    ],
}

# §17.1 목록 필드 그대로 — 20.6 이 결과에 포함하라고 지정한 넷이 여기서 온다.
SPEC_LIST_FIELDS = {
    "supportType": "SUBSIDY",
    "maxAmount": 3000000,
    "applyStart": "2026-09-01",
    "applyEnd": "2026-09-19",
}

META = ModelMeta(
    model_name="fake",
    model_version=None,
    prompt_version="program-structuring/v1",
    pipeline="LLM",
)


def spec_result() -> dict[str, object]:
    """20.6 이 지정한 대로 17.2 상세 + 17.1 목록 필드를 합친 결과."""
    return {
        "eligibility": SPEC_DETAIL["eligibility"],
        "requiredDocuments": SPEC_DETAIL["requiredDocuments"],
        **SPEC_LIST_FIELDS,
    }


# ---------------------------------------------------------------- 요청


def test_명세_예시가_그대로_파싱된다() -> None:
    request = ProgramStructuringRequest.model_validate(SPEC_REQUEST)

    assert request.job_id == 9014
    assert request.program_id == 1201
    assert request.raw_text == "공고 원문 전문..."
    assert request.source_url == "https://www.bizinfo.go.kr/..."


def test_원문이_비면_거부한다() -> None:
    with pytest.raises(ValidationError):
        ProgramStructuringRequest.model_validate({**SPEC_REQUEST, "rawText": ""})


def test_원문이_상한을_넘으면_거부한다() -> None:
    """페이지 전체를 긁어 온 입력을 여기서 잡는다. 통과시키면 조용히 비싸진다."""
    with pytest.raises(ValidationError):
        ProgramStructuringRequest.model_validate(
            {**SPEC_REQUEST, "rawText": "가" * (RAW_TEXT_MAX + 1)}
        )


def test_원문이_상한_그대로면_통과한다() -> None:
    request = ProgramStructuringRequest.model_validate(
        {**SPEC_REQUEST, "rawText": "가" * RAW_TEXT_MAX}
    )
    assert len(request.raw_text) == RAW_TEXT_MAX


def test_원문_링크가_없으면_거부한다() -> None:
    """`sourceUrl` 이 없으면 사용자가 결과를 원문과 대조할 수 없다 (17.1 요구)."""
    payload = {k: v for k, v in SPEC_REQUEST.items() if k != "sourceUrl"}
    with pytest.raises(ValidationError):
        ProgramStructuringRequest.model_validate(payload)


# ---------------------------------------------------------------- 지원 대상 조건


def test_명세_17_2의_지원대상_조건이_그대로_파싱된다() -> None:
    eligibility = Eligibility.model_validate(SPEC_DETAIL["eligibility"])

    assert eligibility.regions == ["광주"]
    assert eligibility.industries == ["전 업종"]
    assert eligibility.max_annual_revenue == 300_000_000
    assert eligibility.min_years_in_business == 1
    assert eligibility.max_years_in_business is None
    assert eligibility.excludes == ["휴·폐업자", "국세 체납자"]
    assert eligibility.uncertain_conditions == ["공동사업자 신청 가능 여부"]


def test_조건을_하나도_못_뽑아도_통과한다() -> None:
    """빈 공고가 아니라 **못 읽은 공고**가 실제로 온다. 여기서 거부하면 Job 이 통째로
    실패하고 원문 링크조차 화면에 남지 않는다."""
    eligibility = Eligibility.model_validate({})

    assert eligibility.regions == []
    assert eligibility.excludes == []
    assert eligibility.uncertain_conditions == []


def test_자격_판정_필드를_두지_않는다() -> None:
    """도메인 규칙 4·명세 17.1 — 최종 자격 판단은 기관 확인이 필요하다.

    모델이 「대상이다」 를 낼 자리를 아예 만들지 않는다. 필드가 없으면 프롬프트를
    어떻게 고치든 판정이 화면까지 갈 수 없다.
    """
    fields = set(Eligibility.model_fields) | set(ProgramStructuringResult.model_fields)

    for banned in ("eligible", "is_eligible", "eligibility_result", "match_score"):
        assert banned not in fields


def test_판정_필드를_보내도_모델에_남지_않는다() -> None:
    eligibility = Eligibility.model_validate(
        {**SPEC_DETAIL["eligibility"], "eligible": True, "matchScore": 0.91}
    )

    assert not hasattr(eligibility, "eligible")
    assert "eligible" not in eligibility.model_dump(by_alias=True)


def test_음수_매출_상한을_거부한다() -> None:
    with pytest.raises(ValidationError):
        Eligibility.model_validate({"maxAnnualRevenue": -1})


# ---------------------------------------------------------------- 제출 서류


def test_명세_17_2의_서류가_그대로_파싱된다() -> None:
    documents = [RequiredDocument.model_validate(d) for d in SPEC_DETAIL["requiredDocuments"]]

    assert [d.document_name for d in documents] == ["사업자등록증명", "부가가치세과세표준증명"]
    assert documents[0].issuer == "홈택스"
    assert documents[0].method == "온라인 즉시 발급"


def test_발급_링크를_담지_않는다() -> None:
    """링크의 정본은 서류 사전(17.4)이다. 공고 본문에서 주워 온 URL 을 실으면
    죽은 링크가 그대로 사용자에게 나간다."""
    document = RequiredDocument.model_validate(SPEC_DETAIL["requiredDocuments"][0])
    dumped = document.model_dump(by_alias=True)

    assert "issueUrl" not in dumped
    assert "fee" not in dumped


def test_서류_이름만_있어도_통과한다() -> None:
    document = RequiredDocument.model_validate({"documentName": "사업자등록증명"})

    assert document.issuer is None
    assert document.method is None


def test_이름_없는_서류를_거부한다() -> None:
    with pytest.raises(ValidationError):
        RequiredDocument.model_validate({"issuer": "홈택스"})


# ---------------------------------------------------------------- 결과 전체


def test_명세_구조를_합친_결과가_그대로_파싱된다() -> None:
    result = ProgramStructuringResult.model_validate(spec_result())

    assert result.support_type == "SUBSIDY"
    assert result.max_amount == 3_000_000
    assert result.apply_start == date(2026, 9, 1)
    assert result.apply_end == date(2026, 9, 19)
    assert len(result.required_documents) == 2
    assert result.eligibility.uncertain_conditions == ["공동사업자 신청 가능 여부"]


def test_모르는_지원_유형을_거부한다() -> None:
    """17.1 의 다섯 값 밖이면 백엔드 필터(`supportType=`)에 걸리지 않는다.
    분류가 애매하면 `OTHER` 로 보내고 사유를 `uncertainConditions` 에 남긴다."""
    with pytest.raises(ValidationError):
        ProgramStructuringResult.model_validate({**spec_result(), "supportType": "LOAN"})


@pytest.mark.parametrize("support_type", ["FUNDING", "SUBSIDY", "VOUCHER", "CONSULTING", "OTHER"])
def test_명세_17_1의_지원_유형_다섯을_모두_받는다(support_type: str) -> None:
    result = ProgramStructuringResult.model_validate({**spec_result(), "supportType": support_type})

    assert result.support_type == support_type


def test_금액과_기간을_못_읽어도_통과한다() -> None:
    """공고에 "예산 소진 시까지" 로만 적힌 경우가 흔하다. 여기서 거부하면 나머지
    구조화 결과까지 같이 버려진다."""
    result = ProgramStructuringResult.model_validate({"eligibility": {}, "supportType": "OTHER"})

    assert result.max_amount is None
    assert result.apply_start is None
    assert result.apply_end is None
    assert result.required_documents == []


def test_음수_지원금액을_거부한다() -> None:
    with pytest.raises(ValidationError):
        ProgramStructuringResult.model_validate({**spec_result(), "maxAmount": -1})


def test_지원_대상_조건은_생략할_수_없다() -> None:
    """비어 있는 것과 없는 것은 다르다. `eligibility` 가 통째로 빠지면 백엔드는
    "조건이 없는 공고" 와 "구조화가 실패한 공고" 를 구분하지 못한다."""
    with pytest.raises(ValidationError):
        ProgramStructuringResult.model_validate({"supportType": "OTHER"})


def test_camel_case로_직렬화한다() -> None:
    result = ProgramStructuringResult.model_validate(spec_result())
    dumped = result.model_dump(by_alias=True, mode="json")

    assert dumped["supportType"] == "SUBSIDY"
    assert dumped["maxAmount"] == 3_000_000
    assert dumped["applyStart"] == "2026-09-01"
    assert dumped["requiredDocuments"][0]["documentName"] == "사업자등록증명"
    assert dumped["eligibility"]["maxAnnualRevenue"] == 300_000_000
    assert dumped["eligibility"]["uncertainConditions"] == ["공동사업자 신청 가능 여부"]


def test_콜백_봉투에_담기면_명세_20_2_모양이_된다() -> None:
    result = ProgramStructuringResult.model_validate(spec_result())
    body = cb.completed(META, result.model_dump(by_alias=True, mode="json"))

    assert body["success"] is True
    assert body["error"] is None
    assert body["data"]["status"] == "COMPLETED"
    assert body["data"]["pipeline"] == "LLM"
    assert body["data"]["supportType"] == "SUBSIDY"
    assert body["data"]["eligibility"]["uncertainConditions"] == ["공동사업자 신청 가능 여부"]
