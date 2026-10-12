"""1번 NLP 파트 계약 — 명세 20.3 · 20.6 · 20.7 · 20.10.

거래 분류 · 공고 구조화 · 브리핑 생성 · 신고서 초안 점검 네 경로가 모두 여기 있다.
20.10 만 동기 호출이라 `jobId` 가 없고, 콜백 대신 응답으로 결과를 돌려준다.

JSON은 camelCase(`CamelModel`). 명세에 없는 필드는 주석으로 "확장"임을 밝힌다.
`status`와 `ModelMeta` 4필드는 `callback.completed()`가 붙이므로 결과 모델에 두지 않는다.
"""

# `date` 를 그대로 쓰면 안 된다 — `BriefingMetric.date` 필드가 클래스 본문에서 이 이름을
# 가린다. 애노테이션보다 값 대입이 먼저라 `date | None` 의 `date` 가 `None` 으로 평가된다.
from datetime import date as DateOnly
from datetime import datetime
from decimal import Decimal
from typing import Literal, get_args

from pydantic import Field, field_validator

from app.common.callback import ResultStatus
from app.common.envelope import CamelModel
from app.common.model_meta import ModelMeta

# ---------------------------------------------------------------- 공통


class AcceptedJob(CamelModel):
    """비동기 접수 응답 — `202 { "jobId": ... }` (명세 20.2).

    `app/ocr/schemas.py`의 같은 이름 모델과 정의가 같다. 파트 간 import를 만들지 않으려고
    재정의했다 — `app/common`으로 합칠 때 양쪽을 함께 지운다.
    """

    job_id: int


# API 7.1 — 요식업 우선, 이후 확장
IndustryType = Literal[
    "FOOD_CAFE",
    "FOOD_RESTAURANT",
    "FOOD_BAKERY",
    "FOOD_OTHER",
    "RETAIL",
    "SERVICE",
    "OTHER",
]

# ERD 「입출금 방향」 — TRANSACTION.direction
Direction = Literal["DEPOSIT", "WITHDRAWAL"]

# API 8.1의 23종. ERD core.transaction_category.code 와 동일하다.
TransactionCategory = Literal[
    # 입금 7종
    "CARD_SETTLEMENT",
    "PLATFORM_SETTLEMENT",
    "CASH_SALES",
    "TRANSFER_SALES",
    "LOAN_DISBURSEMENT",
    "OWNER_FUNDING",
    "OTHER_INCOME",
    # 출금 16종
    "RENT",
    "PAYROLL",
    "SOCIAL_INSURANCE",
    "MATERIALS",
    "UTILITIES",
    "TELECOM",
    "ADVERTISING",
    "PLATFORM_FEE",
    "INSURANCE",
    "SUBSCRIPTION",
    "LOAN_REPAYMENT",
    "TAX",
    "SUPPLIES",
    "EQUIPMENT",
    "PERSONAL",
    "OTHER_EXPENSE",
]

# API 8.1이 카테고리마다 방향을 못박고 있다. 입금 거래에 출금 카테고리를 붙이는 오류를
# 신뢰도와 무관하게 잡아내려고 표로 들고 있다 (`invalid_results`).
# 23종 전체를 덮는지는 테스트가 `get_args(TransactionCategory)`와 대조한다.
CATEGORY_DIRECTION: dict[str, Direction] = {
    "CARD_SETTLEMENT": "DEPOSIT",
    "PLATFORM_SETTLEMENT": "DEPOSIT",
    "CASH_SALES": "DEPOSIT",
    "TRANSFER_SALES": "DEPOSIT",
    "LOAN_DISBURSEMENT": "DEPOSIT",
    "OWNER_FUNDING": "DEPOSIT",
    "OTHER_INCOME": "DEPOSIT",
    "RENT": "WITHDRAWAL",
    "PAYROLL": "WITHDRAWAL",
    "SOCIAL_INSURANCE": "WITHDRAWAL",
    "MATERIALS": "WITHDRAWAL",
    "UTILITIES": "WITHDRAWAL",
    "TELECOM": "WITHDRAWAL",
    "ADVERTISING": "WITHDRAWAL",
    "PLATFORM_FEE": "WITHDRAWAL",
    "INSURANCE": "WITHDRAWAL",
    "SUBSCRIPTION": "WITHDRAWAL",
    "LOAN_REPAYMENT": "WITHDRAWAL",
    "TAX": "WITHDRAWAL",
    "SUPPLIES": "WITHDRAWAL",
    "EQUIPMENT": "WITHDRAWAL",
    "PERSONAL": "WITHDRAWAL",
    "OTHER_EXPENSE": "WITHDRAWAL",
}

# ---------------------------------------------------------------- 20.3 거래 분류

# 기술스택 §6.3 — "배치는 한 요청에 거래 20건. 1건씩 보내면 시스템 프롬프트를 20번 낸다."
# max_tokens(2048)가 20건 기준이라 상한을 계약으로 둔다. 넘으면 출력이 조용히 잘리는 대신
# 422로 거절한다. 늘릴 때는 max_tokens도 함께 올린다.
#
# **이 값은 LLM 한 번에 보내는 건수다. 요청 하나가 담는 건수(`REQUEST_MAX`)와 다르다.**
BATCH_MAX = 20

# 요청 하나가 담을 수 있는 거래 수. 워커가 `BATCH_MAX`씩 잘라 **순차로** 부르고 콜백은
# 한 번만 보낸다 (`router.run_classification_job`).
#
# 왜 둘을 나눴나 — 백엔드의 Job 기계가 `1 Job ↔ 1 콜백`을 강제한다. `AiResultApplier`가
# 이미 종단 상태인 Job의 콜백을 버리므로, 400건짜리 Job 하나를 20건씩 20번 POST하면
# **첫 콜백만 반영되고 나머지 19번(380건)은 버려진다. LLM 비용은 20번 다 나간다.**
# 쪼개는 일을 워커가 요청 안에서 끝내야 그 사고가 안 난다 (2026-09-11 백엔드 회신).
#
# 400 = 20청크 × 실측 15.0초 = 약 300초. `JobProperties.stallTimeout`(10분)의 절반이라
# 스위퍼가 실행 중인 Job을 회수하지 않는다. 이 숫자를 올리려면 배치당 소요를 다시 재고
# 그 예산 안에 드는지 확인한다 (`evals/p1-01/report.json`).
REQUEST_MAX = 400


class BusinessContext(CamelModel):
    """업종만 보낸다. 사업장명·사업자번호는 분류에 필요하지 않다 (§20.1 입력 위생)."""

    industry_type: IndustryType


class FewShotExample(CamelModel):
    """사용자 정정 이력(`TRANSACTION_CLASSIFICATION_HISTORY`)에서 최신순 상위 N건 — §6.3.

    프리픽스 캐시는 정확 일치라 **하루 1회만 갱신**해야 한다. 매 요청 재구성하면 적중률이 0이 된다.
    """

    description: str = Field(min_length=1, max_length=200)
    direction: Direction
    category: TransactionCategory
    merchant_name: str | None = Field(default=None, max_length=200)


class TransactionToClassify(CamelModel):
    """금융망이 주는 정보가 이것뿐이다 — `DEMAND_DEPOSIT_12` 응답에 거래처명 필드가 없고
    `transactionSummary` 한 줄이 전부다 (§4.1). 적요·금액·방향·주기 힌트로 추론해야 한다.
    """

    transaction_id: int
    description: str = Field(max_length=200)
    direction: Direction
    amount: int = Field(ge=0)
    occurred_at: datetime
    # P0-03 반복 탐지가 계산한 주기. LLM이 주기를 추론하는 것보다 정확하고 싸다 (§6.3).
    cycle_hint: str | None = Field(default=None, max_length=200)


class TransactionClassificationRequest(CamelModel):
    job_id: int
    business: BusinessContext
    # 업종별 부분집합이다. 응답 `category`가 이 안에 있는지는 `invalid_results`가 검사한다.
    allowed_categories: list[TransactionCategory] = Field(min_length=1)
    few_shot_examples: list[FewShotExample] = Field(default_factory=list)
    # 워커가 `BATCH_MAX` 씩 잘라 순차로 부른다. 백엔드는 이 상한까지 한 Job 에 담아도 된다.
    transactions: list[TransactionToClassify] = Field(min_length=1, max_length=REQUEST_MAX)


class ClassificationResult(CamelModel):
    """`confidence`는 항목 단위다 (노트 C-1).

    API 서버가 `confidence < 0.85`이거나 신규 거래처인 결과에 `needsConfirmation = true`를
    세운다 (§20.3). 그 판정이 결과 하나하나에 일어나므로 최상위에 둘 수 없다.
    """

    transaction_id: int
    category: TransactionCategory
    merchant_name: str | None = Field(default=None, max_length=200)
    confidence: float = Field(ge=0, le=1)
    reason: str = Field(min_length=1, max_length=500)


class TransactionClassificationResult(CamelModel):
    """콜백 `data`에 평면 전개된다 — `callback.completed(meta, result)` (노트 C-4).

    `status`·`modelName`·`modelVersion`·`promptVersion`·`pipeline`은 `completed()`가 붙인다.
    """

    results: list[ClassificationResult] = Field(default_factory=list)


def invalid_results(
    request: TransactionClassificationRequest,
    results: list[ClassificationResult],
) -> dict[int, str]:
    """폐기해야 하는 결과와 그 사유를 낸다. `{transactionId: 사유}`.

    명세 20.1 — *"허용 카테고리 외 값, 근거 없는 수치는 폐기하고 **해당 항목만** 제외한다."*
    응답 전체를 버리지 않는다. 호출부가 이 표를 보고 남길 건과 버릴 건을 나눈다.

    Pydantic이 잡지 못하는 세 가지를 본다 — 타입은 맞지만 이 요청에서는 틀린 값들이다.
    """
    allowed = set(request.allowed_categories)
    directions = {t.transaction_id: t.direction for t in request.transactions}
    seen: set[int] = set()
    invalid: dict[int, str] = {}

    for result in results:
        tx_id = result.transaction_id
        if tx_id in seen:
            invalid[tx_id] = "같은 transactionId가 두 번 나왔습니다."
            continue
        seen.add(tx_id)

        requested = directions.get(tx_id)
        if requested is None:
            invalid[tx_id] = "요청에 없는 transactionId입니다."
        elif result.category not in allowed:
            invalid[tx_id] = f"allowedCategories 밖의 카테고리입니다: {result.category}"
        elif CATEGORY_DIRECTION[result.category] != requested:
            invalid[tx_id] = (
                f"{requested} 거래에 {CATEGORY_DIRECTION[result.category]} 카테고리"
                f"({result.category})를 붙였습니다."
            )

    return invalid


def missing_transaction_ids(
    request: TransactionClassificationRequest,
    results: list[ClassificationResult],
) -> list[int]:
    """분류 결과가 오지 않은 거래. 배치 일부가 빠져도 나머지는 살린다.

    빠진 건은 규칙·사전 결과를 유지하고 `needsConfirmation`으로 넘긴다 (§6.3 폴백).
    """
    classified = {r.transaction_id for r in results}
    return [t.transaction_id for t in request.transactions if t.transaction_id not in classified]


# ---------------------------------------------------------------- 20.7 브리핑 생성 (P1-17)

PeriodType = Literal["WEEKLY", "MONTHLY"]

# 지표 개수 상한. 분류의 `BATCH_MAX`와 성격이 다르다 — 모델 한도가 아니라 **브리핑의 목적**이
# 정하는 값이다. 주간 요약에 지표 20개를 넣으면 요약이 아니다. 넘치면 계산 엔진이 고를 일이지
# 모델이 버릴 일이 아니므로, 조용히 자르지 않고 422로 거절한다.
METRICS_MAX = 20


class BriefingMetric(CamelModel):
    """계산 엔진이 낸 지표 하나. **워커는 이 값을 문장으로 옮기기만 한다.**

    `value`가 `float`가 아니라 `Decimal`인 이유는 프롬프트가 문자열이기 때문이다.
    `float`로 받으면 JSON의 `748000`이 `748000.0`이 되어 "748000.0원"이라고 쓰게 된다.
    `-95`의 환각 숫자 검증도 출력 숫자를 입력과 문자열로 대조하므로, 여기서 표기가 바뀌면
    **맞는 숫자를 환각으로 잡는다.** `Decimal`은 JSON에 적힌 그대로를 보존한다.

    `sourceRef`는 여기 없다 — 명세 15.2의 응답에는 있지만 근거 연결은 API 서버가 하는 일이고
    워커가 알 필요가 없다. 보내면 프롬프트만 길어진다.
    """

    label: str = Field(min_length=1, max_length=100)
    value: Decimal
    change_rate: Decimal | None = None
    date: DateOnly | None = None
    title: str | None = Field(default=None, max_length=100)


class BriefingGenerationRequest(CamelModel):
    """§20.7 요청.

    **원본 거래를 받는 필드가 없다.** 일부러 없다 — 도메인 규칙 2가 "수치 계산은 백엔드
    엔진이, LLM은 자연어 변환만"이고, 거래를 주면 모델이 직접 세기 시작한다. 입력을
    계산 엔진 산출값으로 좁히는 것이 환각을 **구조로** 막는 유일한 방법이다.
    이 주석은 필드를 추가하려는 사람을 향한 것이다.
    """

    job_id: int
    briefing_id: int
    period_type: PeriodType
    metrics: list[BriefingMetric] = Field(min_length=1, max_length=METRICS_MAX)
    # 명세 예: "확정 표현 금지, 조언은 권장 수준, 입력에 없는 수치 생성 금지".
    # 없으면 워커 시스템 프롬프트의 기본 어조를 쓴다 — 규칙을 여기에만 의존하지 않는다.
    tone_guide: str | None = Field(default=None, max_length=500)


class BriefingGenerationResult(CamelModel):
    """§20.7 Callback 결과. `status`는 `callback.completed()`가 붙인다.

    상한은 화면 계약에서 왔다(명세 15.2 예시: headline 25자 · content 90자 안팎).
    넉넉히 잡되 상한을 두는 이유는 **잘린 출력을 조용히 넘기지 않기 위해서**다 —
    `max_tokens`에 걸려 문장이 끊기면 여기서 걸린다.
    """

    headline: str = Field(min_length=1, max_length=100)
    content: str = Field(min_length=1, max_length=1000)


__all__ = [
    "BATCH_MAX",
    "REQUEST_MAX",
    "CATEGORY_DIRECTION",
    "METRICS_MAX",
    "RAW_TEXT_MAX",
    "FIELDS_MAX",
    "PENDING_MAX",
    "NOTES_MAX",
    "SENSITIVE_FIELD_KEYS",
    "VALUE_ABS_MAX",
    "AcceptedJob",
    "BriefingCallbackData",
    "CallbackData",
    "ClassificationCallbackData",
    "ProgramStructuringCallbackData",
    "BriefingGenerationRequest",
    "BriefingGenerationResult",
    "BriefingMetric",
    "BusinessContext",
    "ClassificationResult",
    "Direction",
    "DocumentAssistRequest",
    "DocumentAssistResult",
    "DraftFieldSummary",
    "Eligibility",
    "FewShotExample",
    "FormType",
    "IndustryType",
    "PendingItem",
    "PeriodType",
    "ProgramStructuringRequest",
    "ProgramStructuringResult",
    "RequiredDocument",
    "ReviewNote",
    "Severity",
    "SupportType",
    "TransactionCategory",
    "TransactionClassificationRequest",
    "TransactionClassificationResult",
    "TransactionToClassify",
    "invalid_results",
    "missing_transaction_ids",
]


def _assert_category_table_complete() -> None:
    """import 시 한 번 — 카테고리 23종과 방향 표가 어긋나면 즉시 실패한다."""
    categories = set(get_args(TransactionCategory))
    if categories != set(CATEGORY_DIRECTION):
        missing = categories - set(CATEGORY_DIRECTION)
        extra = set(CATEGORY_DIRECTION) - categories
        raise RuntimeError(
            f"CATEGORY_DIRECTION이 TransactionCategory와 다릅니다. 누락={missing} 잉여={extra}"
        )


_assert_category_table_complete()


# ---------------------------------------------------------------- 20.6 공고 구조화 (P1-15)

# 명세 17.1 — 융자 / 보조금 / 바우처 / 컨설팅 / 그 밖
SupportType = Literal["FUNDING", "SUBSIDY", "VOUCHER", "CONSULTING", "OTHER"]

# 공고 원문 길이 상한. 기업마당 공고는 길어야 수천 자다. 상한을 두는 이유는 프롬프트
# 예산이 아니라 **들어온 것이 공고가 맞는지** 보기 위해서다 — 수십만 자가 오면 크롤러가
# 페이지 전체를 긁어 온 것이고, 그대로 모델에 넣으면 조용히 비싸진다.
RAW_TEXT_MAX = 50_000


class ProgramStructuringRequest(CamelModel):
    """§20.6 요청. 공고 원문 한 덩어리를 받는다.

    `sourceUrl` 은 모델에게 읽히려고 주는 것이 아니라 **결과에 그대로 되돌려 붙이기
    위한** 것이다. 명세 17.1 이 원문 링크와 수집일을 항상 병기하라고 못박았고, 화면에서
    원문으로 갈 수 없으면 사용자가 구조화 결과를 확인할 방법이 사라진다.
    """

    job_id: int
    program_id: int
    raw_text: str = Field(min_length=1, max_length=RAW_TEXT_MAX)
    source_url: str = Field(min_length=1, max_length=2048)


class RequiredDocument(CamelModel):
    """제출 서류 하나 (명세 17.2 `requiredDocuments[]`).

    **`issueUrl` 과 `fee` 를 담지 않는다.** 17.2 가 "서류 사전(17.4)과 연결되어 발급
    링크를 제공한다" 고 적었으니 링크의 정본은 사전이다. 공고 본문에서 주워 온 URL 을
    여기에 실으면 죽은 링크나 엉뚱한 기관 페이지가 그대로 사용자에게 나간다.
    이름·발급처·발급방법까지만 뽑고 링크는 백엔드가 사전에서 붙인다.
    """

    document_name: str = Field(min_length=1, max_length=200)
    issuer: str | None = Field(default=None, max_length=200)
    method: str | None = Field(default=None, max_length=200)


class Eligibility(CamelModel):
    """지원 대상 조건 (명세 17.2 `eligibility`).

    ## 조건이지 판정이 아니다

    여기 담기는 것은 공고가 말하는 **조건 자체**다. 이 사업장이 대상인지는 백엔드
    규칙 엔진이 정한다 — 모델이 「자격 있음」 을 내면 근거 없는 판정이 그대로 화면에
    간다. 그래서 `eligible` 같은 필드를 두지 않는다.

    ## `uncertainConditions` 가 이 기능의 안전장치다

    공고는 사람이 쓴 문서라 조건이 애매한 것이 흔하다. 모델이 애매한 자리를 **채워
    버리면** 사용자는 "대상이다" 로 읽고 서류를 준비한 뒤 기관에서 거절당한다.

    확신할 수 없으면 해당 필드를 비우고 **공고의 표현을 여기에 남긴다.** 명세 17.1 의
    `needsVerification` 플래그가 이 목록이 비었는지로 정해지고, 화면은 "확인 필요" 로
    띄운다. 자격 외의 불확실(금액·기간·서류)도 같은 목록에 넣는다 — 17.2 가 보여 주는
    자리가 여기 하나뿐이고, 사용자에게는 "확인할 것" 이라는 한 덩어리로 보이면 된다.

    빈 목록이 곧 "확실하다" 는 선언이므로, **애매한데 비워 두는 것이 가장 나쁜 실패**다.
    """

    regions: list[str] = Field(default_factory=list)
    industries: list[str] = Field(default_factory=list)
    max_annual_revenue: int | None = Field(default=None, ge=0)
    min_years_in_business: int | None = Field(default=None, ge=0)
    max_years_in_business: int | None = Field(default=None, ge=0)
    excludes: list[str] = Field(default_factory=list)
    uncertain_conditions: list[str] = Field(default_factory=list)


class ProgramStructuringResult(CamelModel):
    """§20.6 Callback 결과. `status` 와 ModelMeta 는 `callback.completed()` 가 붙인다.

    명세 20.6 이 지정한 여섯 가지(`eligibility` · `supportType` · `maxAmount` ·
    `applyStart`/`applyEnd` · `requiredDocuments` · `uncertainConditions`)만 담는다.

    ## 명세 공백 둘을 메웠다 (2026-09-16, 백엔드 합의)

    17.1·17.2 에는 있는데 20.6 목록에 없던 필드가 셋이었다. 그중 둘을 여기 넣는다.

    | 필드 | 어디 | 지금 |
    | --- | --- | --- |
    | `interestRate` | 17.1 | **여기서 채운다** |
    | `expectedDisbursementDays` | 17.2 | **여기서 채운다** |
    | `rawSummary` | 17.2 | 워커 몫이 아니다 — **수집기가 채운다**(기업마당 `bsnsSumryCn`, `V24`) |

    ## 「모르면 비운다」가 이 둘에서 특히 중요하다

    실제 공고 32건을 재 보니 **지급 시기를 말하는 공고는 1건(3%)** 이고 「선정 후 N일」은
    0건이었다. 즉 `expectedDisbursementDays` 는 **정직하게 뽑으면 거의 항상 빈다.**
    그것이 맞는 결과다 — 백엔드가 이 값으로 「지원금이 정산 공백 전에 도착한다」(17.3
    `canArriveBeforeGap`)를 말하기 때문에, 없는 값을 지어내면 **사장님이 오지 않을 돈을
    기다리며 다른 자금 대책을 접는다.**

    그래서 **환산하지 않는다.** 「선정 후 1개월」을 30 으로 바꾸지 말고 비운다 —
    선정일을 모르므로 그 30 은 기준점 없는 숫자이고, 애초에 도메인 규칙 2 가 계산을
    백엔드 몫으로 정했다. 원문에 **일수가 적혀 있는 경우에만** 채운다.
    """

    eligibility: Eligibility
    support_type: SupportType
    max_amount: int | None = Field(default=None, ge=0)
    apply_start: DateOnly | None = None
    apply_end: DateOnly | None = None
    required_documents: list[RequiredDocument] = Field(default_factory=list)
    # 연 %. 대출 쪽과 같은 단위다 (`V23` — "연 %. 금융망은 0.1~20 만 허용한다").
    # 0.025 같은 소수로 보내면 화면에 `0.03%` 로 뜬다.
    interest_rate: Decimal | None = Field(default=None, ge=0, le=100)
    # **원문에 일수가 적힌 경우에만.** 위 머리말 참고 — 환산은 지어내기다.
    expected_disbursement_days: int | None = Field(default=None, ge=0, le=730)


# ---------------------------------------------------------- 20.10 신고서 초안 점검 (P0-12 보조)

# 명세 14.3 서식 템플릿 — 간편장부 / 기준경비율 / 단순경비율 / 복식부기
FormType = Literal["SIMPLE_LEDGER", "STANDARD_RATE", "SIMPLE_RATE", "DOUBLE_ENTRY"]

# 명세 20.10 — `ERROR` 가 없다. 세무 판단을 확정하지 않기 때문이다(도메인 규칙 4,
# 14.3 `disclaimer`). 「이건 잘못됐다」 라고 말할 수 있는 자리를 만들어 두면 모델이
# 결국 거기에 쓴다. 우리가 낼 수 있는 것은 확인 요청(`WARN`)과 설명(`INFO`)뿐이다.
Severity = Literal["WARN", "INFO"]

FIELDS_MAX = 60
PENDING_MAX = 20
NOTES_MAX = 20

# 명세 14.3 이 `sensitive: true` 로 표시한 항목과, 값이 사람을 가리키는 항목.
# 20.1 이 "신고서 초안(14.3)의 민감 항목은 워커에 전달 자체를 하지 않는다" 고 못박았다.
#
# 실제 철자를 아는 것만 넣었다 — 주민등록번호·환급금 계좌는 별지 제40호 항목이라
# 14.3 에 키가 없어서, 이름을 **추측해서** 막는 대신 `value` 를 숫자로 묶어 구조로
# 막는다(`DraftFieldSummary.value` 주석).
SENSITIVE_FIELD_KEYS = frozenset({"HOME_ADDRESS", "BIRTH_DATE", "NAME"})

# 항목 금액의 절대값 상한 = 1조 원.
#
# 금액 검증이 아니라 **민감 정보 검증**이다. 숫자로만 된 주민등록번호(13자리)나
# 계좌번호(10~14자리)는 `int` 로 조용히 파싱된다 — 문자열 차단만으로는 못 막는다.
# 간편장부대상자는 직전연도 수입금액 1억 5천만원 미만이고(14.3), 신고서 초안의 어떤
# 항목도 조 단위가 될 수 없다. 13자리 숫자가 오면 금액이 아니라 식별번호다.
VALUE_ABS_MAX = 1_000_000_000_000


def _reject_sensitive_key(field_key: str) -> str:
    """민감 항목이면 **요청 전체를 거부한다.** 조용히 버리지 않는다.

    버리면 백엔드는 자기가 민감 항목을 보내고 있다는 것을 영영 모른다. 20.1 의
    「전달 자체를 하지 않는다」 는 백엔드 쪽 의무이고, 어겼을 때 알려 줄 수 있는
    곳이 여기뿐이다.

    거부해도 사용자는 손해를 보지 않는다 — 20.10 이 "초안 생성은 이 호출에
    의존하지 않는다" 고 적었고, 워커가 실패하면 초안은 `reviewNote` 없이 그대로
    반환된다. 잃는 것은 점검 문장이고 지키는 것은 주소·생년월일이다.
    """
    if field_key in SENSITIVE_FIELD_KEYS:
        raise ValueError(f"민감 항목은 워커로 보내지 않습니다 (명세 20.1). fieldKey={field_key}")
    return field_key


class DraftFieldSummary(CamelModel):
    """집계가 끝난 서식 항목 하나 (명세 14.3 `sections[].fields[]` 의 요약).

    `value` 의 타입과 범위가 **이 스키마의 두 번째 방어선**이다. 20.10 은 "항목 합계·
    건수" 만 보낸다고 적었으므로 금액 아닌 값이 올 이유가 없다. 키 이름을 못 맞혀도
    성명("정유선")·주소·생년월일("1985-03-12")은 정수가 아니라 여기서 걸리고,
    숫자로만 된 주민등록번호·계좌번호는 `VALUE_ABS_MAX` 에 걸린다.

    건수 필드는 항목마다 다르게 온다 — 20.10 예시에서 `PAYROLL` 은
    `evidenceMissingCount`·`unreviewedCount` 를, `RENT` 는 `monthsCovered` 를 달고
    온다. 그래서 전부 선택이다.
    """

    field_key: str = Field(min_length=1, max_length=60)
    value: int | None = Field(default=None, ge=-VALUE_ABS_MAX, le=VALUE_ABS_MAX)
    transaction_count: int | None = Field(default=None, ge=0)
    evidence_missing_count: int | None = Field(default=None, ge=0)
    unreviewed_count: int | None = Field(default=None, ge=0)
    months_covered: int | None = Field(default=None, ge=0)

    @field_validator("field_key")
    @classmethod
    def _no_sensitive_key(cls, value: str) -> str:
        return _reject_sensitive_key(value)


class PendingItem(CamelModel):
    """항목 금액에 넣지 않고 남겨 둔 건 (명세 14.3 `pendingAmount`).

    `reason` 을 Literal 로 묶지 않았다. 명세 전체에서 `EQUIPMENT_PURCHASE` 한 값만
    나오고(20.10 예시) 허용 집합을 적은 곳이 없다. 지금 추측해서 묶으면 백엔드가
    두 번째 사유를 넣는 날 요청이 통째로 400 이 되는데, 그 대가로 막는 것이 없다.
    """

    field_key: str = Field(min_length=1, max_length=60)
    reason: str = Field(min_length=1, max_length=60)
    amount: int | None = None
    count: int | None = Field(default=None, ge=0)

    @field_validator("field_key")
    @classmethod
    def _no_sensitive_key(cls, value: str) -> str:
        return _reject_sensitive_key(value)


class DocumentAssistRequest(CamelModel):
    """§20.10 요청. **동기 호출이라 `jobId` 가 없다** — 20.2 목록에서 이 경로만
    콜백을 쓰지 않는다.

    민감 정보를 담을 수 있는 키를 아예 두지 않았다. 주민등록번호·계좌번호·주소지·
    생년월일·부양가족에 해당하는 필드가 이 모델과 하위 모델 어디에도 없다
    (`tests/nlp/test_document_assist_schema.py` 가 매번 확인한다).
    """

    draft_id: int
    form_type: FormType
    business: BusinessContext
    fields: list[DraftFieldSummary] = Field(min_length=1, max_length=FIELDS_MAX)
    pending: list[PendingItem] = Field(default_factory=list, max_length=PENDING_MAX)
    tone_guide: str | None = Field(default=None, max_length=500)


class ReviewNote(CamelModel):
    """항목 하나에 대한 점검 문장. 14.3 의 `reviewNote` 로 저장된다."""

    field_key: str = Field(min_length=1, max_length=60)
    severity: Severity
    message: str = Field(min_length=1, max_length=500)


class DocumentAssistResult(CamelModel):
    """§20.10 응답 `data`. 동기라 `status` 도 ModelMeta 도 붙지 않는다 — 20.10 예시가
    `notes` 하나만 담고 있고, 20.1 의 모델 식별 정보 요구는 예측 모델(20.11~12)에
    한정된 규약이다.

    **남는 구멍 하나** — 저장되는 `reviewNote` 에 어느 프롬프트 버전이 썼는지가
    남지 않는다. 문장이 이상하다는 제보가 와도 어느 판이 냈는지 되짚을 수 없다.
    계약 밖 필드를 임의로 얹지 않고 백엔드와 합의한 뒤 넣는다(20.6 과 같은 이유).

    빈 목록이 정상이다. 점검할 것이 없으면 아무 문장도 만들지 않는 것이 맞고,
    억지로 한 문장 채우면 그게 곧 환각이다.
    """

    notes: list[ReviewNote] = Field(default_factory=list, max_length=NOTES_MAX)


# ------------------------------------------------- 콜백 본문 (문서 전용 · 명세 20.2)

# 아래 모델들은 **런타임에 쓰이지 않는다.** `callback.completed()` 가 dict 를 만들고,
# 이것들은 그 dict 의 모양을 OpenAPI 에 싣기 위해 존재한다.
#
# 왜 필요한가 — 비동기 셋의 202 응답은 `{jobId}` 뿐이라 **결과 모양이 문서에 안 실린다.**
# 백엔드는 이 문서를 보고 DTO 를 만드는데, 정작 자기가 받을 콜백 본문이 거기 없다.
# 2026-09-11 에 백엔드가 `ai/app/nlp/schemas.py` 를 직접 읽어 DTO 를 만든 것이 그 증거다.
#
# `ModelMeta` 를 상속하는 이유는 `completed()` 가 메타를 **평면으로 펼치기** 때문이다
# (`{"status": …, **meta, **result}`). 중첩 모델로 두면 문서와 실제가 어긋난다.


class CallbackData(ModelMeta):
    """모든 성공 콜백의 공통 머리 — `status` + 모델 식별 4필드."""

    status: ResultStatus


class ClassificationCallbackData(CallbackData):
    results: list[ClassificationResult]


class ProgramStructuringCallbackData(CallbackData):
    eligibility: Eligibility
    support_type: SupportType
    max_amount: int | None = None
    apply_start: DateOnly | None = None
    apply_end: DateOnly | None = None
    required_documents: list[RequiredDocument] = Field(default_factory=list)


class BriefingCallbackData(CallbackData):
    headline: str
    content: str
