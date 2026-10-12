"""잔여 현금흐름 집계 — S15P21C105-66

    residual_deposit(d)    = 실제 입금 합계(d) − Σ 예정 거래에 매칭된 입금(d)
    residual_withdrawal(d) = 실제 출금 합계(d) − Σ 예정 거래에 매칭된 출금(d)

정의가 뺄셈이라 이중 계상이 구조적으로 불가능하다. 예정 거래가 설명한 만큼은
잔여에서 이미 빠져 있고, 사용자가 새 패턴을 승인하면 잔여가 자동으로 준다.

**캐시하지 않는다.** 패턴이 승인되면 과거 잔여가 소급해서 바뀌므로
(매칭 항목이 늘어 잔여가 준다) 예측 실행 시점마다 새로 집계한다.
캐시하면 승인 직후 이중 계상이 생긴다.

금액은 Decimal 로만 다룬다. float 를 쓰면 백엔드의 BigDecimal 과 값이 갈린다.
"""

from dataclasses import dataclass
from datetime import date, timedelta
from decimal import Decimal

DEPOSIT = "DEPOSIT"
WITHDRAWAL = "WITHDRAWAL"


@dataclass(frozen=True)
class Transaction:
    """계좌 거래 한 건. ERD `TRANSACTION` 에서 잔여 계산에 필요한 컬럼만 뽑았다.

    id             — TRANSACTION.id
    occurred_on    — TRANSACTION.transaction_date (사업장 기준 거래일)
    amount         — TRANSACTION.amount (항상 양수)
    direction      — TRANSACTION.flow_type
    is_excluded    — TRANSACTION.is_excluded. 장비 구입 등 일회성이라 예측에서 빼는 거래.
                     실제 합계에서 아예 뺀다 (잔여로도 넘기지 않는다).
                     세무 판단(tax_review_status)과는 독립이다 — 여기서는 이것만 본다.

    **매칭 여부는 이 테이블에 없다.** ERD 는 `PLANNED_TRANSACTION.matched_transaction_id`
    쪽에 1:1 로 두었으므로, 매칭된 거래 id 집합을 따로 받아 판정한다
    (`aggregate_residual` 의 matched_transaction_ids).
    """

    id: int
    occurred_on: date
    amount: Decimal
    direction: str
    is_excluded: bool = False


@dataclass(frozen=True)
class ResidualSeries:
    """일별 잔여 시계열. 구멍 없이 하루도 빠짐없이 채운다."""

    start_date: date
    deposit: list[Decimal]
    withdrawal: list[Decimal]

    def __len__(self) -> int:
        return len(self.deposit)

    @property
    def end_date(self) -> date:
        return self.start_date + timedelta(days=len(self) - 1)

    def day(self, d: date) -> tuple[Decimal, Decimal]:
        i = (d - self.start_date).days
        if not 0 <= i < len(self):
            raise KeyError(f"{d} 는 시계열 범위 밖이다 ({self.start_date}~{self.end_date})")
        return self.deposit[i], self.withdrawal[i]


def aggregate_residual(
    transactions: list[Transaction],
    matched_transaction_ids: set[int],
    start_date: date,
    end_date: date,
) -> ResidualSeries:
    """거래 목록에서 일별 잔여를 뽑는다.

    matched_transaction_ids — `PLANNED_TRANSACTION.matched_transaction_id` 가
        가리키는 거래 id 집합. 예정 거래가 설명한 몫이라 잔여에서 빠진다.
        ERD 상 1:1(부분 UK)이므로 한 거래가 두 예정에 붙는 일은 없다.

    거래가 하나도 없는 날은 0 이다. 비어 있는 게 아니라 0 이라는 점이 중요하다 —
    빠뜨리면 요일별 통계에서 표본 수가 틀어진다.
    """
    if end_date < start_date:
        raise ValueError("end_date 가 start_date 보다 앞이다")

    span = (end_date - start_date).days + 1
    deposit = [Decimal("0")] * span
    withdrawal = [Decimal("0")] * span

    for tx in transactions:
        if tx.is_excluded:
            continue
        if tx.id in matched_transaction_ids:
            continue  # 예정 거래가 설명한 몫 — 뺄셈으로 이미 빠진 부분
        if not start_date <= tx.occurred_on <= end_date:
            continue

        i = (tx.occurred_on - start_date).days
        if tx.direction == DEPOSIT:
            deposit[i] += tx.amount
        elif tx.direction == WITHDRAWAL:
            withdrawal[i] += tx.amount
        else:
            raise ValueError(f"알 수 없는 direction: {tx.direction!r}")

    return ResidualSeries(start_date=start_date, deposit=deposit, withdrawal=withdrawal)


def explained_ratio(
    transactions: list[Transaction], matched_transaction_ids: set[int]
) -> float:
    """예정 거래가 설명한 금액 비율. 잔여가 얼마나 큰지 감을 잡는 데 쓴다.

    이 값이 1.0 에 가까우면 잔여 축이 거의 비어 있다는 뜻이고,
    낮으면 지금 곡선이 그만큼 틀리고 있다는 뜻이다.
    """
    total = Decimal("0")
    matched = Decimal("0")
    for tx in transactions:
        if tx.is_excluded:
            continue
        total += tx.amount
        if tx.id in matched_transaction_ids:
            matched += tx.amount
    if total == 0:
        return 1.0
    return float(matched / total)
