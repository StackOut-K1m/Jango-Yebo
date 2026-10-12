import { request } from './client';
import type { ApiResult } from './envelope';
import type {
  CreateScenarioRequest,
  SavedScenario,
  SavedScenarioList,
  ScenarioResult,
} from './types/11_scenario';

/**
 * What-if 시나리오 API (docs/api/11_시나리오.md 11.1·11.2).
 *
 * 서버는 **예측을 새로 돌리지 않는다** — 최신 완료 실행의 원장을 변형해 같은 계산기를
 * 다시 부른다. 그래서 `originalCurve` 는 예보 화면(10.1)이 그리는 곡선과 같은 값이고,
 * 아직 예보가 없는 사업장은 `409 FORECAST_NOT_READY`, 계좌가 없으면 `422` 가 온다.
 */

export function createScenario(
  businessId: number,
  body: CreateScenarioRequest,
): Promise<ApiResult<ScenarioResult>> {
  return request<ScenarioResult>('scenarios.create', `/businesses/${businessId}/scenarios`, {
    method: 'POST',
    json: body,
  });
}

export function listScenarios(businessId: number): Promise<ApiResult<SavedScenarioList>> {
  return request<SavedScenarioList>('scenarios.list', `/businesses/${businessId}/scenarios`);
}

/** 저장한 파라미터로 **지금 데이터 기준 다시 계산한** 결과다. 저장 당시 스냅샷이 아니다 */
export function getScenario(scenarioId: number): Promise<ApiResult<ScenarioResult>> {
  return request<ScenarioResult>('scenarios.get', `/scenarios/${scenarioId}`);
}

/** 이름만 바꾼다. 결과를 다시 계산하지 않는다 — 웹에는 아직 이 UI 가 없다 */
export function renameScenario(
  scenarioId: number,
  name: string,
): Promise<ApiResult<SavedScenario>> {
  return request<SavedScenario>('scenarios.patch', `/scenarios/${scenarioId}`, {
    method: 'PATCH',
    json: { name },
  });
}

export function deleteScenario(scenarioId: number): Promise<ApiResult<null>> {
  return request<null>('scenarios.delete', `/scenarios/${scenarioId}`, { method: 'DELETE' });
}
