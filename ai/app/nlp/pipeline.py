"""1번 NLP 파이프라인 — stub / LLM. 20.3 분류 · 20.6 공고 · 20.7 브리핑 · 20.10 초안 점검.

OCR(2번)이 AUTO·OCR_PIPELINE·VLM 세 갈래를 고르는 것과 달리 여기는 두 갈래뿐이라
`PipelineSet` 없이 `build_classifier()` 하나로 끝낸다. 요청에 `preferredPipeline` 도 없다
(명세 20.3).

`ClassificationError` 는 `app.ocr.pipelines.base.ExtractionError` 와 모양이 같지만 따로
정의했다 — 파트 간 import 를 만들지 않는 것이 이 레포의 규칙이다(`AcceptedJob` 주석 참고).
`app/common` 으로 합칠 때 양쪽을 함께 지운다.

허용 카테고리 밖·방향 불일치·ID 중복 검사는 여기서 하지 않는다. 호출부(`router.py`)가
`invalid_results()` 로 stub·LLM 양쪽에 똑같이 적용한다 — stub 이 규격을 어기는 것도 잡힌다.
"""

from __future__ import annotations

import asyncio
import logging
from datetime import date as DateOnly
from decimal import Decimal
from typing import Any, Protocol

from pydantic import BaseModel, ConfigDict, ValidationError

from app.common.claude import ClaudeClient, ClaudeError
from app.common.model_meta import ModelMeta
from app.config import Settings
from app.nlp import prompts
from app.nlp.schemas import (
    CATEGORY_DIRECTION,
    BriefingGenerationRequest,
    BriefingGenerationResult,
    ClassificationResult,
    DocumentAssistRequest,
    DocumentAssistResult,
    Eligibility,
    ProgramStructuringRequest,
    ProgramStructuringResult,
    ReviewNote,
    Severity,
    TransactionCategory,
    TransactionClassificationRequest,
)

log = logging.getLogger(__name__)


class ClassificationError(Exception):
    """모델 단계의 실패. 콜백에 AI_ANALYSIS_FAILED 로 실린다.

    `retryable` 은 `ClaudeError` 에서 그대로 넘어온다 — 429·5xx·네트워크면 True.
    차단기가 이 값만 센다(기술스택 §8.6). 기본이 False 인 것은 의도적이다:
    분류할 수 없는 실패를 실수로 흘려도 차단기가 괜히 열리지는 않게 한다.
    """

    def __init__(self, stage: str, message: str, *, retryable: bool = False) -> None:
        super().__init__(message)
        self.stage = stage
        self.retryable = retryable


class ClassificationPipeline(Protocol):
    async def classify(
        self, request: TransactionClassificationRequest
    ) -> tuple[list[ClassificationResult], ModelMeta]: ...


# ---------------------------------------------------------------- stub

_STUB_META = ModelMeta(model_name="stub", model_version=None, prompt_version=None, pipeline="STUB")

# 0.85(§20.3 기본 임계값) 아래로 둔다 — API 서버가 전건에 needsConfirmation 을 세우므로
# stub 결과가 사용자 확인 없이 확정되지 않는다. pipeline="STUB" 와 이중 안전장치다.
_STUB_CONFIDENCE = 0.5


class StubClassifier:
    """고정 규칙 응답 — 외부 호출 없이 계약 모양만 돌려준다.

    용도: 백엔드 연동 검증(도입 순서 6단계 "더미 응답으로 Job 이 완결 상태에 도달").

    방향이 맞는 허용 카테고리 중 첫 번째를 고른다. 하나도 없으면(예: 출금 거래만 있는데
    허용 목록이 입금 카테고리뿐) **그 건을 뺀다** — 규격을 어기는 결과를 만드는 대신
    `missing_transaction_ids()` 폴백 경로를 타게 한다.
    """

    async def classify(
        self, request: TransactionClassificationRequest
    ) -> tuple[list[ClassificationResult], ModelMeta]:
        results = []
        for tx in request.transactions:
            category = next(
                (c for c in request.allowed_categories if CATEGORY_DIRECTION[c] == tx.direction),
                None,
            )
            if category is None:
                continue
            results.append(
                ClassificationResult(
                    transaction_id=tx.transaction_id,
                    category=category,
                    merchant_name=None,
                    confidence=_STUB_CONFIDENCE,
                    reason="stub 응답입니다. 실제 분류 결과가 아닙니다.",
                )
            )
        return results, _STUB_META


# ---------------------------------------------------------------- live (Claude)

# 모델에게 요구하는 출력 스키마. 계약 스키마(`ClassificationResult`)와 분리한다 —
# camelCase 로 받고, category 는 23종 Literal 로 강제한다.
#
# 허용 카테고리(요청별 부분집합)를 Literal 로 좁히지 않는다. 요청마다 스키마를 새로 만들면
# 캐시와 SDK 스키마 재사용이 깨지고, 어차피 `invalid_results()` 가 목록 밖을 잡는다.
# 프롬프트가 허용 목록을 문장으로 알려준다.


class _Strict(BaseModel):
    model_config = ConfigDict(extra="forbid")


class _LlmResult(_Strict):
    transactionId: int
    category: TransactionCategory
    merchantName: str | None
    confidence: float
    reason: str


class _LlmClassification(_Strict):
    results: list[_LlmResult]


class ClaudeClassifier:
    """Claude 텍스트 분류 경로(`LLM`) — 기술스택 §6.3.

    호출 규약(구조화 출력·effort·폴백·stop_reason·usage 로그)은 `ClaudeClient` 가 맡는다.
    이 파일은 요청 → 프롬프트 → 계약 스키마 변환만 한다.
    """

    def __init__(self, settings: Settings, claude: ClaudeClient) -> None:
        self._settings = settings
        self._claude = claude

    async def classify(
        self, request: TransactionClassificationRequest
    ) -> tuple[list[ClassificationResult], ModelMeta]:
        user_text = prompts.classification_user_text(
            industry_type=request.business.industry_type,
            allowed_categories=list(request.allowed_categories),
            few_shot_examples=request.few_shot_examples,
            transactions=request.transactions,
        )
        try:
            response: Any = await self._claude.parse(
                model=self._settings.classification_model,
                system=prompts.CLASSIFICATION_SYSTEM,
                messages=[{"role": "user", "content": user_text}],
                output_format=_LlmClassification,
                max_tokens=self._settings.classification_max_tokens,
                effort=self._settings.classification_effort,
            )
        except ClaudeError as exc:
            raise ClassificationError(exc.stage, str(exc), retryable=exc.retryable) from exc

        out: _LlmClassification = response.parsed_output
        results = []
        for item in out.results:
            try:
                results.append(
                    ClassificationResult(
                        transaction_id=item.transactionId,
                        category=item.category,
                        merchant_name=item.merchantName,
                        confidence=item.confidence,
                        reason=item.reason,
                    )
                )
            except ValidationError as exc:
                # 계약 범위를 벗어난 값(confidence 가 0~1 밖, reason 이 빈 문자열이거나 500자
                # 초과 등). 명세 20.1 — "근거 없는 수치는 폐기하고 **해당 항목만** 제외한다".
                # 조용히 0~1 로 깎거나 문자열을 자르지 않고, 배치 전체를 버리지도 않는다.
                # 빠진 건은 `missing_transaction_ids()` 폴백으로 넘어간다.
                log.warning(
                    "분류 결과 항목을 폐기했습니다 transactionId=%s: %s",
                    item.transactionId,
                    exc.errors(include_url=False),
                )
        # response.model 은 실제로 응답한 모델 — 폴백이 일어났으면 폴백 모델이 기록된다
        meta = ModelMeta(
            model_name=response.model,
            model_version=None,
            prompt_version=prompts.CLASSIFICATION_PROMPT_VERSION,
            pipeline="LLM",
        )
        return results, meta


def build_classifier(settings: Settings, claude: ClaudeClient) -> ClassificationPipeline:
    if settings.ai_pipeline_mode == "stub":
        return StubClassifier()
    return ClaudeClassifier(settings, claude)


# ================================================================ 20.7 브리핑 생성


class BriefingPipeline(Protocol):
    async def write(
        self, request: BriefingGenerationRequest
    ) -> tuple[BriefingGenerationResult, ModelMeta]: ...


class StubBriefer:
    """고정 응답. **지표 하나를 그대로 인용한다** — 환각 검증기를 통과해야 하기 때문이다.

    stub 이 검증기에 걸리면 백엔드 연동 검증에서 원인을 찾느라 시간을 버린다. 숫자를
    지어내지 않는 것이 stub 에도 그대로 적용되는 규칙이다.
    """

    async def write(
        self, request: BriefingGenerationRequest
    ) -> tuple[BriefingGenerationResult, ModelMeta]:
        first = request.metrics[0]
        result = BriefingGenerationResult(
            headline="stub 응답입니다. 실제 브리핑이 아닙니다.",
            content=(
                f"{first.label}은 {first.value} 입니다. stub 응답이라 판단이 들어 있지 않습니다."
            ),
        )
        return result, _STUB_META


class _LlmBriefing(_Strict):
    headline: str
    content: str


class ClaudeBriefer:
    """Claude 문장 생성 경로. 숫자 검증은 여기서 하지 않는다.

    검증을 호출부(`router.py`)에 둔 이유는 분류와 같다 — stub 과 LLM 양쪽에 똑같이
    걸려야 하고, 폐기 결과가 `PARTIAL` 이라는 **콜백 결정**으로 이어지기 때문이다.
    """

    def __init__(self, settings: Settings, claude: ClaudeClient) -> None:
        self._settings = settings
        self._claude = claude

    async def write(
        self, request: BriefingGenerationRequest
    ) -> tuple[BriefingGenerationResult, ModelMeta]:
        user_text = prompts.briefing_user_text(
            period_type=request.period_type,
            metrics=request.metrics,
            tone_guide=request.tone_guide,
        )
        try:
            response: Any = await self._claude.parse(
                model=self._settings.briefing_model,
                system=prompts.BRIEFING_SYSTEM,
                messages=[{"role": "user", "content": user_text}],
                output_format=_LlmBriefing,
                max_tokens=self._settings.briefing_max_tokens,
                effort=self._settings.briefing_effort,
            )
        except ClaudeError as exc:
            raise ClassificationError(exc.stage, str(exc), retryable=exc.retryable) from exc

        out: _LlmBriefing = response.parsed_output
        try:
            result = BriefingGenerationResult(headline=out.headline, content=out.content)
        except ValidationError as exc:
            # 계약 범위를 벗어난 출력(빈 문장·상한 초과). 조용히 자르지 않는다 —
            # max_tokens 에 걸려 끊긴 본문이 사용자에게 나가면 안 된다.
            raise ClassificationError(
                "model", f"브리핑 출력이 계약을 벗어났습니다: {exc.errors(include_url=False)}"
            ) from exc

        meta = ModelMeta(
            model_name=response.model,
            model_version=None,
            prompt_version=prompts.BRIEFING_PROMPT_VERSION,
            pipeline="LLM",
        )
        return result, meta


def build_briefer(settings: Settings, claude: ClaudeClient) -> BriefingPipeline:
    if settings.ai_pipeline_mode == "stub":
        return StubBriefer()
    return ClaudeBriefer(settings, claude)


# ================================================================ 20.6 공고 구조화


class StructuringPipeline(Protocol):
    async def structure(
        self, request: ProgramStructuringRequest
    ) -> tuple[ProgramStructuringResult, ModelMeta]: ...


class StubStructurer:
    """고정 응답. **아무 조건도 채우지 않고 전부 `uncertainConditions` 로 넘긴다.**

    이 기능에서 stub 이 지켜야 할 규칙은 브리핑과 정반대다. 브리핑 stub 은 지표를
    인용해야 검증기를 통과하지만, 여기서는 **조건을 지어내지 않는 것**이 계약이다
    (`Eligibility` 머리말). stub 이 그럴듯한 지역·업종을 채우면 연동 검증에서
    "모델이 공고를 읽었다" 로 오인된다.

    `supportType` 은 `OTHER` 다 — 다섯 값 중 유일하게 "분류하지 않았다" 를 뜻한다.
    """

    async def structure(
        self, request: ProgramStructuringRequest
    ) -> tuple[ProgramStructuringResult, ModelMeta]:
        result = ProgramStructuringResult(
            eligibility=Eligibility(
                uncertain_conditions=["stub 응답이라 공고를 읽지 않았습니다. 원문을 확인하세요."]
            ),
            support_type="OTHER",
        )
        return result, _STUB_META


class _LlmEligibility(_Strict):
    regions: list[str] = []
    industries: list[str] = []
    maxAnnualRevenue: int | None = None
    minYearsInBusiness: int | None = None
    maxYearsInBusiness: int | None = None
    excludes: list[str] = []
    uncertainConditions: list[str] = []


class _LlmDocument(_Strict):
    documentName: str
    issuer: str | None = None
    method: str | None = None


class _LlmProgram(_Strict):
    eligibility: _LlmEligibility
    supportType: str
    maxAmount: int | None = None
    applyStart: str | None = None
    applyEnd: str | None = None
    requiredDocuments: list[_LlmDocument] = []
    interestRate: Decimal | None = None
    expectedDisbursementDays: int | None = None


class ClaudeStructurer:
    """Claude 공고 구조화 경로 (20.6) — 기술스택 §6.6.

    ## 계약보다 느슨하게 받아서 좁힌다

    모델 출력을 {@link ProgramStructuringResult} 로 바로 파싱하지 않는다. 그랬다가는
    금리 하나가 범위를 벗어났다고 **공고 전체가 구조화되지 않는다** — 지역·업종·제외
    대상까지 같이 날아가고, 그 공고는 검색에서 빠져 사장님에게 영영 안 보인다.

    그래서 느슨한 모양(`_LlmProgram`)으로 받은 뒤 칸마다 좁히고, **버린 값은 조용히
    지우지 않고 `uncertainConditions` 로 옮긴다.** 화면에 「확인 필요」로 뜨는 편이
    없던 일이 되는 것보다 낫다.

    **`max_tokens` 초과는 다르다.** 그건 `claude.py` 가 소리 나게 실패시킨다 — 잘린
    JSON 은 어디가 잘렸는지 알 수 없어 「비었다」와 구분되지 않는다.
    """

    # 명세 17.1 `supportType` 다섯 값. CHECK 제약·백엔드 필터와 같은 집합이다.
    _SUPPORT_TYPES = {"FUNDING", "SUBSIDY", "VOUCHER", "CONSULTING", "OTHER"}

    def __init__(self, settings: Settings, claude: ClaudeClient) -> None:
        self._settings = settings
        self._claude = claude

    async def structure(
        self, request: ProgramStructuringRequest
    ) -> tuple[ProgramStructuringResult, ModelMeta]:
        user_text = prompts.structuring_user_text(
            raw_text=request.raw_text, source_url=request.source_url
        )
        try:
            response: Any = await self._claude.parse(
                model=self._settings.structuring_model,
                system=prompts.STRUCTURING_SYSTEM,
                messages=[{"role": "user", "content": user_text}],
                output_format=_LlmProgram,
                max_tokens=self._settings.structuring_max_tokens,
                effort=self._settings.structuring_effort,
            )
        except ClaudeError as exc:
            raise ClassificationError(exc.stage, str(exc), retryable=exc.retryable) from exc

        out: _LlmProgram = response.parsed_output
        unsure = list(out.eligibility.uncertainConditions)

        support_type = out.supportType.strip().upper()
        if support_type not in self._SUPPORT_TYPES:
            # 억지로 고르지 말라고 일러 뒀지만 그래도 벗어나면 「분류하지 않았다」로 둔다.
            # 여기서 던지면 멀쩡한 자격 조건까지 통째로 잃는다.
            log.warning("구조화 supportType 이 계약 밖이라 OTHER 로 둔다: %r", out.supportType)
            support_type = "OTHER"

        interest_rate = self._narrow(
            out.interestRate,
            lambda v: Decimal(0) <= v <= Decimal(100),
            unsure,
            "금리 «{}» — 연 % 로 읽을 수 없어 비웠습니다",
        )
        disbursement_days = self._narrow(
            out.expectedDisbursementDays,
            lambda v: 0 <= v <= 730,
            unsure,
            "지급 소요일 «{}» — 일수로 읽을 수 없어 비웠습니다",
        )
        apply_start = self._as_date(out.applyStart, unsure)
        apply_end = self._as_date(out.applyEnd, unsure)
        if apply_start and apply_end and apply_start > apply_end:
            # 뒤집힌 기간은 DB 제약(ck_sp_period)에 걸려 적재가 통째로 실패한다.
            unsure.append(f"신청 기간 «{apply_start} ~ {apply_end}» — 앞뒤가 뒤집혀 비웠습니다")
            apply_start = apply_end = None

        try:
            result = ProgramStructuringResult(
                eligibility=Eligibility(
                    regions=out.eligibility.regions,
                    industries=out.eligibility.industries,
                    max_annual_revenue=out.eligibility.maxAnnualRevenue,
                    min_years_in_business=out.eligibility.minYearsInBusiness,
                    max_years_in_business=out.eligibility.maxYearsInBusiness,
                    excludes=out.eligibility.excludes,
                    uncertain_conditions=unsure,
                ),
                support_type=support_type,
                max_amount=out.maxAmount,
                apply_start=apply_start,
                apply_end=apply_end,
                required_documents=[
                    {"documentName": d.documentName, "issuer": d.issuer, "method": d.method}
                    for d in out.requiredDocuments
                ],
                interest_rate=interest_rate,
                expected_disbursement_days=disbursement_days,
            )
        except ValidationError as exc:
            # 여기까지 좁히고도 걸리는 것은 음수 금액·업력처럼 계약 자체를 벗어난 값이다.
            raise ClassificationError(
                "model", f"구조화 출력이 계약을 벗어났습니다: {exc.errors(include_url=False)}"
            ) from exc

        meta = ModelMeta(
            model_name=response.model,
            model_version=None,
            prompt_version=prompts.STRUCTURING_PROMPT_VERSION,
            pipeline="LLM",
        )
        return result, meta

    @staticmethod
    def _narrow(value: Any, ok: Any, unsure: list[str], note: str) -> Any:
        """범위를 벗어난 값을 버리되 **버렸다는 사실을 남긴다.**"""
        if value is None or ok(value):
            return value
        unsure.append(note.format(value))
        return None

    @staticmethod
    def _as_date(value: str | None, unsure: list[str]) -> DateOnly | None:
        """`YYYY-MM-DD` 만 받는다.

        읽히지 않으면 비운다. **출처(기업마당)가 준 기간이 정본이고 백엔드가 이 값으로
        덮지 않으므로**(`SupportProgram.applyStructuring`) 여기서 날짜 하나를 잃는 손해가
        작다. 반대로 「예산 소진시까지」를 억지로 날짜로 만들면 마감이 지난 공고가
        열려 있는 것처럼 보인다.
        """
        if value is None or not value.strip():
            return None
        try:
            return DateOnly.fromisoformat(value.strip())
        except ValueError:
            unsure.append(f"신청 기간 «{value}» — 날짜로 읽을 수 없어 비웠습니다")
            return None


def build_structurer(settings: Settings, claude: ClaudeClient) -> StructuringPipeline:
    """`stub` 이면 고정 응답, `live` 면 Claude.

    분류·브리핑과 같은 모양이 됐다. 전에는 `live` 에서도 stub 을 돌려줬고 그것이
    조용한 거짓이 아니었던 이유는 응답의 `pipeline` 이 `STUB` 이었기 때문인데
    (명세 20.2), 이제 그 표시가 실제 갈림길을 가리킨다.
    """
    if settings.ai_pipeline_mode == "stub":
        return StubStructurer()
    return ClaudeStructurer(settings, claude)


# ================================================================ 20.10 초안 점검


class AssistPipeline(Protocol):
    async def review(
        self, request: DocumentAssistRequest
    ) -> tuple[DocumentAssistResult, ModelMeta]: ...


class StubAssistant:
    """고정 응답. **빈 `notes` 를 돌려준다.**

    점검 문장을 지어내면 사장님이 세무 판단으로 읽는다. 20.10 이 `ERROR` 를 뺀 것과
    같은 이유로, stub 도 할 말이 없을 때는 아무 말도 하지 않는다. 빈 목록은 계약상
    정상이라(`DocumentAssistResult` 머리말) 백엔드가 `reviewNote` 없이 초안을 그대로
    내보낸다 — 20.10 이 정한 실패 경로와 같은 모양이다.
    """

    async def review(
        self, request: DocumentAssistRequest
    ) -> tuple[DocumentAssistResult, ModelMeta]:
        del request
        return DocumentAssistResult(), _STUB_META


class _LlmNote(_Strict):
    field_key: str
    severity: Severity
    message: str


class _LlmNotes(_Strict):
    notes: list[_LlmNote]


class ClaudeAssistant:
    """Claude 점검 문장 생성. **근거 검증은 여기서 하지 않는다** — 호출부가 한다.

    stub 과 LLM 양쪽에 똑같이 걸려야 하고, 폐기 결과가 "빈 목록으로 200 을 낸다" 는
    라우트 결정으로 이어지기 때문이다. 브리핑·분류와 같은 배치다.

    ## 스스로 시계를 본다

    명세 20.10 이 동기 타임아웃 10초다. 백엔드가 먼저 끊어도 워커는 모델 호출을 계속해
    **받을 사람이 없는 응답에 토큰을 태운다.** `asyncio.wait_for` 로 같은 상한을 이쪽에도
    건다. 초과는 재시도 가능 실패로 본다 — 모델이 느린 것은 다음번에 나을 수 있고,
    연속되면 차단기가 알아서 연다.
    """

    def __init__(self, settings: Settings, claude: ClaudeClient) -> None:
        self._settings = settings
        self._claude = claude

    async def review(
        self, request: DocumentAssistRequest
    ) -> tuple[DocumentAssistResult, ModelMeta]:
        user_text = prompts.assist_user_text(
            form_type=request.form_type,
            industry_type=request.business.industry_type,
            fields=request.fields,
            pending=request.pending,
            tone_guide=request.tone_guide,
        )
        try:
            response: Any = await asyncio.wait_for(
                self._claude.parse(
                    model=self._settings.assist_model,
                    system=prompts.ASSIST_SYSTEM,
                    messages=[{"role": "user", "content": user_text}],
                    output_format=_LlmNotes,
                    max_tokens=self._settings.assist_max_tokens,
                    effort=self._settings.assist_effort,
                ),
                timeout=self._settings.assist_timeout_seconds,
            )
        except TimeoutError as exc:
            raise ClassificationError(
                "timeout",
                f"초안 점검이 {self._settings.assist_timeout_seconds:.0f}초를 넘겼습니다.",
                retryable=True,
            ) from exc
        except ClaudeError as exc:
            raise ClassificationError(exc.stage, str(exc), retryable=exc.retryable) from exc

        out: _LlmNotes = response.parsed_output
        try:
            result = DocumentAssistResult(
                notes=[
                    ReviewNote(field_key=n.field_key, severity=n.severity, message=n.message)
                    for n in out.notes
                ]
            )
        except ValidationError as exc:
            # 계약 범위를 벗어난 출력(빈 문장·상한 초과·모르는 severity).
            raise ClassificationError(
                "model", f"초안 점검 출력이 계약을 벗어났습니다: {exc.errors(include_url=False)}"
            ) from exc

        meta = ModelMeta(
            model_name=response.model,
            model_version=None,
            prompt_version=prompts.ASSIST_PROMPT_VERSION,
            pipeline="LLM",
        )
        return result, meta


def build_assistant(settings: Settings, claude: ClaudeClient) -> AssistPipeline:
    if settings.ai_pipeline_mode == "stub":
        return StubAssistant()
    return ClaudeAssistant(settings, claude)
