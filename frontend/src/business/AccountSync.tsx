import {
  formatDateTime,
  formatMoney,
  getJob,
  isJobTerminal,
  syncAccount,
  type Account,
  type Job,
  type SyncResult,
} from '@jangoyebo/core';
import { useQuery, useQueryClient } from '@tanstack/react-query';
import { useEffect, useState } from 'react';
import type { ReactNode } from 'react';

/**
 * 계좌 동기화 (API 7.3).
 *
 * CSV 임포트와 달리 응답의 Job 은 아직 QUEUED 다 — 워커가 처리하는 동안 폴링한다.
 * 실측 1.5~1.7초 동안 QUEUED → PROCESSING → COMPLETED 를 거친다(2026-09-08).
 * 그동안 아무것도 안 보이면 버튼이 먹히지 않은 것처럼 보인다.
 *
 * 계좌 줄의 격자 두 칸을 그린다 — 잔액 칸과 버튼 칸. 버튼 칸에는 부르는 쪽이 넘긴
 * 동작(연결 해제 등)이 동기화 버튼 뒤에 붙는다. 칸마다 너비가 고정이라 줄이 맞는다.
 */

/** 서버가 영영 PROCESSING 이면 탭이 닫힐 때까지 요청이 나간다. 여기서 끊는다. */
const POLL_MS = 700;
const TIMEOUT_MS = 60_000;

const SYNC_LABEL: Record<string, string> = {
  NEVER: '동기화 전',
  RUNNING: '동기화 중',
  SUCCESS: '동기화됨',
  FAILED: '동기화 실패',
};

export function AccountSync({
  account,
  businessId,
  children,
}: {
  account: Account;
  businessId: number;
  /** 버튼 칸에 동기화 버튼 뒤로 붙는 동작 */
  children?: ReactNode;
}) {
  const queryClient = useQueryClient();
  const [jobId, setJobId] = useState<number | null>(null);
  const [error, setError] = useState<string | null>(null);
  // 제한 시간이 지났는가. 시각을 담고 렌더에서 빼는 대신 <b>타이머가 세우는 상태</b>로 둔다 —
  // 아래 useEffect 주석 참고.
  const [deadlinePassed, setDeadlinePassed] = useState(false);

  // 인증이 끝난 계좌만 동기화할 수 있다(409 ACCOUNT_NOT_VERIFIED).
  // 연결을 끊은 계좌도 대상이 아니다 — 서버가 DISCONNECTED 로 두고 수집에서 뺀다.
  const ready = account.status === 'CONNECTED';

  const job = useQuery({
    queryKey: ['job', jobId],
    enabled: jobId !== null,
    queryFn: async (): Promise<Job<SyncResult> | null> => {
      const res = await getJob<SyncResult>(jobId!);
      return res.ready && res.ok ? res.data : null;
    },
    // 배경 탭에서는 폴링하지 않는다(기본값). 돌아오면 focus 재조회로 결과를 받는다.
    // 끝났으면 false 를 돌려 스스로 멈춘다. 종료 조건과 별개로 전체 시간도 끊는다.
    refetchInterval: (q) => {
      if (isJobTerminal(q.state.data?.status)) return false;
      return deadlinePassed ? false : POLL_MS;
    },
  });

  const status = job.data?.status;
  const running = jobId !== null && !isJobTerminal(status);
  const timedOut = running && deadlinePassed;

  /**
   * 제한 시간을 <b>타이머로</b> 잰다.
   *
   * 예전에는 렌더에서 {@code Date.now()} 를 읽어 마감을 판정했다. 렌더가 멱등하지 않은 것도
   * 문제지만(react-hooks/purity) 실제로 새는 구멍이 있었다 — 마감이 지나도 <b>리렌더를
   * 예약하는 것이 없다.</b> 폴링이 리렌더를 일으켜 주는 동안에만 판정이 갱신되는데, 폴링을
   * 멈추는 조건이 같은 마감이라 마지막 응답이 이전과 같아 리렌더가 생략되면 폴링은 멈췄는데
   * 화면은 "동기화를 기다리는 중…" 에 그대로 남는다.
   *
   * {@code jobId} 에만 매어 둔다. {@code status} 를 넣으면 QUEUED → PROCESSING 마다 타이머가
   * 다시 시작해 마감이 뒤로 밀린다 — 마감은 요청을 보낸 시점부터 한 번만 세는 것이 맞다.
   */
  useEffect(() => {
    if (jobId === null) return;
    const timer = setTimeout(() => setDeadlinePassed(true), TIMEOUT_MS);
    return () => clearTimeout(timer);
  }, [jobId]);

  // 끝나면 계좌(잔액·동기화 시각)와 거래 목록을 다시 읽는다.
  useEffect(() => {
    if (!isJobTerminal(status)) return;
    queryClient.invalidateQueries({ queryKey: ['accounts', businessId] });
    queryClient.invalidateQueries({ queryKey: ['transactions'] });
  }, [status, businessId, queryClient]);

  async function onSync() {
    setError(null);
    setDeadlinePassed(false);
    setJobId(null);

    const res = await syncAccount(account.accountId);
    if (!res.ready) {
      setError('동기화 기능이 아직 준비되지 않았습니다.');
      return;
    }
    if (!res.ok) {
      setError(
        res.error.code === 'ACCOUNT_NOT_VERIFIED'
          ? '계좌 인증을 먼저 마쳐 주세요.'
          : res.error.message,
      );
      return;
    }

    setJobId(res.data.jobId);
  }

  // 값이 없으면 키 자체가 없다(구현현황). 포맷터가 undefined 를 —로 받아낸다.
  const syncedAt = account.lastSyncedAt ? formatDateTime(account.lastSyncedAt) : null;
  const label = SYNC_LABEL[account.syncStatus] ?? account.syncStatus;

  // 진행·오류는 잔액이 있던 자리를 대신 차지한다 — 칸이 그대로라 목록이 흔들리지 않는다.
  const message =
    error ??
    (timedOut
      ? '아직 끝나지 않았습니다. 잠시 후 새로 고쳐 확인해 주세요.'
      : status === 'FAILED'
        ? `동기화하지 못했습니다${job.data?.error ? ` — ${job.data.error.message}` : ''}`
        : null);

  return (
    <>
      {message ? (
        <span className="account-sync__error" role="alert">
          {message}
        </span>
      ) : running ? (
        // 움직임만으로 말하지 않는다 — 글자가 상태를 말하고 점은 거들기만 한다.
        // prefers-reduced-motion 이면 base.css 가 애니메이션을 걷어내고 글자만 남는다.
        <span className="account-sync__busy" role="status">
          <i aria-hidden="true" />
          {status === 'PROCESSING' ? '거래를 가져오는 중…' : '동기화를 기다리는 중…'}
        </span>
      ) : (
        <span className="account-sync__state" data-sync={account.syncStatus}>
          <b>{formatMoney(account.balance)}</b>
          <small>
            잔액 · {label}
            {syncedAt && ` · ${syncedAt}`}
          </small>
        </span>
      )}

      <span className="account-sync__actions">
        {/* 인증 전·연결 끊김에는 아예 두지 않는다. 누를 수 없는 버튼은 자리만 차지한다 */}
        {ready && (
          <button type="button" className="account-sync__btn" onClick={onSync} disabled={running}>
            {running ? '동기화 중…' : '동기화'}
          </button>
        )}
        {children}
      </span>
    </>
  );
}
