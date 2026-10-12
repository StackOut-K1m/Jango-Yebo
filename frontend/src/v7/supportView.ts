import { formatMoney } from '@jangoyebo/core';
import type { ProgramStatus, RecommendationItem, SupportProgramQuery, SupportProgramRecommendations, SupportType } from '@jangoyebo/core';

/** 추천 응답(최대 20건) 안에서 공통 검색 조건만 적용하고 서버의 추천 순서는 유지한다. */
export function filterRecommendations(items: RecommendationItem[], query: Pick<SupportProgramQuery, 'q' | 'supportType'>): RecommendationItem[] {
  const keyword = query.q?.trim().toLocaleLowerCase() ?? '';
  return items.filter((item) =>
    (!query.supportType || item.supportType === query.supportType) &&
    (!keyword || `${item.title} ${item.organizer ?? ''}`.toLocaleLowerCase().includes(keyword)),
  );
}

/**
 * 지원 제도 화면(SC-10-3·4)의 표시 규칙. 화면 파일에서 떼어 둔 것은 테스트하려는 것뿐이다.
 */

export const SUPPORT_TYPE: Record<SupportType, string> = {
  FUNDING: '융자',
  SUBSIDY: '보조금',
  VOUCHER: '바우처',
  CONSULTING: '컨설팅',
  OTHER: '기타',
};

/** 색만으로 말하지 않도록 글자와 그림을 함께 쓴다(도메인 규칙 6) */
export const PROGRAM_STATUS: Record<ProgramStatus, { label: string; icon: string }> = {
  OPEN: { label: '접수 중', icon: 'check' },
  UPCOMING: { label: '접수 예정', icon: 'clock' },
  CLOSED: { label: '마감', icon: 'close' },
  // 신청 기간을 모르는 공고. 빠뜨리면 이 Record 조회가 undefined 가 되어 화면이 통째로 죽는다
  UNKNOWN: { label: '기간 미정', icon: 'info' },
};

/**
 * 공백 전에 받을 수 있나 — **세 상태다.**
 *
 * 서버는 지급 시기를 모르면 `canArriveBeforeGap` 키를 뺀다(실측으로 공고의 97%).
 * 없는 것을 `false` 로 읽으면 말한 적 없는 「못 받는다」를 단정하게 된다(도메인 규칙 1).
 */
export type Timing = 'likely' | 'unlikely' | 'unknown';
export function timingOf(canArriveBeforeGap: boolean | undefined): Timing {
  if (canArriveBeforeGap === true) return 'likely';
  if (canArriveBeforeGap === false) return 'unlikely';
  return 'unknown';
}
export const TIMING: Record<Timing, { label: string; icon: string }> = {
  likely: { label: '공백 전 받을 가능성', icon: 'check' },
  unlikely: { label: '공백 전에는 어려울 가능성', icon: 'warning' },
  unknown: { label: '지급 시기 모름', icon: 'info' },
};

/** 17.2 지급 시기. 없으면 「빠르다」가 아니라 「공고에 없다」다 */
export function disbursementLabel(days: number | undefined): string {
  return days === undefined ? '공고에 적혀 있지 않습니다' : `선정 후 약 ${days}일`;
}

/**
 * 연 매출 상한. ⚠ 워커가 「연 3억 이하」 같은 문장을 그대로 넘길 때가 있어 숫자라고 믿지 않는다 —
 * 문장을 포맷터에 넣으면 `NaN원` 이 뜬다.
 */
export function revenueLabel(value: unknown): string | null {
  if (typeof value === 'number' && Number.isFinite(value)) return `연 매출 ${formatMoney(value)} 이하`;
  if (typeof value === 'string' && value.trim()) return value.trim();
  return null;
}

/** 금리. 17장은 퍼센트 값을 그대로 보낸다(3.51 = 3.51%) — 16장의 비율과 다르다 */
export function percentLabel(value: number | undefined): string {
  if (value === undefined || Number.isNaN(value)) return '—';
  return `${Number(value.toFixed(2))}%`;
}

/** 수수료. 서버는 무료를 0 으로 보낸다 — 「0원」보다 「무료」가 읽힌다. 모르면 비운다 */
export function feeLabel(fee: number | undefined): string {
  if (fee === undefined) return '—';
  return fee === 0 ? '무료' : formatMoney(fee);
}

/** '2026-09-19' 을 기준일(같은 모양)과 견준 남은 날. 시각이 없는 날짜라 문자열로만 센다 */
export function daysLeft(end: string, today: string): number {
  const at = (d: string) => Date.UTC(Number(d.slice(0, 4)), Number(d.slice(5, 7)) - 1, Number(d.slice(8, 10)));
  return Math.round((at(end) - at(today)) / 86_400_000);
}

/** 마감 표시. 날짜가 없으면 「상시」가 아니라 모르는 것이라 그대로 비운다 */
export function deadlineLabel(end: string | undefined, today: string): { text: string; urgent: boolean } | null {
  if (!end) return null;
  const left = daysLeft(end, today);
  const day = `${Number(end.slice(5, 7))}월 ${Number(end.slice(8, 10))}일`;
  if (left < 0) return { text: `${day} 마감됨`, urgent: false };
  if (left === 0) return { text: `오늘 마감 (${day})`, urgent: true };
  return { text: `${day} 마감 · D-${left}`, urgent: left <= 7 };
}

/** 오늘을 'YYYY-MM-DD' 로(로컬 시간). 화면이 한 번만 잰다 */
export function todayIso(now = new Date()): string {
  const p = (n: number) => String(n).padStart(2, '0');
  return `${now.getFullYear()}-${p(now.getMonth() + 1)}-${p(now.getDate())}`;
}

/** 프로필이 비었나. 네 칸이 다 없으면 서버는 `{}` 를 보내고 모든 공고가 같은 점수가 된다 */
export function isEmptyProfile(profile: SupportProgramRecommendations['profile']): boolean {
  if (!profile) return true;
  return Object.values(profile).every((v) => v === undefined || v === null || v === '');
}

/**
 * 17.2 `eligibility`(JSONB)를 읽을 수 있는 줄로.
 *
 * 워커가 채운 키만 오고 모양이 고정이 아니다. 아는 키는 이름을 붙이고, 모르는 키는
 * 글자·글자 목록일 때만 그대로 보인다 — 숨기면 조건이 조용히 사라진다.
 */
const ELIGIBILITY_LABEL: Record<string, string> = {
  regions: '지역',
  industries: '업종',
  maxAnnualRevenue: '매출',
  minYearsInBusiness: '업력 하한',
  maxYearsInBusiness: '업력 상한',
  excludes: '제외 대상',
  uncertainConditions: '확인 필요',
};
export function eligibilityRows(eligibility: Record<string, unknown>): { key: string; label: string; value: string }[] {
  const rows: { key: string; label: string; value: string }[] = [];
  for (const [key, raw] of Object.entries(eligibility)) {
    let value: string | null = null;
    if (key === 'maxAnnualRevenue') value = revenueLabel(raw);
    else if (key === 'minYearsInBusiness' && typeof raw === 'number') value = `${raw}년 이상`;
    else if (key === 'maxYearsInBusiness' && typeof raw === 'number') value = `${raw}년 이하`;
    else if (Array.isArray(raw)) {
      const texts = raw.filter((v): v is string => typeof v === 'string' && v.trim() !== '');
      value = texts.length ? texts.join(', ') : null;
    } else if (typeof raw === 'string' && raw.trim()) value = raw.trim();
    else if (typeof raw === 'number' || typeof raw === 'boolean') value = String(raw);
    if (value) rows.push({ key, label: ELIGIBILITY_LABEL[key] ?? key, value });
  }
  return rows;
}
