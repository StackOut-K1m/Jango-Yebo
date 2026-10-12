import { request } from './client';
import type { ApiResult } from './envelope';
import type { Page, SyncAccepted } from './types/02_common';
import type {
  CreditStatus,
  LoanItem,
  LoanProductQuery,
  LoanProductRecommendation,
} from './types/16_loan';

/**
 * 대출·신용 (API 16장, P1-13·P1-14).
 *
 * 세 GET 은 동기이고 동기화만 202 + Job 이다.
 *
 * ⚠ **`getCredit` 과 `listLoanProducts` 는 부작용이 있는 GET 이다** — 호출할 때마다 금융망을
 * 부르고 `CREDIT_SNAPSHOT` 행을 쓴다. 폴링하지 말고 `staleTime` 을 길게 잡는다.
 */

/** 목록 응답은 items 만 온다. 페이징 필드는 오지 않는다 */
type Items<T> = Pick<Page<T>, 'items'>;

/**
 * 대출 현황 (16.1).
 *
 * 등록된 대출이 없어도 **빈 200** 이다. 404 를 기다리지 말 것.
 */
export function listLoans(businessId: number): Promise<ApiResult<Items<LoanItem>>> {
  return request<Items<LoanItem>>('loans.list', `/businesses/${businessId}/loans`);
}

/**
 * 금융망 대출 동기화 접수 (16.1). 202 — resultRef 는 `LoanSyncResult` 다.
 *
 * ⚠ **자동 배치가 없다.** 이 수동 호출이 유일한 갱신 경로이므로
 * **화면에 동기화 버튼이 반드시 있어야 한다** — 안 그러면 대출이 영원히 비어 있다.
 *
 * ⚠ LOAN_08 은 userKey 로 조회해 **사업장 개념이 없다.** 가게가 둘이면 둘 다 온다 —
 * 출금 계좌로 갈라 붙이고, 못 붙인 수가 `unattachedCount` 다.
 */
export function syncLoans(businessId: number): Promise<ApiResult<SyncAccepted>> {
  return request<SyncAccepted>('loans.sync', `/businesses/${businessId}/loans/sync`, {
    method: 'POST',
  });
}

/**
 * 신용등급 현황·예측 (16.2).
 *
 * ⚠ **부작용이 있는 GET 이다** — 금융망 LOAN_04 를 부르고 스냅샷 행을 쓴다. 폴링 금지.
 *
 * 곡선이 없으면 `projection` 이 빈 배열이고 `confidence` 가 `UNPREDICTABLE`,
 * `warnings` 에 사유가 온다. 오류가 아니다.
 *
 * 오류는 404 `BUSINESS_NOT_FOUND` · 502 `FIN_API_FAILED` 둘이다 —
 * ⚠ `CREDIT_FETCH_FAILED` 는 enum 에 있지만 **아무도 던지지 않는다.** 분기하지 말 것.
 */
export function getCredit(businessId: number): Promise<ApiResult<CreditStatus>> {
  return request<CreditStatus>('credit.get', `/businesses/${businessId}/credit`);
}

/**
 * 대출 상품 추천 (16.3).
 *
 * ⚠ **부작용이 있는 GET 이다**(16.2 와 같은 이유). 폴링 금지.
 *
 * ⚠ `amount` 는 없거나 0 이하면 400 이다. 서버가 500 이 나지 않게 손으로 검증한다.
 *
 * ⚠ **빈 목록이 정상이다.** `recommendable` 이 꺼진 새 DB 에서는 `items: []` 가 온다 —
 * 자세한 사정은 `LoanProductRecommendation` 주석에 있다.
 */
export function listLoanProducts(
  businessId: number,
  query: LoanProductQuery,
): Promise<ApiResult<LoanProductRecommendation>> {
  return request<LoanProductRecommendation>(
    'loanProducts.list',
    `/businesses/${businessId}/loan-products`,
    { query },
  );
}
