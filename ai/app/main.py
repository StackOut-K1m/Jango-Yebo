"""FastAPI 앱 조립. uvicorn 진입점은 `app.main:app`."""

import asyncio
import logging
from collections.abc import AsyncIterator
from contextlib import asynccontextmanager

import anyio
from fastapi import FastAPI

from app.common.breaker import BreakerRegistry
from app.common.callback import CallbackClient
from app.common.claude import ClaudeClient
from app.common.errors import register_error_handlers
from app.common.logging import configure_logging
from app.config import Settings, get_settings
from app.health import router as health_router
from app.nlp.pipeline import build_assistant, build_briefer, build_classifier, build_structurer
from app.nlp.router import router as nlp_router
from app.ocr.pipelines.select import build_pipelines
from app.ocr.router import router as ocr_router

log = logging.getLogger(__name__)


def create_app(settings: Settings | None = None) -> FastAPI:
    settings = settings or get_settings()
    configure_logging()

    @asynccontextmanager
    async def lifespan(app: FastAPI) -> AsyncIterator[None]:
        # 예측 모델은 **기동 시 한 번 올려 상주**시킨다 — 요청마다 로드하면 첫 응답이 수십 초다
        # (명세 20.1 ④). off 면 torch 를 import 하지도 않는다.
        if settings.forecast_mode == "live":
            from app.forecast import service as forecast_service

            try:
                await anyio.to_thread.run_sync(forecast_service.load, settings.forecast_threads)
            except Exception:
                # **여기서 예외를 올리면 워커가 기동하지 못하고 1번(NLP)·2번(OCR)까지 같이 죽는다.**
                # 가중치는 이미지에 굽지 않고 볼륨으로 붙이므로(S15P21C105-76) 마운트가 빠지거나
                # 파일 권한이 틀리면 실제로 여기서 실패한다. 3번 파트의 파일 하나 때문에
                # 남의 기능이 내려가서는 안 된다.
                #
                # 예측만 「모델 미상주」로 떨어진다 — 라우터가 `service.is_loaded()` 로 이미
                # 구분해서 AI_ANALYSIS_FAILED 를 돌려준다 (router.py).
                log.exception("예측 모델 상주 실패 — 예측만 중단하고 워커는 계속 뜬다")
        yield
        if settings.forecast_mode == "live":
            from app.forecast import service as forecast_service

            forecast_service.unload()
        await app.state.callback.aclose()

    is_local = settings.app_env == "local"
    app = FastAPI(
        title="Jango-Yebo AI Worker",
        version="0.1.0",
        docs_url="/docs" if is_local else None,
        redoc_url=None,
        openapi_url="/openapi.json" if is_local else None,
        lifespan=lifespan,
    )
    app.state.settings = settings
    app.state.callback = CallbackClient(settings)
    # Claude 클라이언트는 하나만 — 1번(NLP)·2번(OCR) 라우트가 app.state.claude 를 함께 쓴다
    app.state.claude = ClaudeClient(settings)
    # 세마포어를 파이프라인보다 **먼저** 만든다 — 로컬 VLM 이 생성자에서 받아야
    # 예측 워커와 같은 것을 보게 된다(명세 20.1 ④).
    app.state.compute_semaphore = asyncio.Semaphore(settings.compute_concurrency)
    app.state.pipelines = build_pipelines(settings, app.state.claude, app.state.compute_semaphore)
    app.state.classifier = build_classifier(settings, app.state.claude)
    app.state.briefer = build_briefer(settings, app.state.claude)
    app.state.structurer = build_structurer(settings, app.state.claude)
    app.state.assistant = build_assistant(settings, app.state.claude)
    # 기능 이름별 차단기 (기술스택 §8.7 규칙 6 — "해당 AI 기능만 끄고 나머지는 살린다").
    # `app.state.claude` 는 1번·2번이 공유하므로 차단기를 그 안에 넣으면 분류 실패가
    # 증빙 추출까지 멈춘다. 1번 라우트 넷이 각자 자기 이름으로 꺼내 쓴다 — `breaker.py` 참고.
    app.state.breakers = BreakerRegistry(
        threshold=settings.circuit_failure_threshold,
        reset_seconds=settings.circuit_open_seconds,
    )

    register_error_handlers(app)
    app.include_router(health_router)
    app.include_router(nlp_router)
    app.include_router(ocr_router)
    if settings.forecast_mode == "live":
        # import 를 분기 안에 둔다 — off 인 환경에는 torch 가 없다
        from app.forecast.router import router as forecast_router

        app.include_router(forecast_router)
    return app


app = create_app()
