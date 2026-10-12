"""헬스체크 — 인증 없음. compose healthcheck 와 로컬 확인용."""

import os

from fastapi import APIRouter, Request

from app.common.envelope import ok
from app.config import Settings

router = APIRouter(tags=["health"])


def _has_anthropic_credential(settings: Settings) -> bool:
    """`ClaudeClient.client_kwargs()` 와 **같은 해석 순서**다.

    설정값이 있으면 SDK 에 넘기고, 없으면 SDK 가 환경 변수에서 직접 읽는다.
    게이트웨이(GMS 등)를 거칠 때는 `api_key` 없이 `auth_token` 만 있을 수 있으므로 둘 다 본다.
    한쪽만 보면 게이트웨이 구성에서 멀쩡한 워커를 DEGRADED 로 잘못 찍는다.
    """
    if settings.anthropic_api_key is not None or settings.anthropic_auth_token is not None:
        return True
    return bool(os.environ.get("ANTHROPIC_API_KEY") or os.environ.get("ANTHROPIC_AUTH_TOKEN"))


@router.get("/health")
def health(request: Request) -> dict:
    """「떴는가」가 아니라 **「실제로 일할 수 있는가」** 를 돌려준다.

    이 워커는 부분 실패를 부분으로 남기는 쪽을 택했다 — 가중치가 없어도 NLP·OCR 은 돌고,
    자격 증명이 없어도 예측은 돈다(`main.py` lifespan 주석). 그래서 「떴다」와 「된다」가
    갈라지고, 그 간극은 요청이 올 때까지 아무 신호를 내지 않는다.

    2026-09-09~15 에 서버 `ai.env` 의 `ANTHROPIC_API_KEY` 가 빠진 채로 배포됐다.
    워커는 healthy 로 떠 있었고 예측도 정상이라, 분류를 눌러 보기 전까지 일주일 동안
    아무도 몰랐다. 시크릿은 `verify-sync.sh` 의 대조 대상도 아니라 레포로도 안 잡힌다.

    **설정만 본다. 외부 API 에 실제로 닿는지는 보지 않는다** — 헬스체크가 네트워크를 타면
    Anthropic 이 잠깐 흔들릴 때 멀쩡한 컨테이너가 unhealthy 로 뒤집히고, 재시작 정책과
    맞물려 흔들린다. 설정 누락은 결과가 확정적이라 그런 위험이 없다.
    """
    settings = request.app.state.settings
    degraded: list[str] = []

    # live 인데 자격 증명이 없으면 분류·OCR 은 **호출이 올 때서야** 터진다 — SDK 가 요청
    # 헤더를 만들며 TypeError 를 내므로, 기동 시점에는 예외도 로그도 나오지 않는다.
    if settings.ai_pipeline_mode == "live" and not _has_anthropic_credential(settings):
        degraded += ["nlp", "ocr"]

    # 가중치는 이미지에 굽지 않고 볼륨으로 붙인다(S15P21C105-76). 마운트가 빠지거나 파일
    # 권한이 틀리면 lifespan 이 예외를 삼키고 **예측만** 미상주로 뜬다.
    if settings.forecast_mode == "live":
        from app.forecast import service as forecast_service  # off 인 환경에는 torch 가 없다

        if not forecast_service.is_loaded():
            degraded.append("forecast")

    return ok(
        {
            # compose healthcheck 는 200 만 본다 — DEGRADED 여도 컨테이너는 살아 있고
            # 멀쩡한 기능은 계속 돈다. 배포를 막을지는 CI 스모크가 판단한다.
            "status": "DEGRADED" if degraded else "UP",
            "pipelineMode": settings.ai_pipeline_mode,
            "visionModel": settings.vision_model,
            # 기동은 됐지만 실제로는 못 도는 기능. **비어 있어야 정상이다.**
            "degraded": degraded,
        }
    )
