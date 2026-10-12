#!/usr/bin/env node
// 시드 콘솔 — 로컬 전용 정적 서버 + 시드 생성·투입. 의존성 0개.
//
//   npm run seed                               # http://127.0.0.1:5181
//   PORT=6001 node tools/seed-console/server.mjs
//
// scripts/seed.mjs 를 인자 바꿔 가며 매번 다시 돌리던 일을 화면에서 한다. 곡선은
// scripts/seed/plan.mjs 가 만들고 투입은 scripts/seed/pipeline.mjs 가 한다 —
// <b>CLI 와 같은 코드다.</b> 화면과 명령어가 서로 다른 시드를 만들면 안 된다.
//
// 화면에서 맞춘 설정은 「설정 내보내기」로 받아 그대로 CLI 에 넘길 수 있다:
//   node scripts/seed.mjs --tuning seed-out/설정.json
//
// **개발 전용이다.** 배포 경로에 넣지 않고 프론트엔드와도 분리해 둔다.
//
// ⚠ 127.0.0.1 에만 바인딩한다. 0.0.0.0 으로 열면 같은 망의 누구나 팀 키로 계좌를 만진다.
// ⚠ 요청 본문을 로그에 남기지 않는다. 비밀번호와 토큰이 들어 있다.
// ⚠ schema.mjs 에 있는 경로만 설정으로 받는다. 나머지는 버린다 — 화면이 보낸 것을 통째로
//   믿고 생성기에 넘기면 오타 하나가 6개월치 거래를 조용히 망가뜨린 채로 업로드된다.

import { createServer } from 'node:http';
import { readFile } from 'node:fs/promises';
import { readFileSync } from 'node:fs';
import { dirname, extname, join, resolve, sep } from 'node:path';
import { fileURLToPath } from 'node:url';

import {
  DEFAULT_ACCOUNTS, DEFAULT_OWNER, DEFAULT_TUNING, addDays, buildPrimary, dailyClosing,
  holidayRules, isBusinessDay, parse, productSalesPeriods, resolveTuning, solveOpening, toCsv,
  toLabelCsv, ymd,
} from '../../scripts/seed/plan.mjs';
import { SeedPipeline } from '../../scripts/seed/pipeline.mjs';
import {
  ACCOUNT_COLUMNS, STEPS, TABLES, coerce, expandSteps, getAt, scalarFields, setAt,
} from './public/schema.mjs';

const HERE = dirname(fileURLToPath(import.meta.url));
const PUBLIC = join(HERE, 'public');
const REPO = process.env.JANGO_REPO ?? resolve(HERE, '..', '..');
const PORT = Number(process.env.PORT ?? 5181);

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
const apiKey = process.env.FINOPENAPI_API_KEY ?? envFile?.FINOPENAPI_API_KEY ?? '';
const keyReady = Boolean(apiKey) && !apiKey.startsWith('__');

const DEFAULT_API_BASE = process.env.SEED_API_BASE ?? 'http://localhost:8080';
const AUTH_TEXT = process.env.ACCOUNT_AUTH_TEXT ?? '잔고예보';

// ── 들어온 설정 정리 ─────────────────────────────────────────────────────

/**
 * 화면이 보낸 설정에서 <b>스키마가 아는 칸만</b> 골라 타입대로 강제 변환한다.
 * 모르는 경로와 못 읽는 값은 버려지고, 그 자리는 {@code resolveTuning} 이 기본값으로 채운다.
 */
function sanitizeTuning(raw) {
  const out = {};
  if (typeof raw !== 'object' || raw === null) return out;

  for (const spec of scalarFields()) {
    const value = coerce(spec, getAt(raw, spec.path));
    if (value !== undefined) setAt(out, spec.path, value);
  }

  for (const table of TABLES) {
    const incoming = raw[table.base];
    if (typeof incoming !== 'object' || incoming === null) continue;

    // 수입은 줄이 고정이다 — deposits() 가 card·platform·cash 를 이름으로 읽고,
    // 난수 스트림도 그 이름에 매여 있다. 표에 없는 줄은 만들지 않는다.
    const allowed = table.rows
      ? table.rows.map((r) => r.key)
      : Object.keys(incoming).filter((k) => /^[A-Za-z][A-Za-z0-9_]{0,30}$/.test(k));

    for (const key of allowed) {
      const row = incoming[key];
      if (typeof row !== 'object' || row === null) continue;

      const base = DEFAULT_TUNING[table.base][key];
      const clean = {};
      for (const column of table.columns) {
        const value = coerce(column, row[column.field]);
        if (value !== undefined) clean[column.field] = value;
      }
      // 화면에서 새로 만든 줄은 기본값이 없다. 빠진 칸을 여기서 채워 둔다 —
      // 안 채우면 생성기가 undefined 를 날짜로 읽고 그 항목만 조용히 빠진다.
      // 표마다 칸이 다르므로 스키마의 newRow 를 먼저 본다(상품 줄에 날짜·분류는 없다).
      const filled = base
        ? clean
        : {
          label: coerce({ type: 'text', max: 20 }, row.label) ?? key,
          ...(table.newRow ?? {
            description: key,
            amount: 0,
            day: 1,
            dow: 1,
            shift: 1,
            time: '06:00:00',
            category: 'OTHER_EXPENSE',
          }),
          ...clean,
        };
      if (Object.keys(filled).length) setAt(out, `${table.base}.${key}`, filled);
    }

    // 화면이 보낸 표에 한 줄도 못 건졌으면 <b>전부 지운 것</b>이다. 빈 객체를 명시해야
    // resolveTuning 이 표째 갈아 끼운다 — 안 그러면 기본 고정비가 통째로 되살아난다.
    if (table.addable && !out[table.base]) out[table.base] = {};
  }
  return out;
}

function sanitizeOwner(raw) {
  const text = (v, max) => coerce({ type: 'text', max }, v);
  return {
    email: text(raw?.email, 100) ?? DEFAULT_OWNER.email,
    password: text(raw?.password, 60) ?? DEFAULT_OWNER.password,
    name: text(raw?.name, 20) ?? DEFAULT_OWNER.name,
    phone: text(raw?.phone, 20) ?? DEFAULT_OWNER.phone,
  };
}

function sanitizeAccounts(raw) {
  if (!Array.isArray(raw) || !raw.length) return DEFAULT_ACCOUNTS;
  const out = raw.slice(0, 5).map((row, index) => {
    const clean = {};
    for (const column of ACCOUNT_COLUMNS) {
      const value = coerce(column, row?.[column.field]);
      clean[column.field] = value === undefined ? (column.type === 'bool' ? false : `계좌 ${index + 1}`) : value;
    }
    return clean;
  });
  // 거래를 받을 계좌가 없으면 업로드·잔액 맞추기가 갈 곳이 없다. 첫 줄을 투입 계좌로 삼는다.
  if (!out.some((a) => a.main)) out[0].main = true;
  return out;
}

const sanitizeDate = (raw, fallback) => coerce({ type: 'date' }, raw) ?? fallback;

/** 백엔드 주소. 로컬 도구라 자유롭게 바꾸되 형태는 확인한다. */
function sanitizeApiBase(raw) {
  const value = String(raw ?? '').trim() || DEFAULT_API_BASE;
  let url;
  try {
    url = new URL(value);
  } catch {
    throw new Error(`백엔드 주소를 읽을 수 없다: ${value}`);
  }
  if (url.protocol !== 'http:' && url.protocol !== 'https:') {
    throw new Error('백엔드 주소는 http 또는 https 여야 한다');
  }
  return url.origin;
}

// ── 곡선 계산 ────────────────────────────────────────────────────────────

/**
 * 파라미터 한 벌로 곡선을 만들어 <b>판정까지</b> 붙여 돌려준다.
 * 네트워크를 건드리지 않으므로 화면이 값을 고칠 때마다 불러도 된다.
 */
function preview({ asOf: asOfStr, tuning: raw }) {
  const t = resolveTuning(sanitizeTuning(raw));
  const asOf = parse(asOfStr);
  const from = addDays(asOf, -t.window.pastDays);
  const horizon = addDays(asOf, t.window.horizonDays);

  const solved = solveOpening({ from, asOf, to: horizon, tuning: t });
  const { rows } = buildPrimary({ from, asOf, to: horizon, opening: solved.opening, tuning: t });

  const today = ymd(asOf);
  const past = rows.filter((r) => r.date < today);
  const future = rows.filter((r) => r.date >= today);
  const closing = dailyClosing(rows);

  // 날짜별 종가. 거래가 없는 날은 직전 잔액이 이어진다 — 안 이어 주면 곡선에 구멍이 뚫린다.
  const series = [];
  let carried = solved.opening;
  for (let d = from; d <= horizon; d = addDays(d, 1)) {
    const date = ymd(d);
    if (closing.has(date)) carried = closing.get(date);
    series.push({ date, balance: carried, future: date >= today, open: isBusinessDay(d) });
  }

  // 월별 저점 — 과거가 0 을 안 뚫는지, 앵커 구간만 마이너스인지가 한눈에 보인다.
  const monthly = new Map();
  for (const { date, balance } of series) {
    const key = date.slice(0, 7);
    const seen = monthly.get(key);
    if (!seen || balance < seen.balance) monthly.set(key, { date, balance });
  }

  const windowRows = series.filter((s) => s.date >= solved.windowFrom && s.date <= solved.windowTo);
  const gapDays = windowRows.filter((s) => s.balance < 0);
  const low = windowRows.reduce((best, s) => (!best || s.balance < best.balance ? s : best), null);

  // 분류별 합계. 금액을 손봤을 때 어디가 얼마나 움직였는지 보려는 것이다.
  const totals = new Map();
  for (const r of past) {
    const key = r.category;
    const seen = totals.get(key) ?? { category: key, flow: r.flow, count: 0, amount: 0 };
    seen.count += 1;
    seen.amount += r.amount;
    totals.set(key, seen);
  }

  const funding = past.filter((r) => r.category === t.funding.category);

  // 상품별 매출은 같은 과거 거래에서 뽑는다(P1-22). 표를 고쳤을 때 무엇이 달라지는지
  // 화면에 보이지 않으면, 보이기는 하는데 고쳐도 아무 일이 안 일어나는 표가 된다.
  const productSales = productSalesPeriods({ rows: past, from, asOf, tuning: t });

  return {
    ok: true,
    asOf: today,
    from: ymd(from),
    to: ymd(horizon),
    opening: solved.opening,
    closingBalance: past.at(-1)?.balanceAfter ?? solved.opening,
    anchorLow: solved.anchorLow,
    anchorTarget: solved.anchor.lowest,
    anchorError: solved.error,
    windowFrom: solved.windowFrom,
    windowTo: solved.windowTo,
    pastCount: past.length,
    futureCount: future.length,
    funding: { count: funding.length, amount: funding.reduce((s, r) => s + r.amount, 0) },
    gap: gapDays.length
      ? { from: gapDays[0].date, to: gapDays.at(-1).date, days: gapDays.length }
      : null,
    low: low ? { date: low.date, balance: low.balance } : null,
    series,
    monthly: [...monthly.entries()].map(([month, v]) => ({ month, ...v })),
    totals: [...totals.values()].sort((a, b) => b.amount - a.amount),
    productSales: productSalesSummary(productSales),
    // 표본. 전부 보내면 응답이 커지고 화면도 못 읽는다.
    samplePast: past.slice(-40),
    sampleFuture: future.slice(0, 40),
  };
}

/**
 * 인쇄물 요약. <b>마지막 달만 펼친다</b> — 다섯 달치 65행을 다 보내면 응답이 커지고 화면도
 * 못 읽는다. 함께 보내는 {@code shift} 는 첫 달 대비 구성비 변화라, "무엇이 팔려서
 * 달라졌나"가 한 칸에 보인다(P1-22 가 답할 질문이 그것이다).
 */
function productSalesSummary(periods) {
  if (!periods.length) return { periods: 0, rows: 0, items: [] };

  const last = periods.at(-1);
  const total = last.checksum.billedAmount;
  const shareIn = (period, productName) => {
    const item = period.items.find((i) => i.productName === productName);
    return item ? item.billedAmount / period.checksum.billedAmount : null;
  };

  return {
    periods: periods.length,
    rows: periods.reduce((sum, p) => sum + p.items.length, 0),
    from: periods[0].periodStart,
    to: last.periodEnd,
    lastPeriod: {
      from: last.periodStart,
      to: last.periodEnd,
      quantity: last.checksum.quantity,
      grossAmount: last.checksum.grossAmount,
      billedAmount: total,
    },
    items: [...last.items]
      .sort((a, b) => b.billedAmount - a.billedAmount)
      .map((item) => {
        const share = item.billedAmount / total;
        return {
          productName: item.productName,
          quantity: item.quantity,
          billedAmount: item.billedAmount,
          share,
          shift: share - (shareIn(periods[0], item.productName) ?? share),
        };
      }),
  };
}

/** 업로드에 쓸 과거 구간 행. preview 와 같은 규칙으로 만든다. */
function pastRows(asOfStr, rawTuning) {
  const t = resolveTuning(sanitizeTuning(rawTuning));
  const asOf = parse(asOfStr);
  const from = addDays(asOf, -t.window.pastDays);
  const horizon = addDays(asOf, t.window.horizonDays);
  const solved = solveOpening({ from, asOf, to: horizon, tuning: t });
  const { rows } = buildPrimary({ from, asOf, to: horizon, opening: solved.opening, tuning: t });
  return { t, from, asOf, rows: rows.filter((r) => r.date < ymd(asOf)) };
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
  res.writeHead(status, {
    'Content-Type': 'application/json; charset=utf-8',
    'Cache-Control': 'no-store',
  });
  res.end(JSON.stringify(payload));
}

async function readBody(req) {
  const chunks = [];
  let size = 0;
  for await (const chunk of req) {
    size += chunk.length;
    // CSV 직접 업로드가 들어오므로 금융망 콘솔(1MB)보다 넉넉하게 잡는다.
    if (size > 8_000_000) throw new Error('요청이 너무 크다');
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

/** 실행 진행을 NDJSON 으로 흘린다. 한 줄이 한 사건이다. */
function streamer(res) {
  res.writeHead(200, {
    'Content-Type': 'application/x-ndjson; charset=utf-8',
    'Cache-Control': 'no-store',
    // 버퍼링되면 단계가 끝난 뒤에야 한꺼번에 보인다 — 진행을 보여 주려는 뜻이 사라진다.
    'X-Accel-Buffering': 'no',
  });
  return (event) => res.write(`${JSON.stringify(event)}\n`);
}

/**
 * 고른 단계를 순서대로 실행한다. <b>전부 쓰기</b>다 — 화면이 실행 전에 확인받는다.
 *
 * <p>한 단계가 실패하면 거기서 멈춘다. 계좌가 없는데 업로드를 밀어붙이면 오류가 두 번 나고
 * 원인이 뒤엣것으로 보인다.
 */
async function runPipeline(input, emit) {
  const asOf = sanitizeDate(input.asOf, ymd(new Date()));
  const owner = sanitizeOwner(input.owner);
  const accounts = sanitizeAccounts(input.accounts);
  const apiBase = sanitizeApiBase(input.apiBase);
  const steps = expandSteps(Array.isArray(input.steps) ? input.steps : []);

  if (!steps.length) {
    emit({ type: 'done', ok: false, message: '고른 단계가 없다' });
    return;
  }

  const { t, from, asOf: asOfDate, rows } = pastRows(asOf, input.tuning);
  const csv = toCsv(rows);
  const closingBalance = rows.at(-1)?.balanceAfter ?? 0;
  const productSales = productSalesPeriods({ rows, from, asOf: asOfDate, tuning: t });

  let current = null;
  const run = new SeedPipeline({
    apiBase,
    repo: REPO,
    authText: AUTH_TEXT,
    log: (line, opts) => emit({ type: 'log', step: current, text: line, bad: opts?.bad === true }),
  });

  const state = { businessId: null, numbers: [], ids: [] };
  const mainAt = Math.max(0, accounts.findIndex((a) => a.main));

  const work = {
    async login() {
      await run.login(owner);
    },
    async reset() {
      // 금융망 단계를 고르지 않아도 해지는 해야 한다. userKey 는 이메일로 뽑으므로
      // 여기서 따로 열어도 finAccounts 가 여는 것과 같은 값이다.
      await run.openFin(owner.email);
      await run.resetSeed();
    },
    async business() {
      state.businessId = await run.ensureBusiness(t.business);
    },
    async finAccounts() {
      // userKey 는 이메일로 뽑는다. 계정 단계를 건너뛰어도 금융망 쪽은 붙을 수 있다.
      await run.openFin(owner.email);
      state.numbers = await run.ensureFinAccounts(accounts.length);
    },
    async link() {
      for (const [i, spec] of accounts.entries()) {
        state.ids.push(await run.ensureAccount(state.businessId, spec, state.numbers[i]));
      }
    },
    async csv() {
      await run.importCsv(state.ids[mainAt], csv);
    },
    async balance() {
      await run.alignBalance(state.numbers[mainAt], closingBalance);
    },
    async sync() {
      // 끝날 때까지 기다린다. 흔적 제외가 뒤따르는데, 동기화가 아직 돌고 있으면 제외할
      // 거래가 아직 없어 조용히 0건으로 지나간다(CLI 도 awaitJob 뒤에 부른다).
      const job = await run.sync(state.ids[mainAt]);
      await run.awaitJob(job.jobId);
    },
    async exclude() {
      await run.excludeBalanceArtifacts(state.businessId);
    },
    async productSales() {
      await run.registerProductSales(state.businessId, productSales);
    },
    async calendar() {
      // 생성기는 휴업일에 가게를 닫아 놓고 백엔드에는 안 알렸다 — 그러면 `OpenDayRatio` 가
      // 1.0 을 돌려주고 정산 투영이 평소 금액 그대로다. CLI 는 늘 이 단계를 밟는다.
      await run.registerClosedDays(state.businessId, t.closedDays);
    },
    async patterns() {
      await run.detectAndConfirmPatterns(state.businessId, holidayRules(t));
    },
    async autoExecute() {
      await run.enableAutoExecution(state.ids[mainAt]);
    },
  };

  emit({
    type: 'start',
    steps,
    asOf,
    apiBase,
    email: owner.email,
    rows: rows.length,
    closingBalance,
  });

  for (const key of steps) {
    current = key;
    emit({ type: 'step', step: key, status: 'running' });
    try {
      await work[key]();
      emit({ type: 'step', step: key, status: 'done' });
    } catch (err) {
      emit({ type: 'step', step: key, status: 'fail', message: err.message });
      // <b>여기가 경고가 필요한 자리다.</b> 지우기는 끝났는데 계좌를 다시 세우지 못하고
      // 멈추면, 예보 화면에 사라진 계좌의 잔액으로 그린 곡선이 그대로 떠 있다.
      // 멈춘 단계 아래에 붙는다 — 사용자가 보고 있는 곳이 거기다.
      await run.warnIfLeftEmpty().catch(() => {});
      emit({ type: 'done', ok: false, message: `${key} 에서 멈췄다 — ${err.message}` });
      console.log(`  ✗ ${key} — ${err.message}`);
      return;
    }
  }

  // 지우기만 고르고 끝낸 경우가 여기로 온다 — 실패가 아니라서 위 경로를 안 탄다.
  // 계좌가 다시 섰으면 조용하다.
  await run.warnIfLeftEmpty().catch(() => {});

  console.log(`  ✓ ${steps.join(' · ')}`);
  emit({ type: 'done', ok: true, message: `끝. ${owner.email} 로 로그인해서 확인하세요.` });
}

const server = createServer(async (req, res) => {
  const { pathname } = new URL(req.url, 'http://localhost');

  if (pathname === '/api/boot') {
    send(res, 200, {
      keyReady,
      envFound: Boolean(envFile),
      keyTail: keyReady ? apiKey.slice(-4) : null,
      apiBase: DEFAULT_API_BASE,
      today: ymd(new Date()),
      defaults: {
        owner: DEFAULT_OWNER,
        accounts: DEFAULT_ACCOUNTS,
        tuning: DEFAULT_TUNING,
      },
    });
    return;
  }

  if (req.method !== 'POST') {
    await serveStatic(req, res);
    return;
  }

  let input;
  try {
    input = await readBody(req);
  } catch (err) {
    send(res, 400, { ok: false, message: err.message });
    return;
  }

  // ── 미리보기 · CSV: 네트워크를 건드리지 않는다 ──────────────────────────

  if (pathname === '/api/preview') {
    try {
      send(res, 200, preview({
        asOf: sanitizeDate(input.asOf, ymd(new Date())),
        tuning: input.tuning,
      }));
    } catch (err) {
      // solveOpening 이 던지는 문구는 "무엇을 어떻게 고치라"까지 들어 있다. 그대로 보낸다.
      send(res, 200, { ok: false, message: err.message });
    }
    return;
  }

  if (pathname === '/api/csv') {
    try {
      const asOf = sanitizeDate(input.asOf, ymd(new Date()));
      const { t, from, rows } = pastRows(asOf, input.tuning);
      const body = input.kind === 'label' ? toLabelCsv(rows) : toCsv(rows);
      const slug = t.business.name.replace(/[\\/:*?"<>|\s]/g, '') || '시드';
      send(res, 200, {
        ok: true,
        // 엑셀에서 열 때 깨지지 않게 BOM 을 붙인다. CLI 가 파일로 쓸 때와 같다.
        csv: `\uFEFF${body}`,
        filename: `${slug}_${input.kind === 'label' ? '정답' : '거래'}_${ymd(from)}_${asOf}.csv`,
        rows: rows.length,
      });
    } catch (err) {
      send(res, 200, { ok: false, message: err.message });
    }
    return;
  }

  if (pathname === '/api/preset') {
    // CLI 로 넘길 설정 파일. `node scripts/seed.mjs --tuning <파일>` 이 이 모양을 읽는다.
    try {
      send(res, 200, {
        ok: true,
        preset: {
          asOf: sanitizeDate(input.asOf, ymd(new Date())),
          owner: sanitizeOwner(input.owner),
          accounts: sanitizeAccounts(input.accounts),
          tuning: resolveTuning(sanitizeTuning(input.tuning)),
        },
      });
    } catch (err) {
      send(res, 200, { ok: false, message: err.message });
    }
    return;
  }

  // ── 여기서부터는 실제로 바꾼다 ─────────────────────────────────────────

  if (pathname === '/api/run') {
    if (!keyReady) {
      send(res, 503, {
        ok: false,
        message: 'FINOPENAPI_API_KEY 가 비어 있다. backend/.env 를 확인한다.',
      });
      return;
    }
    const emit = streamer(res);
    try {
      await runPipeline(input, emit);
    } catch (err) {
      emit({ type: 'done', ok: false, message: err.message });
    }
    res.end();
    return;
  }

  // 직접 넣기 — 이미 있는 계정·계좌에 CSV 를 올린다. 토큰을 서버에 두지 않고 매번 로그인한다.
  if (pathname === '/api/accounts' || pathname === '/api/import') {
    try {
      const apiBase = sanitizeApiBase(input.apiBase);
      const email = coerce({ type: 'text', max: 100 }, input.email);
      const password = coerce({ type: 'text', max: 60 }, input.password);
      if (!email || !password) throw new Error('이메일과 비밀번호가 필요하다');

      const run = new SeedPipeline({ apiBase, repo: REPO, authText: AUTH_TEXT });
      const session = await run.api('POST', '/auth/login', { email, password });
      run.useToken(session.accessToken);

      if (pathname === '/api/accounts') {
        const businesses = await run.businessesWithAccounts();
        send(res, 200, {
          ok: true,
          businesses: businesses.map((b) => ({
            businessId: b.businessId,
            name: b.name,
            accounts: (b.accounts ?? []).map((a) => ({
              accountId: a.accountId,
              alias: a.alias,
              accountNoMasked: a.accountNoMasked,
              status: a.status,
            })),
          })),
        });
        return;
      }

      const accountId = coerce({ type: 'text', max: 64 }, input.accountId);
      const csv = typeof input.csv === 'string' ? input.csv : '';
      if (!accountId) throw new Error('올릴 계좌를 고른다');
      if (!csv.trim()) throw new Error('CSV 가 비어 있다');

      // 화면이 고른 계좌가 이 계정 것인지 확인한다. id 를 그대로 믿지 않는다.
      const mine = (await run.businessesWithAccounts())
        .flatMap((b) => b.accounts ?? [])
        .some((a) => String(a.accountId) === accountId);
      if (!mine) throw new Error('이 계정의 연결 계좌가 아니다');

      const result = await run.importCsv(accountId, csv);
      console.log(`  ✓ 직접 업로드 — ${result.importedCount ?? '?'}건`);
      send(res, 200, { ok: true, result });
    } catch (err) {
      send(res, 200, { ok: false, message: err.message });
    }
    return;
  }

  await serveStatic(req, res);
});

server.listen(PORT, '127.0.0.1', () => {
  console.log(`
  잔고예보 시드 콘솔

  http://127.0.0.1:${PORT}

  백엔드   ${DEFAULT_API_BASE}
  apiKey   ${keyReady ? `backend/.env 에서 읽음 (…${apiKey.slice(-4)})` : '없음 — backend/.env 의 FINOPENAPI_API_KEY 를 채운다'}
  단계     ${STEPS.map((s) => s.key).join(' → ')}

  개발 전용이다. 배포 경로에 넣지 않는다.
`);
});
