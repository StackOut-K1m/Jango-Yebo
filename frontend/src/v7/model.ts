import {
  countedAccounts,
  formatManwon,
  formatManwonRange,
  formatMoney,
  getBalanceForecast,
  getCashGaps,
  getSafetyLine,
  listAccounts,
  listLoans,
  listPlannedTransactions,
  listTransactions,
} from '@jangoyebo/core';
import type {
  Account,
  ApiResult,
  BalanceForecast,
  CashGapItem,
  LoanItem,
  PlannedTransaction,
  Transaction,
} from '@jangoyebo/core';
import { useQuery } from '@tanstack/react-query';
import { koreanDate } from './transactionHistory';

/* ⚠ 쿼리 키는 반드시 'v7' 로 시작한다.
 *   기존 화면(AccountList 등)이 ['accounts', id] 를 이미 쓰는데 그쪽은 배열을,
 *   여기서는 ApiResult 를 캐시에 넣는다. 키가 같으면 먼저 채운 쪽이 이기고
 *   상대 화면이 통째로 죽는다 — 실제로 계좌 화면이 흰 화면이 됐다.
 */

/* ══════════════════════════════════════════════════════════════════════
   v7 화면이 쓰는 데이터.

   시안의 `data.ts` 는 더미 배열이었다. 여기서는 같은 모양을 **실제 API** 로 채운다.
   화면 코드는 이 파일만 보므로 서버가 바뀌어도 마크업은 안 바뀐다.
   ══════════════════════════════════════════════════════════════════════ */

/** 큰 숫자 옆에 단위 '원'을 작게 따로 붙이는 자리에서만 쓴다. 글 안에서는 core 포맷터를 그대로 쓴다. */
export const money = (value: number | null | undefined) =>
  formatMoney(value).replace(/원$/, '');
export const shortMoney = (value: number | null | undefined) =>
  formatManwon(value).replace(/원$/, '');
/**
 * 날짜 카드용 짧은 금액. 만원 반올림이 안전선을 넘나들면 소수 한 자리까지 적는다 —
 * 99만 8,500원이 「100만」으로 찍히면 안전선 100만원과 같아 보이는데 날씨는 「흐림」이라 앞뒤가 안 맞는다.
 * 안전선 쪽으로 넘어가지 않게 아래면 내리고 위면 올린다.
 */
export function shortMoneyNear(value: number, line: number | null) {
  const rounded = Math.round(value / 10_000) * 10_000;
  if (line === null || value < 10_000 || value < line === rounded < line) return shortMoney(value);
  const tenths = (value < line ? Math.floor : Math.ceil)(value / 1_000) / 10;
  return `${tenths.toLocaleString('ko-KR')}만`;
}
export const shortRange = (min: number | null | undefined, max: number | null | undefined) =>
  formatManwonRange(min, max).replace(/원$/, '');

/** 곡선의 하루. 시안의 점 모양을 그대로 유지한다. */
export interface Point {
  index: number;
  /**
   * `2026-09-24`. 서버가 준 날짜 그대로다.
   *
   * 화면 문구(`date`·`label`)는 사람이 읽는 형태라 <b>날짜끼리 견줄 수 없다.</b> 정산 공백을
   * 곡선 위에 그리려면 공백의 시작·회복 날짜를 점의 인덱스로 바꿔야 하고, 그 대조는 이 값으로 한다.
   */
  iso: string;
  /** `9.24` */
  label: string;
  /** `9월 24일` */
  date: string;
  weekday: string;
  value: number;
  min: number;
  max: number;
}

const WEEKDAY = ['일', '월', '화', '수', '목', '금', '토'];

/**
 * 서버 곡선 → 화면 점.
 *
 * ⚠ 응답의 모든 필드가 옵셔널이다(구현현황 3.3). 키가 없을 수 있으므로
 * expectedBalance 가 없는 날은 버린다 — 0 으로 채우면 곡선이 바닥을 찍는다.
 *
 * 오늘(KST)까지의 날도 버린다. 곡선은 계산한 날 다음부터라 계산이 며칠 묵으면
 * 이미 지난 날이 「예측」으로 남고, 거래로 그린 과거 곡선과 같은 날이 두 번 나온다.
 */
export function toPoints(forecast: BalanceForecast | undefined, now = new Date()): Point[] {
  const curve = forecast?.curve ?? [];
  const today = koreanDate(now);
  const points: Point[] = [];
  for (const day of curve) {
    if (day.expectedBalance === undefined || !day.date || day.date <= today) continue;
    const d = new Date(`${day.date}T00:00:00Z`);
    points.push({
      index: points.length,
      iso: day.date,
      label: `${d.getUTCMonth() + 1}.${String(d.getUTCDate()).padStart(2, '0')}`,
      date: `${d.getUTCMonth() + 1}월 ${d.getUTCDate()}일`,
      weekday: WEEKDAY[d.getUTCDay()],
      value: day.expectedBalance,
      min: day.balanceMin ?? day.expectedBalance,
      max: day.balanceMax ?? day.expectedBalance,
    });
  }
  return points;
}

/** 곡선이 아직 없거나(409) 계좌가 없어(422) 못 그리는 상태. 오류가 아니다. */
export type ForecastPhase = 'loading' | 'ready' | 'preparing' | 'no-account' | 'not-ready' | 'error';

export interface ForecastState {
  phase: ForecastPhase;
  forecast?: BalanceForecast;
  points: Point[];
  message?: string;
  /** 오류 안내의 「다시 시도」가 부른다 */
  retry: () => void;
  retrying: boolean;
}

/**
 * 예상 잔액 곡선 (10.1).
 *
 * 409 FORECAST_NOT_READY 는 정상 흐름이다 — 이 호출이 계산을 예약하므로
 * 잠시 뒤 다시 부른다. 오류 화면을 띄우면 사용자는 고장으로 읽는다.
 */
export function useForecast(businessId: number | undefined): ForecastState {
  const query = useQuery({
    queryKey: ['v7', 'forecast', businessId],
    enabled: businessId !== undefined,
    queryFn: () => getBalanceForecast(businessId as number),
    // 준비 중일 때만 다시 묻는다. 다 된 곡선을 계속 두드리지 않는다.
    refetchInterval: (q) => {
      const r = q.state.data;
      return r && r.ready && !r.ok && r.status === 409 ? 4000 : false;
    },
    staleTime: 60_000,
  });

  const again = { retry: () => void query.refetch(), retrying: query.isFetching };
  if (query.isPending) return { phase: 'loading', points: [], ...again };
  const result = query.data;
  if (!result) return { phase: 'error', points: [], message: '예보를 불러오지 못했습니다.', ...again };
  if (!result.ready) return { phase: 'not-ready', points: [], ...again };
  if (!result.ok) {
    if (result.status === 409) return { phase: 'preparing', points: [], ...again };
    if (result.status === 422) return { phase: 'no-account', points: [], ...again };
    // 서버에 아직 이 경로가 없는 경우. 오류가 아니라 "안 열린 기능"이다.
    // ⚠ 지금 로컬 백엔드는 모르는 경로에 404 가 아니라 400 VALIDATION_FAILED 를 준다 —
    //   예보 API 가 머지된 뒤 백엔드를 다시 띄우면 사라진다.
    if (result.status === 404 || result.error.code === 'ENDPOINT_NOT_IMPLEMENTED')
      return { phase: 'not-ready', points: [], ...again };
    return { phase: 'error', points: [], message: result.error.message, ...again };
  }
  return { phase: 'ready', forecast: result.data, points: toPoints(result.data), ...again };
}

/**
 * 목록 조회의 상태. **빈 목록을 「없음」으로 읽어도 되는 것은 `ok` 뿐이다.**
 * 실패를 빈 목록으로 돌려주면 화면이 「공백 없음」「모두 확인」 같은 안심 문구를 띄운다(UI 리뷰 1).
 *
 * - `preparing` — 기능이 안 열렸거나(NOT_READY) 곡선이 아직 없다(409·422). 오류는 아니지만 판정도 못 한다
 */
export type LoadStatus = 'loading' | 'preparing' | 'error' | 'ok';

export function loadStatus(query: { isPending: boolean; isError: boolean }, r: ApiResult<unknown> | undefined): LoadStatus {
  if (query.isPending) return 'loading';
  if (query.isError || !r) return 'error';
  if (!r.ready) return 'preparing';
  if (r.ok) return 'ok';
  return r.status === 409 || r.status === 422 ? 'preparing' : 'error';
}

/** 정산 공백 (10.2). 곡선과 같은 실행을 근거로 하므로 같은 상태를 낸다. */
export function useCashGaps(businessId: number | undefined) {
  const query = useQuery({
    queryKey: ['v7', 'cash-gaps', businessId],
    enabled: businessId !== undefined,
    queryFn: () => getCashGaps(businessId as number),
    staleTime: 60_000,
  });
  const r = query.data;
  const items: CashGapItem[] = r?.ready && r.ok ? (r.data.items ?? []) : [];
  return {
    status: loadStatus(query, r),
    items,
    retry: () => void query.refetch(),
    retrying: query.isFetching,
  };
}

/**
 * 지갑 카드(메인)의 대출 목록 (16.1).
 *
 * 키를 `['v7', 'loans', id, 'wallet']` 로 한 단계 내린다 — 대출 화면이 같은 접두사에 가공한 모양을
 * 담아, 같은 키를 쓰면 캐시가 서로의 모양을 덮는다. 접두사는 같아서 대출 화면의 불러오기가
 * `['v7', 'loans']` 를 버릴 때 이것도 함께 새로 읽는다.
 */
export function useLoans(businessId: number | undefined) {
  const query = useQuery({
    queryKey: ['v7', 'loans', businessId, 'wallet'],
    enabled: businessId !== undefined,
    queryFn: () => listLoans(businessId as number),
    staleTime: 60_000,
  });
  const r = query.data;
  const items: LoanItem[] = r?.ready && r.ok ? r.data.items : [];
  return {
    status: loadStatus(query, r),
    items,
    retry: () => void query.refetch(),
    retrying: query.isFetching,
  };
}

/**
 * 사업장의 계좌.
 *
 * **합계를 내지 않는다.** 총잔액은 서버가 곡선과 같은 실행에서 낸 `currentBalance` 하나만
 * 쓴다 — 여기서 한 번 더 더하면 규칙이 두 벌이 되고, 예보가 준비됐는지에 따라 같은 화면의
 * 숫자가 달라진다. 실제로 그랬다: 예보 전에는 해제된 계좌까지 더한 합계가 보였다.
 */
export function useAccounts(businessId: number | undefined) {
  const query = useQuery({
    queryKey: ['v7', 'accounts', businessId],
    enabled: businessId !== undefined,
    queryFn: () => listAccounts(businessId as number),
    staleTime: 30_000,
  });
  const r = query.data;
  const all: Account[] = r?.ready && r.ok ? r.data.items : [];
  // 해제된 계좌는 세지 않는다. 판정은 코어에 둔다 — 웹과 앱이 갈리면 같은 가게를 두고
  // 서로 다른 잔액을 보여준다. 관리 화면(계좌 목록)은 전체를 따로 조회한다.
  const items = countedAccounts(all);
  // balance 는 없을 수 있다(수집 전). 없는 계좌를 0 으로 세면 합계가 거짓말이 된다.
  const known = items.filter((a) => typeof a.balance === 'number');
  return {
    loading: query.isPending,
    items,
    /** 잔액을 아직 모르는 계좌가 있으면 서버 합계도 그 계좌를 빼고 센 값이다 */
    partial: known.length !== items.length,
  };
}

/** 확인이 필요한 거래. v7 의 `확인할 일` 카드가 이걸 센다. */
export function useNeedsReview(businessId: number | undefined) {
  const query = useQuery({
    queryKey: ['v7', 'tx-review', businessId],
    enabled: businessId !== undefined,
    queryFn: () => listTransactions(businessId as number, { needsConfirmation: true, size: 20 }),
    staleTime: 30_000,
  });
  const r = query.data;
  const items: Transaction[] = r?.ready && r.ok ? r.data.items : [];
  // 목록은 20건만 받는다. 건수는 서버 합계를 쓴다 — 21건째부터 「20건」으로 멈추면 안 된다
  const total = r?.ready && r.ok ? (r.data.totalElements ?? items.length) : 0;
  return {
    status: loadStatus(query, r),
    items,
    total,
    retry: () => void query.refetch(),
    retrying: query.isFetching,
  };
}

/** 가까운 예정 입출금. 곡선의 입력이자 `가까운 입출금` 목록이다. */
export function usePlanned(businessId: number | undefined, days = 30) {
  const from = new Date();
  const to = new Date(from.getTime() + days * 86_400_000);
  const ymd = (d: Date) => d.toISOString().slice(0, 10);
  const query = useQuery({
    queryKey: ['v7', 'planned', businessId, days],
    enabled: businessId !== undefined,
    queryFn: () =>
      listPlannedTransactions(businessId as number, { from: ymd(from), to: ymd(to), size: 50 }),
    staleTime: 30_000,
  });
  const r = query.data;
  const items: PlannedTransaction[] = r?.ready && r.ok ? r.data.items : [];
  return { loading: query.isPending, items };
}

/** 안전선. 설정 전에는 빈 200 이 온다 — 404 가 아니다. */
export function useSafetyLine(businessId: number | undefined) {
  const query = useQuery({
    queryKey: ['v7', 'safety-line', businessId],
    enabled: businessId !== undefined,
    queryFn: () => getSafetyLine(businessId as number),
    staleTime: 120_000,
  });
  const r = query.data;
  const line = r?.ready && r.ok ? r.data : undefined;
  return {
    loading: query.isPending,
    amount: line?.amount ?? null,
    method: line?.method,
    updatedAt: line?.updatedAt,
    // 12.1 current. 예측 실행이 없으면 키 자체가 없어 undefined 다.
    current: line?.current,
  };
}
