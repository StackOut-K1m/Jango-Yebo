import { useMemo, useState } from 'react';
import { Link } from 'react-router';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { bulkUpdateTaxReview, listTransactionCategories, listTransactions } from '@jangoyebo/core';
import type { TaxReviewBulkResult, Transaction } from '@jangoyebo/core';
import { useBusiness } from '@/business/useBusiness';
import { pageNumbers } from '@/components/pageNumbers';
import { PageShell, Section } from './PageShell';
import { Icon } from './icons';
import { money } from './model';
import { koreanDate } from './transactionHistory';
import { todayIso } from './supportView';
import { ASSIGNABLE_REVIEW, REVIEW_FILTERS, TAX_REVIEW, collectAllIds, excludedByReview, yearPeriod } from './taxView';
import type { AssignableReview } from './taxView';
import './tax.css';

/**
 * 신고자료 검토 (SC-07-7, API 8.7, P0-12 최소).
 *
 * 출금이 찍혔다는 것만으로 필요경비가 되지 않는다 — 거래마다 검토 상태를 두고, 여기서 여러 건에
 * 한 번에 매긴다. 증빙 확인·누락 두 값은 증빙을 연결하면 서버가 정하므로 고르는 목록에 없다.
 *
 * 검토 상태는 분류와 따로 매기는 값이라 목록은 분류 여부를 가리지 않는다. 다만 신고서 초안에는
 * **분류를 확정한 거래만** 들어가므로, 분류 전인 거래는 그 사실을 표시한다 — 여기서 판단을 매겨도
 * 분류를 마치기 전에는 초안 금액에 나타나지 않는다.
 */

const PAGE_SIZE = 50;
/**
 * 한 번의 요청에 담는 수. 서버 상한과 같다(구현현황 3.30) — <b>요청당</b> 상한이지
 * 한 번에 바꿀 수 있는 총량이 아니다. 더 고르면 이 크기로 나눠 여러 번 보낸다.
 */
const CHUNK = 200;
/**
 * 「모두 고르기」가 쪽을 이어 받을 때 쓰는 크기. 서버가 한 쪽에 100건까지만 준다
 * (`spring.data.web.pageable.max-page-size`) — 더 크게 보내도 조용히 100으로 깎인다.
 */
const FETCH_SIZE = 100;
/**
 * 날짜만으로 정렬하면 <b>같은 날짜끼리 순서가 정해지지 않는다.</b> 쪽을 넘길 때마다 그 묶음이
 * 다르게 늘어서서, 486건을 쪽 단위로 모았더니 485건만 잡혔다 — 한 건이 두 쪽 사이로 새고
 * 다른 한 건이 겹쳐 들어온 것이다. 같은 값이면 id 로 가른다(Spring 은 `a,b,desc` 를
 * 「a·b 를 desc 로」 로 읽는다). 엔티티의 식별자 필드 이름은 `transactionId` 가 아니라 `id` 다.
 */
const STABLE_SORT = 'transactionDate,id,desc';

export function TaxReviewScreen() {
  const { current } = useBusiness();
  const businessId = current?.businessId;
  const queryClient = useQueryClient();
  const [today] = useState(() => todayIso());
  const thisYear = Number(today.slice(0, 4));
  const [year, setYear] = useState(thisYear);
  const [filter, setFilter] = useState(REVIEW_FILTERS[0].id);
  /**
   * 거래명 검색. 이 화면은 486건을 다 훑는 곳이 아니라 <b>몇 건을 찾아 표시하는</b> 곳인데
   * (머리말이 「모든 거래를 볼 필요는 없습니다」라고 말한다) 찾는 길이 검토 상태 5종뿐이었다.
   * 거래 화면과 같은 `q` 를 쓴다 — 서버가 이미 받는다.
   */
  const [q, setQ] = useState('');
  const [page, setPage] = useState(0);
  const [picked, setPicked] = useState<Set<number>>(new Set());
  const [status, setStatus] = useState<AssignableReview>('EXPERT_REVIEW');
  const [note, setNote] = useState('');
  const [result, setResult] = useState<TaxReviewBulkResult | null>(null);
  /** 나눠 보내는 중의 진행. 200건을 넘게 고르면 왕복이 여러 번이라 말없이 기다리게 하지 않는다 */
  const [sending, setSending] = useState<{ done: number; total: number } | null>(null);

  const period = yearPeriod(year, today);
  const statuses = REVIEW_FILTERS.find((f) => f.id === filter)?.statuses ?? [];
  /** 서버는 쉼표로 여러 값을 받는다. 「전체」는 값을 보내지 않는다 */
  const statusParam = statuses.length ? statuses.join(',') : undefined;

  const list = useQuery({
    queryKey: ['v7', 'tax-review', businessId, year, filter, q.trim(), page],
    enabled: businessId !== undefined,
    placeholderData: (prev) => prev,
    queryFn: () =>
      listTransactions(businessId!, {
        ...period,
        taxReviewStatus: statusParam,
        q: q.trim() || undefined,
        page,
        size: PAGE_SIZE,
        sort: STABLE_SORT,
      }),
  });
  const categories = useQuery({
    queryKey: ['v7', 'transaction-categories'],
    staleTime: Infinity,
    queryFn: listTransactionCategories,
  });
  const categoryName = useMemo(() => {
    const r = categories.data;
    const items = r?.ready && r.ok ? r.data.items : [];
    return new Map(items.map((c) => [c.code, c.name]));
  }, [categories.data]);

  const r = list.data;
  const data = r?.ready && r.ok ? r.data : null;
  const rows: Transaction[] = data?.items ?? [];
  /** 지금 조건에 맞는 거래 수. 고르기에는 상한이 없다 — 적용할 때 200건씩 나눠 보낸다 */
  const selectable = data?.totalElements ?? 0;
  const allPicked = selectable > 0 && picked.size >= selectable;

  /**
   * 머리 체크상자가 부르는 일. 조건에 맞는 거래 <b>전체</b>의 id 를 쪽을 이어 가며 모은다 —
   * 왜 쪽 단위로는 안 되는지는 `collectAllIds` 에 적었다.
   *
   * 무엇을 고를지를 <b>인자로 받는다.</b> 두 번 왕복하는 사이에 사용자가 귀속연도나 거르개를
   * 바꿀 수 있어서, 돌아왔을 때 그 조건이 아직 화면의 조건인지 대조해야 한다.
   */
  const pickAll = useMutation({
    mutationFn: (asked: { from: string; to: string; statusParam: string | undefined; q: string }) =>
      collectAllIds(
        async (index) => {
          const res = await listTransactions(businessId!, {
            from: asked.from,
            to: asked.to,
            taxReviewStatus: asked.statusParam,
            q: asked.q || undefined,
            page: index,
            size: FETCH_SIZE,
            sort: STABLE_SORT,
          });
          if (!res.ready) throw new Error('거래 목록 기능이 아직 준비되지 않았습니다.');
          if (!res.ok) throw new Error(res.error.message);
          return { ids: res.data.items.map((t) => t.transactionId), totalPages: res.data.totalPages };
        },
        { size: FETCH_SIZE },
      ),
    onSuccess: (ids, asked) => {
      // 고르는 사이에 조건이 바뀌었으면 이건 다른 목록의 결과다. 버린다 — 화면에 보이지도 않는
      // 거래가 골라진 채로 「적용」을 누르면 엉뚱한 거래의 검토 상태가 바뀐다.
      if (
        asked.from !== period.from ||
        asked.to !== period.to ||
        asked.statusParam !== statusParam ||
        asked.q !== q.trim()
      )
        return;
      setPicked(new Set(ids));
      setResult(null);
    },
  });

  function reset(next: () => void) {
    next();
    setPage(0);
    setPicked(new Set());
    setResult(null);
    // 조건이 바뀌면 앞서 「모두 고르기」가 낸 오류는 더 이상 이 목록의 이야기가 아니다
    pickAll.reset();
  }
  function toggle(id: number) {
    setPicked((prev) => {
      const next = new Set(prev);
      if (next.has(id)) next.delete(id);
      else next.add(id);
      return next;
    });
  }
  function toggleAll() {
    if (allPicked) setPicked(new Set());
    else pickAll.mutate({ ...period, statusParam, q: q.trim() });
  }

  /**
   * 고른 거래에 검토 상태를 적는다. 200건을 넘으면 <b>나눠서 여러 번</b> 보낸다 —
   * 서버 상한은 요청당이고, 사람이 고른 것은 「조건에 맞는 전부」일 수 있다.
   *
   * 한 덩어리가 통째로 실패하면 거기서 멈춘다. 이어서 보내 봐야 같은 이유로 또 실패할
   * 가능성이 높고, 어디까지 갔는지 모르는 채로 끝나는 것이 가장 나쁘다 — 멈춘 지점까지의
   * 결과는 살려서 돌려준다.
   */
  const apply = useMutation({
    mutationFn: async () => {
      const ids = [...picked];
      const merged: TaxReviewBulkResult = { updatedCount: 0, failedCount: 0, failed: [] };
      for (let at = 0; at < ids.length; at += CHUNK) {
        setSending({ done: at, total: ids.length });
        const res = await bulkUpdateTaxReview(businessId!, {
          transactionIds: ids.slice(at, at + CHUNK),
          taxReviewStatus: status,
          taxReviewNote: note.trim() || undefined,
        });
        if (!res.ready) throw new Error('검토 상태 기능이 아직 준비되지 않았습니다.');
        if (!res.ok) throw new Error(res.error.message);
        merged.updatedCount += res.data.updatedCount;
        merged.failedCount += res.data.failedCount;
        merged.failed.push(...res.data.failed);
      }
      return merged;
    },
    onSettled: () => setSending(null),
    onSuccess: (data) => {
      setResult(data);
      setPicked(new Set(data.failed.map((f) => f.transactionId)));
      // 검토 상태가 바뀌면 이미 만든 초안은 서버가 STALE 로 적는다 — 목록·초안을 함께 다시 읽는다
      queryClient.invalidateQueries({ queryKey: ['v7', 'tax-review'] });
      queryClient.invalidateQueries({ queryKey: ['v7', 'tax-drafts'] });
      queryClient.invalidateQueries({ queryKey: ['v7', 'tax-draft'] });
      queryClient.invalidateQueries({ queryKey: ['transactions'] });
    },
  });
  const applyError = apply.error?.message ?? null;

  return (
    <PageShell
      title="신고자료 검토"
      actions={
        <Link to="/tax/drafts" className="v7-secondary-button">
          신고서 초안으로 <Icon name="arrow" size={15} />
        </Link>
      }
    >
      <Section title="검토할 거래">
        {/* 하위 화면은 머리말을 숨긴다 — 이 화면을 쓰는 법이라 칸 안에 둔다 */}
        <div className="v7-note tax-intro">
          <Icon name="info" size={18} />
          <div>
            <strong>초안 금액에 넣으면 안 될 것 같은 거래만 골라 표시하세요</strong>
            <span>
              표시한 거래는 신고서 초안 금액에서 빠지고 따로 모입니다. 표시하지 않은 거래는 그대로 초안에 들어가니 모든 거래를 볼 필요는
              없습니다. 최종 판단은 사장님 또는 세무전문가가 합니다.
            </span>
          </div>
        </div>
        <div className="tax-toolbar">
          <label>
            귀속연도
            <select value={year} onChange={(e) => reset(() => setYear(Number(e.target.value)))}>
              {[thisYear, thisYear - 1].map((y) => (
                <option key={y} value={y}>
                  {y}년
                </option>
              ))}
            </select>
          </label>
          <label className="tax-toolbar__search">
            거래명 검색
            <input
              type="search"
              value={q}
              placeholder="배달의민족"
              onChange={(e) => {
                setQ(e.target.value);
                // 검색어가 바뀌면 3쪽에 머물러 있을 이유가 없다. 고른 것도 화면과 어긋나므로 비운다
                setPage(0);
                setPicked(new Set());
                setResult(null);
              }}
            />
          </label>
          <div className="v7-chips" role="group" aria-label="검토 상태">
            {REVIEW_FILTERS.map((f) => (
              <button
                key={f.id}
                type="button"
                aria-pressed={filter === f.id}
                onClick={() => reset(() => setFilter(f.id))}
              >
                {f.label}
              </button>
            ))}
          </div>
        </div>
        <p className="v7-note v7-note--small">
          {period.from} ~ {period.to} · 신고서 초안에는 분류를 확정한 거래만 들어갑니다. 「분류 전」 거래는{' '}
          <Link to="/transactions">거래 화면</Link>에서 분류를 마쳐 주세요.
        </p>

        {/* 일괄 지정 줄. 고른 것이 있을 때만 무게를 둔다 */}
        <div className="tax-bulk" data-active={picked.size > 0 || undefined}>
          <strong>{pickAll.isPending ? '모두 고르는 중…' : `${picked.size}건 선택`}</strong>
          <label>
            검토 상태
            <select aria-describedby="tax-review-effect" value={status} onChange={(e) => setStatus(e.target.value as AssignableReview)}>
              {ASSIGNABLE_REVIEW.map((s) => (
                <option key={s} value={s}>
                  {s === 'UNREVIEWED' ? '초안 제외 취소' : TAX_REVIEW[s].label}
                </option>
              ))}
            </select>
          </label>
          <label className="tax-bulk__note">
            메모(선택)
            <input type="text" maxLength={200} placeholder="예: 세무사 확인 요청" value={note} onChange={(e) => setNote(e.target.value)} />
          </label>
          <button
            type="button"
            className="v7-secondary-button"
            disabled={picked.size === 0 || apply.isPending || businessId === undefined}
            onClick={() => apply.mutate()}
          >
            {apply.isPending
              ? sending && sending.total > CHUNK
                ? `바꾸는 중… ${sending.done.toLocaleString()}/${sending.total.toLocaleString()}`
                : '바꾸는 중…'
              : '선택한 거래에 적용'}
          </button>
        </div>
        <p id="tax-review-effect" className="v7-note v7-note--small">
          {status === 'UNREVIEWED'
            ? '초안에서 제외했던 거래를 다시 포함합니다. 거래 분류가 확정된 경우에만 초안 금액에 반영됩니다.'
            : '이 상태를 적용한 거래는 신고서 초안 금액에서 제외되며, 검토할 거래로 따로 표시됩니다.'}
        </p>
        {pickAll.isError && (
          <p className="v7-inline-error" role="alert">
            ⚠ 모두 고르지 못했습니다 — {pickAll.error.message}
          </p>
        )}
        {applyError && (
          <p className="v7-inline-error" role="alert">
            ⚠ {applyError}
          </p>
        )}
        {result && (
          <div className={`v7-note ${result.failedCount ? 'v7-note--bad' : 'v7-note--good'}`} role="status">
            <Icon name={result.failedCount ? 'warning' : 'check'} size={18} />
            <div>
              <strong>{result.updatedCount}건을 바꿨습니다</strong>
              {result.failedCount > 0 && (
                <span>{result.failedCount}건은 찾을 수 없어 바꾸지 못했습니다. 선택에 남겨 두었습니다.</span>
              )}
            </div>
          </div>
        )}
        {list.isPending ? (
          <p className="v7-note">불러오는 중…</p>
        ) : !r?.ready ? (
          <div className="empty-state">거래 목록 기능이 아직 준비되지 않았습니다.</div>
        ) : !r.ok ? (
          <p className="v7-inline-error" role="alert">
            ⚠ 거래를 불러오지 못했습니다 — {r.error.message}
          </p>
        ) : rows.length === 0 ? (
          <div className="empty-state">이 조건에 맞는 거래가 없습니다.</div>
        ) : (
          <>
            <p className="tax-count">{data!.totalElements}건</p>
            <div className="tx-table-wrap v7-scroll" tabIndex={0} aria-label="검토할 거래 표">
              <table className="tx-table tax-table">
                <thead>
                  <tr>
                    <th scope="col" className="tax-check">
                      <input
                        type="checkbox"
                        aria-label={
                          allPicked ? '고른 거래 모두 풀기' : `이 조건에 맞는 거래 ${selectable}건 모두 고르기`
                        }
                        checked={allPicked}
                        /* 쪽에서 몇 건만 고른 상태. 빈 네모로 두면 아무것도 안 고른 것으로 읽힌다. */
                        ref={(el) => {
                          if (el) el.indeterminate = !allPicked && picked.size > 0;
                        }}
                        disabled={pickAll.isPending || businessId === undefined}
                        onChange={toggleAll}
                      />
                    </th>
                    <th scope="col">날짜</th>
                    <th scope="col">적요</th>
                    <th scope="col">분류</th>
                    <th scope="col" className="tx-num">금액</th>
                    <th scope="col">검토 상태</th>
                  </tr>
                </thead>
                <tbody>
                  {rows.map((t) => {
                    const review = TAX_REVIEW[t.taxReview.status];
                    return (
                      <tr key={t.transactionId} data-picked={picked.has(t.transactionId) || undefined}>
                        <td className="tax-check">
                          <input
                            type="checkbox"
                            aria-label={`${t.description} 고르기`}
                            checked={picked.has(t.transactionId)}
                            onChange={() => toggle(t.transactionId)}
                          />
                        </td>
                        <td data-label="날짜">{koreanDate(new Date(t.occurredAt))}</td>
                        <td data-label="적요">
                          {t.description}
                          {t.taxReview.note && <small>{t.taxReview.note}</small>}
                        </td>
                        <td data-label="분류">
                          {t.category ? (categoryName.get(t.category) ?? t.category) : '—'}
                          {t.classification.status !== 'CONFIRMED' && <small>분류 전 — 초안에 들어가지 않음</small>}
                        </td>
                        <td data-label="금액" className="tx-num" data-direction={t.direction}>
                          {t.direction === 'WITHDRAWAL' ? '−' : '+'}
                          {money(t.amount)}원
                        </td>
                        <td data-label="검토 상태">
                          <span className="tax-badge" data-tone={review.tone}>
                            <Icon name={review.icon} size={13} />
                            {review.label}
                          </span>
                          {excludedByReview(t.taxReview.status) && <small>초안에서 뺌</small>}
                        </td>
                      </tr>
                    );
                  })}
                </tbody>
              </table>
            </div>
            {data!.totalPages > 1 && (
              <nav className="tx-pager" aria-label="쪽 넘김">
                <button type="button" className="v7-secondary-button" disabled={page === 0} onClick={() => setPage(page - 1)}>
                  이전
                </button>
                {pageNumbers(page, data!.totalPages).map((n, i) =>
                  n === null ? (
                    <span key={`gap-${i}`} className="tx-pager__gap" aria-hidden="true">…</span>
                  ) : (
                    <button
                      key={n}
                      type="button"
                      className="tx-pager__page"
                      aria-label={`${n + 1}페이지`}
                      aria-current={page === n ? 'page' : undefined}
                      onClick={() => setPage(n)}
                    >
                      {n + 1}
                    </button>
                  ),
                )}
                <button
                  type="button"
                  className="v7-secondary-button"
                  disabled={page + 1 >= data!.totalPages}
                  onClick={() => setPage(page + 1)}
                >
                  다음
                </button>
              </nav>
            )}
          </>
        )}
      </Section>
    </PageShell>
  );
}
