import { useEffect, useRef, useState } from 'react';
import type { ReactNode } from 'react';
import { useQuery } from '@tanstack/react-query';
import { listTransactions } from '@jangoyebo/core';
import type { Transaction } from '@jangoyebo/core';
import { BalanceChart } from './BalanceChart';
import { DayCards } from './DayCards';
import { useAccounts } from './model';
import type { Point } from './model';
import { historicalBalancePoints, historyRange } from './transactionHistory';

export function CombinedBalanceChart({
  businessId,
  currentBalance,
  forecast,
  safetyLine,
  selectedFuture,
  onSelectFuture,
  selectedPast,
  onSelectPast,
  actions,
}: {
  businessId: number | undefined;
  currentBalance: number;
  forecast: Point[];
  safetyLine: number | null;
  selectedFuture: number;
  onSelectFuture: (index: number) => void;
  /** 고른 과거 날. 부모가 들고 있어야 위쪽 날씨도 그날을 따라간다 */
  selectedPast: Point | null;
  onSelectPast: (point: Point | null) => void;
  /**
   * 조작 줄 오른쪽 끝에 붙는 화면 단위 버튼(예보 새로고침 · 안전선).
   *
   * 머리말에 두었더니 성함·잔액·날씨와 한 줄에 서지 못해 줄이 하나 더 났다. 여기는 이미
   * 조회 기간을 고르는 줄이라 **높이를 더 쓰지 않고** 들어가고, 둘 다 곡선을 다시 그리게
   * 하는 버튼이라 뜻으로도 이 자리가 맞다.
   */
  actions?: ReactNode;
}) {
  const [pastDays, setPastDays] = useState(30);
  const [futureDays, setFutureDays] = useState(30);
  const [centerRequest, setCenterRequest] = useState(0);
  /**
   * 클릭할 때마다 오르는 값. **같은 날을 다시 눌러도** 차트가 알아채야 해서 둔다 —
   * selected 만 보면 같은 날 재클릭은 값이 그대로라 아무 일도 안 일어난다.
   */
  const [pickSeq, setPickSeq] = useState(0);
  const { from, to } = historyRange(pastDays);
  const query = useQuery({
    queryKey: ['v7', 'balance-history', businessId, from, to],
    enabled: businessId !== undefined,
    staleTime: 30_000,
    queryFn: async () => {
      const items: Transaction[] = [];
      for (let page = 0; ; page++) {
        const result = await listTransactions(businessId!, { from, to, page, size: 100 });
        if (!result.ready) throw new Error('거래 조회 기능이 아직 준비되지 않았습니다.');
        if (!result.ok) throw new Error(result.error.message);
        items.push(...result.data.items);
        if (page + 1 >= result.data.totalPages || result.data.items.length < 100) return items;
      }
    },
  });

  // 어느 계좌의 거래를 세는지가 곡선의 일부다 — 서버가 시작 잔액을 CONNECTED 만으로 고르므로
  // 목록 기준(해제만 제외)을 그대로 쓰면 잔액은 빠진 계좌의 거래만 들어온다. 판정은 코어에 있다.
  const accounts = useAccounts(businessId);
  const history = historicalBalancePoints(
    query.data ?? [],
    accounts.items,
    currentBalance,
    from,
    to,
  );
  const future = forecast.slice(0, futureDays);
  const points = [...history, ...future].map((point, index) => ({ ...point, index }));
  const forecastStart = history.length;
  const todayIndex = history.length - 1;
  const pastIndex = selectedPast ? history.findIndex((p) => p.date === selectedPast.date) : -1;
  const selected =
    pastIndex >= 0
      ? pastIndex
      : forecastStart + Math.min(selectedFuture, Math.max(0, future.length - 1));

  /** 오늘을 고르고 그 카드를 가운데로. 버튼은 걷었고 지금은 처음 열 때만 부른다 */
  function goToday() {
    onSelectPast(history[todayIndex] ?? null);
    setCenterRequest((n) => n + 1);
  }

  // 처음 열면 오늘을 고르고 오늘 카드를 가운데에 둔다. 과거 곡선이 들어온 뒤 한 번만 한다.
  const openedAtToday = useRef(false);
  const historyReady = query.isSuccess && !accounts.loading;
  useEffect(() => {
    if (!historyReady || openedAtToday.current) return;
    openedAtToday.current = true;
    goToday();
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [historyReady]);

  function select(index: number) {
    if (index < forecastStart) onSelectPast(history[index]);
    else onSelectFuture(index - forecastStart);
    setPickSeq((n) => n + 1);
    // 고른 날의 카드를 가운데로 데려온다. 날짜를 골랐는데 그 카드가 가장자리에 걸쳐
    // 있으면 무엇을 고른 건지 눈으로 확인할 수 없다.
    setCenterRequest((n) => n + 1);
  }

  return (
    <div className="combined-chart">
      <div className="combined-chart__controls">
        <PeriodControl
          label="과거"
          values={[7, 30, 60]}
          value={pastDays}
          onChange={(days) => {
            onSelectPast(null);
            setPastDays(days);
          }}
        />
        <PeriodControl
          label="예측"
          values={[7, 14, 30]}
          value={futureDays}
          onChange={setFutureDays}
        />
        {actions && <div className="combined-chart__acts">{actions}</div>}
      </div>
      {/* 계좌 목록도 기다린다. 없으면 셀 계좌가 없어 과거가 0원으로 한 번 그려진다 —
          돈을 세는 화면에서 그 깜빡임은 없는 공백처럼 읽힌다. */}
      {query.isPending || accounts.loading ? (
        <p className="combined-chart__state" role="status">
          과거 잔액을 불러오는 중…
        </p>
      ) : query.isError ? (
        <p className="combined-chart__state" role="alert">
          과거 잔액을 불러오지 못했습니다.{' '}
          <button type="button" onClick={() => void query.refetch()}>
            다시 시도
          </button>
        </p>
      ) : (
        <>
          <BalanceChart
            roomy
            points={points}
            safetyLine={safetyLine}
            selected={Math.min(selected, points.length - 1)}
            onSelect={select}
            pickSeq={pickSeq}
            forecastStart={forecastStart}
            todayIndex={todayIndex}
          />
          <DayCards
            points={points}
            safetyLine={safetyLine}
            selected={Math.min(selected, points.length - 1)}
            onSelect={select}
            forecastStart={forecastStart}
            todayIndex={todayIndex}
            centerRequest={centerRequest}
          />
        </>
      )}
    </div>
  );
}

function PeriodControl({
  label,
  values,
  value,
  onChange,
}: {
  label: string;
  values: number[];
  value: number;
  onChange: (value: number) => void;
}) {
  return (
    <div className="combined-chart__period" role="group" aria-label={`${label} 조회 기간`}>
      <span>{label}</span>
      {values.map((days) => (
        <button
          key={days}
          type="button"
          aria-pressed={value === days}
          onClick={() => onChange(days)}
        >
          {days}일
        </button>
      ))}
    </div>
  );
}
