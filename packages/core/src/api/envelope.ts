// 공통규약 2.4 응답 Envelope.

export interface ApiErrorBody {
  code: string;
  message: string;
  detail?: unknown;
}

export type Envelope<T> = { success: true; data: T } | { success: false; error: ApiErrorBody };

/**
 * 우리 서버 응답인지 판별한다.
 *
 * 우리 404      { success:false, error:{ code, message } }  — "거래가 없다"
 * Spring 기본 404 { timestamp, status, path }                — "그런 기능이 없다"
 *
 * 둘은 다른 말이다. Envelope 유무로 가른다.
 */
export function isEnvelope(body: unknown): body is Envelope<unknown> {
  return typeof body === 'object' && body !== null && 'success' in body;
}

/** 호출 결과. NOT_READY는 오류가 아니라 정상 빈 상태다(화면구성도 6.4). */
export type ApiResult<T> =
  | { ready: true; ok: true; data: T; isMock: boolean }
  | { ready: true; ok: false; error: ApiErrorBody; status: number }
  | { ready: false };

/** 서버가 Envelope 없이 응답했을 때 쓰는 오류. 기능 자체가 없거나 프록시가 가로챈 경우다. */
export const NOT_IMPLEMENTED_ERROR: ApiErrorBody = {
  code: 'ENDPOINT_NOT_IMPLEMENTED',
  message: '아직 제공하지 않는 기능입니다.',
};
