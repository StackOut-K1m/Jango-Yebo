import { request } from './client';
import type { ApiResult } from './envelope';
import type { Page } from './types/02_common';
import type {
  DocumentGuide,
  DocumentGuideQuery,
  SupportProgramDetail,
  SupportProgramListItem,
  SupportProgramQuery,
  SupportProgramRecommendations,
} from './types/17_support';

/**
 * 지원 제도·서류 (API 17장, P1-15). 넷 다 동기 GET 이다.
 *
 * 목록이 비어도 오류가 아니다 — 서버는 자격 조건을 모르는 공고(구조화 전)를 **일부러 뺀다.**
 * 목록이 비면 버그가 아니라 수집이 안 된 것이다.
 */

/** 공고 검색 (17.1). ⚠ `matchScore` 가 없다 — 맞춤 점수는 `getSupportRecommendations` */
export function listSupportPrograms(
  query: SupportProgramQuery = {},
): Promise<ApiResult<Page<SupportProgramListItem>>> {
  return request<Page<SupportProgramListItem>>('supportPrograms.list', '/support-programs', { query });
}

/** 공고 상세 (17.2). ⚠ 목록 필드는 `summary` 안에 있다 */
export function getSupportProgram(programId: number): Promise<ApiResult<SupportProgramDetail>> {
  return request<SupportProgramDetail>('supportPrograms.get', `/support-programs/${programId}`);
}

/**
 * 사업장 맞춤 추천 (17.3). `cashGapLinked` 면 가장 가까운 공백과 지급 시기를 대조한다.
 *
 * 프로필(지역·업종·매출·업력)이 비면 모든 공고가 같은 점수로 오고 마감 임박순으로 선다.
 */
export function getSupportRecommendations(
  businessId: number,
  cashGapLinked = true,
): Promise<ApiResult<SupportProgramRecommendations>> {
  return request<SupportProgramRecommendations>(
    'supportPrograms.recommendations',
    `/businesses/${businessId}/support-programs/recommendations`,
    { query: { cashGapLinked } },
  );
}

/** 서류 발급처 사전 (17.4). 응답은 `items` 한 겹이다 */
export function listDocumentGuides(
  query: DocumentGuideQuery,
): Promise<ApiResult<{ items: DocumentGuide[] }>> {
  return request<{ items: DocumentGuide[] }>('documentGuides.list', '/document-guides', { query });
}
