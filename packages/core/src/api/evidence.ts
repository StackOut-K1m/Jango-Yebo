import { request, requestFile } from './client';
import type { DownloadedFile } from './client';
import type { ApiResult } from './envelope';
import type { Page } from './types/02_common';
import type {
  EvidenceDetail,
  EvidenceQuery,
  EvidenceSummary,
  EvidenceUpload,
  EvidenceType,
  ExtractionPatch,
  MatchCandidate,
  ReExtractResult,
  UploadEvidenceOptions,
} from './types/13_evidence';

/**
 * 증빙 (API 13장, P0-10·P1-02).
 *
 * 업로드하면 서버가 추출 Job 을 건다. 결과는 Job 이 아니라 증빙 상세의 extraction 에 남으므로
 * (Job 은 30일 뒤 정리된다) 화면은 Job 대신 목록·상세의 ocrStatus 를 다시 읽는다(구현현황 3.17).
 */

/** 202. JPG·PNG·PDF 10MB 이하가 아니면 400 EVIDENCE_FILE_UNSUPPORTED */
export function uploadEvidence(
  businessId: number,
  file: Blob,
  fileName: string,
  options: UploadEvidenceOptions = {},
): Promise<ApiResult<EvidenceUpload>> {
  const form = new FormData();
  form.append('file', file, fileName);
  if (options.evidenceType) form.append('evidenceType', options.evidenceType);
  if (options.transactionId !== undefined) form.append('transactionId', String(options.transactionId));
  return request<EvidenceUpload>('evidences.upload', `/businesses/${businessId}/evidences`, {
    method: 'POST',
    body: form,
  });
}

/** 모르는 ocrStatus·evidenceType 은 400 이다 — 조용히 무시하면 필터가 안 걸린 전체가 나간다 */
export function listEvidences(
  businessId: number,
  query: EvidenceQuery = {},
): Promise<ApiResult<Page<EvidenceSummary>>> {
  return request<Page<EvidenceSummary>>('evidences.list', `/businesses/${businessId}/evidences`, {
    query,
  });
}

/**
 * 원본 파일(13.1 fileUrl). 인증 헤더가 필요해 <img src> 로 못 받는다 — blob 으로 받아 object URL 을 만든다.
 * 지운 증빙은 404 EVIDENCE_NOT_FOUND, 기록은 있는데 파일만 없으면 404 EVIDENCE_FILE_MISSING(다시 올리면 된다).
 */
export function getEvidenceFile(evidenceId: number): Promise<ApiResult<DownloadedFile>> {
  return requestFile('evidences.file', `/evidences/${evidenceId}/file`);
}

export function getEvidence(evidenceId: number): Promise<ApiResult<EvidenceDetail>> {
  return request<EvidenceDetail>('evidences.get', `/evidences/${evidenceId}`);
}

/** 응답의 extraction.validation 은 고친 값으로 다시 돌린 검산이다. 워커가 없으면 옛 결과가 남는다 */
export function patchEvidenceExtraction(
  evidenceId: number,
  body: ExtractionPatch,
): Promise<ApiResult<EvidenceDetail>> {
  return request<EvidenceDetail>('evidences.patchExtraction', `/evidences/${evidenceId}/extraction`, {
    method: 'PATCH',
    json: body,
  });
}

/** 13.4 연결 후보, 점수순. 발행일을 모르면 빈 목록이다 */
export function listMatchCandidates(evidenceId: number): Promise<ApiResult<{ items: MatchCandidate[] }>> {
  return request<{ items: MatchCandidate[] }>('evidences.matchCandidates', `/evidences/${evidenceId}/match-candidates`);
}

/**
 * 13.4 거래 연결. 증빙당 하나다 — 다른 거래에 붙어 있으면 409 EVIDENCE_ALREADY_LINKED 라 떼고 다시 붙인다.
 * 같은 거래에 다시 붙이면 성공이다. 연결되면 그 거래의 검토 상태가 증빙 확인 완료로 바뀐다(8.7 자동 판정).
 */
export function linkEvidence(evidenceId: number, transactionId: number): Promise<ApiResult<null>> {
  return request<null>('evidences.link', `/evidences/${evidenceId}/link`, { method: 'POST', json: { transactionId } });
}

/** 13.4 연결 해제. 붙어 있지 않아도 204 다 */
export function unlinkEvidence(evidenceId: number): Promise<ApiResult<null>> {
  return request<null>('evidences.unlink', `/evidences/${evidenceId}/link`, { method: 'DELETE' });
}

/** 13.5 증빙 종류 수정. 추출 결과가 아니라 증빙 자체라 수정 이력에 남지 않는다 */
export function patchEvidence(evidenceId: number, body: { evidenceType: EvidenceType }): Promise<ApiResult<EvidenceDetail>> {
  return request<EvidenceDetail>('evidences.patch', `/evidences/${evidenceId}`, { method: 'PATCH', json: body });
}

/**
 * 13.5 삭제, 204. 소프트 삭제라 목록·상세에서 사라지고 **거래 연결은 실제로 끊긴다** — 연결된 거래는
 * 증빙 누락으로 돌아갈 수 있다. 되살리는 경로는 없다.
 */
export function deleteEvidence(evidenceId: number): Promise<ApiResult<null>> {
  return request<null>('evidences.delete', `/evidences/${evidenceId}`, { method: 'DELETE' });
}

/**
 * 13.7 다시 읽기(THOROUGH), 202. ocrStatus 가 EXTRACTED 일 때만 된다 — 확정본은 409 다(승인한 값이
 * 조용히 사라진다). 실패하면 이전 추출이 그대로 남는다.
 */
export function reExtractEvidence(evidenceId: number): Promise<ApiResult<ReExtractResult>> {
  return request<ReExtractResult>('evidences.reExtract', `/evidences/${evidenceId}/re-extract`, { method: 'POST' });
}
