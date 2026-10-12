import { expect, test } from 'vitest';
import { loadStatus } from './model';

const settled = { isPending: false, isError: false };
const fail = (status: number) => ({ ready: true as const, ok: false as const, status, error: { code: 'X', message: 'x' } });

// 빈 목록을 「없음」으로 읽어도 되는 것은 ok 뿐이다(UI 리뷰 1)
test('조회 실패·준비 중·로딩은 정상 0건과 갈린다', () => {
  expect(loadStatus({ isPending: true, isError: false }, undefined)).toBe('loading');
  expect(loadStatus({ isPending: false, isError: true }, undefined)).toBe('error');
  expect(loadStatus(settled, undefined)).toBe('error');
  expect(loadStatus(settled, { ready: false })).toBe('preparing');
  expect(loadStatus(settled, fail(409))).toBe('preparing');
  expect(loadStatus(settled, fail(422))).toBe('preparing');
  expect(loadStatus(settled, fail(500))).toBe('error');
  expect(loadStatus(settled, { ready: true, ok: true, data: { items: [] }, isMock: false })).toBe('ok');
});
