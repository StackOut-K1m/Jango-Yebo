"""거래 분류 평가 세트의 라벨 레코드와 검증.

라벨 파일은 JSONL이다 — 한 줄에 한 건. 이유:

- 사람이 300건을 여러 번에 걸쳐 채운다. 줄 단위라 중간에 끊고 이어가기 쉽다
- git diff가 바뀐 줄만 보여준다. 라벨을 고친 이력이 리뷰에서 읽힌다
- CSV로 두면 Excel이 한글을 cp949로 재저장해 깨뜨린다. 실제로 겪은 사고다

**손으로 채우는 것은 여기가 아니라 스프레드시트에서 한다** — `evals/sheet.py` 가
이 파일을 xlsx로 내보내고 다시 읽어들인다. 300건을 편집기에서 치는 것보다 빠르고,
방향에 맞는 카테고리만 나오는 드롭다운이라 오타와 방향 불일치가 안 생긴다.
위의 cp949 사고는 `.csv` 확장자에서만 난다. xlsx는 내부가 UTF-8 XML이라 무관하다.

정본은 이 JSONL이고 xlsx는 입력 도구다. git에도 이 파일만 올라간다.

`category`·`merchantName`이 비어 있는 레코드가 아직 라벨링 안 된 건이다.
"""

from __future__ import annotations

from collections import Counter
from datetime import datetime
from pathlib import Path

from pydantic import Field, model_validator

from app.common.envelope import CamelModel
from app.nlp.schemas import (
    CATEGORY_DIRECTION,
    Direction,
    IndustryType,
    TransactionCategory,
)


class LabelRecord(CamelModel):
    """라벨링 1건.

    앞쪽 필드는 거래에서 그대로 옮긴 것이고, 뒤쪽 세 개를 사람이 채운다.

    **라벨러는 `description`·`direction`·`amount`·`occurredAt`·`cycleHint`만 보고 판단한다.**
    금융망이 그것밖에 주지 않기 때문이다 — 거래처명 필드가 응답에 없다. 사람에게 더 많은
    정보를 주고 라벨을 받으면 모델이 도달할 수 없는 정답이 되어 평가가 불공정해진다.
    """

    # --- 거래에서 옮겨온 것 (수정하지 않는다) ---
    transaction_id: int
    description: str = Field(max_length=200)
    direction: Direction
    amount: int = Field(ge=0)
    occurred_at: datetime
    cycle_hint: str | None = Field(default=None, max_length=200)
    industry_type: IndustryType

    # 이 거래가 어디서 왔는지. 합성 시드와 실제 거래가 섞이면 구분할 방법이 필요하다.
    #
    # 합성 데이터에서 나온 점수는 실제보다 높게 나온다 — 적요·금액·주기가 깔끔해서다.
    # 이 필드가 없으면 나중에 실제 시드가 들어왔을 때 "0.87" 이 무엇을 잰 점수인지
    # 알 수 없어진다. 절대 점수를 인용할 때 이 값을 함께 밝힌다.
    source: str = Field(default="unknown", max_length=100)

    # --- 사람이 채우는 칸 ---
    category: TransactionCategory | None = None
    merchant_name: str | None = Field(default=None, max_length=200)
    # 판단이 애매했던 이유. 오답 분석에서 이 메모가 프롬프트 수정의 단서가 된다.
    note: str | None = Field(default=None, max_length=500)

    @property
    def labeled(self) -> bool:
        return self.category is not None

    @model_validator(mode="after")
    def _direction_must_match_category(self) -> LabelRecord:
        """입금 거래에 출금 카테고리를 붙이는 실수를 파일 읽는 시점에 잡는다.

        사람이 300건을 타이핑하면 반드시 몇 건은 틀린다. 그게 정답셋에 남으면
        모델이 맞혀도 틀렸다고 채점되므로, 조용히 넘어가면 안 된다.
        """
        if self.category is not None and CATEGORY_DIRECTION[self.category] != self.direction:
            raise ValueError(
                f"transactionId={self.transaction_id}: {self.direction} 거래에 "
                f"{CATEGORY_DIRECTION[self.category]} 카테고리({self.category})를 붙였습니다."
            )
        return self


class LabelSetReport(CamelModel):
    """진행률과 분포. 라벨링 중 언제든 뽑아 본다."""

    total: int
    labeled: int
    by_category: dict[str, int]
    by_direction: dict[str, int]
    by_industry: dict[str, int]
    by_source: dict[str, int]
    with_cycle_hint: int
    unlabeled_ids: list[int]
    duplicate_ids: list[int]

    @property
    def progress(self) -> float:
        return self.labeled / self.total if self.total else 0.0


def load(path: Path) -> list[LabelRecord]:
    """JSONL을 읽는다. 한 줄이라도 규격을 어기면 그 줄 번호와 함께 실패한다."""
    records: list[LabelRecord] = []
    for lineno, line in enumerate(path.read_text(encoding="utf-8").splitlines(), start=1):
        stripped = line.strip()
        if not stripped or stripped.startswith("//"):
            continue
        try:
            records.append(LabelRecord.model_validate_json(stripped))
        except ValueError as exc:
            raise ValueError(f"{path.name}:{lineno} 규격 위반\n{exc}") from exc
    return records


def dump(records: list[LabelRecord], path: Path) -> None:
    """JSONL로 쓴다. transactionId 순으로 정렬해 diff가 안정적이게 한다."""
    path.parent.mkdir(parents=True, exist_ok=True)
    lines = [
        r.model_dump_json(by_alias=True, exclude_none=False)
        for r in sorted(records, key=lambda r: r.transaction_id)
    ]
    path.write_text("\n".join(lines) + "\n", encoding="utf-8")


def report(records: list[LabelRecord]) -> LabelSetReport:
    seen: Counter[int] = Counter(r.transaction_id for r in records)
    labeled = [r for r in records if r.labeled]
    return LabelSetReport(
        total=len(records),
        labeled=len(labeled),
        by_category=dict(Counter(r.category for r in labeled if r.category).most_common()),
        by_direction=dict(Counter(r.direction for r in records).most_common()),
        by_industry=dict(Counter(r.industry_type for r in records).most_common()),
        by_source=dict(Counter(r.source for r in records).most_common()),
        with_cycle_hint=sum(1 for r in records if r.cycle_hint),
        unlabeled_ids=[r.transaction_id for r in records if not r.labeled],
        duplicate_ids=sorted(tx for tx, n in seen.items() if n > 1),
    )
