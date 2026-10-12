import { request } from './client';
import type { ApiResult } from './envelope';
import type { Page } from './types/02_common';
import type {
  ConfirmPatternRequest,
  CreatePlannedTransactionRequest,
  PatternStatus,
  PlannedTransaction,
  PlannedTransactionQuery,
  RecurringPattern,
  UpdatePatternRequest,
  UpdatePlannedTransactionRequest,
} from './types/09_planned';

/**
 * 반복·예정 거래 API (docs/api/09_반복_예정거래.md).
 *
 * 예정 거래는 잔액 예측(P0-07)의 직접 입력이다 — 곡선은 이 목록을 날짜별로 더해 만든다.
 * 반복 패턴은 그 예정 거래를 자동으로 만들어 주는 쪽이다.
 */

/** 목록 응답은 items 만 온다. 페이징 필드는 오지 않는다(실측) */
type Items<T> = Pick<Page<T>, 'items'>;

/**
 * 예정 거래 목록.
 *
 * ⚠ from·to 가 필수다. 범위를 안 주면 롤링 배치가 만든 90일치가 통째로 나온다.
 *   최대 92일이고 넘기면 400 이다 — 화면이 기간을 고르게 두고 여기서 막지는 않는다.
 */
export function listPlannedTransactions(
  businessId: number,
  query: PlannedTransactionQuery,
): Promise<ApiResult<Items<PlannedTransaction>>> {
  return request<Items<PlannedTransaction>>(
    'plannedTransactions.list',
    `/businesses/${businessId}/planned-transactions`,
    { query },
  );
}

/**
 * 예정 거래 등록. 사용자가 넣은 것은 EXPECTED 가 아니라 CONFIRMED 로 생성된다.
 *
 * ⚠ 금액은 amount(확정) 또는 amountMin+amountMax(범위) 중 정확히 한 형태여야 한다.
 *   둘 다 보내거나 둘 다 없으면 400 이다.
 */
export function createPlannedTransaction(
  businessId: number,
  body: CreatePlannedTransactionRequest,
): Promise<ApiResult<PlannedTransaction>> {
  return request<PlannedTransaction>(
    'plannedTransactions.create',
    `/businesses/${businessId}/planned-transactions`,
    { method: 'POST', json: body },
  );
}

/** 전달한 필드만 바뀐다. status 로 바꿀 수 있는 것은 CANCELLED 뿐이다. */
export function updatePlannedTransaction(
  plannedTransactionId: number,
  body: UpdatePlannedTransactionRequest,
): Promise<ApiResult<PlannedTransaction>> {
  return request<PlannedTransaction>(
    'plannedTransactions.patch',
    `/planned-transactions/${plannedTransactionId}`,
    { method: 'PATCH', json: body },
  );
}

/**
 * 삭제는 source=USER 만 된다. 패턴이 만든 것은 409 다 —
 * 그때는 updatePlannedTransaction(id, { status: 'CANCELLED' }) 로 대신한다.
 */
export function deletePlannedTransaction(
  plannedTransactionId: number,
): Promise<ApiResult<void>> {
  return request<void>(
    'plannedTransactions.delete',
    `/planned-transactions/${plannedTransactionId}`,
    { method: 'DELETE' },
  );
}

// --- 반복 패턴 (9.2~9.3) ---------------------------------------------------

export function listRecurringPatterns(
  businessId: number,
  query: { status?: PatternStatus } = {},
): Promise<ApiResult<Items<RecurringPattern>>> {
  return request<Items<RecurringPattern>>(
    'recurringPatterns.list',
    `/businesses/${businessId}/recurring-patterns`,
    { query },
  );
}

export function getRecurringPattern(patternId: number): Promise<ApiResult<RecurringPattern>> {
  return request<RecurringPattern>('recurringPatterns.get', `/recurring-patterns/${patternId}`);
}

/**
 * 승인. 보정값을 함께 보내면 그 값으로 고친 뒤 CONFIRMED 로 바꾸고
 * 향후 90일 예정 거래를 만든다. 본문 없이 불러도 된다.
 *
 * ⚠ CANDIDATE 가 아니면 409 PATTERN_STATE_CONFLICT 다.
 */
export function confirmRecurringPattern(
  patternId: number,
  body: ConfirmPatternRequest = {},
): Promise<ApiResult<RecurringPattern>> {
  return request<RecurringPattern>(
    'recurringPatterns.confirm',
    `/recurring-patterns/${patternId}/confirm`,
    { method: 'POST', json: body },
  );
}

/** 후보 기각. 204 라 본문이 없다. */
export function dismissRecurringPattern(patternId: number): Promise<ApiResult<void>> {
  return request<void>(
    'recurringPatterns.dismiss',
    `/recurring-patterns/${patternId}/dismiss`,
    { method: 'POST' },
  );
}

/**
 * 승인된 패턴 수정.
 *
 * ⚠ PAUSED 로 바꾸면 미래 EXPECTED 예정 거래가 함께 취소된다.
 *   화면은 "곡선에서도 빠집니다"를 함께 알려야 한다 — 안 그러면
 *   "중지했는데 곡선은 그대로"로 읽힌다.
 */
export function updateRecurringPattern(
  patternId: number,
  body: UpdatePatternRequest,
): Promise<ApiResult<RecurringPattern>> {
  return request<RecurringPattern>(
    'recurringPatterns.patch',
    `/recurring-patterns/${patternId}`,
    { method: 'PATCH', json: body },
  );
}

/**
 * 탐지 수동 실행. 202 로 접수만 된다.
 *
 * ⚠ 명세에 없는 임시 엔드포인트다(구현현황 §3). 배치가 생기면 내부 호출로 바뀌고
 *   이 경로는 사라진다 — **화면에 상시 버튼으로 두지 말 것.**
 */
export function detectRecurringPatterns(businessId: number): Promise<ApiResult<void>> {
  return request<void>(
    'recurringPatterns.detect',
    `/businesses/${businessId}/recurring-patterns/detect`,
    { method: 'POST' },
  );
}
