// docs/api/13_증빙_품목.md
//
// 증빙 업로드(13.1) → 워커 추출 → 검토·확정(13.2·13.3) → 거래 연결(13.4) · 종류 수정·삭제(13.5) · 재추출(13.7).

import type { PageQuery } from './02_common';
import type { Direction } from './08_transaction';

export type EvidenceType =
  | 'RECEIPT'
  | 'TAX_INVOICE'
  | 'INVOICE'
  | 'CASH_RECEIPT'
  | 'SETTLEMENT_STATEMENT'
  | 'CONTRACT'
  | 'PAYROLL'
  | 'OTHER';

/**
 * EXTRACTED 는 오류가 아니라 **사용자 확인 대기**다. 자동 승인이 없어 모든 추출이 이 상태를 거친다.
 * REVIEW 같은 상태는 없다 — 검산이 걸린 것은 needsReview 로 따로 온다.
 */
export type OcrStatus = 'QUEUED' | 'PROCESSING' | 'EXTRACTED' | 'CONFIRMED' | 'FAILED' | 'SKIPPED';

/** 워커가 내는 검산 사유 코드(API 20.4). 모르는 코드가 와도 화면이 깨지지 않게 string 으로 받는다 */
export type ReviewReason =
  | 'AMOUNTS_INCONSISTENT'
  | 'ITEMS_SUM_INCONSISTENT'
  | 'VAT_RATIO_INCONSISTENT'
  | 'ITEM_ROWS_MISALIGNED'
  | 'BUSINESS_NO_INVALID'
  | 'DATE_INVALID'
  | 'TOTAL_MISSING'
  | 'DATE_MISSING'
  | 'SUPPLIER_MISSING'
  | 'LOW_CONFIDENCE';

/** 13.1 응답. 원본은 getEvidenceFile 로 blob 을 받는다(fileUrl 은 인증 헤더가 필요해 그대로 못 쓴다) */
export interface EvidenceUpload {
  evidenceId: number;
  evidenceType: EvidenceType;
  fileName: string;
  ocrStatus: OcrStatus;
  ocrJobId: number;
  linkedTransactionId?: number;
  uploadedAt: string;
}

export interface UploadEvidenceOptions {
  /** 힌트일 뿐이다. 안 주면 OTHER 로 두고 추출이 추정한다 */
  evidenceType?: EvidenceType;
  /** 주면 업로드와 동시에 연결한다. 남의 거래면 404 */
  transactionId?: number;
}

/**
 * 13.2 목록 항목.
 *
 * ⚠ needsReview 는 세 상태다. **키가 없으면 아직 추출 결과가 없다는 뜻**이고 false(검산 통과)가 아니다.
 */
export interface EvidenceSummary {
  evidenceId: number;
  evidenceType: EvidenceType;
  fileName: string;
  ocrStatus: OcrStatus;
  issuedDate?: string;
  totalAmount?: number;
  needsReview?: boolean;
  linked: boolean;
  uploadedAt: string;
}

/**
 * 기간은 **업로드 시각** 기준이다. 발행일은 추출 후에 채워져 그것으로 걸면 갓 올린 증빙이 사라진다.
 *
 * sort 는 여러 번 보낼 수 있다 — 검토 화면은 ['needsReview,desc', 'createdAt,desc'] 로 「먼저 볼 곳」을 올린다.
 */
export type EvidenceQuery = Omit<PageQuery, 'sort'> & {
  from?: string;
  to?: string;
  ocrStatus?: OcrStatus;
  evidenceType?: EvidenceType;
  linked?: boolean;
  needsReview?: boolean;
  sort?: string | string[];
};

export interface ExtractionItem {
  extractionItemId: number;
  rawName?: string;
  quantity?: number;
  unit?: string;
  unitPrice?: number;
  amount?: number;
  discountAmount?: number;
  netAmount?: number;
  matchedItemId?: number;
  matchedBy?: string;
}

export interface ExtractionValidation {
  needsReview?: boolean;
  reviewReasons?: string[];
}

/**
 * 추출 결과. 추출 전이거나 실패했으면 상세에 키째 없다.
 *
 * ⚠ boundingBox 는 없다 — 값이 항상 null 이라 키가 빠진다. 화면은 좌표 대신
 *   validation.needsReview 로 정렬하고 fieldConfidence 로 필드를 강조한다(P1-02 완료 기준).
 * ⚠ fieldConfidence 는 게이트가 아니다. 낮은 필드만 보면 된다는 뜻으로 쓰면 안 된다(13.2).
 */
export interface Extraction {
  extractionId?: number;
  supplierName?: string;
  supplierBusinessNo?: string;
  issuedDate?: string;
  totalAmount?: number;
  vatAmount?: number;
  discountAmount?: number;
  items?: ExtractionItem[];
  fieldConfidence?: Record<string, unknown>;
  /**
   * 모델이 남긴 메모. **판정이 아니다** — 값이 채워진 행에 대한 경고가 남을 수 있고,
   * 그때 그 값이 틀렸다는 뜻이 아니다. 검토 대상은 validation.needsReview 로만 가른다.
   *
   * 그래도 화면에 보여야 하는 이유는 **건너뛴 행**이다. 인쇄물이 흐리면 모델이 읽기를
   * 포기하고 `"6행 흐려서 판독 불가"` 처럼 남기는데, 이걸 안 보이면 사장님은 상품이 왜
   * 빠졌는지 알 방법이 없다 — 인쇄물에 12개인데 화면에 10개만 뜬다.
   *
   * 실측(2026-09-11, 합성 15표본): 흐린 행의 **41% 를 건너뛰고**, 경고가 있는 표본 7개 중
   * 4개는 **읽고도** 「판독 불가」를 남겼다. 그래서 **경고 배지가 아니라 참고 메모**로
   * 그린다 — 붉게 그리면 멀쩡한 행까지 의심하게 된다.
   *
   * 근거: ai/experiments/README.md 「중간 흐림 측정」
   */
  warnings?: string[];
  validation?: ExtractionValidation;
  modelName?: string;
  promptVersion?: string;
  pipeline?: string;
  status?: string;
  extractedAt?: string;
  confirmedAt?: string;
}

export interface EvidenceDetail {
  evidenceId: number;
  evidenceType: EvidenceType;
  fileName: string;
  ocrStatus: OcrStatus;
  extraction?: Extraction;
  linkedTransactionIds?: number[];
  uploadedAt: string;
}

/**
 * 13.4 연결 후보. **점수는 판정이 아니라 정렬 기준이다**(구현현황 3.29) — 금액 일치 0.60, 같은 날 0.25,
 * 상호 유사 0.15. 발행일을 모르면 빈 목록이다(오류가 아니라 아직 기준이 없다).
 *
 * autoLinkEligible 은 금액·날짜가 맞고 그런 후보가 하나뿐일 때만 켜진다. ⚠ 서버는 표시만 하고 붙이지
 * 않는다 — 화면이 기본 선택으로 띄우고 사장님이 한 번 보게 한다.
 */
export interface MatchCandidate {
  transactionId: number;
  occurredAt: string;
  description: string;
  amount: number;
  /** 점수에 방향을 넣지 않았다 — 같은 금액의 입금·출금을 화면이 가르게 싣는다 */
  direction: Direction;
  score: number;
  matchedBy: string[];
  autoLinkEligible: boolean;
}

/** 13.7 응답. 이 뒤로 ocrStatus 는 PROCESSING 이다 */
export interface ReExtractResult {
  evidenceId: number;
  ocrJobId: number;
  extractionMode: string;
}

/** extractionItemId 로 행을 찾는다. 없는 id 는 무시되고 행 추가는 없다 */
export interface ExtractionItemPatch {
  extractionItemId: number;
  rawName?: string;
  quantity?: number;
  unit?: string;
  unitPrice?: number;
  amount?: number;
  discountAmount?: number;
}

/**
 * 13.3. **보낸 필드만 바뀐다** — 빼는 것이 「안 바꿈」이고 값을 지우는 경로는 없다.
 *
 * confirmed: true 는 고친 것이 없어도 확인 시각을 남긴다(실사용 정확도의 분모).
 * ocrStatus 가 EXTRACTED·CONFIRMED 가 아니면 409 EVIDENCE_STATE_CONFLICT 다.
 */
export interface ExtractionPatch {
  supplierName?: string;
  supplierBusinessNo?: string;
  issuedDate?: string;
  totalAmount?: number;
  vatAmount?: number;
  discountAmount?: number;
  items?: ExtractionItemPatch[];
  confirmed?: boolean;
}
