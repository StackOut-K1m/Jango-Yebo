import { request, requestFile } from './client';
import type { DownloadedFile } from './client';
import type { ApiResult } from './envelope';
import type { TaxReviewStatus } from './types/08_transaction';
import type {
  CreateTaxDraftRequest,
  ExportTaxDraftRequest,
  PatchTaxDraftRequest,
  TaxDraftBasis,
  TaxDraftCreated,
  TaxDraftDetail,
  TaxDraftSummary,
  TaxFilingForm,
  TaxReviewBulkResult,
} from './types/14_taxdraft';

/**
 * 종합소득세 신고서 초안 (API 14.3) + 검토 상태 일괄 변경 (8.7). P0-12.
 *
 * 초안 생성은 AI 를 부르지 않는다. 202 + `TAX_DRAFT_GENERATION` Job 이고 끝나면 `READY` 다.
 */

type Items<T> = { items: T[] };

/** 귀속연도의 서식 목록. 안내만 하는 서식도 함께 온다(`supportLevel`) */
export function listTaxFilingForms(taxYear: number): Promise<ApiResult<Items<TaxFilingForm>>> {
  return request<Items<TaxFilingForm>>('taxForms.list', '/tax-filing-forms', { query: { taxYear } });
}

/** 초안 생성 (202). 같은 연도·서식에 집계 중인 초안이 있으면 그것을 돌려준다 */
export function createTaxDraft(
  businessId: number,
  body: CreateTaxDraftRequest,
): Promise<ApiResult<TaxDraftCreated>> {
  return request<TaxDraftCreated>('taxDrafts.create', `/businesses/${businessId}/tax-drafts`, {
    method: 'POST',
    json: body,
  });
}

/** 귀속연도별 초안 목록. 페이징이 없다 — 한 해 몇 개뿐이다 */
export function listTaxDrafts(businessId: number, taxYear: number): Promise<ApiResult<Items<TaxDraftSummary>>> {
  return request<Items<TaxDraftSummary>>('taxDrafts.list', `/businesses/${businessId}/tax-drafts`, {
    query: { taxYear },
  });
}

/**
 * 초안 상세. ⚠ **조회가 상태를 바꿀 수 있다** — 근거가 달라졌으면 서버가 이 자리에서 `STALE` 로 적는다.
 * 민감 항목은 키 자체가 없다.
 */
export function getTaxDraft(draftId: number): Promise<ApiResult<TaxDraftDetail>> {
  return request<TaxDraftDetail>('taxDrafts.get', `/tax-drafts/${draftId}`);
}

/** 항목 수정·참고값 입력. 응답은 산식을 다시 푼 상세 전체다 */
export function patchTaxDraft(draftId: number, body: PatchTaxDraftRequest): Promise<ApiResult<TaxDraftDetail>> {
  return request<TaxDraftDetail>('taxDrafts.patch', `/tax-drafts/${draftId}`, { method: 'PATCH', json: body });
}

/**
 * 초안 삭제. **실제로 지운다** — 사장님이 적은 값(overrides)도 함께 사라지고, 다음 초안은 남은 초안 중
 * 가장 최근 것에서 값을 물려받는다. 화면은 지우기 전에 묻는다.
 */
export function deleteTaxDraft(draftId: number): Promise<ApiResult<null>> {
  return request<null>('taxDrafts.delete', `/tax-drafts/${draftId}`, { method: 'DELETE' });
}

/** 항목 근거 거래. `fieldKey` 필수, 페이징(기본 50) */
export function getTaxDraftBasis(
  draftId: number,
  fieldKey: string,
  page = 0,
  size = 50,
): Promise<ApiResult<TaxDraftBasis>> {
  return request<TaxDraftBasis>('taxDrafts.basis', `/tax-drafts/${draftId}/basis`, {
    query: { fieldKey, page, size },
  });
}

/**
 * 홈택스 입력용 XLSX 내보내기.
 *
 * ⚠ 인증이 헤더라 `<a href>` 로는 못 받는다 — 파일로 받아 화면이 저장한다.
 * `sensitiveValues` 는 이 요청에만 싣는다.
 */
export function exportTaxDraft(draftId: number, body: ExportTaxDraftRequest): Promise<ApiResult<DownloadedFile>> {
  return requestFile('taxDrafts.export', `/tax-drafts/${draftId}/export`, { method: 'POST', json: body });
}

/**
 * 검토 상태 일괄 변경 (8.7). 한 번에 200건까지.
 *
 * ⚠ `EVIDENCE_CONFIRMED`·`EVIDENCE_MISSING` 은 보낼 수 없다(400) — 증빙 연결로 자동으로 정해진다.
 */
export function bulkUpdateTaxReview(
  businessId: number,
  body: {
    transactionIds: number[];
    taxReviewStatus: Exclude<TaxReviewStatus, 'EVIDENCE_CONFIRMED' | 'EVIDENCE_MISSING'>;
    taxReviewNote?: string;
  },
): Promise<ApiResult<TaxReviewBulkResult>> {
  return request<TaxReviewBulkResult>('taxReview.bulk', `/businesses/${businessId}/tax-review/bulk`, {
    method: 'POST',
    json: body,
  });
}
