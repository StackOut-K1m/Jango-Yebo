import { useEffect, useState } from 'react';
import type { CSSProperties } from 'react';
import { useForm } from 'react-hook-form';
import { zodResolver } from '@hookform/resolvers/zod';
import { z } from 'zod';
import { useQuery, useQueryClient } from '@tanstack/react-query';
import {
  formatDateTime,
  formatManwon,
  formatMoney,
  getCredit,
  getJob,
  isJobTerminal,
  listLoanProducts,
  listLoans,
  syncLoans,
} from '@jangoyebo/core';
import type {
  ApiResult,
  Confidence,
  Job,
  LoanItem,
  LoanProductItem,
  LoanProductRecommendation,
  LoanSyncResult,
} from '@jangoyebo/core';
import { useBusiness } from '@/business/useBusiness';
import { Field, FormError } from './AuthShell';
import { PageShell, Section } from './PageShell';
import { Icon } from './icons';
import { STATUS, dayLabel, monthLabel, parseAmount, ratePercent, repaymentLabel } from './loanView';
import './loan.css';

/**
 * 대출·신용 (SC-10-1·2, API 16.1~16.3, P1-13·14).
 *
 * 세 구획이 한 화면에 선다 — 지금 갚는 대출, 등급, 새로 받을 수 있는 대출.
 * 추천 표가 「하루 상환액」과 「상환 후 최저 잔액」을 먼저 보이는 것은 위 두 구획과 같은 질문
 * (이 돈을 갚을 수 있나)에 답하기 때문이다.
 *
 * ⚠ 16.2·16.3 은 **부작용이 있는 GET 이다** — 부를 때마다 금융망을 부르고 스냅샷을 쓴다.
 *   페이지에 들어올 때 한 번만 부르고, 창 포커스·재연결로 다시 부르지 않는다.
 */

/** 서버가 영영 끝나지 않으면 탭이 닫힐 때까지 요청이 나간다. 여기서 끊는다(AccountSync 와 같은 값) */
const POLL_MS = 700;
const TIMEOUT_MS = 60_000;

/** 부작용 있는 GET 을 한 번만 부르는 설정. 새로 보려면 사용자가 버튼을 누른다 */
const ONCE = {
  staleTime: Infinity,
  gcTime: 30 * 60_000,
  retry: false,
  refetchOnWindowFocus: false,
  refetchOnReconnect: false,
} as const;

const CONFIDENCE: Record<Confidence, string> = {
  HIGH: '높음',
  MEDIUM: '보통',
  LOW: '낮음',
  UNPREDICTABLE: '예측 어려움',
};

type Loaded<T> = { state: 'ready'; data: T } | { state: 'not-ready' } | { state: 'error'; message: string };

/** NOT_READY 는 오류가 아니라 정상 빈 상태다(화면구성도 6.4) — 던지지 않고 갈래로 돌려준다 */
function settle<T>(res: ApiResult<T>): Loaded<T> {
  if (!res.ready) return { state: 'not-ready' };
  if (!res.ok) return { state: 'error', message: res.error.message };
  return { state: 'ready', data: res.data };
}

export function LoanScreen() {
  const { current } = useBusiness();
  const businessId = current?.businessId;

  return (
    <PageShell
      title="대출·신용"
    >
      {businessId === undefined ? (
        <p className="v7-note">사업장을 불러오는 중…</p>
      ) : (
        <>
          <LoanList businessId={businessId} />
          <CreditPanel businessId={businessId} />
          <ProductFinder businessId={businessId} />
        </>
      )}
    </PageShell>
  );
}

// --- 16.1 대출 현황 ------------------------------------------------------------

function LoanList({ businessId }: { businessId: number }) {
  const queryClient = useQueryClient();
  const loans = useQuery({
    queryKey: ['v7', 'loans', businessId],
    queryFn: async () => settle(await listLoans(businessId)),
  });

  const [jobId, setJobId] = useState<number | null>(null);
  const [syncError, setSyncError] = useState<string | null>(null);
  // 마감은 렌더에서 시각을 읽지 않고 타이머가 세운다 — 사정은 AccountSync 주석에 있다
  const [deadlinePassed, setDeadlinePassed] = useState(false);

  const job = useQuery({
    queryKey: ['job', jobId],
    enabled: jobId !== null,
    queryFn: async (): Promise<Job<LoanSyncResult> | null> => {
      const res = await getJob<LoanSyncResult>(jobId!);
      return res.ready && res.ok ? res.data : null;
    },
    refetchInterval: (q) => (isJobTerminal(q.state.data?.status) || deadlinePassed ? false : POLL_MS),
  });
  const status = job.data?.status;
  const running = jobId !== null && !isJobTerminal(status) && !deadlinePassed;

  useEffect(() => {
    if (jobId === null) return;
    const timer = setTimeout(() => setDeadlinePassed(true), TIMEOUT_MS);
    return () => clearTimeout(timer);
  }, [jobId]);

  // 동기화는 대출만 바꾸지 않는다 — 상환 회차가 예정 거래(source=LOAN)로 들어가 곡선이 바뀐다.
  // 추천도 「상환 후 최저 잔액」을 그 곡선으로 재므로 함께 버린다.
  useEffect(() => {
    if (status !== 'COMPLETED') return;
    for (const key of ['loans', 'forecast', 'cash-gaps', 'planned', 'loan-products']) {
      queryClient.invalidateQueries({ queryKey: ['v7', key] });
    }
  }, [status, queryClient]);

  async function onSync() {
    setSyncError(null);
    setDeadlinePassed(false);
    setJobId(null);
    const res = await syncLoans(businessId);
    if (!res.ready) return setSyncError('대출 불러오기 기능이 아직 준비되지 않았습니다.');
    if (!res.ok) return setSyncError(`불러오지 못했습니다 — ${res.error.message}`);
    setJobId(res.data.jobId);
  }

  const result = status === 'COMPLETED' ? job.data?.resultRef : undefined;
  const items = loans.data?.state === 'ready' ? loans.data.data.items : [];
  // 끝난 대출은 뒤로 — 지금 빠져나가는 돈이 먼저다
  const sorted = [...items].sort((a, b) => Number(a.status === 'CLOSED') - Number(b.status === 'CLOSED'));

  const syncButton = (
    <button type="button" className="v7-secondary-button" onClick={onSync} disabled={running}>
      <Icon name="refresh" size={15} />
      {running ? '불러오는 중…' : '금융망에서 불러오기'}
    </button>
  );

  return (
    <Section title="지금 갚고 있는 대출" action={syncButton}>
      {/* 자동으로 갱신되지 않는다는 사실을 숨기지 않는다 — 누르지 않으면 영영 비어 있다 */}
      <p className="v7-note v7-note--small">
        대출은 자동으로 갱신되지 않습니다. 새로 받았거나 갚았다면 「금융망에서 불러오기」를 눌러 주세요.
        불러온 상환 일정은 잔액 예보에 반영됩니다.
      </p>

      {running && (
        <p className="v7-note loan-status-line" role="status">
          <Icon name="clock" size={16} />
          {status === 'PROCESSING' ? '대출과 상환 내역을 가져오는 중…' : '불러오기를 기다리는 중…'}
        </p>
      )}
      {syncError && (
        <p className="v7-inline-error" role="alert">
          ⚠ {syncError}
        </p>
      )}
      {jobId !== null && deadlinePassed && !isJobTerminal(status) && (
        <p className="v7-inline-error" role="alert">
          ⚠ 아직 끝나지 않았습니다. 잠시 후 새로 고쳐 확인해 주세요.
        </p>
      )}
      {status === 'FAILED' && (
        <p className="v7-inline-error" role="alert">
          ⚠ 불러오지 못했습니다{job.data?.error ? ` — ${job.data.error.message}` : ''}
        </p>
      )}
      {result && (
        <div className="v7-note v7-note--good" role="status">
          <Icon name="check" size={18} />
          <div>
            <strong>대출 {result.loanCount}건을 불러왔습니다</strong>
            <span>
              상환 {result.installmentCount}회차를 예정 거래로 넣었습니다.
              {result.closedCount > 0 && ` 다 갚은 대출 ${result.closedCount}건은 상환 완료로 표시했습니다.`}
              {result.unattachedCount > 0 &&
                ` ${result.unattachedCount}건은 출금 계좌로 어느 가게 대출인지 가르지 못해 여기에 붙이지 않았습니다.`}
            </span>
          </div>
        </div>
      )}

      {loans.isPending ? (
        <p className="v7-note">불러오는 중…</p>
      ) : loans.data?.state === 'not-ready' ? (
        <div className="empty-state">대출 현황 기능이 아직 준비되지 않았습니다.</div>
      ) : loans.data?.state === 'error' ? (
        <p className="v7-inline-error" role="alert">
          ⚠ 대출을 불러오지 못했습니다 — {loans.data.message}{' '}
          <button type="button" className="v7-secondary-button" onClick={() => void loans.refetch()}>
            다시 불러오기
          </button>
        </p>
      ) : sorted.length === 0 ? (
        <div className="empty-state">
          등록된 대출이 없습니다.
          <br />
          금융망에서 받은 대출이 있다면 위의 「금융망에서 불러오기」를 눌러 주세요.
        </div>
      ) : (
        <div className="tx-table-wrap">
          <table className="tx-table loan-table">
            <thead>
              <tr>
                <th scope="col">대출</th>
                <th scope="col">상태</th>
                <th scope="col" className="tx-num">남은 금액</th>
                <th scope="col" className="tx-num">금리</th>
                <th scope="col">상환</th>
                <th scope="col">다음 상환</th>
                <th scope="col">만기</th>
                <th scope="col">예보 반영</th>
              </tr>
            </thead>
            <tbody>
              {sorted.map((loan) => (
                <LoanRow key={loan.loanId} loan={loan} />
              ))}
            </tbody>
          </table>
        </div>
      )}
      {sorted.some((l) => l.repaymentCycle === 'DAILY') && (
        <p className="v7-note v7-note--small">
          매일 빠지는 금액은 대표값입니다. 끝자리를 내림해 마지막 회차는 금액이 다를 수 있습니다.
        </p>
      )}
    </Section>
  );
}

function LoanRow({ loan }: { loan: LoanItem }) {
  const status = STATUS[loan.status];
  const repay = repaymentLabel(loan);
  // 남은 금액(balance)은 원금+이자다. 같은 기준인 총상환액과 견준다 — 원금과 견주면 이자만큼
  // 남은 금액이 원금보다 커 「원금 100만원 중 110만원 남음」이 된다(실데이터로 확인)
  const whole = loan.totalRepayable ?? loan.principal;
  const left = loan.balance;
  const paidRatio = whole && left !== undefined ? Math.min(1, Math.max(0, 1 - left / whole)) : null;

  return (
    <tr data-closed={loan.status === 'CLOSED' || undefined}>
      <td data-label="대출">
        <strong className="loan-name">{loan.productName}</strong>
        <small>{loan.lenderName}</small>
      </td>
      <td data-label="상태">
        <span className="loan-state" data-status={loan.status}>
          <Icon name={status.icon} size={13} />
          {status.label}
          {loan.status === 'OVERDUE' && loan.overdueDays ? ` ${loan.overdueDays}일` : ''}
        </span>
      </td>
      <td data-label="남은 금액" className="tx-num">
        {formatMoney(left)}
        {whole !== undefined && (
          <small>
            {loan.totalRepayable !== undefined ? '총상환액' : '원금'} {formatMoney(whole)} 중
          </small>
        )}
        {paidRatio !== null && (
          <span
            className="loan-progress"
            role="img"
            aria-label={`${Math.round(paidRatio * 100)}% 갚음`}
            style={{ '--paid': paidRatio } as CSSProperties}
          />
        )}
      </td>
      <td data-label="금리" className="tx-num">{ratePercent(loan.interestRate)}</td>
      {/* 다 갚은 대출에 「매일 ○원」을 남기면 아직 빠지는 돈으로 읽힌다 */}
      <td data-label="상환">
        {loan.status === 'CLOSED' ? (
          '—'
        ) : (
          <>
            {repay.main}
            {repay.sub && <small>{repay.sub}</small>}
          </>
        )}
      </td>
      <td data-label="다음 상환">{dayLabel(loan.nextPaymentDate)}</td>
      <td data-label="만기">{dayLabel(loan.maturityDate)}</td>
      <td data-label="예보 반영">
        {loan.status === 'CLOSED' ? (
          '—'
        ) : loan.affectsForecast ? (
          <span className="loan-forecast">
            <Icon name="check" size={13} />
            반영됨
          </span>
        ) : (
          <span className="loan-forecast" data-off>
            <Icon name="warning" size={13} />
            반영 안 됨
            {/* 문구가 서버에 있다 — 새로 짓지 않는다 */}
            {loan.forecastNote && <small>{loan.forecastNote}</small>}
          </span>
        )}
      </td>
    </tr>
  );
}

// --- 16.2 신용등급 --------------------------------------------------------------

function CreditPanel({ businessId }: { businessId: number }) {
  const credit = useQuery({
    queryKey: ['v7', 'credit', businessId],
    queryFn: async () => settle(await getCredit(businessId)),
    ...ONCE,
  });

  const again = (
    <button
      type="button"
      className="v7-secondary-button"
      onClick={() => void credit.refetch()}
      disabled={credit.isFetching}
    >
      <Icon name="refresh" size={15} />
      {credit.isFetching ? '조회하는 중…' : '다시 조회'}
    </button>
  );

  if (credit.isPending) {
    return (
      <Section title="신용등급">
        <p className="v7-note">금융망에서 등급을 조회하는 중…</p>
      </Section>
    );
  }
  if (credit.data?.state !== 'ready') {
    return (
      <Section title="신용등급" action={again}>
        {credit.data?.state === 'not-ready' ? (
          <div className="empty-state">신용등급 기능이 아직 준비되지 않았습니다.</div>
        ) : (
          <p className="v7-inline-error" role="alert">
            ⚠ 등급을 조회하지 못했습니다
            {credit.data?.state === 'error' ? ` — ${credit.data.message}` : ''}
          </p>
        )}
      </Section>
    );
  }

  const c = credit.data.data;
  const floor = new Map(c.gradeCriteria.map((g) => [g.grade, g.minAssetValue]));

  return (
    <Section title="신용등급" action={again}>
      <div className="v7-cols">
        <div>
          <p className="v7-figure">참고 등급 · {formatDateTime(c.baseTime)} 기준</p>
          <ol className="loan-grades" aria-label={`등급 ${c.gradeScale.join(', ')} 중 현재 ${c.currentGrade}`}>
            {c.gradeScale.map((grade) => {
              const now = grade === c.currentGrade;
              const min = floor.get(grade);
              return (
                <li key={grade} aria-current={now || undefined}>
                  <b>{grade}</b>
                  <small>{now ? '지금' : min !== undefined ? `${formatManwon(min)} 이상` : ''}</small>
                </li>
              );
            })}
          </ol>
          <p className="v7-note v7-note--small">{c.gradeBasis}</p>
          <dl className="v7-facts">
            <div>
              <dt>수시입출금</dt>
              <dd>{formatMoney(c.assets.demandDeposit)}</dd>
            </div>
            <div>
              <dt>예·적금</dt>
              <dd>{formatMoney(c.assets.depositSavings)}</dd>
            </div>
            <div>
              <dt>합계</dt>
              <dd>{formatMoney(c.assets.total)}</dd>
            </div>
          </dl>
        </div>

        <div>
          <h3 className="v7-subhead">앞으로의 등급</h3>
          {c.projection.length === 0 ? (
            <p className="v7-note v7-note--small">아직 전망할 잔액 곡선이 없습니다.</p>
          ) : (
            <ul className="v7-rows">
              {c.projection.map((p, i) => {
                const drop = c.gradeScale.indexOf(p.estimatedGrade) > c.gradeScale.indexOf(c.currentGrade);
                return (
                  <li key={p.month}>
                    <span className="t">{monthLabel(p.month, c.projection[i - 1]?.month)}</span>
                    <span className="d">
                      {/* 확정 표현을 쓰지 않는다 — 「○등급」이 아니라 「○등급 가능성」(도메인 규칙 1) */}
                      <b className={drop ? 'is-gap' : undefined}>
                        {p.estimatedGrade}등급 가능성
                        {drop && ' · 지금보다 낮음'}
                      </b>
                      <small>추정 자산 약 {formatManwon(p.estimatedAsset)}</small>
                    </span>
                  </li>
                );
              })}
            </ul>
          )}
          <dl className="v7-facts">
            <div>
              <dt>신뢰 수준</dt>
              <dd>{CONFIDENCE[c.confidence] ?? c.confidence}</dd>
            </div>
            <div>
              <dt>계산 방법</dt>
              <dd className="v7-muted">{c.basis.method}</dd>
            </div>
            {c.basis.assumption && (
              <div>
                <dt>가정</dt>
                <dd className="v7-muted">{c.basis.assumption}</dd>
              </div>
            )}
          </dl>
          {c.warnings.length > 0 && (
            <ul className="v7-warnings">
              {c.warnings.map((w) => (
                <li key={w}>
                  <Icon name="info" size={14} />
                  {w}
                </li>
              ))}
            </ul>
          )}
        </div>
      </div>
      <p className="v7-note v7-note--small">{c.disclaimer}</p>
    </Section>
  );
}

// --- 16.3 대출 상품 추천 --------------------------------------------------------

const amountSchema = z.object({
  amount: z
    .string()
    .min(1, '금액을 입력해 주세요.')
    .refine((v) => !Number.isNaN(parseAmount(v)), '숫자만 입력해 주세요.')
    .refine((v) => parseAmount(v) > 0, '0원보다 커야 합니다.'),
});
type AmountForm = z.infer<typeof amountSchema>;

function ProductFinder({ businessId }: { businessId: number }) {
  const [amount, setAmount] = useState<number | null>(null);
  const {
    register,
    handleSubmit,
    formState: { errors },
  } = useForm<AmountForm>({ resolver: zodResolver(amountSchema), mode: 'onTouched' });

  const products = useQuery({
    queryKey: ['v7', 'loan-products', businessId, amount],
    enabled: amount !== null,
    queryFn: async () => settle(await listLoanProducts(businessId, { amount: amount! })),
    ...ONCE,
  });

  return (
    <Section title="새로 대출을 받는다면">
      <form className="loan-ask" onSubmit={handleSubmit((v) => setAmount(parseAmount(v.amount)))} noValidate>
        <Field label="필요한 금액" error={errors.amount?.message} hint="이 금액을 빌렸을 때 매일 얼마가 빠지는지 계산합니다">
          {/* 버튼을 입력칸과 한 줄에 둔다. 칸 밖에 두고 여백으로 맞추면 라벨 높이가 바뀔 때마다 어긋난다 */}
          <span className="loan-ask__row">
            <input type="text" inputMode="numeric" placeholder="3,000,000" {...register('amount')} />
            <button type="submit" className="auth-submit" disabled={products.isFetching}>
              {products.isFetching ? '찾는 중…' : '상품 찾기'}
            </button>
          </span>
        </Field>
      </form>

      {amount === null ? null : products.isPending ? (
        <p className="v7-note">상품을 찾는 중…</p>
      ) : products.data?.state === 'not-ready' ? (
        <div className="empty-state">대출 상품 추천 기능이 아직 준비되지 않았습니다.</div>
      ) : products.data?.state === 'error' ? (
        <FormError message={`상품을 불러오지 못했습니다 — ${products.data.message}`} />
      ) : products.data?.state === 'ready' ? (
        <ProductTable data={products.data.data} />
      ) : null}

      <p className="v7-note v7-note--small">
        추천은 참고용입니다. 실제 대출은 금융기관에서 직접 신청하셔야 하며, 심사 결과에 따라 조건이 달라질 수 있습니다.
      </p>
    </Section>
  );
}

function ProductTable({ data }: { data: LoanProductRecommendation }) {
  if (data.items.length === 0) {
    // 기본값이 빈 목록이다 — 남이 시험 삼아 만든 상품을 내밀지 않으려고 서버가 걸러 둔다(16_loan.ts)
    return (
      <div className="empty-state">
        {formatMoney(data.requestedAmount)}에 맞춰 보여 드릴 대출 상품이 아직 없습니다.
      </div>
    );
  }

  const pick = data.recommendation;
  const picked = pick ? data.items.find((p) => p.productId === pick.productId) : undefined;

  return (
    <>
      {pick && picked && (
        <div className="v7-note v7-note--good loan-pick">
          <Icon name="sparkle" size={18} />
          <div>
            <strong>
              1순위 · {picked.bankName ?? ''} {picked.name ?? ''}
            </strong>
            <span>{pick.message}</span>
          </div>
        </div>
      )}
      {!pick && (
        <div className="v7-note v7-note--wait">
          <Icon name="info" size={18} />
          <div>
            <strong>지금 등급({data.currentGrade})으로 신청할 수 있는 상품이 없습니다</strong>
            <span>아래 상품은 등급이 오르면 신청할 수 있습니다.</span>
          </div>
        </div>
      )}

      <div className="tx-table-wrap">
        <table className="tx-table loan-table">
          <caption className="loan-caption">
            {formatMoney(data.requestedAmount)}을 빌린다면 · 순서는 금리가 아니라 갚을 수 있는가 기준
          </caption>
          <thead>
            <tr>
              <th scope="col">상품</th>
              <th scope="col" className="tx-num">하루 상환액</th>
              <th scope="col" className="tx-num">기간</th>
              <th scope="col" className="tx-num">금리</th>
              <th scope="col" className="tx-num">총상환액</th>
              <th scope="col">갚는 동안 잔액</th>
              <th scope="col">신청</th>
            </tr>
          </thead>
          <tbody>
            {data.items.map((p) => (
              <ProductRow key={p.productId} product={p} picked={p.productId === pick?.productId} />
            ))}
          </tbody>
        </table>
      </div>
      <p className="v7-note v7-note--small">{data.disclaimer}</p>
    </>
  );
}

function ProductRow({ product: p, picked }: { product: LoanProductItem; picked: boolean }) {
  // 곡선이 없으면 gapDaysAfter 가 0 이다 — 「공백이 없다」가 아니다. 최저 잔액이 없으면 곡선이 없는 것이다
  const measured = p.worstBalanceAfter !== undefined;
  return (
    <tr data-picked={picked || undefined} data-closed={!p.isEligibleNow || undefined}>
      <td data-label="상품">
        <strong className="loan-name">
          {picked && <span className="loan-badge">1순위</span>}
          {p.name ?? '이름 없는 상품'}
        </strong>
        <small>
          {[p.bankName, p.loanTypeName].filter(Boolean).join(' · ')}
        </small>
      </td>
      <td data-label="하루 상환액" className="tx-num">
        <b>{formatMoney(p.dailyPayment)}</b>
        <small>{p.repaymentMethodDisplay}</small>
      </td>
      <td data-label="기간" className="tx-num">{p.loanPeriodDays}일</td>
      <td data-label="금리" className="tx-num">{ratePercent(p.interestRate)}</td>
      <td data-label="총상환액" className="tx-num">
        {formatMoney(p.totalRepayment)}
        {p.totalInterest !== undefined && <small>이자 {formatMoney(p.totalInterest)}</small>}
      </td>
      <td data-label="갚는 동안 잔액">
        {measured ? (
          <>
            <span className={p.worstBalanceAfter! < 0 ? 'is-gap' : undefined}>
              최저 약 {formatManwon(p.worstBalanceAfter)}
            </span>
            <small>
              {p.gapDaysAfter > 0 ? `안전선 아래 ${p.gapDaysAfter}일 가능성` : '안전선 아래로 내려가지 않을 전망'}
            </small>
          </>
        ) : (
          <small>잔액 예보가 없어 계산하지 못했습니다</small>
        )}
      </td>
      <td data-label="신청">
        {p.isEligibleNow ? (
          <span className="loan-forecast">
            <Icon name="check" size={13} />
            가능
          </span>
        ) : (
          <span className="loan-forecast" data-off>
            <Icon name="close" size={13} />
            지금은 불가
            <small>{p.eligibilityNote ?? (p.minGrade ? `${p.minGrade}등급 이상` : '')}</small>
          </span>
        )}
      </td>
    </tr>
  );
}
