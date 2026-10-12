import { expect, it } from 'vitest';
import { calendarDays, dailyBalanceHistory, koreanDate } from './balanceHistory';
import type { Account } from '../api/types/07_business';
import type { Transaction } from '../api/types/08_transaction';

let nextId = 1;
const tx = (
  accountId: number,
  occurredAt: string,
  direction: 'DEPOSIT' | 'WITHDRAWAL',
  amount: number,
  balanceAfter: number | null,
) =>
  ({ transactionId: nextId++, accountId, occurredAt, direction, amount, balanceAfter } as Transaction);

const account = (accountId: number, status: Account['status'], balance: number | null) =>
  ({ accountId, status, balance } as Account);

const values = (days: { date: string; balance: number }[]) => days.map((d) => [d.date, d.balance]);

it('KST 달력으로 날짜를 센다', () => {
  // 09:00 KST — 같은 날
  expect(koreanDate('2026-09-14T00:00:00Z')).toBe('2026-09-14');
  // 00:30 KST — 다음 날이다. UTC 로 자르면 하루 밀린다
  expect(koreanDate('2026-09-13T15:30:00Z')).toBe('2026-09-14');
  expect(calendarDays('2025-12-30', '2026-01-02')).toEqual([
    '2025-12-30', '2025-12-31', '2026-01-01', '2026-01-02',
  ]);
});

// 2026-09-15 배포에서 실제로 났던 일이다. 시드가 금융망 잔액을 맞추려고 넣은 「전기이월」
// 940,899원이 동기화로 들어오자, 되감기가 그 앞 30일을 통째로 940,899원씩 끌어내렸다.
// 거래 후 잔액은 날마다 독립이라 그 한 건이 앞날을 건드리지 못한다.
it('흔적 거래 한 건이 그 앞의 날을 끌어내리지 않는다', () => {
  const days = dailyBalanceHistory({
    accounts: [account(7, 'CONNECTED', 940_900)],
    currentBalance: 940_900,
    from: '2026-09-13',
    to: '2026-09-15',
    transactions: [
      tx(7, '2026-09-13T08:00:00Z', 'WITHDRAWAL', 19_800, 569_600),
      tx(7, '2026-09-14T00:00:00Z', 'WITHDRAWAL', 275_600, 294_000),
      tx(7, '2026-09-14T06:00:00Z', 'DEPOSIT', 178_000, 940_900),
      // 사장님이 한 거래가 아니다. 통장 잔액은 이미 940,900 이었다
      tx(7, '2026-09-15T02:23:46Z', 'DEPOSIT', 940_899, 940_900),
    ],
  });

  expect(values(days)).toEqual([
    ['2026-09-13', 569_600],
    ['2026-09-14', 940_900],
    ['2026-09-15', 940_900],
  ]);
});

it('거래가 없는 날은 직전 마감 잔액이 이어진다', () => {
  const days = dailyBalanceHistory({
    accounts: [account(7, 'CONNECTED', 500)],
    currentBalance: 500,
    from: '2026-09-13',
    to: '2026-09-16',
    transactions: [tx(7, '2026-09-13T08:00:00Z', 'DEPOSIT', 300, 800)],
  });

  expect(values(days)).toEqual([
    ['2026-09-13', 800],
    ['2026-09-14', 800],
    ['2026-09-15', 800],
    ['2026-09-16', 500],
  ]);
});

// 첫 거래 직전 잔액은 그 한 건만 되감으면 나온다. 창 전체를 되감는 것과 달리 오차가 번지지 않는다.
it('첫 거래 앞의 날은 첫 거래 직전 잔액이다', () => {
  const days = dailyBalanceHistory({
    accounts: [account(7, 'CONNECTED', 1_000)],
    currentBalance: 1_000,
    from: '2026-09-11',
    to: '2026-09-14',
    transactions: [
      tx(7, '2026-09-13T08:00:00Z', 'DEPOSIT', 300, 800),
      tx(7, '2026-09-14T08:00:00Z', 'DEPOSIT', 200, 1_000),
    ],
  });

  expect(values(days)).toEqual([
    ['2026-09-11', 500],
    ['2026-09-12', 500],
    ['2026-09-13', 800],
    ['2026-09-14', 1_000],
  ]);
});

// 목록은 해제된 계좌만 빼지만 서버의 currentBalance 는 CONNECTED 만 센다. 곡선이 목록 기준을
// 쓰면 잔액은 빠진 계좌의 거래만 들어와 과거가 통째로 어긋난다.
it('CONNECTED 가 아닌 계좌의 거래는 곡선에 세지 않는다', () => {
  const days = dailyBalanceHistory({
    accounts: [account(7, 'CONNECTED', 800), account(8, 'VERIFICATION_EXPIRED', null)],
    currentBalance: 800,
    from: '2026-09-13',
    to: '2026-09-14',
    transactions: [
      tx(7, '2026-09-13T08:00:00Z', 'DEPOSIT', 300, 800),
      // 인증 전 계좌에 CSV 로 올린 거래. 목록에는 나오지만 이 계좌 잔액은 아무도 모른다
      tx(8, '2026-09-13T09:00:00Z', 'DEPOSIT', 9_999, 9_999),
    ],
  });

  expect(values(days)).toEqual([
    ['2026-09-13', 800],
    ['2026-09-14', 800],
  ]);
});

it('계좌가 여럿이면 날마다 합친다 — 창 안에 거래가 없는 계좌는 평평하다', () => {
  const days = dailyBalanceHistory({
    accounts: [account(7, 'CONNECTED', 800), account(9, 'CONNECTED', 200)],
    currentBalance: 1_000,
    from: '2026-09-13',
    to: '2026-09-14',
    transactions: [tx(7, '2026-09-13T08:00:00Z', 'DEPOSIT', 300, 800)],
  });

  expect(values(days)).toEqual([
    ['2026-09-13', 1_000],
    ['2026-09-14', 1_000],
  ]);
});

// 0 으로 세면 곡선이 조용히 낮아져 없는 공백이 생긴다. 서버의 currentBalance 도 같은 계좌를
// 빼고 세므로 오늘과 과거가 같은 기준으로 비어 있다.
it('잔액도 거래도 없는 계좌는 합계에서 빠진다 — 0 으로 세지 않는다', () => {
  const days = dailyBalanceHistory({
    accounts: [account(7, 'CONNECTED', 800), account(9, 'CONNECTED', null)],
    currentBalance: 800,
    from: '2026-09-13',
    to: '2026-09-14',
    transactions: [tx(7, '2026-09-13T08:00:00Z', 'DEPOSIT', 300, 800)],
  });

  expect(values(days)).toEqual([
    ['2026-09-13', 800],
    ['2026-09-14', 800],
  ]);
});

// 머리말의 큰 숫자와 곡선의 오늘이 갈리면 안 된다. 마지막 날은 서버 값을 그대로 쓴다.
it('마지막 날은 서버가 준 현재 잔액이다', () => {
  const days = dailyBalanceHistory({
    accounts: [account(7, 'CONNECTED', 800)],
    // 동기화가 거래보다 최신이면 둘이 다를 수 있다
    currentBalance: 1_234,
    from: '2026-09-13',
    to: '2026-09-14',
    transactions: [tx(7, '2026-09-13T08:00:00Z', 'DEPOSIT', 300, 800)],
  });

  expect(values(days)).toEqual([
    ['2026-09-13', 800],
    ['2026-09-14', 1_234],
  ]);
});

// CSV 에 거래 후 잔액 칸이 없을 수 있다. 계산할 수 없는 계좌는 지금 값으로 평평하게 물러선다.
it('거래 후 잔액이 없으면 지금 잔액으로 평평하다', () => {
  const days = dailyBalanceHistory({
    accounts: [account(7, 'CONNECTED', 800)],
    currentBalance: 800,
    from: '2026-09-13',
    to: '2026-09-14',
    transactions: [tx(7, '2026-09-13T08:00:00Z', 'DEPOSIT', 300, null)],
  });

  expect(values(days)).toEqual([
    ['2026-09-13', 800],
    ['2026-09-14', 800],
  ]);
});
