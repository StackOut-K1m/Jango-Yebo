import type { TaxDraftDetail, TaxDraftField, TaxFormSection } from '@jangoyebo/core';

/**
 * 홈택스 직접 신고 따라하기의 단계 지도 (S15P21C105-264).
 *
 * **칸 목록을 여기 적지 않는다.** 초안 상세가 `sections[].fields[]` 로 칸 번호가 붙은 `label` 과
 * 값·출처·상태를 다 준다(`"⑱ 세무조정 후 필요경비 (⑮−⑯+⑰)"`). 여기서 정하는 것은 **그 섹션들을
 * 어떤 순서로 묶어 보여 줄지**와, **우리가 아예 채우지 않는 묶음이 무엇인지**뿐이다.
 *
 * ⚠ **순서를 새로 짓지 않았다.** 홈택스 화면의 실제 입력 순서는 확인할 길이 없고, 확인 안 된
 * 세법 사실을 화면에 내보내지 않는 것이 팀 규칙이다(도메인 규칙 4 · V36 선례). 그래서 팀이 이미
 * 화면에 써 둔 `SimpleLedgerSelfGuide` 의 다섯 단계를 그대로 따른다 — 사업소득명세서 → 본표 →
 * 부표 → 소득공제·세액공제 → 제출.
 *
 * ⚠ **나중에 백엔드 서식 정의로 옮긴다.** 팀이 2026-09-18 에 서식 문구와 복식부기 서류 목록을
 * 화면 상수에서 서식 정의로 옮겼다(`V36`·`V37`) — 웹과 앱이 같은 목록을 써야 한다는 이유였다.
 * 이 파일도 같은 이유로 결국 그쪽에 가야 한다. 지금 상수로 두는 것은 화면을 먼저 세우려는
 * 임시 조치다.
 */

/** 우리가 값을 채우는 단계 — 초안의 섹션을 묶는다 */
export interface FilledStep {
  id: string;
  title: string;
  /** 이 단계가 품는 `sectionKey`. 초안에 없는 키는 조용히 건너뛴다 */
  sections: string[];
  hint?: string;
  self?: false;
}

/** 우리가 계산하지 않는 단계 — 줄은 보여 주되 「홈택스에서 직접 입력」이라고 적는다 */
export interface SelfStep {
  id: string;
  title: string;
  self: true;
  hint: string;
}

export type ChecklistStep = FilledStep | SelfStep;

/** 우리가 채우지 않는 단계인가. `filter` 가 좁혀 주지 않아 술어로 둔다 */
export const isSelfStep = (step: ChecklistStep): step is SelfStep => step.self === true;

/**
 * ⚠ **칸 번호를 붙이지 않는다.**
 *
 * 우리가 채우는 칸의 번호는 서버가 준 `label` 에 이미 들어 있다. 반대로 소득공제·세액공제는
 * 별지 제74호서식 밖이라 우리 서식 정의에 없고, 번호를 여기서 지어 붙이면 **확인하지 않은 세법
 * 사실을 화면에 내보내는 것**이 된다. 묶음 이름과 「직접 입력」까지만 적는다.
 */
export const CHECKLIST_STEPS: ChecklistStep[] = [
  {
    id: 'IDENTITY',
    title: '기본사항',
    sections: ['IDENTITY'],
    // ⚠ 「초안에 적은 값을 여기서도 보여 준다」고 쓰지 않는다 — 그런 길이 없다. 민감 항목은
    //    초안 화면의 화면 상태로만 잠깐 있다가 파일을 받으면 지워지고 서버에 남지 않는다.
    hint: '주소지·생년월일은 저장하지 않아 여기에도 나오지 않습니다. 홈택스 화면에 직접 적어 주세요 — 파일로 받을 때 초안 화면에서 한 번 적으면 그 파일에만 들어갑니다.',
  },
  {
    id: 'BUSINESS',
    title: '사업소득명세서',
    sections: ['BUSINESS'],
    hint: '장부 유형을 「간편장부」로 고른 뒤 아래 값을 옮겨 적습니다.',
  },
  {
    id: 'LEDGER',
    title: '간편장부소득금액계산서 (본표)',
    sections: ['ADJUSTMENT', 'INCOME'],
    hint: '세무조정과 기부금은 해당이 없으면 0 그대로 둡니다.',
  },
  {
    id: 'APPENDIX',
    title: '부표 — 총수입금액 및 필요경비명세서',
    sections: ['REVENUE', 'COST_OF_SALES', 'EXPENSE'],
    hint: '본표의 필요경비는 이 표의 합계에서 옵니다. 여기를 먼저 채워야 본표 숫자가 맞습니다.',
  },
  {
    id: 'DEDUCTION',
    title: '소득공제',
    self: true,
    hint: '서비스가 계산하지 않습니다. 인적공제·연금보험료 같은 항목은 사업 거래로 알 수 없어 홈택스에서 직접 입력하세요.',
  },
  {
    id: 'TAX_CREDIT',
    title: '세액공제·감면과 기납부세액',
    self: true,
    hint: '서비스가 계산하지 않습니다. 세액은 소득금액까지만 채우는 것이 이 서비스의 범위입니다 — 홈택스에서 직접 입력하세요.',
  },
  {
    id: 'SUBMIT',
    title: '제출',
    self: true,
    hint: '옮겨 적은 값을 한 번 더 보고 제출합니다. 최종 판단은 사장님 또는 세무전문가가 합니다.',
  },
];

/** 단계 하나가 실제로 그릴 내용 */
export interface ResolvedStep {
  step: ChecklistStep;
  sections: TaxFormSection<TaxDraftField>[];
  /** 옮겨 적을 값이 하나라도 있나. 없으면 「직접 입력」 안내만 그린다 */
  fieldCount: number;
}

/**
 * 초안의 섹션을 단계에 나눠 담는다.
 *
 * **어느 단계에도 못 들어간 섹션을 버리지 않는다.** 서식이 모르는 항목은 서버가 `UNMAPPED` 로
 * 보내고(14.3 타입 주석), 서식이 개정되면 새 `sectionKey` 가 생긴다. 조용히 사라지면 **화면에
 * 없는 칸을 사장님이 홈택스에서 빠뜨린다** — 남은 것은 맨 뒤에 한 단계로 모은다.
 */
export function resolveSteps(draft: TaxDraftDetail): ResolvedStep[] {
  const byKey = new Map(draft.sections.map((s) => [s.sectionKey, s]));
  const used = new Set<string>();

  const steps: ResolvedStep[] = CHECKLIST_STEPS.map((step) => {
    const sections = step.self
      ? []
      : step.sections.flatMap((key) => {
          const found = byKey.get(key);
          if (!found) return [];
          used.add(key);
          return [found];
        });
    return { step, sections, fieldCount: sections.reduce((n, s) => n + s.fields.length, 0) };
  });

  const leftover = draft.sections.filter((s) => !used.has(s.sectionKey));
  if (leftover.length === 0) return steps;

  return [
    ...steps,
    {
      step: {
        id: 'REST',
        title: '그 밖의 항목',
        sections: leftover.map((s) => s.sectionKey),
        hint: '단계에 넣지 못한 항목입니다. 서식이 개정되었거나 새로 생긴 칸일 수 있으니 홈택스 화면에서 자리를 찾아 적어 주세요.',
      },
      sections: leftover,
      fieldCount: leftover.reduce((n, s) => n + s.fields.length, 0),
    },
  ];
}

/**
 * 옮겨 적을 때 복사할 글자.
 *
 * 금액은 **자릿점을 빼고** 준다 — 홈택스 입력칸이 숫자만 받는 곳이 있고, 붙여넣은 뒤 쉼표를
 * 지우게 하면 복사 버튼을 둔 뜻이 없다. 글자 항목은 그대로 준다.
 *
 * 값이 없으면 `null` 이다. 빈 글자를 복사시키면 **붙여넣고 나서야 빈 것을 알게 된다.**
 */
export function copyText(field: TaxDraftField): string | null {
  const value = field.value;
  if (typeof value === 'number') return String(value);
  if (typeof value === 'string' && value.trim()) return value.trim();
  return null;
}
