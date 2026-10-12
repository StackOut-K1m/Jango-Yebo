"""거래 CSV에서 라벨링 표본을 뽑는다.

    uv run --frozen python -m evals.sample bank.csv --industry FOOD_CAFE --count 300

입력은 **CSV 거래 업로드와 같은 규격**이다(거래일시·적요·입금액·출금액·거래후잔액).
백엔드가 그 형식만 받으므로 라벨링 원본도 같은 형식으로 두면 나중에 DB와 대조할 수 있다.

## 무엇으로 층화하는가

**카테고리로 층화할 수 없다.** 카테고리는 라벨링 결과이지 입력이 아니다. 라벨링 전에
카테고리를 알고 표본을 고르면 정답을 미리 정해 두고 뽑는 셈이 되어 평가가 무의미해진다.

그래서 **관측 가능한 축**으로만 층화한다.

| 축 | 이유 |
| --- | --- |
| 방향 | 입금 7종 / 출금 16종으로 카테고리 체계가 갈린다. 한쪽만 뽑으면 절반을 못 본다 |
| 금액 구간 | 120만원 임차료와 3천원 커피값은 난이도가 다르다 |
| 적요 중복도 | 같은 적요가 50번 반복되면 그게 표본을 잡아먹는다. 적요별 상한을 둔다 |

카테고리 균형은 **라벨링이 끝난 뒤 `evals.check`로 확인**한다. 치우쳤으면 표본을 보강한다.
순서가 이렇게 되는 것이 이 작업의 본질적 제약이다.
"""

from __future__ import annotations

import argparse
import csv
import random
import sys
from collections import Counter, defaultdict
from datetime import datetime
from pathlib import Path

from app.nlp.schemas import IndustryType
from evals import labels

EVALS_ROOT = Path(__file__).resolve().parent

# 헤더 별칭 — 백엔드 CsvTransactionParser 와 같은 매핑
HEADER_ALIASES = {
    "transactionat": "transactionAt",
    "거래일시": "transactionAt",
    "description": "description",
    "적요": "description",
    "depositamount": "depositAmount",
    "입금액": "depositAmount",
    "withdrawalamount": "withdrawalAmount",
    "출금액": "withdrawalAmount",
    "balanceafter": "balanceAfter",
    "거래후잔액": "balanceAfter",
}

# 금액 구간 상한(원). 마지막은 무한. 로그 눈금에 가깝게 잡았다.
AMOUNT_BANDS = (10_000, 50_000, 200_000, 1_000_000, 5_000_000)

# 같은 적요가 표본을 잡아먹지 않게 하는 기본 상한.
#
# 실제 은행 CSV 에는 `씨제이프레시웨이` 가 50번 찍힌다. 그대로 뽑으면 300건 중 상당수가
# 같은 문자열이 되어 다양성이 죽는다. 그래서 기본을 낮게 잡는다.
#
# 반대로 풀이 이미 분포를 맞춰 만든 것이고 크기가 목표와 같으면 이 상한이 오히려
# 건수를 깎는다. 그때는 `--max-per-description` 으로 올린다.
DEFAULT_MAX_PER_DESCRIPTION = 4


WEEKDAY_KO = ("월", "화", "수", "목", "금", "토", "일")

# 반복으로 인정하는 최소 관측 횟수. 문서의 반복 탐지가 최소 3~4회를 요구한다.
MIN_CYCLE_OCCURRENCES = 3

# 주 주기에서 「같은 요일」로 인정할 최빈 요일 비율. 1.0 이면 한 건만 어긋나도 주기가 사라진다.
#
# 실제 통장은 어긋난다 — 명세 9.2 의 `holidayRule` 이 그것을 규정한다:
# `PREVIOUS_BUSINESS_DAY`(직전 영업일 앞당김, **일부 급여**) · `NEXT_BUSINESS_DAY`(이월).
# 매주 금요일 급여가 추석 연휴 직전 수요일로 앞당겨지는 것이 그 예이고, 그때 요일이 하나
# 늘어난다. 월 주기에는 이미 ±2일 허용(`max(days) - min(days) <= 2`)이 있는데 주 주기에만
# 없어서 **같은 흔들림이 월에서는 살아남고 주에서는 사라졌다.** 그 비대칭을 없앤다.
#
# 0.8 인 근거 — 실측 분포에 큰 간격이 있다. 진짜 주 주기는 92~100% 이고(주급·현금입금·
# 플랫폼 정산·주간 발주), 그 아래는 67% 로 뚝 떨어진다(월 주기·부정기). 기존 300건 시드는
# 최고가 75% 라 이 값으로는 판정이 하나도 바뀌지 않는다.
#
# 운영에서는 백엔드의 P0-03 탐지가 median·MAD 로 같은 일을 한다 — 이상치 하나에 주기를
# 포기하지 않는다. 여기가 그보다 엄격하면 평가 세트가 실제보다 어려운 문제를 재게 된다.
WEEKDAY_MAJORITY = 0.8


class Row:
    __slots__ = ("occurred_at", "description", "direction", "amount", "cycle_hint")

    def __init__(self, occurred_at: datetime, description: str, direction: str, amount: int):
        self.occurred_at = occurred_at
        self.description = description
        self.direction = direction
        self.amount = amount
        self.cycle_hint: str | None = None

    @property
    def band(self) -> int:
        for i, upper in enumerate(AMOUNT_BANDS):
            if self.amount < upper:
                return i
        return len(AMOUNT_BANDS)


def parse_amount(raw: str) -> int:
    """천 단위 구분자와 통화 기호를 떼고 정수로. 백엔드 파서와 같은 관용도."""
    cleaned = raw.strip().replace(",", "").replace("₩", "").replace("원", "")
    return int(cleaned) if cleaned else 0


def read_csv(path: Path | str) -> list[Row]:
    # 인계 문서(evals/p1-01/거래_CSV_생성_규격.md)의 검증 스니펫에서 문자열로 부른다.
    # 거기서 Path 를 import 하게 만들 이유가 없다.
    path = Path(path)
    text = path.read_text(encoding="utf-8-sig")
    reader = csv.reader(text.splitlines())
    header = next(reader, None)
    if header is None:
        raise ValueError(f"{path.name}: 빈 파일입니다")

    columns: dict[str, int] = {}
    for i, cell in enumerate(header):
        canonical = HEADER_ALIASES.get(cell.strip().lower().replace(" ", ""))
        if canonical:
            columns[canonical] = i

    missing = {"transactionAt", "description"} - set(columns)
    if missing or not {"depositAmount", "withdrawalAmount"} & set(columns):
        raise ValueError(
            f"{path.name}: 헤더가 규격과 다릅니다. 필요한 것은 "
            "거래일시·적요·입금액·출금액 (영문 헤더도 허용)"
        )

    rows: list[Row] = []
    for lineno, cells in enumerate(reader, start=2):
        if not any(c.strip() for c in cells):
            continue
        try:
            deposit = (
                parse_amount(cells[columns["depositAmount"]]) if "depositAmount" in columns else 0
            )
            withdrawal = (
                parse_amount(cells[columns["withdrawalAmount"]])
                if "withdrawalAmount" in columns
                else 0
            )
            if (deposit > 0) == (withdrawal > 0):
                # 입금·출금 중 정확히 하나만 양수여야 한다 (업로드 규격)
                print(f"  건너뜀 {lineno}행: 입금·출금이 동시에 0 또는 동시에 양수")
                continue
            rows.append(
                Row(
                    occurred_at=datetime.fromisoformat(
                        cells[columns["transactionAt"]].strip().replace(" ", "T")
                    ),
                    description=cells[columns["description"]].strip(),
                    direction="DEPOSIT" if deposit > 0 else "WITHDRAWAL",
                    amount=deposit or withdrawal,
                )
            )
        except (ValueError, IndexError) as exc:
            print(f"  건너뜀 {lineno}행: {exc}")
    return rows


def attach_cycle_hints(rows: list[Row]) -> int:
    """반복 주기를 찾아 `cycle_hint` 에 채운다. 채운 건수를 돌려준다.

    **운영에서는 백엔드의 반복 탐지가 이 값을 만들어 워커에 보낸다.** 평가 세트에도
    같은 값이 있어야 한다 — 없으면 실제보다 어려운 문제를 재게 된다.

    `김영자 · 120만원 · 출금 · 8월 25일` 한 줄만 보면 임차료인지 알 수 없다.
    매월 25일 반복이라는 사실이 판단의 핵심인데 한 건에는 그게 담기지 않는다.

    풀 전체를 훑어 (적요, 방향, 금액 구간) 이 같은 거래를 모아 주기를 본다.

    **금액 구간을 키에 넣는 이유** — 같은 적요가 성격이 다른 두 거래로 나뉘는 경우가 있다.
    `구글` 이 매월 2,900원(구독료)과 부정기 35만원(광고비)으로 함께 빠지면, 금액을 무시하고
    묶으면 "주기 불규칙" 이 되어 **구독료의 월 주기가 사라진다.** 그러면 라벨러가 판단할
    근거를 잃는다. 백엔드 반복 탐지도 금액 중앙값·MAD 로 같은 일을 한다.
    """
    groups: dict[tuple[str, str, int], list[Row]] = defaultdict(list)
    for row in rows:
        groups[(row.description, row.direction, row.band)].append(row)

    filled = 0
    for group in groups.values():
        if len(group) < MIN_CYCLE_OCCURRENCES:
            continue
        group.sort(key=lambda r: r.occurred_at)
        count = len(group)

        days = {r.occurred_at.day for r in group}
        weekdays = {r.occurred_at.weekday() for r in group}

        if len(days) == 1:
            hint = f"매월 {days.pop()}일 반복 (최근 {count}회)"
        elif len(weekdays) == 1:
            hint = f"매주 {WEEKDAY_KO[weekdays.pop()]}요일 반복 (최근 {count}회)"
        elif max(days) - min(days) <= 2:
            # 주말·공휴일에 밀리면 하루이틀 흔들린다. 그래도 월 주기다
            hint = f"매월 {min(days)}~{max(days)}일 반복 (최근 {count}회)"
        else:
            # 주 주기도 공휴일에 앞당겨지거나 밀린다 (`WEEKDAY_MAJORITY` 주석 참고).
            # 예외 건수를 함께 적는다 — 「대부분 금요일인데 한 번 어긋났다」는 사실 자체가
            # 공휴일 보정을 추론할 단서이고, 감추면 라벨러도 모델도 그것을 못 본다.
            weekday_counts = Counter(r.occurred_at.weekday() for r in group)
            top_weekday, top_count = weekday_counts.most_common(1)[0]
            if top_count / count >= WEEKDAY_MAJORITY:
                hint = (
                    f"매주 {WEEKDAY_KO[top_weekday]}요일 반복 "
                    f"(최근 {count}회, 예외 {count - top_count}회)"
                )
            else:
                hint = f"같은 적요로 {count}회 발생 (주기 불규칙)"

        for row in group:
            row.cycle_hint = hint
        filled += count

    return filled


def stratified(
    rows: list[Row],
    count: int,
    seed: int,
    max_per_description: int = DEFAULT_MAX_PER_DESCRIPTION,
) -> list[Row]:
    """방향 x 금액구간 칸에서 고르게, 적요 중복은 상한을 두고 뽑는다."""
    rng = random.Random(seed)

    cells: dict[tuple[str, int], list[Row]] = defaultdict(list)
    for row in rows:
        cells[(row.direction, row.band)].append(row)
    for bucket in cells.values():
        rng.shuffle(bucket)

    used_description: defaultdict[str, int] = defaultdict(int)
    picked: list[Row] = []
    # 칸을 돌아가며 한 건씩 — 한 칸이 마르면 다음 칸이 채운다
    while len(picked) < count and any(cells.values()):
        for key in sorted(cells):
            bucket = cells[key]
            while bucket:
                row = bucket.pop()
                if used_description[row.description] >= max_per_description:
                    continue
                used_description[row.description] += 1
                picked.append(row)
                break
            if len(picked) >= count:
                break
    return picked


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description="거래 CSV에서 라벨링 표본 추출")
    parser.add_argument("csv_path", type=Path, help="거래 CSV (업로드 규격과 동일)")
    parser.add_argument("--dataset", default="p1-01", help="세트 이름")
    parser.add_argument("--industry", required=True, help="업종 (FOOD_CAFE 등)")
    parser.add_argument("--count", type=int, default=300)
    parser.add_argument("--seed", type=int, default=20260908, help="재현 가능하게 고정")
    parser.add_argument("--start-id", type=int, default=1, help="transactionId 시작값")
    parser.add_argument(
        "--max-per-description",
        type=int,
        default=DEFAULT_MAX_PER_DESCRIPTION,
        help="같은 적요를 몇 건까지 담을지. 풀이 이미 분포를 맞춘 것이면 올린다",
    )
    parser.add_argument(
        "--source",
        required=True,
        help="거래 출처. 합성 시드와 실제 거래를 구분한다 (예: synthetic-20260908, bank-csv)",
    )
    args = parser.parse_args(argv)

    if args.industry not in IndustryType.__args__:  # type: ignore[attr-defined]
        print(f"업종은 {IndustryType.__args__} 중 하나여야 합니다")  # type: ignore[attr-defined]
        return 1

    rows = read_csv(args.csv_path)
    print(f"읽은 거래 {len(rows)}건")
    if len(rows) < args.count:
        print(f"경고: 요청한 {args.count}건보다 적습니다. 있는 만큼만 뽑습니다")

    # 표본을 뽑기 전에 풀 전체에서 주기를 본다 — 일부만 보면 반복을 놓친다
    filled = attach_cycle_hints(rows)
    print(f"반복 주기를 찾아 {filled}건에 cycleHint 를 채웠습니다")

    picked = stratified(rows, args.count, args.seed, args.max_per_description)
    if len(picked) < min(args.count, len(rows)):
        print(
            f"적요 중복 상한({args.max_per_description})에 걸려 {len(picked)}건만 뽑혔습니다. "
            "풀이 이미 분포를 맞춘 것이면 --max-per-description 을 올리세요"
        )

    records = [
        labels.LabelRecord(
            transaction_id=args.start_id + i,
            description=row.description,
            direction=row.direction,
            amount=row.amount,
            occurred_at=row.occurred_at,
            cycle_hint=row.cycle_hint,
            industry_type=args.industry,  # type: ignore[arg-type]
            source=args.source,
        )
        for i, row in enumerate(picked)
    ]

    out = EVALS_ROOT / args.dataset / "labels.jsonl"
    if out.exists():
        print(f"이미 있습니다: {out}")
        print("덮어쓰면 라벨링한 것이 사라집니다. 필요하면 파일을 직접 옮기고 다시 실행하세요")
        return 1

    labels.dump(records, out)
    print(f"\n{len(records)}건을 {out}에 썼습니다")
    print(f"방향·금액 구간으로 층화, seed={args.seed} (같은 CSV·같은 seed면 같은 표본)")
    print("\n다음: category·merchantName 칸을 채우고 수시로 점검하세요")
    print(f"  uv run --frozen python -m evals.check {args.dataset}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
