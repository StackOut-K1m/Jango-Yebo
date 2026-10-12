// 명세는 120개인데 백엔드가 연 것은 일부다(docs/api/99_구현현황.md 2장).
// 화면 코드가 그 차이를 몰라도 되도록 상태를 여기 한 곳에서 관리한다.
//
// LIVE      서버를 호출한다
// MOCK      개발에서만 목 응답 + 화면에 목데이터 배지. 배포 빌드에서는 NOT_READY로 강등된다
// NOT_READY 호출하지 않고 { ready:false }를 돌려준다. throw 하지 않는다(화면구성도 6.4)

import { getConfig } from '../config';

export type EndpointState = 'LIVE' | 'MOCK' | 'NOT_READY';

/** 선언된 상태. 배포 강등 전의 원본이다. */
const DECLARED = {
  // 6. 인증·사용자
  'auth.signup': 'LIVE',
  'auth.login': 'LIVE',
  'auth.refresh': 'LIVE',
  'auth.logout': 'LIVE',
  'users.me.get': 'LIVE',
  'users.me.patch': 'LIVE',

  // 7. 사업장·계좌·수집
  /** 인증 불필요. 금융망 실시간 조회 + 서버 캐시다(구현현황 v1.9) */
  'bankCodes.list': 'LIVE',
  'businesses.list': 'LIVE',
  'businesses.create': 'LIVE',
  'businesses.get': 'LIVE',
  'businesses.patch': 'LIVE',
  'accounts.list': 'LIVE',
  'accounts.create': 'LIVE',
  /** v1.9 신규 — 1원 인증 */
  'accounts.verify': 'LIVE',
  'accounts.verification': 'LIVE',
  /** 금융망 거래 동기화. 인증 성공 시 서버가 자동 접수하기도 한다 */
  'accounts.sync': 'LIVE',
  /** 연동 해제. 계좌 행은 남고 DISCONNECTED 가 된다 — 수집된 거래는 보존한다 */
  'accounts.disconnect': 'LIVE',
  /**
   * 계좌 삭제. 해제와 **다른 문**이다 — 계좌와 그 계좌의 거래를 실제로 지운다.
   * 되돌릴 수 없다. MOCK 을 두지 않는다: 목으로 「지웠습니다」를 보여주면
   * 사용자가 지운 줄 알고 넘어가는데 데이터는 그대로다.
   */
  'accounts.purge': 'LIVE',
  'transactions.import': 'LIVE',

  // 8. 거래·분류
  'transactionCategories.list': 'LIVE',
  'transactions.list': 'LIVE',
  'transactions.get': 'LIVE',
  'transactions.patch': 'LIVE',
  /**
   * 분류 수동 재실행 (명세 8.5). 동기화·CSV 업로드 완료 시에는 서버가 자동으로 건다.
   * 이미 도는 작업이 있으면 409 가 아니라 **그 작업을 그대로** 돌려준다.
   */
  'transactions.classificationJobs': 'LIVE',
  /**
   * 진행 중인 분류 작업 조회 (명세 8.5.1). 화면이 돌아왔을 때 진행 표시를 되살리는 데 쓴다.
   * **8.5 POST 로 대신할 수 없다** — 그쪽은 도는 것이 없으면 새로 만든다.
   * 도는 것이 없어도 200 · `{ running: false }` 다.
   */
  'transactions.classificationJobCurrent': 'LIVE',
  /** AI 제안 수락 (명세 8.4). 본문 없음 — 제안된 분류를 그대로 확정한다 */
  'transactions.confirmClassification': 'LIVE',

  // 13. 증빙
  /** 202 + 추출 Job. 원본은 evidences.file 로 받는다 */
  'evidences.upload': 'LIVE',
  /** 원본 미리보기(inline). 인증 헤더가 필요해 <img src> 로 못 받는다 — blob. 파일만 없으면 404 EVIDENCE_FILE_MISSING */
  'evidences.file': 'LIVE',
  /** needsReview 는 세 상태다 — 키가 없으면 추출 전이지 검산 통과가 아니다 */
  'evidences.list': 'LIVE',
  'evidences.get': 'LIVE',
  /** 보낸 필드만 바뀐다. confirmed:true 는 고친 것이 없어도 확인을 기록한다 */
  'evidences.patchExtraction': 'LIVE',
  /** 점수는 정렬 기준이지 판정이 아니다. autoLinkEligible 이어도 서버가 붙이지 않는다(구현현황 3.29) */
  'evidences.matchCandidates': 'LIVE',
  /** 증빙당 하나. 다른 거래에 붙어 있으면 409 EVIDENCE_ALREADY_LINKED */
  'evidences.link': 'LIVE',
  'evidences.unlink': 'LIVE',
  /** evidenceType 하나 */
  'evidences.patch': 'LIVE',
  /** 소프트 삭제 · 거래 연결은 끊는다 */
  'evidences.delete': 'LIVE',
  /** EXTRACTED 에서만 · 확정본은 409 */
  'evidences.reExtract': 'LIVE',

  // 9. 반복·예정 거래
  /** 예정 거래는 잔액 예측(P0-07)의 직접 입력이다. from·to 필수, 최대 92일 */
  'plannedTransactions.list': 'LIVE',
  'plannedTransactions.create': 'LIVE',
  'plannedTransactions.patch': 'LIVE',
  /** source=USER 만 지울 수 있다. 패턴 생성분은 409 — PATCH status=CANCELLED 로 대신한다 */
  'plannedTransactions.delete': 'LIVE',
  'recurringPatterns.list': 'LIVE',
  'recurringPatterns.get': 'LIVE',
  'recurringPatterns.confirm': 'LIVE',
  'recurringPatterns.dismiss': 'LIVE',
  'recurringPatterns.patch': 'LIVE',
  /**
   * 명세에 없는 임시 엔드포인트다 — 탐지를 언제 돌릴지가 정해지지 않아 배치 대신 수동 트리거로 뒀다.
   * 배치가 생기면 내부 호출로 바뀌고 이 키는 사라진다. 화면에 상시 버튼으로 두지 말 것.
   */
  'recurringPatterns.detect': 'LIVE',

  // 12. 안전선
  /** 미설정이면 404 가 아니라 값이 빈 200 이다 */
  'safetyLine.get': 'LIVE',
  'safetyLine.put': 'LIVE',
  /** 추천값은 고정비 집계가 필요해 P1 이다 */
  'safetyLine.recommendations': 'NOT_READY',

  // 10. 예상 잔액 곡선·정산 공백
  /**
   * 실행이 아직 없으면 409 FORECAST_NOT_READY, 계좌가 없으면 422 다.
   * 둘 다 오류 화면이 아니라 상태다 — 409 는 그 호출이 계산을 예약하므로 잠시 뒤 다시 부른다.
   */
  'forecast.balance': 'LIVE',
  'forecast.cashGaps': 'LIVE',
  /**
   * 명세에 없는 임시 엔드포인트다 — 재계산 트리거가 다 붙기 전까지의 진입점이다.
   * 202 만 받고 곡선은 몇 초 뒤에 바뀐다. 화면에 상시 버튼으로 두지 말 것.
   */
  'forecast.refresh': 'LIVE',
  /** 사업장별로 저장된다. 부분 수정이라 보내지 않은 필드는 그대로 둔다 */
  'forecastSettings.get': 'LIVE',
  /** applyResidualModel 을 바꾸면 서버가 재계산을 건다 — 곡선은 몇 초 뒤에 바뀐다 */
  'forecastSettings.patch': 'LIVE',

  // 10.3 인앱 알림
  /** 지금 실제로 알림을 만드는 것은 정산 불일치뿐이다. 목록은 유형을 가리지 않는다 */
  'alerts.list': 'LIVE',
  'alerts.ack': 'LIVE',

  // 11. What-if 시나리오
  /**
   * 11장 다섯 개가 전부 열렸다 (2026-09-16 · 구현현황 §3.38). 목 생성기는 지웠다.
   * 서버는 최신 완료 예측 실행 위에서 계산하므로 예보가 아직 없으면 409 FORECAST_NOT_READY 다.
   */
  'scenarios.create': 'LIVE',
  'scenarios.list': 'LIVE',
  'scenarios.get': 'LIVE',
  /** 이름 수정. 서버에는 있지만 웹 화면에는 아직 UI 가 없다 */
  'scenarios.patch': 'LIVE',
  'scenarios.delete': 'LIVE',

  // 14.1 정산 대사
  'settlements.list': 'LIVE',
  'settlements.create': 'LIVE',
  'settlements.get': 'LIVE',
  'settlements.patch': 'LIVE',
  'settlements.delete': 'LIVE',

  // 14.4~14.7 상품별 매출 구성비
  /**
   * 202 + 추출 Job. **1차 입력 경로다** — 한 달 80행을 손으로 치는 사람은 없다.
   * ⚠ 추출된 행은 DB 에 안 남는다. 결과는 jobs.get 의 resultRef 에만 있다
   */
  'productSales.ocr': 'LIVE',
  /** 200 이다(201 아님). 같은 기간 재등록은 그 기간을 통째로 교체한다 */
  'productSales.register': 'LIVE',
  /** 등록이 없으면 빈 200 이다 — 404 가 아니다. incomplete 는 hasMismatch 가 아니다 */
  'productSales.shares': 'LIVE',
  /** ⚠ 이 엔드포인트만 to < from 가드가 없다 — 뒤집힌 범위가 조용히 빈 목록으로 온다 */
  'productSales.trend': 'LIVE',
  /** 전 기간에 적용된다. 바꿀 대상이 없으면 400 — 조용한 0건 성공이 아니다 */
  'productSales.rename': 'LIVE',

  // 14.2 신고 준비자료 패키지 (P0-12 부속)
  /** 202 + EXPORT_GENERATION Job. exportType 은 TAX_PREP 하나뿐이고 모르는 값은 400 */
  'exports.create': 'LIVE',
  /** 명세 밖 경로(구현현황 4장). 페이징 없이 items 한 겹, 최근 것부터 */
  'exports.list': 'LIVE',
  /** ⚠ exportId 는 jobId 와 같은 값이다. 진행 중이면 파일 관련 키가 통째로 없다 */
  'exports.get': 'LIVE',
  /**
   * 파일(ZIP)이다. <a href> 로는 못 받는다 — 인증이 헤더라 fetch → blob 이다.
   * ⚠ 409 EXPORT_NOT_READY 와 410 EXPORT_EXPIRED 를 갈라 말해야 한다 — 410 은 기다리면 안 된다
   */
  'exports.download': 'LIVE',

  // 14.3 종합소득세 신고서 초안 (P0-12)
  'taxForms.list': 'LIVE',
  /** 202 + TAX_DRAFT_GENERATION Job. 안내 전용 서식은 400 FORM_NOT_SUPPORTED */
  'taxDrafts.create': 'LIVE',
  'taxDrafts.list': 'LIVE',
  /** ⚠ 조회가 상태를 바꿀 수 있다 — 근거가 달라졌으면 서버가 STALE 로 적는다 */
  'taxDrafts.get': 'LIVE',
  'taxDrafts.patch': 'LIVE',
  /** 명세 밖(구현현황 4장). 204. 집계 중이면 409 TAX_DRAFT_NOT_DELETABLE. 실제로 지운다 */
  'taxDrafts.delete': 'LIVE',
  'taxDrafts.basis': 'LIVE',
  /** 파일(XLSX)이다. READY 가 아니면 409 TAX_DRAFT_NOT_READY. PDF 는 400 */
  'taxDrafts.export': 'LIVE',
  // 8.7 검토 상태 일괄 변경 — 한 번에 200건까지
  'taxReview.bulk': 'LIVE',

  // 16. 대출·신용
  /** 등록이 없어도 빈 200 이다 */
  'loans.list': 'LIVE',
  /**
   * 202 + LOAN_SYNC Job. ⚠ **자동 배치가 없다** — 이 수동 호출이 유일한 갱신 경로다.
   * 화면에 동기화 버튼이 반드시 있어야 한다
   */
  'loans.sync': 'LIVE',
  /** ⚠ **부작용이 있는 GET 이다** — 금융망을 부르고 CREDIT_SNAPSHOT 행을 쓴다. 폴링 금지 */
  'credit.get': 'LIVE',
  /**
   * ⚠ 같은 이유로 폴링 금지. amount 가 실질 필수(없거나 0 이하면 400)다.
   * ⚠ recommendable 이 꺼진 새 DB 에서는 items: [] 인 정상 200 이 온다 — 오류가 아니다
   */
  'loanProducts.list': 'LIVE',

  // 17. 지원 제도·서류
  /** 빈 목록이 정상일 수 있다 — 구조화 안 된 공고는 서버가 뺀다. ⚠ sort=MATCH_SCORE 는 400 */
  'supportPrograms.list': 'LIVE',
  /** ⚠ 평평하지 않다 — 목록 필드가 summary 안에 있다 */
  'supportPrograms.get': 'LIVE',
  'supportPrograms.recommendations': 'LIVE',
  /** 응답이 items 한 겹이다 */
  'documentGuides.list': 'LIVE',

  // 2.7 공통 Job
  'jobs.get': 'LIVE',
} as const satisfies Record<string, EndpointState>;

export type EndpointKey = keyof typeof DECLARED;

/**
 * 배포 빌드에서는 MOCK을 NOT_READY로 강등한다.
 * 강등되지 않으면 시연 서버에 가짜 잔액이 진짜처럼 뜬다. 돈을 세는 화면에서 이건 사고다.
 */
export function resolveRegistry(opts: { isProduction: boolean }): Record<EndpointKey, EndpointState> {
  const out = {} as Record<EndpointKey, EndpointState>;
  for (const key of Object.keys(DECLARED) as EndpointKey[]) {
    const declared = DECLARED[key] as EndpointState;
    out[key] = opts.isProduction && declared === 'MOCK' ? 'NOT_READY' : declared;
  }
  return out;
}

export function getEndpointState(key: EndpointKey): EndpointState {
  return resolveRegistry({ isProduction: getConfig().isProduction })[key];
}

/** 개발 중 MOCK 응답에는 화면에 목데이터 배지를 띄워야 한다(CLAUDE.md). */
export function isMockData(key: EndpointKey): boolean {
  return getEndpointState(key) === 'MOCK';
}
