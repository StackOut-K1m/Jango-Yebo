// docs/api/06_인증_사용자.md
//
// 실서버 응답으로 검증했다(2026-09-08, 로컬 백엔드). 명세와 다른 곳은 아래에 표시한다.

export interface SignupRequest {
  email: string;
  password: string;
  name: string;
  phone?: string;
}

export interface SignupResponse {
  userId: number;
  email: string;
  name: string;
}

export interface LoginRequest {
  email: string;
  password: string;
}

/**
 * 웹은 Refresh Token이 응답 본문에 없다. HttpOnly 쿠키로 내려온다(공통규약 2.1).
 * 실측: Path=/api/v1/auth, Max-Age=1209600, Secure, HttpOnly, SameSite=Lax.
 *
 * 앱(`X-Client-Type: app`)만 refreshToken·refreshExpiresIn을 본문으로 받고 쿠키를 받지 않는다.
 * 갱신 응답도 같은 모양이다 — 서버는 토큰이 온 채널로만 돌려준다.
 */
export interface LoginResponse {
  accessToken: string;
  /** Access Token 만료까지 남은 초. 실측 1800(30분) */
  expiresIn: number;
  /** ⚠ Me 전체가 아니다. 로그인 응답에는 userId·name 둘뿐이다 */
  user: LoginUser;
  /** 앱 전용. 쿠키 모드에서는 키 자체가 없다 */
  refreshToken?: string;
  /** 앱 전용. Refresh Token 만료까지 남은 초. 1209600(14일) */
  refreshExpiresIn?: number;
}

export interface LoginUser {
  userId: number;
  name: string;
}

export interface Me {
  userId: number;
  email: string;
  name: string;
  /** 실측으로 확인된 필드 */
  businessCount?: number;
  createdAt?: string;
  /**
   * 값이 없으면 키 자체가 없다(공통규약의 non_null 정책).
   * 첫 사업장을 만들면 그것이 자동으로 기본 사업장이 된다.
   * 두 번째부터는 덮어쓰지 않는다 — 사용자가 PATCH /users/me 로 고른 값이 우선이다(구현현황 3.10).
   */
  defaultBusinessId?: number;
  phone?: string;
}

export interface UpdateMeRequest {
  name?: string;
  phone?: string;
  defaultBusinessId?: number;
}
