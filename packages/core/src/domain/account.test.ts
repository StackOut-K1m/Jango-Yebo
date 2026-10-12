import { expect, it } from 'vitest';
import { countedAccounts, countsTowardBalance } from './account';
import type { AccountStatus } from '../api/types/07_business';

const withStatus = (status: AccountStatus) => ({ status });

// 연동을 끊은 계좌의 돈이 합계에 남아 있으면 사용자는 해제로 무엇을 했는지 알 수 없다.
// 서버도 시작 잔액을 같은 기준으로 고른다 — 갈리면 합계와 곡선의 출발점이 어긋난다.
it('해제된 계좌는 세지 않는다', () => {
  expect(countsTowardBalance(withStatus('DISCONNECTED'))).toBe(false);
  expect(countsTowardBalance(withStatus('CONNECTED'))).toBe(true);
});

// 세지 말아야 해서가 아니라 잔액을 아직 모르기 때문이다. 목록에서 빼면 「계좌 3개」가
// 「1개」가 되어, 연결은 해 두고 인증만 안 끝낸 계좌가 화면에서 사라진다.
it('인증 대기·만료 계좌는 남긴다', () => {
  expect(countsTowardBalance(withStatus('PENDING_VERIFICATION'))).toBe(true);
  expect(countsTowardBalance(withStatus('VERIFICATION_EXPIRED'))).toBe(true);
});

it('해제된 계좌만 걸러낸다', () => {
  const accounts = [
    withStatus('CONNECTED'),
    withStatus('DISCONNECTED'),
    withStatus('PENDING_VERIFICATION'),
  ];

  expect(countedAccounts(accounts).map((a) => a.status)).toEqual([
    'CONNECTED',
    'PENDING_VERIFICATION',
  ]);
});
