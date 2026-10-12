import { dailyBalanceHistory, koreanDate } from '@jangoyebo/core';
import type { Account, Transaction } from '@jangoyebo/core';
import type { Point } from './model';

export { koreanDate };

/** 과거 n일과 오늘을 포함한 KST 달력 날짜 범위. */
export function historyRange(days: number, now = new Date()) {
  const today = Date.parse(`${koreanDate(now)}T00:00:00Z`);
  return {
    from: new Date(today - days * 86_400_000).toISOString().slice(0, 10),
    to: new Date(today).toISOString().slice(0, 10),
  };
}

const WEEKDAY = ['일', '월', '화', '수', '목', '금', '토'];

/**
 * 과거 잔액 곡선의 점.
 *
 * 계산은 코어가 한다(`dailyBalanceHistory`) — 웹과 앱이 같은 규칙으로 세야 하고, 규칙이
 * 갈리면 같은 가게를 두고 서로 다른 과거를 보여준다. 여기서는 그 값을 시안의 점 모양으로
 * 옮기기만 한다.
 *
 * `min`·`max` 를 값과 같게 둔다. 지나간 날은 범위가 아니라 사실이라 밴드를 그리지 않는다.
 */
export function historicalBalancePoints(
  items: Transaction[],
  accounts: Pick<Account, 'accountId' | 'status' | 'balance'>[],
  currentBalance: number,
  from: string,
  to: string,
): Point[] {
  return dailyBalanceHistory({ transactions: items, accounts, currentBalance, from, to }).map(
    ({ date, balance }, index) => {
      const at = new Date(`${date}T00:00:00Z`);
      return {
        index,
        iso: date,
        label: `${at.getUTCMonth() + 1}.${String(at.getUTCDate()).padStart(2, '0')}`,
        date: `${at.getUTCMonth() + 1}월 ${at.getUTCDate()}일`,
        weekday: WEEKDAY[at.getUTCDay()],
        value: balance,
        min: balance,
        max: balance,
      };
    },
  );
}
