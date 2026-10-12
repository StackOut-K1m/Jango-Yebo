// docs/api/12_안전선_준비금_부가세.md
//
// 안전선은 위험 등급 4단계 중 CAUTION·DANGER의 기준선이다.
// 없으면 0원 미만(GAP)만 판정할 수 있어 자금 부족 알림(P0-08)이 반쪽이 된다.

import type { RiskLevel } from './02_common';

/**
 * 산출 방식. P0 범위에서 서버가 실제로 계산해 주는 것은 없다 —
 * 사용자가 스스로 계산해 넣고 근거만 표시한다. 추천값(/recommendations)이 P1이다.
 */
export type SafetyLineMethod = 'MANUAL' | 'FIXED_COST_BASED' | 'ESSENTIAL_EXPENSE_BASED';

/** 안전선 하회의 원인. `plannedTransactionId` 가 없으면 「예정 입금 없음」 같은 관측 사실이다. */
export interface SafetyLineCause {
  date?: string;
  title?: string;
  plannedTransactionId?: number;
}

/**
 * 12.1 `current` — 안전선과 지금 곡선을 비교한 결과.
 *
 * ⚠ **예측 실행이 아직 없으면 `current` 키 자체가 없다.** 오류가 아니라 「아직 계산할 것이
 * 없다」는 뜻이다 — 안전선 조회는 곡선을 만들지도, 예약하지도 않는다.
 *
 * ⚠ `riskLevel` 은 **현재 잔액**의 등급이다. 30일 최저의 등급이 아니다 —
 * 지금은 여유가 있어도(`SAFE`) `firstBelowLineDate` 가 있을 수 있다.
 *
 * ⚠ 하회가 없으면 `shortfallAgainstLine` · `firstBelowLineDate` 가 빠진다.
 * 0으로 채워 오지 않는다 — 「딱 안전선에 붙어 있다」와 다른 말이다.
 *
 * ⚠ 안전선을 정하지 않았어도 `currentBalance` · `next30dLowest*` 는 온다.
 * 금액을 정하려는 사람에게 그 둘이 곧 근거다.
 */
export interface SafetyLineCurrent {
  currentBalance?: number;
  riskLevel?: RiskLevel;
  next30dLowestBalance?: number;
  next30dLowestDate?: string;
  /** 안전선 − 30일 최저. 하회가 없으면 없다 */
  shortfallAgainstLine?: number;
  /** 처음 안전선 아래로 내려가는 날. 하회가 없으면 없다 */
  firstBelowLineDate?: string;
  causes?: SafetyLineCause[];
}

/**
 * ⚠ 미설정이면 404가 아니라 **네 필드가 전부 없는 200**이다.
 * 안전선을 아직 안 정한 것은 오류가 아니라 정상 상태다(화면구성도 6.4).
 *
 * `amount === undefined`로 "설정하기" 유도 화면을 띄운다.
 *
 * ⚠ `current` 는 **예측 실행이 있을 때만** 온다. 설명은 `SafetyLineCurrent`.
 */
export interface SafetyLine {
  amount?: number;
  method?: SafetyLineMethod;
  autoUpdate?: boolean;
  updatedAt?: string;
  current?: SafetyLineCurrent;
}

/**
 * PUT은 전체 교체다. 사업장당 한 행이라 있으면 덮고 없으면 만든다.
 *
 * ⚠ amount 0과 "설정 안 함"은 다르다 — 후자는 이 API를 부르지 않는 것이다.
 *
 * ⚠ 저장하면 서버가 `SAFETY_LINE` 알림을 새 기준으로 다시 맞춘다. 곡선은 다시 돌지
 * 않으므로(공통규약 2.6) 예보를 기다릴 필요는 없지만, 알림 목록은 무효화해야 한다.
 */
export interface PutSafetyLineRequest {
  amount: number;
  /** 생략하면 MANUAL */
  method?: SafetyLineMethod;
  /** 생략하면 false. P0에서는 저장만 하고 자동 갱신 배치는 P1이다 */
  autoUpdate?: boolean;
}
