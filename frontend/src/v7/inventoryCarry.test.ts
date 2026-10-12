import { expect, test } from 'vitest';
import type { TaxDraftDetail, TaxDraftField } from '@jangoyebo/core';
import { closingInventoryOf } from './taxView';

const draft = (fields: TaxDraftField[]) =>
  ({ sections: [{ sectionKey: 'COST_OF_SALES', label: '매출원가', fields }] }) as Pick<TaxDraftDetail, 'sections'>;
const field = (over: Partial<TaxDraftField>) =>
  ({ fieldKey: 'CLOSING_INVENTORY', label: '⑯ 기말재고액', ...over }) as TaxDraftField;

test('작년 기말재고액을 꺼낸다', () => {
  expect(closingInventoryOf(draft([field({ value: 10_000_000 })]))).toBe(10_000_000);
});

// 0 원은 「재고가 없었다」는 답이다 — 안 적은 것과 다르다
test('0원도 값으로 본다', () => {
  expect(closingInventoryOf(draft([field({ value: 0 })]))).toBe(0);
});

// 안 적은 해의 값을 0 으로 이어받으면 매출원가가 그만큼 부풀고 소득금액이 줄어든다
test('안 적은 해는 후보를 내놓지 않는다', () => {
  expect(closingInventoryOf(draft([field({ value: null })]))).toBeUndefined();
  expect(closingInventoryOf(draft([field({})]))).toBeUndefined();
  expect(closingInventoryOf(draft([]))).toBeUndefined();
  expect(closingInventoryOf(undefined)).toBeUndefined();
});

test('기초재고액을 기말재고액으로 착각하지 않는다', () => {
  expect(closingInventoryOf(draft([field({ fieldKey: 'OPENING_INVENTORY', value: 999 })]))).toBeUndefined();
});
