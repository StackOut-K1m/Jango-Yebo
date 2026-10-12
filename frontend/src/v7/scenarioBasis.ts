import { formatManwon, formatManwonRange } from '@jangoyebo/core';
import type { PlannedTransaction, RecurringPattern, ScenarioParameter } from '@jangoyebo/core';
import { cycleLabel } from './patternView';

/**
 * 「이 결과의 근거」 — 곡선이 <b>왜 이렇게 나왔는지</b> (S15P21C105-268).
 *
 * 결과를 다시 말하지 않는다. 위의 비교표가 무엇이 어떻게 됐는지를 이미 보여 주므로, 여기서는
 * **그 곡선을 만든 재료**를 댄다.
 *
 * ⚠ **예정 거래 건수를 말하지 않는다.** `usePlanned` 가 `size: 50` 으로 잘라 받는데 응답에
 * 총계가 없어(`Items<T>` — 페이징 필드가 오지 않는다) **50건인지 잘린 50건인지 구분할 수
 * 없다.** 근거에 틀린 수를 적으면 근거가 아니라 오해다. 그래서 세는 것은 **반복 패턴**이다 —
 * 그쪽은 페이징이 없어 전부 온다.
 *
 * ⚠ **사장님이 직접 적은 값은 근거가 아니다.** 「9월 29일에 3,000만원 출금」은 방금 본인이
 * 넣은 것이라 되읽어 줘도 아는 것이 늘지 않는다. 가정 설명은 <b>넣지 않은 사실을 드러낼 때만</b>
 * 적는다 — 지목한 패턴의 주기·평소 금액, 옮긴 지출의 원래 날짜·금액 같은 것.
 *
 * ⚠ **못 대는 근거가 하나 있다.** `PlannedTransaction` 에 `patternId` 가 없어
 * (`source: 'PATTERN'` 까지만 온다) 어느 패턴이 만든 예정 거래인지 잇지 못한다. 그래서
 * 「배달 정산의 예정 입금 4건이 밀렸습니다」를 쓸 수 없다. 백엔드가 그 필드를 열면 붙는다.
 */

export interface Basis {
  /** 곡선이 무엇으로 만들어졌나 */
  inputs: string[];
  /**
   * 금액이 큰 패턴부터. 한 줄에 「·」로 이어 붙였더니 이름·주기·금액이 세 개씩 붙어
   * 어디까지가 한 패턴인지 눈으로 끊기지 않았다 — 목록으로 그린다.
   */
  ranking: { patternId: number; text: string }[];
  /** 가정이 드러낸 사실. **사장님이 적은 값을 되읽지 않는다** — 없으면 빈 배열 */
  touched: string[];
}

/** `2026-03-05` → `3월 5일`. 시나리오 창이 길어야 30일이라 해를 빼도 헷갈리지 않는다 */
export function shortDate(iso: string): string {
  const m = /^(\d{4})-(\d{2})-(\d{2})/.exec(iso);
  return m ? `${Number(m[2])}월 ${Number(m[3])}일` : iso;
}

/** 패턴 한 줄 — 이름 · 주기 · 평소 금액 */
function patternFact(p: RecurringPattern): string {
  const { min, max } = p.amountRange;
  return `${p.name}(${cycleLabel(p.cycle)} · 평소 ${formatManwonRange(min, max)})`;
}

/** 곡선을 가장 크게 흔드는 것부터. 정렬일 뿐 새로 계산하지 않는다 */
const BIGGEST = 3;

function inputLines(patterns: RecurringPattern[], days: number): string[] {
  if (patterns.length === 0) {
    return [
      `이 ${days}일 곡선은 아직 반복 패턴 없이 그려졌습니다. 거래가 쌓이면 반복을 찾아 예정 거래로 넣습니다.`,
    ];
  }

  const deposits = patterns.filter((p) => p.direction === 'DEPOSIT').length;
  const lines = [
    `이 ${days}일 곡선은 반복 패턴 ${patterns.length}개(입금 ${deposits} · 출금 ${patterns.length - deposits})가 만든 예정 거래로 그려집니다.`,
  ];

  // 확신이 낮은 패턴이 섞였는지가 곡선을 얼마나 믿을지를 정한다(도메인 규칙 1)
  const count = (c: RecurringPattern['confidence']) => patterns.filter((p) => p.confidence === c).length;
  const grades = (
    [
      ['높음', count('HIGH')],
      ['보통', count('MEDIUM')],
      ['낮음', count('LOW')],
      ['예측 어려움', count('UNPREDICTABLE')],
    ] as const
  )
    .filter(([, n]) => n > 0)
    .map(([label, n]) => `${label} ${n}개`);
  const shaky = count('LOW') + count('UNPREDICTABLE');
  lines.push(
    `확신 ${grades.join(' · ')}.${shaky > 0 ? ' 확신이 낮은 패턴이 섞인 만큼 곡선도 그만큼 흔들립니다.' : ''}`,
  );

  return lines;
}

/** 스무 개를 다 적으면 아무것도 안 읽힌다 — 곡선을 가장 크게 흔드는 것만 짚는다 */
function rankingOf(patterns: RecurringPattern[]): Basis['ranking'] {
  return [...patterns]
    .sort((a, b) => b.amountRange.expected - a.amountRange.expected)
    .slice(0, BIGGEST)
    .map((p) => ({ patternId: p.patternId, text: patternFact(p) }));
}

/**
 * 가정이 드러낸 사실.
 *
 * **넣은 값을 되읽지 않는다.** 직접 적은 입출금·비율·대출 조건은 사장님이 방금 쓴 것이라
 * 근거가 되지 못한다. 우리 자료에서 끌어온 것만 — 지목한 패턴, 옮긴 지출의 원래 모습 — 적는다.
 */
function touchedLine(
  p: ScenarioParameter,
  patterns: RecurringPattern[],
  planned: PlannedTransaction[],
): string | null {
  const found = (id?: number) => (id === undefined ? undefined : patterns.find((x) => x.patternId === id));
  switch (p.type) {
    case 'SETTLEMENT_DELAY': {
      const hit = found(p.patternId);
      return hit ? `${patternFact(hit)} — 이 패턴의 입금을 ${p.delayDays}일씩 미뤘습니다.` : null;
    }
    case 'DEPOSIT_DECREASE': {
      const hit = found(p.patternId);
      return hit
        ? `${patternFact(hit)} — 이 패턴의 입금을 ${Math.abs(Math.round(p.rate * 100))}% 줄였습니다.`
        : null;
    }
    case 'EXPENSE_DATE_SHIFT': {
      const hit = planned.find((x) => x.plannedTransactionId === p.plannedTransactionId);
      return hit
        ? `${hit.title} — 원래 ${shortDate(hit.originalDate)} · ${formatManwon(hit.amountExpected)} 나갈 것이었습니다.`
        : null;
    }
    // 아래는 전부 사장님이 적은 값 그대로다. 되읽어 줄 이유가 없다
    case 'EXTRA_EXPENSE':
    case 'EXTRA_DEPOSIT':
    case 'SALES_CHANGE':
    case 'PRODUCT_SALES_CHANGE':
    case 'LOAN_EXECUTION':
      return null;
  }
}

export function scenarioBasis({
  parameters,
  planned,
  patterns,
  days,
}: {
  parameters: ScenarioParameter[];
  planned: PlannedTransaction[];
  patterns: RecurringPattern[];
  days: number;
}): Basis {
  return {
    inputs: inputLines(patterns, days),
    ranking: rankingOf(patterns),
    touched: parameters
      .map((p) => touchedLine(p, patterns, planned))
      .filter((line): line is string => line !== null),
  };
}
