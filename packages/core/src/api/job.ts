import { request } from './client';
import type { ApiResult } from './envelope';
import type { Job, JobStatus } from './types/02_common';

/**
 * 공통 Job 조회 (공통규약 2.7).
 *
 * 도메인이 아니라 규약에 속한다 — 동기화·LLM 분류·OCR·내보내기가 모두 이 하나를 쓴다.
 */

/** 더 이상 바뀌지 않는 상태. 폴링을 멈출 조건이다. */
export function isJobTerminal(status: JobStatus | undefined): boolean {
  return status === 'COMPLETED' || status === 'FAILED';
}

/**
 * 남의 작업은 403 이 아니라 404 다(공통규약 2.7) — 존재 자체를 흘리지 않는다.
 *
 * 폴링은 화면에서 TanStack Query 의 refetchInterval 로 한다. 종료 조건은 isJobTerminal 이며,
 * 그것과 별개로 최대 시도·전체 타임아웃을 함께 둔다 — 서버가 영영 PROCESSING 이면
 * 탭이 닫힐 때까지 요청이 나간다.
 */
export function getJob<TResult = unknown>(jobId: number): Promise<ApiResult<Job<TResult>>> {
  return request<Job<TResult>>('jobs.get', `/jobs/${jobId}`);
}
