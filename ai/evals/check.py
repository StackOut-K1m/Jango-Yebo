"""라벨링 진행 상황과 규격 위반을 본다. 라벨링 중 수시로 돌린다.

    uv run --frozen python -m evals.check p1-01

규격을 어긴 줄이 있으면 줄 번호와 사유를 내고 종료 코드 1로 끝난다.
채점(Macro F1 등)은 이 도구가 하지 않는다 — 별도 스토리다.
"""

from __future__ import annotations

import argparse
import sys
from pathlib import Path

from app.nlp.schemas import CATEGORY_DIRECTION
from evals import labels

EVALS_ROOT = Path(__file__).resolve().parent
TARGET_COUNT = 300


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description="평가 세트 라벨 진행·규격 점검")
    parser.add_argument("dataset", help="세트 이름 (예: p1-01)")
    parser.add_argument("--file", default="labels.jsonl", help="라벨 파일명")
    args = parser.parse_args(argv)

    path = EVALS_ROOT / args.dataset / args.file
    if not path.exists():
        print(f"라벨 파일이 없습니다: {path}")
        print("거래 풀에서 표본을 먼저 뽑으세요 — python -m evals.sample --help")
        return 1

    try:
        records = labels.load(path)
    except ValueError as exc:
        print(f"규격 위반\n{exc}")
        return 1

    rep = labels.report(records)

    print(f"[{args.dataset}] {rep.labeled} / {rep.total} 건 라벨링 ({rep.progress:.0%})")
    if rep.total != TARGET_COUNT:
        print(f"  주의: 목표는 {TARGET_COUNT}건인데 파일에 {rep.total}건 있습니다")

    print(f"\n방향   {_fmt(rep.by_direction)}")
    print(f"업종   {_fmt(rep.by_industry)}")
    print(f"출처   {_fmt(rep.by_source)}")
    print(f"주기힌트 있음   {rep.with_cycle_hint}건")

    synthetic = sum(n for src, n in rep.by_source.items() if src.startswith("synthetic"))
    if synthetic:
        share = synthetic / rep.total
        print(
            f"  주의: 합성 데이터 {share:.0%}. 적요·금액·주기가 깔끔해서 "
            "실제보다 점수가 높게 나옵니다"
        )
        print("        프롬프트 A/B 비교에는 쓸 수 있지만 절대 점수로 인용하지 마세요")

    if rep.by_category:
        print(f"\n카테고리 {len(rep.by_category)}종 사용")
        width = max(len(c) for c in rep.by_category)
        for category, count in rep.by_category.items():
            bar = "#" * count
            print(f"  {category:<{width}}  {count:>3}  {bar}")
        _warn_thin_coverage(rep.by_category, rep.labeled)

    problems = 0
    if rep.duplicate_ids:
        print(f"\n[오류] transactionId 중복 {len(rep.duplicate_ids)}건: {rep.duplicate_ids[:10]}")
        problems += 1

    if rep.unlabeled_ids:
        head = rep.unlabeled_ids[:15]
        more = (
            ""
            if len(rep.unlabeled_ids) == len(head)
            else f" … 외 {len(rep.unlabeled_ids) - len(head)}건"
        )
        print(f"\n남은 건 {len(rep.unlabeled_ids)}개 — transactionId {head}{more}")
    elif rep.total:
        print("\n라벨링 완료. 분포표를 함께 커밋하세요.")

    return 1 if problems else 0


def _fmt(counts: dict[str, int]) -> str:
    return "  ".join(f"{k} {v}" for k, v in counts.items()) or "(없음)"


def _warn_thin_coverage(by_category: dict[str, int], labeled: int) -> None:
    """한 카테고리가 표본을 지배하면 Macro F1이 그 카테고리 하나로 흔들린다."""
    if labeled < 30:
        return
    top, top_count = next(iter(by_category.items()))
    if top_count / labeled > 0.4:
        print(f"  주의: {top}가 {top_count / labeled:.0%}를 차지합니다. 표본이 치우쳤습니다")

    thin = [c for c, n in by_category.items() if n < 3]
    if thin:
        print(f"  주의: 3건 미만 카테고리 {len(thin)}종 — {thin}")
        print("        Macro F1은 카테고리별 F1의 평균이라 건수가 적으면 점수가 불안정합니다")

    unused = sorted(set(CATEGORY_DIRECTION) - set(by_category))
    if unused:
        print(f"  참고: 표본에 없는 카테고리 {len(unused)}종 — 채점에서 제외됩니다")


if __name__ == "__main__":
    sys.exit(main())
