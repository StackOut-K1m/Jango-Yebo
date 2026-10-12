import * as SecureStore from 'expo-secure-store';
import type { TokenStore } from '@jangoyebo/core';

/**
 * 앱의 토큰 보관소.
 *
 * Access Token은 **메모리**에만 둔다. 30분이면 만료되고, 만료되면 코어가 Refresh Token으로
 * 조용히 새로 받아 온다(공통규약 2.1).
 *
 * Refresh Token은 14일짜리라 앱을 껐다 켜도 살아 있어야 한다. 그래서 `expo-secure-store`에
 * 넣는다 — Android Keystore로 암호화되므로 `AsyncStorage`와 달리 평문으로 남지 않는다.
 *
 * 앱을 다시 켜면 Access Token이 없어 첫 호출이 401이고, 코어가 그것을 받아 갱신한 뒤 재시도한다.
 * 즉 여기 저장된 Refresh Token이 그대로 "로그인 유지"다.
 */
const REFRESH_KEY = 'jangoyebo.refreshToken';

export function createSecureTokenStore(): TokenStore {
  let accessToken: string | null = null;

  return {
    getAccessToken: () => accessToken,
    setAccessToken: (next) => {
      accessToken = next;
    },
    getRefreshToken: () => SecureStore.getItemAsync(REFRESH_KEY),
    setRefreshToken: async (next) => {
      // setItemAsync 는 null 을 받지 못한다. 지우는 것은 별도 호출이다.
      if (next === null) await SecureStore.deleteItemAsync(REFRESH_KEY);
      else await SecureStore.setItemAsync(REFRESH_KEY, next);
    },
  };
}
