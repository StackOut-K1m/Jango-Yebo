import type { ExportItem } from '@jangoyebo/core';

/**
 * 신고 준비자료 패키지(14.2)의 표시 규칙.
 *
 * 파일이 **7일만 보관된다**는 사실이 화면 곳곳에 나온다. 「목록에 있다」와 「받을 수 있다」가
 * 다른 말이라, 그 판단을 한곳에 모아 둔다.
 */

const UNIT = ['B', 'KB', 'MB', 'GB'] as const;

/** 없으면 — 다. 0 과 「모름」을 가르는 것이 이 화면의 규칙이다(서버가 모르는 값은 키를 뺀다) */
export function fileSizeLabel(bytes: number | undefined): string {
  if (bytes === undefined) return '—';
  let n = bytes;
  let unit = 0;
  while (n >= 1024 && unit < UNIT.length - 1) {
    n /= 1024;
    unit += 1;
  }
  // 바이트는 소수점이 뜻이 없다. 그 위로는 한 자리까지만 — 「12.4MB」면 충분하다
  return `${unit === 0 ? n : Number(n.toFixed(1))}${UNIT[unit]}`;
}

/**
 * 언제까지 받을 수 있나.
 *
 * **「만료됨」을 「아직 안 됐다」와 같은 말로 쓰지 않는다** — 만료는 끝났다가 사라진 것이라
 * 기다리면 안 되고 다시 만들어야 한다(서버도 409 와 410 을 갈라 준다).
 */
export function expiryLabel(iso: string | undefined, now: Date): string | null {
  if (!iso) return null;
  const at = Date.parse(iso);
  if (Number.isNaN(at)) return null;
  const left = at - now.getTime();
  if (left <= 0) return '만료됨';
  const days = Math.floor(left / 86_400_000);
  return days === 0 ? '오늘 만료' : `${days}일 뒤 만료`;
}

/**
 * 지금 받을 수 있는가.
 *
 * 다 만들어졌더라도 보관 기간이 지났으면 서버가 `410` 을 낸다. 받기 버튼을 그대로 두면
 * 눌러 본 뒤에야 알게 되므로 여기서 미리 가른다.
 */
export function canDownload(item: ExportItem, now: Date): boolean {
  if (item.status !== 'COMPLETED') return false;
  return expiryLabel(item.downloadExpiresAt, now) !== '만료됨';
}
