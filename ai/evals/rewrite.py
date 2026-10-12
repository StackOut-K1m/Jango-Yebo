"""적요 치환 — 카테고리 이름이 적요에 새어 나온 거래 CSV 를 평가에 쓸 수 있게 고친다.

    uv run --frozen python -X utf8 -m evals.rewrite --tx 거래.csv --labels 거래_labels.csv

`--out-dir` 을 안 주면 `artifacts/rewrite/` 에 `<원본이름>.rewritten.csv` 로 낸다.
**원본은 건드리지 않는다.**

## 무엇을 고치나

`거래_CSV_생성_규격.md` §3 이 금지한 것 — *"적요에 카테고리 이름(`임차료`·`MATERIALS`)을 넣는
것도 금지다. 모델이 그대로 베껴 100점이 나오는데 아무것도 검증하지 못한다."*

`임차료` → `RENT`, `급여` → `PAYROLL`, `4대보험` → `SOCIAL_INSURANCE` 처럼 적요가 답을 그대로
말해 주면 정규식 한 줄로 맞힐 수 있고, 그 세트로 잰 점수는 LLM 에 대해 아무것도 말하지 않는다.

## 함정을 만든다

단순 치환이 아니다. 세 가지를 **의도적으로** 만든다 (`적요_표기_목록.md` 「함정 5개」).

1. 개인 이름 출금 — `임차료`·`급여`·`주급 아르바이트` 를 사람 이름으로. 적요만으로는 못 풀고
   금액·주기·같은 날 몇 명인지를 봐야 갈린다
2. 같은 적요가 방향으로 갈림 — `배민 광고비` → `우아한형제들`. 입금(정산)이 이미 있으므로
   출금(광고)과 한 문자열이 된다
3. 상호명만 남기기 — `신한카드 매출대금` → `신한카드`. 무슨 회사인지 알아야 풀린다

## 안 고치는 것

`한국전력공사` · `해양도시가스` · `이든푸드` · `강남유통` · `제일종이` · `한성상회` ·
`우아한형제들` · `현금입금` 은 **실제 은행 표기이고 상호명**이라 답을 알려주지 않는다.
`현금입금` 에 「현금」이 들어 있지만 그건 은행이 실제로 그렇게 찍는 것이다.
"""

from __future__ import annotations

import argparse
import csv
import sys
from collections import Counter, defaultdict
from pathlib import Path

from app.nlp.schemas import CATEGORY_DIRECTION

OUT_ROOT = Path(__file__).resolve().parent.parent / "artifacts" / "rewrite"

# 기존 합성 시드(`datasets/gen_seed.py`)의 박서준·김영자·이수진·최민호·정하늘과 **겹치지 않게**
# 골랐다. 두 세트를 나중에 합치거나 나란히 볼 때 어느 쪽 사람인지 헷갈리지 않아야 한다.
LANDLORD = "조순희"
STAFF_MONTHLY = "임재현"
STAFF_WEEKLY = "노예린"

# 원본 적요 → 바꿀 적요. 왜 그 값인지가 평가 세트의 난이도를 결정하므로 한 줄씩 적는다.
RENAME: dict[str, str] = {
    # --- 카테고리 이름이 그대로 적혀 있던 것 → 실제 은행 표기 ---
    "신한카드 매출대금": "신한카드",  # 「매출대금」이 답이었다
    "삼성화재 화재보험": "삼성화재",  # 「보험」이 답이었다
    "포스뱅크 구독료": "포스뱅크",  # 「구독」이 답이었다
    "KT 통신료": "케이티",  # 「통신」이 답이었다
    "4대보험": "국민건강보험공단",  # 낱말 자체가 카테고리명이었다
    "대출상환 이체": "기업은행",  # 「대출상환」이 답이었다. 은행명은 §적요_표기_목록 에 있다
    "부가가치세 예정고지": "국세청",  # 세목명이 답이었다
    # --- 함정 1·2: 개인 이름 출금 (적요만으로는 못 푼다) ---
    # 매월 6일 900,000 단독 → 임차료. 같은 날 다른 사람이 없다는 것이 단서다
    "임차료": LANDLORD,
    # 매월 10일 1,200,000. 국민건강보험공단·주급과 **같은 날**이라 급여 묶음이 보인다
    "급여": STAFF_MONTHLY,
    # 매주 금요일 210,000. 주 단위 반복이 급여의 또 다른 모양이다
    "주급 아르바이트": STAFF_WEEKLY,
    # --- 함정 3: 같은 적요가 방향으로 갈린다 ---
    # 입금(정산) 25건이 이미 `우아한형제들` 이다. 출금을 같은 문자열로 맞추면
    # 「입금이면 PLATFORM_SETTLEMENT · 출금이면 ADVERTISING」 함정이 생긴다
    "배민 광고비": "우아한형제들",
}

# 치환 뒤 남은 누출을 다시 재는 데 쓴다. 「이 카테고리인데 적요에 이 낱말이 있으면 답을 준 것」
LEAK_WORDS: dict[str, tuple[str, ...]] = {
    "CARD_SETTLEMENT": ("매출대금", "매출"),
    "RENT": ("임차료", "월세"),
    "PAYROLL": ("급여", "주급", "상여", "아르바이트"),
    "SOCIAL_INSURANCE": ("4대보험", "고용보험", "산재"),
    "INSURANCE": ("화재보험", "보험료"),
    "SUBSCRIPTION": ("구독",),
    "TELECOM": ("통신",),
    "ADVERTISING": ("광고",),
    "LOAN_REPAYMENT": ("대출", "상환", "원리금"),
    "TAX": ("부가가치세", "종합소득세", "원천세"),
    "PLATFORM_FEE": ("수수료",),
}

TX_HEADER = ["거래일시", "적요", "입금액", "출금액", "거래후잔액"]
LABEL_HEADER = ["transactionId", "거래일시", "적요", "입금액", "출금액", "카테고리"]


def read_csv(path: Path) -> list[dict[str, str]]:
    """BOM 을 떼고 읽는다 — Excel 이 저장한 CSV 에는 거의 항상 붙어 있다."""
    return list(csv.DictReader(path.read_text(encoding="utf-8-sig").splitlines()))


def direction(row: dict[str, str]) -> str:
    return "DEPOSIT" if (row.get("입금액") or "").strip() else "WITHDRAWAL"


def leaks(description: str, category: str) -> list[str]:
    return [w for w in LEAK_WORDS.get(category, ()) if w in description]


def check_pairing(tx: list[dict[str, str]], labels: list[dict[str, str]]) -> list[str]:
    """두 파일이 같은 거래를 같은 순서로 담고 있는지. 어긋나면 라벨이 통째로 밀린다.

    라벨 CSV 에 `transactionId` 가 없어서 행 순서가 유일한 연결고리다. 그래서 여기서
    한 번 확인하고, 출력에는 ID 를 붙여 다음부터는 순서에 기대지 않게 한다.
    """
    problems: list[str] = []
    if len(tx) != len(labels):
        problems.append(f"행 수가 다르다 — 거래 {len(tx)}건 · 라벨 {len(labels)}건")
        return problems
    for index, (t, label) in enumerate(zip(tx, labels, strict=True), start=1):
        if t["거래일시"] != label["거래일시"] or t["적요"] != label["적요"]:
            problems.append(
                f"{index}행이 어긋난다 — 거래 '{t['거래일시']} {t['적요']}' "
                f"vs 라벨 '{label['거래일시']} {label['적요']}'"
            )
        if len(problems) >= 5:
            problems.append("… 이하 생략")
            break
    return problems


def report(tx: list[dict[str, str]], labels: list[dict[str, str]], title: str) -> None:
    """누출·함정·커버리지를 한 화면에. 치환 전후로 같은 것을 재서 나란히 본다."""
    leaked = sum(
        1 for t, label in zip(tx, labels, strict=True) if leaks(t["적요"], label["카테고리"])
    )
    by_desc: dict[str, set[str]] = defaultdict(set)
    for t in tx:
        by_desc[t["적요"]].add(direction(t))
    split = sorted(d for d, v in by_desc.items() if len(v) > 1)
    cats = Counter(label["카테고리"] for label in labels)
    thin = sorted(c for c, n in cats.items() if n < 3)

    print(f"\n[{title}]")
    print(f"  적요가 답을 알려주는 행 : {leaked}/{len(tx)}건 ({leaked / len(tx):.0%})")
    print(f"  서로 다른 적요          : {len(by_desc)}개")
    print(f"  방향으로 갈리는 적요    : {len(split)}개 {split or ''}")
    print(f"  카테고리                : {len(cats)}/23종")
    if thin:
        print(f"  3건 미만 카테고리       : {len(thin)}종 {thin}")
    unused = sorted(set(CATEGORY_DIRECTION) - set(cats))
    if unused:
        print(f"  표본에 없는 카테고리    : {len(unused)}종 — 채점에서 빠진다")


def rewrite(rows: list[dict[str, str]]) -> tuple[list[dict[str, str]], Counter[str]]:
    hits: Counter[str] = Counter()
    out = []
    for row in rows:
        new = dict(row)
        before = row["적요"]
        after = RENAME.get(before)
        if after is not None:
            new["적요"] = after
            hits[f"{before} → {after}"] += 1
        out.append(new)
    return out, hits


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__.split("\n")[0])
    parser.add_argument("--tx", type=Path, required=True, help="거래 CSV (업로드 규격)")
    parser.add_argument("--labels", type=Path, required=True, help="카테고리 CSV")
    parser.add_argument("--out-dir", type=Path, default=OUT_ROOT, help="출력 폴더")
    parser.add_argument("--dry-run", action="store_true", help="파일을 쓰지 않고 보고서만 낸다")
    args = parser.parse_args(argv)

    for path in (args.tx, args.labels):
        if not path.exists():
            print(f"파일이 없습니다: {path}")
            return 1

    tx, labels = read_csv(args.tx), read_csv(args.labels)
    missing_cols = [c for c in ("거래일시", "적요") if c not in (tx[0] if tx else {})]
    if missing_cols:
        print(f"거래 CSV 에 필수 열이 없습니다: {missing_cols}")
        return 1
    if "카테고리" not in (labels[0] if labels else {}):
        print("라벨 CSV 에 '카테고리' 열이 없습니다.")
        return 1

    problems = check_pairing(tx, labels)
    if problems:
        print("두 파일이 짝이 맞지 않습니다 — 치환을 멈춥니다.")
        for line in problems:
            print(f"  {line}")
        return 1

    report(tx, labels, "치환 전")

    new_tx, hits = rewrite(tx)
    new_labels, _ = rewrite(labels)

    print(f"\n[치환] {len(hits)}종 · {sum(hits.values())}건")
    for line, n in hits.most_common():
        print(f"  {line:<40}{n:>4}건")
    untouched = sorted({t["적요"] for t in tx} - set(RENAME))
    print(f"\n[그대로 둔 적요] {len(untouched)}개 — 상호명이라 답을 알려주지 않는다")
    print(f"  {' · '.join(untouched)}")

    report(new_tx, new_labels, "치환 후")

    if args.dry_run:
        print("\n--dry-run 이라 파일을 쓰지 않았습니다.")
        return 0

    args.out_dir.mkdir(parents=True, exist_ok=True)
    tx_out = args.out_dir / f"{args.tx.stem}.rewritten.csv"
    label_out = args.out_dir / f"{args.labels.stem}.rewritten.csv"

    # 거래 CSV 는 업로드 규격(명세 7.4) 헤더를 그대로 지킨다 — 열을 더하지 않는다.
    with tx_out.open("w", encoding="utf-8-sig", newline="") as fp:
        writer = csv.DictWriter(fp, fieldnames=TX_HEADER, extrasaction="ignore")
        writer.writeheader()
        writer.writerows(new_tx)

    # 라벨 CSV 에만 transactionId 를 붙인다. 지금은 행 순서가 유일한 연결고리라
    # 한쪽을 정렬하거나 행을 지우면 조용히 어긋난다. 번호는 거래 CSV 의 행 순서다.
    with label_out.open("w", encoding="utf-8-sig", newline="") as fp:
        writer = csv.DictWriter(fp, fieldnames=LABEL_HEADER, extrasaction="ignore")
        writer.writeheader()
        for index, row in enumerate(new_labels, start=1):
            writer.writerow({**row, "transactionId": index})

    print(f"\n→ {tx_out}")
    print(f"→ {label_out}  (transactionId 추가)")
    print("\n남은 일 — 이 스크립트가 못 하는 것:")
    print("  1. 빠진 카테고리 보강. 없는 종류는 채점에서 빠진다")
    print("  2. **사람이 라벨을 검토한다.** 치환 뒤에도 카테고리는 생성기 의도이지 정답이 아니다")
    print(f"     적요가 {len({t['적요'] for t in new_tx})}종뿐이라 검토는 그만큼이면 끝난다")
    return 0


__all__ = ["RENAME", "leaks", "rewrite"]

if __name__ == "__main__":
    sys.exit(main())
