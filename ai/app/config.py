"""워커 설정.

시크릿에는 기본값을 두지 않는다 — 미주입 시 명확히 실패한다(백엔드 application.yml 과 같은 원칙).
`ANTHROPIC_API_KEY` 는 여기서 읽지 않는다. Anthropic SDK 가 환경 변수에서 직접 해석한다.
"""

from functools import lru_cache
from pathlib import Path
from typing import Literal

from pydantic import SecretStr, model_validator
from pydantic_settings import BaseSettings, SettingsConfigDict

Effort = Literal["low", "medium", "high", "xhigh", "max"]
PipelineMode = Literal["stub", "live"]
VlmProvider = Literal["claude", "local"]
# off 가 기본이다 — 1·2번(NLP·OCR) 개발 환경은 torch 도 아티팩트도 없이 기동해야 한다.
ForecastMode = Literal["off", "live"]


class Settings(BaseSettings):
    model_config = SettingsConfigDict(env_file=".env", env_file_encoding="utf-8", extra="ignore")

    app_env: Literal["local", "dev", "prod"] = "local"

    # --- API 서버(jango-api) 연동 ---
    backend_base_url: str = "http://localhost:8080"
    # 서비스 간 고정 토큰(명세 20.1). jango-api 와 같은 값. local 이외 환경에서는 필수.
    service_token: SecretStr | None = None
    callback_timeout_seconds: float = 5.0
    callback_max_retries: int = 3
    callback_backoff_seconds: float = 0.5

    # --- Circuit Breaker (워커 → Anthropic) ---
    # 기술스택 §8.7 규칙 6 이 못박은 5회·60초다. 설정으로 뺀 이유는 테스트에서 60초를
    # 실제로 기다리지 않기 위해서다 — 운영값은 바꾸지 않는다.
    # 어느 구간을 지키는 차단기인지는 `app/common/breaker.py` 머리말에 적어 뒀다.
    circuit_failure_threshold: int = 5
    circuit_open_seconds: float = 60.0

    # --- 파일 저장소 ---
    # fileObjectKey 를 해석하는 기준 경로. API 서버가 쓰는 업로드 볼륨을 read-only 로 마운트한다.
    storage_root: Path = Path("/data/uploads")

    # --- 파이프라인 ---
    # stub: 외부 호출 없이 고정 응답. 백엔드 연동·Job 완결 검증용(도입 순서 6단계).
    # live: 실제 모델 호출.
    ai_pipeline_mode: PipelineMode = "stub"
    # VLM 경로의 제공자. claude: Anthropic(GMS). local: 사용자 PC 의 Ollama(토큰 0, 개발·검증용,
    # 정확도 낮음). 최종 수치에는 안 쓴다. 실험 스크립트는 --pipeline local 로도 강제한다.
    vlm_provider: VlmProvider = "claude"
    vision_model: str = "claude-opus-5"  # 기술스택 §5.1 — 검증한 ID 를 설정값으로 고정
    # 모델 티어링. 비우면 끔(vision_model 단독). 채우면 vision_model 이 1차(싼 모델)가 되고
    # 검산이 needsReview 를 내는 건만 이 모델로 재추출한다. 실측 needsReview 12%.
    vision_escalation_model: str | None = None
    # 로컬 VLM(Ollama). 8GB VRAM 은 OOM 시 qwen2.5vl:3b. 이미지 크면 IMAGE_LONG_EDGE 를 낮춘다.
    local_vlm_base_url: str = "http://localhost:11434"
    local_vlm_model: str = "qwen2.5vl:7b"
    local_vlm_timeout_seconds: float = 180.0
    evidence_effort: Effort = "high"
    # 출력 + adaptive thinking 을 합친 상한. 4096 에서는 열화 이미지(촬영 흉내)에서 생각이 길어져
    # 50장 중 2장이 잘렸다(2026-09-08 실측). 잘리면 그 장은 처리 실패가 된다.
    evidence_max_tokens: int = 8192
    product_sales_effort: Effort = "high"
    # 증빙보다 크게 잡는다 — 행 10~16개 × 열 8개를 다 내야 해서 8192 에서 잘린 표본이 있었다
    # (2026-09-08 sales-002, printout80 1/30). 잘리면 그 표본은 처리 실패가 된다.
    product_sales_max_tokens: int = 16384
    # §6.5 품목 정규화 3차 — 저신뢰 후보만 묶어서 한 번. 동기 10초 안에 답해야 하므로 시간 제한을
    # 따로 둔다(초과하면 미매칭으로 응답). 기술스택 기준선은 Opus 5 effort low; GMS 에서는 4.8.
    #
    # **내리지 않기로 했다 (2026-09-15).** 비용 계산에서 이 줄기가 눈에 띄어 Sonnet 을
    # 검토했는데 셈이 안 맞는다. 절감이 100 사업장 월 $1.3 → $0.5 로 **$0.8** 뿐이다
    # (전체 $57 중). 같은 노력으로 건드릴 큰 항목은 증빙 VLM($18)·매출 인쇄물($9) 인데
    # 그 둘은 이미 Sonnet 이고, 기술스택 §6.4 는 거기서 다음 레버가 모델이 아니라
    # **전처리 후 해상도**라고 적었다.
    #
    # 무엇보다 **내린 결과를 관측할 수단이 없다** — `evals/` 에 p1-03 세트가 없다.
    # 이 경로는 §6.5 가 「오매칭이 매칭 실패보다 나쁘다」고 못박은 곳이라, 재지 못하는 채로
    # 내리면 나빠진 것을 나빠진 줄 모른다. 분류에서 Haiku 여유가 +0.0012 였던 것과 같은
    # 종류의 위험이다.
    #
    # 다시 볼 조건: p1-03 평가 세트(오매칭률)가 생기거나, 실사용 `usage` 로그가 §7.1 의
    # 「사업장당 월 20건」 가정보다 훨씬 많다고 말할 때.
    normalization_model: str = "claude-opus-5"
    normalization_effort: Effort = "low"
    normalization_max_tokens: int = 2048  # §6.5 는 50건 묶음에 1,536 — thinking 여유를 둔다
    normalization_timeout_seconds: float = 8.0
    normalization_accept_confidence: float = 0.8  # 이 아래면 LLM 이 같다고 해도 미매칭으로
    image_long_edge: int = 1600  # §6.4 — 원본 4,000px 을 그대로 보내면 이미지 토큰만 3배
    # 긴 영수증(높이/폭 > evidence_tile_max_aspect)을 세로 타일로 잘라 여러 장으로 보낸다.
    # 글자 해상도가 오르지만 이미지 토큰이 타일 수만큼 늘어난다. 효과를 확인한 뒤 켠다.
    evidence_tiling: bool = False
    evidence_tile_max_aspect: float = 1.5
    # 종이 바깥 배경을 잘라 같은 장변 예산을 글자에 쓴다. 절단만 켜면 화면이 정사각형에 가까워져
    # 토큰이 오히려 는다 — IMAGE_LONG_EDGE=1400 과 함께 쓰면 글자 크기 그대로 토큰 18% 절감(실측).
    image_crop_margins: bool = False

    # --- 1번 분류 (20.3 transaction-classification) ---
    # **Opus 5 는 이 경로에서 쓰지 않는다.** 기술스택 §5.1 기준선은 Opus 5 였지만 내렸다 —
    # 2번 파트가 증빙에서 Opus → Sonnet 으로 내린 것과 같은 판단이다. 같은 19건 배치가
    # Haiku 는 $0.0085, Opus 5 는 약 $0.05 였다 (`experiments/p1-01-거래분류.md`).
    #
    # 남은 둘 중 Sonnet 5 를 고른 근거는 실측이다. 라벨 300건 · 프롬프트 v2 로 각각 3회 채점했다
    # (`evals/p1-01/report.json`, 재현은 `python -m evals.run_eval p1-01 --repeat 3`).
    #
    #              Macro F1(거래)     수락률    300건 1회
    #   Haiku 4.5   0.8661 ± 0.0135   0.9572    $0.146
    #   Sonnet 5    0.9226 ± 0.0074   0.9927    $0.309
    #
    # 평균만 보면 Haiku 도 기준 0.85(§10.2)를 넘는다. 그런데 **최저 회차가 0.8512 로 여유가
    # +0.0012** 다 — 표준편차 0.0135 의 1/10 이다. 다시 돌리면 게이트가 빨개진다는 뜻이고,
    # 그때 할 수 있는 일은 결국 모델을 올리는 것뿐이다. Sonnet 의 최저 회차는 0.9144 다.
    #
    # 수락률 차이는 성격이 다르다. confidence 0.85 이상은 사용자 확인 없이 확정되므로 여기서
    # 틀린 건은 **조용히** 틀린다. 3회 모두에서 Haiku 는 회차당 9~10건, Sonnet 은 1건이었다.
    # 도메인 규칙 3 이 확정된 `거래처 ↔ 분류` 를 DB 에 캐시하니 그 오답은 굳어서 재사용된다.
    # 대신 Sonnet 은 자동 확정을 적게 한다(약 137 vs 226) — 사람이 볼 건수가 는다. 돈을 세는
    # 화면에서는 이쪽이 옳은 방향이라 받아들였다.
    #
    # 비용은 2.1 배다. Opus 를 뺀 이유였던 6 배와는 자릿수가 다르고, 규칙 3 덕분에 실제 호출은
    # 처음 보는 거래처에만 일어난다.
    #
    # 바꿀 때 주의 — §8.6 서버측 폴백은 Opus 5 계열 전용이다. 다른 모델에서 `fallbacks` 를
    # 켠 채로 부르면 400 이지만 `claude.py` 가 첫 400 에서 배워 빼므로 설정은 안 건드려도 된다.
    #
    # ⚠ **시연할 때는 Sonnet 으로 돌려야 한다.** 기본값이 Haiku 인 것은 개발 중 크레딧을
    # 아끼려는 것이고, 아래 두 이유로 **시연·발표에 쓸 모델이 아니다** (2026-09-14 결정).
    #
    # ```
    # ai/.env 에서 한 줄 — CLASSIFICATION_MODEL=claude-sonnet-5
    # ```
    #
    # 1. **점수 게이트가 아슬아슬하다.** Haiku 는 3회 평균 0.8661 인데 최저 회차가 0.8512 로
    #    기준 0.85 에 여유가 +0.0012 다(표준편차의 1/10). 다시 돌리면 게이트가 빨개질 수 있다.
    # 2. **조용한 오답이 10배다.** 회차당 9~10건 대 Sonnet 1건. 0.85 이상은 사용자 확인 없이
    #    넘어가고, 도메인 규칙 3 이 그 매핑을 거래처 사전에 캐시해 굳힌다.
    #
    # **`evals/p1-01/report.json` 은 Sonnet 기준이다**(Macro F1 0.9226 · 수락률 0.9927).
    # 시연에서 쓸 모델의 점수이므로 발표 자료에 쓸 수 있지만, **기본값으로 돌린 결과의
    # 점수는 아니다.** 두 값을 같은 것으로 말하지 마라.
    classification_model: str = "claude-haiku-4-5-20251001"
    # 짧은 텍스트 20건 판단이라 증빙 추출(high)보다 낮게 둔다. §8.5 — 라우트별로 튠한다.
    classification_effort: Effort = "medium"
    # `schemas.py` 의 BATCH_MAX 주석은 20건 기준 2048 로 적혀 있으나, adaptive thinking
    # (Opus 5 에서 budget_tokens 400)이 max_tokens 안에서 함께 소비된다. 20건 × 결과에
    # 400 을 더하면 2048 을 넘길 수 있어 4096 으로 둔다.
    # 넘치면 stop_reason=max_tokens 로 **소리 나게** 실패한다 (`claude.py`) — 조용히
    # 잘린 JSON 이 흘러가지 않는다. BATCH_MAX 를 올릴 때 이 값도 함께 올린다.
    classification_max_tokens: int = 4096

    # --- 1번 브리핑 (20.7 briefing-generation) ---
    # **티켓(`-95`)은 Opus 5 를 적었지만 쓰지 않는다.** 분류에서 Opus 를 뺀 이유(입력 5배·
    # 출력 5배 단가)가 여기서도 그대로다. 게다가 이 경로는 **계산을 시키지 않는다** —
    # 주어진 숫자를 문장으로 옮기는 일이라 추론 난도가 분류보다 낮다. 분류가 Sonnet 으로
    # Macro F1 0.9226 을 낸 마당에 브리핑에 그 위 모델을 쓸 근거가 없다.
    briefing_model: str = "claude-sonnet-5"
    # 문장 몇 개를 쓰는 일이라 분류(medium)보다 낮춰 시작한다. §8.5 — 라우트별로 튠한다.
    briefing_effort: Effort = "low"
    # 티켓 기준 2048. headline 40자 + content 2~4문장이면 충분하고, thinking 여유를 둔다.
    briefing_max_tokens: int = 2048
    # --- 1번 초안 점검 (20.10 document-assist) ---
    # 브리핑과 같은 모델이다. 이 경로도 계산을 시키지 않는다 — 주어진 합계·건수를 읽고
    # "무엇을 확인해야 하는가" 를 문장으로 옮긴다.
    assist_model: str = "claude-sonnet-5"
    # **브리핑(low)보다 높다.** 브리핑은 지표를 문장으로 옮기면 끝이지만 여기는 항목 사이
    # 관계를 봐야 한다 — 증빙 누락 3건이 왜 문제인지, 보류 항목이 어느 서식 칸에 걸리는지.
    # 티켓이 high 로 지정한 근거도 그것이다.
    assist_effort: Effort = "high"
    # 티켓 기준 2048. 점검 문장 몇 개라 출력은 작고 나머지는 thinking 여유다.
    assist_max_tokens: int = 2048
    # 명세 20.10 이 정한 동기 타임아웃. **워커가 스스로도 지킨다.**
    #
    # 백엔드가 먼저 끊어도 워커는 모델 호출을 계속해 토큰을 태운다 — 받을 사람이 없는
    # 응답에 돈을 쓰는 것이다. 게다가 백엔드 `jango.ai.client.read-timeout` 기본값이
    # 5초라(15초짜리는 예측 전용) 지금 붙이면 명세보다 먼저 끊긴다. 그쪽은 백엔드에
    # 확인 요청해 뒀고, 이쪽 상한은 계약대로 10초를 지킨다.
    assist_timeout_seconds: float = 10.0

    # --- 2번 공고 구조화 (20.6 program-structuring) ---
    # **Haiku 로 시작한다.** 기술스택 6.6 은 Opus 를 적었고 근거는 중첩 조건이었다
    # (「업력 3년 미만 단, 청년창업자는 5년 미만」). 그 문장은 실재하지만, 1,526건을
    # 전수 조사해 보니 **「단/다만」 예외절은 5%** 다. 나머지 95% 에 5배 단가를 쓸 근거가
    # 그 5% 에서 나오지 않는다.
    #
    # **더 큰 사실이 있다 — 공고의 35% 는 본문에 조건이 아예 없다**(「공고문 참조」,
    # 조건은 첨부 PDF). 모델을 올려서 나아지는 구간이 아니다. 품질의 실제 레버는
    # 모델이 아니라 PDF 본문 읽기다.
    #
    # 전체 1,526건 기준 Haiku $10 · Sonnet $20 · Opus $50 이고 Batch(-97)면 절반이다.
    # **`evals/p1-15` 가 32건짜리 판정 세트다**(1회 $0.21). 미달이면 Sonnet 으로 올려
    # 두 보고서를 비교한다 — 그 비교에 드는 값이 $0.63 이라 추측 대신 재는 쪽이 싸다.
    structuring_model: str = "claude-haiku-4-5-20251001"
    # **분류(medium)보다 높다.** 분류는 짧은 적요 한 줄을 고르는 일이지만 여기는 1,300자
    # 자유 문장에서 조건을 뽑고 **애매한 것을 애매하다고 남겨야** 한다. 그 판단이
    # 이 기능의 안전장치(`uncertainConditions`)라 아낄 자리가 아니다.
    structuring_effort: Effort = "high"
    # 원문 중앙값 1,335자(실측 32건) 입력에 조건·서류 목록 출력. 조건이 많은 공고는
    # `excludes` 와 `uncertainConditions` 가 길어져 2048 로는 모자랄 수 있다.
    # 넘치면 stop_reason=max_tokens 로 **소리 나게** 실패한다 (`claude.py`).
    structuring_max_tokens: int = 4096

    # 자체 호스팅 모델(OCR_PIPELINE 로컬 OCR, 20.11~20.12 예측 모델)의 동시 실행 상한.
    # 명세 20.1 ④ — GPU 없는 4 vCPU 라 OCR 계열과 예측 모델을 동시에 돌리지 않는다(세마포어 1).
    # Claude API 호출(VLM/LLM)은 네트워크 대기라 이 제한을 받지 않는다.
    compute_concurrency: int = 1

    # --- 3번 예측 (20.11 forecast-residual) ---
    # off:  라우트를 붙이지 않는다. torch 를 import 하지도 않으므로 의존성 없이 기동된다
    # live: 기동 시 모델을 상주시킨다. `artifacts/residual-gru.pt` 가 있어야 하고
    #       설정이 코드와 다르면 **기동에 실패한다**
    #       (조용히 틀린 숫자를 내는 것보다 낫다)
    forecast_mode: ForecastMode = "off"
    # cpus 쿼터에 맞춘다. 가변이면 부동소수점 합산 순서가 달라져 재현성이 깨진다 (명세 13.2)
    forecast_threads: int = 2

    # Anthropic 자격 증명. 환경 변수로 주면 SDK 가 직접 읽고, .env 에 두면 여기로 들어와
    # SDK 에 주입된다. pydantic-settings 는 .env 를 os.environ 에 올리지 않으므로
    # 이 필드가 없으면 .env 의 키를 SDK 가 보지 못한다.
    anthropic_api_key: SecretStr | None = None
    # 게이트웨이(SSAFY GMS 같은 프록시)를 거칠 때. SDK 가 뒤에 /v1/messages 를 붙이므로
    # 원본 호스트까지만 적는다. 예: https://gms.ssafy.io/gmsapi/api.anthropic.com
    anthropic_base_url: str | None = None
    # 게이트웨이가 x-api-key 대신 `Authorization: Bearer` 를 요구하면 키를 이쪽에 넣는다.
    anthropic_auth_token: SecretStr | None = None
    # §8.6 서버측 폴백(beta 헤더 + fallbacks). 게이트웨이가 beta 헤더나 파라미터를 거부하면 끈다.
    claude_server_fallback: bool = True
    # §8.3 시스템 프롬프트 캐시(cache_control ephemeral). 캐시 읽기는 입력 단가의 10%.
    claude_prompt_cache: bool = True

    # --- (선택) OCR_PIPELINE 공급자. 선정 전까지 비어 있어도 기동된다 ---
    clova_ocr_invoke_url: str | None = None
    clova_ocr_secret: SecretStr | None = None
    azure_docintel_endpoint: str | None = None
    azure_docintel_key: SecretStr | None = None

    @model_validator(mode="after")
    def _require_secrets_outside_local(self) -> "Settings":
        if self.app_env != "local" and self.service_token is None:
            raise ValueError(
                "SERVICE_TOKEN 이 없습니다. local 이외 환경에서는 필수입니다 (명세 20.1)."
            )
        return self


@lru_cache
def get_settings() -> Settings:
    return Settings()
