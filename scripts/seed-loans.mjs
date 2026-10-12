#!/usr/bin/env node
// 대출 상품 시드 — 금융망에 A~E 등급 대출 상품을 채운다 (P1-14).
//
//   node scripts/seed-loans.mjs              계획만 출력 (기본)
//   node scripts/seed-loans.mjs --commit     실제로 등록
//   node scripts/seed-loans.mjs --catalog 다른카탈로그.json
//
// 상품 목록은 코드가 아니라 scripts/seed/loan-products.json 에 있다. 이름·금리·한도를
// 고칠 때 이 파일을 열 필요가 없다.
//
// ⚠ 금융망에 **대출 상품 삭제 API 가 없다.** 한 번 등록하면 팀 앱 전체에 영원히 남는다.
//   그래서 세 겹으로 막는다 — (1) 보내기 전에 카탈로그를 검증하고 하나라도 어긋나면
//   아무것도 보내지 않는다, (2) 이미 있는 (은행, 상품명)은 건너뛴다, (3) --commit 없이는
//   계획만 찍는다. 여러 번 돌려도 상품이 늘어나지 않는다.
//
// ⚠ 시크릿은 인자로 받지 않는다. backend/.env(git 제외)에서 읽고 화면에 찍지 않는다.

import { dirname, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

import { FinClient, readEnv } from './seed/fin.mjs';
import {
  createLoanProduct, planProducts, readCatalog, readFinState, validateCatalog,
} from './seed/loans.mjs';

const REPO = process.env.JANGO_REPO ?? resolve(dirname(fileURLToPath(import.meta.url)), '..');

const argv = process.argv.slice(2);
const COMMIT = argv.includes('--commit');
const CATALOG = argv[argv.indexOf('--catalog') + 1];

const won = (n) => Number(n || 0).toLocaleString('ko-KR') + '원';
const ok = (s) => `\x1b[32m${s}\x1b[0m`;
const bad = (s) => `\x1b[31m${s}\x1b[0m`;
const dim = (s) => `\x1b[2m${s}\x1b[0m`;

const catalog = readCatalog(REPO, argv.includes('--catalog') ? CATALOG : undefined);

// 1. 금융망 제약을 먼저 건다. 절반만 등록되면 지울 수가 없다.
const violations = validateCatalog(catalog);
if (violations.length) {
  console.error(bad(`카탈로그가 금융망 제약을 어긴다 — ${violations.length}건. 아무것도 보내지 않았다.`));
  for (const v of violations) console.error(`  ${v}`);
  process.exit(1);
}

// 2. 등급 고유번호·기존 상품·은행명. 고유번호는 앱 상수지만 박아두지 않고 매번 조회한다.
const fin = new FinClient(readEnv(REPO, '.env'));
const { grades, existing, banks } = await readFinState(fin);
const plan = planProducts(catalog, grades, existing);

console.log(dim(`금융망에 이미 등록된 대출 상품 ${existing.size}개\n`));
for (const p of plan) {
  console.log(`  ${p.skip ? dim('건너뜀') : ok('등록')}  ${p.grade}  ` +
    `${(banks.get(p.bankCode) ?? p.bankCode).padEnd(10)} ${p.accountName}`);
  console.log(dim(`          ${p.interestRate}% · ${p.loanPeriod}일 · ` +
    `${won(p.minLoanBalance)} ~ ${won(p.maxLoanBalance)}`));
}

const todo = plan.filter((p) => !p.skip);
if (!todo.length) {
  console.log(ok('\n새로 등록할 상품이 없다. 카탈로그가 금융망과 같다.'));
  process.exit(0);
}
if (!COMMIT) {
  console.log(dim(`\n계획만 찍었다. ${todo.length}개를 실제로 등록하려면 --commit 을 붙여라.`));
  console.log(bad('  등록한 상품은 삭제할 수 없다. 이름·금리·한도를 먼저 확정해라.'));
  process.exit(0);
}

// 3. 등록. 한 건이 실패해도 나머지는 계속한다 — 재실행이 안전하므로 다시 돌리면 된다.
console.log('');
let done = 0;
for (const p of todo) {
  try {
    const rec = await createLoanProduct(fin, p);
    console.log(`  ${ok('✓')} ${p.accountName}  ${dim(rec?.accountTypeUniqueNo ?? '')}`);
    done++;
  } catch (e) {
    console.log(`  ${bad('✗')} ${p.accountName}  ${bad(e.message)}`);
    process.exitCode = 1;
  }
}
console.log(ok(`\n${done}/${todo.length} 등록 완료.`));
