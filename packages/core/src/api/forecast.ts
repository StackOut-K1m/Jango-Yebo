import { request } from './client';
import type { ApiResult } from './envelope';
import type {
  BalanceForecast,
  CashGapList,
  ForecastRefreshAccepted,
  ForecastSettings,
} from './types/10_forecast';

/**
 * 예상 잔액 곡선·정산 공백 API (docs/api/10_잔액예측_알림.md 10.1·10.2·10.7).
 *
 * 서비스의 핵심 계산이다 — 날짜별 예상 잔액 = 현재 잔액 + 예상 입금 − 예상 지출.
 */

/** 조회 기간. 실행은 항상 30일이고 서버가 여기에 맞춰 자른다. */
export type ForecastDays = 7 | 14 | 30;

/**
 * 날짜별 예상 잔액 곡선 (P0-07).
 *
 * ⚠ **200 이 아닌 두 경우가 정상 흐름이다.**
 * - `409 FORECAST_NOT_READY` — 아직 계산된 곡선이 없다. 이 호출이 계산을 예약하므로
 *   "예보를 준비하고 있습니다"를 띄우고 잠시 뒤 다시 부른다. 오류 화면이 아니다.
 * - `422 FORECAST_INSUFFICIENT_DATA` — 연결된 계좌가 없다. 온보딩으로 유도한다.
 *
 * ⚠ 계좌별 곡선(`accountId`)은 아직 없다. 보내면 400 이다 — 조용히 무시하면
 * 사용자가 계좌별 값을 본다고 착각한다.
 */
export function getBalanceForecast(
  businessId: number,
  options: { days?: ForecastDays } = {},
): Promise<ApiResult<BalanceForecast>> {
  const days = options.days ?? 30;
  return request<BalanceForecast>(
    'forecast.balance',
    `/businesses/${businessId}/forecast/balance?days=${days}`,
  );
}

/**
 * 정산 공백 목록 (P0-08).
 *
 * 곡선에서 조회 시 계산하는 파생값이라 위 곡선과 같은 실행을 근거로 한다.
 * 곡선이 없으면 이 API 도 409·422 로 같은 상태를 알린다.
 */
export function getCashGaps(
  businessId: number,
  options: { days?: ForecastDays } = {},
): Promise<ApiResult<CashGapList>> {
  const days = options.days ?? 30;
  return request<CashGapList>(
    'forecast.cashGaps',
    `/businesses/${businessId}/forecast/cash-gaps?days=${days}`,
  );
}

/**
 * 수동 새로고침 — **명세에 없는 임시 엔드포인트다.**
 *
 * ⚠ 즉시 곡선이 바뀌지 않는다. 서버가 5초 디바운스 뒤에 계산하므로 202 를 받고
 * 잠시 뒤 곡선을 다시 조회해야 한다. 연타해도 실행은 한 번이다.
 *
 * 재계산은 원래 거래 동기화·패턴 승인·예정 거래 변경이 알아서 건다. 이 버튼은
 * 그 트리거가 다 붙기 전까지의 진입점이고, 화면에 상시 노출할 것은 아니다.
 */
export function refreshForecast(businessId: number): Promise<ApiResult<ForecastRefreshAccepted>> {
  return request<ForecastRefreshAccepted>(
    'forecast.refresh',
    `/businesses/${businessId}/forecast/refresh`,
    { method: 'POST' },
  );
}

/**
 * 예측 옵션 조회 (10.7).
 *
 * ⚠ 아직 사업장별로 저장되지 않는다 — 서버 설정을 그대로 비춘다. 저장소(FORECAST_SETTING)와
 * PATCH 는 함께 열린다.
 */
export function getForecastSettings(businessId: number): Promise<ApiResult<ForecastSettings>> {
  return request<ForecastSettings>(
    'forecastSettings.get',
    `/businesses/${businessId}/forecast-settings`,
  );
}

/**
 * 예측 옵션 수정 (10.7).
 *
 * ⚠ 아직 열리지 않았다 — registry 가 NOT_READY 라 `{ ready: false }` 를 돌려준다.
 * 토글 UI 는 그 상태를 보고 비활성으로 그린다.
 */
export function patchForecastSettings(
  businessId: number,
  body: ForecastSettings,
): Promise<ApiResult<ForecastSettings>> {
  return request<ForecastSettings>(
    'forecastSettings.patch',
    `/businesses/${businessId}/forecast-settings`,
    { method: 'PATCH', json: body },
  );
}
