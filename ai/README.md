# AI 워커 (jango-ai)

무상태 추론 서버. API 서버(jango-api)가 DB 영속화와 수치 계산을 맡고, 워커는 LLM·OCR 추론만 한다.
계약은 [../docs/api/20_내부_AI워커.md](../docs/api/20_내부_AI워커.md),
모델·호출 규약은 [../docs/기능별_기술스택_선정.md](../docs/기능별_기술스택_선정.md) §5~§8 을 따른다.

Python 3.12.13 · uv · FastAPI · Pydantic v2. 명령은 `ai/` 폴더에서 실행한다. IDE 인터프리터는 `ai/.venv`.

## 빠른 시작

```powershell
uv python install 3.12.13
uv sync --frozen
Copy-Item .env.example .env          # 최초 1회
uv run --frozen python -X utf8 -m uvicorn app.main:app --reload --port 8000
```

`-X utf8` 은 Windows 에서 한국어 로그가 cp949 로 깨지는 것을 막는다(Linux 컨테이너에서는 불필요).

- 상태: http://localhost:8000/health (인증 없음)
- API 문서: http://localhost:8000/docs (`APP_ENV=local` 에서만)
- 기본은 **stub 모드** — 외부 호출 없이 고정 응답으로 "Job 이 완결 상태에 도달"하는지 검증한다(도입 순서 6단계).
  실제 모델을 부르려면 `.env` 에 `AI_PIPELINE_MODE=live` 와 `ANTHROPIC_API_KEY` 를 넣는다.
- 키가 준비되면 서버 없이 한 장으로 먼저 확인한다(비용 발생 — 장당 수 센트):

  ```powershell
  uv run --frozen python -X utf8 -m scripts.smoke_vlm .\datasets\receipts\sample.jpg            # 증빙
  uv run --frozen python -X utf8 -m scripts.smoke_vlm .\datasets\pos\1.jpg .\datasets\pos\2.jpg --sales PRINTOUT
  ```

### 로컬 VLM — 토큰 0 개발 경로

GMS 쿼터를 아끼려면 개발·검증은 사용자 PC 의 [Ollama](https://ollama.com) 로 돌린다. 파이프라인·프롬프트·
스키마·검산이 맞게 동작하는지는 이걸로 충분히 확인되고, **정확도가 낮으므로 최종 수치는 Claude 로 낸다**
(`run.json` 에 모델명이 남아 섞이지 않는다).

```powershell
# 1) Ollama 설치 후 (RTX 4070 8GB 기준 7B, OOM 이면 qwen2.5vl:3b)
ollama pull qwen2.5vl:7b
# 2) 스모크 — --local 로 강제하거나 .env 에 VLM_PROVIDER=local
uv run --frozen python -X utf8 -m scripts.smoke_vlm .\datasets\receipts\sample.jpg --local
# 3) 평가기도 --pipeline local (예측 캐시·재채점 동일, 비용 0)
uv run --frozen python -X utf8 -m experiments.evaluate_evidence datasets/receipts/korie/val --out artifacts/eval/local-001 --limit 20 --pipeline local
```

워커를 로컬로 띄우려면 `.env` 에 `AI_PIPELINE_MODE=live` + `VLM_PROVIDER=local`. 증빙·매출 인쇄물 두 경로가
Ollama 로 가고, 품목 정규화 3차(텍스트 LLM)는 Claude 그대로라 키가 없으면 미매칭으로 폴백한다.

### 실 서비스 비용 줄이기

장당 기준선은 Opus 4.8 · effort high · 캐시 미적용에서 **$0.038**(입력 4,400 · 출력 640 토큰)이다.
`.env.example` 의 "비용 절감 프리셋"을 참고하되, 각 항목은 20장으로 정확도를 확인한 뒤 켠다.

| 방법 | 원리 | 절감 | 상태 |
| --- | --- | --- | --- |
| 프롬프트 캐시 | 시스템 프롬프트+스키마(약 2,000토큰)를 캐시 접두로, 읽기는 입력 단가의 10% | 약 24% | 기본 on (`CLAUDE_PROMPT_CACHE`) |
| **모델 티어링** | 1차 싼 모델 → 검산이 `needsReview` 인 건만 승격. 실측 12%만 승격 | Sonnet 45% · Haiku 75% | 구현 (`VISION_ESCALATION_MODEL`) |
| 여백 절단 + 장변 1400 | 배경을 잘라 같은 글자 크기를 더 적은 픽셀로 | 이미지 토큰 18% | 구현 (`IMAGE_CROP_MARGINS`) |
| 출력 다이어트 | `evidence-extraction/v4`(발표 수치인 test 359장은 v3 기준 — v4 는 현금영수증 15장에서만 검증) 가 warnings 를 3개·60자로 제한 | 출력 일부 | 기본 적용 |
| effort 낮추기 | 사고 토큰이 출력 단가(입력의 5배)로 과금된다 | 5~10% | 설정 (`EVIDENCE_EFFORT`) |
| 파일 해시 중복 제거 | 같은 이미지 재업로드 시 저장된 결과 재사용 | 재업로드분 100% | **백엔드 몫** |
| Batch API | 비동기 경로를 묶어 보내면 50% 할인 | 약 50% | 미구현 (게이트웨이 지원 확인 필요) |

티어링은 검산이 결정적이라 가능하다. 싼 모델이 틀리면 검산이 잡아 강한 모델이 다시 읽으므로 품질은 유지된다.
결과 `warnings` 에 승격 사유가 남고, 평가 `run.json` 의 `escalatedReceipts`·`usageByModel` 로 승격률과
혼합 비용을 실측한다.

검증:

```powershell
uv run --frozen ruff format .      # 포맷 (커밋 전)
uv run --frozen ruff check .       # 린트
uv run --frozen pytest
```

의존성은 `uv add` 로 넣고 `pyproject.toml` 과 `uv.lock` 을 함께 커밋한다. OCR 공급자 SDK 는
선정 후 extra(`uv sync --extra azure` 등)로만 설치한다 — 기본 설치에 넣지 않는다.

## 백엔드·프론트 없이 테스트하는 방법

증빙 추출은 워커만으로 끝까지 확인할 수 있다. 네 단계가 있고 아래로 갈수록 실제 경로에 가깝다.

**① 모델·정확도만 — HTTP 없음 (권장)**

이미지 폴더만 있으면 된다. 라벨이 있으면 채점까지 한다.

```bash
uv run --frozen python -X utf8 -m scripts.smoke_vlm receipt.jpg          # 1장, 결과+검산 출력
uv run --frozen python -X utf8 -m experiments.evaluate_evidence <라벨폴더> --out artifacts/eval/x
```

`--rescore` 를 붙이면 저장된 예측만 다시 채점한다(**API 호출 0**). 채점기 목록은
[`experiments/README.md`](./experiments/README.md).

**② 동기 엔드포인트 — 콜백이 없어 수신기도 필요 없다**

검산 재실행과 품목 정규화는 즉시 응답한다. 검산은 **모델을 부르지 않아 비용 0** 이다.

```bash
uv run --frozen uvicorn app.main:app --port 8000
```

```bash
curl -X POST http://127.0.0.1:8000/internal/v1/ai/evidence-validation -H "Authorization: Bearer $SERVICE_TOKEN" -H "X-Request-Id: t-1" -H "Content-Type: application/json" --data-binary @body.json
```

> Windows Git Bash 에서 `-d '{"...한글..."}'` 을 인라인으로 주면 인코딩이 깨져
> `VALIDATION_FAILED`(body 파싱 실패)가 난다. **UTF-8 파일로 저장해 `--data-binary @file` 로 준다.**

**③ 비동기 엔드포인트 + 가짜 API 서버 — HTTP 경로를 통째로**

콜백을 받을 곳이 필요하다. `scripts/fake_backend.py` 가 그 역할을 하면서 **명세 20.2 계약을
검사한다** — 서비스 토큰, `X-Request-Id = ai-job-{jobId}-result`, Envelope 모양, `data.status`,
모델 메타. 그냥 200 만 주면 계약 위반을 못 잡으므로 뜯어본다.

```bash
uv run --frozen python -X utf8 -m scripts.fake_backend --port 8011 --out artifacts/fixtures
```

```bash
BACKEND_BASE_URL=http://127.0.0.1:8011 AI_PIPELINE_MODE=stub uv run --frozen uvicorn app.main:app --port 8010
```

`--strict` 를 주면 위반 시 400 을 돌려 워커의 4xx 즉시 포기 동작도 볼 수 있다. `--out` 은 받은
본문을 저장해 백엔드 픽스처로 쓰게 한다. 보낼 본문을 미리 보고 싶으면
`scripts/sample_callback.py`(모델 호출 없음)를 쓴다.

**④ 진짜 백엔드로 보내기 — 합동 검증**

```bash
uv run --frozen python -X utf8 -m scripts.send_callback product-sales --job-id 9401
```

저장된 콜백 본문을 **실제 백엔드에 쏜다**. 수신기는 셋인데(아래) 정작 합동 단계에서 필요한
보내는 쪽이 없었다. 본문은 `sample_callback.py` 와 **같은 함수**로 만들어 픽스처와 실제 콜백이
갈라지지 않는다 — 매출 쪽에서 `validation` 이 빠진 채 나간 적이 있다. 모델을 부르지 않는다.

`--job-id` 는 본문의 `jobId` 와 `X-Request-Id`(`ai-job-{id}-result`)를 함께 바꾼다.
`--dry-run` 은 보내지 않고 본문만 찍는다.

> **백엔드 쪽 전제.** 그 `jobId` 의 `JOB` 행이 실제로 있어야 한다(없으면 404). 매출 인쇄물은
> `job_type = 'PRODUCT_SALES_OCR'` 인데 **14.7 업로드가 아직 없어 Job 을 만들 경로가 없다** —
> 그때까지는 행을 직접 넣고 확인한다. `--base-url` 로 대상을 바꿀 수 있어, 먼저
> `fake_backend`(③)에 쏴서 우리 쪽이 맞는지 보고 나서 진짜 백엔드로 가면 된다.

**수신기가 둘인데 규칙은 하나다.** `scripts/callback_sink.py` 는 모듈 앱 진입점이고
(`uvicorn scripts.callback_sink:app --port 8099`, 받은 본문을 `artifacts/callback/job-{jobId}.json`
에 저장, 한 건씩 요약을 찍는다), 위 `fake_backend.py` 는 CLI 진입점이다(토큰 값까지 보고 401·400 을
돌린다). **규약 검사는 `scripts/callback_contract.py` 한 곳에 있다** — 전에는 두 수신기가 같은
20.2 를 서로 다른 엄격도로 적고 있어서, `callback_sink` 에서 「규약 검사 통과」가 뜨는 본문이
`fake_backend` 에서는 위반이었다(`data.status`·모델 메타·Envelope 키 집합을 안 봤다).

**④ 테스트 — 위 경로를 자동으로**

```bash
uv run --frozen pytest -q
```

콜백 수신은 `respx` 로 가짜 처리한다. 파일 저장소도 `AI_PIPELINE_MODE=stub` 이면 읽지 않으므로
**공유 볼륨 없이도** 백엔드 연동을 검증할 수 있다(그게 `STUB` 파이프라인이 있는 이유다).

`tests/ocr/test_callback_contract.py` 는 **③ 의 검사기를 그대로 불러** 두 비동기 엔드포인트의
실제 콜백 본문을 통과시킨다. ③ 을 손으로 띄우지 않아도 계약 위반이 테스트에서 드러난다 —
전에는 콜백 모양이 어긋나도 테스트가 전부 통과했다. 검사기가 아무것도 안 잡는 것이 되지
않도록 어긋난 본문 6가지를 정말 잡는지도 함께 시험한다.

`tests/ocr/test_db_schema_conformance.py` 는 한 걸음 더 가서 **콜백이 백엔드 테이블에 실제로
들어가는지** 본다. 마이그레이션(`V*.sql`)을 **파싱해서**(베끼지 않는다) `CHECK` 값 집합 ·
`NOT NULL` · `VARCHAR` 길이 · `NUMERIC` 정밀도를 대조한다. 한 파일이 아니라 디렉터리 전체를
읽는다 — 증빙은 `V6`, 매출 인쇄물은 `V8` 이고 다음 테이블이 어느 번호로 올지 모른다.

증빙(20.4)에서는 합의 ⑤(`pipeline` 에 `STUB` 없음 · `model_version` NOT NULL · `prompt_version`
없음)를 사람이 ERD 를 읽어서 찾았는데, 이제 테스트가 찾는다. 매출 인쇄물(20.9)에서는
`period_source` 가 `HEADER` 하나로 좁혀진 것(「워커는 추론하지 않는다」의 스키마 표현)과
`captureType` 값 집합을 계약과 대조한다. **상품 행은 검사하지 않는다** — 합의 ④ 대로 저장하지
않고(14.7 「OCR 결과는 후보」) 실제로 백엔드에도 쓰는 코드가 없다.

백엔드 마이그레이션이 없는 체크아웃에서는 통과시키지 않고 건너뛴다.

### 시연 자료

```bash
uv run --frozen python -X utf8 -m scripts.make_demo
```

`artifacts/demo/index.html` 하나가 나온다 — **자기완결**이다. 이미지를 data URI 로 박아 넣어
파일만 들고 다니면 되고, 발표장 와이파이가 없어도 열리며 **모델을 부르지 않는다**(저장된 예측을
재생한다). 산출물은 gitignore 대상이고 이 스크립트가 정본이다.

시연에 **KORIE 이미지를 쓰지 않는다** — 라이선스 미표기 데이터다. 화면에 띄우는 것은 우리가 만든
합성 이미지뿐이다. 큐레이션 4표본은 실측에서 실제로 그렇게 나온 건들이다.

| 표본 | 보여줄 것 |
| --- | --- |
| 세금계산서 (정상) | 필드·품목 전부 정확, 검산 통과, 검토 불필요 |
| 현금영수증 009 | **금액 칸 밀림** — 합계 검산은 통과하는데 세율 검사가 잡는다 (v1.14) |
| 현금영수증 005 | **품목 이름 열 밀림** — 산술이 다 통과, 구조 검사만 잡는다 (v1.15) |
| 현금영수증 012 | 사업자번호 한 자리 오독 — 체크섬이 잡는다. **실행마다 흔들린다**(4번 중 2번 틀리고 틀릴 때마다 다른 숫자) — 발표에서 "항상 이렇게 읽는다"고 말하지 않는다 |

「실제 값 보기」로 AI 가 틀린 곳을 빨간 글씨로 드러내고(실제 서비스 화면에는 없는 열이다),
「사용자가 바로잡음」으로 검산 사유가 사라지는 것을 보인다. 그 재검산은 실제 서비스에서
`POST /internal/v1/ai/evidence-validation`(모델 호출 없음)이 담당하며, 이 페이지는 결과를 미리
계산해 담았다 — **화면에서 검산식을 다시 구현하지 않는다.**

#### 매출 인쇄물 (P1-22, 명세 20.9)

```bash
uv run --frozen python -X utf8 -m scripts.make_sales_demo
```

`artifacts/demo-sales/index.html` — 같은 방식이고 껍데기(CSS·상호작용)는 `scripts/demo_shell.py`
를 증빙 쪽과 함께 쓴다. 두 곳에 복사하면 화면이 갈라진다.

이야기가 증빙과 다르다. 증빙은 「값 몇 개를 정확히 읽는가」이고, 매출 인쇄물은 **「표를 통째로,
열이 줄어드는 종이에서도 읽는가」** 다.

| 표본 | 보여줄 것 |
| --- | --- |
| 80mm 정상 (6열) | 17행을 그대로 읽고 검산 통과 |
| **58mm 좁은 종이 (4열)** | 열이 줄어든 것을 알아채고 **없는 열을 지어내지 않는다**(요구 3) |
| 80mm 012 | 행 안의 금액이 서로 안 맞는 것을 **검산이 잡는다** (1행) |
| **화면 021** | 16행 **전부 밀렸는데 검산은 「검토 불필요」** — 한계를 감추지 않는다 |

**네 번째가 이 시연의 요점이다.** 실측에서 숫자가 틀린 57행 중 52행(91.2%)이 행 밀림이고, 열이
함께 움직여 행 안의 산술이 그대로 성립한다. 「라벨과 다른 행 보기」를 누르면 16행이 전부 주황으로
바뀌는데 검산 패널은 초록이다 — **"다 잡습니다"라고 말할 수 없는 이유**가 화면에 있다.

### 발표 리허설 체크리스트

발표 전날 이 순서로 한 번 통과시킨다. **2026-09-09 에 전부 통과했다.**

| # | 확인 | 명령 | 기대 |
| --- | --- | --- | --- |
| 1 | 시연 자료 생성 | `uv run --frozen python -X utf8 -m scripts.make_demo` | 4표본 · 검토 불필요 1 + 사유 3종 |
| 1b | 매출 시연 생성 | `uv run --frozen python -X utf8 -m scripts.make_sales_demo` | 4표본 · 검토 필요 1 · 밀림 표본의 「라벨과 다른 행」 16 |
| 2 | 자료가 열리나 | `artifacts/demo/index.html` 을 브라우저로 | 탭 4개 · 「실제 값 보기」·「사용자가 바로잡음」 동작 |
| 3 | 백업 — 가짜 백엔드 | `... -m scripts.fake_backend --port 8031` | `콜백 대기` |
| 4 | 백업 — 워커 | `BACKEND_BASE_URL=http://127.0.0.1:8031 AI_PIPELINE_MODE=stub ... uvicorn app.main:app --port 8030` | `/health` 200 |
| 5 | 비동기 왕복 | 위 curl (`evidence-extraction`) | `202 {jobId}` → 가짜 백엔드에 **`[OK ]`** (계약 위반 0) |
| 6 | 동기 검산 | 위 curl (`evidence-validation`) | `needsReview true` · `VAT_RATIO_INCONSISTENT` |

**발표장에서 하지 않을 것.** ① 모델 호출 — 시연 자료가 저장된 결과를 재생하므로 필요 없다.
② KORIE 이미지 노출 — 라이선스 미표기다. ③ `boundingBox` 하이라이트를 기대하게 만들기 —
값이 `null` 이다. 먼저 밝히면 근거 있는 절제로 읽힌다(실측 중심 적중 33%).

### 이 방법으로 확인되는 것과 안 되는 것

| 확인된다 | 안 된다 (백엔드·프론트 필요) |
| --- | --- |
| 추출 정확도 · 검산 · `needsReview` 사유 | 증빙 업로드 (`POST /evidences` 없음) |
| 콜백 본문·헤더가 명세 20.2 대로인가 | `EVIDENCE_EXTRACTION` 영속화 (테이블 없음) |
| 202 접수 → 콜백 왕복 | 조회·수정·확정 (`GET`/`PATCH /evidences/{id}` 없음) |
| 검산 재실행·품목 정규화 응답 | 재추출 트리거 · 수정 이력 저장 |

## 환경 변수

| 변수 | 기본 | 설명 |
| --- | --- | --- |
| `APP_ENV` | `local` | `local` 이외에서는 `SERVICE_TOKEN` 없으면 부팅 실패 |
| `BACKEND_BASE_URL` | `http://localhost:8080` | 콜백 대상 API 서버 |
| `SERVICE_TOKEN` | — | 서비스 간 고정 토큰. jango-api 와 같은 값. 없으면 내부 API 전부 401 |
| `AI_PIPELINE_MODE` | `stub` | `stub` / `live` |
| `STORAGE_ROOT` | `/data/uploads` | `fileObjectKey` 기준 경로. API 서버 업로드 볼륨을 read-only 로 공유 |
| `ANTHROPIC_API_KEY` | — | `live` 에서만 필요. 환경 변수면 SDK 가 직접 읽고, `.env` 에 두면 Settings 를 거쳐 SDK 에 주입된다 |
| `COMPUTE_CONCURRENCY` | `1` | 자체 호스팅 모델(로컬 OCR·예측 모델)의 동시 실행 상한 — 명세 20.1 ④. Claude 호출은 제한 없음 |
| `ANTHROPIC_BASE_URL` | — | 게이트웨이(SSAFY GMS 등) 사용 시 원본 호스트까지 (`https://gms.ssafy.io/gmsapi/api.anthropic.com`). SDK 가 `/v1/messages` 를 붙인다 |
| `ANTHROPIC_AUTH_TOKEN` | — | 게이트웨이가 `Authorization: Bearer` 를 요구할 때 `ANTHROPIC_API_KEY` 대신 |
| `CLAUDE_SERVER_FALLBACK` | `true` | §8.6 서버측 폴백(beta 헤더 + `fallbacks`). **Opus 5 전용** — `claude-opus-4-8` 등 다른 모델은 400 이므로 `false`. 점검은 `python -X utf8 -m scripts.ping_claude` |
| `VISION_MODEL` | `claude-opus-5` | **실측 권장은 `claude-sonnet-5`** — val 20장에서 Opus 대비 71% 저렴하고 금액·사업자번호·검산 100%(`experiments/README.md` 모델 비교). 기술스택 §5.1 기준선은 Opus 5 이므로 최종 채택은 팀 합의 사항. §5.1 — **SSAFY GMS 는 Opus 5·Sonnet 5 를 열어 두지 않아** (2026-09-07 확인: `claude-opus-4-8`·`4-7`·`4-6`, `claude-sonnet-4-6`, `claude-sonnet-4-5-20250929`, `claude-haiku-4-5-20251001` 만) GMS 경로에서는 같은 가격대의 `claude-opus-4-8` 을 쓴다. 어떤 모델로 측정했는지는 `run.json` 에 자동 기록 |
| `VLM_PROVIDER` | `claude` | `local` 이면 사용자 PC 의 Ollama 로 부른다 — **토큰 0**, 개발·검증용. 정확도가 낮아 최종 수치에는 쓰지 않는다(아래 "로컬 VLM") |
| `LOCAL_VLM_BASE_URL` / `LOCAL_VLM_MODEL` / `_TIMEOUT_SECONDS` | `http://localhost:11434` / `qwen2.5vl:7b` / `180` | Ollama 주소·모델·타임아웃. 8GB VRAM 에서 OOM 이면 `qwen2.5vl:3b` |
| `EVIDENCE_EFFORT` / `PRODUCT_SALES_EFFORT` | `high` | §8.5 — 라우트별로 튠한다 |
| `EVIDENCE_MAX_TOKENS` | `8192` | 출력 + adaptive thinking 상한. 4096 에서는 열화 이미지 50장 중 2장이 잘렸다 |
| `VISION_ESCALATION_MODEL` | — | **모델 티어링.** 채우면 `VISION_MODEL` 이 1차(싼 모델)가 되고 검산이 `needsReview` 를 내는 건만 이 모델로 재추출한다. 실측 needsReview 12% |
| `IMAGE_LONG_EDGE` | `1600` | §6.4. Claude API 도 장변 1,568 에서 다시 줄이므로 더 올려도 효과 없음 |
| `IMAGE_CROP_MARGINS` | `false` | 종이 바깥 배경 절단. **장변 1400 과 함께** 쓰면 글자 크기 그대로 이미지 토큰 18% 절감(실측). 절단만 켜면 토큰이 되레 는다 |
| `EVIDENCE_TILING` / `EVIDENCE_TILE_MAX_ASPECT` | `false` / `1.5` | 긴 영수증을 세로 타일로 나눠 보낸다(글자 해상도 ↑, 비용 +52%). 실측 뒤 켠다 |
| `CLAUDE_PROMPT_CACHE` | `true` | 시스템 프롬프트 `cache_control`(§8.3). 게이트웨이가 거부하면 `false` |
| `NORMALIZATION_MODEL` / `_EFFORT` / `_MAX_TOKENS` / `_TIMEOUT_SECONDS` / `_ACCEPT_CONFIDENCE` | `claude-opus-5` / `low` / `2048` / `8.0` / `0.8` | 품목 정규화 3차(§6.5). **실측 권장은 `claude-sonnet-5`** — 짧은 텍스트 판정에 Opus 는 4배 비싸고(입출력 5/25 vs 2/10) 실측에서 차이를 볼 만한 작업이 아니다. GMS 에서는 `claude-opus-4-8` 로 |
| `CLOVA_OCR_*` / `AZURE_DOCINTEL_*` | — | OCR_PIPELINE 공급자 선정(§12) 후 한쪽만 |
| `FORECAST_MODE` | `off` | `live` 면 20.11 라우트를 붙이고 모델을 상주시킨다. `artifacts/residual-gru.pt` 필요 — **없으면 예측만 「모델 미상주」가 되고 워커는 뜬다** |
| `FORECAST_THREADS` | `2` | torch 스레드 수. 가변이면 재현성이 깨진다 (명세 13.2) |

## 구조와 담당

```
app/
├─ main.py             앱 조립 (create_app). 새 라우터는 여기서 include
├─ config.py           Settings (pydantic-settings)
├─ health.py           GET /health
├─ common/             ★ 공통 — 전원이 그대로 쓴다
│  ├─ auth.py          Bearer 서비스 토큰 검증
│  ├─ request_id.py    X-Request-Id 필수 · 응답 에코 · 로그 컨텍스트
│  ├─ envelope.py      {success, data, error} + CamelModel(JSON camelCase)
│  ├─ errors.py        ErrorCode(공통규약 45종 중 사용분) → Envelope 변환
│  ├─ callback.py      POST /internal/v1/ai-jobs/{jobId}/result (재시도·멱등 ID)
│  ├─ claude.py        Anthropic 호출 래퍼 — 구조화 출력·effort·서버측 폴백·stop_reason·usage 로그 (§8)
│  ├─ model_meta.py    modelName · modelVersion · promptVersion · pipeline
│  └─ logging.py       stdout 전용 + 민감정보 마스킹
├─ ocr/                ★ 2번 — OCR · VLM · 문서 인식
│  ├─ router.py        evidence-extraction · evidence-validation · item-normalization
│  │                   · product-sales-extraction
│  ├─ schemas.py       명세 20.4 / 20.5 / 20.9 / 13.2 계약
│  ├─ storage.py       fileObjectKey → 파일 (저장소 밖 경로 차단)
│  ├─ preprocess.py    EXIF 회전 · 장변 1600 · JPEG 재인코딩
│  ├─ validators.py    사업자번호 체크섬 · 금액 검산 · 부가세=과세×10% · 품목 행 정렬 · 날짜
│  ├─ sales_validators.py  매출 인쇄물 검산 — 행 안의 산술 4식 + 합계 행 체크섬 (20.9)
│  ├─ normalization.py 단위 규칙(1차) + rapidfuzz 유사도(2차)
│  ├─ prompts.py       버전 고정 프롬프트 (SCREEN / PRINTOUT 분리)
│  └─ pipelines/       base(Protocol) · stub · claude_vlm · select(AUTO/OCR_PIPELINE/VLM)
├─ forecast/           ★ 3번 — 잔여 현금흐름 예측 (명세 20.11)
│  ├─ router.py        forecast-residual (동기 15초). 세마포어 1 안에서만 추론
│  ├─ schemas.py       명세 20.11 계약. **금액과 날짜만** 받는다
│  ├─ service.py       모델 상주 · 단일 창 추론 · 스레드 고정
│  ├─ features.py      창 크기·채널·달력 특징의 **유일한 출처** (학습·서빙 공용)
│  ├─ model.py         GRU 정의 + 낡은 아티팩트 차단 로더
│  ├─ baseline.py      통계 베이스라인 · 표본 부족 등급. **폴백 경로**
│  ├─ residual.py      잔여 집계 (실제 − 예정거래 매칭분)
│  └─ calendar_kr.py   2022~2026 공휴일 · 연휴 · 영업일
└─ nlp/                ★ 1번 — 거래 분류 · 공고 구조화 · 브리핑 · 초안 점검
   ├─ router.py        transaction-classification (비동기 → 콜백). 폐기·누락 처리는 여기서
   ├─ schemas.py       명세 20.3 계약 — 카테고리 23종 + 방향표 + 배치 검증
   ├─ pipeline.py      stub · Claude(LLM) · build_classifier
   └─ prompts.py       버전 고정 프롬프트 (transaction-classification/v1)
tests/                 pytest — 계약 · 인증 · 콜백 · 검산 · 전처리
evals/                 평가 세트. 정답셋(jsonl)은 커밋, 작업용 xlsx 는 금지
experiments/           모델 비교(OCR). 데이터·결과는 커밋 금지
artifacts/             예측 모델 아티팩트(residual-gru.pt). **.gitignore**
datasets/              거래 시드 CSV · OCR 비교용 표본. **.gitignore**
```

`forecast` 는 `FORECAST_MODE=off` 가 기본이라 **torch 없이도 그대로 기동된다** —
1·2번 파트는 신경 쓸 것이 없다. 켜려면 `uv sync --extra forecast` + `FORECAST_MODE=live`.

**예측 모델의 학습·비교·시연은 이 레포에 없다.** 더미 데이터로 도는 실험이라
운영 코드가 아니고, `chronos-forecasting` 을 넣으면 `transformers` 가
`huggingface-hub` 을 1.30 → 0.36 으로 끌어내려 **OCR 의 paddle extra 까지 영향**을 준다.
실험은 `private/현재/forecast-lab/` 에서 돌린다. **거기서 project 로 오는 것은
`artifacts/residual-gru.pt` (350KB) 하나뿐**이다 — 워커가 읽는 파일이라서다.
더미 데이터·Chronos 아티팩트(224MB)·측정 리포트는 전부 private 에 남는다.

새 라우터를 만들 때: `APIRouter(dependencies=[Depends(require_service_token), Depends(require_request_id)])`
로 선언하고 `create_app` 에 include 한다. 성공은 `ok()`, 실패는 `AppError`, 콜백은
`callback.completed()` / `callback.failed()` 만 쓴다. Claude 는 `request.app.state.claude`
(`ClaudeClient.parse`) 로만 부른다 — `anthropic.Anthropic()` 을 라우트마다 새로 만들지 않는다.

## 공통 계약 요약

| 항목 | 규칙 |
| --- | --- |
| 인증 | `Authorization: Bearer {SERVICE_TOKEN}`. 실패 → `401 INTERNAL_AUTH_FAILED`. 토큰 미설정이면 전부 거절 |
| 멱등성 | 모든 POST 에 `X-Request-Id` 필수(없으면 `400 VALIDATION_FAILED`). 응답 헤더로 에코 |
| Envelope | 응답·콜백 모두 `{ "success", "data", "error" }` |
| 비동기 | `202 { "jobId" }` → 백그라운드 → 콜백. 파일 없음·파이프라인 없음은 **202 전에** 4xx |
| 콜백 완료 | `{"success": true, "data": {"status": "COMPLETED", "modelName", "modelVersion", "promptVersion", "pipeline", ...결과}}` |
| 콜백 실패 | `{"success": false, "error": {"code": "AI_ANALYSIS_FAILED", "message", "detail"}}` → API 서버가 Job FAILED |
| 콜백 X-Request-Id | `ai-job-{jobId}-result` 고정 — 재시도가 중복 저장되지 않는다 |
| 콜백 재시도 | 5xx·연결 오류 3회(0.5s × 2ⁿ). 4xx 는 즉시 포기. 최종 실패는 로그 → JobSweeper(10분) 회수 |
| 로그 | stdout 만. 원본 이미지·추출 원문·민감값 금지. 필터가 주민/사업자/계좌 패턴을 마스킹 |
| 결과 메타 | 모든 결과에 `modelName` `modelVersion` `promptVersion` `pipeline`. stub 은 `pipeline: "STUB"` |

## NLP 파트(1번) 현재 상태

| 항목 | 상태 |
| --- | --- |
| `app/nlp/schemas.py` (명세 20.3) | **완료** — 카테고리 23종·방향표·`allowedCategories` 밖·방향 불일치·ID 중복 검증 |
| `POST /internal/v1/ai/transaction-classification` | **stub 완료 · live 구현, 실측 전.** 폐기 검사(허용 목록 밖·방향 불일치·ID 중복)를 라우터가 stub·LLM 양쪽에 적용한다 |
| 카테고리 23종 ↔ 백엔드 시드 SQL 대조 | **완료** — `tests/nlp/test_category_seed_parity.py`. 한쪽만 바뀌면 테스트가 깨진다 |
| 평가 세트 도구 (`evals/`) | **완료** — 층화 추출 · 반복 주기 탐지 · 라벨 검증 · 라벨링 xlsx 왕복 |
| 평가 세트 정답 300건 | **0/300.** 사람이 채워야 하고 **AI 가 대신 만들면 안 된다** |
| 공고 구조화·브리핑·초안 점검 스키마 | 미구현 — 부를 백엔드(P1-15·P1-17·P0-12)가 전부 미착수 |
| 채점 도구 (`evals/run_eval.py`) | 미구현 |

거래 분류 live 경로는 **실제 모델 호출을 한 번도 하지 않았다.** stub 으로 계약·폐기·콜백만
검증했다. 실측할 때는 **평가 세트 300건을 쓰지 말 것** — 출력을 사람이 보면 라벨링이 모델
답에 끌려가고, 그러면 AI 가 라벨을 채운 것과 같아진다. 평가 세트 밖의 거래로 한다.

평가 세트 300건은 **합성 데이터**다 — 금융망이 과거 날짜 거래를 만들 수 없고(§4.1.1)
CSV 시드도 없어서 직접 만들었다. 실제보다 점수가 높게 나오므로 **프롬프트 A/B 비교에만**
쓰고 절대 점수로 인용하지 않는다. `python -m evals.check p1-01` 이 매번 경고한다.

거래 CSV 를 새로 만들려면 `evals/p1-01/거래_CSV_생성_규격.md` 를 본다 — 업로드 규격
(명세 7.4)과 평가 세트 요구사항을 함께 적어 두었다.

## OCR 파트(2번) 현재 상태

| 엔드포인트 | 상태 |
| --- | --- |
| `POST /internal/v1/ai/evidence-extraction` | stub 완료 · live = `claude-sonnet-5`(프롬프트 `evidence-extraction/v3`) · 검산 `validation` + `needsReview`/`reviewReasons` 동봉(v1.13 — 게이트는 결정적 검사만, 모델 신뢰도는 `fieldConfidence` 로만 전달) · **재추출 `extractionMode=THOROUGH`**(타일링 + 강한 모델, v1.17) · **KORIE test 359장 최종 측정**: 처리 실패 0%, 날짜 99.2%, 합계 99.1%, 검산 99.1%, 품목 재현율 95.3%, needsReview 13.9%, **장당 $0.0106** (`experiments/README.md` 결과표) |
| `POST /internal/v1/ai/evidence-validation` | **동기·모델 호출 없음(비용 0).** 검산만 다시 돌린다 — 검토 화면에서 사용자가 값을 고친 직후 검산 해소를 보여주고, 규칙이 화면·API 서버에 복제돼 드리프트하는 것을 막는다 (v1.17) |
| `POST /internal/v1/ai/item-normalization` | 1차 단위 규칙 + 2차 유사도 + 3차 LLM 묶음 호출(live 모드, 저신뢰 0.70~0.92 만, 8초 제한, 실패 시 미매칭). `matchedBy` 로 단계 표시. **실측(2026-09-08, Sonnet 5)**: 사전에 정답이 있는 239건 중 91.6% 를 붙이고 그중 **1·2차가 100%** 를 처리했다 — §6.5 "규칙이 90% 이상" 성립. 위험한 오매칭(정답이 사전에 있는데 다른 항목)은 2,026건 중 1건(0.05%). 3차는 45회 호출에서 평균 3.1초, 후보 밖 itemId·스키마 오류 0건이고 확정률이 보수적(6%)이다. 확정 건은 손검증 3/3 정답 — 자동 채점의 3차 정확도는 채점 사전 결함으로 신뢰할 수 없다. 2차 임계값은 0.92 유지(1.00 으로 올려도 오인이 11.7% 남고 놓침만 두 배). (`experiments/README.md`) |
| `POST /internal/v1/ai/product-sales-extraction` | stub 완료 · live = `claude-sonnet-5`, 여러 장을 한 요청으로 병합, SCREEN/PRINTOUT 프롬프트 분리 · **합성 90표본 전량 실측: 명세 20.9 요구 4건 전부 통과**(만들어낸 행 0, 상품명 exact 99.8%, 행 F1 99.4%), 표본당 $0.024 (`experiments/README.md` 발표용 수치 요약) |
| OCR_PIPELINE 공급자 | **데모 범위에서 제외 — `AUTO` = `VLM` 유지.** 붙일 이유가 두 개였는데 둘 다 약해졌다. ① `boundingBox` 좌표 — VLM 실측(중심 적중 33%)으로 하이라이트를 포기하고 필드 강조로 대체했다(합의 ⑥). ② 비용 2단 티어 — 이미 장당 $0.0106 이고 비용 지렛대는 모델 티어링으로 있다. 붙일 때는 `pipelines/select.py` 에서 생성하면 된다. 후보 사실 확인(2026-09-08): Azure `prebuilt-receipt` 는 한국어(`ko`) 지원, 무료 F0 는 월 500페이지·다중 페이지 앞 2장만·요청 폭주 시 403. 로컬 대안은 `paddle` extra |

## 백엔드(jango-api) 연동 상태

연동 기반 5건(`jango.ai` 설정 · `/internal/**` 서비스 토큰 체인 · 콜백 수신과 `X-Request-Id` 중복 제거 ·
`JobType` 8종 · 업로드 볼륨 `:ro` 공유)은 **백엔드 쪽에서 해소됐다** *(2026-09-07 —
[99_구현현황.md 7장·7.1](../docs/api/99_구현현황.md))*. `SERVICE_TOKEN` 만 양쪽에 맞추면 API 키 없이
`enqueue → 워커 호출 → 콜백 → Job 완결` 이 돈다. 백엔드 콜백 수신은 `data.status = PARTIAL` 을 `COMPLETED` 로
저장하고 `resultRef.status` 로 구분한다. 워커가 4xx 로 거절하면 Job 은 즉시 `FAILED` 다.

남은 것은 **저장·소비 합의 5건**이다 — 선택지·근거·`CREATE TABLE` 초안·콜백 픽스처를
[`docs/api/98_AI_백엔드_합의.md`](../docs/api/98_AI_백엔드_합의.md) 에 정리했다. ① 헤더 확장 필드는
`header_data` JSONB 로 ② `EVIDENCE_ITEM` 에 `amount`·`discount_amount` 컬럼 추가(에누리는 갈 곳이 없다)
③ `needsReview` 는 상태가 아니라 정렬·강조 ④ `matchedBy` 저장 ⑤ ERD 와 실제 출력 불일치 3건
(`pipeline` CHECK 에 `STUB`, `model_version` nullable, `prompt_version` 신규).
픽스처: `uv run --frozen python -X utf8 -m scripts.sample_callback --out artifacts/fixtures` (모델 호출 없음).

### 1번 파트(거래 분류)에 추가로 필요한 합의

워커 라우트는 구현됐지만 **호출하는 쪽이 아직 없다.** 백엔드에 참조가 하나도 없다.
`JobType.TRANSACTION_CLASSIFICATION` 은 이미 enum 에 있다.

6. `POST /businesses/{id}/classification-jobs` (명세 8.5) — 분류 Job 생성. 미구현
7. `TransactionClassificationHandler` — 규칙·거래처 사전으로 먼저 걸러 남은 것만 워커로.
   `EvidenceExtractionHandler` 와 같은 모양이면 된다
8. **합의가 필요한 것** — 라우트 경로와 `allowedCategories` 위치는 명세 20.3 에 있고 시드 SQL
   주석도 같은 값 집합을 가리키므로 사실상 정해진 것으로 본다. 남은 것은 **배치 최대 크기**
   (현재 워커는 `BATCH_MAX = 20`) — 이건 워커 쪽 제안이고 백엔드에 참조가 없다
9. ~~카테고리 23종 드리프트~~ → `tests/nlp/test_category_seed_parity.py` 가 `V2__seed_transaction_category.sql`
   과 대조한다. 한쪽만 바뀌면 이제 **테스트가 깨진다**

## Docker

로컬(8000 포트를 쓰는 Python 서버와 동시에 띄우지 않는다):

```powershell
$env:UV_VERSION = (uv --version).Split(' ')[1]
docker compose up -d --build --wait
docker compose ps
docker compose stop
```

이 Compose 는 로컬용이다. 운영은 `infra/compose.yml` 의 `ai-worker` 서비스 — `internal` 네트워크만,
nginx 에 노출하지 않는다. 컨테이너에서 PC 의 백엔드는 `host.docker.internal:8080`.

### 운영 이미지 (S15P21C105-76)

`Dockerfile` 은 3단이다.

| 타깃 | 무엇 | 누가 쓰나 |
| --- | --- | --- |
| `base` | 의존성 + `app/` | 아래 둘의 공통 바탕 |
| `test` | dev 그룹 + 컨텍스트 전체 → `pytest` | CI (`--target test`). **실패하면 빌드가 실패한다** |
| `runtime` | 비특권 uid 10001 로 uvicorn | 배포 (**기본 타깃**) |

**torch 가 들어간다.** 리눅스에서는 CPU 전용 인덱스를 쓴다 — `pyproject.toml` 의
`[tool.uv.sources]`. PyPI 의 리눅스 휠은 CUDA 스택을 함께 끌고 오는데(락에 `nvidia-*`·
`cuda-*` 43개가 달려 있었다) 서버에 GPU 가 없다. **Windows·macOS 는 영향이 없다** — 그
의존이 전부 `sys_platform == 'linux'` 조건이라 원래 받지 않는다.

실측 (2026-09-09, 이 이미지로 확인):

```
런타임 이미지   1.67 GB   (torch 711 MB · cv2+libs 162 MB · sympy 30 MB)
상주 메모리     210 MiB   FORECAST_MODE=live · 모델 상주 후
추론              7 ms   contextDays 365 · horizonDays 30
이미지 안 테스트   207 통과 · 4 스킵
```

스킵 4건은 `tests/nlp` 의 카테고리 시드 대조다 — `backend/` 가 ai 빌드 컨텍스트에 없어서
건너뛴다. **CI 가 그 4건은 못 본다.**

**가중치는 이미지에 굽지 않는다.** `artifacts/` 는 커밋 금지라 Jenkins 가 clone 해도 빌드
컨텍스트에 없다. 서버에 파일을 두고 `/app/artifacts` 로 붙인다.

```bash
mkdir -p ~/app/ai-artifacts
scp ai/artifacts/residual-gru.pt deploy@your-server:~/app/ai-artifacts/
chmod 644 ~/app/ai-artifacts/residual-gru.pt      # 컨테이너 uid 10001 이 읽는다
# 그리고 secrets/ai.env 에 FORECAST_MODE=live
```

**마운트가 없어도 워커는 뜬다.** `load()` 실패를 기동 실패로 만들면 3번 파트의 파일 하나
때문에 1번(NLP)·2번(OCR)까지 내려간다. 예측만 「모델 미상주」가 되고 20.11 라우트가
`AI_ANALYSIS_FAILED` 를 돌려준다 (`app/main.py`). 이미지와 가중치가 엇갈리는 것은
**`modelVersion` 의 지문**(`0.3.0+a82bc981`)이 바뀌어 응답에서 드러난다.

## 운영 규칙 (기술스택 §8.7 체크리스트)

DB 직접 접속 금지 · 서비스 토큰 · X-Request-Id · 비동기는 Job + 콜백 · 동기 10초 · Circuit Breaker(연속 5회 → 60초,
API 서버 쪽) · 스위퍼(5분 주기, 10분 초과 재시도 1회) · 민감정보 로그 금지 · 외부 전송 전 서버에서 민감 필드 제거 ·
이중 검증(워커 Pydantic + 서버 DTO) · 결과에 모델·프롬프트 버전 · 모델 ID 고정.

## 예측 파트(3번) 현재 상태

| 엔드포인트 | 상태 |
| --- | --- |
| `POST /internal/v1/ai/forecast-residual` (20.11) | **구현** — `FORECAST_MODE=live` 로 켠다. 배포에서는 **가중치 볼륨도 붙여야 한다** (위 「운영 이미지」) |
| `POST /internal/v1/ai/settlement-delay` (20.12) | 미착수 (M-2 조건부) |
### 채택 판정: **구간 조건부 채택** (2026-09-08 재측정)

게이트는 `공백 F1 > 베이스라인` **그리고** `최초 부족일 오차 ≤ 베이스라인` 이다.
**컨텍스트 구간마다 판정이 갈린다.** 한 줄로 「채택/미채택」을 말할 수 없다.

| 과거 기록 | 통계 F1 | 모델 F1 | 차이 (95% CI) | 최초오차 | 게이트 |
| ---: | ---: | ---: | --- | --- | :---: |
| 7~30일 | 0.576 | **0.638** | +0.062 [+0.050, +0.073] | 9.85 → 10.72일 | **미통과** — 최초오차 악화 |
| **30일~1년** | 0.664 | **0.680** | +0.016 [+0.008, +0.024] | 9.27 → **9.14일** | **통과** |
| **365일** (창 상한) | **0.692** | 0.687 | −0.006 [−0.012, +0.001] | 9.10 → 8.99일 | **미통과** — F1 미달 |

**전체를 한 덩어리로 채점하면 미채택이다.** 5년치 합성 데이터에서는 대부분의 창이
컨텍스트가 상한(365일)까지 차 있고, 그 구간에서 통계가 이미 강하다.
**「모델이 통계를 이겼다」고 통째로 말하면 안 된다.**

계약 10.4 의 `activeVariant` 는 그래서 구간에 따라 갈린다 — **컨텍스트 365일**(창 상한) 실행은
`BASELINE`. **「기록이 1년 이상인 사업장」이 아니다** — 나누는 축은 워커에 넣은 일수다.
어느 쪽이든 **워커를 올려서 서비스가 나빠지지 않는다.** 그게 이 게이트의 요점이다.

전체 구간 상세:

| | 공백 F1 | 정밀도 | 재현율 | 최초 부족일 오차 |
| --- | ---: | ---: | ---: | ---: |
| 통계 베이스라인 (요일 중앙값) | **0.692** | **0.770** | 0.629 | 9.10일 |
| 우리 GRU (88,390개) | 0.687 | 0.717 | **0.658** | **8.99일** |

시간 hold-out 창 9,600개 · 사업장 200곳 · 판정 α=0.00 · 학습 데이터 지문 `a82bc981`

> **창 9,600개는 독립 표본 9,600개가 아니다.** 창 간격이 7일이라 정답이 창끼리 23/30
> 겹친다 — 겹치지 않는 30일 뭉치로 환산하면 **2,200개 규모**다.
>
> **구간 A/B/C 는 부분집합이 아니다.** 같은 9,600개 창의 **컨텍스트를 잘라** 만든다.
> 그래서 구간 비교가 「가게가 달라서 생긴 차이」를 재지 않는다.

**판정을 두 방향으로 흔들어 봤다** (2026-09-10).

| 무엇을 흔들었나 | 결과 |
| --- | --- |
| **공백 기준선** — 0원이 아니라 시작잔액의 10·25·50·75% | **다섯 자리 전부 유의하게 통과.** 안전선은 사용자가 정하는 값인데(`SAFETY_BALANCE_POLICY`) **어디에 둬도 채택이 유지된다** |
| **판정 보수성 α** — 0.00 / 0.05 / 0.10 | **0.00 에서만 통과.** 계약에 못박아야 한다 |

**둘의 성격이 반대다.** α 는 응답에 실어 보내야 하지만(`judgmentAlpha`), 기준선은
워커가 알 필요조차 없다 — 분위수를 그대로 주고 판정은 API 서버가 사용자 값으로 한다.

**그리고 +0.016 의 실체는 한 칸이다.** 창을 「곡선에서 잔여가 차지하는 몫」으로 나눠
다시 채점하면 (재학습 없이 · 지문 그대로) —

| 잔여 몫 | 창 | 통계 | 우리 GRU | 차이 |
| ---: | ---: | ---: | ---: | --- |
| 0.10~0.20 | 831 | 0.880 | 0.879 | 차이 없음 |
| 0.20~0.30 | 2,742 | 0.790 | 0.798 | 차이 없음 |
| **0.30~0.45** | **4,450** | 0.567 | **0.590** | **+0.024 유의** |
| 0.45~1.00 | 1,576 | 0.436 | 0.442 | 차이 없음 |

**「잔여가 클수록 모델이 낫다」는 관계는 없다.** 0.45 이상에서는 통계와 모델이 **같이**
무너진다 — 예측 불가능한 몫이 느는 것이지 모델이 그걸 잡아내는 게 아니다.

### 왜 이 정도인가 — 바닥과 천장

```
① 예정 거래만 (예측 아예 안 함)      0.457   ← 곡선의 대부분이 예정거래라 공짜로 나온다
② 잔여 = 마지막 7일 평균            0.529
③ 잔여 = 과거 평균 (가게별 상수 하나)  0.677
④ 통계 (요일별 분위수)              0.692
⑤ 우리 GRU (88,390개)             0.687
⑥ LightGBM 분위수                 0.697   ← 근소하게 최고. 배포 안 함 (아래)
⑦ 모수 오라클 (요일 참값을 본다)       0.733   ← **부정 팔.** 요일 축의 실질 상한
⑧ 정답 잔여를 그대로 넣으면           1.000   ← 천장
```

**③을 반드시 같이 봐야 한다.** 가게별 상수 하나가 0.677 이다 — 신경망과 0.010 차이다.
「그거 그냥 평균 아닌가요?」에 답하려면 이 표가 필요하다.

**⑦이 더 중요하다.** 요일별 참값을 직접 보는(=미래를 아는) 부정 팔이 0.733 에서 멈춘다.
④~⑥이 0.69~0.70 이라는 건 **요일 축에서 짜낼 것이 거의 안 남았다**는 뜻이다.
남은 0.267 은 요일로 설명되지 않는 부분이고, 지금 데이터에는 그 신호가 없다
(`최근7일평균` 0.529 — 생성기에 자기상관을 넣지 않았다).

**⑦은 순환논법 점검도 겸한다.** 우리 GRU 는 우리 생성 규칙으로 학습하므로 정보 상한이
그 규칙이다. GRU 가 0.687 로 **⑦에 못 미친다**는 것이 「생성 규칙을 외우지 않았다」는
증거다. 0.733 에 붙었다면 그게 순환논법의 증거였다.

### 왜 GRU 인가 — 큰 모델을 안 쓴 이유

| 팔 | 파라미터 | 전체 F1 |
| --- | ---: | ---: |
| Chronos zero-shot | 48M | **0.600** ← 통계보다 −0.092 [−0.105, −0.080] **유의하게 나쁨** |
| Chronos fine-tune | 48M | 0.687 |
| **우리 GRU** | **88K** | **0.687** |

**48M 파운데이션 모델과 88K 자체 모델이 0.6867 로 소수점 넷째 자리까지 같다.**
병목은 용량이 아니라 데이터에 실제로 들어 있는 신호다. 그래서 TimesFM(200M) 을 얹지 않았다.
메모리도 이유다 — 상주 203 MiB 중 **torch 런타임이 182**, 모델은 7 이다.
200M 이면 가중치 800 MiB 가 이 위에 얹히고, 4 vCPU·GPU 없는 서버에서 OCR 과 같이 돈다.

**LightGBM(⑥ 0.697)이 근소하게 낫지만 배포하지 않았다.** 통계 대비
`+0.005 [−0.000, +0.010]` 로 **판정 보류**이고, 워커 계약이 GRU 아티팩트를 전제한다.
뒤집을 만한 차이가 아니다.

### 표본 하한은 30일 하나다

| | 하한 | 미달이면 |
| --- | ---: | --- |
| 워커 (`features.MIN_HISTORY`) | **30일** | `422` 로 거절 |
| 통계 베이스라인 (`baseline.MIN_SAMPLE_DAYS`) | **30일** | `UNAVAILABLE` + 잔여 0 |
| 신뢰도 강등 (`baseline.DEGRADED_SAMPLE_DAYS`) | 180일 미만 | `DEGRADED` — 값은 낸다 |

**2026-09-09 에 통계 하한을 90 → 30 으로 내렸다.** 이전에는 30~89일에서 통계가 침묵해
모델도 통계도 못 쓰고 잔여 0(F1 0.457)으로 떨어졌다. 그 구간 실측이 모델 **0.676** ·
통계 0.652 였고, 최초 부족일 오차가 **19.4일 → 9.3일**로 절반 이하가 된다.

**싸피 금융망 계좌는 프로젝트 중에 만든 것이라 기록이 30~60일이다** — 서비스가 실제로
도는 구간이 바로 여기고, 모델이 통계를 유의하게 넘는 구간이기도 하다.

### ⚠ 성능을 지배하는 것은 모델이 아니라 **P0-03 반복 거래 탐지**다

우리가 받는 것은 잔여(`실제 − 예정 매칭분`)이고, 그 뺄셈은 **BE 의 P0-03** 이 해 준다.
지금 학습 데이터는 생성기가 정답을 알려줘 **100% 정확히 차감**한 것이다 — 실서비스에서는
추정이므로 그만큼이 안 된다. **학습·서빙 불일치**다.

| P0-03 재현율 | 공백 F1 | 최초 부족일 오차 |
| ---: | ---: | ---: |
| 100% (지금 학습 조건) | 0.687 | 8.99일 |
| **95%** | **0.623** | **10.75일** |
| 90% | 0.555 | 13.30일 |
| **0%** (P0-03 없음) | **0.029** | 29.14일 |

**모델을 고쳐 얻은 것이 +0.016 인데 탐지 5%p 하락이 −0.064 다.** 기울기가 4배 차이다.
**다음 성능은 모델이 아니라 P0-03 에서 나온다.**

오탐은 대가가 작다 — 미탐 5%가 **−0.064**(창 9,600개)인 반면 오탐 28%는 방향만 확인됐다 (그 표는 창이 63개라 값을 못 붙인다 — 설계노트 M-3).
그래서 **재현율 95% 이상을 요구하고 정밀도는 느슨하게** 둔다.

근거는 [`docs/예측엔진_설계노트.md`](../docs/예측엔진_설계노트.md) M-3 「모델보다 탐지
재현율이 성능을 지배한다」, 재현은 `private/현재/forecast-lab/audit/recall.py`·`pipeline.py`.

### 닫은 방향 — 다시 파지 않는다

| 시도 | 결과 |
| --- | --- |
| 채널 분해 (매출·식자재·주류 등 8종) | −0.0011. 완벽 분류를 가정해도 효과 없음 |
| 부족확률 분류 헤드 (τ 독립 튜닝) | 게이트 미달 |
| 사업장 수 증설 (25→200곳) | 학습곡선이 **50곳부터 평평** |
| zero-shot 파운데이션 모델 | −0.092 유의하게 나쁨 |
| **잔여 비중이 높은 분포로 재학습** | **착수 전에 접었다** (2026-09-10) — 위 잔여 몫 표가 「잔여가 커도 모델이 더 이기지는 않는다」를 보인다. 게다가 그런 가게(카드가 매 영업일 입금)는 `BUSINESS_DAILY` 를 켜면 **잔여 몫이 0.000** 이 되어 맞힐 것이 남지 않는다. **재학습이 아니라 탐지를 고치는 것이 답이었다** |

> **이 숫자는 전부 합성 데이터에서 잰 것이다.** 「합성 벤치마크에서 베이스라인 대비」라고만
> 말해야 하고, 「정확도가 좋다」가 아니다. 평가에 미래 예정 거래를 실제 금액으로 넣었으므로
> 바닥(①)조차 부풀려져 있다. **실제 통장 데이터로 재측정하기 전까지 절대 성능을 인용하지 않는다.**
>
> 판단 근거 전문은 [`docs/예측엔진_설계노트.md`](../docs/예측엔진_설계노트.md) M-3 「실측 결과」.

### 모델 아티팩트를 만드는 법

실험 코드는 레포 밖이다 (`private/현재/forecast-lab/`, 담당 이한호).
**명령을 여기 옮겨 적지 않는다** — 재구성될 때마다 어긋난다.
실행 방법·폴더 구조·안전장치는 그쪽 `README.md` 가 정본이다.

```powershell
cd ..\..\private\현재\forecast-lab
.\.venv-gpu\Scripts\python.exe models\gru_v1.py   # 학습 (GPU, 51초)
.\.venv\Scripts\python.exe evaluate.py --bands     # 구간별 성적표 + HTML
```

`project/ai/artifacts/residual-gru.pt` 로 떨어지고 워커가 그걸 읽는다.
**설정이 코드와 다르면 기동에 실패한다** — 조용히 틀린 숫자를 내는 것보다 낫다.

`modelVersion` 은 `MODEL_VERSION` 에 **학습 데이터 지문**을 붙여 나간다
(`0.3.0+a82bc981`). 구조를 안 바꾸고 데이터만 바꿔 재학습하면 버전이 그대로여서
계약 20.11 의 재현성 보증이 **조용히 깨지기** 때문이다. 실제로 그럴 뻔했다.

실험 이력·다음에 손볼 코드는 `private/현재/forecast-lab/현황.md`,
티켓 대조는 `지라.md` 에 있다.
