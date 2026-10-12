import { expect, test } from 'vitest';
import {
  attachableJobId,
  classificationProgress,
  unclassifiedPollInterval,
} from './classificationRun';

// 서버가 무엇을 돌려주는지는 백엔드가 지킨다(TransactionClassificationJobApiTests).
// 여기서 보는 것은 그 답을 화면이 어떻게 읽는가다 — 언제 붙고, 무엇을 숫자로 말하는가.

const clickedAt = Date.parse('2026-09-18T04:00:00Z');
const later = (ms: number) => new Date(clickedAt + ms).toISOString();

test('도는 작업이 없으면 붙지 않는다 — 200 이어도 running:false 는 정상 빈 상태다', () => {
  expect(attachableJobId({ running: false }, null)).toBeNull();
  expect(attachableJobId(null, null)).toBeNull();
  expect(attachableJobId(undefined, null)).toBeNull();
});

test('도는 작업이 있으면 그 id 로 붙는다', () => {
  expect(attachableJobId({ running: true, job: { jobId: 412, status: 'PROCESSING' } }, null)).toBe(412);
  // 워커가 아직 안 집었을 뿐이다
  expect(attachableJobId({ running: true, job: { jobId: 413, status: 'QUEUED' } }, null)).toBe(413);
});

// 이것이 없으면 STALL_MS 마다 「붙음 → 정체 → 감지 → 다시 붙음」이 무한히 돈다
test('정체로 손을 뗀 작업에는 다시 붙지 않는다', () => {
  const stuck = { running: true as const, job: { jobId: 412, status: 'PROCESSING' as const } };
  expect(attachableJobId(stuck, 412)).toBeNull();
});

// 이어달리기가 새 id 를 만들면 그것은 갇힌 작업이 아니다 — 서버가 나아갔다는 뜻이다
test('손을 뗀 뒤라도 다른 작업이면 다시 붙는다', () => {
  const next = { running: true as const, job: { jobId: 413, status: 'PROCESSING' as const } };
  expect(attachableJobId(next, 412)).toBe(413);
});

test('안 눌렀으면 분모를 쓰지 않는다 — 돌아와서 붙은 실행은 출발점을 모른다', () => {
  const p = classificationProgress({
    jobCreatedAt: later(-600_000),
    remaining: 128,
    startedAt: null,
    startTotal: null,
  });
  expect(p.mine).toBe(false);
  expect(p.label).toBe('이미 분류가 진행 중입니다 · 미분류 128건 남음');
  // 불확정 막대 — 분모를 지어내지 않는다(도메인 규칙 1)
  expect(p.max).toBeUndefined();
  expect(p.value).toBeUndefined();
});

test('잔량을 아직 모르면 수를 말하지 않는다', () => {
  const p = classificationProgress({
    jobCreatedAt: later(-600_000),
    remaining: null,
    startedAt: null,
    startTotal: null,
  });
  expect(p.label).toBe('이미 진행 중인 분류를 확인하는 중');
});

test('내가 누른 실행은 몇 건 중 몇 건으로 센다', () => {
  const p = classificationProgress({
    jobCreatedAt: later(200),
    remaining: 26,
    startedAt: clickedAt,
    startTotal: 40,
  });
  expect(p.mine).toBe(true);
  expect(p.label).toBe('14 / 40건');
  expect(p.max).toBe(40);
  expect(p.value).toBe(14);
});

// 한 Job 이 batch-size 만큼만 맡고 서버가 다음 작업을 **새 id 로** 이어 건다.
// 그 작업은 내 클릭보다 뒤에 서므로 여전히 내 실행이다 — 여기서 갈라지면 진행 표시가
// 배치 하나마다 「몇 건 중 몇 건」에서 「남은 N건」으로 튄다.
test('이어달리기가 만든 작업도 내 실행이다', () => {
  const p = classificationProgress({
    jobCreatedAt: later(45_000),
    remaining: 6,
    startedAt: clickedAt,
    startTotal: 40,
  });
  expect(p.mine).toBe(true);
  expect(p.label).toBe('34 / 40건');
});

// 8.5 는 이미 도는 작업이 있으면 409 가 아니라 그것을 돌려준다. 그것은 내 실행이 아니라
// 앞에서 막고 있던 것이라, 내 잔량을 분모로 쓰면 남의 진행을 내 것처럼 센다.
test('클릭보다 먼저 만들어진 작업은 내 실행이 아니다', () => {
  const p = classificationProgress({
    jobCreatedAt: later(-600_000),
    remaining: 40,
    startedAt: clickedAt,
    startTotal: 40,
  });
  expect(p.mine).toBe(false);
  expect(p.label).toBe('이미 분류가 진행 중입니다 · 미분류 40건 남음');
});

test('시계 오차 5초 안쪽은 내 실행으로 본다', () => {
  expect(
    classificationProgress({
      jobCreatedAt: later(-3_000),
      remaining: 40,
      startedAt: clickedAt,
      startTotal: 40,
    }).mine,
  ).toBe(true);
});

test('생성 시각이 없거나 깨졌으면 클릭 여부로 판단한다', () => {
  const base = { remaining: 40, startedAt: clickedAt, startTotal: 40 };
  expect(classificationProgress({ ...base }).mine).toBe(true);
  expect(classificationProgress({ ...base, jobCreatedAt: '언젠가' }).mine).toBe(true);
});

test('아직 한 건도 안 줄었으면 0 / N 이 아니라 시작하는 중이다', () => {
  const p = classificationProgress({
    jobCreatedAt: later(200),
    remaining: 40,
    startedAt: clickedAt,
    startTotal: 40,
  });
  expect(p.label).toBe('시작하는 중');
  // 막대는 그래도 분모를 안다 — 0 에서 출발하는 것이 맞다
  expect(p.max).toBe(40);
  expect(p.value).toBe(0);
});

test('천 단위 구분은 숫자에 붙인다', () => {
  expect(
    classificationProgress({
      jobCreatedAt: later(200),
      remaining: 1_200,
      startedAt: clickedAt,
      startTotal: 3_400,
    }).label,
  ).toBe('2,200 / 3,400건');
  expect(
    classificationProgress({
      jobCreatedAt: later(-600_000),
      remaining: 1_340,
      startedAt: null,
      startTotal: null,
    }).label,
  ).toBe('이미 분류가 진행 중입니다 · 미분류 1,340건 남음');
});

// 2026-09-18 실측에서 잡았다 — 미분류 0건인 사업장에 갇힌 Job 이 하나 있었고, 화면은
// 보여 줄 진행이 없는데도 거래 목록을 3초마다 계속 다시 불렀다.
test('잔량이 0 이면 잔량 폴링을 멈춘다', () => {
  expect(unclassifiedPollInterval(true, 0, 3000)).toBe(false);
});

test('도는 것이 없어도 멈춘다', () => {
  expect(unclassifiedPollInterval(false, 128, 3000)).toBe(false);
});

test('남은 것이 있고 돌고 있으면 센다', () => {
  expect(unclassifiedPollInterval(true, 128, 3000)).toBe(3000);
});

// 첫 조회 전에는 0 이라고 단정할 수 없다 — 멈추면 영영 못 센다
test('아직 모르면 일단 센다', () => {
  expect(unclassifiedPollInterval(true, undefined, 3000)).toBe(3000);
  expect(unclassifiedPollInterval(true, null, 3000)).toBe(3000);
});
