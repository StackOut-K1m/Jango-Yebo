import { expect, test } from 'vitest';
import type { ExportItem } from '@jangoyebo/core';
import { canDownload, expiryLabel, fileSizeLabel } from './exportView';

const NOW = new Date('2026-09-18T10:00:00+09:00');

// 서버는 모르는 값의 키를 아예 뺀다 — 0 으로 읽으면 「0바이트짜리 파일이 있다」가 된다
test('파일 크기는 없음과 0 을 가른다', () => {
  expect(fileSizeLabel(undefined)).toBe('—');
  expect(fileSizeLabel(0)).toBe('0B');
  expect(fileSizeLabel(512)).toBe('512B');
  expect(fileSizeLabel(1024)).toBe('1KB');
  expect(fileSizeLabel(18_349_222)).toBe('17.5MB');
  expect(fileSizeLabel(3 * 1024 ** 3)).toBe('3GB');
});

// 보관은 7일이다. 만료는 「아직 안 됐다」가 아니라 「다시 만들어야 한다」다
test('만료 안내는 남은 날로 말하고 지난 것은 만료됨이다', () => {
  expect(expiryLabel(undefined, NOW)).toBeNull();
  expect(expiryLabel('말이 안 되는 값', NOW)).toBeNull();
  expect(expiryLabel('2026-09-25T10:00:00+09:00', NOW)).toBe('7일 뒤 만료');
  expect(expiryLabel('2026-09-18T23:00:00+09:00', NOW)).toBe('오늘 만료');
  expect(expiryLabel('2026-09-18T09:59:00+09:00', NOW)).toBe('만료됨');
});

const item = (over: Partial<ExportItem>): ExportItem => ({
  exportId: 1,
  jobId: 1,
  exportType: 'TAX_PREP',
  status: 'COMPLETED',
  periodStart: '2026-01-01',
  periodEnd: '2026-12-31',
  requestedAt: '2026-09-11T10:00:00+09:00',
  ...over,
});

// 다 만들어졌어도 기간이 지나면 서버가 410 이다 — 눌러 본 뒤에 알게 하지 않는다
test('받을 수 있는 것은 완료됐고 아직 안 지난 것뿐이다', () => {
  expect(canDownload(item({ downloadExpiresAt: '2026-09-25T10:00:00+09:00' }), NOW)).toBe(true);
  expect(canDownload(item({ downloadExpiresAt: '2026-09-11T10:00:00+09:00' }), NOW)).toBe(false);
  expect(canDownload(item({ status: 'PROCESSING' }), NOW)).toBe(false);
  expect(canDownload(item({ status: 'FAILED' }), NOW)).toBe(false);
  // 만료 시각이 없는 완료 건은 막지 않는다 — 서버가 안 준 것을 「만료」로 단정하지 않는다
  expect(canDownload(item({}), NOW)).toBe(true);
});
