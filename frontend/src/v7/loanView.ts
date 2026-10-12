import { formatMoney } from '@jangoyebo/core';
import type { LoanItem, LoanStatus } from '@jangoyebo/core';

/**
 * 대출 화면(SC-10)의 표시 규칙. 화면 파일에서 떼어 둔 것은 테스트하려는 것뿐이다.
 */

/**
 * 비율을 퍼센트로. 서버는 5.2% 를 `0.052` 로 보낸다(16_loan.ts).
 *
 * `formatPercent` 는 자릿수를 고정해 5.2% 가 `5.20%` 로 나온다. 금리는 소수 둘째 자리까지
 * 의미가 있고 뒤의 0 은 뜻이 없어 떼어 낸다. 부동소수(0.052 × 100 = 5.2000…02)도 여기서 걸러진다.
 */
export function ratePercent(rate: number | undefined): string {
  if (rate === undefined || Number.isNaN(rate)) return '—';
  return `${Number((rate * 100).toFixed(2))}%`;
}

/** 색만으로 말하지 않도록 글자와 그림을 함께 쓴다(도메인 규칙 6) */
export const STATUS: Record<LoanStatus, { label: string; icon: string }> = {
  NORMAL: { label: '정상', icon: 'check' },
  REPAYING: { label: '상환 중', icon: 'clock' },
  OVERDUE: { label: '연체', icon: 'warning' },
  CLOSED: { label: '상환 완료', icon: 'check' },
};

/**
 * 한 번에 얼마가 언제 빠지는가.
 *
 * 금융망 대출(`DAILY`)은 매일 08:30 에 빠진다. `dailyPaymentAmount` 는 내림한 대표값이라
 * 마지막 회차가 다르다 — 칸마다 되풀이하지 않고 화면이 표 아래에 한 번 적는다.
 */
export function repaymentLabel(loan: LoanItem): { main: string; sub?: string } {
  if (loan.repaymentCycle === 'DAILY') {
    return {
      main: `매일 ${formatMoney(loan.dailyPaymentAmount)}`,
      sub: '08:30 자동 출금',
    };
  }
  const day = loan.monthlyPaymentDay ? `매월 ${loan.monthlyPaymentDay}일` : '매월';
  const what = loan.repaymentCycle === 'INTEREST_MONTHLY' ? '이자' : '';
  return {
    main: `${day} ${formatMoney(loan.monthlyPaymentAmount)}`,
    sub: what ? `${what}만 냅니다` : undefined,
  };
}

/** '2026-09-14' → '9월 14일'. LocalDate 라 시각이 없다 — formatDate 를 쓰면 하루 밀릴 수 있다 */
export function dayLabel(date: string | undefined): string {
  if (!date) return '—';
  const [, m, d] = date.split('-');
  return m && d ? `${Number(m)}월 ${Number(d)}일` : date;
}

/** '2026-10' → '10월'. 해가 바뀌는 달만 연도를 붙인다 */
export function monthLabel(month: string, previous?: string): string {
  const [y, m] = month.split('-');
  if (!y || !m) return month;
  const yearChanged = previous !== undefined && previous.slice(0, 4) !== y;
  return yearChanged ? `${y}년 ${Number(m)}월` : `${Number(m)}월`;
}

/** 입력칸의 「1,000,000」을 숫자로. 숫자가 아니면 NaN */
export function parseAmount(text: string): number {
  const bare = text.replace(/[,\s원]/g, '');
  return /^\d+$/.test(bare) ? Number(bare) : Number.NaN;
}

/**
 * 지갑 카드(메인)의 대출 요약. 끝난 대출은 세지 않는다 — 이미 빠져나가지 않는 돈이다.
 *
 * `balanceTotal` 은 남은 금액을 **아는 대출만** 더한다. 모르는 것을 0 으로 세면 합계가 거짓말이 되므로
 * 하나라도 모르면 `null` 이다.
 */
export function loanSummary(items: LoanItem[]) {
  const active = items.filter((l) => l.status !== 'CLOSED');
  const dailyTotal = active
    .filter((l) => l.repaymentCycle === 'DAILY')
    .reduce((sum, l) => sum + (l.dailyPaymentAmount ?? 0), 0);
  const nextDate = active
    .map((l) => l.nextPaymentDate)
    .filter((d): d is string => !!d)
    .sort()[0];
  const balanceTotal = active.every((l) => l.balance !== undefined)
    ? active.reduce((sum, l) => sum + (l.balance ?? 0), 0)
    : null;
  return {
    active,
    dailyTotal,
    nextDate,
    balanceTotal,
    offForecast: active.filter((l) => !l.affectsForecast).length,
  };
}
