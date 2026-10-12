import type { DownloadedFile } from '@jangoyebo/core';

/**
 * 받아 둔 파일을 브라우저에 저장한다.
 *
 * 인증이 헤더라 `<a href>` 로는 받을 수 없어(브라우저 기본 다운로드는 `Authorization` 을
 * 싣지 않는다) 한 번 `fetch` → `blob` 으로 받아서 넘긴다. 신고서 초안(14.3)과 준비자료
 * 패키지(14.2)가 같은 사정이라 한곳에 둔다.
 */
export function saveFile(file: DownloadedFile, fallback: string) {
  const url = URL.createObjectURL(file.blob);
  const a = document.createElement('a');
  a.href = url;
  a.download = file.fileName ?? fallback;
  document.body.appendChild(a);
  a.click();
  a.remove();
  // 클릭이 저장을 시작한 뒤에 풀어야 한다 — 바로 풀면 일부 브라우저가 빈 파일을 받는다
  setTimeout(() => URL.revokeObjectURL(url), 10_000);
}
