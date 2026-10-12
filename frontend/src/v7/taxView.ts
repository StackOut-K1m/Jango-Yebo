import { formatMoney } from '@jangoyebo/core';
import type {
  IndustryType,
  TaxDraftDetail,
  TaxDraftSummary,
  TaxDraftField,
  TaxDraftStatus,
  TaxFieldSource,
  TaxFieldStatus,
  TaxFilingForm,
  TaxFormFieldDefinition,
  TaxFormSupportLevel,
  TaxReviewStatus,
} from '@jangoyebo/core';

/**
 * 신고자료 검토(SC-07-7)·신고서 초안(SC-07-8)의 표시 규칙.
 *
 * 세무 값은 확정하지 않는다(도메인 규칙 4) — 라벨도 「경비 인정」이 아니라 「확인 필요」·「초안」으로 쓴다.
 */

/** 8.7 한글 라벨은 명세 표 그대로. 색만으로 말하지 않도록 그림을 함께 둔다(도메인 규칙 6) */
export const TAX_REVIEW: Record<TaxReviewStatus, { label: string; icon: string; tone: 'good' | 'warn' | 'muted' }> = {
  // 「미검토」라 부르면 할 일이 남은 것처럼 읽힌다. 실제로는 표시가 없을 뿐이고 금액에 그대로 들어간다
  UNREVIEWED: { label: '표시 없음', icon: 'clock', tone: 'muted' },
  EVIDENCE_CONFIRMED: { label: '증빙 확인 완료', icon: 'check', tone: 'good' },
  EVIDENCE_MISSING: { label: '증빙 누락', icon: 'warning', tone: 'warn' },
  BUSINESS_USE_UNCLEAR: { label: '사업 관련성 확인 필요', icon: 'info', tone: 'warn' },
  PERSONAL_SUSPECTED: { label: '개인 사용 가능성 있음', icon: 'info', tone: 'warn' },
  DUPLICATE_SUSPECTED: { label: '중복 등록 가능성 있음', icon: 'info', tone: 'warn' },
  EXPERT_REVIEW: { label: '세무전문가 확인 필요', icon: 'info', tone: 'warn' },
  ASSET_REVIEW: { label: '자산·감가상각 검토 필요', icon: 'info', tone: 'warn' },
};

/**
 * 사람이 고를 수 있는 값. 증빙 두 값은 증빙 연결로 **자동으로** 정해지고(서버가 400),
 * 자산 검토는 값만 예약돼 있다(화면 P2). 미검토는 판단을 되돌리는 길이다.
 */
export const ASSIGNABLE_REVIEW = [
  'BUSINESS_USE_UNCLEAR',
  'PERSONAL_SUSPECTED',
  'DUPLICATE_SUSPECTED',
  'EXPERT_REVIEW',
  'UNREVIEWED',
] as const satisfies readonly TaxReviewStatus[];
export type AssignableReview = (typeof ASSIGNABLE_REVIEW)[number];

/** 목록 필터 묶음. 서버는 쉼표로 여러 값을 받는다 */
export const REVIEW_FILTERS: { id: string; label: string; statuses: TaxReviewStatus[] }[] = [
  { id: 'all', label: '전체', statuses: [] },
  { id: 'unreviewed', label: '표시 없음', statuses: ['UNREVIEWED'] },
  { id: 'missing', label: '증빙 누락', statuses: ['EVIDENCE_MISSING'] },
  {
    id: 'check',
    label: '확인 필요',
    statuses: ['BUSINESS_USE_UNCLEAR', 'PERSONAL_SUSPECTED', 'DUPLICATE_SUSPECTED', 'EXPERT_REVIEW'],
  },
  { id: 'confirmed', label: '증빙 확인 완료', statuses: ['EVIDENCE_CONFIRMED'] },
];

/**
 * 초안 금액에서 빠지는 검토 상태(서버 TaxDraftAggregator — 개인 사용·판단 대기). 검토는 **예외만 표시**하는
 * 일이다: 표시가 없거나 증빙 두 값이면 금액에 들어간다. 그래서 「검토율」을 진행률처럼 보여 주지 않는다 —
 * 올바르게 쓸수록(이상한 것만 표시할수록) 낮게 나와 할 일이 남은 것처럼 읽힌다.
 */
const EXCLUDED_BY_REVIEW = new Set<TaxReviewStatus>([
  'PERSONAL_SUSPECTED',
  'BUSINESS_USE_UNCLEAR',
  'DUPLICATE_SUSPECTED',
  'EXPERT_REVIEW',
]);
export function excludedByReview(status: TaxReviewStatus): boolean {
  return EXCLUDED_BY_REVIEW.has(status);
}

/** 검토에서 표시해 초안에서 뺀 거래의 합. 분류 전 거래는 판단이 아니라서 넣지 않는다. 없으면 null */
export function markedExclusion(excluded: TaxDraftDetail['excluded']): { count: number; amount: number } | null {
  const parts = [excluded?.personalSuspected, excluded?.needsReview].filter((p) => p !== undefined);
  const count = parts.reduce((n, p) => n + p.count, 0);
  return count ? { count, amount: parts.reduce((n, p) => n + p.amount, 0) } : null;
}

/**
 * 초안 목록을 서식마다 최신 하나와 나머지로 가른다. 최신은 draftId 가 가장 큰 것 — 서버가 재생성 때 값을
 * 물려받을 초안을 고르는 기준(OrderByIdDesc)과 같다. 둘 다 최근 것부터.
 */
export function splitDrafts(list: TaxDraftSummary[]): { latest: TaxDraftSummary[]; older: TaxDraftSummary[] } {
  const sorted = [...list].sort((a, b) => b.draftId - a.draftId);
  const seen = new Set<string>();
  const latest: TaxDraftSummary[] = [];
  const older: TaxDraftSummary[] = [];
  for (const d of sorted) {
    (seen.has(d.formType) ? older : latest).push(d);
    seen.add(d.formType);
  }
  return { latest, older };
}

export const DRAFT_STATUS: Record<TaxDraftStatus, { label: string; icon: string; tone: 'good' | 'warn' | 'muted' }> = {
  PROCESSING: { label: '집계 중', icon: 'clock', tone: 'muted' },
  READY: { label: '초안 생성 완료', icon: 'check', tone: 'good' },
  STALE: { label: '자료 변경 · 다시 만들기 필요', icon: 'warning', tone: 'warn' },
  FAILED: { label: '만들지 못함', icon: 'close', tone: 'warn' },
};

export const FIELD_STATUS: Record<TaxFieldStatus, { label: string; icon: string; tone: 'good' | 'warn' | 'muted' }> = {
  FILLED: { label: '채움', icon: 'check', tone: 'good' },
  NEEDS_REVIEW: { label: '검토 필요', icon: 'warning', tone: 'warn' },
  EMPTY: { label: '비어 있음', icon: 'info', tone: 'muted' },
};

export const FIELD_SOURCE: Record<TaxFieldSource, string> = {
  AUTO_TRANSACTION: '거래 집계',
  AUTO_SETTLEMENT: '정산 대사',
  // 성명·전화번호는 내 정보에서, 업종·사업자등록번호는 사업장에서 온다
  AUTO_PROFILE: '사업장·내 정보',
  REFERENCE_HOMETAX: '홈택스 참고값',
  USER_SENSITIVE: '직접 입력(저장 안 함)',
  USER_CONFIRM: '직접 확인',
  CALCULATED: '서식 산식',
};

export const SUPPORT_LEVEL: Record<TaxFormSupportLevel, { label: string; canDraft: boolean }> = {
  DRAFT: { label: '초안 작성', canDraft: true },
  DRAFT_OPTIONAL: { label: '초안 작성(선택)', canDraft: true },
  GUIDE_ONLY: { label: '안내만 제공', canDraft: false },
  CHECKLIST_ONLY: { label: '서류 목록만 제공', canDraft: false },
};

/**
 * 신고안내문의 기장의무. 서비스는 판정하지 않고 사용자가 옮겨 적는다(SRS 6.10 · API 14.3
 * `hometaxReference.bookkeepingDuty`). 유형마다 쓰는 서식이 달라 안내가 갈린다.
 */
export type BookkeepingChoice = 'SIMPLE_LEDGER' | 'DOUBLE_ENTRY' | 'UNKNOWN';
export const BOOKKEEPING_CHOICES: { id: BookkeepingChoice; label: string }[] = [
  { id: 'SIMPLE_LEDGER', label: '간편장부' },
  { id: 'DOUBLE_ENTRY', label: '복식부기' },
  { id: 'UNKNOWN', label: '모르겠어요' },
];

/** 신고를 누가 하느냐. 장부 유형과 따로 묻는다 — 간편장부도 회계사에게, 복식부기도 직접 할 수 있다 */
export type Filer = 'SELF' | 'ACCOUNTANT';

/**
 * 고른 신고 방식을 사업장별로 기억한다.
 *
 * 목록 화면의 지역 상태로만 두면 <b>초안 상세로 넘어가는 순간 사라진다</b> — 「직접 신고」를
 * 골라 놓고도 상세 화면은 그 사실을 몰라 회계사에게 넘길 때와 똑같은 화면을 보여 주고,
 * 옮겨 적기로 가려면 단계 4를 따로 찾아 눌러야 했다. 고른 것이 목적지까지 따라가야 한다.
 *
 * 서버에 둘 값은 아니다 — 언제든 바꿀 수 있는 화면 취향이고, 신고 결과에 영향을 주지 않는다.
 * 언제든 바꿀 수 있는 화면 취향이라 기기에만 남긴다.
 */
const filerKey = (businessId: number) => `jangoyebo:tax-filer:${businessId}`;

export function readFiler(businessId: number | undefined): Filer | null {
  if (businessId === undefined) return null;
  try {
    const raw = localStorage.getItem(filerKey(businessId));
    return raw === 'SELF' || raw === 'ACCOUNTANT' ? raw : null;
  } catch {
    // 사생활 보호 모드·저장소 차단에서 던진다. 기억 못 하는 것은 화면이 조금 덜 친절해질 뿐이다
    return null;
  }
}

export function writeFiler(businessId: number | undefined, filer: Filer | null): void {
  if (businessId === undefined) return;
  try {
    if (filer === null) localStorage.removeItem(filerKey(businessId));
    else localStorage.setItem(filerKey(businessId), filer);
  } catch {
    /* 위와 같다 */
  }
}
export const FILER_CHOICES: { id: Filer; label: string }[] = [
  { id: 'SELF', label: '직접 신고' },
  { id: 'ACCOUNTANT', label: '회계사에게 맡김' },
];

/**
 * 복식부기 직접 신고 — 홈택스 입력 순서. 서비스는 재무제표를 채우지 않는다(2026-09-18 팀 결정:
 * 안내까지만, 참고값은 다음 단계, 작성 지원은 하지 않음 · SRS 1.3).
 */
export const DOUBLE_ENTRY_STEPS = [
  '표준재무상태표 — 연말 기준 자산·부채·자본',
  '표준손익계산서 — 매출과 비용의 계정별 합계',
  '합계잔액시산표',
  '조정계산서 — 세법 기준으로 장부 금액을 고친 내역',
  '종합소득세 신고서 — 소득공제·세액공제를 넣고 제출',
];

/** 복식부기 직접 신고 전에 준비할 값 */
export const DOUBLE_ENTRY_PREP = [
  '연말 기준 현금·예금 잔액',
  '남은 재고(식자재 등)',
  '설비·인테리어 취득가와 감가상각',
  '대출 등 빌린 돈의 연말 잔액',
  '아직 치르지 않은 돈(미지급금)',
  '매출과 비용의 계정별 합계',
];

/**
 * 업종별 간편장부 기준(직전 연도 수입금액 · 소득세법 시행령 제208조). 우리 업종 분류로 기준이
 * 하나로 정해지지 않으면(기타) null — 매출 질문을 하지 않는다.
 */
export function ledgerThresholdLabel(industry: IndustryType): string | null {
  if (industry.startsWith('FOOD_')) return '1억 5천만원';
  if (industry === 'RETAIL') return '3억원';
  if (industry === 'SERVICE') return '7,500만원';
  return null;
}

export type RevenueBand = 'BELOW' | 'ABOVE' | 'UNKNOWN';
export interface DutyAnswers {
  professional?: boolean;
  newlyOpened?: boolean;
  revenue?: RevenueBand;
}

/**
 * 답으로 본 **가능성이 높은** 유형. 판정이 아니다(SRS 6.10 · 도메인 규칙 4) — 화면은 늘 홈택스에서
 * 확인하라는 말과 함께 보여 준다. 답이 모자라거나 매출을 모르면 null.
 *
 * 전문직은 개업 첫해에도 복식부기라 가장 먼저 본다.
 */
export function likelyDuty(a: DutyAnswers): 'SIMPLE_LEDGER' | 'DOUBLE_ENTRY' | null {
  if (a.professional === undefined) return null;
  if (a.professional) return 'DOUBLE_ENTRY';
  if (a.newlyOpened === undefined) return null;
  if (a.newlyOpened) return 'SIMPLE_LEDGER';
  if (a.revenue === 'BELOW') return 'SIMPLE_LEDGER';
  if (a.revenue === 'ABOVE') return 'DOUBLE_ENTRY';
  return null;
}

/**
 * 초안을 만들 수 있는 서식. 지원 수준이 초안이어도 서버가 칸 정의(sections)를 아직 안 채웠으면
 * 만들 수 없다 — 기준경비율(DRAFT_OPTIONAL)이 그렇다. 만들면 빈 초안이 생긴다.
 */
export function canDraftForm(form: TaxFilingForm): boolean {
  return SUPPORT_LEVEL[form.supportLevel].canDraft && (form.sections?.length ?? 0) > 0;
}

/** 근거 거래를 조회할 수 있는 출처. 사용자가 넣는 항목은 근거가 빈 목록이라 버튼을 두지 않는다 */
export function hasBasis(field: TaxDraftField): boolean {
  return field.source === 'AUTO_TRANSACTION' || field.source === 'AUTO_SETTLEMENT';
}

/** 사용자가 값을 넣는 항목. 산식·민감 항목은 PATCH 로 못 바꾼다(서버 400) */
export function isEditable(field: TaxDraftField): boolean {
  // PATCH overrides는 금액만 받는다. 소재지에 숫자 입력창을 띄우지 않는다.
  return field.source === 'USER_CONFIRM' && field.fieldKey !== 'BIZ_ADDRESS';
}

/**
 * 비어 있는 항목을 어디서 채우는지. 항목마다 채우는 곳이 다르다 — 전화번호는 내 정보, 주소지·생년월일은
 * 저장하지 않고 내보낼 때만, 주업종코드는 초안을 만들 때(서버가 생성 시점에만 읽는다).
 * `anchor` 는 내보내기 칸의 입력 id 다.
 */
export interface EmptyFieldGuide {
  help: string;
  action?: { label: string; to?: string; anchor?: string };
}
export function emptyFieldGuide(fieldKey: string): EmptyFieldGuide | null {
  switch (fieldKey) {
    case 'PHONE':
      return {
        help: '마이페이지 「내 정보」의 휴대폰 번호에서 가져옵니다. 입력한 뒤 초안을 다시 만들면 채워지고, 비워 두면 파일에 「직접 기재」로 남습니다.',
        action: { label: '마이페이지에서 입력', to: '/mypage' },
      };
    case 'INDUSTRY_CODE':
      return {
        help: '홈택스 신고도움 서비스에서 확인하는 값입니다. 초안을 만들 때 적으면 채워지고, 비워 두면 파일에 「직접 기재」로 남습니다.',
      };
    case 'BIZ_ADDRESS':
      return { help: '사업장 주소는 이 화면에서 저장할 수 없습니다. 파일을 받은 뒤 해당 칸에 직접 적어 주세요.' };
    case 'HOME_ADDRESS':
    case 'BIRTH_DATE':
      return {
        help: '저장하지 않는 개인정보입니다. 파일을 받을 때 아래 칸에 적으면 그 파일에만 들어갑니다.',
        action: { label: '파일에 넣을 값 적기', anchor: `sensitive-${fieldKey}` },
      };
    default:
      return null;
  }
}

/**
 * 비어 있는 항목이 <b>어떤 종류의 빈칸인가.</b>
 *
 * 「비어 있음」 하나로 덮으면 여덟 줄이 모두 같은 말이 된다 — 실제로는 셋이 섞여 있다.
 * 그런 지출이 있을 때만 적는 칸, 비워 두면 서식에 「직접 기재」로 남는 칸, 이 화면에서는
 * 저장할 수 없어 받은 파일에 손으로 적는 칸.
 *
 * ⚠ 판정은 <b>무슨 일이 일어나는가</b>로만 한다. 「안 써도 된다」처럼 들리는 말은 쓰지
 * 않는다 — 어떤 항목이 신고에 꼭 필요한지는 사장님과 세무전문가가 정할 일이고,
 * 여기서 확정하면 도메인 규칙 4를 어긴다.
 */
export type FieldFillKind = 'IF_APPLICABLE' | 'LEFT_BLANK_NOTED' | 'ON_PAPER';
export const FILL_KIND: Record<FieldFillKind, { label: string; icon: string; tone: 'good' | 'warn' | 'muted'; help: string }> = {
  IF_APPLICABLE: {
    label: '해당 있으면 입력',
    icon: 'plus',
    tone: 'warn',
    help: '그런 지출이 있었다면 금액을 적습니다. 없었던 것을 확인했다면 비워 두세요.',
  },
  LEFT_BLANK_NOTED: {
    label: '비우면 「직접 기재」',
    icon: 'info',
    tone: 'muted',
    help: '비워 두어도 파일은 나옵니다. 그 칸은 「직접 기재」로 남습니다.',
  },
  ON_PAPER: {
    label: '받은 파일에 직접',
    icon: 'fold',
    tone: 'muted',
    help: '이 화면에서는 저장할 수 없는 칸입니다. 파일을 받은 뒤 해당 칸에 적어 주세요.',
  },
};

/** 서식에 「직접 기재」로 남는 칸. `emptyFieldGuide` 의 안내문과 같은 사실을 가리킨다 */
const BLANK_NOTED = new Set(['PHONE', 'INDUSTRY_CODE']);
/** 화면에서 저장하지 않는 칸 — 주소·생년월일은 파일에만 들어간다 */
const ON_PAPER = new Set(['BIZ_ADDRESS', 'HOME_ADDRESS', 'BIRTH_DATE']);

/**
 * 재고 두 칸의 `fieldKey`. 서식 작성방법 4 가 둘을 해마다 이어 붙인다 —
 * <b>올해 기초재고액 = 작년 기말재고액</b>.
 *
 * 기말은 우리가 알 수 없다(계좌는 매입을 보지만 남은 양을 모른다 — 재고 실사 값이다).
 * 기초는 작년 초안에 사용자가 적어 둔 기말을 그대로 쓰면 되므로 <b>후보로 제시할 수 있다.</b>
 * 다만 자동으로 채우지 않는다 — 세무 값은 확정하지 않고 기준연도·출처와 함께 후보로만
 * 내놓는다(도메인 규칙 4). 작년에 폐업·개업이 섞였거나 작년 값 자체가 틀렸을 수 있다.
 */
export const OPENING_INVENTORY = 'OPENING_INVENTORY';
export const CLOSING_INVENTORY = 'CLOSING_INVENTORY';

/**
 * 초안에서 기말재고액을 꺼낸다. 없거나 숫자가 아니면 `undefined` —
 * <b>0 과 「안 적음」을 섞지 않는다.</b> 0 원은 「재고가 없었다」는 사장님의 답이고,
 * 없는 것은 아직 답하지 않은 것이다. 둘을 같게 다루면 안 적은 해의 값을 0 으로 이어받는다.
 */
export function closingInventoryOf(draft: Pick<TaxDraftDetail, 'sections'> | undefined): number | undefined {
  const field = draft?.sections.flatMap((section) => section.fields).find((f) => f.fieldKey === CLOSING_INVENTORY);
  return typeof field?.value === 'number' ? field.value : undefined;
}

/** 비어 있지 않으면 `null` — 채운 칸에는 이 구분이 필요 없다 */
export function fieldFillKind(field: TaxDraftField): FieldFillKind | null {
  if (field.status !== 'EMPTY') return null;
  if (ON_PAPER.has(field.fieldKey)) return 'ON_PAPER';
  if (BLANK_NOTED.has(field.fieldKey)) return 'LEFT_BLANK_NOTED';
  // 금액을 넣을 수 있는 칸은 「그런 지출이 있었나」를 묻는 칸이다
  return isEditable(field) ? 'IF_APPLICABLE' : null;
}

/** 파일에만 적는 개인정보는 내보내기 단계에서 안내한다. 빈 금액을 0으로 간주하지 않는다. */
export function needsFieldAttention(field: TaxDraftField): boolean {
  if (field.source === 'USER_SENSITIVE') return false;
  return field.status === 'EMPTY' || (field.status === 'NEEDS_REVIEW' && !field.confirmed);
}

/** 숫자지만 금액이 아닌 항목 — ⑩ 소득종류는 코드(사업소득 40)다 */
const CODE_FIELDS = new Set(['INCOME_TYPE']);

/** ⑥ 업종은 서버가 코드로 준다. 서식에는 이름을 적는다 */
const INDUSTRY_LABEL: Record<IndustryType, string> = {
  FOOD_RESTAURANT: '음식점',
  FOOD_CAFE: '카페',
  FOOD_BAKERY: '베이커리',
  FOOD_OTHER: '기타 요식업',
  RETAIL: '소매',
  SERVICE: '서비스',
  OTHER: '기타',
};

/** 항목 값. 금액은 원, 코드·인적사항 같은 글자는 그대로, 없으면 — */
export function fieldValueLabel(value: TaxDraftField['value'], fieldKey?: string): string {
  if (typeof value === 'number') return fieldKey && CODE_FIELDS.has(fieldKey) ? String(value) : formatMoney(value);
  if (typeof value === 'string' && value.trim()) {
    return fieldKey === 'INDUSTRY' ? (INDUSTRY_LABEL[value as IndustryType] ?? value) : value;
  }
  return '—';
}

/** 귀속연도의 기간. 올해는 오늘까지만 잡는다 — 오지 않은 날의 거래는 없다 */
export function yearPeriod(year: number, today: string): { from: string; to: string } {
  const end = `${year}-12-31`;
  return { from: `${year}-01-01`, to: end < today ? end : today };
}

/** 내보낼 때 물어볼 민감 항목. 상세 응답에는 키가 없어 서식 정의에서 찾는다 */
export function sensitiveFields(form: TaxFilingForm | undefined): TaxFormFieldDefinition[] {
  return (form?.sections ?? []).flatMap((s) => s.fields).filter((f) => f.sensitive || f.source === 'USER_SENSITIVE');
}

/** 입력칸의 「1,000,000」을 숫자로. 비우면 null(비움), 숫자가 아니면 NaN */
export function parseWon(text: string): number | null {
  const bare = text.replace(/[,\s원]/g, '');
  if (bare === '') return null;
  return /^-?\d+$/.test(bare) ? Number(bare) : Number.NaN;
}

/**
 * 쪽이 나뉜 목록에서 <b>조건에 맞는 것 전부</b>의 id 를 모은다 — 「모두 고르기」가 쓴다.
 *
 * 보고 있는 쪽만 고르면, 다 골랐다고 믿고 적용한 뒤 다음 쪽이 그대로 남는다. 쪽이 넘어가는
 * 목록에서 「모두」는 쪽이 아니라 <b>조건</b>을 뜻해야 한다.
 *
 * 부르는 쪽이 이미 조건을 쥐고 있으므로 여기서는 쪽 번호만 넘긴다. 목록과 <b>같은 정렬</b>로
 * 받아야 `max` 에 걸려 잘리는 것이 화면 맨 아래, 즉 사람이 덜 급하다고 보는 쪽이 된다.
 *
 * @param page 한 쪽을 받아 오는 함수. 못 받으면 던진다 — 반쯤 고른 채로 성공한 척하지 않는다
 * @param size 한 쪽에 받는 건수. 덜 오면 마지막 쪽이다
 * @param max  모을 상한. 서버가 한 번에 바꿔 주는 수와 같아야 골라 놓고 못 바꾸는 일이 없다
 */
export async function collectAllIds(
  page: (index: number) => Promise<{ ids: number[]; totalPages: number }>,
  /**
   * `max` 를 주면 그만큼만 모은다. <b>비우면 끝까지</b> 모은다 — 일괄 변경은 200건씩 나눠
   * 보내면 되므로 고르는 쪽에는 상한이 필요 없다(구현현황 3.30: 200 은 <b>요청당</b> 상한이다).
   */
  { size, max }: { size: number; max?: number },
): Promise<number[]> {
  const ids: number[] = [];
  for (let index = 0; max === undefined || ids.length < max; index++) {
    const got = await page(index);
    ids.push(...got.ids);
    if (index + 1 >= got.totalPages || got.ids.length < size) break;
  }
  return max === undefined ? ids : ids.slice(0, max);
}
