// docs/api/07_사업장_계좌_수집.md
//
// 실서버 응답으로 검증했다(2026-09-08, 로컬 백엔드 develop f18da53).
// 명세와 다른 곳은 아래에 표시한다. 응답에 없는 필드는 null 이 아니라 키 자체가 없다.

import type { RiskLevel } from './02_common';

export type IndustryType =
  | 'FOOD_CAFE'
  | 'FOOD_RESTAURANT'
  | 'FOOD_BAKERY'
  | 'FOOD_OTHER'
  | 'RETAIL'
  | 'SERVICE'
  | 'OTHER';

export type TaxationType = 'GENERAL' | 'SIMPLIFIED';

export interface Business {
  businessId: number;
  name: string;
  industryType: IndustryType;
  taxationType: TaxationType;
  /** 마스킹된 사업자등록번호. 예: ***-**-67890 (구현현황 3.9) */
  businessRegNoMasked?: string;
  region?: string;
  /** 실측: 응답에 오지 않는다. 요청으로는 받는다 */
  openedAt?: string;
  /** 스텁이다. 값을 신뢰하지 않는다 */
  accountCount?: number;
  /** 스텁이다 */
  currentRiskLevel?: RiskLevel;
}

export interface CreateBusinessRequest {
  name: string;
  industryType: IndustryType;
  taxationType: TaxationType;
  /** 형식: 123-45-67890 (하이픈 필수). 명세 7.1에 필수 표시는 없다 */
  businessRegNo?: string;
  region?: string;
  openedAt?: string;
  annualRevenue?: number;
  employeeCount?: number;
}

export type UpdateBusinessRequest = Partial<CreateBusinessRequest>;

/**
 * 계좌 상태 (구현현황 3.1, 2026-09-08 개정).
 *
 * 등록하면 PENDING_VERIFICATION 으로 생기고 그 시점에 이미 1원이 나가 있다.
 * 유효시간이 지나면 목록을 조회하는 시점에 VERIFICATION_EXPIRED 가 된다 — 폴링하지 않는다.
 */
export type AccountStatus =
  | 'PENDING_VERIFICATION'
  | 'VERIFICATION_EXPIRED'
  | 'CONNECTED'
  | 'DISCONNECTED';
/**
 * 동기화 상태. 값은 ERD가 기준이다(docs/ERD.md ACCOUNT.sync_status).
 *
 * 명세 7.2가 열거한 적이 없어 한때 SYNCING·SYNCED 로 적혀 있었다 — 프론트의 추측이었다.
 * 지금은 서버가 항상 NEVER 를 주지만 동기화가 들어오면 상태 뱃지가 깨진다(백엔드 지적, 2026-09-08).
 */
export type SyncStatus = 'NEVER' | 'RUNNING' | 'SUCCESS' | 'FAILED';

export interface Account {
  accountId: number;
  bankCode: string;
  accountNoMasked: string;
  alias?: string;
  isPrimary: boolean;
  /**
   * 예정 거래 자동 반영 여부 (API 9.6 · 시연용).
   *
   * 켜진 계좌는 서버가 예정일마다 금융망에서 실제로 출금·입금한다. 화면에 토글을 두지
   * 않는다 — 켜는 것은 시드 도구와 계좌 소유자의 일이다.
   */
  autoExecutePlanned?: boolean;
  status: AccountStatus;
  syncStatus: SyncStatus;
  /**
   * 실측: 아래 셋은 응답에 키가 없다. 구현현황 3.1은 "항상 null"이라고 적혀 있으나
   * 실제로는 부재다. 잔액 조회가 금융망 기능이라 동기화 전까지 값이 없다.
   */
  balance?: number | null;
  balanceUpdatedAt?: string | null;
  lastSyncedAt?: string | null;
  /** 실측: 응답에 오지 않는다. 목록·상세 모두 bankCode 만 온다 */
  bankName?: string;
}

export interface CreateAccountRequest {
  bankCode: string;
  /**
   * 숫자와 구분자(하이픈·공백)만. 하이픈은 있어도 없어도 되고 서버가 정규화해 같은 계좌로
   * 인식한다(실측). 그 밖의 문자 — 전각 숫자 포함 — 는 `400 VALIDATION_FAILED` 다(구현현황 3.7)
   */
  accountNo: string;
  alias?: string;
  isPrimary?: boolean;
}

/** 인증코드는 항상 숫자 4자리다. 통장 적요에 "잔고예보 5020" 형태로 찍힌다. */
export interface VerifyAccountRequest {
  authCode: string;
}

/**
 * POST /accounts/{accountId}/purge — 계좌 삭제. **되돌릴 수 없다.**
 *
 * 연동 해제(DELETE)와 다른 문이다. 그쪽은 계좌 행을 남기고 거래를 보존한다.
 */
export interface PurgeAccountRequest {
  /**
   * 계좌 별칭(`alias`)을 그대로. 오조작 방어이며 틀리면 `400 VALIDATION_FAILED` 다.
   *
   * 계좌번호를 묻지 않는다 — 화면에는 마스킹밖에 없고 그것이 앞뒤 3자리씩만 남겨
   * (`110-***-***789`) "뒤 몇 자리"가 무엇인지 사용자가 알 수 없다.
   */
  confirm: string;
  /**
   * **이 계좌 거래에만** 붙어 있던 증빙도 지운 것으로 표시할지. 기본 false.
   *
   * 다른 계좌 거래에도 붙은 증빙은 켜도 남는다 — 증빙 ↔ 거래는 N:M 이고, 그것까지 지우면
   * 살아 있는 계좌의 근거가 사라진다. 증빙 행과 파일은 어느 쪽이든 지워지지 않는다.
   */
  purgeOrphanEvidence?: boolean;
}

/** 무엇이 얼마나 지워졌는지. 화면이 사용자에게 되짚어 준다. */
export interface AccountPurgeResult {
  transactions: number;
  /** 끊은 증빙 연결 수. 증빙 자체가 지워진 수가 아니다 */
  evidenceLinks: number;
  /** 지운 것으로 표시한 고아 증빙 수. purgeOrphanEvidence 가 false 면 항상 0 */
  evidenceMarkedDeleted: number;
  settlementAllocations: number;
  /** 배분이 빠져 상태를 다시 판정한 정산 수 */
  settlementsRefreshed: number;
}

/** GET /bank-codes — 인증 불필요. 금융망 실시간 조회 + 서버 캐시(구현현황 v1.9) */
export interface BankCode {
  bankCode: string;
  bankName: string;
}
