"""거래 분류 live 경로 스모크 — 키·모델·구조화 출력·계약 검증이 실제로 도는지 본다.

    uv run --frozen python -X utf8 -m scripts.smoke_classification
    uv run --frozen python -X utf8 -m scripts.smoke_classification --model claude-haiku-4-5
    uv run --frozen python -X utf8 -m scripts.smoke_classification --csv 거래.csv --labels 라벨.csv

`--csv` 를 주면 내장 픽스처 대신 그 CSV 의 `(적요, 방향)` **대표 1건씩**을 쓴다. 311건을 전량
보내면 배치가 16회지만 대표만 뽑으면 **1회로 모든 적요와 함정을 친다.** 주기 힌트는
`evals.sample` 의 반복 탐지가 붙이므로 워커가 실제로 받는 값과 같은 모양이다.

`-X utf8` 은 Windows 콘솔에서 한글이 깨지지 않게 한다(없으면 UnicodeEncodeError).
`ANTHROPIC_API_KEY`(또는 게이트웨이 경로면 `ANTHROPIC_BASE_URL` + `ANTHROPIC_AUTH_TOKEN`)가
`.env` 나 환경 변수에 있어야 한다. 호출 1회분 비용이 발생한다.

`AI_PIPELINE_MODE` 와 무관하게 **항상 live(`ClaudeClassifier`)로 간다** — stub 을 스모크해도
아무것도 확인되지 않기 때문이다. `smoke_vlm.py` 가 파이프라인을 직접 고르는 것과 같다.

## 이 스크립트가 재는 것과 재지 않는 것

재는 것 — 호출이 뚫리는가, 구조화 출력이 계약(`_LlmClassification`)에 맞는가, 라우터가 쓰는
폐기·누락 검사(`invalid_results` · `missing_transaction_ids`)를 통과하는가, 지연·토큰이 얼마인가.

**재지 않는 것 — 정확도.** 기대값을 눈으로 대조하라고 같이 찍지만 **정확률을 합산하지 않는다.**
20건 안팎의 표본에서 나온 숫자는 반드시 절대 점수로 인용되고, 그러면 평가 세트가 존재할 이유가
사라진다. 점수는 라벨 300건 + `evals/run_eval.py` 로만 낸다.

`--csv` 모드의 기대값도 마찬가지다 — 라벨 CSV 의 카테고리는 **생성기 의도이지 사람이 검증한
정답이 아니다.** 나온 일치율은 「정확도」가 아니라 「라벨 CSV 와의 일치율」이라고 불러야 한다.

## 왜 평가 세트를 쓰지 않는가

`ai/README.md` — *"실측할 때는 평가 세트 300건을 쓰지 말 것. 출력을 사람이 보면 라벨링이 모델
답에 끌려간다."* 라벨이 0/300 이라 아직 한 건도 안 채워졌고, 여기서 모델 답을 먼저 보면 그 오염이
그대로 일어난다. 그래서 적요·인물·금액을 전부 다르게 새로 지었다 — 시드 생성기의 박서준·김영자·
이수진과 겹치지 않는다.

함정 5개(`evals/p1-01/적요_표기_목록.md`)는 전부 넣었다. 같은 적요가 방향·금액·주기로 갈리는
사례들이고, 이게 이 기능의 실제 난이도다.
"""

from __future__ import annotations

import argparse
import asyncio
import csv
import json
import os
import time
from pathlib import Path

from app.common.claude import ClaudeClient
from app.config import Settings
from app.nlp.pipeline import ClassificationError, ClaudeClassifier
from app.nlp.schemas import (
    BATCH_MAX,
    CATEGORY_DIRECTION,
    BusinessContext,
    TransactionClassificationRequest,
    invalid_results,
    missing_transaction_ids,
)

# 실제 호출은 업종별 부분집합을 보내지만(§20.3), 스모크는 **23종 전부**를 허용한다.
#
# 손으로 20종을 적었다가 `TAX` 를 빠뜨렸고, 모델이 `국세청 → TAX` 로 맞게 답한 것을
# `invalid_results()` 가 「허용 목록 밖」으로 폐기했다. 폐기 검사는 제대로 돈 것이고
# **목록 쪽이 틀렸다.** 스모크에서 재려는 것은 모델이지 내 손타자가 아니므로
# 표에서 직접 만들어 다시는 어긋나지 않게 한다.
ALLOWED = list(CATEGORY_DIRECTION)

# 어느 적요가 어떤 함정인지. 실패했을 때 「무엇을 재던 건이 깨졌나」를 바로 읽으려고 둔다.
# 행 번호가 아니라 적요로 잡아서, 픽스처 순서를 바꿔도 어긋나지 않는다.
TRAPS = {
    "한지우": "함정1",
    "서말순": "함정2",
    "오현석": "함정2",
    "배수민": "함정2",
    "네이버": "함정3",
    "쿠팡": "함정4",
    "구글": "함정5",
}


# 라벨 CSV 를 안 준 칸. 불일치가 아니라 「모른다」이므로 대조에서 뺀다.
UNKNOWN = "?"


def monthly(day: int) -> str:
    """P0-03 반복 탐지가 붙여 주는 `cycleHint` 모양 (§20.3). LLM 이 주기를 추론하지 않게 한다."""
    return f"매월 {day}일 반복 (최근 4회)"


# 적요 · 방향 · 금액 · 발생일 · 주기힌트 · 눈으로 볼 기대값
# 기대값은 요청에 실리지 않는다. 모델에게 가는 것은 앞의 다섯 개뿐이다.
FIXTURE: list[tuple[str, str, int, str, str | None, str]] = [
    # 함정 1 — 사장님 본인 이름: 입금이면 자금 투입, 출금이면 개인 사용
    ("한지우", "DEPOSIT", 5_000_000, "2026-09-01", None, "OWNER_FUNDING"),
    ("한지우", "WITHDRAWAL", 300_000, "2026-09-03", None, "PERSONAL"),
    # 함정 2 — 개인 이름 출금: 같은 날 큰 금액 하나면 임차료, 여러 명이면 급여
    ("서말순", "WITHDRAWAL", 1_800_000, "2026-08-25", monthly(25), "RENT"),
    ("오현석", "WITHDRAWAL", 2_150_000, "2026-08-25", monthly(25), "PAYROLL"),
    ("배수민", "WITHDRAWAL", 1_980_000, "2026-08-25", monthly(25), "PAYROLL"),
    # 함정 3 — 네이버: 입금이면 예약 정산, 출금이면 광고
    ("네이버", "DEPOSIT", 388_000, "2026-09-02", None, "PLATFORM_SETTLEMENT"),
    ("네이버", "WITHDRAWAL", 550_000, "2026-09-02", None, "ADVERTISING"),
    # 함정 4 — 쿠팡: 입금이면 쿠팡이츠 정산, 출금이면 소모품
    ("쿠팡", "DEPOSIT", 612_000, "2026-09-04", None, "PLATFORM_SETTLEMENT"),
    ("쿠팡", "WITHDRAWAL", 47_300, "2026-09-04", None, "SUPPLIES"),
    # 함정 5 — 구글: 소액 정기면 구독, 큰 금액이면 광고
    ("구글", "WITHDRAWAL", 11_900, "2026-09-08", monthly(8), "SUBSCRIPTION"),
    ("구글", "WITHDRAWAL", 740_000, "2026-09-05", None, "ADVERTISING"),
    # 평범한 것들 — 함정만 넣으면 쉬운 건이 깨지는 회귀를 못 본다
    ("KB국민카드", "DEPOSIT", 843_200, "2026-09-07", None, "CARD_SETTLEMENT"),
    ("현금입금", "DEPOSIT", 156_000, "2026-09-06", None, "CASH_SALES"),
    ("한국전력", "WITHDRAWAL", 284_500, "2026-08-20", monthly(20), "UTILITIES"),
    ("SK텔레콤", "WITHDRAWAL", 68_900, "2026-08-15", monthly(15), "TELECOM"),
    ("(주)신선유통", "WITHDRAWAL", 1_342_000, "2026-09-03", None, "MATERIALS"),
    ("국민건강보험공단", "WITHDRAWAL", 412_300, "2026-08-10", monthly(10), "SOCIAL_INSURANCE"),
    ("대출이자", "WITHDRAWAL", 187_400, "2026-08-12", monthly(12), "LOAN_REPAYMENT"),
    ("삼성화재", "WITHDRAWAL", 96_000, "2026-08-05", monthly(5), "INSURANCE"),
    ("한일냉동", "WITHDRAWAL", 3_850_000, "2026-09-02", None, "EQUIPMENT"),
]


def rows_from_csv(tx_path: Path, label_path: Path | None, limit: int) -> list[tuple]:
    """거래 CSV + 라벨 CSV 에서 `(적요, 방향)` 대표 1건씩 뽑아 픽스처 모양으로 만든다.

    전량(311건)을 보내면 배치가 16회다. 대표만 뽑으면 **1회로 모든 적요와 함정을 친다** —
    같은 적요가 방향으로 갈리면(`우아한형제들`) 둘 다 남으므로 함정이 살아 있다.

    주기 힌트는 `evals.sample.attach_cycle_hints` 로 붙인다. 워커가 받는 `cycleHint` 는
    P0-03 반복 탐지가 계산한 값이고, 여기서도 같은 계산기를 쓴다.

    라벨 CSV 의 카테고리는 **생성기 의도이지 사람이 검증한 정답이 아니다.** 그래서 이 값은
    「기대값」으로만 옆에 찍고 점수를 내지 않는다 (모듈 docstring 참고).
    """
    from evals.sample import attach_cycle_hints, read_csv

    rows = read_csv(tx_path)
    attach_cycle_hints(rows)

    labels: dict[tuple[str, str, int], str] = {}
    if label_path is not None:
        with label_path.open(encoding="utf-8-sig", newline="") as fp:
            for record in csv.DictReader(fp):
                direction = "DEPOSIT" if (record.get("입금액") or "").strip() else "WITHDRAWAL"
                raw = (record.get("입금액") or "").strip() or (record.get("출금액") or "").strip()
                key = (record["적요"], direction, int(raw.replace(",", "") or 0))
                labels[key] = record["카테고리"]

    picked: list[tuple] = []
    seen: set[tuple[str, str]] = set()
    for row in rows:
        key = (row.description, row.direction)
        if key in seen:
            continue
        seen.add(key)
        want = labels.get((row.description, row.direction, row.amount), UNKNOWN)
        picked.append(
            (
                row.description,
                row.direction,
                row.amount,
                row.occurred_at.strftime("%Y-%m-%d"),
                row.cycle_hint,
                want,
            )
        )
        if len(picked) >= limit:
            break
    return picked


def build_request(
    fixture: list[tuple] | None = None,
) -> tuple[TransactionClassificationRequest, dict[int, str]]:
    """요청과, 요청에 실리지 않는 눈대중 기대값 표를 함께 만든다."""
    transactions = []
    expected: dict[int, str] = {}
    for index, (desc, direction, amount, day, cycle, want) in enumerate(
        fixture if fixture is not None else FIXTURE, start=1
    ):
        transactions.append(
            {
                "transactionId": index,
                "description": desc,
                "direction": direction,
                "amount": amount,
                "occurredAt": f"{day}T01:00:00Z",
                "cycleHint": cycle,
            }
        )
        expected[index] = want
    request = TransactionClassificationRequest.model_validate(
        {
            "jobId": 0,
            "business": BusinessContext(industry_type="FOOD_CAFE").model_dump(by_alias=True),
            "allowedCategories": ALLOWED,
            # few-shot 은 비운다. 사용자 정정 이력이 아직 없는 최초 상태가 이 모양이다 (§6.3).
            "fewShotExamples": [],
            "transactions": transactions,
        }
    )
    return request, expected


def missing_credentials(settings: Settings) -> bool:
    """키가 없으면 SDK 가 `ClaudeError` 가 아니라 `TypeError` 를 낸다 — 그것만 미리 거른다.

    워커 본체는 이 구멍이 없다. `router.py` 의 `except Exception` 이 받아 콜백을
    `AI_ANALYSIS_FAILED` 로 보내므로 Job 이 PROCESSING 에 남지 않는다. 스크립트만 손해다.
    """
    return not (
        settings.anthropic_api_key
        or settings.anthropic_auth_token
        or os.environ.get("ANTHROPIC_API_KEY")
        or os.environ.get("ANTHROPIC_AUTH_TOKEN")
    )


def print_header(settings: Settings, claude: ClaudeClient, count: int, source: str) -> None:
    kwargs = claude.client_kwargs()
    if "api_key" in kwargs:
        auth = "api_key"
    elif "auth_token" in kwargs:
        auth = "auth_token"
    else:
        auth = "환경변수 위임"
    print(
        f"[경로] Claude {settings.classification_model} "
        f"effort={settings.classification_effort} max_tokens={settings.classification_max_tokens}"
    )
    print(
        f"[인증] {auth} | base_url={kwargs.get('base_url', '기본값')} "
        f"| server_fallback={settings.claude_server_fallback}"
    )
    if settings.ai_pipeline_mode != "live":
        print(
            f"[주의] AI_PIPELINE_MODE={settings.ai_pipeline_mode} 다. 이 스크립트는 live 로 "
            "강제하지만, 워커를 띄우면 라우트는 여전히 stub 을 쓴다."
        )
    print(f"[입력] {source} {count}건 (BATCH_MAX={BATCH_MAX})\n")


async def run(args: argparse.Namespace) -> int:
    settings = Settings()  # .env 를 읽는다
    if args.model:
        settings.classification_model = args.model
    if args.effort:
        settings.classification_effort = args.effort

    if args.csv:
        # `--labels` 는 선택이다. 없으면 기대값 칸이 `?` 로 비고, 모델 답만 본다 —
        # 라벨이 없는 CSV 를 스모크하려고 내가 기대값을 지어내면 그게 곧 AI 라벨링이다.
        fixture = rows_from_csv(args.csv, args.labels, args.limit)
        source = f"{args.csv.name} 의 (적요,방향) 대표"
    else:
        fixture = None
        source = "손으로 만든 스모크 픽스처 — 평가 세트 아님"

    claude = ClaudeClient(settings)
    request, expected = build_request(fixture)
    rows = fixture if fixture is not None else FIXTURE
    print_header(settings, claude, len(request.transactions), source)

    if missing_credentials(settings):
        print(
            "[중단] Anthropic 자격 증명이 없다. 모델을 부르지 않고 끝낸다.\n"
            "   → ai/.env 에 아래 중 하나를 넣는다 (.env 는 gitignore 대상이다).\n"
            "      직접 키   : ANTHROPIC_API_KEY=...\n"
            "      GMS 경유  : ANTHROPIC_BASE_URL=https://gms.ssafy.io/gmsapi/api.anthropic.com\n"
            "                  ANTHROPIC_AUTH_TOKEN=...\n"
            "                  CLASSIFICATION_MODEL=claude-opus-4-8\n"
            "                  CLAUDE_SERVER_FALLBACK=false\n"
            "   GMS 는 Opus 5 를 열어 두지 않고(README 환경 변수 표), 서버측 폴백은 Opus 5 "
            "전용이라 켠 채로 다른 모델을 부르면 400 이 난다.\n"
            "   넣은 뒤 점검: uv run --frozen python -X utf8 -m scripts.ping_claude"
        )
        return 2

    started = time.perf_counter()
    try:
        results, meta = await ClaudeClassifier(settings, claude).classify(request)
    except ClassificationError as exc:
        print(f"[실패] stage={exc.stage} ({time.perf_counter() - started:.1f}s): {exc}")
        print(
            "   → config 단계면 키·base URL·모델 이름. model 단계면 한도·모델 서버. "
            "먼저 `python -X utf8 -m scripts.ping_claude` 로 계층을 좁힌다."
        )
        return 1
    elapsed = time.perf_counter() - started

    # 라우터가 stub·LLM 양쪽에 똑같이 거는 검사다. 여기서도 같은 것을 건다.
    discarded = invalid_results(request, results)
    missing = missing_transaction_ids(request, results)

    if args.json:
        print(
            json.dumps(
                {
                    "model": meta.model_dump(by_alias=True),
                    "elapsedSec": round(elapsed, 2),
                    "usage": claude.usage.snapshot(),
                    "discarded": {str(k): v for k, v in discarded.items()},
                    "missing": missing,
                    "results": [r.model_dump(by_alias=True, mode="json") for r in results],
                },
                ensure_ascii=False,
                indent=2,
            )
        )
        return 1 if (discarded or missing) else 0

    by_id = {r.transaction_id: r for r in results}
    print(f"{'ID':>3}  {'적요':<16} {'방향':<11} {'모델':<20} {'기대':<20} {'신뢰':>5}  비고")
    print("-" * 100)
    for index, (desc, direction, *_rest) in enumerate(rows, start=1):
        want = expected[index]
        trap = TRAPS.get(desc, "")
        result = by_id.get(index)
        if result is None:
            note = "폐기됨" if index in discarded else "누락"
            print(f"{index:>3}  {desc:<16} {direction:<11} {'—':<20} {want:<20} {'—':>5}  {note}")
            continue
        mark = "" if want == UNKNOWN or result.category == want else "✗"
        print(
            f"{index:>3}  {desc:<16} {direction:<11} {result.category:<20} {want:<20} "
            f"{result.confidence:>5.2f}  {(mark + ' ' + trap).strip()}"
        )

    print("-" * 100)
    print(f"[모델] {json.dumps(meta.model_dump(by_alias=True), ensure_ascii=False)}")
    print(f"[지연] {elapsed:.1f}s ({len(request.transactions)}건 배치 1회)")
    print(f"[토큰] {json.dumps(claude.usage.snapshot(), ensure_ascii=False)}")
    print(f"[계약] 폐기 {len(discarded)}건 · 누락 {len(missing)}건")
    for tx_id, why in discarded.items():
        print(f"        폐기 {tx_id}: {why}")
    if missing:
        print(f"        누락 ID: {missing}")

    # 정확률을 합산하지 않는 이유는 모듈 docstring 에 적었다. 다른 건만 짚어 준다.
    # `?` 는 기대값이 없는 것(라벨 CSV 미지정)이지 불일치가 아니다 — 세면 안 된다.
    comparable = [i for i in expected if expected[i] != UNKNOWN and i in by_id]
    mismatched = [i for i in comparable if by_id[i].category != expected[i]]
    if not comparable:
        print("[대조] 기대값이 없어 대조를 건너뛴다 (--labels 를 주면 나란히 찍는다).")
    elif mismatched:
        print(
            f"[대조] 대조 가능한 {len(comparable)}건 중 {len(mismatched)}건이 다르다: {mismatched}"
            " — 20건 안팎이라 점수가 아니라 **눈으로 볼 단서**다."
        )
    else:
        print(
            f"[대조] 대조 가능한 {len(comparable)}건이 모두 기대값과 같다. 점수로 인용하지 않는다."
        )

    return 1 if (discarded or missing) else 0


def main() -> int:
    parser = argparse.ArgumentParser(
        description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter
    )
    parser.add_argument(
        "--model", help="CLASSIFICATION_MODEL 대신 쓸 모델 (GMS 경로면 claude-opus-4-8)"
    )
    parser.add_argument("--effort", choices=["low", "medium", "high"], help="effort 덮어쓰기")
    parser.add_argument("--json", action="store_true", help="표 대신 JSON 으로 찍는다")
    parser.add_argument(
        "--csv", type=Path, help="거래 CSV. 주면 내장 픽스처 대신 (적요,방향) 대표를 쓴다"
    )
    parser.add_argument("--labels", type=Path, help="카테고리 CSV (--csv 와 짝)")
    parser.add_argument(
        "--limit",
        type=int,
        default=BATCH_MAX,
        help=f"한 배치에 담을 최대 건수 (기본 {BATCH_MAX} = BATCH_MAX)",
    )
    return asyncio.run(run(parser.parse_args()))


if __name__ == "__main__":
    raise SystemExit(main())
