// 대출 상품 시드 — 금융망에 A~E 등급 대출 상품을 채운다 (P1-14).
//
// 왜 필요한가 — 금융망 대출 상품은 팀 앱에 E 등급 하나뿐이었다. 등급별 상품이 없으면
// 추천 화면(API 16.3)에 보여 줄 것이 없고, 「등급이 오르면 더 싼 상품이 열린다」는
// 신용등급 예측(16.2)의 결론도 화면에 나타날 자리가 없다.
//
// ⚠ 금융망에 **대출 상품 삭제 API 가 없다**(LOAN_01~10 어디에도 없다). 한 번 등록하면
//   팀 앱 전체에 영원히 남는다. 그래서 CLI 기본값이 dry-run 이고, 이미 있는 상품은
//   건너뛴다. 잘못 등록한 상품을 화면에서 지우는 방법은 LOAN_PRODUCT.recommendable 을
//   끄는 것뿐이다.
//
// plan.mjs / pipeline.mjs 와 같은 이유로 **순수한 것과 부수효과를 나눠 둔다** —
// 검증·계획은 네트워크를 안 건드리고, 등록만 금융망에 나간다.

import { readFileSync } from 'node:fs';
import { resolve } from 'node:path';

/**
 * LOAN_02 가 받는 범위(워크북 LOAN_02 요청 항목). 넘기면 금융망이 거절하는데,
 * 어느 필드가 문제인지 알려주지 않는다 — 그래서 보내기 전에 우리가 먼저 거른다.
 */
export const LIMITS = {
  nameChars: 20,
  descriptionChars: 255,
  period: [2, 365],
  minBalance: 1000,
  maxBalance: 300_000_000,
  rate: [0.1, 20],
};

/** 카탈로그는 코드가 아니라 데이터다 — 이름·금리·한도를 고치는 데 이 파일을 안 열어도 된다. */
export function readCatalog(repo, file = 'scripts/seed/loan-products.json') {
  return JSON.parse(readFileSync(resolve(repo, file), 'utf8')).products ?? [];
}

/** (은행, 상품명)이 같으면 같은 상품으로 본다. 금융망이 주는 고유번호는 등록 전에는 없다. */
export const keyOf = (p) => `${p.bankCode}|${p.accountName}`;

/**
 * 순수 함수. 카탈로그가 금융망 제약을 지키는지 본다.
 *
 * <p>반환이 비어 있지 않으면 <b>한 건도 보내지 않는다.</b> 절반만 등록되면 지울 수가 없다.
 */
export function validateCatalog(products) {
  const violations = [];
  for (const p of products) {
    const bad = (why) => violations.push(`${p.accountName}: ${why}`);
    const nameLen = [...(p.accountName ?? '')].length;
    const descLen = [...(p.accountDescription ?? '')].length;
    if (!nameLen) bad('상품명이 없다');
    if (nameLen > LIMITS.nameChars) bad(`상품명 ${nameLen}자 (${LIMITS.nameChars}자 이하)`);
    if (descLen > LIMITS.descriptionChars) bad(`설명 ${descLen}자 (${LIMITS.descriptionChars}자 이하)`);
    if (!(p.loanPeriod >= LIMITS.period[0] && p.loanPeriod <= LIMITS.period[1])) {
      bad(`기간 ${p.loanPeriod}일 (${LIMITS.period.join('~')})`);
    }
    if (!(p.minLoanBalance >= LIMITS.minBalance)) bad(`최소금액 ${p.minLoanBalance} (${LIMITS.minBalance} 이상)`);
    if (!(p.maxLoanBalance <= LIMITS.maxBalance)) bad(`최대금액 ${p.maxLoanBalance} (3억 이하)`);
    if (!(p.minLoanBalance <= p.maxLoanBalance)) bad('최소금액이 최대금액보다 크다');
    if (!(p.interestRate >= LIMITS.rate[0] && p.interestRate <= LIMITS.rate[1])) {
      bad(`금리 ${p.interestRate}% (${LIMITS.rate.join('~')})`);
    }
  }
  return violations;
}

/**
 * 순수 함수. 무엇을 새로 등록하고 무엇을 건너뛸지 정한다.
 *
 * @param grades   ratingName → ratingUniqueNo (LOAN_01)
 * @param existing 이미 있는 상품의 {@link keyOf} 집합 (LOAN_03)
 */
export function planProducts(products, grades, existing) {
  return products.map((p) => {
    const ratingUniqueNo = grades.get(p.grade);
    if (!ratingUniqueNo) throw new Error(`모르는 등급: ${p.grade} (${p.accountName})`);
    return { ...p, ratingUniqueNo, skip: existing.has(keyOf(p)) };
  });
}

/** LOAN_01 · LOAN_03 · BANK_01 — 셋 다 userKey 를 쓰지 않는다. 팀 전체가 같은 값을 본다. */
export async function readFinState(fin) {
  const criteria = await fin.call('loan/inquireAssetBasedCreditRatingList',
    'inquireAssetBasedCreditRatingList', null, {});
  const products = await fin.call('loan/inquireLoanProductList', 'inquireLoanProductList', null, {});
  const banks = await fin.call('bank/inquireBankCodes', 'inquireBankCodes', null, {});
  return {
    grades: new Map((criteria ?? []).map((g) => [g.ratingName, g.ratingUniqueNo])),
    existing: new Set((products ?? []).map(keyOf)),
    banks: new Map((banks ?? []).map((b) => [b.bankCode, b.bankName])),
  };
}

/**
 * LOAN_02 — 대출 상품 등록. <b>부수효과가 되돌릴 수 없다.</b>
 *
 * <p>⚠ 숫자를 숫자로 보내면 전부 {@code Q1001}(본문 형식 오류)이다. 워크북 타입표는
 * {@code loanPeriod}=int, {@code minLoanBalance}=Long, {@code interestRate}=double 이라고
 * 적었지만 <b>같은 시트의 요청 예제는 넷을 모두 문자열로 보낸다</b>({@code "loanPeriod": "3"}).
 * 응답도 문자열로 돌아온다. 타입표가 아니라 예제가 맞다 — 12건 전멸로 확인했다.
 */
export function createLoanProduct(fin, p) {
  return fin.call('loan/createLoanProduct', 'createLoanProduct', null, {
    bankCode: p.bankCode,
    accountName: p.accountName,
    accountDescription: p.accountDescription ?? '',
    ratingUniqueNo: p.ratingUniqueNo,
    loanPeriod: String(p.loanPeriod),
    minLoanBalance: String(p.minLoanBalance),
    maxLoanBalance: String(p.maxLoanBalance),
    interestRate: String(p.interestRate),
  });
}
