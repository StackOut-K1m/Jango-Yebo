// docs/api/14_정산_내보내기.md 14.4~14.7
//
// 상품별 매출 구성비 (P1-22). 14.1 정산 대사가 14_settlement.ts 를 이미 쓰고 있어
// 한 장에 파일이 둘이다 — 장 번호 규칙은 그대로 따른다.
//
// **원가를 모르므로 수익성을 말하지 않는다.** 점유율·증감·할인율까지다 —
// 매출 1위 상품이 적자일 수 있고, 마진은 P2-03 범위다(SRS 12장 「확정 표현 금지」).

/** 행 단위로 다를 수 있다. 생략하면 OCR 이다 */
export type ProductSalesSourceType = 'OCR' | 'OCR_CORRECTED' | 'MANUAL';

/**
 * 구성비의 분모가 무엇인가.
 *
 * BILLED(결제금액)가 원칙이고, 그 열이 없는 인쇄물에서 GROSS(총매출)로 내려간다 —
 * 58mm 감열지에는 **총매출 열이 아예 인쇄되지 않는다**(실측 행의 33.3%).
 */
export type ShareBasis = 'BILLED' | 'GROSS';

/**
 * 인쇄물 합계 행과의 대조 결과.
 *
 * ⚠ **MISMATCHED 는 오류가 아니다.** 실측 라벨 90개 중 20개(22.2%)가 그렇고 그것이
 * 정상이다 — 인쇄물이 여러 장으로 잘리면 합계 행은 기간 전체를 담는다. 빨간 경고로 그리면
 * 사장님이 매달 경고를 본다(도메인 규칙 6).
 */
export type ChecksumStatus = 'MATCHED' | 'MISMATCHED' | 'UNAVAILABLE';

/** 등록 기간 하나 */
export interface ProductSalesPeriod {
  from: string;
  to: string;
}

// --- 14.4 등록 --------------------------------------------------------------

/** 인쇄물 맨 아래 합계 행. 항목 합과 대조하는 무료 정합성 검사다 */
export interface ProductSalesChecksum {
  quantity?: number;
  grossAmount?: number;
  billedAmount?: number;
}

/**
 * 상품 한 줄.
 *
 * ⚠ **grossAmount 와 billedAmount 중 하나는 있어야 한다** — 둘 다 없으면 구성비의
 * 분모를 만들 수 없어 400 이다. 반대로 둘 다 요구하지는 않는다.
 *
 * ⚠ **수량은 정수가 아니어도 되고 음수도 된다** — 무게 단위 상품(정육·반찬)이 있고,
 * 취소·반품을 음수로 넣는다.
 */
export interface ProductSalesItemInput {
  productName: string;
  quantity?: number;
  grossAmount?: number;
  discountAmount?: number;
  netAmount?: number;
  vatAmount?: number;
  billedAmount?: number;
  /** 행마다 다르면 여기서 덮는다 */
  sourceType?: ProductSalesSourceType;
}

/**
 * 14.4 요청.
 *
 * ⚠ **같은 기간 재등록은 그 기간을 통째로 교체한다.** 이번에 빠진 상품은 삭제된다 —
 * 지난번 오인식이 분모에 계속 남는 것을 막는 규칙이다.
 *
 * ⚠ **같은 상품이 두 번 들어오면 400 이다**(정규화 후 같은 키면 중복이다).
 *
 * ⚠ 금액 검증 「총매출 − 할인 = 공급가 + 부가세」는 **넷이 다 있을 때만** 본다.
 *   하나라도 없으면 건너뛴다.
 */
export interface RegisterProductSalesRequest {
  /** 영업일이다. 자정을 넘긴 매출은 전날에 귀속한다 */
  periodStart: string;
  periodEnd: string;
  /** 생략하면 OCR */
  sourceType?: ProductSalesSourceType;
  checksum?: ProductSalesChecksum;
  /**
   * 14.7 추출 기록과 이 행들을 잇는 확장 필드. **OCR Job 의
   * resultRef.extractionId 를 그대로 넣는다.**
   *
   * 넣으면 저장되는 모든 행에 찍히고, 14.6 으로 이름을 바꿔도 따라간다 — 구성비가 나중에
   * 틀린 것으로 드러났을 때 「모델이 그때 무엇을 읽었나」에 답하는 유일한 경로다
   * (JOB.result 는 90일 뒤 사라지지만 추출 기록은 남는다).
   *
   * 수기 입력이면 생략한다. 생략해도 저장은 되고 추적만 안 될 뿐이다.
   */
  extractionId?: number;
  items: ProductSalesItemInput[];
}

/** 합계 행과 얼마나 어긋났나. 어긋나도 저장은 된다 */
export interface ChecksumDetail {
  field?: string;
  itemsTotal?: number;
  printedTotal?: number;
  difference?: number;
  message?: string;
}

/** 기존 상품과 표기가 비슷한 신규 상품. 14.6 일괄 변경과 짝이다 */
export interface SimilarName {
  productName: string;
  similarTo: string;
}

/**
 * 14.4 응답. **상태 코드가 200 이다**(201 아님).
 *
 * ⚠ warnings 의 원소가 14.5 와 **다른 타입**이다 — 여기는 SimilarName,
 * 14.5 는 { code, message } 다. 타입을 공유하지 말 것.
 */
export interface RegisterProductSalesResult {
  periodStart: string;
  periodEnd: string;
  savedCount: number;
  /** 기간 교체로 지워진 행 수 */
  removedCount: number;
  shareBasis: ShareBasis;
  checksumStatus: ChecksumStatus;
  /** 체크섬을 안 보냈으면 없다 */
  checksumDetail?: ChecksumDetail;
  /** 이번에 처음 등장한 상품. 저장 전 사용자 확인을 받는 장치다 */
  newProducts: string[];
  /**
   * 이 사업장의 첫 등록.
   *
   * true 면 전 품목이 신규라 80개를 하나씩 확인시키는 것이 의미가 없다 —
   * 화면은 **일괄 확인**을 낸다.
   */
  bootstrap: boolean;
  warnings: SimilarName[];
}

// --- 14.5 구성비 조회 --------------------------------------------------------

/**
 * ⚠ **여섯 금액이 각각 널 가능하다.** 없는 열을 0 으로 채우지 않는다 —
 * 58mm 인쇄물에는 총매출 열이 아예 없다.
 */
export interface ProductSalesTotals {
  quantity?: number;
  grossAmount?: number;
  discountAmount?: number;
  netAmount?: number;
  vatAmount?: number;
  billedAmount?: number;
}

/** 숫자를 그대로 믿으면 안 되는 사정. 실제로 오는 코드는 이 넷이다 */
export type ProductSalesWarningCode =
  /** 등록 기간이 서로 겹친다. 겹친 날의 매출이 두 번 더해진다 */
  | 'PERIODS_OVERLAP'
  /** 등록 기간이 조회 범위 밖으로 걸쳐 있다. **물어본 것보다 넓은 기간**의 숫자다 */
  | 'PERIOD_PARTIALLY_OUTSIDE'
  /** 일부 등록에만 있던 열이다. 그 합계는 **일부 기간만 더한 값**이다 */
  | 'PARTIAL_COLUMN'
  /** 결제금액 없는 상품이 섞여 총매출 기준으로 내려갔다 */
  | 'MIXED_SHARE_BASIS';

export interface ProductSalesWarning {
  code: ProductSalesWarningCode;
  message: string;
}

/**
 * 상품 한 줄의 지표.
 *
 * ⚠ changeRate 는 **직전 기간에 등록이 아예 없으면 없다.** 이때 「신규 성장」으로 그리면
 * 첫 달 리포트가 통째로 거짓이 된다.
 *
 * 비율은 소수 셋째 자리다 — 80품목짜리 인쇄물의 꼬리에서는 0.05% 가 0.001 로,
 * 그보다 작으면 0 으로 떨어진다.
 */
export interface ProductShareItem {
  productKey: string;
  productName: string;
  quantity?: number;
  grossAmount?: number;
  discountAmount?: number;
  netAmount?: number;
  vatAmount?: number;
  billedAmount?: number;
  /** 합계 기준 점유율. POS 가 인쇄하는 값과 분모를 맞춘 것이다 */
  share?: number;
  /** 할인액 ÷ 총매출. 할인 열이 없으면 없다 */
  discountRate?: number;
  /** 직전 동일 길이 기간 대비 증감률 */
  changeRate?: number;
  /** 총매출 변동 중 이 상품이 차지한 금액. 「이번 달 매출이 왜 줄었나」에 답하는 지표다 */
  contributionToChange?: number;
}

/**
 * 14.5 응답.
 *
 * ⚠ **필드 이름이 hasMismatch 가 아니라 incomplete 다**(명세 쪽 이름과 다르다).
 *
 * 등록이 하나도 없으면 **빈 200** 이다. 404 가 아니다 —
 * 아직 인쇄물을 안 올린 사업장이 정상이다(화면구성도 6.4).
 */
export interface ProductSalesShares {
  period: ProductSalesPeriod;
  shareBasis: ShareBasis;
  /** 조회 기간에 MISMATCHED 인 등록이 섞여 있다. 구성비는 계산하되 「불완전」을 표시한다 */
  incomplete: boolean;
  totals: ProductSalesTotals;
  items: ProductShareItem[];
  warnings: ProductSalesWarning[];
}

/** AMOUNT 는 **구성비 기준 금액** 내림차순이다. 값이 없는 행은 어느 정렬에서도 뒤로 간다 */
export type ProductSalesSort = 'AMOUNT' | 'QUANTITY' | 'NAME' | 'CHANGE';

/**
 * 14.5 질의. 생략하면 **이번 달 1일부터 오늘까지**다.
 *
 * ⚠ 모르는 sort 는 400 이다.
 *
 * interface 가 아니라 type 인 이유는 PageQuery 와 같다(02_common).
 */
export type ProductSalesQuery = {
  from?: string;
  to?: string;
  sort?: ProductSalesSort;
};

// --- 14.5 상품별 추이 --------------------------------------------------------

/**
 * 한 등록 기간의 점.
 *
 * ⚠ **netAmount·vatAmount 가 없다.** ProductShareItem 과 필드가 다르니
 * 타입을 공유하지 말 것.
 *
 * ⚠ share 는 **그 기간 안에서의** 점유율이라 점마다 분모가 다르다.
 */
export interface ProductTrendPoint {
  periodStart: string;
  periodEnd: string;
  shareBasis: ShareBasis;
  quantity?: number;
  grossAmount?: number;
  discountAmount?: number;
  billedAmount?: number;
  share?: number;
  discountRate?: number;
  checksumStatus: ChecksumStatus;
}

/**
 * 14.5 두 번째 엔드포인트.
 *
 * **일자별은 없다** — 등록 기간이 곧 해상도이고, 안 팔린 기간은 **0 이 아니라 아예 빠진다**
 * (안 판 것과 못 읽은 것이 구분되지 않게 된다).
 */
export interface ProductSalesTrend {
  /** 정규화된 키. 넣은 표시 이름이 아니다 */
  productKey: string;
  /** 점이 하나도 없으면 없다 */
  productName?: string;
  items: ProductTrendPoint[];
}

/** 생략하면 **최근 12개월**이다. 월 단위 등록이라 열두 점이면 계절성이 보인다 */
export type ProductTrendQuery = {
  from?: string;
  to?: string;
};

// --- 14.6 상품명 일괄 변경 ---------------------------------------------------

/** ⚠ **전 기간에 적용된다.** 한 기간만 고치면 추이에서 같은 상품이 두 줄로 갈라진 채 남는다 */
export interface RenameProductRequest {
  from: string;
  to: string;
}

/**
 * 14.6 응답.
 *
 * ⚠ keyUnchanged 가 true 면 두 이름이 **같은 키로 정규화된 것**이다
 * (「카페 라떼」 → 「카페라떼」 — 공백은 정규화가 이미 지운다).
 * 합쳐진 것이 없고 표시 글자만 바뀐 상태라 화면 문구가 달라야 한다.
 *
 * ⚠ 바꿀 대상이 없으면 **400 이다.** 조용한 0건 성공으로 두지 않았다 —
 * 그러면 사용자는 바뀐 줄 안다.
 */
export interface RenameProductResult {
  from: string;
  to: string;
  productKey: string;
  keyUnchanged: boolean;
  /** 대상 키가 없어 그냥 옮긴 기간 수 */
  renamedCount: number;
  /** 대상 키가 이미 있어 **수량·금액을 합친** 기간 수 */
  mergedCount: number;
  periods: ProductSalesPeriod[];
}

// --- 14.7 인쇄물 OCR ---------------------------------------------------------

/** 레이아웃·글꼴·방해 요소가 달라 프롬프트와 기대 정확도가 갈린다 */
export type CaptureType = 'PRINTOUT' | 'SCREEN';

export interface ProductSalesOcrOptions {
  /** 생략하면 PRINTOUT — 1차 입력 경로가 감열 인쇄물이다 */
  captureType?: CaptureType;
}

/**
 * 인쇄물 머리글에서 읽은 기간.
 *
 * ⚠ **source 가 없는 것이 정상이다** — 기간 머리글이 없는 인쇄물이 있어서
 * 일부러 PERIOD_MISSING 같은 사유를 두지 않았다. 화면이 이 키를 직접 보고
 * 「기간을 확인해 주세요」를 띄운다.
 */
export interface ProductSalesOcrPeriod {
  start?: string;
  end?: string;
  source?: 'HEADER';
}

/** 추출된 상품 한 줄. **저장되지 않은 후보다** */
export interface ProductSalesOcrRow {
  productName: string;
  quantity?: number;
  grossAmount?: number;
  discountAmount?: number;
  netAmount?: number;
  vatAmount?: number;
  billedAmount?: number;
  /** 인쇄물에 찍힌 구성비. **대조용이고 저장되지 않는다** — 우리 계산과 다르면 오독이다 */
  share?: number;
  confidence?: number;
}

/** 합계 행. 항목 행과 분리해 체크섬으로 쓴다 */
export interface ProductSalesPrintedTotal {
  quantity?: number;
  grossAmount?: number;
  discountAmount?: number;
  netAmount?: number;
  vatAmount?: number;
  billedAmount?: number;
}

/**
 * 워커 검산 사유.
 *
 * ⚠ **PERIOD_MISSING 이 없는 것은 의도다** — 기간이 안 찍힌 인쇄물이 정상이라
 * 사유로 넣으면 검토 게이트가 2.2% 에서 15.6% 로 뛴다.
 */
export type SalesReviewReason =
  | 'ROW_ARITHMETIC_INCONSISTENT'
  | 'TOTAL_ARITHMETIC_INCONSISTENT'
  | 'TOTAL_MISSING'
  | 'NO_ROWS';

/**
 * 워커 검산 (계약 v1.18).
 *
 * ⚠ **checksumStatus 와 다른 검사다.** 이쪽은 행 안의 산술 4식과 합계 행 자체의 정합을
 * 보고, checksumStatus 는 **행이 빠졌는지**를 본다. 하나로 다른 하나를 대신할 수 없다.
 *
 * ⚠ 「Σ품목 = 합계 행」은 **일부러 넣지 않았다** — 정상 인쇄물의 22% 를 틀렸다고 잡는다.
 */
export interface ProductSalesValidation {
  /** 값이 없어 식을 세울 수 없으면 없다. 「통과」가 아니다 */
  rowArithmeticConsistent?: boolean;
  printedTotalConsistent?: boolean;
  /** 0부터 세는 행 번호 */
  inconsistentRows: number[];
  needsReview: boolean;
  reviewReasons: SalesReviewReason[];
  issues: string[];
}

/**
 * productSales.ocr Job 의 resultRef (14.7).
 *
 * ⚠ **여기가 다른 Job 들과 다르다.** 추출된 상품 행은 DB 에 저장되지 않는다 —
 * PRODUCT_SALES_EXTRACTION 에는 기간·읽은 열·합계 행·검산·모델 메타만 남고
 * 행은 JOB.result 에만 있다. 증빙(13장)처럼 목록을 다시 부르는 방식이 통하지 않는다.
 *
 * 화면은 이 값을 검토 화면에 띄우고, 사용자가 고친 뒤 14.4 로 저장한다.
 *
 * ⚠ **Job 실패는 HTTP 200 안에 error.code 로 온다.** 비 2xx 가 아니다.
 */
export interface ProductSalesOcrResult {
  /**
   * 추출 기록의 id. **서버가 붙인다**(워커가 보낸 값이 아니다 — 추출 행은 이 콜백에서
   * 처음 생겨 워커는 id 를 모른다).
   *
   * 검토 화면이 이 값을 들고 있다가 14.4 의 extractionId 로 되돌려 보낸다.
   * 그래야 저장된 행이 「어느 추출에서 왔나」를 가리킨다.
   */
  extractionId?: number;
  /** 워커가 보고한 원본 상태. PARTIAL 도 Job 은 COMPLETED 다 */
  status?: 'COMPLETED' | 'PARTIAL';
  /** status 가 PARTIAL 일 때만 서버가 붙인다 */
  partial?: boolean;
  period: ProductSalesOcrPeriod;
  /** **읽은 열만** 온다. 없는 열을 채우지 않는다 */
  columnsDetected: string[];
  items: ProductSalesOcrRow[];
  printedTotal?: ProductSalesPrintedTotal;
  /** 못 읽은 행 등. 문자열이다 — 14.5 의 { code, message } 와 다르다 */
  warnings: string[];
  validation?: ProductSalesValidation;
  modelName?: string;
  modelVersion?: string;
  promptVersion?: string;
  pipeline?: string;
}
