import type { ApiErrorBody } from '@jangoyebo/core';
import type { FieldValues, Path, UseFormSetError } from 'react-hook-form';

/**
 * 서버가 준 필드별 오류를 폼에 붙인다.
 *
 * 공통규약 2.4가 오류 응답에 detail 을 허용한다. 실제로 검증 실패는
 * `detail: { password: "비밀번호는 8~64자이며 …" }` 형태로 온다.
 * 이걸 안 쓰면 화면에는 "요청값이 올바르지 않습니다"만 뜨고 어느 칸이 틀렸는지 알 수 없다.
 *
 * 폼에 없는 필드 이름이 오면 무시한다 — 호출한 쪽이 전체 오류로 대신 표시한다.
 */
export function applyFieldErrors<T extends FieldValues>(
  error: ApiErrorBody,
  setError: UseFormSetError<T>,
  fields: readonly string[],
): boolean {
  const detail = error.detail;
  if (typeof detail !== 'object' || detail === null) return false;

  let applied = false;
  for (const [key, message] of Object.entries(detail as Record<string, unknown>)) {
    if (!fields.includes(key) || typeof message !== 'string') continue;
    setError(key as Path<T>, { message });
    applied = true;
  }
  return applied;
}
