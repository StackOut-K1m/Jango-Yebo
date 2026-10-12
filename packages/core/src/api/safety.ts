import { request } from './client';
import type { ApiResult } from './envelope';
import type { PutSafetyLineRequest, SafetyLine } from './types/12_safety';

/**
 * 운영자금 안전선 API (docs/api/12_안전선_준비금_부가세.md 12.1).
 *
 * 안전선은 위험 등급 4단계 중 CAUTION·DANGER 의 기준선이다.
 * 없으면 0원 미만(GAP)만 판정할 수 있어 자금 부족 알림(P0-08)이 반쪽이 된다.
 */

/**
 * ⚠ 미설정이면 404 가 아니라 **네 필드가 전부 없는 200** 이다.
 *   안전선을 아직 안 정한 것은 오류가 아니라 정상 상태다(화면구성도 6.4) —
 *   화면은 `amount === undefined` 로 "설정하기" 유도를 띄운다. throw 하지 않는다.
 */
export function getSafetyLine(businessId: number): Promise<ApiResult<SafetyLine>> {
  return request<SafetyLine>('safetyLine.get', `/businesses/${businessId}/safety-line`);
}

/**
 * 전체 교체다. 사업장당 한 행이라 있으면 덮고 없으면 만든다.
 *
 * ⚠ amount 0 과 "설정 안 함"은 다르다 — 후자는 이 API 를 부르지 않는 것이다.
 */
export function putSafetyLine(
  businessId: number,
  body: PutSafetyLineRequest,
): Promise<ApiResult<SafetyLine>> {
  return request<SafetyLine>('safetyLine.put', `/businesses/${businessId}/safety-line`, {
    method: 'PUT',
    json: body,
  });
}
