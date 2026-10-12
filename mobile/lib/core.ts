import { configureCore } from '@jangoyebo/core';
import { createSecureTokenStore } from '@/lib/tokenStore';

/**
 * 공유 코어에 플랫폼 조각을 넣어 준다. 앱이 API를 부르기 전에 한 번만 실행한다.
 *
 * 코어는 플랫폼을 모른다 — baseUrl·토큰 저장소·배포 여부를 여기서 주입한다(CLAUDE.md).
 *
 * `refreshMode: 'body'` 가 앱과 웹을 가르는 지점이다. 앱에는 쿠키 저장소가 없어
 * HttpOnly 쿠키를 받을 수 없으므로 Refresh Token을 본문으로 주고받는다(공통규약 2.1).
 */
export function configureAppCore(): void {
  const baseUrl = process.env.EXPO_PUBLIC_API_BASE_URL;
  if (!baseUrl) {
    // 조용히 넘어가면 모든 화면이 "서버 없음"으로 보인다. 원인을 여기서 말한다.
    throw new Error('EXPO_PUBLIC_API_BASE_URL 이 없습니다. mobile/.env.development 를 확인하세요.');
  }

  configureCore({
    baseUrl,
    // 웹의 import.meta.env.PROD 자리. MOCK 엔드포인트를 배포 빌드에서 강등시킨다.
    isProduction: !__DEV__,
    tokenStore: createSecureTokenStore(),
    refreshMode: 'body',
  });
}
