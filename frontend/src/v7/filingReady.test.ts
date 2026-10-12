import { expect, test } from 'vitest';
import type { Account, TaxDraftDetail, TaxDraftField } from '@jangoyebo/core';
import { filingIssues, needsReauth } from './filingReady';

const draft = (over: Partial<TaxDraftDetail> = {}): TaxDraftDetail =>
  ({
    draftId: 7,
    taxYear: 2026,
    formType: 'SIMPLE_LEDGER',
    status: 'READY',
    periodStart: '2026-01-01',
    periodEnd: '2026-12-31',
    sections: [],
    disclaimer: '초안입니다',
    ...over,
  }) as TaxDraftDetail;

const section = (fields: TaxDraftField[]) => [{ sectionKey: 'EXPENSE', label: '경비', fields }];
const account = (status: Account['status'], alias?: string): Account =>
  ({ accountId: 1, bankCode: '088', accountNoMasked: '***1234', alias, isPrimary: true, status, syncStatus: 'NEVER' }) as Account;

test('덜 모인 것이 없으면 아무것도 말하지 않는다', () => {
  expect(filingIssues(draft(), [account('CONNECTED')])).toEqual([]);
});

// 분류 전 거래는 초안 금액에 아예 안 들어간다 — 빼고 보면 매출 0원 초안도 그럴듯하다
test('분류하지 않은 거래를 건수와 금액으로 말한다', () => {
  const [issue] = filingIssues(draft({ excluded: { unclassified: { count: 486, amount: 9_000_000 } } }), []);
  expect(issue.id).toBe('unclassified');
  expect(issue.detail).toContain('486건');
  expect(issue.detail).toContain('9,000,000원');
  expect(issue.to).toBe('/transactions');
});

// 사용자가 일부러 뺀 것은 누락이 아니다 — 초안 화면이 이미 따로 보여 준다
test('사용자가 검토에서 뺀 거래는 세지 않는다', () => {
  const issues = filingIssues(
    draft({ excluded: { personalSuspected: { count: 2, amount: 30_000 }, needsReview: { count: 1, amount: 50_000 } } }),
    [],
  );
  expect(issues).toEqual([]);
});

test('비어 있거나 확인 전인 항목을 세고 이름을 몇 개 보여 준다', () => {
  const issues = filingIssues(
    draft({
      sections: section([
        { fieldKey: 'DEP', label: '㉛ 감가상각비', source: 'USER_CONFIRM', status: 'EMPTY' },
        { fieldKey: 'STOCK', label: '⑭ 기초재고', source: 'USER_CONFIRM', status: 'NEEDS_REVIEW' },
        // 파일에만 적는 개인정보는 내보내기 단계에서 따로 묻는다
        { fieldKey: 'HOME_ADDRESS', label: '①주소지', source: 'USER_SENSITIVE', status: 'EMPTY' },
        { fieldKey: 'RENT', label: '㉗ 임차료', source: 'AUTO_TRANSACTION', status: 'FILLED' },
      ]),
    }),
    [],
  );
  expect(issues.map((i) => i.id)).toEqual(['fields']);
  expect(issues[0].detail).toContain('2개 항목');
  expect(issues[0].detail).toContain('㉛ 감가상각비');
  expect(issues[0].detail).not.toContain('주소지');
  expect(issues[0].to).toBe('/tax/drafts/7');
});

test('증빙 누락은 항목마다 센 것을 합친다', () => {
  const issues = filingIssues(
    draft({
      sections: section([
        { fieldKey: 'RENT', label: '㉗ 임차료', basisSummary: { evidenceMissingCount: 4 } },
        { fieldKey: 'SUPPLIES', label: '㉜ 소모품비', basisSummary: { evidenceMissingCount: 9 } },
        { fieldKey: 'FEE', label: '㉝ 지급수수료', basisSummary: { transactionCount: 12 } },
      ]),
    }),
    [],
  );
  expect(issues[0].id).toBe('evidence');
  expect(issues[0].detail).toContain('13건');
});

// 해제는 사장님이 일부러 끊은 것이다 — 인증 대기·만료만 「끊길 뜻이 없었는데 안 들어오는」 것이다
test('인증 대기·만료 계좌만 자료 누락으로 센다', () => {
  expect(needsReauth(account('PENDING_VERIFICATION'))).toBe(true);
  expect(needsReauth(account('VERIFICATION_EXPIRED'))).toBe(true);
  expect(needsReauth(account('CONNECTED'))).toBe(false);
  expect(needsReauth(account('DISCONNECTED'))).toBe(false);

  const issues = filingIssues(draft(), [
    account('CONNECTED', '주계좌'),
    account('VERIFICATION_EXPIRED', '배달 정산'),
    account('DISCONNECTED', '옛 계좌'),
  ]);
  expect(issues.map((i) => i.id)).toEqual(['accounts']);
  expect(issues[0].detail).toContain('배달 정산');
  expect(issues[0].detail).not.toContain('옛 계좌');
});

// 먼저 고칠 것이 위에 와야 한다 — 분류가 안 되면 나머지 숫자가 통째로 바뀐다
test('여러 개가 겹치면 분류 → 항목 → 증빙 → 계좌 순이다', () => {
  const issues = filingIssues(
    draft({
      excluded: { unclassified: { count: 1, amount: 1 } },
      sections: section([
        { fieldKey: 'DEP', label: '㉛ 감가상각비', source: 'USER_CONFIRM', status: 'EMPTY' },
        { fieldKey: 'RENT', label: '㉗ 임차료', basisSummary: { evidenceMissingCount: 1 } },
      ]),
    }),
    [account('VERIFICATION_EXPIRED')],
  );
  expect(issues.map((i) => i.id)).toEqual(['unclassified', 'fields', 'evidence', 'accounts']);
});

// 낡은 초안은 아래 항목을 다 손봐도 숫자 자체가 틀린다 — 그래서 맨 앞이다
test('근거가 바뀐 초안(STALE)을 가장 먼저 말한다', () => {
  const issues = filingIssues(
    draft({ status: 'STALE', excluded: { unclassified: { count: 3, amount: 1_000 } } }),
    [account('CONNECTED')],
  );
  expect(issues[0].id).toBe('stale');
  expect(issues[0].to).toBe('/tax/drafts/7');
  // 다른 항목을 덮어쓰지 않는다 — 낡은 것과 덜 모인 것은 따로 고쳐야 한다
  expect(issues.map((i) => i.id)).toContain('unclassified');
});

test('READY 초안은 낡았다고 말하지 않는다', () => {
  expect(filingIssues(draft({ status: 'READY' }), [account('CONNECTED')]).map((i) => i.id)).not.toContain('stale');
});
