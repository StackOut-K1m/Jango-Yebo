import { expect, test } from 'vitest';
import {
  daysLeft,
  deadlineLabel,
  disbursementLabel,
  eligibilityRows,
  feeLabel,
  filterRecommendations,
  percentLabel,
  isEmptyProfile,
  revenueLabel,
  timingOf,
} from './supportView';
import type { RecommendationItem } from '@jangoyebo/core';

test('추천 검색은 공고명·기관과 유형을 함께 적용하고 추천 순서를 유지한다', () => {
  const items: RecommendationItem[] = [
    { programId: 3, title: '디지털 전환', organizer: 'SEOUL 지원센터', supportType: 'VOUCHER', matchScore: 0.9, matchedConditions: [], needsVerification: false },
    { programId: 1, title: 'Seoul 경영 지원', supportType: 'SUBSIDY', matchScore: 0.8, matchedConditions: [], needsVerification: true },
    { programId: 2, title: '전국 판로 지원', supportType: 'VOUCHER', matchScore: 0.7, matchedConditions: [], needsVerification: false },
  ];
  expect(filterRecommendations(items, { q: ' seoul ' }).map((item) => item.programId)).toEqual([3, 1]);
  expect(filterRecommendations(items, { q: 'seoul', supportType: 'VOUCHER' }).map((item) => item.programId)).toEqual([3]);
  expect(filterRecommendations(items, { q: '없는 공고' })).toEqual([]);
  expect(filterRecommendations(items, { q: '   ' })).toEqual(items);
  expect(items.map((item) => item.programId)).toEqual([3, 1, 2]);
});

// 키가 없는 것은 「못 받는다」가 아니라 「모른다」다
test('공백 전 도착은 세 상태다', () => {
  expect(timingOf(true)).toBe('likely');
  expect(timingOf(false)).toBe('unlikely');
  expect(timingOf(undefined)).toBe('unknown');
  expect(disbursementLabel(undefined)).toBe('공고에 적혀 있지 않습니다');
  expect(disbursementLabel(30)).toBe('선정 후 약 30일');
});

// 문장을 포맷터에 넣으면 NaN원 이 뜬다
test('매출 상한은 숫자가 아닐 수 있다', () => {
  expect(revenueLabel(300000000)).toBe('연 매출 300,000,000원 이하');
  expect(revenueLabel('연 3억 이하')).toBe('연 3억 이하');
  expect(revenueLabel(undefined)).toBeNull();
  expect(revenueLabel(Number.NaN)).toBeNull();
});

// 17장 금리는 퍼센트 값 그대로다. 비율로 읽으면 351% 가 된다
test('금리는 퍼센트 값을 그대로 쓴다', () => {
  expect(percentLabel(3.51)).toBe('3.51%');
  expect(percentLabel(3.5)).toBe('3.5%');
  expect(percentLabel(undefined)).toBe('—');
});

test('수수료 0 은 무료, 없으면 비운다', () => {
  expect(feeLabel(0)).toBe('무료');
  expect(feeLabel(1000)).toBe('1,000원');
  expect(feeLabel(undefined)).toBe('—');
});

test('마감은 날짜로만 센다', () => {
  expect(daysLeft('2026-09-20', '2026-09-17')).toBe(3);
  expect(deadlineLabel('2026-09-20', '2026-09-17')).toEqual({ text: '9월 20일 마감 · D-3', urgent: true });
  expect(deadlineLabel('2026-09-17', '2026-09-17')?.text).toBe('오늘 마감 (9월 17일)');
  expect(deadlineLabel('2026-09-01', '2026-09-17')?.text).toBe('9월 1일 마감됨');
  expect(deadlineLabel('2026-10-31', '2026-09-17')?.urgent).toBe(false);
  expect(deadlineLabel(undefined, '2026-09-17')).toBeNull();
});

test('네 칸이 다 비면 빈 프로필이다', () => {
  expect(isEmptyProfile({})).toBe(true);
  expect(isEmptyProfile(undefined)).toBe(true);
  expect(isEmptyProfile({ region: '광주' })).toBe(false);
});

// 워커가 채운 키만 온다. 모르는 키도 숨기지 않는다
test('자격 조건 JSONB 를 줄로 편다', () => {
  expect(
    eligibilityRows({
      regions: ['광주'],
      maxAnnualRevenue: '연 3억 이하',
      minYearsInBusiness: 1,
      excludes: [],
      uncertainConditions: ['공동사업자 신청 가능 여부'],
      extra: '대표자 만 39세 이하',
      nested: { a: 1 },
    }),
  ).toEqual([
    { key: 'regions', label: '지역', value: '광주' },
    { key: 'maxAnnualRevenue', label: '매출', value: '연 3억 이하' },
    { key: 'minYearsInBusiness', label: '업력 하한', value: '1년 이상' },
    { key: 'uncertainConditions', label: '확인 필요', value: '공동사업자 신청 가능 여부' },
    { key: 'extra', label: 'extra', value: '대표자 만 39세 이하' },
  ]);
});
