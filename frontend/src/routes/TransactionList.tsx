import {
  confirmClassification,
  daysAgo,
  formatDateTime,
  getCurrentClassificationJob,
  importTransactionsCsv,
  requestClassification,
  listAccounts,
  listTransactionCategories,
  listTransactions,
  updateTransaction,
  type Account,
  type ClassificationStatus,
  type CurrentClassificationJob,
  type Direction,
  type ImportResult,
  type Transaction,
  type TransactionCategory,
} from '@jangoyebo/core';
import {
  attachableJobId,
  classificationProgress,
  unclassifiedPollInterval,
} from './classificationRun';
import { pageNumbers } from '@/components/pageNumbers';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { useEffect, useRef, useState } from 'react';
import { EmptyState } from '@/components/EmptyState';
import { Money } from '@/components/Money';
import { PageShell, Section as V7Section } from '@/v7/PageShell';
import { useBusiness } from '@/business/useBusiness';
import '@/styles/transaction.css';

/**
 * SC-01-5 CSV 업로드 + SC-04-1 거래 목록 (S15P21C105-53).
 *
 * 명세에 있으나 서버에 없는 필터·필드는 화면에 두지 않는다(구현현황 3.3).
 * category·금액범위 필터는 S15P21C105-54 가 열어 준다.
 */

const STATUS_LABEL: Record<ClassificationStatus, string> = {
  UNCLASSIFIED: '미분류',
  // 「제안됨」은 누가 제안했는지를 말하지 않아, 내가 뭘 해야 하는지가 안 읽혔다.
  SUGGESTED: 'AI 추천',
  CONFIRMED: '확정',
};

const STATUS_ICON: Record<ClassificationStatus, string> = {
  UNCLASSIFIED: '○',
  SUGGESTED: '◐',
  CONFIRMED: '●',
};

const PAGE_SIZE = 20;

export function TransactionList() {
  const { current } = useBusiness();
  const businessId = current?.businessId ?? 0;
  const queryClient = useQueryClient();

  const [from, setFrom] = useState(daysAgo(30));
  const [to, setTo] = useState(daysAgo(0));
  const [accountId, setAccountId] = useState('');
  const [direction, setDirection] = useState('');
  const [classificationStatus, setClassificationStatus] = useState('');
  const [needsConfirmation, setNeedsConfirmation] = useState(false);
  const [q, setQ] = useState('');
  const [page, setPageState] = useState(0);
  // 현재 목록에서 확정할 AI 추천 거래를 고른다.
  const [selected, setSelected] = useState<ReadonlySet<number>>(() => new Set());

  /**
   * 페이지·필터가 바뀌면 선택을 비운다.
   *
   * **목록이 달라지는 모든 경로가 여기로 모인다** — 필터 핸들러는 전부 `setPage(0)` 을
   * 부르고 페이지 이동도 이 함수를 지난다. 그래서 비우기를 한 곳에만 둘 수 있다.
   *
   * 선택을 남겨 두면 <b>화면에 뜬 적도 없는 거래를 확정하게 된다.</b> 도메인 규칙 3 이
   * 확정된 매핑을 거래처 사전에 캐시하므로 잘못 확정한 건은 굳어서 재사용된다.
   */
  const setPage = (next: number | ((p: number) => number)) => {
    setPageState(next);
    setSelected(new Set());
  };

  const query = {
    from,
    to,
    accountId: accountId ? Number(accountId) : undefined,
    direction: (direction || undefined) as Direction | undefined,
    classificationStatus: (classificationStatus || undefined) as ClassificationStatus | undefined,
    needsConfirmation: needsConfirmation || undefined,
    q: q.trim() || undefined,
    page,
    size: PAGE_SIZE,
  };

  const { data: accounts = [] } = useQuery({
    queryKey: ['accounts', businessId],
    enabled: businessId > 0,
    queryFn: async (): Promise<Account[]> => {
      const res = await listAccounts(businessId);
      return res.ready && res.ok ? res.data.items : [];
    },
  });

  // 23개 고정 목록이라 오래 들고 있는다.
  const { data: categories = [] } = useQuery({
    queryKey: ['transactionCategories'],
    staleTime: 1000 * 60 * 60,
    queryFn: async (): Promise<TransactionCategory[]> => {
      const res = await listTransactionCategories();
      return res.ready && res.ok ? res.data.items : [];
    },
  });

  const list = useQuery({
    queryKey: ['transactions', businessId, query],
    enabled: businessId > 0,
    queryFn: async () => {
      const res = await listTransactions(businessId, query);
      if (!res.ready) return { items: [] as Transaction[], totalPages: 0, error: null };
      if (!res.ok) return { items: [] as Transaction[], totalPages: 0, error: res.error.message };
      return { items: res.data.items, totalPages: res.data.totalPages, error: null };
    },
  });

  const classify = useMutation({
    mutationFn: (v: { transactionId: number; category: string }) =>
      updateTransaction(v.transactionId, { category: v.category }),
    onSuccess: () => queryClient.invalidateQueries({ queryKey: ['transactions'] }),
  });

  const rows = list.data?.items ?? [];
  const totalPages = list.data?.totalPages ?? 0;

  // 수락할 수 있는 것만 고를 수 있다 — 제안 상태이면서 분류가 있는 건(8.4 의 409 조건).
  // 확인 필요에는 아직 분류가 없는 미분류 거래도 섞여 들어오므로 여기서 갈라야 한다.
  const confirmable = rows.filter((t) => t.classification.status === 'SUGGESTED' && t.category);
  const chosen = confirmable.filter((t) => selected.has(t.transactionId));
  const allChosen = confirmable.length > 0 && chosen.length === confirmable.length;

  const toggleOne = (transactionId: number) =>
    setSelected((prev) => {
      const next = new Set(prev);
      // delete 는 없던 값이면 false 다 — 있으면 빼고 없으면 넣는 것을 한 번에 쓴다.
      if (!next.delete(transactionId)) next.add(transactionId);
      return next;
    });

  const toggleAll = () =>
    setSelected(allChosen ? new Set() : new Set(confirmable.map((t) => t.transactionId)));

  return (
    <PageShell
      title="거래·증빙"
    >
        <CsvUpload accounts={accounts} />
        <ClassificationPanel businessId={businessId} />

        <V7Section title="거래 목록">
          <div className="tx-filters">
            <label>
              <span>시작일</span>
              <input type="date" value={from} onChange={(e) => { setFrom(e.target.value); setPage(0); }} />
            </label>
            <label>
              <span>종료일</span>
              <input type="date" value={to} onChange={(e) => { setTo(e.target.value); setPage(0); }} />
            </label>
            <label>
              <span>계좌</span>
              <select value={accountId} onChange={(e) => { setAccountId(e.target.value); setPage(0); }}>
                <option value="">전체</option>
                {accounts.map((a) => (
                  <option key={a.accountId} value={a.accountId}>
                    {a.alias ?? a.accountNoMasked}
                  </option>
                ))}
              </select>
            </label>
            <label>
              <span>입·출금</span>
              <select value={direction} onChange={(e) => { setDirection(e.target.value); setPage(0); }}>
                <option value="">전체</option>
                <option value="DEPOSIT">입금</option>
                <option value="WITHDRAWAL">출금</option>
              </select>
            </label>
            <label>
              <span>분류</span>
              <select
                value={classificationStatus}
                onChange={(e) => { setClassificationStatus(e.target.value); setPage(0); }}
              >
                <option value="">전체</option>
                <option value="UNCLASSIFIED">미분류</option>
                <option value="SUGGESTED">AI 추천</option>
                <option value="CONFIRMED">확정</option>
              </select>
            </label>
            <label>
              <span>거래명 검색</span>
              <input
                type="search"
                value={q}
                placeholder="배달의민족"
                onChange={(e) => { setQ(e.target.value); setPage(0); }}
              />
            </label>
            <label className="tx-check">
              <input
                type="checkbox"
                checked={needsConfirmation}
                onChange={(e) => { setNeedsConfirmation(e.target.checked); setPage(0); }}
              />
              <span>확인 필요만</span>
            </label>
          </div>

          <BulkConfirm targets={chosen} onDone={() => setSelected(new Set())} />

          {list.data?.error ? (
            <p className="tx-error" role="alert">
              거래를 불러오지 못했습니다 — {list.data.error}
            </p>
          ) : list.isPending ? null : rows.length === 0 ? (
            <EmptyState
              title="이 기간에 거래가 없습니다"
              hint="기간을 넓히거나 위에서 CSV를 올려 보세요."
            />
          ) : (
            <>
              {/* div 격자 금지 — table 을 쓴다(화면구성도) */}
              <div className="tx-table-wrap">
                <table className="tx-table">
                  <thead>
                    <tr>
                        <th scope="col" className="tx-select">
                          <input
                            type="checkbox"
                            checked={allChosen}
                            disabled={confirmable.length === 0}
                            onChange={toggleAll}
                            aria-label={
                              allChosen
                                ? '선택 전부 해제'
                                : `확정할 수 있는 ${confirmable.length}건 전부 선택`
                            }
                          />
                        </th>
                      <th scope="col">거래일시</th>
                      <th scope="col">거래명</th>
                      <th scope="col" className="tx-num">금액</th>
                      <th scope="col" className="tx-num">거래 후 잔액</th>
                      <th scope="col">분류</th>
                    </tr>
                  </thead>
                  <tbody>
                    {rows.map((t) => (
                      <tr key={t.transactionId}>
                          <td className="tx-select">
                            <input
                              type="checkbox"
                              checked={selected.has(t.transactionId)}
                              // 분류가 없는 건은 수락할 것이 없다 — 8.4 가 409 를 낸다.
                              disabled={!confirmable.includes(t)}
                              onChange={() => toggleOne(t.transactionId)}
                              // 같은 거래처가 여러 행에 뜬다. 날짜를 함께 읽어야 구분된다.
                              aria-label={`${t.description || '거래명 없음'} ${formatDateTime(t.occurredAt)} 선택`}
                              title={
                                confirmable.includes(t) ? undefined : t.classification.status === 'CONFIRMED' ? '이미 확정된 거래입니다' : '분류를 먼저 선택해주세요'
                              }
                            />
                          </td>
                        <td data-label="거래일시" className="tnum">{formatDateTime(t.occurredAt)}</td>
                        <td data-label="거래명">{t.description || '—'}</td>
                        {/* 서버는 부호 없는 금액 + direction 을 준다. 출금은 −로 보여준다 */}
                        <td data-label="금액" className="tx-num" data-direction={t.direction}>
                          <Money value={t.direction === 'WITHDRAWAL' ? -t.amount : t.amount} />
                        </td>
                        <td data-label="거래 후 잔액" className="tx-num">
                          <Money value={t.balanceAfter} />
                        </td>
                        <td data-label="분류">
                          <span className="tx-status" data-status={t.classification.status}>
                            {/* 색 단독 금지 — 상태명·아이콘을 함께 둔다(도메인 규칙 6) */}
                            <span aria-hidden="true">{STATUS_ICON[t.classification.status]}</span>{' '}
                            {STATUS_LABEL[t.classification.status]}
                          </span>
                          <select
                            className="tx-category"
                            value={t.category ?? ''}
                            disabled={classify.isPending}
                            aria-label={`${t.description} 카테고리`}
                            onChange={(e) =>
                              classify.mutate({
                                transactionId: t.transactionId,
                                category: e.target.value,
                              })
                            }
                          >
                            <option value="">분류 선택</option>
                            {categories
                              .filter((c) => c.direction === t.direction)
                              .map((c) => (
                                <option key={c.code} value={c.code}>
                                  {c.name}
                                </option>
                              ))}
                          </select>
                        </td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              </div>

              <div className="tx-pager">
                <button type="button" disabled={page === 0} onClick={() => setPage((p) => p - 1)}>
                  이전
                </button>
                {/* 멀리 있는 쪽으로 한 번에 간다. 처음·끝과 현재 앞뒤만 보이고 나머지는 「…」 */}
                {pageNumbers(page, Math.max(totalPages, 1)).map((n, i) =>
                  n === null ? (
                    <span key={`gap-${i}`} className="tx-pager__gap" aria-hidden="true">
                      …
                    </span>
                  ) : (
                    <button
                      key={n}
                      type="button"
                      className="tx-pager__page tnum"
                      aria-current={n === page ? 'page' : undefined}
                      aria-label={`${n + 1}쪽`}
                      onClick={() => setPage(n)}
                    >
                      {n + 1}
                    </button>
                  ),
                )}
                <button
                  type="button"
                  disabled={page + 1 >= totalPages}
                  onClick={() => setPage((p) => p + 1)}
                >
                  다음
                </button>
              </div>
            </>
          )}
        </V7Section>
    </PageShell>
  );
}

/**
 * CSV 업로드 (API 7.4).
 *
 * 응답을 받은 시점에 Job 은 이미 COMPLETED 이고 resultRef 가 채워져 있다 — 폴링하지 않는다.
 */
function CsvUpload({ accounts }: { accounts: Account[] }) {
  const queryClient = useQueryClient();
  const [accountId, setAccountId] = useState('');
  const [file, setFile] = useState<File | null>(null);
  /** 감춰 둔 파일 입력. 보이는 단추가 대신 눌러 준다 */
  const csvInput = useRef<HTMLInputElement>(null);
  const [result, setResult] = useState<ImportResult | null>(null);
  const [error, setError] = useState<string | null>(null);
  /** 파일을 끌고 들어와 있는가. 놓을 자리가 어디인지 보여 주는 표시에만 쓴다 */
  const [dragging, setDragging] = useState(false);

  const target = accountId || (accounts.length === 1 ? String(accounts[0].accountId) : '');

  const upload = useMutation({
    mutationFn: async () => {
      if (!file || !target) return;
      setResult(null);
      setError(null);

      const res = await importTransactionsCsv(Number(target), file, file.name);
      if (!res.ready) {
        setError('CSV 업로드 기능이 아직 준비되지 않았습니다.');
        return;
      }
      if (!res.ok) {
        // 파일 규격이 틀리면 Job 없이 즉시 400 이다. 서버가 어느 컬럼이 없는지까지 알려준다.
        setError(res.error.message);
        return;
      }
      setResult(res.data.resultRef ?? null);
      await queryClient.invalidateQueries({ queryKey: ['transactions'] });
    },
  });

  return (
    <V7Section title="CSV 업로드">
      {accounts.length === 0 ? (
        <EmptyState
          title="먼저 계좌를 연결해 주세요"
          hint="거래를 올릴 계좌가 있어야 합니다."
        />
      ) : (
        <>
          <div className="tx-upload">
            {accounts.length > 1 && (
              <label>
                <span>계좌</span>
                <select value={target} onChange={(e) => setAccountId(e.target.value)}>
                  <option value="">계좌를 고르세요</option>
                  {accounts.map((a) => (
                    <option key={a.accountId} value={a.accountId}>
                      {a.alias ?? a.accountNoMasked}
                    </option>
                  ))}
                </select>
              </label>
            )}
            {/* 끌어다 놓는 길을 더한다. 통장 내역을 내려받아 올리는 흐름에서 파일 고르기
              * 창을 따로 여는 일이 한 번 줄어든다.
              *
              * <b>버튼은 그대로 둔다.</b> 끌기는 마우스만의 길이라, 이것만 두면 키보드로
              * 쓰는 사람에게는 문이 없어진다(화면구성도 6장).
              *
              * 이름표는 점선 상자 밖, 위에 둔다 — 안에 넣으면 상자의 안쪽 여백만큼 내려가
              * 옆 「계좌」와 높이가 어긋난다.
              *
              * 여러 장을 놓아도 첫 장만 받는다 — 업로드는 계좌 하나에 파일 하나다. */}
            <div className="tx-upload__file">
              <label htmlFor="transaction-csv">거래 내역 파일</label>
              <div
                className={`tx-drop ${dragging ? 'is-over' : ''}`}
                onDragOver={(e) => {
                  e.preventDefault();
                  setDragging(true);
                }}
                onDragLeave={() => setDragging(false)}
                onDrop={(e) => {
                  e.preventDefault();
                  setDragging(false);
                  const dropped = e.dataTransfer.files?.[0];
                  if (!dropped) return;
                  // 확장자로 먼저 거른다. 서버도 막지만, 엉뚱한 파일을 올려 놓고 오류를
                  // 기다리게 하는 것보다 그 자리에서 말해 주는 쪽이 빠르다.
                  if (!/\.csv$/i.test(dropped.name)) {
                    setError('CSV 파일만 올릴 수 있습니다.');
                    return;
                  }
                  setError(null);
                  setFile(dropped);
                }}
              >
                <em className="tx-drop__hint">
                  {file ? (
                    '선택한 파일을 업로드하면 거래 목록에 추가됩니다.'
                  ) : (
                    <>
                      CSV 파일을 고르세요.
                      {/* 끌어놓기는 마우스에만 있다. 좁은 화면에서는 CSS 가 이 문장을 지운다 —
                          없는 동작을 안내하면 그 자리에서 길을 잃는다 */}
                      <span className="tx-drop__drag"> 여기에 끌어 놓아도 됩니다.</span>
                    </>
                  )}
                </em>
              </div>

              {/* 고르기와 업로드는 점선 상자 <b>밖</b>에 둔다. 상자는 끌어다 놓는 자리라는
                * 표시일 뿐인데, 안에 넣으면 서로 다른 크기의 단추 둘이 상자에 갇혀
                * 크기가 안 맞아 보인다. 상자는 안내문만 안고 위에 남는다. */}
              <div className="tx-upload__row">
                {/* 파일 입력은 <b>글자까지 통째로 눌린다.</b> 「선택된 파일 없음」을 눌러도
                  * 고르기 창이 열리는데, 그 글자는 단추가 아니라 상태 표시다. 브라우저가
                  * 그리는 부분이라 그 자리만 떼어 막을 수 없어, 입력을 감추고 단추와
                  * 파일 이름을 우리가 그린다.
                  *
                  * 지우지 않고 감추는 이유 — 위 이름표(`for`)가 이 입력을 가리키고,
                  * 낭독기에는 남아야 한다(`sr-only` 는 접근성 트리에 남는다). */}
                <input
                  ref={csvInput}
                  id="transaction-csv"
                  className="sr-only"
                  type="file"
                  accept=".csv,text/csv"
                  onChange={(e) => setFile(e.target.files?.[0] ?? null)}
                />
                <button type="button" className="v7-secondary-button" onClick={() => csvInput.current?.click()}>
                  파일 선택
                </button>
                <span className="tx-upload__name">{file ? file.name : '선택된 파일 없음'}</span>
                <button
                  type="button"
                  className="auth-submit"
                  disabled={!file || !target || upload.isPending}
                  onClick={() => upload.mutate()}
                >
                  {upload.isPending ? '올리는 중…' : '업로드'}
                </button>
              </div>
            </div>
          </div>

          <details className="tx-spec">
            <summary>CSV 컬럼 규격</summary>
            <table className="tx-table">
              <thead>
                <tr>
                  <th scope="col">컬럼</th>
                  <th scope="col">영문 헤더</th>
                  <th scope="col">필수</th>
                  <th scope="col">형식</th>
                </tr>
              </thead>
              <tbody>
                <tr><td data-label="컬럼">거래일시</td><td data-label="영문 헤더">transactionAt</td><td data-label="필수">필수</td><td data-label="형식">2026-09-01 10:00:00</td></tr>
                <tr><td data-label="컬럼">적요</td><td data-label="영문 헤더">description</td><td data-label="필수">필수</td><td data-label="형식">문자열</td></tr>
                <tr><td data-label="컬럼">입금액</td><td data-label="영문 헤더">depositAmount</td><td data-label="필수">필수</td><td data-label="형식">정수 (출금 행은 0 또는 빈 값)</td></tr>
                <tr><td data-label="컬럼">출금액</td><td data-label="영문 헤더">withdrawalAmount</td><td data-label="필수">필수</td><td data-label="형식">정수 (입금 행은 0 또는 빈 값)</td></tr>
                <tr><td data-label="컬럼">거래후잔액</td><td data-label="영문 헤더">balanceAfter</td><td data-label="필수">선택</td><td data-label="형식">정수</td></tr>
              </tbody>
            </table>
            <p className="tx-spec__note">
              헤더가 있어야 하고 한글·영문 모두 됩니다. 입금액과 출금액 중 하나만 양수여야 합니다.
              천 단위 구분자와 원·₩ 기호는 서버가 알아서 걷어냅니다. 최대 10MB.
            </p>
          </details>

          {error && (
            <p className="tx-error" role="alert">
              {error}
            </p>
          )}

          {result && (
            <div className="tx-result">
              <p>
                <strong className="tnum">{result.importedCount}건</strong> 저장했습니다.
                {result.duplicateCount > 0 && (
                  <> 이미 있던 <span className="tnum">{result.duplicateCount}건</span>은 건너뛰었습니다.</>
                )}
              </p>
              {result.rejectedRows.length > 0 && (
                <>
                  <p className="tx-result__reject">
                    {/* 거부된 행은 사용자가 파일에서 바로 찾아 고쳐야 한다. 행 번호를 그대로 보여준다 */}
                    올리지 못한 행 {result.rejectedRows.length}건입니다. 파일에서 고친 뒤 다시 올려 주세요.
                  </p>
                  <table className="tx-table">
                    <thead>
                      <tr>
                        <th scope="col">행</th>
                        <th scope="col">사유</th>
                      </tr>
                    </thead>
                    <tbody>
                      {result.rejectedRows.map((r) => (
                        <tr key={r.rowNumber}>
                          <td data-label="행" className="tnum">{r.rowNumber}</td>
                          <td data-label="사유">{r.reason}</td>
                        </tr>
                      ))}
                    </tbody>
                  </table>
                </>
              )}
            </div>
          )}
        </>
      )}
    </V7Section>
  );
}


// 분류기는 **오래된 거래부터** 집어 간다(`TransactionRepository#findUnclassified`).
// 화면 기본 필터는 최근 30일이라, 눌러도 한동안 이 화면에서는 아무것도 안 바뀐다.
// 2026-09-14 확인에서 실제로 "분류가 안 된다" 로 읽혔다. 그래서 이 패널은 **필터와 무관한
// 전체 수**를 따로 보여준다.
const ALL_FROM = daysAgo(3650);
const ALL_TO = daysAgo(-365);

const POLL_MS = 3_000;

// 남은 건수가 이만큼 그대로면 워커가 죽은 것으로 본다. 한 배치 실측이 14초(Sonnet)라
// 정상 처리 간격의 네 배쯤이다 — 큐가 길어도 헛경보가 나지 않는 여유다.
const STALL_MS = 60_000;

// 진행 중인 작업이 있는지 묻는 주기(8.5.1). 잔량 폴링보다 훨씬 성기게 둔다 — 여기서
// 알아내는 것은 「도는가」 하나뿐이고, 얼마나 남았는지는 잔량 쿼리가 센다.
// **도는 동안에도 계속 묻는다.** 멈추면 이어달리기가 만든 새 jobId 를 못 봐서, 정체로
// 손을 뗄 때 무엇을 걸러야 할지 알 수 없다.
const DETECT_POLL_MS = 15_000;

/**
 * 미분류 거래를 AI 분류에 넣고 진행을 보여준다 (명세 8.5).
 *
 * **버튼 한 번이 한 배치로 끝나지 않는다.** 서버가 20건을 처리한 뒤 남아 있으면 다음 작업을
 * 이어 건다(`AiResultApplier#chainIfUnfinished`). 게다가 분류기는 **오래된 거래부터** 집어
 * 가는데 목록 기본 필터는 최근 30일이라, 목록만 보면 한동안 아무 변화가 없다.
 * 그래서 진행을 **남은 미분류 건수**로 재서 막대로 보여준다.
 *
 * ## 「워커가 꺼졌다」와 「앞 작업이 막고 있다」는 다른 상황이다
 *
 * 8.5 는 이미 도는 작업이 있으면 409 가 아니라 **그 작업을 그대로 돌려준다**
 * (`JobService#enqueueOrCurrent`). 그래서 앞 작업이 어떤 이유로 `PROCESSING` 에 갇히면
 * **그 뒤의 모든 클릭이 그 작업에 흡수된다** — 새 작업이 안 생기고, 워커를 켜도 아무 일이
 * 일어나지 않는다.
 *
 * 2026-09-14 에 이걸로 막혔다. 워커를 켠 시각이 백엔드가 부른 **3초 뒤**라 그 요청이
 * 실패했고, 남은 Job 이 10분(`jango.job.stall-timeout`) 동안 버튼을 먹었다. 화면은 계속
 * "워커를 확인하세요" 라고만 해서 **켰는데도 같은 말이 나오는** 상태가 됐다.
 *
 * 그래서 접수 응답의 `createdAt` 을 본다. 누른 시각보다 **먼저 만들어진 작업**이 돌아오면
 * 그건 내 것이 아니라 앞에서 막고 있던 것이다.
 *
 * ## 작업 상태로 진행을 판단하지 않는다
 *
 * 한 번 그렇게 만들었다가 **20~60건에서 멈추는 버그**를 냈다(2026-09-14). 접수 때 받은
 * `jobId` 를 `getJob` 으로 보다가 `COMPLETED` 를 만나면 끝난 줄 알고 추적을 그만뒀는데,
 * 그 작업은 20건만 맡고 **서버가 다음 작업을 새 id 로 이어 건다.** 백엔드는 멀쩡히 도는데
 * 화면만 먼저 멈췄다.
 *
 * **진행 단위는 Job 이 아니라 남은 거래 수다.** 멈추는 조건도 그것 하나로 둔다 —
 * 남은 게 0 이거나, `STALL_MS` 동안 줄지 않거나.
 *
 * ## 돌아오면 다시 붙는다
 *
 * 분류는 브라우저와 무관하게 서버에서 돈다(워커 스레드 → AI 워커 → 콜백 → 이어달리기).
 * 그런데 진행 표시는 전부 이 컴포넌트의 로컬 상태라, 다른 화면에 갔다 오면 **돌고 있는데
 * 아무 표시도 없는** 상태가 된다. 그러면 사용자는 멈춘 줄 알고 버튼을 다시 누른다.
 *
 * 그래서 8.5.1 로 「지금 도는 작업이 있는가」를 묻고 **그 답을 실행 여부의 단일 출처로
 * 삼는다.** 화면에 로컬 플래그를 따로 두면 갔다 온 순간 둘이 갈라진다 — 서버는 돌고 있는데
 * 화면만 아니라고 한다. 8.5 `requestClassification` 으로는 이 일을 못 한다: 그쪽은 도는
 * 것이 없으면 **새로 만들기 때문에** 화면에 들어올 때마다 분류가 시작되고 값이 나간다.
 *
 * 버튼을 눌렀을 때만 감지 폴링을 기다리지 않고 응답을 캐시에 바로 써 넣는다. 다음 폴링이
 * 서버 상태로 덮으므로 어긋나도 `DETECT_POLL_MS` 안에 맞춰진다.
 *
 * **감지로 붙은 실행은 분모를 모른다.** 내가 시작시킨 게 아니라 얼마에서 출발했는지 알 수
 * 없어서, 「몇 건 중 몇 건」 대신 남은 건수만 보여주고 막대는 불확정으로 둔다. 모르는 수를
 * 지어내지 않는다(도메인 규칙 1). 그 판단은 `classificationRun.ts` 에 있다 — 분기가 많아
 * 테스트가 붙어 있는 쪽이 낫다.
 *
 * **정체로 한 번 손을 뗀 작업에는 다시 붙지 않는다.** 그러지 않으면 `STALL_MS` 마다
 * 「붙음 → 정체 → 감지 → 다시 붙음」이 무한히 돈다. 손을 뗀 jobId 를 기억해 두고 그것과
 * 다른 작업일 때만 붙는다 — 이어달리기가 새 id 를 만들면 자동으로 다시 붙는다는 뜻이기도
 * 하다.
 */
function ClassificationPanel({ businessId }: { businessId: number }) {
  const queryClient = useQueryClient();
  // 접수 응답이 **이전 작업**이었나. `createdAt` 이 클릭보다 앞서면 그렇다.
  const [inherited, setInherited] = useState(false);
  const [notice, setNotice] = useState<'stall' | 'reject' | null>(null);
  // 클릭 시각과 그때의 미분류 수. 「몇 건 중 몇 건」의 분모는 여기서만 나온다.
  const [startedAt, setStartedAt] = useState<number | null>(null);
  const [startTotal, setStartTotal] = useState<number | null>(null);
  const [watch, setWatch] = useState<{ job: number | null; seen: number; at: number }>({
    job: null,
    seen: -1,
    at: 0,
  });
  // 정체로 손을 뗀 작업. 같은 것에 다시 붙지 않으려고 기억한다.
  const [stalledJobId, setStalledJobId] = useState<number | null>(null);

  // 「지금 도는 작업이 있는가」 (8.5.1). **이것이 실행 여부의 단일 출처다.**
  // 화면 로컬 플래그를 따로 두면 다른 화면에 갔다 온 순간 둘이 갈라진다 — 서버는 돌고
  // 있는데 화면만 아니라고 한다.
  const current = useQuery({
    queryKey: ['classificationJobCurrent', businessId],
    enabled: businessId > 0,
    refetchInterval: DETECT_POLL_MS,
    queryFn: async () => {
      const res = await getCurrentClassificationJob(businessId);
      return res.ready && res.ok ? res.data : null;
    },
  });

  // 붙을 작업의 id. 정체로 손을 뗀 그 작업이면 `null` 이다 — 규칙은 `classificationRun.ts`.
  const detectedJobId = attachableJobId(current.data, stalledJobId);
  const detected = detectedJobId !== null;

  const total = useQuery({
    queryKey: ['unclassified', businessId],
    enabled: businessId > 0,
    // 자기 데이터를 봐야 정할 수 있어 함수로 준다 — `running` 은 이 쿼리 뒤에 선다.
    // 규칙(잔량 0 이면 멈춘다)은 `classificationRun.ts` 에 있다.
    refetchInterval: (query) => unclassifiedPollInterval(detected, query.state.data, POLL_MS),
    queryFn: async () => {
      const res = await listTransactions(businessId, {
        from: ALL_FROM,
        to: ALL_TO,
        classificationStatus: 'UNCLASSIFIED',
        page: 0,
        size: 1,
      });
      return res.ready && res.ok ? res.data.totalElements : null;
    },
  });

  // `data` 는 로딩 중 `undefined`, 호출 실패 시 `null` 이다. 둘 다 「아직 모름」으로 묶는다.
  const remaining = total.data ?? null;
  const running = detected && remaining !== 0;

  // 진행 문구와 막대. 「몇 건 중 몇 건」을 언제 쓸 수 있는지는 `classificationRun.ts` 가 정한다.
  const progress = classificationProgress({
    jobCreatedAt: current.data?.job?.createdAt,
    remaining,
    startedAt,
    startTotal,
  });

  // 진행 감시·정지 판정. **setState 가 전부 인터벌 콜백 안**이라 이펙트 본문의 동기
  // setState 가 아니다 — 그건 렌더를 연쇄로 돌린다(린트가 막는다).
  useEffect(() => {
    if (!running) return undefined;
    const timer = window.setInterval(() => {
      const now = Date.now();
      // 다른 실행으로 바뀌었으면 시계를 새로 잡는다. 앞 실행의 `at` 을 물려받으면
      // 시작하자마자 정체로 읽힌다.
      if (watch.job !== detectedJobId) {
        setWatch({ job: detectedJobId, seen: remaining ?? -1, at: now });
        return;
      }
      if (remaining !== null && remaining !== watch.seen) {
        setWatch({ job: detectedJobId, seen: remaining, at: now });
        return;
      }
      if (watch.at > 0 && now - watch.at > STALL_MS) {
        setNotice('stall');
        // 이 작업에는 다시 붙지 않는다. 이어달리기가 새 id 를 만들면 그때 다시 붙는다.
        setStalledJobId(detectedJobId);
      }
    }, POLL_MS);
    return () => window.clearInterval(timer);
  }, [running, remaining, watch, detectedJobId]);

  // 목록 갱신은 따로 둔다. 진행 판정과 주기가 달라도 되고, 섞으면 읽기 어렵다.
  const wasRunning = useRef(false);
  useEffect(() => {
    if (!running) {
      // 방금 끝났으면 한 번 더 받아 온다 — 마지막 배치가 목록에 안 실린 채 멈추면
      // 「눌렀는데 그대로」로 읽힌다.
      if (wasRunning.current) {
        wasRunning.current = false;
        void queryClient.invalidateQueries({ queryKey: ['transactions'] });
      }
      return undefined;
    }
    wasRunning.current = true;
    void queryClient.invalidateQueries({ queryKey: ['transactions'] });
    const timer = window.setInterval(() => {
      void queryClient.invalidateQueries({ queryKey: ['transactions'] });
    }, POLL_MS * 2);
    return () => window.clearInterval(timer);
  }, [running, queryClient]);

  const start = useMutation({
    mutationFn: async () => {
      setNotice(null);
      setInherited(false);
      const clickedAt = Date.now();
      const res = await requestClassification(businessId);
      if (!res.ready || !res.ok) {
        console.error('분류 접수 실패', res.ready ? res.error : 'NOT_READY');
        setNotice('reject');
        return;
      }
      // 내 클릭보다 **먼저 만들어진** 작업이 돌아왔다 = 앞에서 막고 있던 것이다.
      // 시계 오차를 감안해 몇 초 여유를 둔다.
      const createdAt = res.data.createdAt ? Date.parse(res.data.createdAt) : clickedAt;
      setInherited(Number.isFinite(createdAt) && clickedAt - createdAt > 5_000);
      setStartedAt(clickedAt);
      setStartTotal(remaining);
      setWatch({ job: res.data.jobId ?? null, seen: remaining ?? -1, at: clickedAt });
      // 감지 폴링을 기다리지 않고 바로 채운다 — 누른 즉시 진행이 보여야 한다.
      // 다음 폴링이 서버 상태로 덮으므로 어긋나도 `DETECT_POLL_MS` 안에 맞춰진다.
      queryClient.setQueryData<CurrentClassificationJob>(
        ['classificationJobCurrent', businessId],
        { running: true, job: res.data },
      );
      // 손을 뗐던 기억은 버튼을 다시 누르는 순간 지운다 — 사용자가 다시 보겠다고 했다.
      setStalledJobId(null);
    },
  });

  if (!businessId) return null;

  return (
    <V7Section title="AI 분류">
      <div className="tx-classify">
        <button
          type="button"
          className="auth-submit"
          onClick={() => start.mutate()}
          disabled={start.isPending || running || remaining === 0}
        >
          미분류 거래 분류하기
        </button>

        {running ? (
          <div className="tx-classify-progress">
            {/* 출발점을 모르는 실행에서는 `value` 가 없다 — 불확정 막대가 된다. */}
            <progress className="tx-classify-bar" max={progress.max} value={progress.value} />
            <span>{progress.label}</span>
          </div>
        ) : null}

        {/* 색만으로 상태를 구분하지 않는다(도메인 규칙 6). 문장이 다음에 할 일을 말한다. */}
        {notice === 'stall' ? (
          <p className="tx-classify-stall">
            {inherited
              ? '앞선 분류 작업이 아직 끝나지 않아 새 분류를 시작하지 못했습니다. 10분쯤 뒤 다시 누르면 남은 거래부터 이어서 처리합니다.'
              : '거래 분류가 지연되고 있습니다. 잠시 뒤 다시 시도해 주세요. 지금까지 분류된 결과는 그대로 남아 있고, 다시 누르면 이어서 처리합니다.'}
          </p>
        ) : null}

        {notice === 'reject' ? (
          <p className="tx-classify-stall">
            분류 요청이 접수되지 않았습니다. 잠시 뒤 다시 시도해 주세요.
          </p>
        ) : null}
      </div>
    </V7Section>
  );
}

/**
 * 고른 「확인 필요」 거래를 확정한다 (명세 8.4).
 *
 * ## 사용자가 고른 것만 확정한다
 *
 * 처음에는 현재 페이지를 통째로 확정했는데, 그러면 **전부 받거나 하나도 안 받거나** 둘뿐이라
 * 틀린 게 하나 섞이면 버튼을 못 쓰게 된다. 여기 남는 건은 신뢰도가 기준 미만인 것들 —
 * 모델이 스스로 자신 없다고 한 판단이라 섞여 있는 것이 정상이다.
 *
 * `확정됨` 은 명세 8.2 에서 **사람이 봤다**는 뜻이고, 도메인 규칙 3 이 확정된 매핑을
 * 거래처 사전에 캐시하므로 잘못 확정한 건은 **굳어서 재사용된다.** 고를 수 있어야 하는
 * 이유가 그것이다.
 *
 * 몇 건을 확정하는지는 버튼 라벨에 그대로 적는다.
 *
 * ## 409 는 실패가 아니다
 *
 * 이미 확정된 거래에 수락이 오면 서버가 `CLASSIFICATION_STATE_CONFLICT` 를 준다. 목록을
 * 띄워 둔 사이 다른 곳에서 바뀐 것이라, 일괄 처리에서는 **「이미 처리됨」으로 세고 넘어간다.**
 *
 * @param targets 사용자가 고른 거래. 고르는 쪽이 이미 `SUGGESTED` + 분류 있음으로 걸러 둔다
 * @param onDone  확정이 끝난 뒤 선택을 비우라고 알린다 — 처리된 행이 목록에서 빠지므로
 *                남겨 두면 없는 id 가 선택에 남는다
 */
function BulkConfirm({ targets, onDone }: { targets: Transaction[]; onDone: () => void }) {
  const queryClient = useQueryClient();
  const [result, setResult] = useState<string | null>(null);

  const confirm = useMutation({
    mutationFn: async () => {
      setResult(null);
      let done = 0;
      let already = 0;
      let failed = 0;

      // 순차로 보낸다. 한꺼번에 던지면 20건이 동시에 들어가 서버가 받는 순서가 뒤섞이고,
      // 실패했을 때 어디까지 됐는지 말해 줄 수 없다.
      for (const tx of targets) {
        const res = await confirmClassification(tx.transactionId);
        if (!res.ready) {
          failed += 1;
        } else if (res.ok) {
          done += 1;
        } else if (res.error.code === 'CLASSIFICATION_STATE_CONFLICT') {
          already += 1;
        } else {
          failed += 1;
        }
      }

      const parts = [`${done}건 확정했습니다`];
      if (already > 0) parts.push(`${already}건은 이미 확정돼 있었습니다`);
      if (failed > 0) parts.push(`${failed}건은 실패했습니다`);
      setResult(`${parts.join(' · ')}.`);
      onDone();
      await queryClient.invalidateQueries({ queryKey: ['transactions'] });
    },
  });

  return (
    <div className="tx-bulk">
      <button
        type="button"
        className="auth-submit"
        onClick={() => confirm.mutate()}
        disabled={confirm.isPending || targets.length === 0}
      >
        {confirm.isPending ? '확정하는 중…' : `${targets.length}건 확정`}
      </button>
      <p>
        확정할 거래를 <strong>왼쪽 체크박스로 고른</strong> 뒤 누르세요. AI가 제안한 분류를
        그대로 확정합니다 — 다른 분류로 바꾸려면 각 행의 분류 선택을 쓰세요.
      </p>
      {result ? <p role="status">{result}</p> : null}
    </div>
  );
}
