/**
 * 페이지 번호 줄. 0부터 세는 페이지 번호를 돌려주고, 건너뛴 자리는 null(「…」)로 둔다.
 * 처음·끝과 현재 페이지 앞뒤 두 쪽만 보인다 — 7쪽 이하면 전부 보인다.
 */
export function pageNumbers(current: number, total: number): (number | null)[] {
  if (total <= 7) return Array.from({ length: total }, (_, i) => i);
  const from = Math.max(1, Math.min(current - 2, total - 6));
  const to = Math.min(total - 2, Math.max(current + 2, 5));
  const middle = Array.from({ length: to - from + 1 }, (_, i) => from + i);
  return [0, ...(from > 1 ? [null] : []), ...middle, ...(to < total - 2 ? [null] : []), total - 1];
}
