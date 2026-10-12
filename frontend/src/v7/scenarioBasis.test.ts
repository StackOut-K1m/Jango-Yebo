import { expect, test } from 'vitest';
import type { Cycle, PlannedTransaction, RecurringPattern, ScenarioParameter } from '@jangoyebo/core';
import { scenarioBasis, shortDate } from './scenarioBasis';

const plan = (over: Partial<PlannedTransaction> = {}): PlannedTransaction =>
  ({
    plannedTransactionId: 1,
    source: 'PATTERN',
    status: 'EXPECTED',
    direction: 'WITHDRAWAL',
    title: '월세',
    expectedDate: '2026-03-05',
    originalDate: '2026-03-05',
    amountExpected: 1_200_000,
    amountMin: 1_200_000,
    amountMax: 1_200_000,
    ...over,
  }) as PlannedTransaction;

/** 해당 없는 칸은 null 이다 — WEEKLY 면 dayOfWeek 만 찬다 */
const cycle = (over: Partial<Cycle>): Cycle => ({
  type: 'WEEKLY',
  dayOfWeek: null,
  intervalDays: null,
  dayOfMonth: null,
  ...over,
});

const pattern = (over: Partial<RecurringPattern> = {}): RecurringPattern =>
  ({
    patternId: 31,
    direction: 'DEPOSIT',
    name: '배달 정산',
    cycle: cycle({ type: 'WEEKLY', dayOfWeek: 5 }),
    amountRange: { min: 1_800_000, max: 2_200_000, expected: 2_000_000 },
    confidence: 'HIGH',
    ...over,
  }) as RecurringPattern;

const basis = (
  parameters: ScenarioParameter[],
  patterns: RecurringPattern[] = [],
  planned: PlannedTransaction[] = [],
  days = 30,
) => scenarioBasis({ parameters, planned, patterns, days });

test('날짜는 월·일로 줄이고 못 읽는 값은 그대로 둔다', () => {
  expect(shortDate('2026-03-05')).toBe('3월 5일');
  expect(shortDate('언젠가')).toBe('언젠가');
});

// 곡선을 만든 것은 반복 패턴이다. 예정 거래 건수는 믿을 수 없어 말하지 않는다
test('반복 패턴 수를 방향과 함께 센다', () => {
  const b = basis(
    [],
    [pattern(), pattern({ patternId: 2 }), pattern({ patternId: 3, direction: 'WITHDRAWAL' })],
  );
  expect(b.inputs[0]).toBe('이 30일 곡선은 반복 패턴 3개(입금 2 · 출금 1)가 만든 예정 거래로 그려집니다.');
});

/**
 * 예정 거래는 `size: 50` 으로 잘라 받고 응답에 총계가 없다 — 50건인지 잘린 50건인지 모른다.
 * 근거에 틀린 수가 들어가면 근거가 아니라 오해다.
 */
test('예정 거래 건수는 어디에도 적지 않는다', () => {
  const many = Array.from({ length: 50 }, (_, i) => plan({ plannedTransactionId: i + 1 }));
  const b = basis([], [pattern()], many);
  expect(b.inputs.join(' ')).not.toContain('50');
  expect(b.inputs.join(' ')).not.toContain('예정 거래 ');
});

test('확신도를 세고 낮은 것이 있으면 덧붙인다', () => {
  const shaky = basis([], [pattern(), pattern({ patternId: 2, confidence: 'LOW' })]);
  expect(shaky.inputs[1]).toBe(
    '확신 높음 1개 · 낮음 1개. 확신이 낮은 패턴이 섞인 만큼 곡선도 그만큼 흔들립니다.',
  );

  const solid = basis([], [pattern(), pattern({ patternId: 2 })]);
  expect(solid.inputs[1]).toBe('확신 높음 2개.');
});

// 스무 개를 다 적으면 아무것도 안 읽힌다
test('금액이 큰 패턴 셋만 이름·주기·평소 금액으로 짚는다', () => {
  const b = basis(
    [],
    [
      pattern({ patternId: 1, name: '작은 것', amountRange: { min: 10_000, max: 10_000, expected: 10_000 } }),
      pattern({ patternId: 2, name: '배달 정산' }),
      pattern({
        patternId: 3,
        name: '급여',
        direction: 'WITHDRAWAL',
        cycle: cycle({ type: 'MONTHLY', dayOfMonth: 10 }),
        amountRange: { min: 3_000_000, max: 3_000_000, expected: 3_000_000 },
      }),
      pattern({
        patternId: 4,
        name: '월세',
        direction: 'WITHDRAWAL',
        cycle: cycle({ type: 'MONTHLY', dayOfMonth: 5 }),
        amountRange: { min: 1_200_000, max: 1_200_000, expected: 1_200_000 },
      }),
    ],
  );
  expect(b.ranking.map((r) => r.text)).toEqual([
    '급여(매월 10일 · 평소 300만원)',
    '배달 정산(매주 금요일 · 평소 180~220만원)',
    '월세(매월 5일 · 평소 120만원)',
  ]);
  // 목록의 열쇠로 쓴다 — 이름이 같은 패턴이 둘이어도 줄이 겹치지 않는다
  expect(b.ranking.map((r) => r.patternId)).toEqual([3, 2, 4]);
  expect(b.inputs.join(' ')).not.toContain('작은 것');
});

// 순위는 문단이 아니라 목록이다 — 마침표로 끝내면 줄마다 점이 찍힌다
test('순위 줄에는 마침표를 붙이지 않는다', () => {
  const b = basis([], [pattern()]);
  expect(b.ranking[0].text).toBe('배달 정산(매주 금요일 · 평소 180~220만원)');
  expect(b.ranking[0].text.endsWith('.')).toBe(false);
});

test('패턴이 없으면 순위도 비어 있다', () => {
  expect(basis([], []).ranking).toEqual([]);
});

test('패턴이 없으면 그 사실과 다음에 할 일을 말한다', () => {
  const b = basis([], [], [], 7);
  expect(b.inputs).toHaveLength(1);
  expect(b.inputs[0]).toContain('아직 반복 패턴 없이');
  expect(b.inputs[0]).toContain('거래가 쌓이면');
});

// 지목한 패턴은 사장님이 적지 않은 사실(주기·평소 금액)을 드러낸다 — 이건 근거가 된다
test('지목한 패턴은 주기와 평소 금액을 드러낸다', () => {
  const b = basis([{ type: 'SETTLEMENT_DELAY', delayDays: 15, patternId: 31 }], [pattern()]);
  expect(b.touched).toEqual([
    '배달 정산(매주 금요일 · 평소 180~220만원) — 이 패턴의 입금을 15일씩 미뤘습니다.',
  ]);
});

test('옮긴 지출은 원래 날짜와 금액을 드러낸다', () => {
  const b = basis(
    [{ type: 'EXPENSE_DATE_SHIFT', plannedTransactionId: 7, newDate: '2026-03-20' }],
    [],
    [plan({ plannedTransactionId: 7, originalDate: '2026-03-05', amountExpected: 1_200_000 })],
  );
  expect(b.touched).toEqual(['월세 — 원래 3월 5일 · 120만원 나갈 것이었습니다.']);
});

/**
 * 「9월 29일에 3,000만원 출금」은 방금 본인이 넣은 값이라 되읽어 줘도 아는 것이 늘지 않는다.
 * 비율·대출 조건도 마찬가지다.
 */
test('사장님이 적은 값은 되읽지 않는다', () => {
  const typed: ScenarioParameter[] = [
    { type: 'EXTRA_EXPENSE', date: '2026-09-29', amount: 30_000_000, title: 'test1' },
    { type: 'EXTRA_DEPOSIT', date: '2026-03-05', amount: 500_000, title: '환급' },
    { type: 'SALES_CHANGE', rate: -0.2 },
    { type: 'PRODUCT_SALES_CHANGE', productKey: '아메리카노', quantityRate: -0.1 },
    {
      type: 'LOAN_EXECUTION',
      amount: 20_000_000,
      interestRate: 0.05,
      loanPeriodDays: 365,
      repaymentCycle: 'MONTHLY',
      disbursementDate: '2026-03-05',
    },
  ];
  expect(basis(typed, [pattern()], [plan()]).touched).toEqual([]);
});

// 지워진 패턴을 지목한 저장 시나리오 — 번호를 노출하느니 아무 말도 안 한다
test('지목이 없거나 못 찾으면 줄을 만들지 않는다', () => {
  expect(basis([{ type: 'SETTLEMENT_DELAY', delayDays: 3 }], [pattern()]).touched).toEqual([]);
  expect(basis([{ type: 'DEPOSIT_DECREASE', rate: 0.25, patternId: 999 }], [pattern()]).touched).toEqual([]);
  expect(
    basis([{ type: 'EXPENSE_DATE_SHIFT', plannedTransactionId: 999, newDate: '2026-03-20' }], [], [plan()])
      .touched,
  ).toEqual([]);
});

test('문장에 빈 값이 새지 않는다', () => {
  const b = basis(
    [
      { type: 'SETTLEMENT_DELAY', delayDays: 15, patternId: 31 },
      { type: 'EXPENSE_DATE_SHIFT', plannedTransactionId: 1, newDate: '2026-03-20' },
    ],
    [pattern()],
    [plan()],
  );
  for (const line of [...b.inputs, ...b.touched]) {
    expect(line).toMatch(/\.$/);
    expect(line).not.toMatch(/undefined|NaN|null/);
  }
});
