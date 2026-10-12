import { expect, test } from 'vitest';
import type { TaxDraftSummary, TaxFilingForm } from '@jangoyebo/core';
import {
  ASSIGNABLE_REVIEW,
  canDraftForm,
  collectAllIds,
  emptyFieldGuide,
  excludedByReview,
  markedExclusion,
  splitDrafts,
  ledgerThresholdLabel,
  likelyDuty,
  fieldValueLabel,
  hasBasis,
  isEditable,
  needsFieldAttention,
  parseWon,
  sensitiveFields,
  yearPeriod,
} from './taxView';

// 증빙 두 값은 서버가 400 으로 막는다 — 고르는 목록에 있으면 안 된다
test('사람이 고를 수 있는 검토 상태에 자동 판정 값이 없다', () => {
  expect(ASSIGNABLE_REVIEW).not.toContain('EVIDENCE_CONFIRMED');
  expect(ASSIGNABLE_REVIEW).not.toContain('EVIDENCE_MISSING');
  expect(ASSIGNABLE_REVIEW).not.toContain('ASSET_REVIEW');
});

test('항목 값은 금액·글자·없음을 가른다', () => {
  expect(fieldValueLabel(14400000)).toBe('14,400,000원');
  expect(fieldValueLabel('정유선')).toBe('정유선');
  expect(fieldValueLabel(null)).toBe('—');
  expect(fieldValueLabel(undefined)).toBe('—');
  expect(fieldValueLabel(0)).toBe('0원');
  expect(fieldValueLabel(40, 'INCOME_TYPE')).toBe('40');
  expect(fieldValueLabel('FOOD_CAFE', 'INDUSTRY')).toBe('카페');
});

test('근거·수정 가능 여부는 출처로 정한다', () => {
  expect(hasBasis({ fieldKey: 'RENT', label: '임차료', source: 'AUTO_TRANSACTION' })).toBe(true);
  expect(hasBasis({ fieldKey: 'SALES', label: '매출', source: 'AUTO_SETTLEMENT' })).toBe(true);
  expect(hasBasis({ fieldKey: 'DEP', label: '감가상각비', source: 'USER_CONFIRM' })).toBe(false);
  expect(isEditable({ fieldKey: 'DEP', label: '감가상각비', source: 'USER_CONFIRM' })).toBe(true);
  expect(isEditable({ fieldKey: 'NET', label: '소득금액', source: 'CALCULATED' })).toBe(false);
  expect(isEditable({ fieldKey: 'BIZ_ADDRESS', label: '소재지', source: 'USER_CONFIRM' })).toBe(false);
});

test('올해는 오늘까지만, 지난해는 연말까지', () => {
  expect(yearPeriod(2026, '2026-09-17')).toEqual({ from: '2026-01-01', to: '2026-09-17' });
  expect(yearPeriod(2025, '2026-09-17')).toEqual({ from: '2025-01-01', to: '2025-12-31' });
});

// 상세 응답에는 민감 항목 키가 없다 — 서식 정의에서 찾아야 내보낼 때 물어볼 수 있다
test('민감 항목은 서식 정의에서 찾는다', () => {
  const form: TaxFilingForm = {
    formType: 'SIMPLE_LEDGER',
    name: '간편장부',
    supportLevel: 'DRAFT',
    sections: [
      {
        sectionKey: 'IDENTITY',
        label: '인적사항',
        fields: [
          { fieldKey: 'HOME_ADDRESS', label: '① 주소지', source: 'USER_SENSITIVE', sensitive: true },
          { fieldKey: 'NAME', label: '③ 성명', source: 'AUTO_PROFILE', sensitive: false },
          { fieldKey: 'BIRTH_DATE', label: '④ 생년월일', source: 'USER_SENSITIVE', sensitive: true },
        ],
      },
    ],
  };
  expect(sensitiveFields(form).map((f) => f.fieldKey)).toEqual(['HOME_ADDRESS', 'BIRTH_DATE']);
  expect(sensitiveFields({ ...form, sections: undefined })).toEqual([]);
});

// 판정이 아니라 가능성이다 — 모르는 답이 있으면 말하지 않는다
test('장부 유형 가능성은 전문직 → 개업 → 매출 순으로 본다', () => {
  expect(likelyDuty({})).toBeNull();
  expect(likelyDuty({ professional: true })).toBe('DOUBLE_ENTRY');
  expect(likelyDuty({ professional: true, newlyOpened: true })).toBe('DOUBLE_ENTRY');
  expect(likelyDuty({ professional: false })).toBeNull();
  expect(likelyDuty({ professional: false, newlyOpened: true })).toBe('SIMPLE_LEDGER');
  expect(likelyDuty({ professional: false, newlyOpened: false })).toBeNull();
  expect(likelyDuty({ professional: false, newlyOpened: false, revenue: 'BELOW' })).toBe('SIMPLE_LEDGER');
  expect(likelyDuty({ professional: false, newlyOpened: false, revenue: 'ABOVE' })).toBe('DOUBLE_ENTRY');
  expect(likelyDuty({ professional: false, newlyOpened: false, revenue: 'UNKNOWN' })).toBeNull();
});

test('간편장부 기준은 업종마다 다르고 기타는 묻지 않는다', () => {
  expect(ledgerThresholdLabel('FOOD_CAFE')).toBe('1억 5천만원');
  expect(ledgerThresholdLabel('RETAIL')).toBe('3억원');
  expect(ledgerThresholdLabel('SERVICE')).toBe('7,500만원');
  expect(ledgerThresholdLabel('OTHER')).toBeNull();
});

// 비어 있는 항목이 모두 같은 곳(내보내기)으로 가던 것 — 항목마다 채우는 곳이 다르다
test('비어 있는 항목은 채우는 곳으로 보낸다', () => {
  expect(emptyFieldGuide('PHONE')?.action?.to).toBe('/mypage');
  expect(emptyFieldGuide('HOME_ADDRESS')?.action?.anchor).toBe('sensitive-HOME_ADDRESS');
  expect(emptyFieldGuide('INDUSTRY_CODE')?.action).toBeUndefined();
  expect(emptyFieldGuide('RENT')).toBeNull();
});

// 검토는 예외 표시다 — 표시 없음과 증빙 두 값만 금액에 들어간다
test('검토 상태 중 넷은 초안 금액에서 빠진다', () => {
  expect(excludedByReview('UNREVIEWED')).toBe(false);
  expect(excludedByReview('EVIDENCE_MISSING')).toBe(false);
  expect(excludedByReview('PERSONAL_SUSPECTED')).toBe(true);
  expect(excludedByReview('EXPERT_REVIEW')).toBe(true);
  expect(markedExclusion(undefined)).toBeNull();
  expect(markedExclusion({ unclassified: { count: 486, amount: 9_000_000 } })).toBeNull();
  expect(
    markedExclusion({ personalSuspected: { count: 2, amount: 30_000 }, needsReview: { count: 1, amount: 50_000 } }),
  ).toEqual({ count: 3, amount: 80_000 });
});

// 초안이 쌓이면 목록이 난잡하다 — 서식마다 최신 하나만 앞에 둔다
test('초안 목록은 서식마다 최신 하나와 나머지로 가른다', () => {
  const d = (draftId: number, formType: TaxDraftSummary['formType']) =>
    ({ draftId, formType, status: 'READY', periodStart: '2026-01-01', periodEnd: '2026-12-31' }) as TaxDraftSummary;
  const { latest, older } = splitDrafts([d(3, 'SIMPLE_LEDGER'), d(12, 'SIMPLE_LEDGER'), d(7, 'STANDARD_RATE'), d(9, 'SIMPLE_LEDGER')]);
  expect(latest.map((x) => x.draftId)).toEqual([12, 7]);
  expect(older.map((x) => x.draftId)).toEqual([9, 3]);
  expect(splitDrafts([])).toEqual({ latest: [], older: [] });
});

// 기준경비율은 초안 대상(DRAFT_OPTIONAL)이지만 서버가 칸을 아직 안 채웠다 — 누르면 빈 초안이 생긴다
test('칸 정의가 없는 서식은 초안을 만들 수 없다', () => {
  const base: TaxFilingForm = { formType: 'STANDARD_RATE', name: '기준경비율', supportLevel: 'DRAFT_OPTIONAL' };
  expect(canDraftForm(base)).toBe(false);
  expect(canDraftForm({ ...base, sections: [{ sectionKey: 'A', label: 'A', fields: [] }] })).toBe(true);
  expect(canDraftForm({ ...base, supportLevel: 'CHECKLIST_ONLY', sections: [{ sectionKey: 'A', label: 'A', fields: [] }] })).toBe(false);
});

test('금액 입력은 비움과 숫자를 가른다', () => {
  expect(parseWon('3,400,000')).toBe(3400000);
  expect(parseWon('')).toBeNull();
  expect(parseWon('삼백')).toBeNaN();
});

test('확인할 항목은 미확인 경고와 빈 값이며 파일용 개인정보는 제외한다', () => {
  const field = { fieldKey: 'DEP', label: '감가상각비', source: 'USER_CONFIRM' as const };
  expect(needsFieldAttention({ ...field, status: 'NEEDS_REVIEW' })).toBe(true);
  expect(needsFieldAttention({ ...field, status: 'NEEDS_REVIEW', confirmed: true })).toBe(false);
  expect(needsFieldAttention({ ...field, status: 'EMPTY', confirmed: true })).toBe(true);
  expect(needsFieldAttention({ ...field, status: 'FILLED', value: 0 })).toBe(false);
  expect(needsFieldAttention({ ...field, status: 'EMPTY', source: 'USER_SENSITIVE' })).toBe(false);
});

// 「모두 고르기」는 보고 있는 쪽이 아니라 조건에 맞는 전부를 고른다.
// 한 쪽만 세고 멈추면, 다 골랐다고 믿고 적용한 뒤 다음 쪽이 그대로 남는다.
function pager(total: number, size: number, seen: number[]) {
  const totalPages = Math.max(1, Math.ceil(total / size));
  return async (index: number) => {
    seen.push(index);
    const from = index * size;
    return { ids: Array.from({ length: Math.max(0, Math.min(size, total - from)) }, (_, i) => from + i + 1), totalPages };
  };
}

test('모두 고르기는 쪽을 이어 받아 조건에 맞는 전부를 모은다', async () => {
  const seen: number[] = [];
  expect(await collectAllIds(pager(250, 100, seen), { size: 100, max: 1000 })).toHaveLength(250);
  expect(seen).toEqual([0, 1, 2]);
});

test('한 쪽으로 끝나면 더 부르지 않는다', async () => {
  const seen: number[] = [];
  expect(await collectAllIds(pager(30, 100, seen), { size: 100, max: 200 })).toEqual(
    Array.from({ length: 30 }, (_, i) => i + 1),
  );
  expect(seen).toEqual([0]);
});

test('상한에서 끊고 그 뒤 쪽은 부르지 않는다', async () => {
  const seen: number[] = [];
  const ids = await collectAllIds(pager(1000, 100, seen), { size: 100, max: 200 });
  expect(ids).toHaveLength(200);
  // 목록과 같은 정렬이라 남는 것은 앞쪽(최근) — 잘리는 것은 뒤쪽(오래된 것)이다
  expect(ids[0]).toBe(1);
  expect(ids.at(-1)).toBe(200);
  expect(seen).toEqual([0, 1]);
});

test('쪽이 상한에 딱 맞게 떨어져도 한 쪽 더 부르지 않는다', async () => {
  const seen: number[] = [];
  expect(await collectAllIds(pager(200, 100, seen), { size: 100, max: 200 })).toHaveLength(200);
  expect(seen).toEqual([0, 1]);
});

test('한 쪽이라도 못 받으면 반쯤 고른 채로 성공하지 않는다', async () => {
  await expect(
    collectAllIds(async (index) => {
      if (index === 1) throw new Error('거래를 불러오지 못했습니다');
      return { ids: [1, 2, 3], totalPages: 3 };
    }, { size: 3, max: 200 }),
  ).rejects.toThrow('거래를 불러오지 못했습니다');
});
