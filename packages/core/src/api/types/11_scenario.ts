// docs/api/11_시나리오.md
//
// What-if 시나리오(P0-09·P0-13). 원본 예측을 바꾸지 않고 복제해서 계산한다.
// 「적용」은 없다 — 실제 반영은 사용자가 예정 거래를 직접 고치는 것뿐이다.

import type { Confidence } from './02_common';

/** 입금 패턴이 며칠 늦게 들어온다. patternId 를 빼면 입금 패턴 전체다. */
export interface SettlementDelayParameter {
  type: 'SETTLEMENT_DELAY';
  patternId?: number;
  delayDays: number;
}

/** 입금이 그만큼 덜 들어온다. rate 는 0~1 이다(10% 감소 = 0.1). */
export interface DepositDecreaseParameter {
  type: 'DEPOSIT_DECREASE';
  rate: number;
  patternId?: number;
}

export interface ExtraFlowParameter {
  type: 'EXTRA_EXPENSE' | 'EXTRA_DEPOSIT';
  /** 'YYYY-MM-DD' */
  date: string;
  amount: number;
  title: string;
}

/** 예정된 지출의 날짜를 옮긴다. */
export interface ExpenseDateShiftParameter {
  type: 'EXPENSE_DATE_SHIFT';
  plannedTransactionId: number;
  newDate: string;
}

/** 매출성 입금 전체에 곱한다. 음수 허용(−5% = −0.05). */
export interface SalesChangeParameter {
  type: 'SALES_CHANGE';
  rate: number;
}

/**
 * 상품 하나의 수량·가격을 조정한다 (P1-22 연동).
 *
 * `quantityRate`·`priceRate` 중 **하나는 있어야 한다**(둘 다 없으면 400).
 * 둘 다 배율이 아니라 **증감률**이다 — 10% 인상은 `0.1` 이다.
 *
 * ⚠ 같은 상품을 두 번 조정할 수 없다(400). 그 상품의 구성비가 두 번 세어진다.
 *
 * ⚠ 서버는 **가장 최근 등록 기간의 구성비**를 쓴다. 14.5 의 기본 창(이번 달 1일~오늘)을
 * 쓰면 매월 1~2일에 등록이 비어 스퓨리어스 409 가 나기 때문이다.
 *
 * `productKey` 자리에 표시 이름을 넣어도 된다 — 서버가 같은 규칙으로 정규화한다.
 */
export interface ProductSalesChangeParameter {
  type: 'PRODUCT_SALES_CHANGE';
  productKey: string;
  quantityRate?: number;
  priceRate?: number;
}

/**
 * 대출을 실행하면 곡선이 어떻게 되는가 (16.3 미리 보기).
 *
 * ⚠ `interestRate` 는 **비율이다** — 연 4.5% 는 `0.045` 다. `4.5` 를 그대로 보내는 실수가
 * 가장 흔하고, 그러면 연 450% 로 계산돼 곡선이 무너진다(서버가 400 으로 막는다).
 *
 * ⚠ `DAILY` 의 총상환액은 금융망 실측 식 `P·(1 + r·d²/365)` 다 — 정상 단리의 **기간(일)배**다.
 * 16.3 의 `dailyPayment`·`totalRepayment` 와 같은 함수를 타므로 두 화면이 같은 값을 본다.
 *
 * **실행 API 는 없다.** 도메인 규칙 5 — 실제 대출은 사장님이 금융기관에서 직접 한다.
 */
export interface LoanExecutionParameter {
  type: 'LOAN_EXECUTION';
  amount: number;
  /** 비율이다. 연 4.5% 는 0.045 */
  interestRate: number;
  loanPeriodDays: number;
  repaymentCycle: 'DAILY' | 'MONTHLY' | 'INTEREST_MONTHLY';
  /** 'YYYY-MM-DD' */
  disbursementDate: string;
}

/**
 * 서버가 받는 여덟 종 전부다(2026-09-16 구현).
 *
 * ⚠ 이 유니언을 넓히면 화면의 빠짐없는 `switch` 가 컴파일 오류로 드러난다 —
 * 새 파라미터를 추가할 때 문구를 빠뜨리지 않게 하는 장치이므로 `default` 를 달지 말 것.
 */
export type ScenarioParameter =
  | SettlementDelayParameter
  | DepositDecreaseParameter
  | ExtraFlowParameter
  | ExpenseDateShiftParameter
  | SalesChangeParameter
  | ProductSalesChangeParameter
  | LoanExecutionParameter;

export interface CreateScenarioRequest {
  name?: string;
  days: 7 | 14 | 30;
  /** false 면 저장하지 않고 결과만 받는다(200). true 면 201 과 scenarioId */
  save: boolean;
  parameters: ScenarioParameter[];
}

/** 원본·변경 곡선을 요약한 지표. 부족이 없으면 부족일 필드가 없다. */
export interface ScenarioSummary {
  lowestBalance?: number;
  lowestBalanceDate?: string;
  firstShortfallDate?: string;
  gapDurationDays?: number;
  belowSafetyLineDays?: number;
}

export interface ScenarioCurvePoint {
  date?: string;
  expectedBalance?: number;
}

export interface ScenarioResult {
  /** save=false 면 없다 */
  scenarioId?: number;
  name?: string;
  /** 계산 시점. 저장한 시나리오를 다시 열면 그때의 최신 데이터로 다시 계산된다 */
  baseTime?: string;
  days?: number;
  parameters?: ScenarioParameter[];
  comparison?: { original?: ScenarioSummary; modified?: ScenarioSummary };
  originalCurve?: ScenarioCurvePoint[];
  modifiedCurve?: ScenarioCurvePoint[];
  confidence?: Confidence;
  warnings?: string[];
}

/** 저장된 시나리오 목록의 한 줄 */
export interface SavedScenario {
  scenarioId: number;
  name?: string;
  createdAt?: string;
  parameters?: ScenarioParameter[];
}

export interface SavedScenarioList {
  items: SavedScenario[];
}
