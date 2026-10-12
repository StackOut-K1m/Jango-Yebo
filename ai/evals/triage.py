"""라벨링 분류(triage) — 외부 모델 두 개의 답을 대조해 「사람이 봐야 할 곳」만 남긴다.

    uv run --frozen python -X utf8 -m evals.triage ask   p1-01
    uv run --frozen python -X utf8 -m evals.triage diff  p1-01 --a gpt.tsv --b gemini.tsv
    uv run --frozen python -X utf8 -m evals.triage apply p1-01 --answers final.tsv

## 왜 이런 모양인가

라벨은 사람이 정해야 한다(`labels.py` · `ai/README.md`). 이 데이터에는 발굴할 정답이
없기 때문이다 — `김영자 출금 180만원`이 임차료인지 급여인지는 적요·방향·금액·주기로
결정되지 않는다. 어떤 모델이 채워도 그건 정답이 아니라 또 하나의 추측이다.

그런데 **서로 다른 두 모델이 갈리는 지점**은 값어치가 있다. 둘이 다른 이유로 갈렸다는
것은 그 행이 실제로 애매하다는 뜻이고, 그게 사람이 시간을 써야 할 곳이다. 반대로 둘이
일치하는 행은 대개 판단이 필요 없는 것(`한국전력` → 공과금)이다.

그래서 이 도구는 **모델에게 라벨을 맡기지 않는다.** 300건을 훑을 순서를 정해 줄 뿐이고,
확정은 `apply` 에 사람이 넣는 파일이 한다.

**Claude 를 쓰지 마라.** 채점 대상이 Claude 이므로 라벨 쪽에 세우면 순환이 생긴다.
GPT · Gemini 처럼 채점에 안 쓰는 모델 두 개를 쓴다. 채팅창에서 하면 API 크레딧도 안 든다.

## 묶음 기준

`(적요, 방향, 금액밴드)`. 300건이 110묶음으로 준다.

금액밴드를 넣는 이유 — `구글 출금`이 2,900원과 355,700원을 함께 갖고 있다. 소액 정기는
구독료, 큰 금액은 광고비라 한 묶음으로 채우면 틀린다. 밴드는 `sample.py` 의 반복 주기
탐지가 쓰는 것과 같은 것을 쓴다(`AMOUNT_BANDS`).
"""

from __future__ import annotations

import argparse
import sys
from collections import defaultdict
from pathlib import Path

from app.nlp.schemas import CATEGORY_DIRECTION
from evals import labels
from evals.labels import LabelRecord
from evals.sample import AMOUNT_BANDS

EVALS_ROOT = Path(__file__).resolve().parent
OUT_ROOT = EVALS_ROOT.parent / "artifacts" / "triage"

# 코드만으로는 무엇인지 모르므로 뜻을 함께 준다. **적요 예시는 일부러 주지 않는다** —
# `적요_표기_목록.md` 의 함정 풀이(「네이버는 입금이면 정산, 출금이면 광고」)를 같이 주면
# 두 모델이 그 규칙을 따라 똑같이 답하고, 불일치 신호가 사라진다. 그러면 이 도구가
# 「사람이 볼 곳」을 못 찾는다. 사람은 그 문서를 보고 판단하면 된다.
MEANINGS: dict[str, str] = {
    "CARD_SETTLEMENT": "카드사·VAN·PG 를 통한 카드 매출 정산금 입금",
    "PLATFORM_SETTLEMENT": "배달·예약 플랫폼의 매출 정산금 입금",
    "CASH_SALES": "현금 매출을 계좌에 넣은 입금",
    "TRANSFER_SALES": "손님이 계좌이체로 결제한 매출 입금",
    "LOAN_DISBURSEMENT": "대출·정책자금이 실행되어 들어온 입금",
    "OWNER_FUNDING": "사업주가 사업 계좌에 자기 돈을 넣은 입금",
    "OTHER_INCOME": "위 어디에도 안 맞는 입금",
    "RENT": "점포 임차료 출금",
    "PAYROLL": "직원 급여·상여 출금",
    "SOCIAL_INSURANCE": "건강보험·국민연금·고용·산재 보험료 출금",
    "MATERIALS": "판매할 상품을 만들 재료·식자재 매입 출금",
    "UTILITIES": "전기·가스·수도·난방 요금 출금",
    "TELECOM": "통신·인터넷 요금 출금",
    "ADVERTISING": "광고·마케팅 집행 출금",
    "PLATFORM_FEE": "플랫폼·카드·PG 수수료가 별도로 청구된 출금",
    "INSURANCE": "화재·배상 등 사업용 보험료 출금",
    "SUBSCRIPTION": "소프트웨어·서비스 정기 구독료 출금",
    "LOAN_REPAYMENT": "대출 원금·이자 상환 출금",
    "TAX": "국세·지방세 납부 출금",
    "SUPPLIES": "소모품·비품 구입 출금",
    "EQUIPMENT": "설비·기계 구입 출금",
    "PERSONAL": "사업과 무관한 개인 용도로 보이는 출금",
    "OTHER_EXPENSE": "위 어디에도 안 맞는 출금",
}


def amount_band(amount: int) -> int:
    for index, upper in enumerate(AMOUNT_BANDS):
        if amount < upper:
            return index
    return len(AMOUNT_BANDS)


class Group:
    """같은 판단이 적용되는 거래 묶음. 사람이 한 번 정하면 속한 행 전부에 퍼진다."""

    __slots__ = ("number", "description", "direction", "records")

    def __init__(self, number: int, description: str, direction: str) -> None:
        self.number = number
        self.description = description
        self.direction = direction
        self.records: list[LabelRecord] = []

    @property
    def amounts(self) -> str:
        values = [r.amount for r in self.records]
        low, high = min(values), max(values)
        return f"{low:,}" if low == high else f"{low:,}~{high:,}"

    @property
    def cycle_hint(self) -> str:
        return next((r.cycle_hint for r in self.records if r.cycle_hint), "") or "-"

    @property
    def ids(self) -> list[int]:
        return sorted(r.transaction_id for r in self.records)


def build_groups(records: list[LabelRecord]) -> list[Group]:
    """`(적요, 방향, 금액밴드)` 로 묶는다. 번호는 건수 내림차순 — 큰 것부터 처리하면
    같은 시간에 더 많은 행이 덮인다."""
    buckets: dict[tuple[str, str, int], list[LabelRecord]] = defaultdict(list)
    for record in records:
        buckets[(record.description, record.direction, amount_band(record.amount))].append(record)

    ordered = sorted(buckets.items(), key=lambda kv: (-len(kv[1]), kv[0][0], kv[0][2]))
    groups = []
    for number, ((description, direction, _band), rows) in enumerate(ordered, start=1):
        group = Group(number, description, direction)
        group.records = rows
        groups.append(group)
    return groups


def render_question(groups: list[Group]) -> str:
    """채팅창에 그대로 붙여넣을 문제지."""
    deposit = [c for c, d in CATEGORY_DIRECTION.items() if d == "DEPOSIT"]
    withdrawal = [c for c, d in CATEGORY_DIRECTION.items() if d == "WITHDRAWAL"]

    lines = [
        "한국 소상공인(카페) 사업용 계좌의 거래 묶음입니다.",
        "각 묶음에 카테고리를 하나씩 붙여 주세요.",
        "",
        "## 규칙",
        "",
        "- 적요·방향·금액대·주기힌트만 보고 판단합니다. 그 외 정보를 가정하지 마세요.",
        "- DEPOSIT 행에는 입금 카테고리만, WITHDRAWAL 행에는 출금 카테고리만 씁니다.",
        "- 애매해도 가장 그럴듯한 것을 하나 고르세요. 빈칸이나 복수 응답은 안 됩니다.",
        "- 설명·머리말·코드블록 없이 **답만** 아래 형식으로 주세요.",
        "",
        "```",
        "번호<탭>카테고리",
        "```",
        "",
        f"- {len(groups)}줄을 전부 주세요. 번호는 아래 표의 번호 그대로입니다.",
        "",
        "## 입금 카테고리 (DEPOSIT 행에만)",
        "",
    ]
    lines += [f"- `{c}` — {MEANINGS[c]}" for c in deposit]
    lines += ["", "## 출금 카테고리 (WITHDRAWAL 행에만)", ""]
    lines += [f"- `{c}` — {MEANINGS[c]}" for c in withdrawal]
    lines += ["", "## 거래 묶음", "", "번호\t적요\t방향\t건수\t금액대\t주기힌트"]
    lines += [
        f"{g.number}\t{g.description}\t{g.direction}\t{len(g.records)}\t{g.amounts}\t{g.cycle_hint}"
        for g in groups
    ]
    return "\n".join(lines) + "\n"


def read_answers(path: Path, groups: list[Group]) -> dict[int, str]:
    """`번호<탭>카테고리` 를 읽는다. 모델이 붙이는 군더더기는 관용적으로 넘긴다.

    번호·카테고리가 유효하지 않으면 그 줄만 버리고 사유를 낸다 — 한 줄 때문에 전체를
    못 읽는 것이 더 나쁘다. 방향 불일치는 여기서 잡는다(`labels.py` 는 파일 읽는
    시점에 예외를 던지므로 그 전에 걸러야 한다).
    """
    valid_numbers = {g.number: g for g in groups}
    allowed = set(CATEGORY_DIRECTION)
    answers: dict[int, str] = {}
    skipped: list[str] = []

    for lineno, raw in enumerate(path.read_text(encoding="utf-8-sig").splitlines(), start=1):
        line = raw.strip().strip("`")
        if not line or line.startswith("#"):
            continue
        # 빈 필드를 걸러내면 안 된다. `diff` 가 낸 확인 필요 줄은 카테고리 칸이 비어
        # 있고(`번호\t\t적요\t…`) 그것을 버리면 세 번째 칸(적요)이 카테고리로 밀려 온다.
        # 그래서 탭이 있으면 탭으로만 가른다.
        #
        # 탭도 쉼표도 없으면 공백으로 가른다 — 모델이 형식을 지키지 않는다. 실측(2026-09-10)에서
        # Gemini 는 탭을 줬는데 GPT 는 `1 CARD_SETTLEMENT` 처럼 공백을 줬다. 형식을 못 지켰다고
        # 110줄을 통째로 버리면 도구가 쓸모없어진다.
        if "\t" in line:
            parts = line.split("\t")
        elif "," in line:
            parts = line.split(",")
        else:
            parts = line.split()
        if len(parts) < 2 or not parts[0].strip().isdigit():
            continue  # 머리말·표 헤더 등
        category = parts[1].strip().upper()
        if not category:
            continue  # 아직 안 채운 줄. 오류가 아니므로 조용히 넘어간다
        number = int(parts[0].strip())
        group = valid_numbers.get(number)
        if group is None:
            skipped.append(f"{path.name}:{lineno} 없는 묶음 번호 {number}")
        elif category not in allowed:
            skipped.append(f"{path.name}:{lineno} 묶음 {number}: 없는 카테고리 {category}")
        elif CATEGORY_DIRECTION[category] != group.direction:
            skipped.append(
                f"{path.name}:{lineno} 묶음 {number}({group.description}): "
                f"{group.direction} 인데 {category} 를 붙였습니다"
            )
        else:
            answers[number] = category

    if skipped:
        print(f"[{path.name}] 버린 줄 {len(skipped)}개")
        for line in skipped[:10]:
            print(f"  {line}")
        if len(skipped) > 10:
            print(f"  … 외 {len(skipped) - 10}개")
    return answers


def do_ask(groups: list[Group], dataset: str) -> int:
    text = render_question(groups)
    OUT_ROOT.mkdir(parents=True, exist_ok=True)
    out = OUT_ROOT / f"{dataset}-question.md"
    out.write_text(text, encoding="utf-8")
    rows = sum(len(g.records) for g in groups)
    print(text)
    print("=" * 78)
    print(f"묶음 {len(groups)}개 · 거래 {rows}건 → {out}")
    print()
    print("이 파일을 채팅창 두 곳(예: GPT · Gemini)에 각각 붙여넣고, 답을 받아")
    print(f"  {OUT_ROOT / 'a.tsv'}  ·  {OUT_ROOT / 'b.tsv'}  로 저장한 뒤:")
    print(f"  uv run --frozen python -X utf8 -m evals.triage diff {dataset} --a a.tsv --b b.tsv")
    print()
    print("채점 대상인 Claude 는 여기 쓰지 마세요 — 라벨과 예측이 같은 모델이 됩니다.")
    return 0


def do_diff(groups: list[Group], dataset: str, path_a: Path, path_b: Path) -> int:
    answers_a = read_answers(path_a, groups)
    answers_b = read_answers(path_b, groups)

    agreed: list[tuple[Group, str]] = []
    conflict: list[tuple[Group, str | None, str | None]] = []
    for group in groups:
        a, b = answers_a.get(group.number), answers_b.get(group.number)
        if a is not None and a == b:
            agreed.append((group, a))
        else:
            conflict.append((group, a, b))

    covered = sum(len(g.records) for g, _ in agreed)
    total = sum(len(g.records) for g in groups)
    print(f"\n일치 {len(agreed)}묶음 ({covered}건) · 불일치·누락 {len(conflict)}묶음")
    print(f"거래 {total}건 중 {covered}건({covered / total:.0%})이 두 모델에서 같게 나왔습니다.\n")

    OUT_ROOT.mkdir(parents=True, exist_ok=True)
    out = OUT_ROOT / f"{dataset}-answers.tsv"
    lines = [
        "# 확정 답안. 일치분은 채워져 있고, 아래 '확인 필요'는 빈칸입니다.",
        "# 빈칸을 채운 뒤: python -m evals.triage apply <세트> --answers answers.tsv",
        "# 일치분도 그대로 믿지 말고 훑어보세요 — 두 모델이 같이 틀릴 수 있습니다.",
        "#",
        "# 번호\t카테고리\t적요\t방향\t건수\t금액대",
    ]
    lines += [
        f"{g.number}\t{c}\t{g.description}\t{g.direction}\t{len(g.records)}\t{g.amounts}"
        for g, c in agreed
    ]
    lines += ["#", "# ---- 확인 필요 (두 모델이 갈렸거나 답이 없음) ----"]

    if conflict:
        print("확인 필요 — 여기가 사람이 볼 곳입니다\n")
        header = f"{'번호':>4}  {'적요':<20}{'방향':<12}{'건수':>3}  {'A':<20}{'B':<20}금액대"
        print(header)
        print("-" * 104)
    for group, a, b in conflict:
        print(
            f"{group.number:>4}  {group.description:<20}{group.direction:<12}"
            f"{len(group.records):>3}  {(a or '(없음)'):<20}{(b or '(없음)'):<20}{group.amounts}"
        )
        lines.append(
            f"{group.number}\t\t{group.description}\t{group.direction}"
            f"\t{len(group.records)}\t{group.amounts}"
        )

    out.write_text("\n".join(lines) + "\n", encoding="utf-8")
    print(f"\n→ {out}")
    print("  빈칸을 채운 뒤: ", end="")
    print(f"uv run --frozen python -X utf8 -m evals.triage apply {dataset} --answers answers.tsv")
    return 0


def do_apply(
    records: list[LabelRecord],
    groups: list[Group],
    dataset: str,
    jsonl_path: Path,
    answers_path: Path,
) -> int:
    answers = read_answers(answers_path, groups)
    if not answers:
        print(f"읽을 답이 없습니다: {answers_path}")
        return 1

    by_id = {r.transaction_id: r for r in records}
    changed = 0
    for group in groups:
        category = answers.get(group.number)
        if category is None:
            continue
        for record in group.records:
            target = by_id[record.transaction_id]
            if target.category != category:
                target.category = category  # type: ignore[assignment]
                changed += 1

    labels.dump(records, jsonl_path)
    labeled = sum(1 for r in records if r.labeled)
    filled_groups = len(answers)
    print(f"\n묶음 {filled_groups}개 → 거래 {changed}건에 반영했습니다.")
    print(f"라벨링 {labeled} / {len(records)}건 ({labeled / len(records):.0%})")
    print(f"→ {jsonl_path}")
    print("\n분포 점검:")
    print(f"  uv run --frozen python -X utf8 -m evals.check {dataset}")
    print("시트에도 반영하려면 (엑셀에 채운 것이 있으면 덮어씁니다):")
    print(f"  uv run --frozen python -X utf8 -m evals.sheet export {dataset} --force")
    return 0


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__.split("\n")[0])
    sub = parser.add_subparsers(dest="command", required=True)

    ask = sub.add_parser("ask", help="묶음 문제지를 만든다 (채팅창에 붙여넣기)")
    diff = sub.add_parser("diff", help="두 답안을 대조해 확인 필요분을 뽑는다")
    apply_ = sub.add_parser("apply", help="확정 답안을 labels.jsonl 에 반영한다")
    for p in (ask, diff, apply_):
        p.add_argument("dataset", help="세트 이름 (예: p1-01)")
        p.add_argument("--file", default="labels.jsonl", help="라벨 파일명")
    diff.add_argument("--a", required=True, help="첫 번째 모델의 답 (tsv)")
    diff.add_argument("--b", required=True, help="두 번째 모델의 답 (tsv)")
    apply_.add_argument("--answers", required=True, help="확정 답안 (tsv)")

    args = parser.parse_args(argv)
    jsonl_path = EVALS_ROOT / args.dataset / args.file
    if not jsonl_path.exists():
        print(f"라벨 파일이 없습니다: {jsonl_path}")
        return 1
    try:
        records = labels.load(jsonl_path)
    except ValueError as exc:
        print(f"labels.jsonl 규격 위반\n{exc}")
        return 1

    groups = build_groups(records)

    if args.command == "ask":
        return do_ask(groups, args.dataset)
    if args.command == "diff":
        return do_diff(groups, args.dataset, _resolve(args.a), _resolve(args.b))
    return do_apply(records, groups, args.dataset, jsonl_path, _resolve(args.answers))


def _resolve(name: str) -> Path:
    """맨 파일명이면 출력 폴더에서 찾는다 — 경로를 매번 치지 않게."""
    path = Path(name)
    return path if path.exists() or path.is_absolute() else OUT_ROOT / name


__all__ = ["Group", "build_groups", "read_answers", "render_question"]

if __name__ == "__main__":
    sys.exit(main())
