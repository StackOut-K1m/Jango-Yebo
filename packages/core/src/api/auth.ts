import { authChannelHeaders, getConfig } from '../config';
import { clearSessionGone, clearTokens, request } from './client';
import type { ApiResult } from './envelope';
import type {
  LoginRequest,
  LoginResponse,
  Me,
  SignupRequest,
  SignupResponse,
  UpdateMeRequest,
} from './types/06_auth';

/**
 * 인증 API (docs/api/06_인증_사용자.md).
 *
 * 화면은 이 함수들만 쓴다. 경로·메서드·registry 키를 화면이 알 필요가 없다.
 */

export function signup(body: SignupRequest): Promise<ApiResult<SignupResponse>> {
  return request<SignupResponse>('auth.signup', '/auth/signup', {
    method: 'POST',
    json: body,
    anonymous: true,
  });
}

/**
 * 성공하면 토큰을 저장소에 넣는다. 화면이 따로 보관하지 않는다.
 *
 * 웹은 Refresh Token이 응답 본문에 없다 — HttpOnly 쿠키로 브라우저가 받는다(공통규약 2.1).
 * 앱은 `X-Client-Type: app`을 붙여 본문으로 받아 보관소에 넣는다. 이 저장이 없으면
 * Access Token이 만료되는 30분마다 로그인 화면으로 돌아간다.
 */
export async function login(body: LoginRequest): Promise<ApiResult<LoginResponse>> {
  const res = await request<LoginResponse>('auth.login', '/auth/login', {
    method: 'POST',
    json: body,
    anonymous: true,
    headers: authChannelHeaders(),
  });

  if (res.ready && res.ok) {
    const { tokenStore } = getConfig();
    tokenStore.setAccessToken(res.data.accessToken);
    // 쿠키 모드면 키 자체가 없다. 본문 모드에서만 채워진다.
    if (res.data.refreshToken) await tokenStore.setRefreshToken?.(res.data.refreshToken);
    // 세션이 다시 생겼으므로 갱신 재시도를 허용한다.
    clearSessionGone();
  }
  return res;
}

/** 서버가 실패해도 로컬 토큰은 반드시 지운다. 남겨두면 로그아웃된 화면이 토큰을 들고 있다. */
export async function logout(): Promise<ApiResult<null>> {
  const { tokenStore, refreshMode } = getConfig();
  // 앱은 쿠키가 없으니 폐기할 토큰을 본문으로 알려준다. 웹은 브라우저가 쿠키를 싣는다.
  const refreshToken = refreshMode === 'body' ? await tokenStore.getRefreshToken?.() : null;

  const res = await request<null>('auth.logout', '/auth/logout', {
    method: 'POST',
    headers: authChannelHeaders(),
    ...(refreshToken ? { json: { refreshToken } } : {}),
  });

  await clearTokens();
  return res;
}

export function getMe(): Promise<ApiResult<Me>> {
  return request<Me>('users.me.get', '/users/me');
}

export function updateMe(body: UpdateMeRequest): Promise<ApiResult<Me>> {
  return request<Me>('users.me.patch', '/users/me', { method: 'PATCH', json: body });
}

// refresh는 여기에 두지 않는다. client.ts 가 401을 만났을 때 스스로 처리하며,
// 동시 요청이 몰려도 갱신이 한 번만 나가도록 프로미스를 공유한다.
// 화면이 직접 부를 일이 없다.
