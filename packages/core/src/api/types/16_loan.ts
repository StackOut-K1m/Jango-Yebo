// docs/api/16_대출_신용.md
//
// 대출 현황(16.1) · 신용등급(16.2) · 대출 상품 추천(16.3). P1-13·P1-14.
//
// 서버가 non_null 이라(application.yml) null 필드는 **키가 통째로 빠진다.**
// 널 가능한 것은 전부 `?:` 이고, 조건부로 빠지는 것에는 왜 빠지는지를 달았다.

import type { Confidence } from './02_common';

// --- 16.1 대출 현황 ---------------------------------------------------------

/**
 * ⚠ 지금 내려오는 값은 **항상 `TERM_LOAN`** 이다 — 컨트롤러가 박아 보낸다.
 * 마이너스통장은 보류라(기술스택 4.13) 동기화가 넣지도 않는다. 자리만 잡아 둔 유니언이다.
 */
export type LoanType = 'TERM_LOAN' | 'MINUS_ACCOUNT';

/**
 * 금융망 대출 계좌 상태.
 *
 * `CLOSED` 만 **우리가 판정한다** — 금융망에 그 값이 없어, 만기가 지나고 목록에서
 * 사라진 것을 완납으로 본다.
 */
export type LoanStatus = 'NORMAL' | 'REPAYING' | 'OVERDUE' | 'CLOSED';

/**
 * `DAILY` 가 금융망 대출이다 — 개설 다음날부터 **매일 08:30** 에 출금 계좌에서 빠진다.
 * `MONTHLY` 는 수동 등록분(등록 API 는 아직 없다), `INTEREST_MONTHLY` 는 마이너스통장 이자다.
 */
export type LoanRepaymentCycle = 'DAILY' | 'MONTHLY' | 'INTEREST_MONTHLY';

/** 수동 등록 대출만 채운다. 금융망 대출은 이 키가 없다 */
export type LoanRepaymentType = 'EQUAL_PRINCIPAL_INTEREST' | 'EQUAL_PRINCIPAL' | 'BULLET';

/**
 * 대출 한 건 (16.1).
 *
 * ⚠ **금액 필드 셋이 서로 다른 것을 뜻한다.** `principal` 은 최초 원금,
 * `totalRepayable` 은 원금+이자 총액, `balance` 는 남은 원금+이자다.
 * 금융망 대출은 `principal` 이 없고 `totalRepayable` 이 그 자리를 대신한다.
 */
export interface LoanItem {
  loanId: number;
  loanType: LoanType;
  lenderName: string;
  productName: string;
  status: LoanStatus;
  /** ⚠ **금융망 대출은 항상 없다** — LOAN_08 이 원금을 주지 않는다. 수동 등록만 채운다 */
  principal?: number;
  /** 원금+이자 총액. 일 상환액의 분자다 */
  totalRepayable?: number;
  /** 남은 원금+이자 */
  balance?: number;
  /** ⚠ **비율이다.** 5.2% 는 `0.052` 다 — DB 는 퍼센트고 경계에서 나눠 보낸다 */
  interestRate: number;
  repaymentCycle: LoanRepaymentCycle;
  repaymentType?: LoanRepaymentType;
  /** MONTHLY 계열만. DAILY 는 없다 */
  monthlyPaymentDay?: number;
  monthlyPaymentAmount?: number;
  /**
   * `repaymentCycle === 'DAILY'` 일 때만 있다.
   *
   * ⚠ **대표값이다.** 내림이라 마지막 회차가 이 값과 다르다 —
   * 정확한 회차는 예정 거래 목록(9.1)에 있다. 90회차를 한 줄로 묶어 보여줄 때 쓴다.
   */
  dailyPaymentAmount?: number;
  /** 상환금이 빠지는 계좌. **우리 앱에 없는 계좌에서 빠질 수 있다** — 그때는 없다 */
  withdrawalAccountId?: number;
  startedOn: string;
  maturityDate: string;
  /**
   * ⚠ `DAILY` 면 **항상 내일**이다 — 금융망이 08:30 에 빼가므로 오늘 것은 끝난 것으로 본다.
   * 만기가 지났거나 월 상환일이 없으면 없다.
   */
  nextPaymentDate?: string;
  overdueDays?: number;
  /** 지금은 동기화분뿐이라 항상 `FIN_API` 다 */
  source: 'FIN_API' | 'MANUAL';
  /** 출금 계좌를 아는가. `boolean` 이라 키가 안 빠진다 */
  affectsForecast: boolean;
  /**
   * 예보에 반영되지 않는 이유. **`affectsForecast === false` 일 때만 있다.**
   *
   * 문구가 서버에 박혀 있다 — 화면에서 새로 짓지 말고 그대로 쓴다.
   */
  forecastNote?: string;
}

/**
 * `loans.sync` Job 의 resultRef (16.1).
 *
 * `unattachedCount` 는 **출금 계좌로 사업장을 가르지 못한 대출** 수다 — LOAN_08 이
 * userKey 로 조회해 사업장 개념이 없어, 가게가 둘이면 둘 다 온다.
 */
export interface LoanSyncResult {
  businessId: number;
  loanCount: number;
  unattachedCount: number;
  installmentCount: number;
  /** 대출이 만든 원장 거래를 예측에서 뺀 수. 안 빼면 곡선이 같은 상환을 두 번 뺀다 */
  excludedTransactionCount: number;
  closedCount: number;
  /** 추천 후보 상품(16.3) 동기화 건수. 실패해도 대출 동기화는 계속되므로 0 일 수 있다 */
  productCount: number;
}

// --- 16.2 신용등급 현황·예측 -------------------------------------------------

/** 금융망 LOAN_04 가 주는 자산 3분류 */
export interface CreditAssets {
  demandDeposit?: number;
  depositSavings?: number;
  total?: number;
}

/** @param minAssetValue 이 등급이 되는 최소 자산 */
export interface CreditGradeCriterion {
  grade: string;
  minAssetValue?: number;
}

/**
 * 한 달의 등급 추정.
 *
 * ⚠ `estimatedAsset` 은 **음수가 될 수 있다** — 곡선이 공백에 들어간 달이다.
 */
export interface CreditMonthlyEstimate {
  /** `YYYY-MM` */
  month: string;
  estimatedGrade: string;
  estimatedAsset: number;
}

/**
 * 환산에 깔린 가정. **화면에 같이 내보낸다** — 숨기면 사장님이 확정된 예측으로 읽는다
 * (도메인 규칙 1).
 */
export interface CreditProjectionBasis {
  method: string;
  referenceCurveDays: number;
  assumption: string;
}

/**
 * 16.2 응답.
 *
 * ⚠ **이 GET 은 부작용이 있다** — 호출할 때마다 금융망을 부르고 `CREDIT_SNAPSHOT` 행을 쓴다.
 * 폴링하지 말 것. TanStack `staleTime` 을 길게 잡는다.
 */
export interface CreditStatus {
  baseTime: string;
  /** **대표자 단위다** — 사업장이 여러 개여도 하나다 */
  currentGrade: string;
  gradeScale: string[];
  /** 무엇으로 매긴 등급인지. 「보유 자산 기준 참고 등급」으로 쓰는 근거다 */
  gradeBasis: string;
  assets: CreditAssets;
  gradeCriteria: CreditGradeCriterion[];
  /** 곡선이 없으면 **빈 배열**이다. 오류가 아니라 「아직 잴 것이 없다」는 뜻이다 */
  projection: CreditMonthlyEstimate[];
  /**
   * ⚠ **곡선 신뢰도보다 한 단계 낮다**(추정 위의 추정이다).
   * 숫자로 노출하지 않는다 — 도메인 규칙 1.
   */
  confidence: Confidence;
  basis: CreditProjectionBasis;
  /** 문구가 서버에 있다. 화면에서 새로 짓지 않는다 */
  disclaimer: string;
  /** 곡선이 없으면 그 사유가 여기 온다. 오류가 아니다 */
  warnings: string[];
}

// --- 16.3 대출 상품 추천 -----------------------------------------------------

/**
 * 추천 상품 한 건 (16.3).
 *
 * 순위는 금리가 아니라 **「갚을 수 있는가」** 다 — 상환 후 공백 → 최저 잔액 → 총상환액 → 금리.
 */
export interface LoanProductItem {
  productId: number;
  finProductNo?: string;
  bankName?: string;
  name?: string;
  loanTypeName?: string;
  minGrade?: string;
  minLoanBalance?: number;
  maxLoanBalance?: number;
  /** 여기도 **비율**이다. 4.5% 는 `0.045` */
  interestRate?: number;
  loanPeriodDays: number;
  /**
   * ⚠ 금융망 원문(`"원리금균등상환"`)이라 **대조용이다.**
   * 화면에는 `repaymentMethodDisplay` 를 쓴다 — 실제 동작이 매일 빠지는 것이라
   * 원문을 그대로 쓰면 월납으로 오해한다.
   */
  repaymentMethod?: string;
  /** 화면에 쓸 문구(`"매일 자동이체"`) */
  repaymentMethodDisplay: string;
  totalInterest?: number;
  totalRepayment?: number;
  /** **화면이 제일 먼저 보여줄 숫자다.** 하루에 얼마가 빠지는가 */
  dailyPayment?: number;
  /**
   * 상환을 반영해도 안전선 아래인 날 수.
   *
   * ⚠ 곡선이 없으면 **0** 이다 — 「공백이 없다」가 아니라 「잴 곡선이 없다」인데 값이 같다.
   * `worstBalanceAfter` 가 없으면 곡선이 없는 것으로 읽는다.
   */
  gapDaysAfter: number;
  /** 상환 반영 후 최저 잔액. 곡선이 없으면 없다 */
  worstBalanceAfter?: number;
  /** `is` 가 떨어지지 않는다 — record 라 JSON 키도 `isEligibleNow` 다 */
  isEligibleNow: boolean;
  /** 지금 신청할 수 없는 이유. **신청할 수 있으면 없다** */
  eligibilityNote?: string;
}

/** 1순위 하나. 자격 있는 상품이 없으면 키 자체가 없다 */
export interface LoanRecommendation {
  productId: number;
  message: string;
}

/**
 * 16.3 응답.
 *
 * ⚠⚠ **빈 목록이 기본값이다.** `LOAN_PRODUCT.recommendable` 이 `DEFAULT FALSE` 이고
 * 동기화가 그 컬럼을 갱신 대상에서 **일부러 뺀다** — 팀 apiKey 를 가진 누구나 금융망에
 * 상품을 만들 수 있고 삭제 API 가 없어서, 목록을 그대로 쓰면 남이 시험 삼아 만든 상품이
 * 사장님에게 「이 대출을 받으세요」로 나간다.
 *
 * 그래서 새 DB 에서는 `items: []` 에 `recommendation` 이 없는 **정상 200** 이 온다.
 * 오류가 아니라 빈 상태로 그린다(화면구성도 6.4). 켜는 방법은 `scripts/seed-loan-visibility.mjs` 다.
 *
 * ⚠ **신청 못 하는 상품도 내려온다.** 걸러진 목록이 아니다 — 「등급이 오르면 열리는 상품」으로
 * 보여 주고 16.2 로 잇는다.
 */
export interface LoanProductRecommendation {
  requestedAmount: number;
  currentGrade: string;
  items: LoanProductItem[];
  /** 자격 있는 상품이 하나도 없으면 없다 — 고를 수 없을 때 아무거나 내미는 것은 추천이 아니다 */
  recommendation?: LoanRecommendation;
  disclaimer: string;
}

/**
 * 16.3 질의.
 *
 * ⚠ `amount` 는 선언만 선택이고 **실질 필수**다 — 없거나 0 이하면 400 이다.
 *
 * interface 가 아니라 type 인 이유는 `PageQuery` 와 같다(02_common).
 */
export type LoanProductQuery = {
  amount: number;
};
