"""1번 NLP 파트 엔드포인트 — 명세 20.2 / 20.3 / 20.6 / 20.7 / 20.10.

| Method | Endpoint                                      | 처리                 |
| POST   | /internal/v1/ai/transaction-classification    | 비동기 → 콜백 (20.3) |
| POST   | /internal/v1/ai/program-structuring           | 비동기 → 콜백 (20.6) |
| POST   | /internal/v1/ai/briefing-generation           | 비동기 → 콜백 (20.7) |
| POST   | /internal/v1/ai/document-assist               | **동기** (20.10)     |

`document-assist` 하나만 동기다. `jobId` 가 없고 콜백 대신 응답으로 결과를 돌려주며,
실패해도 200 을 낸다 — 이유는 그 라우트 머리말에 있다.

비동기는 202 + {jobId} 를 먼저 돌려주고 백그라운드에서 분류 → 검증 → 콜백을 보낸다.
배치 상한(`BATCH_MAX`) 초과·허용 카테고리 누락 같은 요청 오류는 Pydantic 이 202 전에
400 으로 끝낸다 — Job 이 PROCESSING 에 남지 않게.

Circuit Breaker 는 **202 를 막지 않는다.** 접수는 그대로 받고 백그라운드에서 판단한다 —
요청 시점에 막으면 백엔드가 이미 만든 Job 이 갈 곳을 잃는다. 어느 구간을 지키는
차단기인지와 무엇을 실패로 세는지는 `app/common/breaker.py` 머리말에 있다.
"""

from __future__ import annotations

import logging
from typing import Any

from fastapi import APIRouter, BackgroundTasks, Depends, Request
from starlette.datastructures import State

from app.common import callback as cb
from app.common.auth import require_service_token
from app.common.breaker import CircuitBreaker
from app.common.envelope import Envelope, ok
from app.common.errors import AppError, ErrorCode
from app.common.model_meta import ModelMeta
from app.common.request_id import require_request_id
from app.nlp import assist, briefing
from app.nlp.pipeline import (
    AssistPipeline,
    BriefingPipeline,
    ClassificationError,
    ClassificationPipeline,
    StructuringPipeline,
)
from app.nlp.schemas import (
    BATCH_MAX,
    AcceptedJob,
    BriefingCallbackData,
    BriefingGenerationRequest,
    BriefingGenerationResult,
    ClassificationCallbackData,
    ClassificationResult,
    DocumentAssistRequest,
    DocumentAssistResult,
    ProgramStructuringCallbackData,
    ProgramStructuringRequest,
    TransactionClassificationRequest,
    TransactionClassificationResult,
    invalid_results,
    missing_transaction_ids,
)

log = logging.getLogger(__name__)

# 차단기 키. 기능 단위로 나눠야 "해당 AI 기능만 끄고 나머지는 살린다"(§8.7 규칙 6)가
# 성립한다. 20.6·20.7·20.10 이 붙을 때 각자 자기 이름으로 꺼내 쓴다.
FEATURE = "transaction-classification"
BRIEFING_FEATURE = "briefing-generation"
STRUCTURING_FEATURE = "program-structuring"
ASSIST_FEATURE = "document-assist"

router = APIRouter(
    prefix="/internal/v1/ai",
    tags=["nlp"],
    dependencies=[Depends(require_service_token), Depends(require_request_id)],
)


# ------------------------------------------------------------------ 콜백 문서 (명세 20.2)

# 비동기 셋의 202 응답은 `{jobId}` 뿐이라 **결과 모양이 OpenAPI 에 안 실린다.** 백엔드는
# 이 문서를 보고 DTO 를 만드는데 정작 자기가 받을 본문이 거기 없다 — 그래서 2026-09-11 에
# 백엔드가 `ai/app/nlp/schemas.py` 를 직접 읽어 DTO 를 만들었다.
#
# FastAPI 의 `callbacks` 는 "이 경로를 부르면 우리가 저 모양을 너희에게 POST 한다" 를
# 선언하는 기능이다. 여기 라우트들은 **워커가 제공하는 엔드포인트가 아니다** — 문서에만
# 실리고 실행되지 않는다.
#
# 경로의 `{$request.body#/jobId}` 는 OpenAPI 런타임 표현식이다. 요청 본문의 `jobId` 가
# 콜백 URL 에 들어간다는 뜻이고, 실제 주소는 `{backendBaseUrl}` 뒤에 붙는다.

_CALLBACK_PATH = "/internal/v1/ai-jobs/{$request.body#/jobId}/result"

# 셋을 팩터리로 묶지 않는다. 이 파일이 `from __future__ import annotations` 를 쓰고 있어
# 애노테이션이 문자열로 굳는데, 동적으로 만든 함수의 `body: annotation` 은 그 이름 그대로
# 남아 FastAPI 가 타입을 못 찾는다. 실제로 걸렸고, 세 줄 중복이 그 대가보다 싸다.

classification_callback = APIRouter()
structuring_callback = APIRouter()
briefing_callback = APIRouter()


@classification_callback.post(_CALLBACK_PATH, name="transactionClassificationResult")
async def _classification_result(body: Envelope[ClassificationCallbackData]) -> None:
    """워커 → API 서버. `X-Request-Id` 는 `ai-job-{jobId}-result` 로 고정된다."""


@structuring_callback.post(_CALLBACK_PATH, name="programStructuringResult")
async def _structuring_result(body: Envelope[ProgramStructuringCallbackData]) -> None:
    """워커 → API 서버."""


@briefing_callback.post(_CALLBACK_PATH, name="briefingGenerationResult")
async def _briefing_result(body: Envelope[BriefingCallbackData]) -> None:
    """워커 → API 서버. 일부만 살아남으면 `status` 가 `PARTIAL` 이다."""


async def _deliver(state: State, job_id: int, body: dict[str, Any]) -> None:
    try:
        await state.callback.send(job_id, body)
    except cb.CallbackError as exc:
        # 여기서 끝난 Job 은 API 서버의 JobSweeper 가 10분 뒤 회수한다 (공통규약 23장)
        log.error("콜백 전송 실패 jobId=%s: %s", job_id, exc)


def _failed(code: ErrorCode | str, message: str, detail: dict[str, Any]) -> dict[str, Any]:
    return cb.failed(str(code), message, detail)


# ------------------------------------------------------------------ 20.3 거래 분류


@router.post(
    "/transaction-classification",
    status_code=202,
    response_model=Envelope[AcceptedJob],
    callbacks=classification_callback.routes,
)
async def transaction_classification(
    payload: TransactionClassificationRequest, request: Request, background: BackgroundTasks
) -> dict[str, Any]:
    classifier: ClassificationPipeline = request.app.state.classifier
    background.add_task(run_classification_job, request.app.state, classifier, payload)
    return ok(AcceptedJob(job_id=payload.job_id))


async def _classify_in_chunks(
    classifier: ClassificationPipeline,
    payload: TransactionClassificationRequest,
    breaker: CircuitBreaker,
) -> tuple[list[ClassificationResult], ModelMeta | None, int, str | None]:
    """요청을 `BATCH_MAX` 씩 잘라 **순차로** 부른다. 콜백은 호출부가 **한 번만** 보낸다.

    ## 왜 워커가 쪼개나

    백엔드의 Job 기계가 `1 Job ↔ 1 콜백` 을 강제한다 — `AiResultApplier` 가 이미 종단
    상태인 Job 의 콜백을 버린다. 백엔드가 400건 Job 하나를 20건씩 20번 POST 하면
    **첫 콜백만 반영되고 380건이 버려지는데 LLM 비용은 20번 다 나간다.**
    쪼개는 일이 요청 안에서 끝나야 그 사고가 안 난다 (2026-09-11 백엔드 회신).

    ## 왜 순차인가

    동시에 던지면 "20청크 × 15초 = 300초" 산수가 깨지는 게 아니라 **반대로 무너진다** —
    LLM 경로에는 동시성 제한이 없고(`compute_semaphore` 는 예측 전용), 한꺼번에 나가면
    429 를 받는다. 429 는 재시도 가능이라 차단기가 5연속에 열리고 남은 청크가 전부 죽는다.

    ## 실패한 청크에서 멈추지 않는다

    한 청크가 죽었다고 나머지를 버리면 일시적 429 하나로 380건을 잃는다. 계속 돌되
    **차단기가 열리면 그때 멈춘다** — 5연속 실패라는 경계는 차단기가 이미 들고 있다.
    피해 상한은 차단기가 정하고, 이 반복문은 살릴 수 있는 것을 최대한 살린다.

    Returns:
        (모은 결과, 마지막 성공 청크의 ModelMeta 또는 None, 실패·건너뛴 청크 수,
         마지막 실패의 stage 또는 None)
    """
    chunks = [
        payload.transactions[i : i + BATCH_MAX]
        for i in range(0, len(payload.transactions), BATCH_MAX)
    ]
    results: list[ClassificationResult] = []
    meta: ModelMeta | None = None
    failed = 0
    last_stage: str | None = None

    for index, chunk in enumerate(chunks, start=1):
        # **청크마다 다시 묻는다.** 앞 청크들이 차단기를 열었을 수 있다.
        if not breaker.allow():
            skipped = len(chunks) - index + 1
            log.warning(
                "차단기 open — 남은 청크 %d개 건너뜀 jobId=%s %.0f초 남음",
                skipped,
                payload.job_id,
                breaker.retry_after,
            )
            failed += skipped
            last_stage = "quota"
            break

        sub = payload.model_copy(update={"transactions": chunk})
        try:
            chunk_results, meta = await classifier.classify(sub)
        except ClassificationError as exc:
            # 재시도 가능한 실패만 센다. 400·404·401 을 세면 내 요청이 잘못됐을 때
            # 60초 동안 기능이 꺼지고, 60초 뒤에 같은 400 이 또 난다 (§8.6).
            if exc.retryable:
                breaker.record_failure()
            failed += 1
            last_stage = exc.stage
            log.warning(
                "청크 %d/%d 실패 jobId=%s stage=%s retryable=%s: %s",
                index,
                len(chunks),
                payload.job_id,
                exc.stage,
                exc.retryable,
                exc,
            )
            continue

        breaker.record_success()
        results.extend(chunk_results)

    return results, meta, failed, last_stage


async def run_classification_job(
    state: State,
    classifier: ClassificationPipeline,
    payload: TransactionClassificationRequest,
) -> None:
    detail = {"jobId": payload.job_id, "transactionCount": len(payload.transactions)}
    breaker = state.breakers.get(FEATURE)

    # 차단기가 열려 있으면 모델을 부르지 않고 바로 실패를 돌려준다 (기술스택 §8.7 규칙 6).
    #
    # **콜백은 반드시 보낸다.** 여기서 조용히 return 하면 Job 이 PROCESSING 에 남아
    # JobSweeper 가 10분을 기다린 뒤에야 회수한다(공통규약 23장) — 차단기를 달아서
    # 사용자 대기가 오히려 길어지는 셈이다.
    #
    # 빈 결과로 `completed` 를 보내지 않는 이유: 그건 "분류했는데 아무것도 안 나왔다" 는
    # 말이라 백엔드가 「AI 가 죽었다」와 「AI 가 전건을 못 정했다」를 구분할 수 없다.
    # 20.11 ③ 이 "Circuit Breaker open 시 API 서버는 베이스라인으로 산출한다" 고 정한 것도
    # 워커가 실패를 **분명히 말한다**는 전제다.
    if not breaker.allow():
        log.warning(
            "차단기 open — 모델 호출 없이 실패 응답 jobId=%s %.0f초 남음",
            payload.job_id,
            breaker.retry_after,
        )
        body = _failed(
            ErrorCode.AI_ANALYSIS_FAILED,
            "거래 분류를 일시적으로 사용할 수 없습니다.",
            {**detail, "circuit": "open", "retryAfterSeconds": breaker.retry_after},
        )
        await _deliver(state, payload.job_id, body)
        return

    try:
        results, meta, failed_chunks, stage = await _classify_in_chunks(
            classifier, payload, breaker
        )

        if meta is None:
            # 한 청크도 성공하지 못했다. 빈 결과로 completed 를 보내면 백엔드가
            # 「AI 가 죽었다」와 「전건을 못 정했다」를 구분할 수 없다.
            body = _failed(
                ErrorCode.AI_ANALYSIS_FAILED,
                "거래 분류에 실패했습니다.",
                {**detail, "failedChunks": failed_chunks, "stage": stage},
            )
            await _deliver(state, payload.job_id, body)
            return

        # 명세 20.1 — "허용 카테고리 외 값, 근거 없는 수치는 폐기하고 **해당 항목만** 제외한다".
        # stub 과 LLM 에 똑같이 적용한다. stub 이 규격을 어겨도 여기서 걸린다.
        discarded = invalid_results(payload, results)
        if discarded:
            log.warning(
                "분류 결과 %d건 폐기 jobId=%s: %s", len(discarded), payload.job_id, discarded
            )
            results = [r for r in results if r.transaction_id not in discarded]

        # 빠진 건은 규칙·거래처 사전 결과를 유지하고 needsConfirmation 으로 넘긴다 (§6.3 폴백).
        # 전건이 빠져도 실패가 아니다 — 배치 일부만 살려 돌려주는 것이 계약이다.
        missing = missing_transaction_ids(payload, results)
        if missing:
            log.info("분류 결과 미수신 %d건 jobId=%s: %s", len(missing), payload.job_id, missing)

        # 청크가 하나라도 실패했으면 PARTIAL 이다. 남은 거래는 UNCLASSIFIED 로 남고
        # 명세 8.5 재실행이 주워간다 — **재시도 단위가 Job 이 아니라 거래 상태**다.
        status: cb.ResultStatus = "PARTIAL" if failed_chunks else "COMPLETED"
        if failed_chunks:
            detail["failedChunks"] = failed_chunks
        result = TransactionClassificationResult(results=results)
        body = cb.completed(meta, result.model_dump(by_alias=True, mode="json"), status=status)
    except AppError as exc:
        body = _failed(exc.code, exc.message, detail)
    except Exception:
        log.exception("거래 분류 중 예기치 않은 오류 jobId=%s", payload.job_id)
        body = _failed(ErrorCode.AI_ANALYSIS_FAILED, "거래 분류에 실패했습니다.", detail)
    await _deliver(state, payload.job_id, body)


# ------------------------------------------------------------------ 20.7 브리핑 생성

# 전부 폐기됐을 때 쓰는 제목. 숫자도 판단도 없는 라벨이라 새로 지어내는 것이 아니다.
_NEUTRAL_HEADLINE = {"WEEKLY": "이번 주 자금 요약", "MONTHLY": "이번 달 자금 요약"}


@router.post(
    "/briefing-generation",
    status_code=202,
    response_model=Envelope[AcceptedJob],
    callbacks=briefing_callback.routes,
)
async def briefing_generation(
    payload: BriefingGenerationRequest, request: Request, background: BackgroundTasks
) -> dict[str, Any]:
    briefer: BriefingPipeline = request.app.state.briefer
    background.add_task(run_briefing_job, request.app.state, briefer, payload)
    return ok(AcceptedJob(job_id=payload.job_id))


async def run_briefing_job(
    state: State,
    briefer: BriefingPipeline,
    payload: BriefingGenerationRequest,
) -> None:
    """생성 → **환각 숫자 검증** → 콜백.

    검증을 파이프라인이 아니라 여기서 하는 이유는 분류와 같다 — stub 과 LLM 양쪽에 똑같이
    걸려야 하고(stub 이 규격을 어기는 것도 잡는다), 폐기 결과가 `COMPLETED`/`PARTIAL` 이라는
    **콜백 결정**으로 이어지기 때문이다.
    """
    detail = {"jobId": payload.job_id, "briefingId": payload.briefing_id}
    breaker = state.breakers.get(BRIEFING_FEATURE)

    if not breaker.allow():
        log.warning("차단기 open — 브리핑 생성 생략 jobId=%s", payload.job_id)
        body = _failed(
            ErrorCode.AI_ANALYSIS_FAILED,
            "브리핑 생성을 일시적으로 사용할 수 없습니다.",
            {**detail, "circuit": "open", "retryAfterSeconds": breaker.retry_after},
        )
        await _deliver(state, payload.job_id, body)
        return

    try:
        result, meta = await briefer.write(payload)
        breaker.record_success()

        verdict = briefing.verify(payload, result.content)
        headline_ok = not briefing.verify(payload, result.headline).discarded

        if verdict.discarded:
            # 숫자가 틀린 문장은 고치지 않고 버린다. 사람이 검수하지 않는 경로라
            # "아마 맞겠지" 로 넘기면 사장님이 그 숫자로 판단한다 (`briefing.py` 머리말).
            log.warning(
                "브리핑 문장 %d건 폐기 jobId=%s: %s",
                len(verdict.discarded),
                payload.job_id,
                [reason for _, reason in verdict.discarded],
            )

        if not verdict.kept:
            # 남은 문장이 없다. ERD 의 `REPORT.narrative`(생성 실패 시 null, 지표만 제공)
            # 경로로 보낸다 — 지표 카드는 그대로 노출된다.
            body = _failed(
                ErrorCode.AI_ANALYSIS_FAILED,
                "브리핑 문장이 모두 검증에 걸렸습니다.",
                {**detail, "discardedSentences": len(verdict.discarded)},
            )
            await _deliver(state, payload.job_id, body)
            return

        headline = result.headline if headline_ok else _NEUTRAL_HEADLINE[payload.period_type]
        if not headline_ok:
            log.warning("브리핑 headline 폐기 jobId=%s", payload.job_id)

        checked = BriefingGenerationResult(headline=headline, content=verdict.content)
        status: cb.ResultStatus = "PARTIAL" if verdict.discarded or not headline_ok else "COMPLETED"
        body = cb.completed(meta, checked.model_dump(by_alias=True, mode="json"), status=status)
    except ClassificationError as exc:
        if exc.retryable:
            breaker.record_failure()
        log.warning("브리핑 생성 실패 jobId=%s stage=%s: %s", payload.job_id, exc.stage, exc)
        body = _failed(
            ErrorCode.AI_ANALYSIS_FAILED,
            "브리핑 생성에 실패했습니다.",
            {**detail, "stage": exc.stage},
        )
    except AppError as exc:
        body = _failed(exc.code, exc.message, detail)
    except Exception:
        log.exception("브리핑 생성 중 예기치 않은 오류 jobId=%s", payload.job_id)
        body = _failed(ErrorCode.AI_ANALYSIS_FAILED, "브리핑 생성에 실패했습니다.", detail)
    await _deliver(state, payload.job_id, body)


# ------------------------------------------------------------------ 20.6 공고 구조화


@router.post(
    "/program-structuring",
    status_code=202,
    response_model=Envelope[AcceptedJob],
    callbacks=structuring_callback.routes,
)
async def program_structuring(
    payload: ProgramStructuringRequest, request: Request, background: BackgroundTasks
) -> dict[str, Any]:
    structurer: StructuringPipeline = request.app.state.structurer
    background.add_task(run_structuring_job, request.app.state, structurer, payload)
    return ok(AcceptedJob(job_id=payload.job_id))


async def run_structuring_job(
    state: State,
    structurer: StructuringPipeline,
    payload: ProgramStructuringRequest,
) -> None:
    """구조화 → 콜백.

    분류·브리핑과 달리 **결과를 걸러내는 단계가 없다.** 두 기능은 폐기 기준이 입력에
    있었지만(허용 카테고리 / 입력 지표의 숫자) 공고는 원문이 자유 문장이라 "이 조건이
    원문에 있었나" 를 문자열로 대조할 수 없다. 근거 없는 조건을 막는 장치는 검증기가
    아니라 **스키마와 프롬프트**다 — 확신할 수 없으면 필드를 비우고
    `uncertainConditions` 에 남기게 되어 있다(`Eligibility` 머리말).

    그래서 이 경로에는 `PARTIAL` 이 없다. 살릴 것과 버릴 것을 가르는 기준이 없으면
    부분 성공이라는 상태도 성립하지 않는다.
    """
    detail = {"jobId": payload.job_id, "programId": payload.program_id}
    breaker = state.breakers.get(STRUCTURING_FEATURE)

    if not breaker.allow():
        log.warning("차단기 open — 공고 구조화 생략 jobId=%s", payload.job_id)
        body = _failed(
            ErrorCode.AI_ANALYSIS_FAILED,
            "공고 구조화를 일시적으로 사용할 수 없습니다.",
            {**detail, "circuit": "open", "retryAfterSeconds": breaker.retry_after},
        )
        await _deliver(state, payload.job_id, body)
        return

    try:
        result, meta = await structurer.structure(payload)
        breaker.record_success()
        body = cb.completed(meta, result.model_dump(by_alias=True, mode="json"))
    except ClassificationError as exc:
        if exc.retryable:
            breaker.record_failure()
        log.warning("공고 구조화 실패 jobId=%s stage=%s: %s", payload.job_id, exc.stage, exc)
        body = _failed(
            ErrorCode.AI_ANALYSIS_FAILED,
            "공고 구조화에 실패했습니다.",
            {**detail, "stage": exc.stage},
        )
    except AppError as exc:
        body = _failed(exc.code, exc.message, detail)
    except Exception:
        log.exception("공고 구조화 중 예기치 않은 오류 jobId=%s", payload.job_id)
        body = _failed(ErrorCode.AI_ANALYSIS_FAILED, "공고 구조화에 실패했습니다.", detail)
    await _deliver(state, payload.job_id, body)


# ------------------------------------------------------------------ 20.10 초안 점검


@router.post("/document-assist", response_model=Envelope[DocumentAssistResult])
async def document_assist(payload: DocumentAssistRequest, request: Request) -> dict[str, Any]:
    """**넷 중 유일한 동기 경로다** (명세 20.2). `jobId` 도 콜백도 없다.

    ## 실패해도 200 을 돌려준다

    20.10 이 *"워커 실패 또는 Circuit Breaker open 시 초안은 `reviewNote` 없이 정상
    반환된다. **초안 생성은 이 호출에 의존하지 않는다**"* 라고 적었다. P0 경로에
    AI 실패가 번지면 안 된다는 뜻이다.

    그래서 차단기가 열렸거나 모델이 죽어도 **빈 `notes`** 로 200 을 낸다. 5xx 를 내면
    백엔드가 예외 처리를 한 겹 더 짜야 하고, 그 처리가 빠진 날 초안 화면이 통째로
    죽는다. 빈 목록은 계약상 정상이라(`DocumentAssistResult` 머리말) 백엔드는 받은
    것을 그대로 쓰면 된다.

    **분류·브리핑과 반대 선택이다.** 그쪽은 실패를 분명히 말해야 백엔드가 Job 을
    `FAILED` 로 접는데, 여기는 말할 Job 자체가 없다.
    """
    assistant: AssistPipeline = request.app.state.assistant
    breaker = request.app.state.breakers.get(ASSIST_FEATURE)
    detail = {"draftId": payload.draft_id, "fieldCount": len(payload.fields)}

    if not breaker.allow():
        log.warning("차단기 open — 초안 점검 생략 draftId=%s", payload.draft_id)
        return ok(DocumentAssistResult())

    try:
        result, _meta = await assistant.review(payload)
    except ClassificationError as exc:
        if exc.retryable:
            breaker.record_failure()
        log.warning("초안 점검 실패 draftId=%s stage=%s: %s", payload.draft_id, exc.stage, exc)
        return ok(DocumentAssistResult())
    except Exception:
        log.exception("초안 점검 중 예기치 않은 오류 %s", detail)
        return ok(DocumentAssistResult())

    breaker.record_success()

    # 명세 20.10 — "응답 문장은 요청 본문에 있는 수치만 인용할 수 있다".
    # stub 과 LLM 에 똑같이 적용한다. 검증을 파이프라인이 아니라 여기 두는 이유는
    # 분류·브리핑과 같다.
    verdict = assist.verify(payload, result.notes)
    if verdict.discarded:
        log.warning(
            "초안 점검 문장 %d건 폐기 draftId=%s: %s",
            len(verdict.discarded),
            payload.draft_id,
            [reason for _, reason in verdict.discarded],
        )

    # **전부 폐기돼도 200 이다.** 빈 목록은 계약상 정상이고, 여기서 500 을 내면
    # 초안 화면이 AI 실패에 물린다 — 이 라우트가 막으려는 바로 그 상황이다.
    return ok(DocumentAssistResult(notes=verdict.kept))
