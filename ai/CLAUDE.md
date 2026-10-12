# AI 워커 작업 안내

Claude Code 가 `ai/` 에서 작업할 때 자동으로 읽는 파일이다. **안내판이고 정본이 아니다** —
내용을 여기에 복사하면 원본과 어긋난다. 아래 문서들이 정본이다.

| 무엇을 알고 싶은가 | 정본 |
| --- | --- |
| 구조·공통 계약·환경 변수·담당 | `ai/README.md` |
| 워커 API 계약 (요청·응답·콜백) | `docs/api/20_내부_AI워커.md` |
| 기능별 판단 근거·기술 선정 | `docs/기능별_기술스택_선정.md` |
| 거래 분류 평가 세트 | `ai/evals/p1-01/README.md` |
| 거래 CSV 를 만드는 규격 | `ai/evals/p1-01/거래_CSV_생성_규격.md` |
| 적요에 쓸 실제 은행 표기 | `ai/evals/p1-01/적요_표기_목록.md` |
| 라벨 300건의 분포와 출처 | `ai/evals/p1-01/라벨_분포표.md` |
| 마지막 채점 결과 | `ai/evals/p1-01/report.json` |
| 작업 단위·진행 상태 | Jira 에픽 `S15P21C105-83` |

---

## 절대 하지 말 것

레포를 봐도 알 수 없고, 어기면 조용히 망가지는 것들이다.

### 1. 평가 라벨을 대신 만들지 마라

`ai/evals/p1-01/labels.jsonl` 의 `category`·`merchantName`·`note` 를 **Claude 가 채우지
않는다.** 평가 대상 모델이 정답을 만들면 "모델이 모델을 채점하는" 셋이 되어 점수가
아무것도 재지 못한다.

**300/300 채워져 있다** (`-92`, 2026-09-10 머지). 채운 방법이 곧 이 규칙의 실행 예다 —
`(적요, 방향, 금액밴드)` 110그룹으로 묶어 GPT·Gemini 에 각각 물었고(`evals/triage.py`),
93% 는 두 모델이 일치해 그대로 채택, 불일치 11그룹은 팀 문서 `적요_표기_목록.md` 의 해당
절로 판정했다. **Claude 판단은 0건이다.** 출처 기록은 `evals/p1-01/라벨_분포표.md`.

라벨을 다시 만들어야 할 때가 온다 — 백엔드가 카테고리를 바꾸거나 실제 시드가 들어올 때다.
그때도 같은 규칙이다.

### 2. 거래 CSV 에 정답을 넣지 마라

`ai/datasets/` 의 시드 CSV 에는 **카테고리 열이 없다.** 일부러 뺐다.

정답을 아는 사람이 정답을 만들면 평가가 "LLM 이 내 생성 규칙을 되짚을 수 있는가" 를
재게 된다. 적요에 카테고리 이름(`임차료`·`MATERIALS`)을 넣는 것도 같은 이유로 금지다 —
모델이 그대로 베껴 100점이 나오는데 아무것도 검증하지 못한다.

### 3. 프롬프트를 바꾸면 A/B 를 먼저 돌려라

**규칙 한 줄을 더하면 엉뚱한 데가 망가진다. 두 번 겪었다.**

| 시도 | 더한 것 | 품목 금액 |
| --- | --- | ---: |
| v4 | (기준) | 86.4% |
| v5 | 품목 개수 정렬 규칙 | 57.6% |
| v6 | 문서 종류 판별 규칙 + 출력 필드 | 69.0% |

둘 다 **품목과 직교해 보이는** 규칙이었고 둘 다 되돌렸다. 원인은 품목 행의 숫자가 이웃 행
것으로 밀리는 것이다. 직교해 보인다는 판단을 믿지 말고 **15장 A/B 를 먼저 돌린다**
(`experiments/evaluate_evidence.py`, 약 $0.19, 기준선은 `artifacts/eval/` 의 직전 실행).

기준선과 **프롬프트만 다르게** 맞춘다 — 모델·effort·이미지 크기·타일링이 같아야 A/B 다.

### 4. 커밋하면 안 되는 것

| 경로 | 이유 |
| --- | --- |
| `ai/datasets/` | 거래 데이터. `.gitignore:16` |
| `ai/evals/**/*.xlsx` | 라벨링 작업 파일. 정본은 `labels.jsonl` — 바이너리는 diff 로 라벨 수정 이력을 리뷰할 수 없고, 두 사람이 같이 채우면 병합이 불가능하다 |
| `ai/artifacts/` | 산출물 |
| `docs/AI워커_응답계약_노트.md` | **로컬 전용으로 유지하기로 정했다.** 아래 참고 |

`ai/evals/` 는 **커밋 대상이다.** 적요에 사람 이름이 들어가므로, 실제 계좌에서 뽑은
데이터면 가명화하거나 `ai/datasets/` 로 옮긴다.

### 5. 숫자 계산을 LLM 에 맡기지 마라

LLM 은 **분류·추출·구조화·문장화** 네 가지만 한다. 잔액·세금·대출 계산은 백엔드가
한다 — 재현성 요구 때문이다. 워커는 DB 에도 닿지 않는다.

---

## 명령

`uv` 를 쓴다. `pip` 을 쓰지 않는다. Python 은 3.12 고정(`.python-version`).

```bash
uv run --frozen pytest -q                      # 전체
uv run --frozen pytest tests/nlp -q            # 1번 파트만
uv run --frozen ruff check app tests evals
uv run --frozen ruff format app tests evals
```

한글 출력이 콘솔에서 깨지면 `PYTHONIOENCODING=utf-8 PYTHONUTF8=1` 을 붙인다.
파일은 정상이고 콘솔 코드페이지 문제다.

**`pytest` 는 외부 API 를 부르지 않는다.** 점수 게이트도 커밋된 `report.json` 만 읽는다.
이 구조를 깨지 마라 — 테스트가 유료가 되면 아무도 안 돌린다.

채점은 별도 명령이고 **돈이 든다** (300건 1회 약 $0.31, 3회 약 11분):

```bash
uv run --frozen python -X utf8 -m evals.run_eval p1-01 --repeat 3   # 유료
uv run --frozen python -X utf8 -m evals.run_eval p1-01 --dry-run    # 호출 없이 요청만 확인
```

`report.json` 은 채점 결과이자 게이트의 입력이다. 손으로 고치지 마라.

### 브랜치·커밋

```
feature/{jira_key}-{feature_name}      예: feature/S15P21C105-92-classification-eval-set
```

커밋 타입: `feat` `fix` `build` `chore` `ci` `docs` `style` `refactor` `test` `perf`.
본문 끝에 반드시 붙인다 — `-48` 부터 팀이 써 온 형식이다.

```
Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>
```

MR 은 `develop` 대상. 푸시 시 `-o merge_request.create -o merge_request.target=develop`.

---

## 1번 파트(NLP) 현재 상태

담당: 장원근 (거래 분류 · 브리핑 · 신고서 초안 점검). **공고 구조화(20.6)와 Batch(`-97`)는
2026-09-16 에 박민혁이 넘겨받았다** — 지원 제도(P1-15) 백엔드와 같이 움직여야 해서다.

| 항목 | 상태 |
| --- | --- |
| 거래 분류 요청·응답 스키마 (`app/nlp/schemas.py`) | **완료·머지됨** (`-85`). 카테고리 23종 + 방향표 + 배치 검증 |
| 라우트 4종 + OpenAPI 발행 (`app/nlp/router.py`) | **완료·머지됨** (`-89`) |
| 평가 세트 도구 (`evals/sample.py` `check.py` `sheet.py` `labels.py` `triage.py` `rewrite.py`) | **완료·머지됨** (`-92`, MR !16) |
| 평가 세트 라벨 300건 | **완료·머지됨** (`-92`, MR !53). 300/300 |
| 채점 도구·점수 게이트 (`evals/run_eval.py` · `tests/nlp/test_eval_report.py`) | **완료·머지됨** (`-93`, MR !53) |
| 분류 파이프라인 실호출 (`app/nlp/pipeline.py`) | **완료** (`-91`). Circuit Breaker 도 붙었다(`app/common/breaker.py`) |
| 공고 구조화 · 브리핑 · 초안 점검 스키마 | **완료·머지됨** (`-86` `-87` `-88`) |
| LLM 파이프라인 4종 | **완료.** `ClaudeClassifier` · `ClaudeBriefer` · `ClaudeStructurer` · `ClaudeAssistant` |
| 공고 구조화 평가 세트 (`evals/p1-15/`) | **완료·머지됨** (`-211`). 실제 공고 32건 |
| 공고 구조화 실호출 (`-94`) | **완료·머지됨** (2026-09-16). Haiku 4.5 · 평가 통과 · 실제 비용 $0.2091 |

**`build_structurer` 가 `live` 에서도 stub 을 돌려주던 것이 오래 남아 있었다.** 수집·적재·
Job·콜백·검색·상세가 다 도는데 「공고 분석」은 한 건도 일어나지 않던 이유가 그 한 줄이다
(`-94` 에서 고쳤다). **다음에 파이프라인을 더할 때 `build_*` 분기를 먼저 확인해라** —
응답의 `pipeline` 이 `STUB` 이라 계약상 거짓은 아니지만, 아무도 그 필드를 안 본다.

### 모델·프롬프트 — 실측으로 정한 값이다

| 무엇 | 값 | 어디 |
| --- | --- | --- |
| 분류 기본 모델 | `claude-haiku-4-5-20251001` | `app/config.py` `classification_model` |
| 구조화 기본 모델 | `claude-haiku-4-5-20251001` | `app/config.py` `structuring_model` |
| 분류 프롬프트 | `transaction-classification/v2` | `app/nlp/prompts.py` |
| 구조화 프롬프트 | `program-structuring/v1` | `app/nlp/prompts.py` |
| 분류 점수 | Macro F1 0.9226 · 수락률 0.9927 (300건 × 3회) | `evals/p1-01/report.json` |
| 구조화 판정 | 근거 없는 값 0 · 과신 0% · 전부확인필요 3% | `evals/p1-15/report.json` |

⚠ **`p1-01/report.json` 은 Sonnet 기준이고 기본값은 Haiku 다** (`-193` 에서 기본을 내렸다).
**두 값을 같은 것으로 말하지 마라** — 발표 자료에 0.9226 을 쓰면서 「기본 설정의 점수」라고
하면 거짓이다. 근거 전체는 `app/config.py` 의 주석에 있다.

**모델을 바꾸려면 재 보고 바꿔라.** Haiku 4.5 는 분류 3회 평균 0.8661 로 기준 0.85 를 넘지만
최저 회차가 0.8512 다 — 여유가 +0.0012 라 다시 돌리면 게이트가 빨개진다. 구조화 쪽은
반대로 **Haiku 로 충분하다는 것이 실측으로 나왔다**(`evals/p1-15`, 1회 $0.21).
**Opus 5 는 두 경로 모두에서 쓰지 않는다** (비용 6 배 · 기술스택 6.6 의 중첩 조건 근거는
전수 조사에서 5% 였다).

프롬프트를 고칠 때 지킬 것: 규칙은 `evals/p1-01/적요_표기_목록.md` 에서만 가져오고,
**평가 세트에 실제로 있는 상호명은 프롬프트에 적지 않는다.** 그건 판단 규칙이 아니라
정답지 암기여서 점수만 오르고 처음 보는 거래처에는 도움이 안 된다.

### 평가 세트의 한계 — 반드시 알아야 한다

현재 300건은 **합성 데이터**다. 실제 거래 데이터가 없어서 직접 만들었다.
금융망이 과거 날짜 거래를 만들 수 없고(`기능별_기술스택_선정.md` §4.1.1), CSV 시드도
아직 없다.

적요·금액·주기가 실제 통장보다 깔끔해서 **점수가 실제보다 높게 나온다.**
프롬프트·모델 A/B 비교에는 쓸 수 있고, `Macro F1 0.85 달성` 같은 절대 판정에는 쓸 수 없다.
`evals.check` 와 `report.json` 의 `caveat` 가 이 경고를 매번 낸다.
실제 시드가 오면 CSV 만 바꿔 다시 뽑고, **라벨과 `report.json` 을 함께 다시 만든다.**

그래서 현재 0.9226 이라는 숫자는 "기준 통과"가 아니라 **"v1 대비 개선됐고 Haiku 대비
낫다"** 까지만 말한다. 대외 자료에 절대 수치로 쓰지 마라.

---

## 백엔드와의 결합점 — 드리프트 위험

값 집합을 백엔드에서 **복제**해 왔다. 워커는 백엔드를 호출하지 않지만 값이 어긋나면 깨진다.

| 결합점 | 내 쪽 | 백엔드 쪽 | 자동 감지 |
| --- | --- | --- | --- |
| 카테고리 23종 + 방향 | `app/nlp/schemas.py` `CATEGORY_DIRECTION` | `V2__seed_transaction_category.sql` | **있음** — `tests/nlp/test_category_seed_parity.py` |
| 업종 7종 | `IndustryType` | `BusinessDtos.java` 정규식 | **없음** |
| CSV 헤더·날짜 형식 | `evals/sample.py` | `CsvTransactionParser.java` (명세 §7.4) | **없음** |
| 분류 라우트·`BATCH_MAX=20` | 내가 제안 | **참조 없음 — 미합의** | — |

`_assert_category_table_complete()` 는 **내부 정합성만** 본다(Literal 과 방향표가 서로
맞는지까지). 백엔드 SQL 대조는 `test_category_seed_parity.py` 가 따로 한다 — 시드 SQL 을
직접 파싱해 코드·방향·입출금 개수를 맞춘다. 시드 SQL 이 정본이고 워커가 따라가는 쪽이다.

**이 테스트가 깨지면 라벨 300건도 같이 봐야 한다.** 새 카테고리에 해당하는 건이 기존
라벨에 다른 이름으로 들어가 있을 수 있고, 그러면 `report.json` 도 다시 만들어야 한다.

나머지 셋은 아직 조용히 어긋날 수 있다. 특히 마지막 항목은 코드로 해결할 수 없다 —
백엔드 담당과 **합의**해야 한다. 필요한 것: 라우트 경로, `allowedCategories` 를 요청에
넣을지, 배치 최대 크기 20.

---

## 레포로 전해지지 않는 것

`docs/AI워커_응답계약_노트.md` — 워커 응답 계약의 판단 기록(`-84`)이다.
**로컬 전용으로 유지하기로 정했고 커밋되지 않았다.** 내용이 필요하면 장원근에게 요청한다.
추측으로 재작성하지 마라.

`ai/datasets/gen_seed.py` — 합성 시드 생성기. gitignore 대상이라 레포에 없다.
그 지식은 `evals/p1-01/거래_CSV_생성_규격.md` 로 빼 두었으니 그것을 본다.
