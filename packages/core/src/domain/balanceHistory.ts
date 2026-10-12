import type { Account } from '../api/types/07_business';
import type { Transaction } from '../api/types/08_transaction';
import { entersBalanceCurve } from './account';

/**
 * 과거 잔액 곡선 (도메인 규칙).
 *
 * <h2>왜 되감지 않는가</h2>
 *
 * 예전에는 현재 잔액에서 거래를 날마다 빼며 과거로 되감았다. 되감기는 **한 건이 틀리면
 * 그 앞의 모든 날이 같은 폭으로 틀린다** — 오차가 날짜를 타고 번진다.
 *
 * 실제로 그랬다(2026-09-15 배포): 시드가 금융망 잔액을 맞추려고 넣은 「전기이월」 940,899원
 * 한 건이 동기화로 들어오자, 그 앞 30일이 통째로 940,899원씩 내려앉아 8일이 음수로 보였다.
 * 통장은 한 번도 음수가 아니었고 거래 내역 화면은 멀쩡했다 — 그쪽은 은행이 준 거래 후
 * 잔액을 그대로 보여주기 때문이다.
 *
 * **그래서 곡선도 같은 것을 본다.** `balanceAfter` 는 은행이 찍어 준 그 시점의 절대 잔액이라
 * 날마다 독립이다. 이상한 거래가 하나 끼어도 그 하루만 이상하고 번지지 않으며, 무엇보다
 * 거래 내역 화면과 곡선이 **구조적으로** 같은 값을 말하게 된다.
 *
 * <h2>제외된 거래를 거르지 않는 이유</h2>
 *
 * `isExcluded` 는 예측 입력에서 빼라는 표시지 **그 돈이 통장에서 안 움직였다는 뜻이 아니다.**
 * 거래 후 잔액에는 이미 반영돼 있다. 여기서 걸러내면 오히려 곡선이 실제 통장과 어긋난다.
 * 흔적 거래는 미래 곡선·반복 탐지에서 빠져야 하고(서버 몫), 과거 곡선에서는 빠질 이유가 없다.
 */

/** 하루치 마감 잔액. `date` 는 KST 달력의 `YYYY-MM-DD` 다. */
export interface BalanceHistoryDay {
  date: string;
  balance: number;
}

/** UTC 시각 문자열을 KST 달력 날짜로. 자정을 넘긴 거래가 하루 밀리지 않게 한다. */
export const koreanDate = (value: string | Date) => {
  const at = typeof value === 'string' ? new Date(value) : value;
  return new Date(at.getTime() + 9 * 3_600_000).toISOString().slice(0, 10);
};

/** `from`~`to` 사이의 날짜를 하루 간격으로. 양 끝을 포함한다. */
export function calendarDays(from: string, to: string): string[] {
  const days: string[] = [];
  const last = Date.parse(`${to}T00:00:00Z`);
  for (let time = Date.parse(`${from}T00:00:00Z`); time <= last; time += 86_400_000) {
    days.push(new Date(time).toISOString().slice(0, 10));
  }
  return days;
}

type CurveAccount = Pick<Account, 'accountId' | 'status' | 'balance'>;

/**
 * 날짜별 마감 잔액을 만든다.
 *
 * `currentBalance` 는 서버가 곡선과 같은 실행에서 낸 값이고 **마지막 날에 그대로 쓴다.**
 * 머리말의 큰 숫자와 곡선의 오늘이 갈리면 안 된다 — 돈을 세는 화면에서 그건 사고다.
 *
 * 잔액을 모르는 계좌는 합계에서 빠진다. 0 으로 세지 않는다 — 서버의 `currentBalance` 도
 * 같은 계좌를 빼고 세므로(`AccountBalancesService`) 오늘과 과거가 같은 기준으로 비어 있다.
 * 비어 있다는 사실은 화면이 `partial` 로 따로 말한다.
 */
export function dailyBalanceHistory({
  transactions,
  accounts,
  currentBalance,
  from,
  to,
}: {
  transactions: Transaction[];
  accounts: CurveAccount[];
  currentBalance: number;
  from: string;
  to: string;
}): BalanceHistoryDay[] {
  const days = calendarDays(from, to);
  if (days.length === 0) return [];

  const totals = new Map<string, number>(days.map((day) => [day, 0]));
  for (const account of accounts.filter(entersBalanceCurve)) {
    const series = accountSeries(account, transactions, days);
    for (const [day, value] of series) {
      totals.set(day, (totals.get(day) ?? 0) + value);
    }
  }

  const lastDay = days.length - 1;
  return days.map((date, index) => ({
    date,
    balance: index === lastDay ? currentBalance : (totals.get(date) ?? 0),
  }));
}

/**
 * 계좌 하나의 날짜별 마감 잔액. 값을 모르는 날은 담지 않는다.
 *
 * 창 안에 거래가 없으면 그 창 동안 잔액이 움직이지 않았다는 뜻이므로 지금 값으로 평평하다.
 * 첫 거래 앞의 날들은 **첫 거래 직전 잔액**으로 채운다 — 되감기를 한 걸음만 하는 것이라
 * 그 한 건이 이상해도 하루치 오차에 그친다.
 */
function accountSeries(
  account: CurveAccount,
  transactions: Transaction[],
  days: string[],
): Map<string, number> {
  const series = new Map<string, number>();

  // 거래 후 잔액이 없는 행은 이 계산에 쓸 수 없다. 전부 그렇다면 평평한 선으로 물러선다.
  const mine = transactions
    .filter((t) => t.accountId === account.accountId && typeof t.balanceAfter === 'number')
    .sort((a, b) => a.occurredAt.localeCompare(b.occurredAt) || a.transactionId - b.transactionId);

  if (mine.length === 0) {
    if (typeof account.balance === 'number') {
      for (const day of days) series.set(day, account.balance);
    }
    return series;
  }

  // 오름차순이라 나중 값이 덮어쓴다 — 하루의 마지막 거래가 그날 마감 잔액이 된다.
  const closing = new Map<string, number>();
  for (const t of mine) closing.set(koreanDate(t.occurredAt), t.balanceAfter as number);

  const first = mine[0];
  const signed = first.direction === 'DEPOSIT' ? first.amount : -first.amount;
  let carry = (first.balanceAfter as number) - signed;

  for (const day of days) {
    const close = closing.get(day);
    if (close !== undefined) carry = close;
    series.set(day, carry);
  }
  return series;
}
