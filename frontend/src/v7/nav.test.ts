import { expect, test } from 'vitest';
import { NAV_GROUPS, groupFor, holds } from './nav';

// `/` 는 모든 경로의 접두사다. 접두사로만 재면 어느 화면에서나 메인이 켜진다
test('`/` 는 정확히 같을 때만, 나머지는 하위 경로까지 품는다', () => {
  expect(holds('/', '/')).toBe(true);
  expect(holds('/', '/gaps')).toBe(false);
  expect(holds('/gaps', '/gaps')).toBe(true);
  expect(holds('/evidences', '/evidences/3')).toBe(true);
  expect(holds('/evidences', '/gaps')).toBe(false);
});

test('묶음은 지금 화면 하나만 고른다', () => {
  expect(groupFor('/gaps')?.label).toBe('자금 예보');
  expect(groupFor('/scenarios')?.label).toBe('자금 예보');
  expect(groupFor('/transactions')?.label).toBe('거래 관리');
  expect(groupFor('/evidences/3')?.label).toBe('거래 관리');
  expect(groupFor('/loans')?.label).toBe('대출·지원');
  expect(groupFor('/tax/drafts/3')?.label).toBe('세무 신고');
  expect(groupFor('/support-programs/3')?.label).toBe('대출·지원');
});

// 메뉴에 없는 화면은 2차 바를 그리지 않는다 — 없는 형제를 지어내지 않는다
test('메뉴 밖 화면에서는 묶음이 없다', () => {
  expect(groupFor('/')).toBeUndefined();
  expect(groupFor('/mypage')).toBeUndefined();
  expect(groupFor('/settlements')).toBeUndefined();
  expect(groupFor('/businesses/new')).toBeUndefined();
});

// 한 경로가 두 묶음에 걸리면 1차 바의 밑줄이 두 곳에 붙는다
test('같은 경로가 두 묶음에 들어 있지 않다', () => {
  const all = NAV_GROUPS.flatMap((g) => g.items.map((i) => i.to));
  expect(new Set(all).size).toBe(all.length);
});
