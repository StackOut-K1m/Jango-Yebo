import type { Cycle, RecurringPattern } from '@jangoyebo/core';

/**
 * 반복 패턴을 사람 말로 옮기는 규칙.
 *
 * 반복·예정 화면과 시나리오 근거가 **같은 패턴을 다른 말로 부르면 안 된다** — 한쪽이
 * 「매주 금요일」인데 다른 쪽이 「WEEKLY」면 같은 것인지 알 수 없다. 그래서 한곳에 둔다.
 */

const CADENCE: Record<Cycle['type'], string> = {
  WEEKLY: '매주',
  MONTHLY: '매월',
  INTERVAL: '일정 간격',
  BUSINESS_DAILY: '영업일마다',
};

/** 1(월)~7(일) */
const WEEKDAY = ['', '월', '화', '수', '목', '금', '토', '일'];

export function cycleLabel(cycle: Cycle): string {
  const base = CADENCE[cycle.type];
  if (cycle.type === 'WEEKLY' && cycle.dayOfWeek) return `${base} ${WEEKDAY[cycle.dayOfWeek]}요일`;
  if (cycle.type === 'MONTHLY' && cycle.dayOfMonth) return `${base} ${cycle.dayOfMonth}일`;
  if (cycle.type === 'INTERVAL' && cycle.intervalDays) return `${cycle.intervalDays}일마다`;
  return base;
}

/** 신뢰 수준은 숫자가 아니라 구간이다(도메인 규칙 1). */
export const PATTERN_CONFIDENCE: Record<RecurringPattern['confidence'], string> = {
  HIGH: '신뢰 높음',
  MEDIUM: '신뢰 보통',
  LOW: '신뢰 낮음',
  UNPREDICTABLE: '예측 어려움',
};
