import { request } from './client';
import type { ApiResult } from './envelope';
import type { ImportResult, Job, Page } from './types/02_common';
import type {
  CurrentClassificationJob,
  Transaction,
  TransactionCategory,
  TransactionQuery,
  UpdateTransactionRequest,
} from './types/08_transaction';

/**
 * 거래·분류 API (docs/api/08_거래_분류.md) + CSV 업로드(7.4).
 *
 * 명세에 있으나 서버에 없는 필터는 여기서도 받지 않는다(구현현황 3.3).
 * 화면이 보낼 수 없게 막는 것이 동작하지 않는 UI 를 만드는 것보다 낫다.
 */

/** 목록 응답은 items 만 온다. 페이징 필드는 오지 않는다(실측) */
type Items<T> = Pick<Page<T>, 'items'>;

/** 23개 고정 목록이다. 페이징이 없다 */
export function listTransactionCategories(): Promise<ApiResult<Items<TransactionCategory>>> {
  return request<Items<TransactionCategory>>('transactionCategories.list', '/transaction-categories');
}

/**
 * 거래 목록. from·to 를 생략하면 서버가 최근 31일로 잡는다.
 *
 * ⚠ q 를 비우면 서버가 500 을 낸다(실측 2026-09-08). 백엔드 수정 전까지 화면이 q 를 채워 보내야 한다.
 */
export function listTransactions(
  businessId: number,
  query: TransactionQuery = {},
): Promise<ApiResult<Page<Transaction>>> {
  return request<Page<Transaction>>('transactions.list', `/businesses/${businessId}/transactions`, {
    query,
  });
}

export function getTransaction(transactionId: number): Promise<ApiResult<Transaction>> {
  return request<Transaction>('transactions.get', `/transactions/${transactionId}`);
}

/** 카테고리를 바꾸면 classification.status 가 CONFIRMED 가 된다(실측). */
export function updateTransaction(
  transactionId: number,
  body: UpdateTransactionRequest,
): Promise<ApiResult<Transaction>> {
  return request<Transaction>('transactions.patch', `/transactions/${transactionId}`, {
    method: 'PATCH',
    json: body,
  });
}

/**
 * CSV 업로드. 202 를 주지만 응답 시점에 Job 은 이미 COMPLETED 이고 resultRef 가 채워져 있다.
 * 폴링하지 않는다(구현현황 3.4, 실측 확인).
 *
 * 파일 규격이 틀리면 Job 없이 400 IMPORT_INVALID_FORMAT 이 온다.
 */
export function importTransactionsCsv(
  accountId: number,
  file: Blob,
  fileName = 'transactions.csv',
): Promise<ApiResult<Job<ImportResult>>> {
  const form = new FormData();
  form.append('file', file, fileName);
  return request<Job<ImportResult>>(
    'transactions.import',
    `/accounts/${accountId}/transactions/import`,
    { method: 'POST', body: form },
  );
}

/**
 * 미분류 거래를 AI 분류 큐에 넣는다 (명세 8.5).
 *
 * **무엇을 분류할지 보내지 않는다.** 대상은 서버가 워커에 보낼 때 다시 조회한다 —
 * 목록을 화면이 지목하면 그것은 버튼을 누른 순간의 사진이라, 왕복하는 사이에 사용자가
 * 직접 분류한 거래까지 값을 치르고 보내게 된다.
 *
 * 한 번에 `jango.classification.batch-size`(기본 20)건을 처리하고, 남으면 서버가 다음
 * 작업을 이어 건다. 그래서 **한 번 눌러도 즉시 전부 분류되지는 않는다.**
 */
export function requestClassification(businessId: number): Promise<ApiResult<Job<unknown>>> {
  return request<Job<unknown>>(
    'transactions.classificationJobs',
    `/businesses/${businessId}/classification-jobs`,
    { method: 'POST' },
  );
}

/**
 * 지금 도는 분류 작업이 있는지 묻는다 (명세 8.5.1).
 *
 * **8.5 `requestClassification` 으로 대신할 수 없다.** 그쪽은 도는 것이 있으면 그것을
 * 돌려주지만 **없으면 새로 만든다** — 화면에 들어올 때마다 분류가 시작되고 값이 나간다.
 *
 * 분류는 브라우저와 무관하게 서버에서 돈다(워커 스레드 → AI 워커 → 콜백 → 이어달리기).
 * 그래서 다른 화면에 갔다 와도 작업은 계속 도는데 진행 표시만 사라진다. 이 호출이
 * 그 표시를 되살린다.
 *
 * **돌려받은 `job.jobId` 로 진행률을 재지 않는다.** 이어달리기가 매 배치마다 새 id 를
 * 만들어서, 이 id 가 `COMPLETED` 가 되어도 분류는 계속 돈다. 진행은 미분류 잔량으로 센다.
 *
 * 도는 것이 없어도 404 가 아니라 `{ running: false }` 인 200 이다.
 */
export function getCurrentClassificationJob(
  businessId: number,
): Promise<ApiResult<CurrentClassificationJob>> {
  return request<CurrentClassificationJob>(
    'transactions.classificationJobCurrent',
    `/businesses/${businessId}/classification-jobs/current`,
  );
}

/**
 * AI 제안을 그대로 확정한다 (명세 8.4 — 확인 질문의 「맞아요」).
 *
 * **본문이 없다.** 다른 분류를 고르는 것은 8.3 `updateTransaction` 이다.
 *
 * **`SUGGESTED` 가 아니면 409(`CLASSIFICATION_STATE_CONFLICT`) 다.** 이미 확정된 거래에
 * 수락이 오면 화면이 낡은 것이라, 서버가 조용히 성공으로 처리하지 않는다. 일괄 처리에서는
 * 이 409 를 **실패가 아니라 「이미 처리됨」으로** 다루는 편이 맞다.
 */
export function confirmClassification(transactionId: number): Promise<ApiResult<Transaction>> {
  return request<Transaction>(
    'transactions.confirmClassification',
    `/transactions/${transactionId}/confirm-classification`,
    { method: 'POST' },
  );
}
