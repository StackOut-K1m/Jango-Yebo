import { afterEach, expect, it, vi } from 'vitest';
import { configureCore, createMemoryTokenStore, resetCore } from '../config';
import { resolveRegistry } from './registry';
import { getCurrentClassificationJob, requestClassification } from './transaction';

// 8.5 와 8.5.1 은 **경로가 한 글자 차이**다. 오타가 나면 백엔드는 멀쩡하고 화면 로직도
// 멀쩡한데 감지만 영영 조용히 실패한다 — 401/404 가 `{ready:true, ok:false}` 로 걸러져
// 화면에는 「도는 것 없음」과 똑같이 보이기 때문이다. 그 구간을 여기서 막는다.

const setup = () =>
  configureCore({
    baseUrl: '/api/v1',
    isProduction: false,
    tokenStore: createMemoryTokenStore(),
    refreshMode: 'cookie',
  });

afterEach(() => {
  resetCore();
  vi.unstubAllGlobals();
});

const envelope = (data: unknown, status = 200) =>
  new Response(JSON.stringify({ success: true, data }), {
    status,
    headers: { 'Content-Type': 'application/json' },
  });

it('진행 조회는 GET 이고 current 로 끝난다 — 아무것도 만들지 않는다', async () => {
  const fetch = vi.fn().mockResolvedValue(envelope({ running: false }));
  vi.stubGlobal('fetch', fetch);
  setup();

  await getCurrentClassificationJob(7);

  const [url, init] = fetch.mock.calls[0];
  expect(url).toBe('/api/v1/businesses/7/classification-jobs/current');
  // POST 로 새면 화면에 들어올 때마다 분류가 시작되고 LLM 값이 나간다.
  expect(init?.method ?? 'GET').toBe('GET');
  expect(init?.body).toBeUndefined();
});

it('실행 요청은 같은 경로의 POST 다 — 둘이 섞이지 않는다', async () => {
  const fetch = vi.fn().mockResolvedValue(envelope({ jobId: 1, status: 'QUEUED' }, 202));
  vi.stubGlobal('fetch', fetch);
  setup();

  await requestClassification(7);

  const [url, init] = fetch.mock.calls[0];
  expect(url).toBe('/api/v1/businesses/7/classification-jobs');
  expect(init.method).toBe('POST');
});

it('running:true 면 job 을 그대로 읽는다', async () => {
  const job = {
    jobId: 412,
    jobType: 'TRANSACTION_CLASSIFICATION',
    status: 'PROCESSING',
    createdAt: '2026-09-18T04:12:07Z',
  };
  vi.stubGlobal('fetch', vi.fn().mockResolvedValue(envelope({ running: true, job })));
  setup();

  const res = await getCurrentClassificationJob(7);

  expect(res.ready && res.ok && res.data.running).toBe(true);
  expect(res.ready && res.ok && res.data.job?.jobId).toBe(412);
});

// 구현현황 3.3 — 없는 필드는 null 이 아니라 키 자체가 없다
it('도는 것이 없으면 job 키가 아예 없다', async () => {
  vi.stubGlobal('fetch', vi.fn().mockResolvedValue(envelope({ running: false })));
  setup();

  const res = await getCurrentClassificationJob(7);

  expect(res.ready && res.ok && res.data.running).toBe(false);
  expect(res.ready && res.ok && res.data.job).toBeUndefined();
});

it('배포 빌드에서도 LIVE 다 — 목으로 강등되면 가짜 진행이 뜬다', () => {
  const states = resolveRegistry({ isProduction: true });

  expect(states['transactions.classificationJobs']).toBe('LIVE');
  expect(states['transactions.classificationJobCurrent']).toBe('LIVE');
});
