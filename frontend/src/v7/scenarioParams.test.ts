import { expect, test } from 'vitest';
import { describe as say, isMoney, toParameter } from './scenarioParams';
import type { ParamRow } from './scenarioParams';

const row = (over: Partial<ParamRow> = {}): ParamRow => ({
  type: 'SETTLEMENT_DELAY',
  patternId: '',
  delayDays: '2',
  rate: '10',
  date: '',
  amount: '',
  title: '',
  plannedTransactionId: '',
  newDate: '',
  productKey: '',
  quantityRate: '',
  priceRate: '',
  interestRate: '4.5',
  loanPeriodDays: '365',
  repaymentCycle: 'DAILY',
  ...over,
});

// 명세 11.1 — patternId 를 생략하면 입금 패턴 전체다. 0 이나 null 을 실으면 뜻이 달라진다
test('패턴을 고르지 않으면 patternId 키가 아예 없다', () => {
  expect('patternId' in toParameter(row())).toBe(false);
  expect('patternId' in toParameter(row({ type: 'DEPOSIT_DECREASE' }))).toBe(false);
});

test('고른 패턴은 숫자로 실려 나간다', () => {
  expect(toParameter(row({ patternId: '31' }))).toEqual({
    type: 'SETTLEMENT_DELAY',
    delayDays: 2,
    patternId: 31,
  });
  expect(toParameter(row({ type: 'DEPOSIT_DECREASE', patternId: '7', rate: '25' }))).toEqual({
    type: 'DEPOSIT_DECREASE',
    rate: 0.25,
    patternId: 7,
  });
});

// 패턴을 고를 수 없는 조건에까지 patternId 가 새면 서버가 읽을 수 없는 본문이 된다
test('패턴을 받지 않는 조건에는 붙지 않는다', () => {
  const extra = toParameter(row({ type: 'EXTRA_EXPENSE', patternId: '31', amount: '1,500,000', title: ' 머신 교체 ', date: '2026-10-01' }));
  expect(extra).toEqual({
    type: 'EXTRA_EXPENSE',
    date: '2026-10-01',
    amount: 1_500_000,
    title: '머신 교체',
  });
});

test('요약은 번호가 아니라 이름으로 말한다', () => {
  const names = new Map([[31, '카드사 정산']]);
  expect(say({ type: 'SETTLEMENT_DELAY', delayDays: 2, patternId: 31 }, names)).toBe(
    '카드사 정산 정산 2일 지연',
  );
  // 이름을 모르면 번호를 드러내지 않고 그냥 둔다
  expect(say({ type: 'SETTLEMENT_DELAY', delayDays: 2, patternId: 99 }, names)).toBe('정산 2일 지연');
  expect(say({ type: 'SETTLEMENT_DELAY', delayDays: 2 }, names)).toBe('정산 2일 지연');
});

// 가상 시나리오가 조건 한 줄로 들어오려면 매출 변화도 요청으로 옮겨져야 한다
test('매출 변화는 부호를 지키며 비율로 나간다', () => {
  expect(toParameter(row({ type: 'SALES_CHANGE', rate: '-10' }))).toEqual({
    type: 'SALES_CHANGE',
    rate: -0.1,
  });
  expect(toParameter(row({ type: 'SALES_CHANGE', rate: '25' }))).toEqual({
    type: 'SALES_CHANGE',
    rate: 0.25,
  });
});

// 명세 11.1 — interestRate 는 비율이다. 연 4.5% 를 4.5 로 보내면 450% 가 된다
test('대출 이자율은 퍼센트를 비율로 바꿔 보낸다', () => {
  expect(
    toParameter(
      row({
        type: 'LOAN_EXECUTION',
        amount: '3,000,000',
        interestRate: '4.5',
        loanPeriodDays: '365',
        repaymentCycle: 'DAILY',
        date: '2026-10-01',
      }),
    ),
  ).toEqual({
    type: 'LOAN_EXECUTION',
    amount: 3_000_000,
    interestRate: 0.045,
    loanPeriodDays: 365,
    repaymentCycle: 'DAILY',
    disbursementDate: '2026-10-01',
  });
});

// 빈 칸을 0 으로 실어 보내면 「변화 없음」이 아니라 「0% 로 만든다」가 되어 매출이 사라진다
test('상품별 매출은 비운 쪽 키를 빼고 보낸다', () => {
  expect(
    toParameter(row({ type: 'PRODUCT_SALES_CHANGE', productKey: ' 아메리카노 ', quantityRate: '-10' })),
  ).toEqual({ type: 'PRODUCT_SALES_CHANGE', productKey: '아메리카노', quantityRate: -0.1 });

  expect(
    toParameter(row({ type: 'PRODUCT_SALES_CHANGE', productKey: '라떼', priceRate: '5' })),
  ).toEqual({ type: 'PRODUCT_SALES_CHANGE', productKey: '라떼', priceRate: 0.05 });

  const both = toParameter(
    row({ type: 'PRODUCT_SALES_CHANGE', productKey: '라떼', quantityRate: '-10', priceRate: '5' }),
  );
  expect(both).toEqual({
    type: 'PRODUCT_SALES_CHANGE',
    productKey: '라떼',
    quantityRate: -0.1,
    priceRate: 0.05,
  });
});

// 추가 지출과 대출 실행이 같은 검사를 따로 적고 있었고, 옮겨 적는 사이에 \d 의 역슬래시가
// 빠져 대출 금액은 무엇을 넣어도 통과하지 못했다 (S15P21C105-238)
test('금액은 쉼표를 허용하고 0 과 문자를 막는다', () => {
  expect(isMoney('3000000')).toBe(true);
  expect(isMoney('3,000,000')).toBe(true);
  expect(isMoney('1')).toBe(true);

  expect(isMoney('')).toBe(false);
  expect(isMoney('0')).toBe(false);
  expect(isMoney('0,000')).toBe(false);
  expect(isMoney('ddd')).toBe(false);
  expect(isMoney('삼백만')).toBe(false);
  expect(isMoney('-3000')).toBe(false);
  expect(isMoney('3000원')).toBe(false);
  expect(isMoney(',300')).toBe(false);
});

// 하이픈은 글꼴에서 붙임표로 읽힌다 — 음수는 빼기 기호(U+2212)다
test('요약의 음수 비율은 빼기 기호를 쓴다', () => {
  expect(say({ type: 'SALES_CHANGE', rate: -0.2 })).toBe('매출 −20% 변화');
  expect(say({ type: 'SALES_CHANGE', rate: 0.25 })).toBe('매출 +25% 변화');
  expect(say({ type: 'PRODUCT_SALES_CHANGE', productKey: '라떼', quantityRate: -0.1 })).toBe(
    '라떼 수량 −10%',
  );
});
