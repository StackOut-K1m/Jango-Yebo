// docs/api/14_정산_내보내기.md 14.1 — 정산 수동 대사 (P0-11)
//
// 플랫폼이 알려준 매출과 실제 입금액을 맞춰 본다. 배달 플랫폼은 총매출에서
// 취소·수수료를 뺀 금액을 며칠 뒤에 넣어 주므로, 계좌만 봐서는 "얼마가 들어와야
// 하는지"가 보이지 않는다.
//
// 14.2 내보내기와 14.3 신고서 초안은 아직 백엔드에 없다(P0-12).

/**
 * PENDING은 불일치가 아니다 — 매칭된 입금 거래가 아직 없는 정상 상태다.
 * 정산 주기 안에서는 대부분 이 상태로 시작한다.
 */
export type SettlementStatus = 'PENDING' | 'MATCHED' | 'MISMATCHED';

/**
 * expectedPayout·difference는 서버가 매번 계산해 내려준다(저장하지 않는다).
 *
 * - `expectedPayout = grossSales − cancellations − fees`
 * - `difference = actualDeposit − expectedPayout` (음수면 덜 들어온 것)
 */
export interface Settlement {
  settlementId: number;
  platformName: string;
  periodStart: string;
  periodEnd: string;
  grossSales: number;
  cancellations: number;
  fees: number;
  expectedPayout: number;
  /** 매칭된 입금 거래의 합계. 매칭이 없으면 0 */
  actualDeposit: number;
  difference: number;
  status: SettlementStatus;
  createdAt: string;
}

/**
 * cancellations·fees는 생략하면 0이다.
 *
 * ⚠ matchedTransactionIds에 넣을 수 있는 것은 **같은 사업장의 입금 거래**뿐이다.
 * 출금이면 400, 남의 사업장 거래면 403이다.
 */
export interface CreateSettlementRequest {
  platformName: string;
  periodStart: string;
  periodEnd: string;
  grossSales: number;
  cancellations?: number;
  fees?: number;
  matchedTransactionIds?: number[];
}

/**
 * 전달한 필드만 바뀐다.
 *
 * ⚠ matchedTransactionIds는 **전량 교체**다. 부분 추가·삭제가 아니다.
 * 생략하면 기존 매칭을 유지하고 금액 변경분만 다시 계산한다.
 */
export type UpdateSettlementRequest = Partial<CreateSettlementRequest>;

// --- 10.3 인앱 알림 --------------------------------------------------------

export type AlertType =
  | 'CASH_GAP'
  | 'SAFETY_LINE'
  | 'RESERVE_SHORTFALL'
  | 'SETTLEMENT_MISMATCH'
  | 'PATTERN_CANDIDATE'
  | 'EVIDENCE_MISSING';

export type AlertStatus = 'ACTIVE' | 'ACKNOWLEDGED';

/**
 * ⚠ 지금 실제로 만들어지는 것은 셋이다(2026-09-15) —
 * SETTLEMENT_MISMATCH · CASH_GAP(잔액 0원 미만) · SAFETY_LINE(안전선 하회).
 * 나머지 유형은 각 기능이 붙을 때 채워진다 — 목록 API는 유형을 가리지 않으므로
 * 화면은 지금 붙여도 나중에 안 고친다.
 *
 * ⚠ CASH_GAP 과 SAFETY_LINE 은 **같은 골짜기에 동시에 뜨지 않는다.** 하회 구간에
 * 0원 미만인 날이 하루라도 끼면 CASH_GAP 만 뜬다 — 둘 다 띄우면 급한 쪽이 묻힌다.
 *
 * ⚠ PUT /safety-line 으로 안전선을 바꾸면 서버가 SAFETY_LINE 알림을 새 기준으로
 * 다시 맞춘다. 예보 곡선은 그대로지만 **알림 목록은 무효화해야** 옛 경고가 안 남는다.
 *
 * 같은 원인의 알림은 중복 생성되지 않고 갱신된다. 원인이 해소되면
 * 서버가 ACKNOWLEDGED로 내린다 — 해결한 것이 계속 경고로 남지 않는다.
 */
export interface Alert {
  alertId: number;
  type: AlertType;
  /** 잔액과 무관한 알림(정산 불일치 등)은 null */
  riskLevel: 'SAFE' | 'CAUTION' | 'DANGER' | 'GAP' | null;
  title: string;
  message: string;
  /** 화면이 이동할 대상. 정산 불일치면 { settlementId } */
  relatedRef: Record<string, unknown> | null;
  detail: Record<string, unknown> | null;
  status: AlertStatus;
  createdAt: string;
}
