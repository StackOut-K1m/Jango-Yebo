import { request } from './client';
import type { ApiResult } from './envelope';
import { getEndpointState } from './registry';
import type { Job } from './types/02_common';
import type {
  ProductSalesOcrOptions,
  ProductSalesOcrResult,
  ProductSalesQuery,
  ProductSalesShares,
  ProductSalesTrend,
  ProductTrendQuery,
  RegisterProductSalesRequest,
  RegisterProductSalesResult,
  RenameProductRequest,
  RenameProductResult,
} from './types/14_productsales';

/**
 * 상품별 매출 구성비 (API 14.4~14.7, P1-22).
 *
 * 입력 경로는 둘이고 **OCR 이 1차**다 — 한 달 80행을 손으로 치는 사람은 없다.
 * 인쇄물 촬영(14.7) → Job 폴링 → 사용자 검토 → 14.4 저장 → 14.5 조회로 이어진다.
 *
 * 매출의 단일 출처는 계좌 입금(TRANSACTION)이다. 여기 담기는 것은 절대액이 아니라
 * **구성비**다 — 인쇄물 총매출이 통장 입금 합계보다 1할쯤 큰 것은 어긋난 것이 아니라
 * 정산 전과 정산 후의 차이다(공통규약 1.5).
 */

/**
 * 인쇄물 OCR 등록 (14.7). 202 — resultRef 는 ProductSalesOcrResult 다.
 *
 * 파일 파트 이름은 **files** 다(files[] 가 아니다). 1~10장이고, 넘으면 400 이다 —
 * 화면 촬영은 스크롤 때문에, 인쇄물은 길이 때문에 여러 장이 된다.
 *
 * ⚠ 형식 검증이 Job 생성보다 먼저다. 한 장이라도 거부되면 Job 이 아예 안 만들어진다.
 *
 * ⚠ 크기 제한을 넘으면 **400 EVIDENCE_FILE_UNSUPPORTED** 가 온다 —
 * 상품 매출인데 증빙 코드가 오는 것은 전역 핸들러를 공유하기 때문이다.
 *
 * ⚠ **결과는 저장되지 않는다.** 폴링해서 받은 행을 검토 화면에 띄우고, 사용자가 고친 뒤
 * registerProductSales(14.4)로 저장한다. 목록을 다시 부르는 방식이 통하지 않는다.
 */
export function uploadProductSalesOcr(
  businessId: number,
  files: { blob: Blob; fileName: string }[],
  options: ProductSalesOcrOptions = {},
): Promise<ApiResult<Job<ProductSalesOcrResult>>> {
  const form = new FormData();
  // 같은 이름의 파트를 반복해 보낸다. 순서가 곧 장 순서이고 워커가 그 순서로 이어 붙인다.
  for (const { blob, fileName } of files) form.append('files', blob, fileName);
  // captureType 은 @RequestParam 이라 파일이 아닌 **일반 폼 필드**로 넣는다.
  if (options.captureType) form.append('captureType', options.captureType);

  return request<Job<ProductSalesOcrResult>>(
    'productSales.ocr',
    `/businesses/${businessId}/product-sales/ocr`,
    { method: 'POST', body: form },
  );
}

/**
 * 상품별 매출 등록 (14.4). **200 이다**(201 아님).
 *
 * ⚠ **같은 기간 재등록은 그 기간을 통째로 교체한다** — 이번에 빠진 상품은 삭제된다.
 *
 * ⚠ **체크섬이 안 맞아도 저장된다.** 실측 라벨 90개 중 20개가 안 맞고 그게 정상이다
 * (인쇄물이 여러 장으로 잘린다). 오류로 다루지 말 것.
 */
export function registerProductSales(
  businessId: number,
  body: RegisterProductSalesRequest,
): Promise<ApiResult<RegisterProductSalesResult>> {
  return request<RegisterProductSalesResult>(
    'productSales.register',
    `/businesses/${businessId}/product-sales`,
    { method: 'POST', json: body },
  );
}

/**
 * 구성비 조회 (14.5).
 *
 * 등록이 없으면 **빈 200** 이다 — 404 가 아니다.
 *
 * 서버 기본값은 「이번 달 1일~오늘」이다. 그 날짜를 `LocalDate.now()` 로 잡아 **JVM 기본
 * 시간대**를 쓰는데(대출 쪽은 Asia/Seoul 을 명시한다), 배포는 compose 가 컨테이너마다
 * `TZ=Asia/Seoul` 을 주므로 실제로 어긋나지 않는다. 다른 시간대에서 서버를 띄운 로컬 개발에서만
 * 월 경계가 하루 밀린다 — 화면이 기간을 명시해 보내면 그 경우도 없앨 수 있다.
 */
export function getProductSalesShares(
  businessId: number,
  query: ProductSalesQuery = {},
): Promise<ApiResult<ProductSalesShares>> {
  return request<ProductSalesShares>(
    'productSales.shares',
    `/businesses/${businessId}/product-sales`,
    { query },
  );
}

/**
 * 상품 하나의 기간별 추이 (14.5).
 *
 * productKey 자리에 **표시 이름을 그대로 넣어도 된다** — 서버가 같은 규칙으로 정규화한다.
 * 이름에 슬래시·물음표가 들어갈 수 있어 인코딩해서 넣는다.
 *
 * ⚠ **이 엔드포인트만 `to < from` 가드가 없다.** 뒤집힌 범위가 조용히 빈 목록으로 오므로
 * 여기서 막는다 — 서버가 400 을 주지 않아 화면은 「안 팔렸다」로 읽게 된다.
 */
export function getProductSalesTrend(
  businessId: number,
  productKey: string,
  query: ProductTrendQuery = {},
): Promise<ApiResult<ProductSalesTrend>> {
  // 레지스트리를 먼저 본다. NOT_READY 는 오류가 아니라 정상 빈 상태이므로(화면구성도 6.4)
  // 아래 검증이 그보다 앞서면 그 규칙이 이 함수에서만 깨진다.
  if (getEndpointState('productSales.trend') === 'NOT_READY') {
    return Promise.resolve({ ready: false });
  }
  if (query.from && query.to && query.to < query.from) {
    return Promise.resolve({
      ready: true,
      ok: false,
      status: 400,
      error: { code: 'VALIDATION_FAILED', message: '조회 종료일이 시작일보다 앞섭니다.' },
    });
  }
  return request<ProductSalesTrend>(
    'productSales.trend',
    `/businesses/${businessId}/product-sales/${encodeURIComponent(productKey)}`,
    { query },
  );
}

/**
 * 상품명 일괄 변경 (14.6). **전 기간에 적용된다.**
 *
 * 마스터 테이블이 없어 표기 흔들림·오타 정리의 유일한 수단이다.
 *
 * ⚠ 바꿀 대상이 없거나 from 과 to 가 같으면 **400 이다.**
 *
 * ⚠ 응답의 keyUnchanged 가 true 면 합쳐진 것 없이 표시 글자만 바뀐 것이다 —
 * 화면 문구가 달라야 한다.
 */
export function renameProduct(
  businessId: number,
  body: RenameProductRequest,
): Promise<ApiResult<RenameProductResult>> {
  return request<RenameProductResult>(
    'productSales.rename',
    `/businesses/${businessId}/product-sales/rename`,
    { method: 'PATCH', json: body },
  );
}
