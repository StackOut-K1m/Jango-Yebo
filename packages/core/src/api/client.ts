import { APP_CLIENT_HEADER, getConfig } from '../config';
import {
  isEnvelope,
  NOT_IMPLEMENTED_ERROR,
  type ApiErrorBody,
  type ApiResult,
  type Envelope,
} from './envelope';
import { getEndpointState, type EndpointKey } from './registry';

export interface RequestOptions {
  method?: 'GET' | 'POST' | 'PATCH' | 'PUT' | 'DELETE';
  /** JSON 본문. FormData를 보낼 때는 body를 쓴다. */
  json?: unknown;
  body?: FormData;
  /** 배열은 같은 키로 여러 번 보낸다 — sort=needsReview,desc&sort=createdAt,desc */
  query?: Record<string, string | number | boolean | string[] | undefined>;
  /** 인증 헤더를 붙이지 않는다. /auth/** 에 쓴다. */
  anonymous?: boolean;
  /** 추가 헤더. 인증 채널 헤더(X-Client-Type)에 쓴다. */
  headers?: Record<string, string>;
}

function buildUrl(path: string, query?: RequestOptions['query']): string {
  const base = getConfig().baseUrl.replace(/\/$/, '');
  const url = `${base}${path}`;
  if (!query) return url;

  const params = new URLSearchParams();
  for (const [key, value] of Object.entries(query)) {
    if (value === undefined) continue;
    for (const one of Array.isArray(value) ? value : [value]) params.append(key, String(one));
  }
  const qs = params.toString();
  return qs ? `${url}?${qs}` : url;
}

async function readBody(res: Response): Promise<unknown> {
  if (res.status === 204) return null;
  const text = await res.text();
  if (!text) return null;
  try {
    return JSON.parse(text);
  } catch {
    return null;
  }
}

/** 401을 만나면 한 번만 갱신하고 원요청을 재시도한다. 동시 요청이 몰려도 갱신은 한 번이다. */
let refreshing: Promise<boolean> | null = null;

/**
 * 갱신이 한 번 실패하면 세션이 없는 것이다. 다음 로그인 전까지 다시 묻지 않는다.
 *
 * 이게 없으면 로그아웃 상태에서 화면이 API를 부를 때마다 refresh 가 새로 나간다.
 * 사업장·계좌·거래를 함께 부르는 화면에서는 한 번 그릴 때마다 여러 번 두드리게 된다.
 */
let sessionGone = false;

/** 로그인에 성공하면 다시 갱신을 시도할 수 있다. auth.ts 의 login 이 부른다. */
export function clearSessionGone(): void {
  sessionGone = false;
}

/** 갱신이 실패하면 남은 토큰은 쓸모가 없다. 앱의 보관소(비동기)까지 비운다. */
export async function clearTokens(): Promise<void> {
  const { tokenStore } = getConfig();
  tokenStore.setAccessToken(null);
  await tokenStore.setRefreshToken?.(null);
}

async function refreshOnce(): Promise<boolean> {
  if (sessionGone) return false;

  if (!refreshing) {
    refreshing = doRefresh()
      .then((ok) => {
        if (!ok) sessionGone = true;
        return ok;
      })
      .finally(() => {
        refreshing = null;
      });
  }
  return refreshing;
}

async function doRefresh(): Promise<boolean> {
  const { baseUrl, tokenStore, refreshMode } = getConfig();
  const viaBody = refreshMode === 'body';

  // 웹: HttpOnly 쿠키를 브라우저가 싣는다(공통규약 2.1). 프론트는 쿠키를 읽지 않는다.
  // 앱: 쿠키 저장소가 없어 본문으로 주고받는다. 서버는 받은 곳으로 돌려준다.
  const stored = viaBody ? ((await tokenStore.getRefreshToken?.()) ?? null) : null;

  // 보관된 토큰이 없으면 세션이 없는 것이다. 서버를 부를 이유가 없다.
  if (viaBody && !stored) return false;

  const res = await fetch(`${baseUrl.replace(/\/$/, '')}/auth/refresh`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json', ...(viaBody ? APP_CLIENT_HEADER : {}) },
    body: viaBody ? JSON.stringify({ refreshToken: stored }) : undefined,
    credentials: viaBody ? 'omit' : 'include',
  });

  const body = await readBody(res);
  if (!res.ok || !isEnvelope(body) || !body.success) {
    await clearTokens();
    return false;
  }

  const data = body.data as { accessToken?: string; refreshToken?: string };
  if (!data.accessToken) {
    await clearTokens();
    return false;
  }

  tokenStore.setAccessToken(data.accessToken);

  // 회전 — 방금 쓴 토큰은 서버에서 이미 폐기됐다. 새 토큰으로 갈아 끼우지 않으면 다음 갱신이 401이다.
  // 본문 모드인데 새 토큰이 안 왔다면 서버가 아직 본문 채널을 모르는 것이다.
  // 이번 요청은 살리고 다음 갱신에서 로그인으로 보낸다 — 여기서 지우면 지금 화면까지 끊긴다.
  if (viaBody && data.refreshToken) await tokenStore.setRefreshToken?.(data.refreshToken);

  return true;
}

async function send(path: string, options: RequestOptions): Promise<Response> {
  const { tokenStore, refreshMode } = getConfig();
  const headers: Record<string, string> = {};

  if (options.json !== undefined) headers['Content-Type'] = 'application/json';
  Object.assign(headers, options.headers);

  if (!options.anonymous) {
    const token = tokenStore.getAccessToken();
    if (token) headers.Authorization = `Bearer ${token}`;
  }

  return fetch(buildUrl(path, options.query), {
    method: options.method ?? 'GET',
    headers,
    body: options.json !== undefined ? JSON.stringify(options.json) : options.body,
    credentials: refreshMode === 'cookie' ? 'include' : 'omit',
  });
}

const SESSION_EXPIRED = {
  ready: true,
  ok: false,
  status: 401,
  error: { code: 'AUTH_TOKEN_EXPIRED', message: '다시 로그인해 주세요.' },
} as const;

/** 401 이면 한 번만 갱신하고 다시 보낸다. 갱신이 실패하면 `null` — 세션이 없다 */
async function sendWithRefresh(path: string, options: RequestOptions): Promise<Response | null> {
  const res = await send(path, options);
  if (res.status !== 401 || options.anonymous) return res;
  const renewed = await refreshOnce();
  return renewed ? send(path, options) : null;
}

/** 내려받은 파일. 이름은 서버의 `Content-Disposition` 에서 읽고, 없으면 비운다 */
export interface DownloadedFile {
  blob: Blob;
  fileName?: string;
}

/** `filename*=UTF-8''…` 을 먼저 본다 — 한글 파일명은 그쪽에만 온전히 담긴다 */
export function fileNameFrom(disposition: string | null): string | undefined {
  if (!disposition) return undefined;
  const encoded = /filename\*\s*=\s*(?:UTF-8'')?([^;]+)/i.exec(disposition);
  if (encoded) {
    try {
      return decodeURIComponent(encoded[1].trim().replace(/^"|"$/g, ''));
    } catch {
      // 잘못 인코딩된 이름이면 아래의 평문 이름으로 넘어간다
    }
  }
  const plain = /filename\s*=\s*"?([^";]+)"?/i.exec(disposition);
  return plain?.[1]?.trim();
}

/**
 * 파일을 내려받는 호출. 인증·갱신은 `request` 와 같다.
 *
 * 인증이 헤더라 `<a href>` 로는 받을 수 없다 — 브라우저 기본 다운로드는 `Authorization` 을 싣지 않는다.
 * 성공하면 본문을 파일로, 실패하면 Envelope 오류를 그대로 돌려준다.
 */
export async function requestFile(
  key: EndpointKey,
  path: string,
  options: RequestOptions = {},
): Promise<ApiResult<DownloadedFile>> {
  const state = getEndpointState(key);
  // 가짜 파일은 만들지 않는다 — 목 응답으로 받은 파일을 진짜로 믿고 제출하면 안 된다
  if (state !== 'LIVE') return { ready: false };

  const res = await sendWithRefresh(path, options);
  if (!res) return SESSION_EXPIRED;

  if (res.ok) {
    return {
      ready: true,
      ok: true,
      data: { blob: await res.blob(), fileName: fileNameFrom(res.headers.get('Content-Disposition')) },
      isMock: false,
    };
  }

  const body = await readBody(res);
  if (isEnvelope(body) && !body.success) {
    return { ready: true, ok: false, status: res.status, error: body.error };
  }
  return { ready: true, ok: false, status: res.status, error: NOT_IMPLEMENTED_ERROR };
}

/**
 * 단일 호출 경로. 화면 코드는 이 함수만 쓴다.
 *
 * NOT_READY면 서버를 부르지 않고 { ready:false }를 돌려준다. throw 하지 않는다.
 */
export async function request<T>(
  key: EndpointKey,
  path: string,
  options: RequestOptions & {
    /** MOCK 상태일 때 서버 대신 돌려줄 응답. 배포 빌드에서는 MOCK 이 NOT_READY 로 강등되어 불리지 않는다 */
    mock?: () => T | Promise<T>;
  } = {},
): Promise<ApiResult<T>> {
  const state = getEndpointState(key);
  if (state === 'NOT_READY') return { ready: false };
  if (state === 'MOCK' && options.mock) return { ready: true, ok: true, data: await options.mock(), isMock: true };

  const res = await sendWithRefresh(path, options);
  if (!res) return SESSION_EXPIRED;

  const body = await readBody(res);

  // Envelope가 없으면 우리 서버의 응답이 아니다.
  // Spring 기본 404({timestamp,status,path})가 여기로 온다 — "기능이 없다"는 뜻이다.
  if (!isEnvelope(body)) {
    if (res.ok) return { ready: true, ok: true, data: null as T, isMock: state === 'MOCK' };
    return { ready: true, ok: false, status: res.status, error: NOT_IMPLEMENTED_ERROR };
  }

  if (!body.success) {
    return { ready: true, ok: false, status: res.status, error: body.error };
  }

  return { ready: true, ok: true, data: body.data as T, isMock: state === 'MOCK' };
}

export type { ApiErrorBody, ApiResult, Envelope };
