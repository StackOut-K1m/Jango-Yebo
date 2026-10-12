#!/usr/bin/env node
// 개발용 가짜 계좌 개설 — 싸피은행(999) 수시입출금 계좌를 금융망에 만든다.
//
//   node scripts/fin-account.mjs <이메일> [초기잔액]
//   예: node scripts/fin-account.mjs dev1@jango.test 3000000
//
// 왜 필요한가 — 우리 백엔드의 계좌 연결(POST /businesses/{id}/accounts)은 **이미 있는**
// 계좌번호를 받는다. 개설 기능이 없어서 개발자마다 실계좌나 시드 계좌를 돌려써야 했다.
// 싸피은행은 실제 은행망과 무관한 가상 은행이라 여기에 쓰라고 있는 것이다.
//
// 하는 일:
//   1) MEMBER_02(조회) → 없으면 MEMBER_01(생성)   이메일로 userKey 확보
//   2) DD_02(상품 조회) → 없으면 DD_01(상품 등록)  싸피은행 수시입출금 상품
//   3) DD_03(계좌 생성)                            userKey 소유의 새 계좌
//   4) DD_09(입금) — 초기잔액을 주면 한 번 넣는다
//
// ⚠ 개발 전용이다. 배포 경로에 넣지 않는다.
// ⚠ 이메일은 금융망 전역에서 유니크하다. 다른 팀이 쓴 주소는 못 만든다(E4002).
// 시크릿은 인자로 받지 않는다. backend/.env 에서 읽고 화면에 찍지 않는다.

import { readFileSync } from 'node:fs';
import { dirname, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

const EMAIL = process.argv[2];
const OPENING_BALANCE = Number(process.argv[3] ?? 0);
const SSAFY_BANK = '999';

if (!EMAIL) {
  console.error('사용법: node scripts/fin-account.mjs <이메일> [초기잔액]');
  process.exit(1);
}

const REPO = process.env.JANGO_REPO ?? resolve(dirname(fileURLToPath(import.meta.url)), '..');

function readEnv(file) {
  try {
    return Object.fromEntries(
      readFileSync(resolve(REPO, 'backend', file), 'utf8')
        .split('\n')
        .map((line) => line.trim())
        .filter((line) => line && !line.startsWith('#') && line.includes('='))
        .map((line) => {
          const at = line.indexOf('=');
          return [line.slice(0, at).trim(), line.slice(at + 1).trim()];
        }),
    );
  } catch {
    console.error(`backend/${file} 을 읽을 수 없습니다. JANGO_REPO 로 저장소 경로를 지정하세요.`);
    process.exit(1);
  }
}

const env = readEnv('.env');
const apiKey = env.FINOPENAPI_API_KEY;
const base = env.FINOPENAPI_BASE_URL;
if (!apiKey || apiKey.startsWith('__')) {
  console.error('FINOPENAPI_API_KEY 가 비어 있습니다. 서버 ~/app/secrets/finopenapi.env 에서 받으세요.');
  process.exit(1);
}

const p = (n, len = 2) => String(n).padStart(len, '0');

/** ⚠ 한국시간이어야 한다. 금융망 서버 시각 ±5분을 벗어나면 전부 H1003 이다. */
function stamp() {
  const kst = new Date(Date.now() + 9 * 3600 * 1000);
  const date = `${kst.getUTCFullYear()}${p(kst.getUTCMonth() + 1)}${p(kst.getUTCDate())}`;
  const time = `${p(kst.getUTCHours())}${p(kst.getUTCMinutes())}${p(kst.getUTCSeconds())}`;
  return { date, time };
}

/**
 * 업무 API 호출. userKey 를 주면 헤더에 넣는다 — 상품 조회·등록은 userKey 를 받지 않는다.
 *
 * 오류는 성공과 자리가 다르다: 성공은 Header.responseCode, 실패는 최상위 responseCode 다.
 */
async function call(domain, apiName, payload = {}, userKey) {
  const { date, time } = stamp();
  const Header = {
    apiName,
    transmissionDate: date,
    transmissionTime: time,
    institutionCode: env.FINOPENAPI_INSTITUTION_CODE ?? '00100',
    fintechAppNo: env.FINOPENAPI_FINTECH_APP_NO ?? '001',
    apiServiceCode: apiName,
    // ⚠ 정확히 20자여야 한다. 짧으면 H1010 이고 0 패딩이 빠지면 간헐적으로만 실패한다.
    institutionTransactionUniqueNo: date + time + p(Math.floor(Math.random() * 1e6), 6),
    apiKey,
    ...(userKey ? { userKey } : {}),
  };

  const res = await fetch(`${base}/ssafy/api/v1/edu/${domain}/${apiName}`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ Header, ...payload }),
  });
  const json = await res.json();
  const code = json?.Header?.responseCode ?? json?.responseCode;
  return { ok: code === 'H0000', code, json, status: res.status };
}

function die(what, r) {
  console.error(`${what} 실패 — ${r.code} ${r.json?.responseMessage ?? r.json?.Header?.responseMessage ?? ''}`);
  if (r.code === 'H1003') console.error('  시계가 금융망 기준 ±5분을 벗어났다.');
  if (r.code === 'H1008') console.error('  apiKey 가 유효하지 않다.');
  process.exit(1);
}

// 1) userKey — 있으면 조회, 없으면 만든다. MEMBER 계열은 조회도 201 을 준다.
async function memberKey(email) {
  const url = `${base}/ssafy/api/v1/member`;
  const post = async (path, body) => {
    const res = await fetch(url + path, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(body),
    });
    return { status: res.status, json: await res.json() };
  };

  const found = await post('/search', { apiKey, userId: email });
  if (found.json?.userKey) return { userKey: found.json.userKey, created: false };

  const made = await post('/', { apiKey, userId: email });
  if (made.json?.userKey) return { userKey: made.json.userKey, created: true };

  console.error(`userKey 확보 실패 — ${made.json?.responseCode ?? made.status} ${made.json?.responseMessage ?? ''}`);
  if (made.json?.responseCode === 'E4002') {
    console.error('  이 이메일은 금융망 전역에서 이미 쓰이고 있다. 다른 주소를 쓰거나 조회로 복구한다.');
  }
  process.exit(1);
}

const { userKey, created } = await memberKey(EMAIL);
console.log(`1) userKey ${created ? '발급' : '조회'} 완료 — ${EMAIL}`);

// 2) 싸피은행 수시입출금 상품. 팀 전체가 하나만 있으면 된다.
let product;
const list = await call('demandDeposit', 'inquireDemandDepositList');
if (!list.ok) die('상품 조회', list);
product = (list.json.REC ?? []).find((x) => x.bankCode === SSAFY_BANK);

if (!product) {
  const made = await call('demandDeposit', 'createDemandDeposit', {
    bankCode: SSAFY_BANK,
    accountName: '잔고예보 개발용 수시입출금',
    accountDescription: '개발·시연용. 실제 은행망과 무관하다.',
  });
  if (!made.ok) die('상품 등록', made);
  product = Array.isArray(made.json.REC) ? made.json.REC[0] : made.json.REC;
  console.log('2) 싸피은행 상품 신규 등록');
} else {
  console.log(`2) 싸피은행 상품 재사용 — ${product.accountName}`);
}

// 3) 계좌 생성. 생성된 계좌는 이 userKey 의 소유가 된다.
const acc = await call('demandDeposit', 'createDemandDepositAccount', {
  accountTypeUniqueNo: product.accountTypeUniqueNo,
}, userKey);
if (!acc.ok) die('계좌 생성', acc);
const rec = Array.isArray(acc.json.REC) ? acc.json.REC[0] : acc.json.REC;
const accountNo = rec.accountNo;
console.log(`3) 계좌 생성 — ${accountNo}`);

// 4) 초기 잔액. 날짜를 정할 수 없어 전부 오늘 거래로 남는다 — 과거 이력은 CSV 업로드가 담당한다.
if (OPENING_BALANCE > 0) {
  const dep = await call('demandDeposit', 'updateDemandDepositAccountDeposit', {
    accountNo,
    // ⚠ 명세 표에는 Long 이지만 예제는 문자열이다. 숫자로 보내면 Q1001 이다(실측).
    transactionBalance: String(OPENING_BALANCE),
    transactionSummary: '개발용 초기 잔액',
  }, userKey);
  if (!dep.ok) die('초기 입금', dep);
  console.log(`4) 초기 잔액 ${OPENING_BALANCE.toLocaleString('ko-KR')}원 입금`);
}

console.log(`
다 됐습니다. 화면에서 이렇게 연결하세요.

  은행      싸피은행
  계좌번호  ${accountNo}

이 계좌는 ${EMAIL} 소유입니다. 같은 이메일로 우리 앱에 가입해야 연결됩니다 —
백엔드가 사용자 이메일로 금융망 userKey 를 찾기 때문입니다.`);
