// docs/api/14_정산_내보내기.md 14.3 — 종합소득세 신고서 초안 (P0-12)
//
// 로컬 develop(afaaf496) 컨트롤러·조회 서비스와 구현현황 §3.37·§3.41 로 맞췄다(2026-09-17).
// 명세와 다른 곳:
//  ① 민감 항목(①·④)은 저장하지 않아 **상세 응답에 키 자체가 없다**(명세 예시는 value:null 로 적었다).
//     내보낼 때 물어볼 칸은 서식 목록(`tax-filing-forms`)의 `sensitive: true` 에서 찾는다.
//  ② 매출 근거(basis)에 정산 연결 입금이 `settlementLinked` 로 섞이고 `settlementSummary` 가 붙는다.
//  ③ `excluded.unclassified` 가 더 있다 — 분류를 안 돌린 사업장의 빈 초안을 「매출 0」으로 읽지 않게.
//  ④ 수정 항목에 `aggregatedValue`·`overrideNote`·`confirmed`·`carriedOver` 가 붙는다.
//
// 서버가 non_null 이라 모르는 값은 **키가 빠진다.**

import type { Page } from './02_common';

export type TaxFormType = 'SIMPLE_LEDGER' | 'STANDARD_RATE' | 'SIMPLE_RATE' | 'DOUBLE_ENTRY';

/**
 * `DRAFT`·`DRAFT_OPTIONAL` 만 초안을 만든다. 나머지 둘에 생성을 요청하면 `400 FORM_NOT_SUPPORTED`.
 * 목록에서 빼지 않는 것은 「지원하지 않는 것」과 「빠뜨린 것」을 가르려는 것이다.
 */
export type TaxFormSupportLevel = 'DRAFT' | 'DRAFT_OPTIONAL' | 'GUIDE_ONLY' | 'CHECKLIST_ONLY';

/**
 * 항목 출처. `USER_SENSITIVE` 는 서버에 보내 저장하지 않는다(내보내기 요청 본문에만 싣는다).
 * `USER_CONFIRM` 은 자동 계산하지 않는 항목 — 사용자가 PATCH 로 값을 넣는다.
 */
export type TaxFieldSource =
  | 'AUTO_TRANSACTION'
  | 'AUTO_SETTLEMENT'
  | 'AUTO_PROFILE'
  | 'REFERENCE_HOMETAX'
  | 'USER_SENSITIVE'
  | 'USER_CONFIRM'
  | 'CALCULATED';

export interface TaxFormFieldDefinition {
  fieldKey: string;
  label: string;
  source: TaxFieldSource;
  sensitive?: boolean;
  fixedValue?: number | string;
  /** `CALCULATED` 의 산식. 서버가 푼다 — 화면에서 다시 계산하지 않는다(도메인 규칙 2) */
  formula?: string;
}

export interface TaxFormSection<F> {
  sectionKey: string;
  label: string;
  fields: F[];
}

export interface TaxFilingForm {
  formType: TaxFormType;
  name: string;
  officialForm?: string;
  supportLevel: TaxFormSupportLevel;
  eligibilityNote?: string;
  /** ⚠ 안내만 하는 서식은 **키 자체가 없다** — 빈 배열로 읽어 빈 표를 그리지 않는다 */
  sections?: TaxFormSection<TaxFormFieldDefinition>[];
}

/**
 * 사용자가 홈택스 신고도움서비스에서 옮겨 적는 참고값. 서비스는 신고 유형·기장의무를
 * **판정하지 않는다**(도메인 규칙 4) — 표시와 수입금액 대사에만 쓴다.
 */
export interface HometaxReference {
  filingGuideType?: string;
  bookkeepingDuty?: string;
  industryCode?: string;
  ntsRevenue?: number;
}

export interface CreateTaxDraftRequest {
  taxYear: number;
  formType: TaxFormType;
  /** `YYYY-MM-DD` */
  periodStart: string;
  periodEnd: string;
  hometaxReference?: HometaxReference;
}

/** 202. 같은 (사업장·연도·서식)에 집계 중인 초안이 있으면 그것을 돌려준다 */
export interface TaxDraftCreated {
  draftId: number;
  status: TaxDraftStatus;
  jobId: number;
}

/**
 * `STALE` — 생성 뒤 검토 상태·재분류·정산 대사가 바뀌어 근거가 달라졌다. 지우지 않고 이력으로 남긴다.
 * 갱신은 재생성이고, 재생성은 사용자가 넣은 값을 물려받는다(`carriedOver`).
 */
export type TaxDraftStatus = 'PROCESSING' | 'READY' | 'STALE' | 'FAILED';

/** 목록 한 줄 */
export interface TaxDraftSummary {
  draftId: number;
  formType: TaxFormType;
  status: TaxDraftStatus;
  /** 생성 시점 검토 진행률(0~1). 대상 거래가 없으면 1 이다 */
  basedOnReviewRate?: number;
  periodStart: string;
  periodEnd: string;
  generatedAt?: string;
}

export type TaxFieldStatus = 'FILLED' | 'NEEDS_REVIEW' | 'EMPTY';

export interface TaxDraftBasisSummary {
  transactionCount?: number;
  evidenceCount?: number;
  evidenceMissingCount?: number;
  settlementCount?: number;
  /** 대사가 안 맞은(MISMATCHED·PENDING) 정산도 매출에 센다 — 그 건수 */
  unmatchedSettlementCount?: number;
  /** ㉝ 지급수수료에 정산 수수료가 더해졌나 */
  settlementFeeIncluded?: boolean;
  unreviewedCount?: number;
}

/** 상세의 항목 한 줄. 금액은 원 단위 정수로 온다. 인적사항처럼 글자인 항목도 있다 */
export interface TaxDraftField {
  fieldKey: string;
  label: string;
  value?: number | string | null;
  source?: TaxFieldSource;
  status?: TaxFieldStatus;
  sensitive?: boolean;
  /** 서버 문장이다. 화면에서 새로 짓지 않는다 */
  reviewNote?: string;
  /** 항목 금액에 넣지 않고 따로 둔 금액(설비·비품 → 감가상각 검토) */
  pendingAmount?: number;
  basisSummary?: TaxDraftBasisSummary;
  /** 사용자가 고쳤을 때 원래 집계값 */
  aggregatedValue?: number | string;
  overrideNote?: string;
  confirmed?: boolean;
  /** 재생성이 지난 초안에서 물려받은 값 — 「지난번에 적으신 값」으로 구분해 보인다 */
  carriedOver?: boolean;
}

export interface TaxRevenueCheck {
  /** 서비스 집계(입금·정산 기준) */
  serviceTotal: number;
  /** 사용자가 옮겨 적은 국세청 수입금액 */
  ntsRevenue?: number;
  difference?: number;
  /** POS 판매 총액 — 참고값이며 항목 금액에 반영하지 않는다 */
  productSalesTotal?: number;
  note?: string;
}

export interface TaxExcludedAmount {
  count: number;
  amount: number;
}

export interface TaxDraftDetail {
  draftId: number;
  taxYear: number;
  formType: TaxFormType;
  status: TaxDraftStatus;
  periodStart: string;
  periodEnd: string;
  generatedAt?: string;
  basedOnReviewRate?: number;
  hometaxReference?: HometaxReference;
  /** 참고할 값(국세청 수입금액·POS)이 하나도 없으면 키가 없다 */
  revenueCheck?: TaxRevenueCheck;
  /** 서식이 모르는 항목은 `UNMAPPED` 섹션으로 온다 — 버리지 않는다 */
  sections: TaxFormSection<TaxDraftField>[];
  /** `personalSuspected`·`needsReview`·`unclassified`(명세에 없는 칸) */
  excluded?: Partial<Record<'personalSuspected' | 'needsReview' | 'unclassified', TaxExcludedAmount>>;
  /** 고정 노출한다(도메인 규칙 1·4) */
  disclaimer: string;
}

/**
 * 보낸 항목만 바뀐다. `value: null` 은 「비움」.
 *
 * ⚠ 민감 항목을 보내면 `400 SENSITIVE_FIELD_NOT_STORABLE`, 산식 항목·서식에 없는 키도 `400` 이다.
 * 응답은 산식을 다시 푼 **상세 전체**다.
 */
export interface PatchTaxDraftRequest {
  hometaxReference?: HometaxReference;
  overrides?: { fieldKey: string; value: number | null; note?: string }[];
  confirmedFieldKeys?: string[];
}

export interface TaxDraftBasisItem {
  transactionId: number;
  occurredAt: string;
  description: string;
  amount: number;
  direction: 'DEPOSIT' | 'WITHDRAWAL';
  category: string;
  taxReviewStatus: string;
  /** 정산에 연결된 입금 — 집계에서는 빠지고 근거에만 보인다 */
  settlementLinked?: boolean;
  evidences: { evidenceId: number; evidenceType: string; fileName: string }[];
}

/**
 * 항목 근거. 사용자가 값을 넣는 항목은 **빈 목록**이다(오류 아님).
 * ⚠ 매출 항목은 목록 합과 항목 값이 다르다 — 입금은 수수료를 뗀 금액이라서다. `settlementSummary` 가 그 차이를 설명한다.
 */
export interface TaxDraftBasis extends Page<TaxDraftBasisItem> {
  fieldKey: string;
  settlementSummary?: {
    count: number;
    grossSales: number;
    cancellations: number;
    fees: number;
    note: string;
  };
}

/**
 * 내보내기. 지금은 `XLSX_HOMETAX_INPUT` 만 된다(`PDF` 는 400).
 * `sensitiveValues`(주소지·생년월일)는 **요청 본문에만** 있다 — 서버는 저장하지도 로그에 남기지도 않는다.
 * `READY` 가 아니면 `409 TAX_DRAFT_NOT_READY`.
 */
export interface ExportTaxDraftRequest {
  format: 'XLSX_HOMETAX_INPUT';
  includeBusinessIds?: number[];
  sensitiveValues?: Record<string, string>;
}

/** 8.7 일괄 변경 결과. 되는 것은 반영하고 안 되는 것만 `failed` 로 온다 */
export interface TaxReviewBulkResult {
  updatedCount: number;
  failedCount: number;
  failed: { transactionId: number; reason: string }[];
}
