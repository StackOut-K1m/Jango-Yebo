import { EMPTY } from './money';

// 서버는 시각을 UTC로 준다(실측: occurredAt "2026-09-01T01:00:00Z" = KST 10시).
// 브라우저 기본 시간대에 맡기면 기기 설정에 따라 날짜가 하루씩 밀린다 — 사업장 시간대로 고정한다.
const ZONE = 'Asia/Seoul';

function parts(iso: string): Record<string, string> | null {
  const at = new Date(iso);
  if (Number.isNaN(at.getTime())) return null;

  const fmt = new Intl.DateTimeFormat('ko-KR', {
    timeZone: ZONE,
    year: 'numeric',
    month: '2-digit',
    day: '2-digit',
    hour: '2-digit',
    minute: '2-digit',
    hour12: false,
  });
  return Object.fromEntries(fmt.formatToParts(at).map((p) => [p.type, p.value]));
}

/** '2026-09-01T01:00:00Z' → '2026-09-01' (KST) */
export function formatDate(iso: string | null | undefined): string {
  if (!iso) return EMPTY;
  const p = parts(iso);
  return p ? `${p.year}-${p.month}-${p.day}` : EMPTY;
}

/** '2026-09-01T01:00:00Z' → '2026-09-01 10:00' (KST) */
export function formatDateTime(iso: string | null | undefined): string {
  if (!iso) return EMPTY;
  const p = parts(iso);
  // 자정은 ko-KR에서 24시로 나온다. 00시로 되돌린다.
  return p ? `${p.year}-${p.month}-${p.day} ${p.hour === '24' ? '00' : p.hour}:${p.minute}` : EMPTY;
}

/** 오늘부터 n일 전. 기간 필터 기본값에 쓴다. 'YYYY-MM-DD' */
export function daysAgo(n: number, from = new Date()): string {
  const at = new Date(from.getTime() - n * 24 * 60 * 60 * 1000);
  return formatDate(at.toISOString());
}
