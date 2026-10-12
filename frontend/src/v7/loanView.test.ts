import { expect, test } from 'vitest';
import type { LoanItem } from '@jangoyebo/core';
import { dayLabel, loanSummary, monthLabel, parseAmount, ratePercent, repaymentLabel } from './loanView';

// 서버는 비율로 보낸다. 곱하기 100 의 부동소수 찌꺼기와 뒤의 0 을 남기지 않는다
test('금리는 비율을 퍼센트로 바꾼다', () => {
  expect(ratePercent(0.052)).toBe('5.2%');
  expect(ratePercent(0.195)).toBe('19.5%');
  expect(ratePercent(0.0525)).toBe('5.25%');
  expect(ratePercent(0.05)).toBe('5%');
  expect(ratePercent(undefined)).toBe('—');
});

const base: LoanItem = {
  loanId: 1,
  loanType: 'TERM_LOAN',
  lenderName: '싸피은행',
  productName: '운영자금',
  status: 'REPAYING',
  interestRate: 0.084,
  repaymentCycle: 'DAILY',
  dailyPaymentAmount: 433698,
  startedOn: '2026-09-01',
  maturityDate: '2027-09-01',
  source: 'FIN_API',
  affectsForecast: true,
};

test('상환 주기마다 문구가 다르다', () => {
  expect(repaymentLabel(base).main).toBe('매일 433,698원');
  expect(
    repaymentLabel({ ...base, repaymentCycle: 'MONTHLY', monthlyPaymentDay: 12, monthlyPaymentAmount: 570000 }).main,
  ).toBe('매월 12일 570,000원');
  const interest = repaymentLabel({ ...base, repaymentCycle: 'INTEREST_MONTHLY', monthlyPaymentAmount: 36960 });
  expect(interest.main).toBe('매월 36,960원');
  expect(interest.sub).toBe('이자만 냅니다');
});

test('날짜와 달은 시각 없이 읽는다', () => {
  expect(dayLabel('2026-09-14')).toBe('9월 14일');
  expect(dayLabel(undefined)).toBe('—');
  expect(monthLabel('2026-10', '2026-09')).toBe('10월');
  expect(monthLabel('2027-01', '2026-12')).toBe('2027년 1월');
});

test('입력 금액은 쉼표와 원을 걷어 읽는다', () => {
  expect(parseAmount('1,000,000')).toBe(1000000);
  expect(parseAmount('500만')).toBeNaN();
  expect(parseAmount('3000000원')).toBe(3000000);
});

// 끝난 대출은 세지 않고, 남은 금액을 모르는 대출이 끼면 합계를 내지 않는다
test('지갑 카드용 대출 요약', () => {
  const s = loanSummary([
    base,
    { ...base, loanId: 2, dailyPaymentAmount: 78908, nextPaymentDate: '2026-09-18', balance: 820000, affectsForecast: false },
    { ...base, loanId: 3, status: 'CLOSED', dailyPaymentAmount: 5426, balance: 0 },
    { ...base, loanId: 4, repaymentCycle: 'MONTHLY', nextPaymentDate: '2026-09-25', balance: 1000000 },
  ]);
  expect(s.active).toHaveLength(3);
  expect(s.dailyTotal).toBe(433698 + 78908);
  expect(s.nextDate).toBe('2026-09-18');
  expect(s.balanceTotal).toBeNull();
  expect(s.offForecast).toBe(1);
  expect(loanSummary([{ ...base, balance: 100 }]).balanceTotal).toBe(100);
  expect(loanSummary([]).nextDate).toBeUndefined();
});
