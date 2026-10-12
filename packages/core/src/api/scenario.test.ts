import { afterEach, expect, it, vi } from 'vitest';
import { configureCore, createMemoryTokenStore, resetCore } from '../config';
import { resolveRegistry } from './registry';
import { createScenario, deleteScenario, renameScenario } from './scenario';

const setup = (isProduction: boolean) =>
  configureCore({ baseUrl: '/api/v1', isProduction, tokenStore: createMemoryTokenStore(), refreshMode: 'cookie' });

afterEach(() => {
  resetCore();
  vi.unstubAllGlobals();
});

const body = { days: 30 as const, save: false, parameters: [{ type: 'DEPOSIT_DECREASE' as const, rate: 0.2 }] };

const envelope = (data: unknown) =>
  new Response(JSON.stringify({ success: true, data }), {
    status: 200,
    headers: { 'Content-Type': 'application/json' },
  });

it('시나리오 계산은 서버로 간다 — 목이 끼지 않는다', async () => {
  const fetch = vi.fn().mockResolvedValue(envelope({ days: 30, originalCurve: [], modifiedCurve: [] }));
  vi.stubGlobal('fetch', fetch);
  setup(false);

  const result = await createScenario(1, body);

  const [url, init] = fetch.mock.calls[0];
  expect(url).toBe('/api/v1/businesses/1/scenarios');
  expect(init.method).toBe('POST');
  expect(JSON.parse(init.body)).toEqual(body);
  // 목데이터 배지가 더는 뜨지 않는다 — 화면이 보는 숫자가 서버 계산이다.
  expect(result.ready && result.ok && result.isMock).toBe(false);
});

// 이 저장소에서 MOCK 이 있었던 유일한 자리다. 강등 로직이 남아 있어도 여기서 걸릴 것이 없어야
// 배포 빌드에서 화면이 "준비되지 않았습니다"로 멈추지 않는다.
it('배포 빌드에서도 그대로 LIVE 다', () => {
  const states = resolveRegistry({ isProduction: true });

  expect(states['scenarios.create']).toBe('LIVE');
  expect(states['scenarios.list']).toBe('LIVE');
  expect(states['scenarios.get']).toBe('LIVE');
  expect(states['scenarios.patch']).toBe('LIVE');
  expect(states['scenarios.delete']).toBe('LIVE');
});

it('이름 수정은 PATCH 로 이름만 보낸다', async () => {
  const fetch = vi.fn().mockResolvedValue(envelope({ scenarioId: 61, name: '연휴 대비' }));
  vi.stubGlobal('fetch', fetch);
  setup(false);

  await renameScenario(61, '연휴 대비');

  const [url, init] = fetch.mock.calls[0];
  expect(url).toBe('/api/v1/scenarios/61');
  expect(init.method).toBe('PATCH');
  expect(JSON.parse(init.body)).toEqual({ name: '연휴 대비' });
});

// 삭제는 204 라 본문이 없다. Envelope 가 없다고 "기능이 없다"로 읽으면 화면이 오류를 띄운다.
it('삭제는 본문 없는 204 를 성공으로 받는다', async () => {
  const fetch = vi.fn().mockResolvedValue(new Response(null, { status: 204 }));
  vi.stubGlobal('fetch', fetch);
  setup(false);

  expect(await deleteScenario(61)).toEqual({ ready: true, ok: true, data: null, isMock: false });
});
