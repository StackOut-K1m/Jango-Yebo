#!/usr/bin/env node
// 시연 데이터 시드 — "카페 한잔" 한 사업장을 발표 상태로 세운다.
//
//   node scripts/seed.mjs --as-of 2026-09-28
//   node scripts/seed.mjs --csv-only                     네트워크 없이 CSV 만 만든다
//   node scripts/seed.mjs --reset                        기존 시드를 지우고 처음부터 다시
//   node scripts/seed.mjs --reset-only                   지우기만 하고 멈춘다
//   node scripts/seed.mjs --confirm-patterns --auto-execute   반복 후보 승인 + 자동 반영 켜기
//   node scripts/seed.mjs --tuning seed-out/설정.json     시드 콘솔에서 맞춘 설정으로
//   SEED_ACCESS_TOKEN=... SEED_TARGET_ACCOUNT_NO=... node scripts/seed.mjs --csv 거래.csv --target-email owner@example.com
//
// 금액·날짜를 화면에서 맞추려면 시드 콘솔을 쓴다:  npm run seed  →  http://127.0.0.1:5181
// 거기서 「설정 내보내기」로 받은 파일이 위 --tuning 이다.
//
// 두 층으로 넣는다. <b>금융망은 과거 날짜 거래를 만들 수 없어서다</b> — DD_09 요청에
// 날짜 파라미터가 아예 없고 전부 오늘로 찍힌다(워크북 확인). 그래서
//
//   과거 180일 → CSV 업로드 (명세 7.4)      반복 패턴·모델 학습 표본
//   오늘       → 금융망 DD_08/09 로 잔액 맞추기 + 동기화
//
// 몇 번을 돌려도 같은 상태가 된다. 거래는 고정 시드 PRNG 라 같고, 업로드는 ON CONFLICT 가
// 흡수하며, 사업장·계좌는 이미 있으면 다시 만들지 않는다.
//
// 그 성질 때문에 <b>잘못 넣은 시드는 다시 돌려도 안 고쳐진다</b> — 이미 있는 계좌에 덧쌓인다.
// 그때 쓰는 것이 --reset 이다. 연동 계좌를 거래째 지우고(API 7.2 purge) 금융망에서도
// 해지한 뒤(DD_14) 아래 단계를 그대로 돌린다. <b>되돌릴 수 없다.</b>
//
// 계좌는 없으면 싸피은행(999)에 만든다 — scripts/fin-account.mjs 가 잡은 관례다.
// userKey 는 이메일로 뽑으므로 backend/.env 하나만 있으면 팀원 누구나 돌릴 수 있다.
//
// ⚠ 시크릿은 인자로 받지 않는다. backend/.env(git 제외)에서 읽고 화면에 찍지 않는다.

import { mkdirSync, readFileSync, writeFileSync } from 'node:fs';
import { dirname, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

import { SeedPipeline, matchesMaskedAccount } from './seed/pipeline.mjs';
import {
  DEFAULT_ACCOUNTS, DEFAULT_OWNER, addDays, buildPrimary, holidayRules, parse, productSalesPeriods,
  resolveTuning, solveOpening, toCsv, ymd,
} from './seed/plan.mjs';

// ── 설정 ──────────────────────────────────────────────────────────────────────

const REPO = process.env.JANGO_REPO ?? resolve(dirname(fileURLToPath(import.meta.url)), '..');

/** application.yml 의 {@code jango.finopenapi.account-auth.auth-text} 기본값과 같아야 한다. */
const AUTH_TEXT = process.env.ACCOUNT_AUTH_TEXT ?? '잔고예보';

// ── 인자 ──────────────────────────────────────────────────────────────────────

const argv = process.argv.slice(2);
const flag = (name) => argv.includes(name);
const opt = (name, fallback) => {
  const at = argv.indexOf(name);
  return at >= 0 && argv[at + 1] ? argv[at + 1] : fallback;
};

/**
 * 시드 콘솔(`tools/seed-console`)이 내보낸 설정 파일. 화면에서 맞춘 곡선을 그대로
 * CLI 에서 재생하려고 둔다 — 발표 당일 아침에 브라우저를 열 것 없이 이 파일만 있으면 된다.
 *
 * <p>{@code { asOf, owner, accounts, tuning }} 네 칸이고 <b>전부 선택</b>이다.
 * 빠진 칸은 기본값이 채운다({@code resolveTuning}).
 *
 * ⚠ 비밀번호가 들어갈 수 있는 파일이다. 저장소에 커밋하지 않는다(`seed-out/` 은 gitignore).
 */
const presetPath = opt('--tuning', null);
const preset = presetPath ? JSON.parse(readFileSync(resolve(presetPath), 'utf8')) : {};

const OWNER = { ...DEFAULT_OWNER, ...(preset.owner ?? {}) };

/**
 * 세울 계좌들. <b>계좌번호를 적지 않는다</b> — 없으면 싸피은행에 만든다.
 *
 * 처음에는 시드 계정이 이미 가진 한국은행 계좌 셋을 번호째 박아 뒀는데, 그러면
 * {@code .env.seed.local} 을 받은 사람만 시드를 돌릴 수 있고 팀 관례와도 어긋난다 —
 * {@code scripts/fin-account.mjs} 가 개발·시연 계좌를 싸피은행(999)에 만들도록 정해 뒀다.
 * 이메일로 userKey 를 뽑으면 {@code backend/.env} 하나로 누구나 돌릴 수 있다.
 */
const ACCOUNTS = preset.accounts?.length ? preset.accounts : DEFAULT_ACCOUNTS;

const TUNING = resolveTuning(preset.tuning);

const asOf = parse(opt('--as-of', preset.asOf ?? ymd(new Date())));
const apiBase = opt('--api', 'http://localhost:8080').replace(/\/$/, '');
const outDir = resolve(REPO, opt('--out', 'seed-out'));
const csvOnly = flag('--csv-only');
/**
 * 반복 후보를 탐지해 전부 승인한다. <b>예정 거래는 승인해야 생긴다</b> — 자동 반영은
 * 그 뒤에야 할 일이 있다. 기본이 꺼짐인 이유는 발표에서 사장님이 화면에서 누르는 것이
 * 시연 흐름이기 때문이다(리허설·로컬에서 켜고 쓴다).
 */
const confirmPatterns = flag('--confirm-patterns');
/**
 * 예정 거래 자동 반영을 켠다 (API 9.6). 켜면 서버가 예정일마다 이 계좌에서 <b>실제로</b>
 * 입출금한다. ⚠ 로컬과 서버 중 <b>한쪽에서만</b> 켠다 — 같은 이메일로 양쪽을 시드하면
 * 두 서버가 같은 금융망 계좌를 본다.
 */
const autoExecute = flag('--auto-execute');
/**
 * <b>세우기 전에 기존 시드를 지운다.</b> 이 계정에 연동된 계좌를 거래째 지우고(API 7.2)
 * 금융망 계좌도 해지한다(DD_14).
 *
 * <p>되돌릴 수 없는 일에 확인을 따로 두지 않는 것은, <b>플래그를 친 것이 곧 확인</b>이기
 * 때문이다 — 화면(시드 콘솔)에는 한 번 더 묻는 자리가 있다.
 */
const resetOnly = flag('--reset-only');
const reset = resetOnly || flag('--reset');
const providedCsvPath = opt('--csv', null);
const targetEmail = opt('--target-email', null);
const targetAccountNo = process.env.SEED_TARGET_ACCOUNT_NO?.replace(/\D/g, '') || null;

// ── 파이프라인 ────────────────────────────────────────────────────────────────

// 실제로 상태를 세우는 코드는 scripts/seed/pipeline.mjs 에 있다. 시드 콘솔
// (tools/seed-console)이 같은 파일을 쓴다 — 화면과 명령어가 서로 다른 시드를 만들면 안 된다.
const red = (s) => `\x1b[31m${s}\x1b[0m`;

const run = new SeedPipeline({
  apiBase,
  repo: REPO,
  authText: AUTH_TEXT,
  // 경고는 빨갛게. 되돌리기 뒤의 「계좌가 0개다」가 300줄 로그 한가운데 묻히면
  // 안 적은 것과 같다(seed-loans.mjs 와 같은 방식).
  log: (line, opts) => console.log(`  ${opts?.bad ? red(line) : line}`),
});

/**
 * 제공된 CSV 를 기존 사용자의 지정 계좌에만 넣는다. 계좌번호·토큰은 파일이나 로그에 남기지 않는다.
 *
 * <p>화면에서는 목록에서 계좌를 고르면 되지만(시드 콘솔 「직접 넣기」), CLI 에는 고를 화면이
 * 없어서 계좌번호를 환경변수로 받아 마스킹 표기와 맞춰 본다.
 */
async function importProvidedCsv() {
  if (!targetEmail || !targetAccountNo) {
    throw new Error('--csv 사용 시 --target-email 과 SEED_TARGET_ACCOUNT_NO 가 필요합니다.');
  }

  const preAuth = process.env.SEED_ACCESS_TOKEN?.trim() || null;
  if (preAuth) {
    run.useToken(preAuth);
  } else {
    const password = process.env.SEED_TARGET_PASSWORD?.trim();
    if (!password) throw new Error('SEED_ACCESS_TOKEN 또는 SEED_TARGET_PASSWORD 가 필요합니다.');
    const session = await run.api('POST', '/auth/login', { email: targetEmail, password });
    run.useToken(session.accessToken);
  }

  const me = await run.me();
  if (me.email.toLowerCase() !== targetEmail.toLowerCase()) {
    throw new Error(`인증 계정(${me.email})이 대상 계정(${targetEmail})과 다릅니다.`);
  }

  const matched = [];
  for (const business of await run.businessesWithAccounts()) {
    for (const account of business.accounts) {
      if (matchesMaskedAccount(targetAccountNo, account.accountNoMasked)) {
        matched.push({ business, account });
      }
    }
  }

  if (matched.length !== 1) {
    throw new Error(`지정 계좌와 일치하는 연결 계좌가 ${matched.length}개입니다.`);
  }

  const csv = readFileSync(resolve(providedCsvPath), 'utf8');
  if (!csv.trim()) throw new Error('CSV 파일이 비어 있습니다.');

  const [{ business, account }] = matched;
  console.log(`대상 계정     ${me.email}`);
  console.log(`대상 사업장   ${business.name} (${business.businessId})`);
  console.log(`대상 계좌     ${account.accountNoMasked} (${account.accountId})`);
  console.log(`CSV           ${resolve(providedCsvPath)}`);

  await run.importCsv(account.accountId, csv);
}

/**
 * 기존 시드를 지운다. <b>되돌릴 수 없다.</b>
 *
 * <p>계좌를 지우고(우리 DB) 해지한다(금융망). 사업장은 남는다 — 상호가 같으면 다시
 * 만들지 않으므로 이어지는 단계가 그대로 재사용한다. 사정은 {@code SeedPipeline#resetSeed}.
 *
 * @param opened 이미 {@code openFin} · {@code login} 을 거쳤으면 참. 두 번 부를 이유가 없다
 */
async function resetSeed({ opened = false } = {}) {
  if (!opened) {
    await run.openFin(OWNER.email);
    console.log('로그인');
    await run.login(OWNER);
  }
  console.log('\n기존 시드 지우기  ⚠ 되돌릴 수 없다');
  return run.resetSeed();
}

// ── 실행 ──────────────────────────────────────────────────────────────────────

async function main() {
  if (providedCsvPath) {
    await importProvidedCsv();
    return;
  }

  if (resetOnly) {
    await resetSeed();
    // 여기서 멈추는 것이 --reset-only 의 뜻이므로 경고는 반드시 뜬다. 그게 맞다 —
    // 계좌 0개로 두고 가는 것이 무엇을 남기는지 알고 가야 한다.
    await run.warnIfLeftEmpty();
    console.log('\n--reset-only 라 여기서 멈춘다. 다시 세우려면 --reset 없이 그냥 돌리세요.');
    return;
  }

  const from = addDays(asOf, -TUNING.window.pastDays);
  const horizon = addDays(asOf, TUNING.window.horizonDays);

  const solved = solveOpening({ from, asOf, to: horizon, tuning: TUNING });
  const { rows } = buildPrimary({ from, asOf, to: horizon, opening: solved.opening, tuning: TUNING });
  const past = rows.filter((r) => r.date < ymd(asOf));
  const closingBalance = past.at(-1).balanceAfter;
  // 상품별 매출은 같은 과거 거래에서 뽑는다 — 인쇄물 합계와 통장 합계가 서로 다른 매출을
  // 말하면 안 된다. 창에 통째로 들어온 달만 나온다(P1-22).
  const productSales = productSalesPeriods({ rows: past, from, asOf, tuning: TUNING });

  console.log(`기준일        ${ymd(asOf)}`);
  console.log(`과거 구간     ${ymd(from)} ~ ${ymd(addDays(asOf, -1))}  ${past.length}건`);
  console.log(`기준일 잔액   ${closingBalance.toLocaleString()}원`);
  console.log(`예보 창 저점  ${solved.anchorLow.toLocaleString()}원  (${solved.windowFrom} ~ ${solved.windowTo})`);
  console.log(`상품별 매출   ${productSales.length}개 기간  ${productSales.reduce((sum, p) => sum + p.items.length, 0)}행`);

  // 기준일이 미래면 두 가지가 어긋난다. 리허설로 미리 돌릴 때 실제로 겪는 일이라 적어 둔다.
  //
  //   1. 금융망 거래는 <b>실제 오늘</b> 날짜로 찍힌다(DD_09 에 날짜 파라미터가 없다).
  //      그래서 잔액 맞추기 '전기이월' 이 CSV 구간 한가운데 앉고, 그 행의 잔액만 곡선에서 튄다.
  //   2. 거래 목록 기본 조회창이 {@code to = 오늘} 이라 CSV 의 뒷부분이 잘려 보인다.
  //      (2026-09-09 에 --as-of 2026-09-28 로 돌렸더니 314건 중 59건만 나왔다. 범위를 명시하면 다 나온다.)
  //
  // 발표 당일에 돌리면 둘 다 사라진다.
  const realToday = ymd(new Date());
  if (ymd(asOf) > realToday) {
    console.log(`\n⚠ 기준일이 오늘(${realToday})보다 뒤다.`);
    console.log('  금융망 거래는 실제 오늘로 찍히므로 CSV 구간 안에 섞이고,');
    console.log('  거래 목록 기본 창(to=오늘)이 CSV 뒷부분을 자른다. 발표 당일에 다시 돌리세요.');
  }

  const csv = toCsv(past);
  mkdirSync(outDir, { recursive: true });
  const slug = TUNING.business.name.replace(/[\\/:*?"<>|\s]/g, '') || '시드';
  const generatedCsvPath = resolve(outDir, `${slug}_${ymd(from)}_${ymd(addDays(asOf, -1))}.csv`);
  // 엑셀에서 열 때 깨지지 않게 BOM(\uFEFF)을 붙인다. 리터럴로 적으면 눈에 안 보여 이스케이프로 쓴다.

  writeFileSync(generatedCsvPath, `\uFEFF${csv}`, 'utf8');
  console.log(`\nCSV           ${generatedCsvPath}`);

  if (csvOnly) {
    console.log('\n--csv-only 라 여기서 멈춘다.');
    return;
  }

  // .env 하나면 된다. userKey 는 이메일로 뽑으므로 공유 시크릿 파일이 필요 없다.
  await run.openFin(OWNER.email);

  console.log('\n로그인');
  await run.login(OWNER);

  if (reset) await resetSeed({ opened: true });

  console.log('\n사업장');
  const businessId = await run.ensureBusiness(TUNING.business);

  console.log('\n상품별 매출 등록');
  await run.registerProductSales(businessId, productSales);

  console.log('\n계좌');
  const numbers = await run.ensureFinAccounts(ACCOUNTS.length);
  const ids = [];
  for (const [i, spec] of ACCOUNTS.entries()) {
    ids.push(await run.ensureAccount(businessId, spec, numbers[i]));
  }
  const mainAt = Math.max(0, ACCOUNTS.findIndex((a) => a.main));
  const mainId = ids[mainAt];
  const mainNo = numbers[mainAt];

  console.log('\n과거 거래 업로드');
  // 공휴일과 휴무일은 다르다. 앞은 달력에서 알지만 뒤는 가게마다 다르고, 이력으로도
  // 배울 수 없다 — 탐지 창에 휴업이 한 번도 없는 것이 흔하다. 아는 쪽이 등록한다.
  console.log('\n휴무일 등록');
  await run.registerClosedDays(businessId, TUNING.closedDays);

  await run.importCsv(mainId, csv);

  console.log('\n금융망 잔액 맞추기');
  await run.alignBalance(mainNo, closingBalance);

  console.log('\n동기화 접수');
  const syncJob = await run.sync(mainId);

  // 금융망은 잔액을 직접 정할 수 없어 입금 거래로 만든다. 그건 사장님이 한 거래가 아니라
  // 예측에서 빼야 한다 — 안 빼면 「개발용 초기 잔액」 300만원이 평소 수입으로 읽힌다.
  console.log('\n잔액 맞추기 흔적 제외');
  await run.awaitJob(syncJob.jobId);
  await run.excludeBalanceArtifacts(businessId);

  if (confirmPatterns) {
    // 예정 거래는 승인해야 생긴다. 자동 반영은 그 뒤에야 할 일이 있다.
    console.log('\n반복 후보 승인');
    await run.detectAndConfirmPatterns(businessId, holidayRules(TUNING));
  }

  if (autoExecute) {
    console.log('\n예정 거래 자동 반영');
    await run.enableAutoExecution(mainId);
    console.log('  ⚠ 로컬과 서버 중 한쪽에서만 켭니다 — 같은 계좌를 둘이 보게 됩니다.');
  }

  // 되돌리기를 했으면 계좌가 다시 섰는지 확인하고 끝낸다. 섰으면 조용하다 —
  // 「그럴 것이다」와 「확인했다」의 차이값이 요청 두 번이면 싸다.
  if (reset) await run.warnIfLeftEmpty();

  console.log('\n끝. 로그인해서 확인하세요.');
  console.log(`  ${OWNER.email} / ${OWNER.password}`);
}

main().catch(async (e) => {
  console.error(`\n실패: ${e.message}`);
  // <b>여기가 경고가 필요한 자리다.</b> 되돌리기는 끝났는데 계좌를 다시 세우지 못하고
  // 멈춘 경우, 예보 화면에는 사라진 계좌의 잔액으로 그린 곡선이 그대로 떠 있다.
  // 확인 자체가 또 실패해도 원래 오류를 덮지 않는다.
  await run.warnIfLeftEmpty().catch(() => {});
  process.exit(1);
});
