import { useEffect, useState } from 'react';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { createExport, downloadExport, formatDateTime, formatMoney, getExport, listExports } from '@jangoyebo/core';
import type { ExportItem } from '@jangoyebo/core';
import { Section } from './PageShell';
import { Icon } from './icons';
import { canDownload, expiryLabel, fileSizeLabel } from './exportView';
import { saveFile } from './saveFile';

/**
 * 신고 준비자료 패키지 받기 (API 14.2 · SC-07-6 · S15P21C105-265).
 *
 * 회계사에게 맡기는 사람은 **합계가 아니라 원자료**를 넘겨야 한다 — 회계사는 거래와 증빙으로
 * 장부를 다시 쓴다. 서버가 시트 일곱 장과(켜면) 증빙 원본까지 ZIP 으로 묶어 준다.
 *
 * 만드는 데 시간이 걸려 `202` + Job 이다. 폴링은 끝 상태에서 멈추되, **그것과 별개로 전체
 * 시간 제한을 둔다** — 서버가 영영 `PROCESSING` 이면 탭이 닫힐 때까지 요청이 나간다.
 */

const POLL_MS = 3_000;
/** 증빙 원본을 켜면 수백 MB 다. 넉넉히 두되 무한정 두지는 않는다 */
const GIVE_UP_MS = 10 * 60_000;

export function ExportPackagePanel({ businessId, year }: { businessId: number | undefined; year: number }) {
  const queryClient = useQueryClient();
  const [periodStart, setPeriodStart] = useState(`${year}-01-01`);
  const [periodEnd, setPeriodEnd] = useState(`${year}-12-31`);
  const [withEvidence, setWithEvidence] = useState(true);
  /** 지금 지켜보는 내보내기. 이력에서 고른 것이 아니라 방금 만든 것만 여기 온다 */
  const [watching, setWatching] = useState<number | null>(null);
  const [tooLong, setTooLong] = useState(false);
  const [failure, setFailure] = useState<string | null>(null);
  // 만료는 화면이 열려 있는 동안 몇 시간씩 흐르지 않는다. 열 때 한 번 잡아 쓴다
  const [now] = useState(() => new Date());

  /**
   * 위에서 귀속연도를 바꾸면 기간도 따라간다. 바뀐 것을 <b>그리는 중에</b> 맞춘다 —
   * effect 로 하면 옛 기간이 한 번 그려졌다가 바뀌고, 그 사이에 누르면 지난해 자료가 만들어진다.
   *
   * 부모가 `key` 를 주는 길도 있지만 그러면 <b>만들던 작업까지 잊는다</b>. 연도를 바꿨다고
   * 진행 중인 것이 사라지면 사용자는 실패한 줄 안다.
   */
  const [shownYear, setShownYear] = useState(year);
  if (year !== shownYear) {
    setShownYear(year);
    setPeriodStart(`${year}-01-01`);
    setPeriodEnd(`${year}-12-31`);
  }

  const history = useQuery({
    queryKey: ['v7', 'exports', businessId],
    enabled: businessId !== undefined,
    queryFn: () => listExports(businessId!),
  });
  const listResult = history.data;
  const items: ExportItem[] = listResult?.ready && listResult.ok ? listResult.data.items : [];

  const watch = useQuery({
    queryKey: ['v7', 'export', watching],
    enabled: watching !== null,
    queryFn: () => getExport(watching!),
    refetchInterval: (query) => {
      const result = query.state.data;
      const item = result?.ready && result.ok ? result.data : null;
      if (tooLong || (item && item.status !== 'PROCESSING')) return false;
      return POLL_MS;
    },
  });
  const watchResult = watch.data;
  const watched = watchResult?.ready && watchResult.ok ? watchResult.data : null;
  const watchedStatus = watched?.status;
  const running = watching !== null && watchedStatus !== 'COMPLETED' && watchedStatus !== 'FAILED';

  useEffect(() => {
    if (watching === null) return;
    const timer = setTimeout(() => setTooLong(true), GIVE_UP_MS);
    return () => clearTimeout(timer);
  }, [watching]);

  // 끝나면 이력을 다시 읽는다 — 방금 만든 것이 목록에도 서야 만료·크기를 거기서 볼 수 있다
  useEffect(() => {
    if (!watchedStatus || watchedStatus === 'PROCESSING') return;
    queryClient.invalidateQueries({ queryKey: ['v7', 'exports'] });
  }, [watchedStatus, queryClient]);

  const periodInvalid = !periodStart || !periodEnd || periodEnd < periodStart;

  const create = useMutation({
    mutationFn: () =>
      createExport(businessId!, {
        exportType: 'TAX_PREP',
        periodStart,
        periodEnd,
        includeEvidenceFiles: withEvidence,
      }),
    onSuccess: (res) => {
      if (!res.ready) {
        setFailure('준비자료 받기 기능이 아직 준비되지 않았습니다.');
        return;
      }
      if (!res.ok) {
        setFailure(res.error.message);
        return;
      }
      setFailure(null);
      setTooLong(false);
      setWatching(res.data.exportId);
      queryClient.invalidateQueries({ queryKey: ['v7', 'exports'] });
    },
  });

  const download = useMutation({
    mutationFn: (item: ExportItem) => downloadExport(item.exportId),
    onSuccess: (res, item) => {
      if (!res.ready) {
        setFailure('준비자료 받기 기능이 아직 준비되지 않았습니다.');
        return;
      }
      if (!res.ok) {
        // 「아직 안 됐다」와 「사라졌다」는 사장님이 할 일이 정반대다 — 같은 말로 적지 않는다
        setFailure(
          res.error.code === 'EXPORT_EXPIRED'
            ? '보관 기간(7일)이 지나 파일이 사라졌습니다. 기다려도 오지 않으니 아래에서 다시 만들어 주세요.'
            : res.error.code === 'EXPORT_NOT_READY'
              ? '아직 만드는 중입니다. 잠시 뒤에 다시 받아 주세요.'
              : res.error.message,
        );
        queryClient.invalidateQueries({ queryKey: ['v7', 'exports'] });
        return;
      }
      setFailure(null);
      saveFile(res.data, `신고준비자료_${item.periodStart}_${item.periodEnd}.zip`);
    },
  });

  return (
    <Section title="거래내역·증빙 묶어 받기">
      <p className="v7-note v7-note--small">
        회계사는 합계가 아니라 거래와 증빙으로 장부를 다시 씁니다. 연간 매출요약·거래내역·필요경비 후보명세서·정산 대사표·고정비·증빙
        누락 목록을 한 번에 묶어 드립니다. <b>확정된 세무 자료가 아니라 판단의 근거 자료입니다.</b>
      </p>

      <div className="tax-create">
        <label>
          시작일
          <input type="date" value={periodStart} onChange={(e) => setPeriodStart(e.target.value)} />
        </label>
        <label>
          종료일
          <input type="date" value={periodEnd} onChange={(e) => setPeriodEnd(e.target.value)} />
        </label>
        <label className="export-toggle">
          <input type="checkbox" checked={withEvidence} onChange={(e) => setWithEvidence(e.target.checked)} />
          증빙 원본도 함께
        </label>
        <button
          type="button"
          className="auth-submit"
          disabled={create.isPending || running || periodInvalid || businessId === undefined}
          onClick={() => create.mutate()}
        >
          {create.isPending ? '접수하는 중…' : '자료 묶기'}
        </button>
        {periodInvalid && <p className="v7-inline-error">종료일이 시작일보다 빠릅니다.</p>}
        <p className="v7-note v7-note--small">
          증빙 원본을 넣으면 파일이 수백 MB가 될 수 있습니다. <b>빼고 만들면 받는 쪽은 증빙이 아예 없었다고 읽습니다</b> — 뺐다면
          회계사에게 그 사실을 알려 주세요. 만든 파일은 <b>7일간</b> 보관합니다.
        </p>
      </div>

      {running && (
        <div className="v7-note" role="status">
          <Icon name="clock" size={18} />
          <div>
            <strong>{tooLong ? '아직 끝나지 않았습니다' : '자료를 묶는 중입니다'}</strong>
            <span>
              {tooLong
                ? '시간이 오래 걸려 진행 확인을 멈췄습니다. 이 화면을 다시 열면 아래 목록에서 결과를 볼 수 있습니다.'
                : '한 해 거래를 전부 훑고 증빙을 담습니다. 이 화면을 열어 두면 끝났을 때 아래 목록에 나타납니다.'}
            </span>
          </div>
        </div>
      )}
      {watchedStatus === 'FAILED' && (
        <p className="v7-inline-error" role="alert">
          ⚠ 자료를 만들지 못했습니다{watched?.errorMessage ? ` — ${watched.errorMessage}` : ''}.
        </p>
      )}
      {failure && (
        <p className="v7-inline-error" role="alert">
          ⚠ {failure}
        </p>
      )}

      {history.isPending ? (
        <p className="v7-note">불러오는 중…</p>
      ) : !listResult?.ready ? (
        <div className="empty-state">준비자료 받기 기능이 아직 준비되지 않았습니다.</div>
      ) : !listResult.ok ? (
        <p className="v7-inline-error" role="alert">
          ⚠ 만든 자료를 불러오지 못했습니다 — {listResult.error.message}
        </p>
      ) : items.length === 0 ? (
        <p className="v7-note v7-note--small">아직 만든 자료가 없습니다.</p>
      ) : (
        <div className="tx-table-wrap">
          <table className="tx-table">
            <thead>
              <tr>
                <th scope="col">기간</th>
                <th scope="col">상태</th>
                <th scope="col">요청한 때</th>
                <th scope="col">크기</th>
                <th scope="col" className="tax-actions">
                  받기
                </th>
              </tr>
            </thead>
            <tbody>
              {items.map((item) => {
                const expiry = expiryLabel(item.downloadExpiresAt, now);
                const ready = canDownload(item, now);
                return (
                  <tr key={item.exportId}>
                    <td data-label="기간">
                      {item.periodStart} ~ {item.periodEnd}
                      {item.summary && <small>{summaryLine(item)}</small>}
                    </td>
                    <td data-label="상태">
                      {STATUS_LABEL[item.status]}
                      {expiry && <small>{expiry}</small>}
                    </td>
                    <td data-label="요청한 때">{formatDateTime(item.requestedAt)}</td>
                    <td data-label="크기">{fileSizeLabel(item.fileSize)}</td>
                    <td className="tax-actions">
                      <button
                        type="button"
                        className="v7-secondary-button"
                        disabled={!ready || download.isPending}
                        onClick={() => download.mutate(item)}
                      >
                        {expiry === '만료됨' ? '만료됨' : '받기'}
                      </button>
                    </td>
                  </tr>
                );
              })}
            </tbody>
          </table>
        </div>
      )}
    </Section>
  );
}

const STATUS_LABEL: Record<ExportItem['status'], string> = {
  // 대기 중과 만드는 중을 갈라 볼 이유가 없어 서버도 하나로 준다
  PROCESSING: '만드는 중',
  COMPLETED: '완료',
  FAILED: '실패',
};

/**
 * 무엇이 들어 있는지 한 줄. **회계사가 빠진 자료를 물어볼 수 있게** 범위를 먼저 말한다.
 *
 * 출금이 한 건도 없으면 서버가 `evidenceLinkRate` 키를 뺀다 — 0% 로 적으면 「증빙을 하나도
 * 안 붙였다」가 되는데 붙일 것이 없었던 것과 다른 말이다.
 */
function summaryLine(item: ExportItem): string {
  const s = item.summary ?? {};
  const parts: string[] = [];
  if (s.transactionCount !== undefined) parts.push(`거래 ${s.transactionCount.toLocaleString()}건`);
  if (s.evidenceLinkRate !== undefined) parts.push(`증빙 연결 ${Math.round(s.evidenceLinkRate * 100)}%`);
  if (s.unverifiedCount) parts.push(`미확인 ${s.unverifiedCount.toLocaleString()}건 ${formatMoney(s.unverifiedAmount ?? 0)}`);
  return parts.join(' · ');
}
