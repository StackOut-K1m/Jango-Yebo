import { request } from './client';
import type { ApiResult } from './envelope';
import type { Page } from './types/02_common';
import type {
  Alert,
  AlertStatus,
  CreateSettlementRequest,
  Settlement,
  SettlementStatus,
  UpdateSettlementRequest,
} from './types/14_settlement';

/**
 * 정산 수동 대사(14.1, P0-11)와 인앱 알림(10.3).
 *
 * 배달 플랫폼은 총매출에서 취소·수수료를 뺀 금액을 며칠 뒤에 넣어 준다.
 * 계좌만 봐서는 "얼마가 들어와야 하는지"가 안 보이므로 여기서 맞춰 본다.
 */

/** 목록 응답은 items 만 온다. 페이징 필드는 오지 않는다(실측) */
type Items<T> = Pick<Page<T>, 'items'>;

export function listSettlements(
  businessId: number,
  query: { status?: SettlementStatus; from?: string; to?: string } = {},
): Promise<ApiResult<Items<Settlement>>> {
  return request<Items<Settlement>>(
    'settlements.list',
    `/businesses/${businessId}/settlements`,
    { query },
  );
}

/**
 * 정산 등록. expectedPayout·difference 는 보내지 않는다 — 서버가 매번 계산해 내려준다.
 *
 * ⚠ matchedTransactionIds 에 넣을 수 있는 것은 **같은 사업장의 입금 거래**뿐이다.
 *   출금이면 400, 남의 사업장 거래면 403 이다.
 */
export function createSettlement(
  businessId: number,
  body: CreateSettlementRequest,
): Promise<ApiResult<Settlement>> {
  return request<Settlement>('settlements.create', `/businesses/${businessId}/settlements`, {
    method: 'POST',
    json: body,
  });
}

export function getSettlement(settlementId: number): Promise<ApiResult<Settlement>> {
  return request<Settlement>('settlements.get', `/settlements/${settlementId}`);
}

/**
 * 전달한 필드만 바뀐다.
 *
 * ⚠ matchedTransactionIds 는 **전량 교체**다. 부분 추가·삭제가 아니다 —
 *   화면이 기존 목록을 읽어 와서 통째로 다시 보내야 한다.
 */
export function updateSettlement(
  settlementId: number,
  body: UpdateSettlementRequest,
): Promise<ApiResult<Settlement>> {
  return request<Settlement>('settlements.patch', `/settlements/${settlementId}`, {
    method: 'PATCH',
    json: body,
  });
}

export function deleteSettlement(settlementId: number): Promise<ApiResult<void>> {
  return request<void>('settlements.delete', `/settlements/${settlementId}`, {
    method: 'DELETE',
  });
}

// --- 10.3 인앱 알림 --------------------------------------------------------

/**
 * ⚠ 지금 실제로 만들어지는 것은 SETTLEMENT_MISMATCH 뿐이다(2026-09-09).
 *   목록 API 는 유형을 가리지 않으므로 화면은 지금 붙여도 나중에 안 고친다.
 */
export function listAlerts(
  businessId: number,
  query: { status?: AlertStatus } = {},
): Promise<ApiResult<Items<Alert>>> {
  return request<Items<Alert>>('alerts.list', `/businesses/${businessId}/alerts`, { query });
}

/** 확인 처리. 204 라 본문이 없다. */
export function ackAlert(alertId: number): Promise<ApiResult<void>> {
  return request<void>('alerts.ack', `/alerts/${alertId}/ack`, { method: 'POST' });
}
