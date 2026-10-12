import { request, requestFile } from './client';
import type { DownloadedFile } from './client';
import type { ApiResult } from './envelope';
import type { CreateExportRequest, ExportCreated, ExportItem } from './types/14_export';

/**
 * 신고 준비자료 패키지 (docs/api/14_정산_내보내기.md 14.2).
 *
 * 세무대리인에게 통째로 넘길 ZIP 이다 — 시트 일곱 장과, 켜면 증빙 원본 폴더까지 들어간다.
 * 홈택스 직접 신고용 주 산출물은 14.3 신고서 초안이고 이쪽은 그 판단의 **근거 자료**다.
 */

/**
 * 만들기 시작한다. `202` 이고 실제 생성은 `EXPORT_GENERATION` Job 이 한다 —
 * 한 해 거래를 전부 훑고 증빙 원본까지 담으므로 요청-응답 안에서 끝나지 않는다.
 *
 * 돌아온 `exportId` 는 `jobId` 와 같은 값이라 `getJob` 으로도 진행을 볼 수 있지만,
 * 화면은 `getExport` 를 쓴다 — 완료 시점에 필요한 `fileName`·`summary` 가 그쪽에만 있다.
 *
 * ⚠ **중복 요청을 서버가 막지 않는다.** 누를 때마다 새 내보내기다.
 */
export function createExport(
  businessId: number,
  body: CreateExportRequest,
): Promise<ApiResult<ExportCreated>> {
  return request<ExportCreated>('exports.create', `/businesses/${businessId}/exports`, {
    method: 'POST',
    json: body,
  });
}

/**
 * 내보내기 이력. 최근 것부터 오고 **페이징이 없다**(명세 밖 경로 — 구현현황 4장).
 *
 * 진행 중인 것도 함께 온다. 보관 기간이 지난 것은 행이 남고 파일만 사라진다 —
 * 그래서 목록에 있다고 받을 수 있는 것은 아니다. `downloadExpiresAt` 을 봐야 한다.
 */
export function listExports(businessId: number): Promise<ApiResult<{ items: ExportItem[] }>> {
  return request<{ items: ExportItem[] }>('exports.list', `/businesses/${businessId}/exports`);
}

/** 한 건. 목록과 같은 모양이다. 진행 중이면 파일 관련 키가 통째로 없다 */
export function getExport(exportId: number): Promise<ApiResult<ExportItem>> {
  return request<ExportItem>('exports.get', `/exports/${exportId}`);
}

/**
 * 내려받기.
 *
 * ⚠ **`<a href>` 로는 못 받는다** — 브라우저 기본 다운로드는 `Authorization` 을 싣지 않는다.
 *
 * ⚠ **`409` 와 `410` 을 같은 말로 옮기면 안 된다.** `EXPORT_NOT_READY`(409)는 아직 만드는
 * 중이라 기다리면 되지만, `EXPORT_EXPIRED`(410)는 7일이 지나 파일이 사라진 것이라
 * **기다리면 안 되고 다시 만들어야 한다.**
 */
export function downloadExport(exportId: number): Promise<ApiResult<DownloadedFile>> {
  return requestFile('exports.download', `/exports/${exportId}/download`);
}
