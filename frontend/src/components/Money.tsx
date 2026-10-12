import { formatManwon, formatManwonRange, formatMoney } from '@jangoyebo/core';

/**
 * 금액 표시. 포맷은 packages/core 가 담당한다(웹·앱 공통).
 *
 * 음수는 −(U+2212), 값이 없으면 —.
 * 만원 축약은 요약 카드에서만 쓰고 상세는 원 단위로 쓴다(화면구성도 6.5).
 */

interface Props {
  value: number | null | undefined;
  /** 요약 카드용. 상세 화면에서는 쓰지 않는다. */
  short?: boolean;
}

export function Money({ value, short = false }: Props) {
  return <span className="tnum">{short ? formatManwon(value) : formatMoney(value)}</span>;
}

/** 예측은 확정값이 아니라 범위로 표기한다(도메인 규칙 1, 화면구성도 6.3). */
export function MoneyRange({
  min,
  max,
}: {
  min: number | null | undefined;
  max: number | null | undefined;
}) {
  return <span className="tnum">{formatManwonRange(min, max)}</span>;
}
