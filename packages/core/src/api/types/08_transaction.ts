// docs/api/08_거래_분류.md
//
// 중요(구현현황 3.3): 응답에 없는 필드는 null이 아니라 키 자체가 없다.
// 그래서 미구현 필드는 전부 옵셔널로 둔다. 포맷터가 undefined를 —로 받아낸다.
//
// 실측했다(2026-09-08, 로컬 백엔드). 구현현황 3.3과 어긋난 곳은 아래에 표시한다.

import type { Job, PageQuery } from './02_common';

export type Direction = 'DEPOSIT' | 'WITHDRAWAL';
export type ClassificationStatus = 'UNCLASSIFIED' | 'SUGGESTED' | 'CONFIRMED';
export type ClassificationSource = 'RULE' | 'DICTIONARY' | 'AI' | 'USER';

/**
 * 종합소득세 검토 상태(8.7).
 *
 * 자동 판정은 EVIDENCE_CONFIRMED·EVIDENCE_MISSING 둘뿐이고 나머지는 사용자가 지정한다.
 * ASSET_REVIEW는 값만 예약돼 있다(화면 P2) — 지금은 아무도 부여하지 않는다.
 */
export type TaxReviewStatus =
  | 'UNREVIEWED'
  | 'EVIDENCE_CONFIRMED'
  | 'EVIDENCE_MISSING'
  | 'BUSINESS_USE_UNCLEAR'
  | 'PERSONAL_SUSPECTED'
  | 'DUPLICATE_SUSPECTED'
  | 'EXPERT_REVIEW'
  | 'ASSET_REVIEW';

/**
 * 값을 바꾸는 경로는 아직 없다(P0-12 미착수). status는 항상 UNREVIEWED이고 나머지는 null이다.
 * 그래도 키는 항상 있으므로 검토 화면(P1-23)의 열을 미리 붙일 수 있다.
 */
export interface TaxReview {
  status: TaxReviewStatus;
  note: string | null;
  reviewedAt: string | null;
}

export interface TransactionCategory {
  code: string;
  name: string;
  direction: Direction;
}

export interface Classification {
  status: ClassificationStatus;
  confidence?: number;
  needsConfirmation: boolean;
  /** 미구현 — 분류 이력 미착수(구현현황 3.3) */
  source?: ClassificationSource;
  reason?: string;
}

export interface Transaction {
  transactionId: number;
  accountId: number;
  /** UTC로 온다("2026-09-01T01:00:00Z" = KST 10시). 화면에 그대로 찍으면 9시간 어긋난다 */
  occurredAt: string;
  description: string;
  direction: Direction;
  /** 실측은 소수 4자리(1200000.0000)다. 원 단위로 반올림해 표시한다 */
  amount: number;
  balanceAfter?: number | null;
  /** 미분류면 null이다(키는 있다) */
  category?: string | null;
  classification: Classification;

  // 아래 셋은 키가 있고 값이 null이다 — 구현현황 3.3의 "키 자체가 없다"에 해당하지 않는다(실측).
  finTransactionId?: number | null;
  counterpartyAccountNo?: string | null;
  isExcluded?: boolean;

  /** 2026-09-09부터 항상 온다. 값을 바꾸는 경로는 P0-12 착수 후 */
  taxReview: TaxReview;

  /** 아래는 정말로 응답에 키가 없다(구현현황 3.3) */
  merchantId?: number;
  merchantName?: string;
  evidence?: { linked: boolean; evidenceIds: number[] };
}

/**
 * from·to를 생략하면 최근 31일이다(명세대로).
 *
 * size는 100이 상한이고, 넘겨도 서버가 100으로 깎는다.
 *
 * ⚠ 모르는 category·taxReviewStatus 값은 400이다. 무시하면 필터가 안 걸린 전체 목록이
 * 나가고 화면은 그것을 "이 카테고리 거래가 이만큼"으로 읽는다.
 */
export type TransactionQuery = PageQuery & {
  from?: string;
  to?: string;
  accountId?: number;
  direction?: Direction;
  classificationStatus?: ClassificationStatus;
  needsConfirmation?: boolean;
  q?: string;
  /** 8.1의 카테고리 코드 */
  category?: string;
  minAmount?: number;
  maxAmount?: number;
  /** 쉼표로 복수 지정한다 — 'EXPERT_REVIEW,BUSINESS_USE_UNCLEAR' */
  taxReviewStatus?: string;
  /**
   * 해제된 계좌의 거래까지 함께 받는다. 기본은 `false` — 서버가 빼고 준다.
   *
   * 합계에 들어가는 목록에는 켜지 않는다. 연동을 끊은 계좌의 거래를 더하면 화면의 숫자가
   * 그만큼 부풀고, 그 계좌 잔액은 이미 총잔액·곡선에서 빠져 있어 앞뒤가 맞지 않는다.
   * 이력을 보여주는 자리에만 켠다 — `accountId` 로 해제된 계좌를 지목할 때도 함께 보내야 한다.
   */
  includeDisconnected?: boolean;
  // hasEvidence 는 증빙(P0-10) 착수 후에 열린다.
};

export interface UpdateTransactionRequest {
  category?: string;
  /** 실측으로 확인 — 컨트롤러가 받는 것은 memo다. merchantName은 필드 자체가 없다 */
  memo?: string;
  isExcluded?: boolean;
}

/**
 * 진행 중인 분류 작업 (8.5.1).
 *
 * **`job.jobId` 로 진행률을 재지 않는다.** 한 작업이 20건만 맡고 서버가 다음 작업을 **새
 * id 로** 이어 걸기 때문에, 이 id 가 `COMPLETED` 가 되어도 분류는 계속 돈다. 이 응답이
 * 답하는 것은 「지금 돌고 있는가」 하나뿐이고, 진행은 미분류 잔량으로 센다.
 *
 * 도는 것이 없으면 404 가 아니라 `{ running: false }` 다 — 키 자체가 없다(구현현황 3.3).
 */
export interface CurrentClassificationJob {
  running: boolean;
  job?: Job;
}
