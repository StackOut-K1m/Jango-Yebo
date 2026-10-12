// docs/api/00_공통규약.md 2장

/** 2.3 페이지네이션. 목록 응답 공통 구조. */
export interface Page<T> {
  items: T[];
  page: number;
  size: number;
  totalElements: number;
  totalPages: number;
}

/**
 * 목록 질의의 공통 부분.
 *
 * interface 가 아니라 type 인 이유: interface 에는 암시적 인덱스 시그니처가 없어서
 * request() 의 query(Record<string, …>) 에 그대로 넘길 수 없다.
 */
export type PageQuery = {
  page?: number;
  /** 기본 20, 최대 100 */
  size?: number;
  /** 예: 'occurredAt,desc' */
  sort?: string;
};

/** 2.6 위험 등급. 색상 단독으로 구분하지 않고 상태명·아이콘을 병기한다. */
export type RiskLevel = 'SAFE' | 'CAUTION' | 'DANGER' | 'GAP';

/** 2.5 예측 응답 공통 필드 */
export type Confidence = 'HIGH' | 'MEDIUM' | 'LOW' | 'UNPREDICTABLE';

/**
 * 2.7 비동기 작업.
 *
 * 값은 공통규약 2.7·ERD JOB.status·백엔드 JobStatus enum 셋이 모두 같다.
 * 한때 PENDING·RUNNING 으로 적혀 있었다 — 프론트의 추측이었다(2026-09-08 정정).
 */
export type JobStatus = 'QUEUED' | 'PROCESSING' | 'COMPLETED' | 'FAILED';

export interface Job<TResult = unknown> {
  jobId: number;
  status: JobStatus;
  resultRef?: TResult;
  error?: { code: string; message: string };
  /** 아래는 실측으로 확인한 필드(2026-09-08) */
  jobType?: string;
  progress?: number;
  createdAt?: string;
  completedAt?: string;
}

/**
 * 동기화 접수 응답 (API 7.3).
 *
 * CSV 임포트와 달리 이 Job 은 아직 끝나지 않았다 — QUEUED 로 시작하고 워커가 처리한다.
 * lastSyncedAt 은 이번 것이 아니라 <b>직전 성공 시각</b>이다(첫 동기화면 없다).
 */
export interface SyncAccepted {
  jobId: number;
  jobType: string;
  status: JobStatus;
  lastSyncedAt?: string | null;
}

/** 동기화 Job 의 resultRef (API 7.3) */
export interface SyncResult {
  importedCount: number;
  duplicateCount: number;
}

/**
 * CSV 임포트 Job의 resultRef (구현현황 3.4).
 *
 * 임포트 응답(202) 본문에 이미 들어 있다 — GET /jobs/{id}를 부를 필요가 없다(실측).
 * rowNumber는 헤더를 1행으로 세는 파일 행 번호다.
 */
export interface ImportResult {
  accountId: number;
  importedCount: number;
  duplicateCount: number;
  rejectedRows: { rowNumber: number; reason: string }[];
}
