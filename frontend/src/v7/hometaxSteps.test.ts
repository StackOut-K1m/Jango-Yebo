import { expect, test } from 'vitest';
import type { TaxDraftDetail, TaxDraftField } from '@jangoyebo/core';
import { CHECKLIST_STEPS, copyText, isSelfStep, resolveSteps } from './hometaxSteps';

const field = (fieldKey: string, value?: TaxDraftField['value']): TaxDraftField => ({
  fieldKey,
  label: `㉝ ${fieldKey}`,
  value,
});

const draft = (sections: { sectionKey: string; fields: TaxDraftField[] }[]): TaxDraftDetail =>
  ({
    draftId: 1,
    taxYear: 2026,
    formType: 'SIMPLE_LEDGER',
    status: 'READY',
    periodStart: '2026-01-01',
    periodEnd: '2026-12-31',
    sections: sections.map((s) => ({ ...s, label: s.sectionKey })),
    disclaimer: '초안입니다',
  }) as TaxDraftDetail;

// 부표를 먼저 채워야 본표 필요경비가 맞는다 — 순서가 뒤집히면 옮겨 적는 사람이 두 번 일한다
test('단계는 기본사항에서 시작해 제출로 끝난다', () => {
  expect(CHECKLIST_STEPS.map((s) => s.id)).toEqual([
    'IDENTITY',
    'BUSINESS',
    'LEDGER',
    'APPENDIX',
    'DEDUCTION',
    'TAX_CREDIT',
    'SUBMIT',
  ]);
});

// 우리가 계산하지 않는 묶음은 줄만 보여 준다(도메인 규칙 2·4)
test('직접 입력 단계는 옮겨 적을 칸을 갖지 않는다', () => {
  const self = CHECKLIST_STEPS.filter(isSelfStep);
  expect(self.map((s) => s.id)).toEqual(['DEDUCTION', 'TAX_CREDIT', 'SUBMIT']);
  expect(self.every((s) => s.hint.length > 0)).toBe(true);
});

test('초안의 섹션이 단계에 나뉘어 담긴다', () => {
  const steps = resolveSteps(
    draft([
      { sectionKey: 'IDENTITY', fields: [field('NAME', '정유선')] },
      { sectionKey: 'REVENUE', fields: [field('SALES', 14_400_000)] },
      { sectionKey: 'EXPENSE', fields: [field('RENT', 9_000_000), field('PLATFORM_FEE', 120_000)] },
      { sectionKey: 'INCOME', fields: [field('NET_INCOME', 31_250_000)] },
    ]),
  );
  const byId = new Map(steps.map((s) => [s.step.id, s]));
  expect(byId.get('IDENTITY')!.fieldCount).toBe(1);
  // 부표는 매출·매출원가·일반관리비를 함께 품는다 — 매출원가가 없는 초안도 그대로 선다
  expect(byId.get('APPENDIX')!.sections.map((s) => s.sectionKey)).toEqual(['REVENUE', 'EXPENSE']);
  expect(byId.get('APPENDIX')!.fieldCount).toBe(3);
  expect(byId.get('LEDGER')!.fieldCount).toBe(1);
  expect(byId.get('DEDUCTION')!.fieldCount).toBe(0);
});

// 서식이 모르는 항목은 서버가 UNMAPPED 로 보낸다. 조용히 빠지면 홈택스에서 그 칸을 빠뜨린다
test('어느 단계에도 못 들어간 섹션은 맨 뒤에 남는다', () => {
  const steps = resolveSteps(
    draft([
      { sectionKey: 'INCOME', fields: [field('NET_INCOME', 1)] },
      { sectionKey: 'UNMAPPED', fields: [field('MYSTERY', 2)] },
    ]),
  );
  const last = steps.at(-1)!;
  expect(last.step.id).toBe('REST');
  expect(last.sections.map((s) => s.sectionKey)).toEqual(['UNMAPPED']);
  expect(last.fieldCount).toBe(1);
});

test('남는 섹션이 없으면 그 밖의 항목 단계도 없다', () => {
  const steps = resolveSteps(draft([{ sectionKey: 'INCOME', fields: [field('NET_INCOME', 1)] }]));
  expect(steps.some((s) => s.step.id === 'REST')).toBe(false);
  expect(steps).toHaveLength(CHECKLIST_STEPS.length);
});

// 붙여넣은 뒤에 빈 것을 알게 하지 않는다. 금액은 자릿점을 빼야 홈택스 숫자칸에 그대로 들어간다
test('복사 글자는 자릿점을 빼고, 빈 값은 복사시키지 않는다', () => {
  expect(copyText(field('SALES', 14_400_000))).toBe('14400000');
  expect(copyText(field('NET', 0))).toBe('0');
  expect(copyText(field('NAME', '정유선'))).toBe('정유선');
  expect(copyText(field('NAME', '  정유선  '))).toBe('정유선');
  expect(copyText(field('HOME_ADDRESS'))).toBeNull();
  expect(copyText(field('HOME_ADDRESS', null))).toBeNull();
  expect(copyText(field('HOME_ADDRESS', '   '))).toBeNull();
});
