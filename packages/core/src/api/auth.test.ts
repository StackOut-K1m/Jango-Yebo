import { afterEach, expect, it, vi } from 'vitest';
import { configureCore, createMemoryTokenStore, resetCore, type TokenStore } from '../config';
import { login, logout } from './auth';
import { request } from './client';

/**
 * 인증 토큰이 어느 채널로 오가는가 (공통규약 2.1).
 *
 * 앱은 HttpOnly 쿠키를 읽지 못해 Access Token이 만료되는 30분마다 로그인 화면으로 돌아갔다.
 * 본문 채널이 그 구멍을 메운다 — 여기서 고정하는 것은 **웹은 아무것도 달라지지 않는다**는 것과,
 * 앱이 회전된 새 토큰을 반드시 갈아 끼운다는 것이다. 갈아 끼우지 않으면 다음 갱신이 401이고
 * 증상은 원래대로 30분 만료로 돌아간다.
 */

/** 앱의 expo-secure-store를 흉내 낸다 — 읽기·쓰기가 모두 비동기다. */
function createAsyncTokenStore(refreshToken: string | null = null) {
  let access: string | null = null;
  let refresh = refreshToken;
  const store: TokenStore = {
    getAccessToken: () => access,
    setAccessToken: (next) => {
      access = next;
    },
    getRefreshToken: async () => refresh,
    setRefreshToken: async (next) => {
      refresh = next;
    },
  };
  return {
    store,
    accessToken: () => access,
    refreshToken: () => refresh,
    seedAccessToken: (next: string) => {
      access = next;
    },
  };
}

const setup = (store: TokenStore, refreshMode: 'cookie' | 'body') =>
  configureCore({ baseUrl: '/api/v1', isProduction: false, tokenStore: store, refreshMode });

const envelope = (status: number, data: unknown) =>
  new Response(JSON.stringify(status === 401 ? { success: false, error: { code: 'AUTH_TOKEN_INVALID' } } : { success: true, data }), {
    status,
    headers: { 'Content-Type': 'application/json' },
  });

const loginBody = { email: 'owner@example.com', password: 'StrongPass!23' };
const loginData = (accessToken: string, refreshToken?: string) => ({
  accessToken,
  expiresIn: 1800,
  user: { userId: 7, name: '문성준' },
  ...(refreshToken ? { refreshToken, refreshExpiresIn: 1209600 } : {}),
});

const headersOf = (call: unknown[]) => new Headers((call[1] as RequestInit).headers);
const jsonOf = (call: unknown[]) => JSON.parse((call[1] as RequestInit).body as string);

afterEach(() => {
  resetCore();
  vi.unstubAllGlobals();
});

it('앱 로그인은 앱 헤더를 붙이고 받은 Refresh Token을 보관한다', async () => {
  const fetch = vi.fn().mockResolvedValue(envelope(200, loginData('access-1', 'refresh-1')));
  vi.stubGlobal('fetch', fetch);
  const held = createAsyncTokenStore();
  setup(held.store, 'body');

  await login(loginBody);

  expect(headersOf(fetch.mock.calls[0]).get('X-Client-Type')).toBe('app');
  expect(held.accessToken()).toBe('access-1');
  expect(held.refreshToken()).toBe('refresh-1');
});

it('웹 로그인은 앱 헤더를 붙이지 않는다 — 쿠키 방식은 그대로다', async () => {
  const fetch = vi.fn().mockResolvedValue(envelope(200, loginData('access-1')));
  vi.stubGlobal('fetch', fetch);
  const held = createAsyncTokenStore();
  setup(held.store, 'cookie');

  await login(loginBody);

  expect(headersOf(fetch.mock.calls[0]).get('X-Client-Type')).toBeNull();
  expect((fetch.mock.calls[0][1] as RequestInit).credentials).toBe('include');
  expect(held.refreshToken()).toBeNull();
});

it('401을 만나면 보관된 토큰으로 조용히 갱신하고 원요청을 다시 보낸다', async () => {
  const fetch = vi
    .fn()
    // 로그인
    .mockResolvedValueOnce(envelope(200, loginData('access-1', 'refresh-1')))
    // 화면 호출 — Access Token 만료
    .mockResolvedValueOnce(envelope(401, null))
    // 갱신 — 회전된 토큰 두 개
    .mockResolvedValueOnce(envelope(200, loginData('access-2', 'refresh-2')))
    // 재시도
    .mockResolvedValueOnce(envelope(200, { userId: 7 }));
  vi.stubGlobal('fetch', fetch);
  const held = createAsyncTokenStore();
  setup(held.store, 'body');
  await login(loginBody);

  const result = await request('users.me.get', '/users/me');

  expect(result).toMatchObject({ ready: true, ok: true });
  expect(jsonOf(fetch.mock.calls[2])).toEqual({ refreshToken: 'refresh-1' });
  expect(headersOf(fetch.mock.calls[2]).get('X-Client-Type')).toBe('app');
  // 회전 — 새 토큰으로 갈아 끼우지 않으면 다음 갱신이 401이다.
  expect(held.refreshToken()).toBe('refresh-2');
  expect(headersOf(fetch.mock.calls[3]).get('Authorization')).toBe('Bearer access-2');
});

it('보관된 Refresh Token이 없으면 갱신을 시도하지 않는다', async () => {
  const fetch = vi.fn().mockResolvedValue(envelope(401, null));
  vi.stubGlobal('fetch', fetch);
  const held = createAsyncTokenStore();
  held.seedAccessToken('access-1');
  setup(held.store, 'body');

  const result = await request('users.me.get', '/users/me');

  expect(result).toMatchObject({ ready: true, ok: false, status: 401 });
  // 화면 호출 한 번뿐 — /auth/refresh 는 나가지 않는다.
  expect(fetch).toHaveBeenCalledTimes(1);
});

it('갱신이 거절되면 두 토큰을 모두 지운다', async () => {
  const fetch = vi
    .fn()
    .mockResolvedValueOnce(envelope(200, loginData('access-1', 'refresh-1')))
    .mockResolvedValueOnce(envelope(401, null))
    .mockResolvedValueOnce(envelope(401, null));
  vi.stubGlobal('fetch', fetch);
  const held = createAsyncTokenStore();
  setup(held.store, 'body');
  await login(loginBody);

  await request('users.me.get', '/users/me');

  expect(held.accessToken()).toBeNull();
  expect(held.refreshToken()).toBeNull();
});

it('앱 로그아웃은 폐기할 토큰을 본문으로 알려주고 보관소를 비운다', async () => {
  const fetch = vi.fn().mockResolvedValue(new Response(null, { status: 204 }));
  vi.stubGlobal('fetch', fetch);
  const held = createAsyncTokenStore('refresh-1');
  held.seedAccessToken('access-1');
  setup(held.store, 'body');

  await logout();

  expect(jsonOf(fetch.mock.calls[0])).toEqual({ refreshToken: 'refresh-1' });
  expect(held.accessToken()).toBeNull();
  expect(held.refreshToken()).toBeNull();
});

it('웹 로그아웃은 본문 없이 나간다 — 쿠키를 브라우저가 싣는다', async () => {
  const fetch = vi.fn().mockResolvedValue(new Response(null, { status: 204 }));
  vi.stubGlobal('fetch', fetch);
  const held = createAsyncTokenStore('무시된다');
  setup(held.store, 'cookie');

  await logout();

  expect((fetch.mock.calls[0][1] as RequestInit).body).toBeUndefined();
  expect(headersOf(fetch.mock.calls[0]).get('X-Client-Type')).toBeNull();
});

it('본문 모드인데 Refresh Token 보관소가 없으면 시작할 때 막는다', () => {
  // 그냥 두면 증상이 「30분마다 로그아웃」으로만 나타나 원인을 찾기 어렵다.
  expect(() =>
    configureCore({
      baseUrl: '/api/v1',
      isProduction: false,
      tokenStore: createMemoryTokenStore(),
      refreshMode: 'body',
    }),
  ).toThrow(/getRefreshToken/);
});
