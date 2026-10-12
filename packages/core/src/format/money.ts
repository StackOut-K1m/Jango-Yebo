// 금액 표기 규칙(CLAUDE.md).
// 순수 함수라 웹과 앱이 그대로 함께 쓴다.

/** 값이 없을 때 쓰는 표기. 응답에 키 자체가 없는 경우가 흔하다(구현현황 3.3). */
export const EMPTY = '—';

/** 음수 부호는 빼기 기호 U+2212다. 하이픈은 글꼴에서 붙임표로 읽힌다(목업 sky.js). */
const MINUS = '−';

function group(n: number): string {
  return Math.abs(n).toLocaleString('ko-KR');
}

/** 1340000 → '1,340,000원', undefined → '—' */
export function formatMoney(value: number | null | undefined): string {
  if (value === null || value === undefined || Number.isNaN(value)) return EMPTY;
  const sign = value < 0 ? MINUS : '';
  return `${sign}${group(value)}원`;
}

/** 부호 없이 숫자만. 표에서 단위를 헤더에 둘 때 쓴다. */
export function formatAmount(value: number | null | undefined): string {
  if (value === null || value === undefined || Number.isNaN(value)) return EMPTY;
  return `${value < 0 ? MINUS : ''}${group(value)}`;
}

/**
 * 1340000 → '134만원', 108000000 → '1억 800만원'. 만 미만은 그대로 원 단위로 쓴다.
 * 소수점은 쓰지 않는다 — '447.6만'은 돈으로 읽히지 않는다. 자세한 값이 필요하면 formatMoney 를 쓴다.
 */
export function formatManwon(value: number | null | undefined): string {
  if (value === null || value === undefined || Number.isNaN(value)) return EMPTY;
  if (Math.abs(value) < 10000) return formatMoney(value);

  // 만 단위로 먼저 반올림한다. 억으로 먼저 나누면 9,999만 5천원이 '9,999만 10,000만'이 된다.
  const man = Math.round(Math.abs(value) / 10000);
  const eok = Math.floor(man / 10000);
  const rest = man % 10000;
  const text = eok ? `${group(eok)}억${rest ? ` ${group(rest)}만` : ''}` : `${group(rest)}만`;
  return `${value < 0 ? MINUS : ''}${text}원`;
}

/** 예측은 범위로 표시한다: '120~140만원', '9,800만~1억 200만원' (도메인 규칙 1) */
export function formatManwonRange(
  min: number | null | undefined,
  max: number | null | undefined,
): string {
  if (min === null || min === undefined || max === null || max === undefined) return EMPTY;
  const left = formatManwon(min);
  const right = formatManwon(max);
  // 줄여 쓰면 같아지는 범위('100만~100만원')는 범위가 아니다.
  if (left === right) return right;
  // 단위가 같으면 앞쪽 단위를 뗀다. 억이 끼면 '만'까지 떼면 '9,800~1억'으로 잘못 읽힌다.
  const shared = /억/.test(left + right) ? /원$/ : /만원$/;
  return `${shared.test(left) && /[만억]원$/.test(right) ? left.replace(shared, '') : left}~${right}`;
}

/** 0.12 → '12%' */
export function formatPercent(ratio: number | null | undefined, digits = 0): string {
  if (ratio === null || ratio === undefined || Number.isNaN(ratio)) return EMPTY;
  return `${(ratio * 100).toFixed(digits)}%`;
}
