// core는 플랫폼 API를 직접 참조하지 않는다.
// window·localStorage·React Native 모듈을 여기서 쓰면 반대쪽 앱에서 깨진다.
// 환경변수 접두사도 웹(VITE_)과 앱(EXPO_PUBLIC_)이 다르므로 각 앱이 읽어서 넘긴다.

/** Access Token 보관소. 웹은 메모리, 앱은 expo-secure-store를 넣는다. */
export interface TokenStore {
  getAccessToken: () => string | null;
  setAccessToken: (token: string | null) => void;
  /**
   * Refresh Token 보관소. **`refreshMode: 'body'`면 반드시 넣는다** — 없으면 앱이
   * 갱신할 토큰을 찾지 못해 Access Token이 만료되는 30분마다 로그인 화면으로 돌아간다.
   *
   * 쿠키 모드(웹)에서는 쓰지 않는다. 브라우저가 쿠키로 들고 있으므로 프론트가 볼 일이 없다.
   *
   * 앱의 보관소(expo-secure-store)는 비동기라 Promise를 돌려줄 수 있게 열어 둔다.
   */
  getRefreshToken?: () => string | null | Promise<string | null>;
  setRefreshToken?: (token: string | null) => void | Promise<void>;
}

export interface CoreConfig {
  /** 예: '/api/v1' (웹, 프록시 경유) 또는 'http://10.0.2.2:8080/api/v1' (앱) */
  baseUrl: string;
  /**
   * 배포 빌드 여부. true면 registry의 MOCK이 전부 NOT_READY로 강등된다.
   * 웹은 import.meta.env.PROD, 앱은 !__DEV__ 를 넘긴다.
   */
  isProduction: boolean;
  tokenStore: TokenStore;
  /**
   * Refresh Token 전달 방식.
   * 'cookie' — 웹. HttpOnly 쿠키를 브라우저가 싣는다(공통규약 2.1)
   * 'body'   — 앱. 쿠키 저장소가 없어 본문으로 주고받는다. 인증 요청에 `X-Client-Type: app`이
   *            붙고, tokenStore의 getRefreshToken·setRefreshToken이 있어야 한다
   */
  refreshMode: 'cookie' | 'body';
}

let config: CoreConfig | null = null;

export function configureCore(next: CoreConfig): void {
  // 본문 모드인데 보관소가 없으면 증상이 「30분마다 로그아웃」으로만 나타난다 —
  // 갱신이 조용히 실패하고 화면은 로그인으로 돌아갈 뿐이라 원인이 드러나지 않는다.
  // SecurityProperties 가 빈 JWT_SECRET 에 부팅을 멈추는 것과 같은 판단으로, 여기서 끊는다.
  if (next.refreshMode === 'body' && !(next.tokenStore.getRefreshToken && next.tokenStore.setRefreshToken)) {
    throw new Error("refreshMode: 'body'에는 tokenStore의 getRefreshToken·setRefreshToken이 필요합니다.");
  }
  config = next;
}

export function getConfig(): CoreConfig {
  if (!config) {
    throw new Error('configureCore()를 앱 시작 시 먼저 호출해야 합니다.');
  }
  return config;
}

/** 테스트에서 상태를 되돌리기 위한 것. 앱 코드에서 쓰지 않는다. */
export function resetCore(): void {
  config = null;
}

/** 메모리 보관소. 웹 기본값이며 새로고침하면 사라진다(공통규약 2.1: localStorage 저장 비권장). */
export function createMemoryTokenStore(): TokenStore {
  let token: string | null = null;
  return {
    getAccessToken: () => token,
    setAccessToken: (next) => {
      token = next;
    },
  };
}

/** 인증 요청에 붙여 서버가 본문 채널로 답하게 하는 헤더(공통규약 2.1). */
export const APP_CLIENT_HEADER = { 'X-Client-Type': 'app' } as const;

/** 본문 모드에서만 붙인다. 쿠키 모드에 붙이면 서버가 무시하지만, 보낼 이유도 없다. */
export function authChannelHeaders(): Record<string, string> {
  return getConfig().refreshMode === 'body' ? { ...APP_CLIENT_HEADER } : {};
}
