import type { CurrentClassificationJob } from '@jangoyebo/core';

// AI 분류 실행을 화면이 어떻게 읽을지 (명세 8.5 · 8.5.1).
//
// 분류는 브라우저와 무관하게 서버에서 돈다. 화면은 8.5.1 로 「지금 도는가」를 묻고,
// 진행은 미분류 잔량으로 잰다 — Job 상태로 재면 이어달리기가 매 배치마다 새 id 를 만들어
// COMPLETED 를 보고 먼저 멈춘다(2026-09-14 에 실제로 20~60건에서 멈췄다).
//
// 판단이 갈리는 곳만 여기에 모아 둔다. 나머지(폴링·인터벌)는 컴포넌트에 있다.

/** 접수 응답과 감지 응답을 「내 것인가」로 가를 때 봐 주는 시계 오차. */
const CLOCK_SLACK_MS = 5_000;

/**
 * 지금 붙을 작업의 id. 없으면 `null`.
 *
 * **정체로 손을 뗀 바로 그 작업이면 없는 것으로 친다.** 그러지 않으면 「붙음 → 정체 →
 * 감지 → 다시 붙음」이 무한히 돈다. 이어달리기가 **새 id** 를 만들면 그때 다시 붙는다 —
 * 진짜로 갇힌 작업은 id 가 그대로라 계속 걸러진다.
 */
export function attachableJobId(
  current: CurrentClassificationJob | null | undefined,
  stalledJobId: number | null,
): number | null {
  const jobId = current?.running ? (current.job?.jobId ?? null) : null;
  return jobId !== null && jobId !== stalledJobId ? jobId : null;
}

export interface ClassificationProgress {
  /** 내가 버튼을 눌러 시작시킨 실행인가. 「몇 건 중 몇 건」을 쓸 수 있는 유일한 경우다 */
  mine: boolean;
  label: string;
  /** 불확정 막대여야 하면 `undefined` */
  max?: number;
  value?: number;
}

/**
 * 진행 표시.
 *
 * **분모는 내가 시작시킨 실행에서만 쓴다.** 돌아와서 붙은 실행은 얼마에서 출발했는지 알 수
 * 없어, 분모를 지어내면 그 진행률이 그대로 거짓이 된다(도메인 규칙 1). 그럴 때는 남은
 * 건수만 말하고 막대를 불확정으로 둔다.
 *
 * **이어달리기가 만든 작업은 여전히 내 것이다** — 그것은 내 클릭보다 **뒤에** 서기 때문이다.
 * 클릭보다 **앞서** 만들어진 작업만 남의 것(앞에서 막고 있던 것)이다.
 */
export function classificationProgress(input: {
  /** 감지된 작업의 생성 시각(ISO). 없으면 판정을 클릭 쪽에 맡긴다 */
  jobCreatedAt?: string;
  /** 미분류 잔량. 모르면 `null` */
  remaining: number | null;
  /** 내가 누른 시각. 안 눌렀으면 `null` */
  startedAt: number | null;
  /** 누른 시각의 미분류 수 */
  startTotal: number | null;
}): ClassificationProgress {
  const { jobCreatedAt, remaining, startedAt, startTotal } = input;

  const createdAt = jobCreatedAt ? Date.parse(jobCreatedAt) : NaN;
  const mine =
    startedAt !== null && (!Number.isFinite(createdAt) || createdAt >= startedAt - CLOCK_SLACK_MS);

  const done = mine && startTotal !== null && remaining !== null ? startTotal - remaining : null;

  const label = !mine
    ? remaining !== null
      ? `이미 분류가 진행 중입니다 · 미분류 ${remaining.toLocaleString()}건 남음`
      : '이미 진행 중인 분류를 확인하는 중'
    : done !== null && startTotal !== null && done > 0
      ? `${done.toLocaleString()} / ${startTotal.toLocaleString()}건`
      : '시작하는 중';

  return {
    mine,
    label,
    max: mine ? (startTotal ?? undefined) : undefined,
    value: mine ? (done ?? undefined) : undefined,
  };
}

/**
 * 미분류 잔량을 다시 셀 주기. 멈춰야 하면 `false`.
 *
 * **잔량이 0 이면 멈춘다.** 「도는 작업이 있다」만 보고 돌리면, 갇힌 Job 이 있고 분류할
 * 것은 없는 사업장에서 거래 목록을 영영 다시 부른다 — 2026-09-18 실측에서 실제로 그랬다
 * (미분류 0건인 사업장에 `PROCESSING` Job 하나가 남아 있었다).
 *
 * `remaining` 이 `undefined`(첫 조회 전)면 아직 0 이라고 단정할 수 없어 돌린다.
 */
export function unclassifiedPollInterval(
  detected: boolean,
  remaining: number | null | undefined,
  pollMs: number,
): number | false {
  return detected && remaining !== 0 ? pollMs : false;
}
