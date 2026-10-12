# p1-01 거래 분류 평가 세트

거래 적요를 카테고리·거래처로 분류하는 기능의 **고정 정답셋**이다. 사람이 라벨링한 300건.

이게 있어야 프롬프트를 고쳤을 때 좋아졌는지 알 수 있다. 없으면 모델 탓인지 프롬프트 탓인지
구분할 기준선이 없다.

| 항목 | 값 |
| --- | --- |
| 목표 건수 | **300** |
| 라벨 파일 | `labels.jsonl` (한 줄에 한 건) — **정본** |
| 라벨링 도구 | `labels.xlsx` (드롭다운 시트, git 에 올리지 않음) |
| 통과 기준 | Macro F1 0.85 이상 · 사용자 수락률 80% 이상 |
| 채점 도구 | 별도 스토리 (`evals/run_eval.py`) |

---

## 1. 표본 뽑기

거래 CSV를 준비한다. **업로드 규격과 같은 형식**이다 — 거래일시·적요·입금액·출금액·거래후잔액.
한글 헤더와 영문 헤더 모두 받는다.

```powershell
uv run --frozen python -m evals.sample .\bank.csv --industry FOOD_CAFE --count 300
```

`seed`가 고정돼 있어 같은 CSV·같은 seed면 같은 표본이 나온다. 표본을 다시 뽑아야 할 때
재현된다.

**카테고리로 층화하지 않는다.** 카테고리는 라벨링 결과이지 입력이 아니다. 라벨링 전에
카테고리를 알고 뽑으면 정답을 미리 정해 두고 표본을 고르는 셈이 되어 평가가 무의미해진다.
그래서 관측 가능한 축(방향·금액 구간·적요 중복도)으로만 층화하고, **카테고리 균형은
라벨링이 끝난 뒤 확인**한다. 이 순서가 이 작업의 본질적 제약이다.

## 2. 라벨링

스프레드시트로 한다. 300건을 편집기에서 치는 것보다 빠르고, 드롭다운이 오타와
방향 불일치를 원천 차단한다.

```powershell
uv run --frozen python -m evals.sheet export p1-01   # labels.jsonl → labels.xlsx
# Excel 에서 category·거래처명·메모 를 채운다
uv run --frozen python -m evals.sheet import p1-01   # labels.xlsx → labels.jsonl
```

`export`는 기존 xlsx를 덮어쓰지 않는다(`--force` 필요). 중간에 끊었다가 다시 내보내면
이미 채운 라벨이 시트에 남아 있다. `import`는 규격 위반이 하나라도 있으면
**아무것도 쓰지 않고** 위반한 `transactionId`와 사유를 낸다.

**정본은 `labels.jsonl`이다.** xlsx는 입력 도구이고 `.gitignore`에 있다 — 바이너리라
diff로 라벨 수정 이력을 리뷰할 수 없고, 두 사람이 같이 채우면 병합이 불가능하다.
`import`는 xlsx에서 **라벨 3칸만** 가져오고 거래일시·금액 같은 맥락 열은 jsonl에서
다시 읽는다. Excel이 날짜 서식을 제 맘대로 바꿔도 정본이 다치지 않는다.

각 줄에서 채우는 **세 칸**:

| 칸 | 채우는 것 |
| --- | --- |
| `category` | 23종 중 하나. 방향이 맞아야 한다(입금 거래에 출금 카테고리를 붙이면 파일 읽기가 실패한다) |
| `merchantName` | 추정 거래처. 모르면 `null` |
| `note` | 판단이 애매했던 이유. 나중에 오답 분석에서 프롬프트 수정의 단서가 된다 |

**보이는 것만으로 판단한다.** `description`·`direction`·`amount`·`occurredAt`·`cycleHint`뿐이다.
금융망이 그것밖에 주지 않기 때문이다 — 응답에 거래처명 필드가 없고 적요 한 줄이 전부다.
DB를 열어 보거나 다른 단서를 쓰면 **모델이 도달할 수 없는 정답**이 되어 평가가 불공정해진다.

애매하면 `note`에 이유를 남기고 가장 그럴듯한 것을 고른다. 비워 두면 그 건은 채점에서 빠진다.

## 3. 수시 점검

```powershell
uv run --frozen python -m evals.check p1-01
```

진행률, 방향·업종·카테고리 분포, 남은 `transactionId`를 낸다. 규격을 어긴 줄이 있으면
줄 번호와 사유를 내고 종료 코드 1로 끝난다.

경고를 내는 경우:

- 한 카테고리가 40%를 넘으면 — Macro F1이 그 카테고리 하나로 흔들린다
- 3건 미만 카테고리가 있으면 — 카테고리별 F1이 불안정하다
- 표본에 없는 카테고리 — 채점에서 제외된다

**건수를 세면서 진행한다.** 스프린트 이월 정책이 "의미 있는 산출물이 남으면 분할"이라,
중간에 끊길 때 몇 건까지 했는지가 바로 나와야 한다.

## 4. 카테고리 23종

`app/nlp/schemas.py`의 `TransactionCategory`가 단일 기준이고, 백엔드 카테고리 시드
마이그레이션과 방향까지 일치함을 확인했다.

| 방향 | 카테고리 |
| --- | --- |
| 입금 (7) | `CARD_SETTLEMENT` `PLATFORM_SETTLEMENT` `CASH_SALES` `TRANSFER_SALES` `LOAN_DISBURSEMENT` `OWNER_FUNDING` `OTHER_INCOME` |
| 출금 (16) | `RENT` `PAYROLL` `SOCIAL_INSURANCE` `MATERIALS` `UTILITIES` `TELECOM` `ADVERTISING` `PLATFORM_FEE` `INSURANCE` `SUBSCRIPTION` `LOAN_REPAYMENT` `TAX` `SUPPLIES` `EQUIPMENT` `PERSONAL` `OTHER_EXPENSE` |

## 5. 개인정보 — 커밋 전 확인

적요에 사람 이름이 들어간다. 임대인 이름이 적요에 그대로 찍히는 것이 흔하다.

**`ai/evals/`는 커밋 대상이다.** `ai/datasets/`·`ai/artifacts/`와 달리 gitignore되지 않는다.

| 원본 | 처리 |
| --- | --- |
| 시드 생성기가 만든 가명 | 그대로 커밋해도 된다 |
| **실제 계좌에서 뽑은 것** | **커밋하지 않는다.** 가명화하거나 `ai/datasets/`로 옮긴다 |

기능 명세가 개인정보 최소수집을 비기능 요구로 두고 있다. 이 확인 없이 커밋하지 않는다.
