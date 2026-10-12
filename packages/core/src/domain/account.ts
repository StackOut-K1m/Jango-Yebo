import type { Account } from '../api/types/07_business';

/**
 * 어떤 계좌가 「지금 내 돈」에 들어가는가 (도메인 규칙).
 *
 * 웹과 앱이 다르게 세면 같은 사업장을 두고 한쪽은 1,340,000원, 다른 쪽은 2,100,000원을
 * 보여준다. 돈을 세는 화면에서 그건 사고다 — 그래서 판정은 이 한 파일에 둔다.
 *
 * **기준이 하나가 아니다.** 합계·목록은 해제만 빼고(`countsTowardBalance`), 잔액 곡선은
 * `CONNECTED` 만 센다(`entersBalanceCurve`) — 서버가 곡선의 시작 잔액을 그렇게 고르기
 * 때문이다(`AccountBalancesService`). 둘을 바꿔 쓰면 잔액과 거래가 서로 다른 계좌 집합에서
 * 와 곡선이 통째로 어긋난다. 세는 것이 「지금 내 돈」이면 앞, 「곡선」이면 뒤다.
 */

/**
 * 합계에 세는 계좌인가.
 *
 * 해제된 계좌만 뺀다. 인증 대기·만료 계좌는 남기는데, 세지 말아야 해서가 아니라
 * **아직 잔액을 모르기** 때문이다 — 서버가 `balance` 를 주지 않으므로 합계에 들어갈 값이
 * 없고, 그 사실은 `partial` 이 따로 말한다. 해제는 다르다: 값이 있어도 세지 않는다.
 */
export const countsTowardBalance = (account: Pick<Account, 'status'>) =>
  account.status !== 'DISCONNECTED';

/** 합계·목록에 쓸 계좌만 남긴다. */
export const countedAccounts = <T extends Pick<Account, 'status'>>(accounts: T[]) =>
  accounts.filter(countsTowardBalance);

/**
 * 그 계좌의 거래를 **잔액 곡선에** 세는가.
 *
 * 합계보다 좁다 — `CONNECTED` 만이다. 서버가 곡선의 시작 잔액을 그렇게 고르기 때문이다
 * (`AccountBalancesService` 의 `a.status = 'CONNECTED'`). 반면 거래 목록은 해제만 뺀다
 * (`TransactionRepository#search`) — 인증 전 계좌에 CSV 로 올린 거래도 사용자 자료라서다.
 *
 * **그 둘을 섞으면 과거 곡선이 통째로 어긋난다.** 시작 잔액에는 없는 계좌의 거래만
 * 얹히기 때문이다. 「화면에 보이는 것과 곡선이 세는 것이 다른 것은 의도다」
 * (`TransactionHistoryService`) — 곡선을 그리는 쪽은 좁은 기준을 써야 한다.
 */
export const entersBalanceCurve = (account: Pick<Account, 'status'>) =>
  account.status === 'CONNECTED';
