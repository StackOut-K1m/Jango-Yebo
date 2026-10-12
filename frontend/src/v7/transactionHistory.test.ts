import { expect, test } from 'vitest';
import type { Account, Transaction } from '@jangoyebo/core';
import { historicalBalancePoints, historyRange } from './transactionHistory';

// 계산 자체의 규칙은 코어가 지킨다(packages/core — balanceHistory.test.ts).
// 여기서 보는 것은 창을 어떻게 잡고 그 값을 시안의 점으로 어떻게 옮기는가다.
test('KST 연도 경계를 넘겨 날짜별 마감 잔액을 점으로 옮긴다', () => {
  const range = historyRange(3, new Date('2026-01-01T15:30:00Z'));
  expect(range).toEqual({ from: '2025-12-30', to: '2026-01-02' });

  const accounts = [{ accountId: 7, status: 'CONNECTED', balance: 1_039 }] as Account[];
  const items = [
    { transactionId: 1, accountId: 7, occurredAt: '2025-12-30T05:00:00Z', direction: 'DEPOSIT', amount: 100, balanceAfter: 100 },
    // 15:00Z 는 KST 자정 — 다음 날로 센다
    { transactionId: 2, accountId: 7, occurredAt: '2025-12-31T15:00:00Z', direction: 'WITHDRAWAL', amount: 60, balanceAfter: 40 },
  ] as Transaction[];

  const points = historicalBalancePoints(items, accounts, 1_039, range.from, range.to);

  expect(points.map((p) => [p.label, p.value])).toEqual([
    ['12.30', 100],
    ['12.31', 100],
    ['1.01', 40],
    // 마지막 날은 서버가 준 현재 잔액이다
    ['1.02', 1_039],
  ]);
  expect(points.map((p) => p.weekday)).toEqual(['화', '수', '목', '금']);
  expect(points[0].date).toBe('12월 30일');
  // 지나간 날은 범위가 아니라 사실이다 — 밴드를 그리지 않는다
  expect(points.map((p) => [p.min, p.max])).toEqual(points.map((p) => [p.value, p.value]));
});

// 곡선 계산이 며칠 묵으면 지난 날이 「예측」으로 남아 과거 곡선과 같은 날이 두 번 나왔다.
test('예보 곡선에서 KST 오늘까지의 날은 뺀다', async () => {
  const { toPoints } = await import('./model');
  const curve = ['2026-09-12', '2026-09-14', '2026-09-15'].map((date) => ({ date, expectedBalance: 1 }));
  // 9/14 00:30 KST
  const now = new Date('2026-09-13T15:30:00Z');
  expect(toPoints({ curve } as never, now).map((p) => p.label)).toEqual(['9.15']);
});
