import type { RiskLevel } from '../api/types/02_common';

/**
 * 위험 등급 판정 (공통규약 2.6).
 *
 * 곡선·캘린더·매장 카드·알림이 모두 이 한 함수를 쓴다. 웹과 앱이 다르게 판정하면
 * 같은 잔액을 두고 한쪽은 '주의', 다른 쪽은 '안전'이라고 말하게 된다.
 *
 * 숫자만 받는다. API 응답 모양과 무관하므로 서버가 바뀌어도 이 규칙은 그대로다.
 */

/** DANGER 경계 — 안전선의 25% 미만이면 0원에 근접한 것으로 본다. */
export const DANGER_RATIO = 0.25;

export function riskOf(balance: number, safetyLine: number | null): RiskLevel {
  if (balance < 0) return 'GAP';

  // 안전선 미설정이면 0원 기준만 판정한다. 화면은 그 사실을 안내하고 설정을 유도한다
  // (화면구성도 6.1) — 여기서 임의의 기본값을 만들어 넣지 않는다.
  if (safetyLine === null) return 'SAFE';

  if (balance < safetyLine * DANGER_RATIO) return 'DANGER';
  if (balance < safetyLine) return 'CAUTION';
  return 'SAFE';
}
