// docs/api/10_잔액예측_알림.md
//
// 예상 잔액 곡선(P0-07)과 정산 공백(P0-08).
// 잔액 = 현재 잔액 + 예상 입금 − 예상 지출을 날짜별로 누적한 것이고,
// 그 곡선이 0원 아래로 내려가는 구간이 정산 공백이다.

import type { Confidence, RiskLevel } from './02_common';

/**
 * 잔여 현금흐름을 무엇이 만들었나.
 *
 * 셋 다 정상 상태다 — 「모델이 실패해도 곡선은 반드시 산출된다」가 인수 조건이고
 * MODEL → BASELINE → NONE 으로 폴백한다. NONE 이면 사유가 warnings 에 온다.
 */
export type ResidualSource = 'MODEL' | 'BASELINE' | 'NONE';

/**
 * 곡선 위에 찍는 보정 마커.
 *
 * ⚠ P0 에서 실제로 오는 것은 없다. MODEL_RESIDUAL 은 잔여 모델이 붙어야 하고
 * 나머지는 전부 P1 기능이다. 배열이 비어 있는 것이 정상이다.
 */
export type AdjustmentType =
  | 'HOLIDAY_SHIFT'
  | 'SEASONALITY'
  | 'VAT_DUE'
  | 'ACCURACY_CORRECTION'
  | 'MODEL_RESIDUAL';

/** 예측 범위. 금액은 정수 원이다. */
export interface ForecastRange {
  min?: number;
  expected?: number;
  max?: number;
}

/**
 * 잔여 현금흐름 산출 근거.
 *
 * ⚠ `judgmentAlpha` 는 곡선을 재현하는 데 필요한 값이다 — 같은 modelVersion 과 같은
 * 입력이어도 α 가 다르면 부족일이 달라진다. 화면에 띄울 값은 아니고 진단용이다.
 */
export interface ResidualBasis {
  applied?: boolean;
  source?: ResidualSource;
  modelName?: string;
  modelVersion?: string;
  historyDays?: number;
  judgmentAlpha?: number;
  totalDeposit?: ForecastRange;
  totalWithdrawal?: ForecastRange;
}

export interface ForecastBasis {
  confirmedPlannedCount?: number;
  expectedPlannedCount?: number;
  userAddedCount?: number;
  referencePeriod?: string;
  residual?: ResidualBasis;
}

/**
 * 곡선의 하루.
 *
 * ⚠ `expectedDeposit` 은 원장(예정 거래) + 잔여 합계다. 응답에 따로 오는 잔여 기여분을
 * 여기에 더하면 이중 계상이다.
 */
export interface CurvePoint {
  date?: string;
  expectedBalance?: number;
  balanceMin?: number;
  balanceMax?: number;
  expectedDeposit?: number;
  expectedWithdrawal?: number;
  riskLevel?: RiskLevel;
}

export interface LowestPoint {
  date?: string;
  expectedBalance?: number;
}

export interface ForecastAdjustment {
  type?: AdjustmentType;
  description?: string;
  affectedDates?: string[];
  modelName?: string;
  modelVersion?: string;
}

/**
 * 10.1 예상 잔액 곡선.
 *
 * ⚠ `safetyLine` 은 **null 이 의미를 갖는다** — 안전선을 설정하지 않은 상태이고,
 * 그때는 0원 미만(GAP)만 판정한다. 화면은 이 값으로 "설정하기" 유도를 띄운다.
 * 서버가 이 키만은 null 이어도 내려보낸다.
 *
 * ⚠ 실행이 아직 없으면 200 이 아니라 409 FORECAST_NOT_READY 가 온다. 오류 화면이 아니라
 * "예보를 준비하고 있습니다" 상태이며, 그 호출이 계산을 예약하므로 잠시 뒤 다시 부른다.
 * 계좌가 하나도 연결되지 않았으면 422 FORECAST_INSUFFICIENT_DATA 다.
 */
export interface BalanceForecast {
  baseTime?: string;
  days?: number;
  currentBalance?: number;
  /** null = 안전선 미설정. undefined 와 뜻이 다르다 */
  safetyLine?: number | null;
  appliedOptions?: Record<string, boolean>;
  /** `engine-1.1/none` 처럼 계산 엔진과 모델 버전을 함께 담는다 */
  modelVersion?: string;
  curve?: CurvePoint[];
  lowestPoint?: LowestPoint;
  adjustments?: ForecastAdjustment[];
  confidence?: Confidence;
  basis?: ForecastBasis;
  warnings?: string[];
}

/** 공백의 원인. `plannedTransactionId` 가 없으면 「예정 입금 없음」 같은 관측 사실이다. */
export interface CashGapCause {
  date?: string;
  title?: string;
  plannedTransactionId?: number;
}

export interface CashGapSuggestion {
  type?: 'SCENARIO' | 'SUPPORT_PROGRAM' | 'LOAN';
  description?: string;
}

/**
 * 10.2 정산 공백 한 건.
 *
 * ⚠ `cashGapId` 는 이 공백으로 만들어진 **알림의 id** 다. 공백 구간 자체는 저장하지 않는
 * 파생값이라 자기 식별자가 없다. 알림 센터에서 같은 id 로 찾을 수 있다.
 *
 * ⚠ `expectedRecoveryDate` 가 없으면 「조회 기간 안에 회복하지 않는다」는 뜻이다.
 * 「영영 회복하지 않는다」가 아니다 — 창 밖은 계산하지 않았다.
 */
export interface CashGapItem {
  cashGapId?: number;
  firstShortfallDate?: string;
  maxShortfallAmount?: number;
  maxShortfallDate?: string;
  durationDays?: number;
  expectedRecoveryDate?: string;
  riskLevel?: RiskLevel;
  causes?: CashGapCause[];
  suggestions?: CashGapSuggestion[];
}

export interface CashGapList {
  baseTime?: string;
  items?: CashGapItem[];
  confidence?: Confidence;
  basis?: { referenceCurveDays?: number };
  warnings?: string[];
}

/**
 * 10.7 예측 옵션.
 *
 * ⚠ P0 에서 곡선을 실제로 바꾸는 것은 `applyResidualModel` 하나다. 나머지 둘은 해당
 * P1 기능이 서기 전까지 항상 false 로 온다.
 */
export interface ForecastSettings {
  applySeasonality?: boolean;
  applyVatReserve?: boolean;
  applyResidualModel?: boolean;
  peakMonths?: number[];
  offMonths?: number[];
}

/** 명세 밖 임시 — 수동 새로고침 접수 응답. */
export interface ForecastRefreshAccepted {
  businessId?: number;
  status?: string;
}
