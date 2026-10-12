import { request } from './client';
import type { ApiResult } from './envelope';
import type { Page, SyncAccepted } from './types/02_common';
import type {
  Account,
  AccountPurgeResult,
  BankCode,
  Business,
  CreateAccountRequest,
  CreateBusinessRequest,
  PurgeAccountRequest,
  UpdateBusinessRequest,
  VerifyAccountRequest,
} from './types/07_business';

/**
 * 사업장·계좌 API (docs/api/07_사업장_계좌_수집.md).
 *
 * 다사업장·다계좌가 전제다(도메인 규칙 7). "사용자 = 사업장 1곳" 가정을 두지 않는다.
 */

/** 목록 응답은 items 만 온다. 페이징 필드는 오지 않는다(실측) */
type Items<T> = Pick<Page<T>, 'items'>;

export function listBusinesses(): Promise<ApiResult<Items<Business>>> {
  return request<Items<Business>>('businesses.list', '/businesses');
}

export function createBusiness(body: CreateBusinessRequest): Promise<ApiResult<Business>> {
  return request<Business>('businesses.create', '/businesses', { method: 'POST', json: body });
}

export function getBusiness(businessId: number): Promise<ApiResult<Business>> {
  return request<Business>('businesses.get', `/businesses/${businessId}`);
}

export function updateBusiness(
  businessId: number,
  body: UpdateBusinessRequest,
): Promise<ApiResult<Business>> {
  return request<Business>('businesses.patch', `/businesses/${businessId}`, {
    method: 'PATCH',
    json: body,
  });
}

export function listAccounts(businessId: number): Promise<ApiResult<Items<Account>>> {
  return request<Items<Account>>('accounts.list', `/businesses/${businessId}/accounts`);
}

/**
 * 계좌를 연결한다.
 *
 * 1원 인증이 없어 등록 즉시 CONNECTED 가 된다(구현현황 3.1). 인증 코드 입력 단계는
 * 붙일 엔드포인트가 없으므로 화면에도 두지 않는다.
 */
export function createAccount(
  businessId: number,
  body: CreateAccountRequest,
): Promise<ApiResult<Account>> {
  return request<Account>('accounts.create', `/businesses/${businessId}/accounts`, {
    method: 'POST',
    json: body,
  });
}

/**
 * 1원 인증 코드 검증. 성공하면 CONNECTED 가 된다.
 *
 * 틀린 코드를 넣어도 인증은 살아 있다(금융망 실측) — 재발송 없이 곧바로 다시 넣으면 된다.
 */
export function verifyAccount(
  accountId: number,
  body: VerifyAccountRequest,
): Promise<ApiResult<Account>> {
  return request<Account>('accounts.verify', `/accounts/${accountId}/verify`, {
    method: 'POST',
    json: body,
  });
}

/** 1원 재발송. 유효시간과 횟수 상한은 서버가 정한다 — 화면에 하드코딩하지 않는다. */
export function resendVerification(accountId: number): Promise<ApiResult<Account>> {
  return request<Account>('accounts.verification', `/accounts/${accountId}/verification`, {
    method: 'POST',
  });
}

/**
 * 금융망 거래 동기화를 접수한다 (API 7.3).
 *
 * CSV 임포트와 정반대다 — 응답의 Job 은 아직 QUEUED 이고 워커가 처리한다.
 * 완료는 getJob(jobId) 으로 본다. 종료 상태는 COMPLETED·FAILED 다.
 *
 * 인증 직후에는 부를 필요가 없다 — verify 가 같은 작업을 자동 접수한다(구현현황 3.1).
 * 인증 전 계좌는 409 ACCOUNT_NOT_VERIFIED 다.
 *
 * 수집 하한은 계좌 연결일이다. 그 이전 거래는 오지 않는다 — 과거는 CSV 담당이다.
 */
export function syncAccount(accountId: number): Promise<ApiResult<SyncAccepted>> {
  return request<SyncAccepted>('accounts.sync', `/accounts/${accountId}/sync`, {
    method: 'POST',
  });
}

/**
 * 연동을 해제한다 (명세 7.2). 204 라 본문이 없다.
 *
 * 계좌 행은 지워지지 않고 DISCONNECTED 가 된다 — 목록에 계속 남는다.
 * 이미 수집된 거래는 보존하고 이후 동기화 대상에서만 뺀다. "거래가 지워진다"가 아니다.
 */
export function disconnectAccount(accountId: number): Promise<ApiResult<null>> {
  return request<null>('accounts.disconnect', `/accounts/${accountId}`, { method: 'DELETE' });
}

/**
 * 계좌를 지운다 (명세 7.2). **되돌릴 수 없다.**
 *
 * disconnectAccount 와 헷갈리면 안 된다. 그쪽은 해제라 계좌 행이 남고 거래가 보존되며
 * 같은 계좌번호로 다시 등록하면 되살아난다. 이쪽은 계좌·거래·예정 거래·반복 패턴이
 * 실제로 사라지고, 같은 계좌번호를 다시 넣으면 **새 계좌**가 된다.
 *
 * DELETE 가 아니라 POST 인 이유는 해제가 이미 DELETE 를 쓰고 있어서다. 같은 메서드에
 * 플래그로 얹으면 웹보다 늦게 배포되는 앱의 구버전이 「해제」하려다 하드 삭제를 부른다.
 *
 * confirm 은 **계좌 별칭**(alias)이다. 계좌번호를 묻지 않는 이유는 화면에 마스킹밖에 없고
 * 그 마스킹이 앞뒤 3자리씩만 남겨(110-***-***789) 「뒤 몇 자리」가 모호하기 때문이다.
 *
 * purgeOrphanEvidence 는 **이 계좌 거래에만** 붙어 있던 증빙을 함께 지운 것으로 표시한다.
 * 기본은 false 이며, 다른 계좌 거래에도 붙은 증빙은 켜도 남는다(증빙 ↔ 거래는 N:M).
 */
export function purgeAccount(
  accountId: number,
  body: PurgeAccountRequest,
): Promise<ApiResult<AccountPurgeResult>> {
  return request<AccountPurgeResult>('accounts.purge', `/accounts/${accountId}/purge`, {
    method: 'POST',
    json: body,
  });
}

/** 은행 목록. 인증이 필요 없고 서버가 캐시하므로 화면에서 매번 불러도 된다. */
export function listBankCodes(): Promise<ApiResult<Items<BankCode>>> {
  return request<Items<BankCode>>('bankCodes.list', '/bank-codes', { anonymous: true });
}
