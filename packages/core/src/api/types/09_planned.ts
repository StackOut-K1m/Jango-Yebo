// docs/api/09_반복_예정거래.md
//
// 예정 거래는 잔액 예측(P0-07)의 직접 입력이다. 곡선은 이 목록을 날짜별로 더해 만든다.
//
// 반복 패턴(9.2·9.3)도 열렸다(2026-09-09).

import type { Direction } from './08_transaction';
import type { PageQuery } from './02_common';

/** 9.1 상태 흐름. 사용자가 등록한 것은 EXPECTED가 아니라 CONFIRMED로 시작한다 */
export type PlannedStatus = 'EXPECTED' | 'CONFIRMED' | 'MATCHED' | 'MISSED' | 'CANCELLED';

/** 삭제 가능 여부가 여기서 갈린다 — USER만 지울 수 있다 */
export type PlannedSource = 'USER' | 'PATTERN' | 'TAX' | 'LOAN';

export interface PlannedTransaction {
  plannedTransactionId: number;
  businessId: number;
  accountId: number | null;
  source: PlannedSource;
  status: PlannedStatus;
  direction: Direction;
  category: string | null;
  title: string;
  /** 공휴일 보정 후 */
  expectedDate: string;
  /** 보정 전. expectedDate와 다르면 보정이 걸린 것이다 */
  originalDate: string;
  /** 파생값이다 — 두 날짜가 다른가. 보정 로직(P1-04) 착수 전이라 지금은 항상 false */
  holidayAdjusted: boolean;
  /**
   * 확정 금액이면 세 값이 같고, 범위면 expected가 중앙이다.
   * 곡선의 낙관·비관 폭이 min~max에서 나온다.
   */
  amountExpected: number;
  amountMin: number;
  amountMax: number;
  matchedTransactionId: number | null;
  memo: string | null;
}

/**
 * from·to는 필수다. 범위를 안 주면 롤링 배치가 만든 90일치가 통째로 나온다.
 *
 * ⚠ 최대 92일이다. 넘기면 400이다.
 */
export type PlannedTransactionQuery = PageQuery & {
  from: string;
  to: string;
  status?: PlannedStatus;
  source?: PlannedSource;
  direction?: Direction;
};

/**
 * 금액은 amount(확정) 또는 amountMin+amountMax(범위) 중 **정확히 한 형태**로 보낸다.
 * 둘 다 보내거나 둘 다 없으면 400이다 — 어느 쪽이 이기는지가 갈리면 같은 요청이
 * 다른 곡선을 만든다.
 */
export interface CreatePlannedTransactionRequest {
  direction: Direction;
  title: string;
  expectedDate: string;
  amount?: number;
  amountMin?: number;
  amountMax?: number;
  /** 8.1의 카테고리 코드. 모르는 코드는 400 */
  category?: string;
  accountId?: number;
  memo?: string;
}

/**
 * 전달한 필드만 바뀐다. direction과 source는 바꿀 수 없다.
 *
 * ⚠ status로 바꿀 수 있는 것은 CANCELLED뿐이다. 나머지는 매칭 배치와 패턴이 정한다.
 */
export interface UpdatePlannedTransactionRequest {
  title?: string;
  expectedDate?: string;
  amount?: number;
  amountMin?: number;
  amountMax?: number;
  category?: string;
  memo?: string;
  status?: 'CANCELLED';
}

// --- 반복 패턴 (9.2~9.3) ---------------------------------------------------

/** BUSINESS_DAILY는 카드 정산이다. INTERVAL:1과 달리 주말을 건너뛴다 */
export type CadenceType = 'WEEKLY' | 'MONTHLY' | 'INTERVAL' | 'BUSINESS_DAILY';

export type PatternStatus = 'CANDIDATE' | 'CONFIRMED' | 'DISMISSED' | 'PAUSED';

export type HolidayRule = 'NEXT_BUSINESS_DAY' | 'PREVIOUS_BUSINESS_DAY' | 'NONE';

/**
 * 숫자가 아니라 구간이다. "0.81"은 정밀해 보이지만 근거는 표본 12건이라
 * 도메인 규칙 1(확정 표현 금지)에 걸린다.
 *
 * UNPREDICTABLE은 오류가 아니라 상태다 — 계산은 됐지만 신뢰할 수 없다는 뜻이다.
 */
export type ConfidenceBand = 'HIGH' | 'MEDIUM' | 'LOW' | 'UNPREDICTABLE';

/** 해당 없는 필드는 null이다 — WEEKLY면 dayOfWeek만 찬다 */
export interface Cycle {
  type: CadenceType;
  /** 1(월)~7(일) */
  dayOfWeek: number | null;
  intervalDays: number | null;
  dayOfMonth: number | null;
}

export interface RecurringPattern {
  patternId: number;
  businessId: number;
  accountId: number | null;
  status: PatternStatus;
  direction: Direction;
  category: string | null;
  name: string;
  cycle: Cycle;
  amountRange: { min: number; max: number; expected: number };
  /** 승인 전이거나 계산할 수 없으면 null */
  nextExpected: {
    date: string;
    originalDate: string;
    /** 공휴일 보정(P1-04)이 없어 지금은 항상 false */
    holidayAdjusted: boolean;
    amountMin: number;
    amountMax: number;
  } | null;
  holidayRule: HolidayRule;
  confidence: ConfidenceBand;
}

/**
 * 전부 선택값이다. 본문 없이 불러도 된다.
 * 전달한 값으로 패턴을 보정한 뒤 CONFIRMED로 바꾸고 향후 90일 예정 거래를 만든다.
 *
 * ⚠ CANDIDATE가 아니면 409 PATTERN_STATE_CONFLICT다.
 */
export interface ConfirmPatternRequest {
  category?: string;
  amountMin?: number;
  amountMax?: number;
  holidayRule?: HolidayRule;
}

/**
 * status로는 CONFIRMED ↔ PAUSED만 오갈 수 있다. 다른 전이는 409다.
 *
 * ⚠ PAUSED로 바꾸면 미래 EXPECTED 예정 거래가 함께 취소된다 —
 * 패턴만 멈추고 두면 "중지했는데 곡선은 그대로"가 된다.
 */
export interface UpdatePatternRequest {
  category?: string;
  amountMin?: number;
  amountMax?: number;
  holidayRule?: HolidayRule;
  status?: 'CONFIRMED' | 'PAUSED';
}
