// docs/api/14_정산_내보내기.md 14.2 — 신고 준비자료 내보내기 (P0-12 부속 산출물)
//
// 서버 구현(`ExportController`·`ExportService#describe`)과 구현현황 §3.43 으로 맞췄다(2026-09-18).
// 명세와 다른 곳:
//  ① `exportId` 와 `jobId` 가 **같은 값**이다. 명세 예시는 81·9003 으로 달리 적었지만, 번호를
//     따로 두려면 테이블이 하나 더 필요하고 그것이 ERD 가 없앤 것이다(`EXPORT_JOB` → `JOB`).
//  ② 이력 목록 `GET /businesses/{id}/exports` 는 **명세에 없는 경로다**(구현현황 4장).
//     페이징이 없고 `items` 한 겹이다.
//  ③ `requestedAt` 이 더 있다 — 진행 중인 내보내기도 목록에서 언제 요청했는지 보여야 한다.
//
// 서버가 non_null 이라 모르는 값은 **키가 빠진다.**

/** 지금은 한 종류뿐이다. 모르는 값을 보내면 `400` — 오타가 조용히 빈 패키지로 끝나지 않는다 */
export type ExportType = 'TAX_PREP';

/**
 * Job 의 `QUEUED` 와 `PROCESSING` 을 갈라 보여 줄 이유가 없어 **둘 다 `PROCESSING`** 이다.
 * 화면이 「대기 중」과 「만드는 중」을 다른 말로 적을 근거가 없다.
 */
export type ExportStatus = 'PROCESSING' | 'COMPLETED' | 'FAILED';

/**
 * 패키지에 무엇을 담을지.
 *
 * `includeEvidenceFiles` 를 켜면 증빙 원본 폴더가 들어가 ZIP 이 수백 MB 가 된다. 서버 기본값이
 * **꺼짐**이라 화면도 켜는 것을 사용자가 고르게 한다.
 */
export interface CreateExportRequest {
  exportType: ExportType;
  /** `YYYY-MM-DD` */
  periodStart: string;
  periodEnd: string;
  includeEvidenceFiles?: boolean;
}

/** 202. 접수만 된 상태라 파일과 관련된 키가 하나도 없다 */
export interface ExportCreated {
  exportId: number;
  jobId: number;
  exportType: ExportType;
  status: ExportStatus;
}

/**
 * 패키지 요약. **14.3 신고서 초안과 정의를 맞춘 값들이다** — 두 산출물이 같은 기간에 다른
 * 숫자를 말하면 세무대리인은 우리 집계를 통째로 버린다.
 */
export interface ExportSummary {
  /** 기간 내 전체 거래. 분류 여부를 가리지 않는다 */
  transactionCount?: number;
  /**
   * 증빙이 붙은 **출금** ÷ 전체 출금.
   *
   * ⚠ **출금이 한 건도 없으면 키가 없다.** 0 으로 읽으면 「증빙을 하나도 안 붙였다」가 되는데,
   * 붙일 것이 없었던 것과 다른 말이다.
   */
  evidenceLinkRate?: number;
  /** 14.3 `excluded.needsReview` 와 같은 세 상태다 */
  unverifiedCount?: number;
  unverifiedAmount?: number;
  taxReviewedCount?: number;
  /** 14.3 `basedOnReviewRate` 와 같은 식. 셀 것이 없으면 **1** 이다 */
  taxReviewRate?: number;
}

/**
 * 내보내기 한 건. 목록과 단건이 **같은 모양**이다(서버가 같은 `describe` 를 쓴다).
 *
 * ⚠ **진행 중이면 `fileName`·`fileSize`·`downloadExpiresAt`·`summary`·`generatedAt` 키가 없다.**
 * 0 이나 빈 문자열로 채우지 않는 것이 서버 약속이라, 화면도 「없음」과 「0」을 갈라 읽어야 한다.
 */
export interface ExportItem {
  exportId: number;
  jobId: number;
  exportType: ExportType;
  status: ExportStatus;
  periodStart: string;
  periodEnd: string;
  /** 요청한 때. 진행 중인 것도 목록에 서므로 이 키는 늘 온다 */
  requestedAt: string;
  fileName?: string;
  fileSize?: number;
  /** **보관 7일.** 지나면 다운로드가 `410` 이고, 기다릴 것이 아니라 다시 만들어야 한다 */
  downloadExpiresAt?: string;
  summary?: ExportSummary;
  generatedAt?: string;
  /** `FAILED` 일 때만 */
  errorCode?: string;
  errorMessage?: string;
}
