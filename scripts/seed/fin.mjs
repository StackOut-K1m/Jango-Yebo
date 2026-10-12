// 금융망 호출 — 시드 스크립트가 쓰는 최소한만. 백엔드 어댑터와 별개로 두는 이유는
// 시드가 백엔드를 거치지 않고 해야 하는 일(잔액 맞추기·인증코드 읽기)이 있어서다.
//
// ⚠ 요청 본문을 절대 찍지 않는다. Header 에 apiKey 와 userKey 가 들어 있다.
// ⚠ reIssuedApiKey(MANAGER_02)는 절대 부르지 않는다. 팀 키가 즉시 무효가 되고 재발급이 막힌다.

import { readFileSync } from 'node:fs';
import { resolve } from 'node:path';

const BASE = 'https://finopenapi.ssafy.io/ssafy/api/v1';

/**
 * 개발·시연 계좌는 <b>싸피은행</b>에 만든다. 실제 은행망과 무관한 가상 은행이라
 * 여기 쓰라고 있는 것이고, {@code scripts/fin-account.mjs} 가 잡은 팀 관례다.
 */
export const SSAFY_BANK = '999';

/** backend/.env 형식의 아주 단순한 파서. 따옴표·다중행은 쓰지 않는 전제다. */
export function readEnv(repo, file) {
  try {
    return Object.fromEntries(
      readFileSync(resolve(repo, 'backend', file), 'utf8')
        .split('\n')
        .map((line) => line.trim())
        .filter((line) => line && !line.startsWith('#') && line.includes('='))
        .map((line) => {
          const at = line.indexOf('=');
          return [line.slice(0, at).trim(), line.slice(at + 1).trim()];
        }),
    );
  } catch {
    throw new Error(`backend/${file} 을 읽을 수 없습니다. JANGO_REPO 로 저장소 경로를 지정하세요.`);
  }
}

const pad = (n, len = 2) => String(n).padStart(len, '0');

/**
 * 한국시간 기준 전송일자·시각. <b>±5분을 벗어나면 전부 H1003</b> 이라 서버 시간대에 기대지 않는다.
 */
function kstStamp() {
  const kst = new Date(Date.now() + 9 * 3600 * 1000);
  return {
    date: `${kst.getUTCFullYear()}${pad(kst.getUTCMonth() + 1)}${pad(kst.getUTCDate())}`,
    time: `${pad(kst.getUTCHours())}${pad(kst.getUTCMinutes())}${pad(kst.getUTCSeconds())}`,
  };
}

export class FinClient {
  constructor(env) {
    this.apiKey = env.FINOPENAPI_API_KEY;
    this.institutionCode = env.FINOPENAPI_INSTITUTION_CODE ?? '00100';
    this.fintechAppNo = env.FINOPENAPI_FINTECH_APP_NO ?? '001';
    if (!this.apiKey || this.apiKey.startsWith('__')) {
      throw new Error('FINOPENAPI_API_KEY 가 비어 있습니다. 서버 ~/app/secrets/finopenapi.env 에서 받으세요.');
    }
  }

  header(apiName, userKey) {
    const { date, time } = kstStamp();
    return {
      apiName,
      transmissionDate: date,
      transmissionTime: time,
      institutionCode: this.institutionCode,
      fintechAppNo: this.fintechAppNo,
      apiServiceCode: apiName,
      // ⚠ 정확히 20자. 0 패딩을 빼면 간헐적으로만 실패해서 원인을 찾기 어렵다.
      institutionTransactionUniqueNo: date + time + pad(Math.floor(Math.random() * 1e6), 6),
      apiKey: this.apiKey,
      ...(userKey ? { userKey } : {}),
    };
  }

  async call(path, apiName, userKey, payload) {
    const res = await fetch(`${BASE}/edu/${path}`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ Header: this.header(apiName, userKey), ...payload }),
    });
    const body = await res.json().catch(() => ({}));
    // 성공은 Header 아래, 실패는 최상위에 responseCode 가 온다.
    const code = body?.Header?.responseCode ?? body?.responseCode;
    if (code !== 'H0000') {
      const err = new Error(`${apiName} 실패 ${code ?? res.status}: ${body?.responseMessage ?? body?.Header?.responseMessage ?? ''}`);
      // 부르는 쪽이 코드로 갈라야 하는 자리가 있다 — 해지의 A1068·A1069·A1070 은
      // 우리가 고칠 수 없는 이유라 던지지 않고 건너뛴다(pipeline.mjs CLOSE_BLOCKED).
      err.code = code ?? String(res.status);
      throw err;
    }
    return body.REC;
  }

  /**
   * MEMBER_02(조회) → 없으면 MEMBER_01(생성). <b>이메일 하나로 userKey 가 정해진다.</b>
   *
   * <p>백엔드도 사용자 이메일로 같은 userKey 를 뽑으므로(FinUserKeyService), 여기서 만든
   * 계좌가 그대로 그 사용자 것이 된다. 시드가 {@code .env.seed.local} 없이 도는 이유이기도 하다 —
   * 공유 시크릿 파일을 받아야만 돌릴 수 있으면 팀원이 못 쓴다.
   *
   * <p>MEMBER 계열은 {@code /edu} 가 아니라 {@code /member} 아래이고 Header 를 쓰지 않는다.
   */
  async memberKey(email) {
    const post = async (path, body) => {
      const res = await fetch(`${BASE}/member${path}`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify(body),
      });
      return res.json().catch(() => ({}));
    };

    const found = await post('/search', { apiKey: this.apiKey, userId: email });
    if (found?.userKey) return found.userKey;

    const made = await post('/', { apiKey: this.apiKey, userId: email });
    if (made?.userKey) return made.userKey;

    const hint = made?.responseCode === 'E4002'
      ? ' — 이 이메일은 금융망 전역에서 이미 쓰이고 있다'
      : '';
    throw new Error(`userKey 확보 실패 ${made?.responseCode ?? '?'}${hint}`);
  }

  /** DD_02(조회) → 없으면 DD_01(등록). 싸피은행 수시입출금 상품은 팀 전체에 하나면 된다. */
  async ensureProduct() {
    const list = await this.call('demandDeposit/inquireDemandDepositList',
      'inquireDemandDepositList', null, {});
    const found = (list ?? []).find((p) => p.bankCode === SSAFY_BANK);
    if (found) return found;

    const made = await this.call('demandDeposit/createDemandDeposit', 'createDemandDeposit', null, {
      bankCode: SSAFY_BANK,
      accountName: '잔고예보 개발용 수시입출금',
      accountDescription: '개발·시연용. 실제 은행망과 무관하다.',
    });
    return Array.isArray(made) ? made[0] : made;
  }

  /** DD_04 — 이 userKey 가 가진 계좌 목록. 재실행에서 만들지 말지 정하는 근거다. */
  async accounts(userKey) {
    return (await this.call('demandDeposit/inquireDemandDepositAccountList',
      'inquireDemandDepositAccountList', userKey, {})) ?? [];
  }

  /** DD_03 — 계좌 생성. 만들어진 계좌는 이 userKey 소유가 된다. */
  async createAccount(userKey, accountTypeUniqueNo) {
    const rec = await this.call('demandDeposit/createDemandDepositAccount',
      'createDemandDepositAccount', userKey, { accountTypeUniqueNo });
    return (Array.isArray(rec) ? rec[0] : rec).accountNo;
  }

  /** DD_07 — 계좌 잔액. 금융망은 숫자도 문자열로 준다. */
  async balance(userKey, accountNo) {
    const rec = await this.call(
      'demandDeposit/inquireDemandDepositAccountBalance',
      'inquireDemandDepositAccountBalance', userKey, { accountNo });
    return Number(rec.accountBalance);
  }

  /** DD_09 — 계좌 입금. <b>날짜 파라미터가 없다</b> — 무조건 오늘로 찍힌다. */
  async deposit(userKey, accountNo, amount, summary) {
    return this.call(
      'demandDeposit/updateDemandDepositAccountDeposit',
      'updateDemandDepositAccountDeposit', userKey,
      { accountNo, transactionBalance: String(amount), transactionSummary: summary });
  }

  /**
   * DD_08 — 계좌 출금. DD_09 와 요청 모양이 같다.
   *
   * <p>이게 있어서 <b>출금에 상대 계좌가 필요 없다.</b> 처음에는 DD_10 이체로 만들려고
   * baemin·kg 계정에 계좌를 파야 하는 줄 알았는데, 워크북을 다시 보니 직접 출금 API 가 있었다.
   */
  async withdraw(userKey, accountNo, amount, summary) {
    return this.call(
      'demandDeposit/updateDemandDepositAccountWithdrawal',
      'updateDemandDepositAccountWithdrawal', userKey,
      { accountNo, transactionBalance: String(amount), transactionSummary: summary });
  }

  /**
   * DD_14 — 계좌 해지. <b>잔액이 0 이면 금액반환계좌를 적지 않아도 된다</b>(워크북 DD_14 설명).
   *
   * <p>돌려줄 계좌가 없으니 부르는 쪽이 DD_08 로 먼저 0 원을 만든다. 예·적금·카드의
   * 연결계좌이거나 대출의 출금계좌인 계좌는 해지되지 않는다 — A1068·A1069·A1070 이고,
   * 이건 우리 쪽에서 풀 수 있는 조건이 아니다.
   */
  async closeAccount(userKey, accountNo, refundAccountNo = null) {
    return this.call(
      'demandDeposit/deleteDemandDepositAccount',
      'deleteDemandDepositAccount', userKey,
      { accountNo, ...(refundAccountNo ? { refundAccountNo } : {}) });
  }

  /** DD_12 — 오늘 거래내역. 1원 인증 적요에서 코드를 읽으려고 쓴다. */
  async todayTransactions(userKey, accountNo) {
    const { date } = kstStamp();
    const rec = await this.call(
      'demandDeposit/inquireTransactionHistoryList',
      'inquireTransactionHistoryList', userKey,
      { accountNo, startDate: date, endDate: date, transactionType: 'A', orderByType: 'DESC' });
    return rec?.list ?? [];
  }
}

/**
 * 통장 적요에서 1원 인증 코드를 읽는다. 실사용에서는 사장님이 자기 통장에서 읽는 값이고,
 * 개발에서는 그 "통장"이 이 API 다.
 */
export async function readAuthCode(fin, userKey, accountNo, authText) {
  for (const tx of await fin.todayTransactions(userKey, accountNo)) {
    const summary = tx.transactionSummary ?? '';
    if (!summary.includes(authText)) continue;
    const found = summary.match(/(\d{4})/);
    if (found) return found[1];
  }
  return null;
}
