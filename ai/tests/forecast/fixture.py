"""테스트 픽스처 — 잔여 집계·베이스라인이 맞게 도는지 눈으로 보기 위한 가짜 거래.

⚠️ **이것은 학습 시드가 아니다.** 설계노트 M-2 의 blind split 은
XGBoost 보정 모델의 학습·평가에 쓰는 시드를 모델 담당자가 만들면 안 된다는 규칙이고,
그 시드는 팀의 다른 사람이 규칙을 비공개로 만든다.

여기 있는 것은 **뺄셈이 맞게 도는지 확인하는 단위 테스트용 픽스처**다.
규칙이 뻔히 보이는 게 정상이고, 모델 성능 측정에 절대 쓰지 않는다.

매칭 표현이 ERD 를 따른다 — `PLANNED_TRANSACTION.matched_transaction_id` 가
실제 거래를 가리키므로, 여기서도 **매칭된 거래 id 집합**을 따로 돌려준다.
"""

import random
from datetime import date, timedelta
from decimal import Decimal

from app.forecast.residual import DEPOSIT, WITHDRAWAL, Transaction

CARD_SETTLEMENT = Decimal("748000")
RENT = Decimal("2000000")
PAYROLL = Decimal("3000000")
LOAN_REPAYMENT = Decimal("450000")


def planned_delta(start: date, days: int) -> list[Decimal]:
    """예정 거래만으로 만든 일별 증감. 지금 곡선에 들어가는 것이 이것뿐이다."""
    out = []
    for i in range(days):
        d = start + timedelta(days=i)
        delta = Decimal("0")
        if d.weekday() == 1:
            delta += CARD_SETTLEMENT
        if d.day == 1:
            delta -= RENT
        if d.day == 10:
            delta -= LOAN_REPAYMENT
        if d.day == 25:
            delta -= PAYROLL
        out.append(delta)
    return out


def build_history(
    start: date, days: int, seed: int = 20260904
) -> tuple[list[Transaction], set[int]]:
    """과거 거래 내역과 매칭된 거래 id 집합.

    예정 거래에 매칭된 것(카드 정산·임차료·급여·대출)과
    패턴에 안 걸리는 흐름(현금 매출 입금·소액 지출)을 섞는다.
    """
    rng = random.Random(seed)
    txs: list[Transaction] = []
    matched: set[int] = set()
    next_id = 1

    def add(d: date, amount: Decimal, direction: str, *, is_matched=False, excluded=False):
        nonlocal next_id
        txs.append(Transaction(next_id, d, amount, direction, excluded))
        if is_matched:
            matched.add(next_id)
        next_id += 1

    for i in range(days):
        d = start + timedelta(days=i)

        if d.weekday() == 1:
            add(d, CARD_SETTLEMENT, DEPOSIT, is_matched=True)
        if d.day == 1:
            add(d, RENT, WITHDRAWAL, is_matched=True)
        if d.day == 10:
            add(d, LOAN_REPAYMENT, WITHDRAWAL, is_matched=True)
        if d.day == 25:
            add(d, PAYROLL, WITHDRAWAL, is_matched=True)

        # 현금 매출을 주 2회(수·토) 몰아서 입금 — 패턴 탐지에 안 걸린다
        if d.weekday() in (2, 5):
            add(d, Decimal(rng.randrange(150_000, 450_000, 1_000)), DEPOSIT)

        # 소모품·수리비 — 주기가 없어 예정 거래가 되지 못한다
        if rng.random() < 0.45:
            add(d, Decimal(rng.randrange(8_000, 60_000, 1_000)), WITHDRAWAL)

    # 예측에서 제외하는 일회성 지출 — 잔여로도 넘어가면 안 된다
    add(start + timedelta(days=days // 2), Decimal("3400000"), WITHDRAWAL, excluded=True)

    return txs, matched
