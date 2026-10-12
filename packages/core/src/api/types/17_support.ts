// docs/api/17_지원제도_문서.md
//
// 지원 제도 검색(17.1) · 상세(17.2) · 맞춤 추천(17.3) · 서류 사전(17.4). P1-15.
//
// 로컬 develop(c360a957)에 기업마당 공고 시드를 넣고 실제 응답으로 맞췄다(2026-09-17).
// 명세와 다른 네 군데:
//  ① 17.1 목록에 `matchScore` 가 없다 — 이 경로에는 사업장이 없어 점수의 입력이 없다.
//     `sort=MATCH_SCORE` 는 400 이다. 맞춤 점수는 17.3 이 낸다.
//  ② 17.1 에 `status` 와 질의 `includeClosed` 가 더 있다. 기본은 마감 건을 뺀다.
//  ③ 17.2 는 평평하지 않다 — 목록 필드가 `summary` 안에 통째로 들어간다.
//  ④ 17.2 `eligibility` 는 JSONB 그대로다. 워커가 채운 키만 온다.
//
// 서버가 non_null 이라 모르는 값은 **키 자체가 빠진다.** 「없다」와 「모른다」를 가르는 데 쓴다.

/** CHECK 제약과 같은 집합 */
export type SupportType = 'FUNDING' | 'SUBSIDY' | 'VOUCHER' | 'CONSULTING' | 'OTHER';
/**
 * ⚠ **네 개다.** `UNKNOWN` 은 신청 기간을 모르는 공고다 — 상시모집이라 비었을 수도,
 * 구조화가 못 읽었을 수도 있는데 서버는 둘 다 「열려 있다」고 단정하지 않는다.
 * DB CHECK 와 같은 집합이고, 기본 검색을 그냥 통과하므로 화면이 반드시 그릴 수 있어야 한다.
 */
export type ProgramStatus = 'OPEN' | 'CLOSED' | 'UPCOMING' | 'UNKNOWN';
/** `MATCH_SCORE` 는 17.3 전용이다 — 여기 넣으면 400 */
export type ProgramSort = 'DEADLINE' | 'COLLECTED_AT';

/** 목록에 얹는 자격 요약. 전체 조건은 17.2 `eligibility` 에 있다 */
export interface EligibilitySummary {
  regions: string[];
  industries: string[];
  /** ⚠ **숫자가 아닐 수 있다** — 워커가 「연 3억 이하」처럼 문장을 그대로 넘길 때가 있다 */
  maxAnnualRevenue?: number | string;
  /** 지금은 제외 대상(`excludes`)이 들어온다 — 「휴·폐업자」처럼 바로 걸러 낼 수 있는 것 */
  notes: string[];
}

/** 17.1 한 줄. `sourceUrl`·`collectedAt` 은 원문 대조용이라 함께 보여 준다 */
export interface SupportProgramListItem {
  programId: number;
  title: string;
  organizer?: string;
  /** 수집처(`BIZINFO` 등). ⚠ `SAMPLE` 은 존재하지 않는 시드 공고다 */
  sourceSite?: string;
  sourceUrl?: string;
  collectedAt?: string;
  supportType?: SupportType;
  maxAmount?: number;
  /**
   * ⚠ **퍼센트다.** 3.51% 는 `3.51` 로 온다 — 16장(대출)은 경계에서 비율로 나눠 보내지만
   * 이 경로는 DB 값을 그대로 낸다(2026-09-17 실측). 비율로 읽으면 351% 가 된다.
   */
  interestRate?: number;
  applyStart?: string;
  applyEnd?: string;
  status: ProgramStatus;
  eligibilitySummary: EligibilitySummary;
  /** 항상 온다. 조건이 불명확한 공고다 — 숨기지 않는다(도메인 규칙 1) */
  needsVerification: boolean;
}

/**
 * 17.1 질의. `q` 는 지금 **키워드 검색**이다(공고명·주관 기관) — 자연어 검색은 별도 스토리.
 * `region` 은 첫 토막만 본다(「광주 서구」 → 「광주」).
 *
 * interface 가 아니라 type 인 이유는 `PageQuery` 와 같다(02_common).
 */
export type SupportProgramQuery = {
  q?: string;
  region?: string;
  industryType?: string;
  supportType?: SupportType;
  /** `YYYY-MM-DD` */
  deadlineAfter?: string;
  includeClosed?: boolean;
  sort?: ProgramSort;
  page?: number;
  size?: number;
};

/**
 * 제출 서류 한 줄 (17.2). 이름은 공고 표기 그대로다.
 * `issueUrl`·`fee` 는 서류 사전(17.4)에서만 온다 — 통장 사본처럼 발급처가 없으면 빈다.
 */
export interface RequiredDocument {
  documentName: string;
  issuer?: string;
  issueUrl?: string;
  method?: string;
  fee?: number;
}

/**
 * 17.2 응답.
 *
 * ⚠ **평평하지 않다.** `data.programId` 가 아니라 `data.summary.programId` 다.
 */
export interface SupportProgramDetail {
  summary: SupportProgramListItem;
  /** ⚠ JSONB 그대로. 워커가 채운 키만 온다 — 고정 스키마로 읽지 않는다 */
  eligibility: Record<string, unknown>;
  /** 수집기가 공고 요약을 그대로 적은 것. 모델이 만든 문장이 아니다 */
  rawSummary?: string;
  /** ⚠ **대부분 없다**(실측 3%). 없는 것은 「빨리 나온다」가 아니라 「공고가 말하지 않았다」다 */
  expectedDisbursementDays?: number;
  /** 빈 배열일 수 있다 */
  requiredDocuments: RequiredDocument[];
}

/** 17.3 한 줄 */
export interface RecommendationItem {
  programId: number;
  title: string;
  organizer?: string;
  sourceUrl?: string;
  supportType?: SupportType;
  maxAmount?: number;
  applyEnd?: string;
  /**
   * 항상 온다. ⚠ 판정할 조건이 없는 공고는 **0.5** 다(0 이 아니다) — 숫자만 띄우면 「반쯤 맞는다」로
   * 읽힌다. 화면은 `matchedConditions` 를 먼저 보인다.
   */
  matchScore: number;
  matchedConditions: string[];
  needsVerification: boolean;
  expectedDisbursementDays?: number;
  /**
   * `expectedDisbursementDays` 가 있을 때만 온다. **없으면 「모른다」다** — `false` 로 읽으면
   * 말한 적 없는 「못 받는다」를 단정하게 된다. 세 상태로 그린다.
   */
  canArriveBeforeGap?: boolean;
  timingNote?: string;
}

/** 17.3 응답 */
export interface SupportProgramRecommendations {
  baseTime: string;
  /** 점수의 입력. 네 칸이 다 없으면 `{}` 다 — 그때는 모든 추천이 같은 점수다 */
  profile?: {
    region?: string;
    industryType?: string;
    annualRevenue?: number;
    yearsInBusiness?: number;
  };
  /** 가장 가까운 공백. 없으면 키가 없다 */
  cashGap?: { firstShortfallDate: string; maxShortfallAmount: number };
  items: RecommendationItem[];
  /** 고정 노출한다 — 접거나 툴팁 뒤로 숨기지 않는다(도메인 규칙 1) */
  disclaimer: string;
}

/** 17.4 사전 한 줄. 응답은 `items` 로 한 겹 감싸 온다 */
export interface DocumentGuide {
  documentGuideId: number;
  documentName: string;
  issuer?: string;
  issueUrl?: string;
  method?: string;
  /** 무료면 0 으로 온다 */
  fee?: number;
  estimatedTime?: string;
  notes?: string;
}

/** 17.4 질의. 둘 중 하나를 준다 */
export type DocumentGuideQuery = {
  programId?: number;
  q?: string;
};
