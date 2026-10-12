import { afterEach, expect, it, vi } from 'vitest';
import { configureCore, createMemoryTokenStore, resetCore } from '../config';
import { getProductSalesShares, getProductSalesTrend, uploadProductSalesOcr } from './productSales';

// 이 파일이 지키는 것은 **클라이언트가 스스로 하는 두 가지**다 —
// 업로드 파트 조립(14.7)과 뒤집힌 기간 차단(14.5 추이). 나머지는 서버로 그냥 흘러간다.

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

const envelope = (data: unknown) =>
  new Response(JSON.stringify({ success: true, data }), {
    status: 200,
    headers: { 'Content-Type': 'application/json' },
  });

// 파트 이름이 files[] 면 서버가 파일을 하나도 못 받는다. @RequestPart("files") 와 글자가 같아야 한다.
it('OCR 업로드는 같은 이름의 files 파트를 장 수만큼 보낸다', async () => {
  const fetch = vi.fn().mockResolvedValue(envelope({ jobId: 47, status: 'QUEUED' }));
  vi.stubGlobal('fetch', fetch);
  setup();

  await uploadProductSalesOcr(
    11,
    [
      { blob: new Blob(['a']), fileName: '1.jpg' },
      { blob: new Blob(['b']), fileName: '2.jpg' },
    ],
    { captureType: 'SCREEN' },
  );

  const [url, init] = fetch.mock.calls[0];
  expect(url).toBe('/api/v1/businesses/11/product-sales/ocr');
  expect(init.method).toBe('POST');
  const form = init.body as FormData;
  expect(form.getAll('files')).toHaveLength(2);
  // captureType 은 @RequestParam 이라 파일이 아닌 일반 폼 필드여야 한다.
  expect(form.get('captureType')).toBe('SCREEN');
  // Content-Type 을 직접 붙이면 경계 문자열이 빠져 서버가 파트를 못 가른다 — fetch 가 붙이게 둔다.
  expect(init.headers['Content-Type']).toBeUndefined();
});

it('captureType 을 안 주면 아예 보내지 않는다 — 기본값은 서버가 정한다', async () => {
  const fetch = vi.fn().mockResolvedValue(envelope({ jobId: 48, status: 'QUEUED' }));
  vi.stubGlobal('fetch', fetch);
  setup();

  await uploadProductSalesOcr(11, [{ blob: new Blob(['a']), fileName: '1.jpg' }]);

  const form = fetch.mock.calls[0][1].body as FormData;
  expect(form.get('captureType')).toBeNull();
});

// 서버에 to < from 가드가 없는 유일한 엔드포인트다. 그대로 보내면 조용히 빈 목록이 와서
// 화면이 「그 기간엔 안 팔렸다」로 읽는다 — 안 판 것과 잘못 물은 것은 다른 말이다.
it('뒤집힌 기간은 서버에 가지 않고 400 으로 돌아온다', async () => {
  const fetch = vi.fn();
  vi.stubGlobal('fetch', fetch);
  setup();

  const result = await getProductSalesTrend(11, '카페라떼', { from: '2026-09-30', to: '2026-09-01' });

  expect(fetch).not.toHaveBeenCalled();
  expect(result.ready && !result.ok && result.status).toBe(400);
});

it('상품명은 인코딩해서 경로에 넣는다', async () => {
  const fetch = vi.fn().mockResolvedValue(envelope({ productKey: '아메리카노', items: [] }));
  vi.stubGlobal('fetch', fetch);
  setup();

  await getProductSalesTrend(11, '아메리카노/ICE');

  expect(fetch.mock.calls[0][0]).toBe(
    `/api/v1/businesses/11/product-sales/${encodeURIComponent('아메리카노/ICE')}`,
  );
});

// 기간을 안 주면 서버 기본값(이번 달 1일~오늘)을 쓴다. buildUrl 이 undefined 를 빼므로
// from=undefined 같은 문자열이 나가면 안 된다 — 그건 400 이다.
it('기간을 안 주면 쿼리 없이 나간다', async () => {
  const fetch = vi.fn().mockResolvedValue(envelope({ items: [], warnings: [] }));
  vi.stubGlobal('fetch', fetch);
  setup();

  await getProductSalesShares(11);

  expect(fetch.mock.calls[0][0]).toBe('/api/v1/businesses/11/product-sales');
});
