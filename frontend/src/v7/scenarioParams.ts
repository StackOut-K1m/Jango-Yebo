import { formatManwon } from '@jangoyebo/core';
import type { ScenarioParameter } from '@jangoyebo/core';

/**
 * 조건 한 줄이 **요청 파라미터**와 **사람이 읽는 말**로 바뀌는 곳.
 *
 * 폼에서 떼어 둔 이유는 하나다 — 여기가 명세 11.1 계약과 맞닿는 자리라 눈으로만 확인하고
 * 넘기면 안 된다. 특히 `patternId` 는 <b>있고 없고가 뜻을 바꾼다</b>(없으면 입금 전체).
 */
export interface ParamRow {
  type: string;
  /** 빈 문자열이면 「입금 전체」 — 명세 11.1 에서 키를 생략한 것과 같은 뜻이다. */
  patternId: string;
  delayDays: string;
  rate: string;
  date: string;
  amount: string;
  title: string;
  plannedTransactionId: string;
  newDate: string;
  /** 상품별 매출 변화. 이름을 그대로 넣어도 서버가 같은 규칙으로 정규화한다(14.5). */
  productKey: string;
  /** 퍼센트로 적는다. 빈 문자열이면 <b>보내지 않는다</b> — 둘 중 하나만 넣어도 된다. */
  quantityRate: string;
  priceRate: string;
  /** 대출 실행. 이자율은 퍼센트로 적고 보낼 때 비율로 바꾼다 — 4.5 를 그대로 보내면 450% 다. */
  interestRate: string;
  loanPeriodDays: string;
  repaymentCycle: string;
}

/**
 * 돈으로 읽히는 문자열인가. 쉼표는 있어도 되고 0 원은 안 된다.
 *
 * <b>한 곳에 둔다.</b> 추가 지출과 대출 실행이 같은 검사를 따로 적고 있었는데, 옮겨 적는
 * 사이에 `\d` 의 역슬래시가 빠져 `/^d[d,]*$/` 가 됐다 — 숫자 대신 문자 d 를 찾게 되어
 * 대출 금액은 무엇을 넣어도 통과하지 못했다(S15P21C105-238).
 */
export const isMoney = (v: string) => /^\d[\d,]*$/.test(v) && digits(v) > 0;

/** `1,500,000` 처럼 찍어 넣은 금액을 숫자로. */
export const digits = (v: string) => Number(v.replace(/,/g, ''));

/**
 * 고른 패턴을 파라미터에 얹는다. 고르지 않았으면 <b>키를 아예 빼서</b> 보낸다 —
 * `0` 이나 `null` 을 실으면 「전체」가 아니라 「0번 패턴」으로 읽힐 수 있다.
 */
const onPattern = (v: ParamRow) => (v.patternId ? { patternId: Number(v.patternId) } : {});

export function toParameter(v: ParamRow): ScenarioParameter {
  switch (v.type) {
    case 'SETTLEMENT_DELAY':
      return { type: 'SETTLEMENT_DELAY', delayDays: Number(v.delayDays), ...onPattern(v) };
    case 'DEPOSIT_DECREASE':
      return { type: 'DEPOSIT_DECREASE', rate: Number(v.rate) / 100, ...onPattern(v) };
    case 'SALES_CHANGE':
      return { type: 'SALES_CHANGE', rate: Number(v.rate) / 100 };
    case 'PRODUCT_SALES_CHANGE': {
      // 둘 중 하나만 넣어도 된다. 빈 칸을 0 으로 실어 보내면 「변화 없음」이 아니라
      // 「0% 로 만든다」가 되어 매출이 사라진다 — 비어 있으면 키를 뺀다.
      const quantity = v.quantityRate.trim();
      const price = v.priceRate.trim();
      return {
        type: 'PRODUCT_SALES_CHANGE',
        productKey: v.productKey.trim(),
        ...(quantity ? { quantityRate: Number(quantity) / 100 } : {}),
        ...(price ? { priceRate: Number(price) / 100 } : {}),
      };
    }
    case 'LOAN_EXECUTION':
      return {
        type: 'LOAN_EXECUTION',
        amount: digits(v.amount),
        // 화면은 사람이 읽는 퍼센트(4.5)를 받고 계약은 비율(0.045)이다. 그대로 보내면 450% 가 된다.
        interestRate: Number(v.interestRate) / 100,
        loanPeriodDays: Number(v.loanPeriodDays),
        repaymentCycle: v.repaymentCycle as 'DAILY' | 'MONTHLY' | 'INTEREST_MONTHLY',
        disbursementDate: v.date,
      };
    case 'EXPENSE_DATE_SHIFT':
      return {
        type: 'EXPENSE_DATE_SHIFT',
        plannedTransactionId: Number(v.plannedTransactionId),
        newDate: v.newDate,
      };
    default:
      return {
        type: v.type as 'EXTRA_EXPENSE',
        date: v.date,
        amount: digits(v.amount),
        title: v.title.trim(),
      };
  }
}

/**
 * 저장 목록 한 줄의 요약. 계산하지 않고 넣은 가정을 말로 옮긴다.
 *
 * `names` 는 패턴 이름 표다. 「31번 패턴 지연」 은 사람이 읽을 말이 아니라 이름으로 옮긴다 —
 * 표에 없으면(지워진 패턴 등) 그냥 「정산 2일 지연」 으로 둔다. 번호를 노출하지 않는다.
 */
export function describe(p: ScenarioParameter, names: Map<number, string> = new Map()): string {
  const on = (id?: number) => (id && names.get(id) ? `${names.get(id)} ` : '');
  switch (p.type) {
    case 'SETTLEMENT_DELAY':
      return `${on(p.patternId)}정산 ${p.delayDays}일 지연`;
    case 'DEPOSIT_DECREASE':
      return `${on(p.patternId)}입금 ${Math.round(p.rate * 100)}% 감소`;
    case 'EXTRA_EXPENSE':
    case 'EXTRA_DEPOSIT':
      return `${p.title} ${p.type === 'EXTRA_EXPENSE' ? '−' : '+'}${formatManwon(p.amount)}`;
    case 'EXPENSE_DATE_SHIFT':
      return `지출일 ${p.newDate}로 변경`;
    case 'SALES_CHANGE':
      // 부호를 직접 찍지 않는다 — 하이픈은 글꼴에서 붙임표로 읽힌다(빼기 기호는 U+2212)
      return `매출 ${signedPercent(p.rate)} 변화`;
    case 'PRODUCT_SALES_CHANGE': {
      // 둘 중 하나는 반드시 온다(서버 검증). 둘 다 오면 둘 다 적는다.
      const parts: string[] = [];
      if (p.quantityRate !== undefined) parts.push(`수량 ${signedPercent(p.quantityRate)}`);
      if (p.priceRate !== undefined) parts.push(`가격 ${signedPercent(p.priceRate)}`);
      return `${p.productKey} ${parts.join(' · ')}`;
    }
    case 'LOAN_EXECUTION':
      return `대출 ${formatManwon(p.amount)} ${p.loanPeriodDays}일 (${p.disbursementDate})`;
  }
}

/** 증감률이라 부호를 붙인다. 음수는 빼기 기호(U+2212)다 — 하이픈은 붙임표로 읽힌다 */
function signedPercent(rate: number): string {
  const percent = Math.round(rate * 100);
  return percent < 0 ? `−${Math.abs(percent)}%` : `+${percent}%`;
}
