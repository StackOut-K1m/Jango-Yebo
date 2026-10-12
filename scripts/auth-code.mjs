#!/usr/bin/env node
// 1원 인증 코드 확인 — 금융망 DD_12(inquireTransactionHistoryList) 로 통장 적요를 읽는다.
//
//   node auth-code.mjs [계좌번호] [이메일]
//   기본 계좌: 0000000000000000 (owner 시드 계정의 수시입출금)
//
// 이메일을 주면 그 사용자의 userKey 로 조회한다 — fin-account.mjs 로 만든 개발용 계좌를
// 볼 때 쓴다. 생략하면 시드 계정(SEED_USERKEY_OWNER)이다. 남의 계좌를 보면 A1003 이다.
//
// 시크릿은 인자로 받지 않는다. backend/.env 와 backend/.env.seed.local 에서 읽는다.
// 두 파일 다 git 제외이며 값을 화면에 찍지 않는다.
//
// 왜 스크립트가 필요한가 — 실사용에서는 사용자가 자기 통장에서 코드를 읽는다.
// 개발에서 그 "통장"이 이 API 이고, 팀 apiKey 와 사용자 userKey 가 둘 다 필요하다.

import { readFileSync } from 'node:fs';
import { dirname, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

const ACCOUNT_NO = process.argv[2] ?? '0000000000000000';
const EMAIL = process.argv[3];

// 이 파일이 저장소 안에 있다면 backend/ 를 찾아 올라간다. 밖에 있으면 REPO 를 직접 지정한다.
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
const seed = readEnv('.env.seed.local');

const apiKey = env.FINOPENAPI_API_KEY;
if (!apiKey || apiKey.startsWith('__')) {
  console.error('FINOPENAPI_API_KEY 가 비어 있습니다. 서버 ~/app/secrets/finopenapi.env 에서 받으세요.');
  process.exit(1);
}
/** 이메일이 있으면 금융망에서 그 사용자의 userKey 를 찾는다. 조회도 201 을 준다. */
async function keyFor(email) {
  const res = await fetch(`${env.FINOPENAPI_BASE_URL}/ssafy/api/v1/member/search`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ apiKey, userId: email }),
  });
  const json = await res.json();
  if (!json?.userKey) {
    console.error(`${email} 의 userKey 를 찾을 수 없습니다 — ${json?.responseCode ?? res.status}`);
    process.exit(1);
  }
  return json.userKey;
}

const userKey = EMAIL ? await keyFor(EMAIL) : seed.SEED_USERKEY_OWNER;
if (!userKey) {
  console.error('SEED_USERKEY_OWNER 가 없습니다. backend/.env.seed.local 이 필요하거나 이메일을 인자로 주세요.');
  process.exit(1);
}

// ⚠ 한국시간이어야 한다. 금융망 서버 시각 ±5분을 벗어나면 전부 H1003 이다.
const kst = new Date(Date.now() + 9 * 3600 * 1000);
const p = (n, len = 2) => String(n).padStart(len, '0');
const date = `${kst.getUTCFullYear()}${p(kst.getUTCMonth() + 1)}${p(kst.getUTCDate())}`;
const time = `${p(kst.getUTCHours())}${p(kst.getUTCMinutes())}${p(kst.getUTCSeconds())}`;

const apiName = 'inquireTransactionHistoryList';
const body = {
  Header: {
    apiName,
    transmissionDate: date,
    transmissionTime: time,
    institutionCode: env.FINOPENAPI_INSTITUTION_CODE ?? '00100',
    fintechAppNo: env.FINOPENAPI_FINTECH_APP_NO ?? '001',
    apiServiceCode: apiName,
    // ⚠ 정확히 20자여야 한다. 짧으면 H1010 이고 0 패딩이 빠지면 간헐적으로만 실패한다.
    institutionTransactionUniqueNo: date + time + p(Math.floor(Math.random() * 1e6), 6),
    apiKey,
    userKey,
  },
  accountNo: ACCOUNT_NO,
  startDate: date,
  endDate: date,
  transactionType: 'A',      // M 입금 / D 출금 / A 전체
  orderByType: 'DESC',       // 거래고유번호 기준. 최신이 앞으로 온다
};

const res = await fetch(`${env.FINOPENAPI_BASE_URL}/ssafy/api/v1/edu/${'demandDeposit'}/${apiName}`, {
  method: 'POST',
  headers: { 'Content-Type': 'application/json' },
  body: JSON.stringify(body),
});
const json = await res.json();

const code = json?.Header?.responseCode ?? json?.responseCode;
if (code !== 'H0000') {
  console.error(`실패 ${res.status} ${code} — ${json?.responseMessage ?? json?.Header?.responseMessage ?? ''}`);
  if (code === 'H1003') console.error('  서버 시계가 금융망 기준 ±5분을 벗어났다.');
  if (code === 'H1009') console.error('  userKey 가 이 계좌의 소유자와 맞지 않는다.');
  process.exit(1);
}

const rows = json.REC?.list ?? [];
if (rows.length === 0) {
  console.log('거래가 없습니다. 계좌 연결(1원 송금)이 아직 안 됐을 수 있습니다.');
  process.exit(0);
}

console.log(`계좌 ${ACCOUNT_NO} — 오늘 거래 ${json.REC.totalCount}건 (최신순)\n`);
for (const [i, row] of rows.entries()) {
  const summary = row.transactionSummary ?? '(적요 없음)';
  const digits = summary.match(/(\d{4})\s*$/);
  const mark = i === 0 && digits ? '  ← 최신 인증코드' : '';
  console.log(`  ${row.transactionTime}  ${row.transactionBalance}원  ${summary}${mark}`);
}

const latest = rows[0].transactionSummary?.match(/(\d{4})\s*$/);
if (latest) console.log(`\n인증코드: ${latest[1]}`);
