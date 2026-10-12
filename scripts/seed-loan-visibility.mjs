#!/usr/bin/env node
// 대출 상품 추천 노출을 켜는 SQL 을 만든다 (P1-14).
//
//   node scripts/seed-loan-visibility.mjs        SQL 을 출력한다 (그대로 DB 에 붙여넣는다)
//
// 왜 필요한가 — LOAN_SYNC 가 금융망 상품을 우리 DB 로 가져올 때 recommendable 을 건드리지
// 않는다. 새 상품은 **꺼진 채로** 들어오고 사람이 켜야 추천에 나간다. 금융망에는 대출 상품
// 삭제 API 가 없고 팀 apiKey 를 가진 누구나 상품을 만들 수 있어서, 목록을 그대로 쓰면
// 남이 시험 삼아 만든 상품이 사장님에게 「이 대출을 받으세요」로 나간다.
//
// **카탈로그가 곧 허용 목록이다.** seed-loans.mjs 가 금융망에 등록할 때 쓴 그 파일을 그대로
// 읽어, (은행코드, 상품명)이 같은 것만 켜는 SQL 을 만든다. 목록에 없는 상품은 건드리지
// 않고, 이미 켜 둔 것도 끄지 않는다 — 사람이 판단해 켠 것일 수 있다.
//
// DB 에 직접 붙지 않는 이유 — psql 이 PATH 에 없는 개발 환경이 있고(DB 가 Docker 안에 있다)
// 서버는 접속 정보가 또 다르다. 만드는 일과 실행하는 일을 갈라 두면 어디서든 쓸 수 있다.
//
// ⚠ 관리 화면이 생기면 이 스크립트는 죽는다. 그때까지의 임시 경로다.

import { dirname, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

import { readCatalog } from './seed/loans.mjs';

const REPO = process.env.JANGO_REPO ?? resolve(dirname(fileURLToPath(import.meta.url)), '..');
const catalog = readCatalog(REPO);

if (!catalog.length) {
  console.error('카탈로그가 비어 있다: scripts/seed/loan-products.json');
  process.exit(1);
}

const quote = (s) => `'${String(s).replace(/'/g, "''")}'`;
const pairs = catalog.map((p) => `(${quote(p.bankCode)}, ${quote(p.accountName)})`);

console.log(`-- 대출 상품 ${catalog.length}개를 추천에 노출한다 (scripts/seed/loan-products.json 기준).
-- 먼저 LOAN_SYNC 를 돌려 core.loan_product 를 채운다:
--   POST /api/v1/businesses/{businessId}/loans/sync
--
-- 목록에 없는 상품은 건드리지 않는다. 이미 켜 둔 것도 끄지 않는다.

UPDATE core.loan_product
   SET recommendable = TRUE, updated_at = now()
 WHERE recommendable = FALSE
   AND (bank_code, name) IN (
${pairs.map((p) => '         ' + p).join(',\n')}
       );

-- 확인
SELECT bank_code, name, min_grade, interest_rate, loan_period_days, recommendable
  FROM core.loan_product
 ORDER BY recommendable DESC, interest_rate;`);
