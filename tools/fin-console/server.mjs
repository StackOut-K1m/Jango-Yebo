#!/usr/bin/env node
// 금융망 콘솔 — 로컬 전용 정적 서버 + 금융망 프록시. 의존성 0개.
//
//   node tools/fin-console/server.mjs          # http://127.0.0.1:5180
//   PORT=6000 node tools/fin-console/server.mjs
//
// 왜 프록시가 필요한가 — 금융망은 브라우저에서 직접 못 부른다. Origin 을 붙이면
// 403 "Invalid CORS request" 다(실측). 그래서 이 프로세스가 대신 부른다.
//
// 그 덕에 apiKey 가 브라우저로 안 나간다. 키는 backend/.env 에서 읽어 이 프로세스에만
// 두고, 화면에는 끝 4자리만 보여준다. userKey 도 시드 키는 이름(SEED_USERKEY_OWNER)만
// 넘기고 값은 서버에서 채운다.
//
// ⚠ 요청 본문을 로그에 남기지 않는다. Header 에 apiKey 와 userKey 가 들어 있고
//   userKey 는 사실상 계좌 접근권이다.
// ⚠ 127.0.0.1 에만 바인딩한다. 0.0.0.0 으로 열면 같은 망의 누구나 팀 키로 계좌를 만진다.
// ⚠ catalog.mjs 에 있는 인터페이스만 부른다. MANAGER_02(reIssuedApiKey)는 목록에 없고,
//   추가하면 기동에서 막힌다 — 부르는 순간 팀 키가 죽고 재발급이 안 된다.

import { createServer } from 'node:http';
import { readFile } from 'node:fs/promises';
import { readFileSync } from 'node:fs';
import { dirname, extname, join, resolve, sep } from 'node:path';
import { fileURLToPath } from 'node:url';

import { BY_ID, FORBIDDEN } from './public/catalog.mjs';

const HERE = dirname(fileURLToPath(import.meta.url));
const PUBLIC = join(HERE, 'public');
const REPO = process.env.JANGO_REPO ?? resolve(HERE, '..', '..');
const PORT = Number(process.env.PORT ?? 5180);

// ── 설정 읽기 ────────────────────────────────────────────────────────────

/** backend/.env 형식의 단순 파서. 팀 스크립트와 같은 규칙이다(따옴표·다중행 없음). */
function readEnvFile(file) {
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
    return null;
  }
}

const envFile = readEnvFile('.env');
const seedFile = readEnvFile('.env.seed.local') ?? {};

/** process.env 가 먼저다 — 서버·CI 에서 파일 없이 돌릴 수 있게. */
const cfg = {
  baseUrl:
    process.env.FINOPENAPI_BASE_URL ??
    envFile?.FINOPENAPI_BASE_URL ??
    'https://finopenapi.ssafy.io',
  apiKey: process.env.FINOPENAPI_API_KEY ?? envFile?.FINOPENAPI_API_KEY ?? '',
  institutionCode:
    process.env.FINOPENAPI_INSTITUTION_CODE ?? envFile?.FINOPENAPI_INSTITUTION_CODE ?? '00100',
  fintechAppNo:
    process.env.FINOPENAPI_FINTECH_APP_NO ?? envFile?.FINOPENAPI_FINTECH_APP_NO ?? '001',
};

const keyReady = Boolean(cfg.apiKey) && !cfg.apiKey.startsWith('__');

/** 시드 userKey 는 이름만 브라우저로 보낸다. 값은 여기서 채운다. */
const seedRefs = Object.keys(seedFile).filter((name) => name.startsWith('SEED_USERKEY_'));

// ── 금융망 호출 ──────────────────────────────────────────────────────────

const pad = (n, len = 2) => String(n).padStart(len, '0');

/** ⚠ 한국시간이어야 한다. 금융망 서버 시각 ±5분을 벗어나면 전부 H1003 이다. */
function kstStamp() {
  const kst = new Date(Date.now() + 9 * 3600 * 1000);
  return {
    date: `${kst.getUTCFullYear()}${pad(kst.getUTCMonth() + 1)}${pad(kst.getUTCDate())}`,
    time: `${pad(kst.getUTCHours())}${pad(kst.getUTCMinutes())}${pad(kst.getUTCSeconds())}`,
  };
}

// 난수 대신 증가 카운터를 쓴다. 같은 초에 연달아 누르면 난수는 H1007(중복)이 난다.
let seq = Math.floor(Math.random() * 1e6);

function header(apiName, userKey) {
  const { date, time } = kstStamp();
  seq = (seq + 1) % 1e6;
  return {
    apiName,
    transmissionDate: date,
    transmissionTime: time,
    institutionCode: cfg.institutionCode,
    fintechAppNo: cfg.fintechAppNo,
    apiServiceCode: apiName,
    // ⚠ 정확히 20자. 0 패딩을 빼면 간헐적으로만 실패해서 원인을 찾기 어렵다.
    institutionTransactionUniqueNo: date + time + pad(seq, 6),
    apiKey: cfg.apiKey,
    ...(userKey ? { userKey } : {}),
  };
}

/** 화면에 돌려줄 요청 사본. apiKey 는 지운다. */
function maskRequest(body) {
  const copy = structuredClone(body);
  if (copy.Header?.apiKey) copy.Header.apiKey = '••••••••';
  if (copy.apiKey) copy.apiKey = '••••••••';
  return copy;
}

async function callFin(entry, payload, userKey) {
  const isMember = entry.kind === 'member';
  const url = isMember
    ? `${cfg.baseUrl}/ssafy/api/v1/member${entry.path}`
    : // MEMO_01 처럼 domain 이 없는 것도 있다 — 빈 구간을 남기지 않는다.
      `${cfg.baseUrl}/ssafy/api/v1/edu/${[entry.domain, entry.apiName].filter(Boolean).join('/')}`;

  // MEMBER 계열은 Header 를 쓰지 않고 apiKey 가 본문 최상위에 온다(공통규약 23장).
  const body = isMember
    ? { apiKey: cfg.apiKey, ...payload }
    : { Header: header(entry.apiName, userKey), ...payload };

  let res;
  let json;
  try {
    res = await fetch(url, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(body),
      signal: AbortSignal.timeout(20_000),
    });
    json = await res.json().catch(() => ({}));
  } catch (err) {
    return {
      ok: false,
      code: 'NETWORK',
      message: `금융망에 닿지 못했다 — ${err.name === 'TimeoutError' ? '20초 응답 없음' : err.message}`,
      request: maskRequest(body),
      response: null,
      status: 0,
    };
  }

  // MEMBER 조회·생성은 Header 없이 최상위로 응답한다. 성공 판정이 계열마다 다르다.
  const code = json?.Header?.responseCode ?? json?.responseCode ?? null;
  const ok = isMember ? Boolean(json?.userKey) : code === 'H0000';

  return {
    ok,
    code: ok ? (code ?? 'OK') : (code ?? String(res.status)),
    message: json?.responseMessage ?? json?.Header?.responseMessage ?? '',
    request: maskRequest(body),
    response: json,
    status: res.status,
  };
}

// ── HTTP ────────────────────────────────────────────────────────────────

const MIME = {
  '.html': 'text/html; charset=utf-8',
  '.css': 'text/css; charset=utf-8',
  '.js': 'text/javascript; charset=utf-8',
  '.mjs': 'text/javascript; charset=utf-8',
  '.svg': 'image/svg+xml',
  '.ico': 'image/x-icon',
};

function send(res, status, payload) {
  const text = JSON.stringify(payload);
  res.writeHead(status, {
    'Content-Type': 'application/json; charset=utf-8',
    'Cache-Control': 'no-store',
  });
  res.end(text);
}

async function readBody(req) {
  const chunks = [];
  let size = 0;
  for await (const chunk of req) {
    size += chunk.length;
    if (size > 1_000_000) throw new Error('요청이 너무 크다');
    chunks.push(chunk);
  }
  return JSON.parse(Buffer.concat(chunks).toString('utf8') || '{}');
}

async function serveStatic(req, res) {
  const path = new URL(req.url, 'http://localhost').pathname;
  const rel = path === '/' ? 'index.html' : decodeURIComponent(path).replace(/^\/+/, '');
  const file = resolve(PUBLIC, rel);

  // 디렉터리 탈출 차단. public 밖은 어떤 경로로도 못 읽는다.
  if (file !== PUBLIC && !file.startsWith(PUBLIC + sep)) {
    res.writeHead(403).end('forbidden');
    return;
  }

  try {
    const data = await readFile(file);
    res.writeHead(200, {
      'Content-Type': MIME[extname(file)] ?? 'application/octet-stream',
      'Cache-Control': 'no-store',
    });
    res.end(data);
  } catch {
    res.writeHead(404, { 'Content-Type': 'text/plain; charset=utf-8' });
    res.end('없는 파일이다');
  }
}

const server = createServer(async (req, res) => {
  const { pathname } = new URL(req.url, 'http://localhost');

  // 기동 정보. 키 값은 주지 않고 끝 4자리만 준다 — 어느 키로 붙었는지 확인용이다.
  if (pathname === '/api/boot') {
    send(res, 200, {
      ready: keyReady,
      baseUrl: cfg.baseUrl,
      institutionCode: cfg.institutionCode,
      fintechAppNo: cfg.fintechAppNo,
      keyTail: keyReady ? cfg.apiKey.slice(-4) : null,
      envFound: Boolean(envFile),
      seedRefs,
    });
    return;
  }

  if (pathname === '/api/call') {
    if (req.method !== 'POST') {
      send(res, 405, { ok: false, code: 'METHOD', message: 'POST 만 받는다' });
      return;
    }
    if (!keyReady) {
      send(res, 503, {
        ok: false,
        code: 'NO_API_KEY',
        message: 'FINOPENAPI_API_KEY 가 비어 있다. backend/.env 를 확인한다.',
      });
      return;
    }

    let input;
    try {
      input = await readBody(req);
    } catch (err) {
      send(res, 400, { ok: false, code: 'BAD_BODY', message: err.message });
      return;
    }

    const entry = BY_ID[input.id];
    if (!entry) {
      send(res, 400, { ok: false, code: 'UNKNOWN_API', message: `${input.id} 는 목록에 없다` });
      return;
    }
    if (FORBIDDEN.includes(entry.apiName)) {
      send(res, 403, {
        ok: false,
        code: 'FORBIDDEN',
        message: `${entry.apiName} 은 호출하지 않는다`,
      });
      return;
    }

    // userKeyRef 는 .env.seed.local 의 키 이름이다. 값은 브라우저를 거치지 않는다.
    let userKey = input.userKey ?? null;
    if (input.userKeyRef) {
      if (!seedRefs.includes(input.userKeyRef)) {
        send(res, 400, { ok: false, code: 'UNKNOWN_REF', message: `${input.userKeyRef} 가 없다` });
        return;
      }
      userKey = seedFile[input.userKeyRef];
    }
    if (entry.userKey === 'required' && !userKey) {
      send(res, 400, { ok: false, code: 'NEED_USER_KEY', message: 'userKey 가 필요하다' });
      return;
    }

    const payload = typeof input.payload === 'object' && input.payload ? input.payload : {};
    // 로그는 어떤 인터페이스를 불렀는지만 남긴다. 본문은 절대 찍지 않는다.
    const result = await callFin(entry, payload, entry.userKey === 'none' ? null : userKey);
    console.log(`  ${result.ok ? '✓' : '✗'} ${entry.id} ${entry.apiName} — ${result.code}`);
    send(res, 200, result);
    return;
  }

  await serveStatic(req, res);
});

server.listen(PORT, '127.0.0.1', () => {
  console.log(`
  잔고예보 금융망 콘솔

  http://127.0.0.1:${PORT}

  금융망   ${cfg.baseUrl}
  apiKey   ${keyReady ? `backend/.env 에서 읽음 (…${cfg.apiKey.slice(-4)})` : '없음 — backend/.env 의 FINOPENAPI_API_KEY 를 채운다'}
  시드 키  ${seedRefs.length ? seedRefs.join(', ') : '없음 (backend/.env.seed.local)'}

  개발 전용이다. 배포 경로에 넣지 않는다.
`);
});
