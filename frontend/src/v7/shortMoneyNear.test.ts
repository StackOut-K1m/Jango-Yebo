import { expect, test } from 'vitest';
import { shortMoneyNear } from './model';

// 반올림이 안전선을 넘나들 때만 소수 한 자리 — 흐림인데 「100만」으로 보이면 안 된다
test('안전선을 넘나드는 반올림만 소수 한 자리로 적는다', () => {
  expect(shortMoneyNear(998_500, 1_000_000)).toBe('99.8만');
  expect(shortMoneyNear(999_999, 1_000_000)).toBe('99.9만');
  expect(shortMoneyNear(1_004_500, 1_004_000)).toBe('100.5만');
  expect(shortMoneyNear(1_200_500, 1_000_000)).toBe('120만');
  expect(shortMoneyNear(998_500, null)).toBe('100만');
  expect(shortMoneyNear(1_000_000, 1_000_000)).toBe('100만');
});
