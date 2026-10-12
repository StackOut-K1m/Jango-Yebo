import { useEffect, useState } from 'react';
import type { ReactNode } from 'react';
import { Link, useNavigate, useParams } from 'react-router';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import {
  createTaxDraft,
  deleteTaxDraft,
  exportTaxDraft,
  formatDateTime,
  formatMoney,
  getJob,
  getTaxDraft,
  getTaxDraftBasis,
  isJobTerminal,
  listTaxDrafts,
  listTaxFilingForms,
  patchTaxDraft,
} from '@jangoyebo/core';
import type {
  ApiResult,
  TaxDraftDetail,
  TaxDraftField,
  TaxFilingForm,
  TaxFormType,
  TaxReviewStatus,
} from '@jangoyebo/core';
import { useBusiness } from '@/business/useBusiness';
import { PageShell, Section } from './PageShell';
import { Icon } from './icons';
import { money } from './model';
import { saveFile } from './saveFile';
import { todayIso } from './supportView';
import {
  BOOKKEEPING_CHOICES,
  FILER_CHOICES,
  DRAFT_STATUS,
  FIELD_SOURCE,
  FIELD_STATUS,
  SUPPORT_LEVEL,
  TAX_REVIEW,
  canDraftForm,
  emptyFieldGuide,
  markedExclusion,
  splitDrafts,
  fieldValueLabel,
  hasBasis,
  isEditable,
  needsFieldAttention,
  parseWon,
  sensitiveFields,
  yearPeriod,
  FILL_KIND,
  fieldFillKind,
  readFiler,
  writeFiler,
  OPENING_INVENTORY,
  closingInventoryOf,
} from './taxView';
import type { BookkeepingChoice, Filer } from './taxView';
import { AccountantHandoff, DoubleEntrySelfGuide, DutyQuiz } from './TaxDutyGuide';
import { TaxFormPrint } from './TaxFormPrint';
import './tax.css';

/**
 * 종합소득세 신고서 초안 (SC-07-8, API 14.3, P0-12).
 *
 * 세액과 신고 유형은 서비스가 정하지 않는다(도메인 규칙 4). 이 화면은 거래·증빙·정산에서 모은
 * 금액을 서식 칸에 **후보로** 채우고, 무엇을 근거로 했는지 보여 주고, 홈택스에 옮겨 적을 파일을 준다.
 * 숫자는 서버가 계산한다 — 사용자가 값을 고치면 산식은 서버가 다시 푼다(도메인 규칙 2).
 */

const POLL_MS = 1000;
const TIMEOUT_MS = 90_000;
const DRAFTABLE_KEY = ['v7', 'tax-drafts'] as const;

function Badge({ icon, tone, children }: { icon: string; tone?: string; children: ReactNode }) {
  return (
    <span className="tax-badge" data-tone={tone}>
      <Icon name={icon} size={13} />
      {children}
    </span>
  );
}

function errorOf<T>(res: ApiResult<T> | undefined): string | null {
  if (!res) return null;
  if (!res.ready) return '이 기능은 아직 준비되지 않았습니다.';
  return res.ok ? null : res.error.message;
}

/**
 * 작년 초안에 적힌 기말재고액. 올해 기초재고액의 후보가 된다(서식 작성방법 4).
 *
 * `enabled` 로 <b>정말 필요할 때만</b> 돈다 — 올해 기초재고가 비어 있을 때뿐이다.
 * 목록·상세 두 번 왕복이라 아무 때나 부를 것이 아니다.
 */
function usePriorClosingInventory(businessId: number | undefined, taxYear: number | undefined, enabled: boolean) {
  const priorYear = taxYear === undefined ? undefined : taxYear - 1;
  const list = useQuery({
    queryKey: [...DRAFTABLE_KEY, businessId, priorYear],
    enabled: enabled && businessId !== undefined && priorYear !== undefined,
    queryFn: () => listTaxDrafts(businessId!, priorYear!),
  });
  const items = list.data?.ready && list.data.ok ? list.data.data.items : [];
  // 여러 스냅샷이 있으면 완성된 것 중 마지막 것을 본다
  const source = [...items].reverse().find((d) => d.status === 'READY');

  const detail = useQuery({
    queryKey: ['v7', 'tax-draft', source?.draftId],
    enabled: source !== undefined,
    queryFn: () => getTaxDraft(source!.draftId),
  });
  const draft = detail.data?.ready && detail.data.ok ? detail.data.data : undefined;
  const value = closingInventoryOf(draft);

  return value === undefined || priorYear === undefined ? undefined : { priorYear, value };
}

function useForms(taxYear: number | undefined) {
  return useQuery({
    queryKey: ['v7', 'tax-forms', taxYear],
    enabled: taxYear !== undefined,
    staleTime: Infinity,
    queryFn: () => listTaxFilingForms(taxYear!),
  });
}

// --- 목록 ------------------------------------------------------------------------

export function TaxDraftListScreen() {
  const { current } = useBusiness();
  const businessId = current?.businessId;
  const navigate = useNavigate();
  const queryClient = useQueryClient();
  const [today] = useState(() => todayIso());
  const thisYear = Number(today.slice(0, 4));
  const [year, setYear] = useState(thisYear);
  const [duty, setDuty] = useState<BookkeepingChoice | null>(null);
  /** 기억해 둔 신고 방식으로 시작한다 — 매번 다시 고르게 하지 않는다 */
  const [filer, setFilerState] = useState<Filer | null>(() => readFiler(current?.businessId));
  const setFiler = (next: Filer | null) => {
    setFilerState(next);
    writeFiler(current?.businessId, next);
  };
  const [showOlder, setShowOlder] = useState(false);
  const [picked, setPicked] = useState<TaxFormType | null>(null);
  const [periodStart, setPeriodStart] = useState(yearPeriod(thisYear, today).from);
  const [periodEnd, setPeriodEnd] = useState(`${thisYear}-12-31`);
  const [nts, setNts] = useState('');
  const [industryCode, setIndustryCode] = useState('');
  const [jobId, setJobId] = useState<number | null>(null);
  const [pendingDraft, setPendingDraft] = useState<number | null>(null);
  const [deadlinePassed, setDeadlinePassed] = useState(false);

  const forms = useForms(year);
  const drafts = useQuery({
    queryKey: [...DRAFTABLE_KEY, businessId, year],
    enabled: businessId !== undefined,
    queryFn: () => listTaxDrafts(businessId!, year),
  });

  function chooseYear(next: number) {
    setYear(next);
    setPicked(null);
    setPeriodStart(`${next}-01-01`);
    setPeriodEnd(`${next}-12-31`);
  }

  const ntsValue = parseWon(nts);
  const create = useMutation({
    mutationFn: () =>
      createTaxDraft(businessId!, {
        taxYear: year,
        formType: chosen!,
        periodStart,
        periodEnd,
        // 서식 고르기는 간편장부를 고른 뒤에만 열린다 — 기장의무는 늘 함께 보낸다
        hometaxReference: {
          bookkeepingDuty: 'SIMPLE_LEDGER',
          ...(ntsValue !== null && !Number.isNaN(ntsValue) ? { ntsRevenue: ntsValue } : {}),
          ...(industryCode.trim() ? { industryCode: industryCode.trim() } : {}),
        },
      }),
    onSuccess: (res) => {
      if (!res.ready || !res.ok) return;
      setDeadlinePassed(false);
      setPendingDraft(res.data.draftId);
      if (res.data.status === 'PROCESSING') setJobId(res.data.jobId);
      else navigate(`/tax/drafts/${res.data.draftId}`);
    },
  });

  const job = useQuery({
    queryKey: ['job', jobId],
    enabled: jobId !== null,
    queryFn: async () => {
      const res = await getJob(jobId!);
      return res.ready && res.ok ? res.data : null;
    },
    refetchInterval: (q) => (isJobTerminal(q.state.data?.status) || deadlinePassed ? false : POLL_MS),
  });
  const jobStatus = job.data?.status;
  useEffect(() => {
    if (jobId === null) return;
    const timer = setTimeout(() => setDeadlinePassed(true), TIMEOUT_MS);
    return () => clearTimeout(timer);
  }, [jobId]);
  useEffect(() => {
    if (!isJobTerminal(jobStatus)) return;
    queryClient.invalidateQueries({ queryKey: DRAFTABLE_KEY });
    if (jobStatus === 'COMPLETED' && pendingDraft !== null) navigate(`/tax/drafts/${pendingDraft}`);
  }, [jobStatus, pendingDraft, navigate, queryClient]);

  const running = jobId !== null && !isJobTerminal(jobStatus) && !deadlinePassed;
  const formList = forms.data?.ready && forms.data.ok ? forms.data.data.items : [];
  // 복식부기는 장부 유형에서 이미 갈렸다 — 간편장부 쪽 서식 목록에 다시 두지 않는다
  const ledgerForms = formList.filter((f) => f.formType !== 'DOUBLE_ENTRY');
  const draftList = drafts.data?.ready && drafts.data.ok ? drafts.data.data.items : [];
  const { latest, older } = splitDrafts(draftList);

  /** 실제로 초안을 만들 수 있는 서식. 하나뿐이면 고르는 단계를 없앤다 */
  const draftable = ledgerForms.filter(canDraftForm);
  const onlyForm = draftable.length === 1 ? draftable[0] : undefined;
  /** 고를 게 하나뿐이면 고른 것으로 친다 — 사람이 한 번 더 누를 이유가 없다 */
  const chosen = picked ?? onlyForm?.formType;

  const remove = useMutation({
    mutationFn: (draftId: number) => deleteTaxDraft(draftId),
    onSuccess: (res) => {
      if (res.ready && res.ok) queryClient.invalidateQueries({ queryKey: DRAFTABLE_KEY });
    },
  });
  /** 실제로 지운다 — 최신 초안이면 사장님이 적은 값도 사라지고 다음 초안은 남은 것에서 물려받는다 */
  function confirmRemove(d: (typeof draftList)[number], isLatest: boolean) {
    const hasOlder = older.some((o) => o.formType === d.formType);
    const carry = !isLatest
      ? ''
      : hasOlder
        ? ' 이 초안에 직접 적은 값도 함께 지워지고, 다음에 만드는 초안은 남은 이전 초안의 값을 이어받습니다.'
        : ' 이 초안에 직접 적은 값도 함께 지워져 다음 초안으로 이어지지 않습니다.';
    if (window.confirm(`이 초안을 삭제할까요? 되돌릴 수 없습니다.${carry}`)) remove.mutate(d.draftId);
  }
  const formName = (type: TaxFormType) => formList.find((f) => f.formType === type)?.name ?? type;
  const periodInvalid = periodStart > periodEnd || !periodStart.startsWith(String(year)) || !periodEnd.startsWith(String(year));
  const isFood = current?.industryType.startsWith('FOOD_') ?? false;

  return (
    <PageShell
      title="신고서 초안"
      lede={
        <>
          검토한 거래와 증빙을 종합소득세 서식 칸에 모아 초안을 만듭니다. 세액은 계산하지 않고 소득금액까지만 채웁니다.
          <br />
          신고 유형과 최종 금액은 사장님 또는 세무전문가가 확인해 주세요.
        </>
      }
      actions={
        <Link to="/tax/review" className="v7-secondary-button">
          <Icon name="check" size={15} /> 신고자료 검토
        </Link>
      }
    >
      <Section title="신고 유형 고르기">
        <div className="tax-toolbar">
          <label>
            귀속연도
            <select value={year} onChange={(e) => chooseYear(Number(e.target.value))}>
              {[thisYear, thisYear - 1].map((y) => (
                <option key={y} value={y}>
                  {y}년
                </option>
              ))}
            </select>
          </label>
          <div className="tax-toolbar__group">
            장부 유형
            <div className="v7-chips" role="group" aria-label="장부 유형">
              {BOOKKEEPING_CHOICES.map((c) => (
                <button
                  key={c.id}
                  type="button"
                  aria-pressed={duty === c.id}
                  onClick={() => {
                    setDuty(c.id);
                    setPicked(null);
                  }}
                >
                  {c.label}
                </button>
              ))}
            </div>
          </div>
          {(duty === 'SIMPLE_LEDGER' || duty === 'DOUBLE_ENTRY') && (
            <div className="tax-toolbar__group">
              신고 방식
              <div className="v7-chips" role="group" aria-label="신고 방식">
                {FILER_CHOICES.map((c) => (
                  <button key={c.id} type="button" aria-pressed={filer === c.id} onClick={() => setFiler(c.id)}>
                    {c.label}
                  </button>
                ))}
              </div>
            </div>
          )}
        </div>
        <p className="v7-note v7-note--small">
          장부 유형과 신고 방식(직접 · 회계사)에 따라 필요한 자료가 다릅니다. 서비스는 유형을 정하지 않으니 신고안내문에 적힌 유형을 골라
          주세요. 모르면 「모르겠어요」에서 알아볼 수 있습니다.
          {isFood && (
            <>
              <br />
              음식점·카페·베이커리는 작년 매출이 1억 5천만원 미만이거나 올해 개업했다면 보통 간편장부입니다.
            </>
          )}
        </p>
      </Section>

      {duty === 'UNKNOWN' && (
        <DutyQuiz
          industry={current?.industryType}
          onPick={(next) => {
            setDuty(next);
            setPicked(null);
          }}
        />
      )}

      {(duty === 'SIMPLE_LEDGER' || duty === 'DOUBLE_ENTRY') && filer === null && (
        <p className="v7-note v7-note--small">신고를 직접 하시는지, 회계사에게 맡기시는지 골라 주세요. 필요한 자료가 달라집니다.</p>
      )}
      {/* 「홈택스 직접 신고」 안내 상자는 걷어냈다. 「아래에서 초안을 먼저 만들어 주세요」
        * 한 줄을 위해 구획 하나를 쓰는데, 바로 아래 「만들 자료」가 같은 말을 단추로 한다.
        * 초안이 생기면 상세 화면이 옮겨 적기까지 이어 준다. */}
      {duty === 'DOUBLE_ENTRY' && filer === 'SELF' && <DoubleEntrySelfGuide onAccountant={() => setFiler('ACCOUNTANT')} />}
      {(duty === 'SIMPLE_LEDGER' || duty === 'DOUBLE_ENTRY') && filer === 'ACCOUNTANT' && (
        <AccountantHandoff hasDraft={duty === 'SIMPLE_LEDGER'} businessId={businessId} year={year} />
      )}

      {duty === 'SIMPLE_LEDGER' && filer !== null && (
        <Section title="서식 고르기">
          {/* ⚠ 못 쓰는 서식도 목록에 남긴다. 서버가 굳이 내려보내는 이유가 있다 —
            * 「지원하지 않는 것」과 「빠뜨린 것」을 가르려는 것이다(types/14_taxdraft.ts).
            * 목록을 접으면 추계로 신고해야 하는 사람은 자기 경로가 「준비 중」인지
            * 아예 없는지 알 길이 없다.
            *
            * 대신 <b>고를 수 있는 것이 하나뿐이면 미리 골라 둔다</b>(`chosen`) —
            * 정보는 남기고 한 번 더 누르는 수고만 던다. */}
          <p className="v7-note v7-note--small">
            간편장부 대상자는 간편장부소득금액계산서로 신고합니다. 장부 없이 추계로 신고한다면 경비율 서식을 고르세요.
          </p>

          {forms.isPending ? (
            <p className="v7-note">불러오는 중…</p>
          ) : errorOf(forms.data) ? (
            <p className="v7-inline-error" role="alert">
              ⚠ 서식을 불러오지 못했습니다 — {errorOf(forms.data)}
            </p>
          ) : ledgerForms.length === 0 ? (
            <div className="empty-state">{year}년 서식이 아직 준비되지 않았습니다.</div>
          ) : (
            <ul className="tax-forms">
              {ledgerForms.map((form) => {
                const level = SUPPORT_LEVEL[form.supportLevel];
                const can = canDraftForm(form);
                return (
                  <li key={form.formType} data-picked={chosen === form.formType || undefined} data-off={!can || undefined}>
                    <button
                      type="button"
                      disabled={!can}
                      aria-pressed={chosen === form.formType}
                      onClick={() => setPicked(form.formType)}
                    >
                      <strong>{form.name}</strong>
                      <Badge icon={can ? 'check' : 'info'} tone={can ? 'good' : 'muted'}>
                        {level.canDraft && !can ? '준비 중' : level.label}
                      </Badge>
                      {form.eligibilityNote && <small>{form.eligibilityNote}</small>}
                      {form.officialForm && <small className="v7-muted">{form.officialForm}</small>}
                    </button>
                  </li>
                );
              })}
            </ul>
          )}

          {ledgerForms.some((f) => f.formType === 'SIMPLE_RATE') && (
            <p className="v7-note v7-note--small">
              단순경비율 대상이라면 초안이 필요 없습니다. 홈택스 모두채움 신고서의 수입금액이{' '}
              <Link to="/settlements">정산 대사</Link> 결과와 맞는지만 확인해 주세요.
            </p>
          )}

          {chosen && (
            <form
              className="tax-create"
              onSubmit={(e) => {
                e.preventDefault();
                create.mutate();
              }}
            >
              <label>
                시작일
                <input type="date" value={periodStart} onChange={(e) => setPeriodStart(e.target.value)} />
              </label>
              <label>
                종료일
                <input type="date" value={periodEnd} onChange={(e) => setPeriodEnd(e.target.value)} />
              </label>
              <label>
                국세청 수입금액(선택)
                <input type="text" inputMode="numeric" placeholder="홈택스 신고도움서비스 값" value={nts} onChange={(e) => setNts(e.target.value)} />
              </label>
              <label>
                주업종코드(선택)
                <input type="text" inputMode="numeric" maxLength={6} placeholder="552305" value={industryCode} onChange={(e) => setIndustryCode(e.target.value)} />
              </label>
              <button
                type="submit"
                className="auth-submit"
                disabled={running || create.isPending || periodInvalid || Number.isNaN(ntsValue) || businessId === undefined}
              >
                {running || create.isPending ? '초안을 만드는 중…' : '초안 만들기'}
              </button>
              {periodInvalid && <p className="v7-inline-error">⚠ 기간은 {year}년 안에서 시작일이 종료일보다 앞서야 합니다.</p>}
              {Number.isNaN(ntsValue) && <p className="v7-inline-error">⚠ 국세청 수입금액은 숫자만 적어 주세요.</p>}
              <p className="v7-note v7-note--small">
                참고값은 홈택스에서 옮겨 적는 값이며, 서비스 집계와의 차이를 보여 주는 데만 씁니다.
              </p>
            </form>
          )}
          {errorOf(create.data) && (
            <p className="v7-inline-error" role="alert">
              ⚠ 초안을 만들지 못했습니다 — {errorOf(create.data)}
            </p>
          )}
          {running && (
            <p className="v7-note tax-status-line" role="status">
              <Icon name="clock" size={16} /> 거래와 증빙을 서식 칸에 모으는 중…
            </p>
          )}
          {jobStatus === 'FAILED' && (
            <p className="v7-inline-error" role="alert">
              ⚠ 초안을 만들지 못했습니다{job.data?.error ? ` — ${job.data.error.message}` : ''}
            </p>
          )}
          {jobId !== null && deadlinePassed && !isJobTerminal(jobStatus) && (
            <p className="v7-inline-error" role="alert">
              ⚠ 아직 끝나지 않았습니다. 아래 목록에서 잠시 후 확인해 주세요.
            </p>
          )}
        </Section>
      )}

      <Section title={`${year}년 초안`}>
        {drafts.isPending ? (
          <p className="v7-note">불러오는 중…</p>
        ) : errorOf(drafts.data) ? (
          <p className="v7-inline-error" role="alert">
            ⚠ 초안 목록을 불러오지 못했습니다 — {errorOf(drafts.data)}
          </p>
        ) : draftList.length === 0 ? (
          <div className="empty-state">아직 만든 초안이 없습니다.</div>
        ) : (
          <div className="tx-table-wrap">
            <table className="tx-table">
              <thead>
                <tr>
                  <th scope="col">서식</th>
                  <th scope="col">기간</th>
                  <th scope="col">상태</th>
                  <th scope="col">만든 시각</th>
                  <th scope="col">
                    <span className="sr-only">동작</span>
                  </th>
                </tr>
              </thead>
              <tbody>
                {[...latest, ...(showOlder ? older : [])].map((d) => {
                  const st = DRAFT_STATUS[d.status];
                  const isLatest = latest.includes(d);
                  return (
                    <tr key={d.draftId} data-older={!isLatest || undefined}>
                      <td data-label="서식">
                        <Link to={`/tax/drafts/${d.draftId}`} className="tax-link">
                          {formName(d.formType)}
                        </Link>
                      </td>
                      <td data-label="기간">
                        {d.periodStart} ~ {d.periodEnd}
                      </td>
                      <td data-label="상태">
                        <Badge icon={st.icon} tone={st.tone}>
                          {st.label}
                        </Badge>
                      </td>
                      <td data-label="만든 시각">{d.generatedAt ? formatDateTime(d.generatedAt) : '—'}</td>
                      <td className="tax-actions">
                        <button
                          type="button"
                          className="tax-text-button"
                          // 집계 중에 지우면 작업이 「초안 없음」으로 실패한다(서버 409)
                          disabled={d.status === 'PROCESSING' || remove.isPending}
                          aria-label={`${formatDateTime(d.generatedAt ?? '')} 초안 삭제`}
                          onClick={() => confirmRemove(d, isLatest)}
                        >
                          삭제
                        </button>
                      </td>
                    </tr>
                  );
                })}
              </tbody>
            </table>
          </div>
        )}
        {older.length > 0 && (
          <button type="button" className="tax-text-button tax-older-toggle" aria-expanded={showOlder} onClick={() => setShowOlder(!showOlder)}>
            {showOlder ? '이전 초안 접기' : `이전 초안 ${older.length}개 보기`}
          </button>
        )}
        {errorOf(remove.data) && (
          <p className="v7-inline-error" role="alert">
            ⚠ 초안을 삭제하지 못했습니다 — {errorOf(remove.data)}
          </p>
        )}
      </Section>
    </PageShell>
  );
}

// --- 상세 ------------------------------------------------------------------------

export function TaxDraftDetailScreen() {
  const { draftId: raw } = useParams();
  const draftId = Number(raw);
  const valid = Number.isInteger(draftId) && draftId > 0;
  const { current } = useBusiness();
  const businessId = current?.businessId;
  const queryClient = useQueryClient();
  const navigate = useNavigate();
  /**
   * 「직접 신고」를 고른 사람은 <b>전체 서식</b>으로 연다 — 홈택스 칸을 하나씩 옮겨 적으려면
   * 확인할 항목만이 아니라 서식 전체가 필요하다. 회계사에게 맡기는 사람은 채울 것만 보면 된다.
   */
  const [showAll, setShowAll] = useState(() => readFiler(businessId) === 'SELF');

  const detail = useQuery({
    queryKey: ['v7', 'tax-draft', draftId],
    enabled: valid,
    queryFn: () => getTaxDraft(draftId),
    // 집계 중이면 끝날 때까지 다시 본다
    refetchInterval: (q) => {
      const r = q.state.data;
      return r?.ready && r.ok && r.data.status === 'PROCESSING' ? POLL_MS * 2 : false;
    },
  });
  const r = detail.data;
  const d = r?.ready && r.ok ? r.data : null;

  /**
   * 올해 기초재고액이 비어 있을 때만 작년 기말재고액을 찾아본다.
   *
   * ⚠ <b>조기 반환보다 위에 있어야 한다.</b> 아래에 두었더니 초안이 캐시에 없는 첫 렌더
   * (로딩)에서는 훅이 안 불리고, 도착한 뒤 렌더에서는 불려서 훅 개수가 달라졌다 —
   * 「Rendered more hooks than during the previous render」로 화면이 통째로 죽는다.
   * 같은 화면 안에서 이동할 때는 캐시가 있어 안 드러나고, 새로고침해야 터진다.
   *
   * 초안이 아직 없어도 호출은 하고, 실제 요청은 `enabled` 가 막는다.
   */
  const openingEmpty =
    d?.sections
      .flatMap((sec) => sec.fields)
      .some((f) => f.fieldKey === OPENING_INVENTORY && f.status === 'EMPTY') ?? false;
  const priorInventory = usePriorClosingInventory(businessId, d?.taxYear, openingEmpty);
  const forms = useForms(d?.taxYear);
  const form = forms.data?.ready && forms.data.ok ? forms.data.data.items.find((f) => f.formType === d?.formType) : undefined;

  /** PATCH 응답이 재계산된 상세 전체라 그대로 캐시에 넣는다 — 한 번 더 묻지 않는다 */
  const patch = useMutation({
    mutationFn: (body: Parameters<typeof patchTaxDraft>[1]) => patchTaxDraft(draftId, body),
    onSuccess: (res) => {
      if (res.ready && res.ok) queryClient.setQueryData(['v7', 'tax-draft', draftId], res);
    },
  });

  const regenerate = useMutation({
    mutationFn: (source: TaxDraftDetail) =>
      createTaxDraft(businessId!, {
        taxYear: source.taxYear,
        formType: source.formType,
        periodStart: source.periodStart,
        periodEnd: source.periodEnd,
        hometaxReference: source.hometaxReference,
      }),
    onSuccess: (res) => {
      if (!res.ready || !res.ok) return;
      queryClient.invalidateQueries({ queryKey: DRAFTABLE_KEY });
      navigate(`/tax/drafts/${res.data.draftId}`);
    },
  });

  const back = (
    <Link to="/tax/drafts" className="v7-secondary-button">
      초안 목록
    </Link>
  );

  if (!valid) {
    return (
      <PageShell title="신고서 초안" actions={back}>
        <div className="empty-state">초안 주소가 올바르지 않습니다.</div>
      </PageShell>
    );
  }
  if (detail.isPending) {
    return (
      <PageShell title="신고서 초안" actions={back}>
        <p className="v7-note">불러오는 중…</p>
      </PageShell>
    );
  }
  if (!d) {
    return (
      <PageShell title="신고서 초안" actions={back}>
        <p className="v7-inline-error" role="alert">
          ⚠ 초안을 불러오지 못했습니다 — {errorOf(r)}
        </p>
      </PageShell>
    );
  }

  const st = DRAFT_STATUS[d.status];
  const patchError = errorOf(patch.data) || (patch.error ? '연결을 확인한 뒤 다시 저장해 주세요.' : null);
  const attentionCount = d.sections.flatMap((section) => section.fields).filter(needsFieldAttention).length;
  const visibleSections = d.sections
    .map((section) => ({ ...section, fields: showAll ? section.fields : section.fields.filter(needsFieldAttention) }))
    .filter((section) => section.fields.length > 0);
  const canEdit = d.status === 'READY';
  /**
   * 「직접 신고」를 고른 사람의 다음 행동은 파일 저장이 아니라 <b>홈택스에 옮겨 적기</b>다.
   * 간편장부에서만 따라하기를 열어 두므로(별지 제74호서식 칸 배치를 전제한다) 서식도 함께 본다.
   */
  const selfFiling = readFiler(businessId) === 'SELF' && d.formType === 'SIMPLE_LEDGER';
  const hometaxPath = `/tax/drafts/${d.draftId}/hometax`;
  /**
   * 옮겨 적기를 열 수 있나. 서식과 상태만 본다 — 누가 골랐는지도, 빈 칸이 남았는지도 묻지 않는다.
   *
   * 한때 `attentionCount === 0` 을 걸어 두었다. 풀었다 — <b>값이 있는 칸이 스무 개가 넘고
   * 빈 칸은 여덟이다.</b> 스무 개를 옮기는 동안 막아 둘 이유가 없고, 홈택스 화면을 띄워 놓고
   * 한 칸씩 옮기다 빈 칸을 만나면 그 자리에서 채우는 것이 실제 작업 방식에 가깝다.
   * 덜 찬 것은 도착 화면의 `filingIssues()` 가 맨 위에서 말해 준다.
   *
   * `STALE` 은 그대로 막는다. 빈 칸과 달리 <b>이미 적힌 숫자가 틀린</b> 것이라 성격이 다르다.
   */
  const canHometax = d.formType === 'SIMPLE_LEDGER' && d.status !== 'STALE';
  const marked = markedExclusion(d.excluded);

  return (
    <PageShell
      title={`${d.taxYear}년 신고서 초안 확인`}
      lede={`${d.periodStart} ~ ${d.periodEnd}의 자료입니다. 빠진 내용을 확인하고, 금액을 검토한 뒤 파일로 저장하세요.`}
      actions={back}
    >
      <nav className="tax-journey" aria-label="초안 확인 순서">
        <a href="#draft-data">1. 포함된 자료 확인</a>
        <a href="#draft-fields">2. 확인할 항목 {attentionCount}개</a>
        <a href="#draft-export">3. 파일로 저장</a>
        {/* 4 단계를 여기 두지 않는다. 1~3 은 이 화면 안에서 뛰는 앵커인데 4 만 다른 화면으로
          * 나간다 — 한 줄에 번호로 묶어 두면 같은 종류로 읽히고, 이미 「다음 행동」이
          * 보내 주는 곳을 한 번 더 누르게 만든다. 아래 블록이 그 일을 맡는다. */}
      </nav>
      {(d.status === 'READY' || d.status === 'STALE') && (
        <section className="tax-next" aria-labelledby="draft-next-title">
          <div>
            <h2 id="draft-next-title">{d.status === 'STALE' ? '자료가 바뀌었어요. 초안을 먼저 다시 만들어 주세요.' : attentionCount ? `확인할 항목이 ${attentionCount}개 있어요` : '표시된 항목 확인을 마쳤어요'}</h2>
            <p>{attentionCount
              ? '아래 항목에서 확인 이유를 읽고, 필요한 금액을 입력하거나 근거를 살펴보세요. 모르는 값은 비워 두고 세무전문가와 확인해도 됩니다.'
              : selfFiling
                ? '옮겨 적기 화면에서 홈택스 입력 순서대로 칸과 값을 보여 드립니다. 항목 확인은 신고 완료를 뜻하지 않습니다.'
                : '전체 서식과 제외된 거래를 살펴본 뒤 파일로 저장하세요. 항목 확인은 신고 완료를 뜻하지 않습니다.'}</p>
            <p className="tax-next__meta">신고자료 검토에서 표시한 거래는 금액에서 빠지고 「금액에 넣지 않은 거래」에 따로 모입니다.</p>
          </div>
          {/* 옮겨 적기가 여기 하나뿐인 입구다(순서줄에서 4 를 뺐다). 그래서 <b>직접 신고를
            * 고른 사람만</b> 보내면 안 된다 — 회계사로 골라 둔 사람도, 아무것도 안 고른
            * 사람도 이 화면에 온다. 간편장부이고 옮겨 적을 값이 준비됐으면 누구에게나 연다.
            *
            * 확인할 항목이 남아 있으면 그쪽이 먼저다 — 빈 칸을 들고 홈택스로 가면
            * 옮겨 적을 값이 없다. STALE 은 숫자가 낡았다. */}
          {canHometax ? (
            <div className="tax-next__acts">
              {/* 빈 칸이 남았으면 채우는 쪽을 주 단추로 둔다 — 열어 두되 권하지는 않는다 */}
              {attentionCount ? (
                <>
                  <a className="v7-secondary-button" href="#draft-fields">
                    확인할 항목 보기 <Icon name="arrow" size={15} />
                  </a>
                  <Link className="tax-text-button" to={hometaxPath}>
                    먼저 홈택스에 옮겨 적기
                  </Link>
                </>
              ) : (
                <>
                  <Link className="v7-secondary-button" to={hometaxPath}>
                    홈택스에 옮겨 적기 <Icon name="arrow" size={15} />
                  </Link>
                  {/* 파일 저장은 여기서 부차적이다. 회계사에게 넘기는 사람은 이쪽을 쓴다 */}
                  <a className="tax-text-button" href="#draft-export">
                    파일로 저장하기
                  </a>
                </>
              )}
            </div>
          ) : (
            <a className="v7-secondary-button" href={d.status === 'STALE' ? '#draft-refresh' : attentionCount ? '#draft-fields' : '#draft-export'}>
              {d.status === 'STALE' ? '다시 만들기로 이동' : attentionCount ? '확인할 항목 보기' : '파일 저장으로 이동'} <Icon name="arrow" size={15} />
            </a>
          )}
        </section>
      )}
      {/* 고정 노출 — 접지 않는다(도메인 규칙 1·4) */}
      <div className="v7-note v7-note--wait">
        <Icon name="info" size={18} />
        <div>
          <strong>초안입니다</strong>
          <span>{d.disclaimer}</span>
        </div>
      </div>

      {d.status === 'STALE' && (
        <div id="draft-refresh" className="v7-note v7-note--bad" role="status">
          <Icon name="warning" size={18} />
          <div>
            <strong>만든 뒤에 검토 상태·분류·정산이 바뀌었습니다</strong>
            <span>
              이 초안의 금액은 지금 거래와 다를 수 있습니다. 다시 만들면 직접 적으신 값은 그대로 이어집니다. 이 초안은 이력으로 남습니다.
            </span>
            <button
              type="button"
              className="v7-secondary-button"
              disabled={regenerate.isPending || businessId === undefined}
              onClick={() => regenerate.mutate(d)}
            >
              <Icon name="refresh" size={15} />
              {regenerate.isPending ? '다시 만드는 중…' : '다시 만들기'}
            </button>
            {errorOf(regenerate.data) && <span className="v7-inline-error">⚠ {errorOf(regenerate.data)}</span>}
          </div>
        </div>
      )}
      {d.status === 'PROCESSING' && (
        <p className="v7-note tax-status-line" role="status">
          <Icon name="clock" size={16} /> 아직 집계 중입니다. 끝나면 이 화면이 저절로 채워집니다.
        </p>
      )}
      {d.status === 'FAILED' && (
        <p className="v7-inline-error" role="alert">
          ⚠ 이 초안은 만들지 못했습니다. 목록에서 다시 만들어 주세요.
        </p>
      )}

      <div id="draft-data" className="v7-cols tax-draft-data">
        <Section title="초안 정보">
          <p className="tax-form-name">{form?.name}</p>
          <dl className="v7-facts">
            <div>
              <dt>상태</dt>
              <dd>
                <Badge icon={st.icon} tone={st.tone}>
                  {st.label}
                </Badge>
              </dd>
            </div>
            <div>
              <dt>만든 시각</dt>
              <dd>{d.generatedAt ? formatDateTime(d.generatedAt) : '—'}</dd>
            </div>
            <div>
              <dt>초안에서 뺀 거래</dt>
              <dd>
                {marked ? `${marked.count}건 · ${formatMoney(marked.amount)}` : '없음'}
                <small className="v7-muted"> 검토에서 표시한 것</small>
              </dd>
            </div>
          </dl>
          <p className="v7-note v7-note--small">
            금액에 넣으면 안 될 거래가 있다면 <Link to="/tax/review">신고자료 검토</Link>에서 표시한 뒤 초안을 다시 만들어 주세요.
          </p>
        </Section>

        <RevenueCheck key={draftId} detail={d} onSave={(ntsRevenue) => patch.mutate({ hometaxReference: { ...d.hometaxReference, ntsRevenue } })} saving={patch.isPending || !canEdit} />
      </div>

      {d.excluded && Object.keys(d.excluded).length > 0 && (
        <Section title="금액에 넣지 않은 거래">
          <p className="v7-note v7-note--small">아래 거래는 이 초안의 금액에서 빠져 있습니다. 포함해야 할 거래라면 분류·검토를 수정한 뒤 초안을 다시 만들어 주세요.</p>
          <ul className="v7-rows">
            {d.excluded.personalSuspected && (
              <li>
                <span className="d">
                  <b>개인 사용 가능성</b> · {d.excluded.personalSuspected.count}건 · {formatMoney(d.excluded.personalSuspected.amount)}
                </span>
              </li>
            )}
            {d.excluded.needsReview && (
              <li>
                <span className="d">
                  <b>판단 대기(사업 관련성·중복·세무전문가 확인)</b> · {d.excluded.needsReview.count}건 · {formatMoney(d.excluded.needsReview.amount)}
                </span>
              </li>
            )}
            {d.excluded.unclassified && (
              <li>
                <span className="d">
                  <b>분류하지 않은 거래</b> · {d.excluded.unclassified.count}건 · {formatMoney(d.excluded.unclassified.amount)}
                  <small>분류를 확정해야 초안에 들어갑니다. 초안이 비어 보이면 매출이 없는 것이 아니라 분류 전일 수 있습니다.</small>
                </span>
              </li>
            )}
          </ul>
          <div className="tax-export-row">
            <Link to="/transactions" className="v7-secondary-button">거래 분류하러 가기</Link>
            <Link to="/tax/review" className="v7-secondary-button">신고자료 검토하러 가기</Link>
          </div>
        </Section>
      )}

      {patchError && (
        <p className="v7-inline-error" role="alert">
          ⚠ 저장하지 못했습니다 — {patchError}
        </p>
      )}
      {patch.isSuccess && !patchError && <p className="v7-note v7-note--good" role="status">변경 내용을 저장하고 초안에 반영했습니다.</p>}

      <section id="draft-fields" className="tax-fields-intro" aria-labelledby="draft-fields-title">
        <div>
          <h2 id="draft-fields-title">항목을 확인해 주세요</h2>
          <p>금액 입력 → 계산 근거 확인 → 내용이 맞으면 ‘확인했어요’. 저장한 금액은 관련 합계에 자동 반영됩니다.</p>
        </div>
        <div className="v7-chips" role="group" aria-label="항목 보기 방식">
          <button type="button" aria-pressed={!showAll} onClick={() => setShowAll(false)}>확인할 항목만 {attentionCount}개</button>
          <button type="button" aria-pressed={showAll} onClick={() => setShowAll(true)}>전체 서식 보기</button>
        </div>
        <p role="status" className="tax-next__meta">{!showAll && attentionCount === 0 ? '현재 표시할 확인 항목이 없습니다. 전체 서식과 자료 검토 상태도 확인해 주세요.' : '모르는 금액을 0원으로 입력하지 마세요. 근거와 안내를 확인한 뒤 입력해 주세요.'}</p>
        {/* 빈칸의 세 종류. 배지만 두면 처음 보는 사람은 무슨 뜻인지 알 수 없고,
            줄마다 긴 안내문을 읽게 하면 여덟 줄이 다시 구분되지 않는다 */}
        {/* 배지는 표 안의 것과 <b>똑같이</b> 생겨야 짝이 맞는다. 그래서 단추처럼 보이는데,
          * 배지를 바꾸면 범례 구실을 못 한다 — 상자와 머리말로 「읽는 것」임을 표시한다. */}
        <div className="tax-legend">
        <p className="tax-legend__cap">표시 보는 법</p>
        <dl className="tax-legend__list">
          {(Object.keys(FILL_KIND) as (keyof typeof FILL_KIND)[]).map((k) => (
            <div key={k}>
              <dt>
                <span className="tax-badge" data-tone={FILL_KIND[k].tone}>
                  <Icon name={FILL_KIND[k].icon} size={13} />
                  {FILL_KIND[k].label}
                </span>
              </dt>
              <dd>{FILL_KIND[k].help}</dd>
            </div>
          ))}
        </dl>
        </div>
      </section>
      {visibleSections.map((section) => (
        <Section key={section.sectionKey} title={section.label}>
          <div className="tx-table-wrap">
            <table className={`tx-table tax-fields${showAll ? '' : ' tax-fields--guided'}`}>
              <thead>
                <tr>
                  <th scope="col">항목</th>
                  <th scope="col" className="tx-num">금액·내용</th>
                  <th scope="col">출처</th>
                  <th scope="col">상태</th>
                  <th scope="col">
                    <span className="sr-only">동작</span>
                  </th>
                </tr>
              </thead>
              <tbody>
                {section.fields.map((field) => (
                  <FieldRow
                    key={`${draftId}-${field.fieldKey}`}
                    draftId={draftId}
                    field={field}
                    saving={patch.isPending || !canEdit}
                    onSave={async (value) => {
                      const result = await patch.mutateAsync({ overrides: [{ fieldKey: field.fieldKey, value }] });
                      return result.ready && result.ok;
                    }}
                    onConfirm={() => patch.mutate({ confirmedFieldKeys: [field.fieldKey] })}
                    /* 기초재고액에만 붙는 후보. 나머지 칸은 undefined 라 아무것도 그리지 않는다 */
                    suggestion={field.fieldKey === OPENING_INVENTORY ? priorInventory : undefined}
                  />
                ))}
              </tbody>
            </table>
          </div>
        </Section>
      ))}

      <div id="draft-export">
        <ExportPanel key={draftId} draft={d} form={form} selfFiling={selfFiling} />
      </div>
    </PageShell>
  );
}

function RevenueCheck({ detail, onSave, saving }: { detail: TaxDraftDetail; onSave: (value: number) => void; saving: boolean }) {
  const [text, setText] = useState(detail.hometaxReference?.ntsRevenue?.toString() ?? '');
  const value = parseWon(text);
  const c = detail.revenueCheck;
  return (
    <Section title="홈택스 매출과 비교하기 · 선택">
      <p className="v7-note v7-note--small">홈택스에서 확인한 수입금액이 있으면 입력하세요. 비교용 참고값이며, 지금 입력하지 않아도 초안을 확인할 수 있습니다.</p>
      {c ? (
        <dl className="v7-facts">
          <div>
            <dt>서비스 집계</dt>
            <dd>{formatMoney(c.serviceTotal)}</dd>
          </div>
          {c.ntsRevenue !== undefined && (
            <div>
              <dt>국세청 수입금액</dt>
              <dd>{formatMoney(c.ntsRevenue)}</dd>
            </div>
          )}
          {c.difference !== undefined && (
            <div>
              <dt>차이</dt>
              <dd className={c.difference !== 0 ? 'is-gap' : undefined}>
                {c.difference > 0 ? '+' : ''}
                {formatMoney(c.difference)}
              </dd>
            </div>
          )}
          {c.productSalesTotal !== undefined && (
            <div>
              <dt>POS 판매 총액</dt>
              <dd>
                {formatMoney(c.productSalesTotal)} <small className="v7-muted">참고값</small>
              </dd>
            </div>
          )}
        </dl>
      ) : (
        <p className="v7-note v7-note--small">대조할 값이 아직 없습니다. 홈택스에서 확인한 국세청 수입금액을 적으면 서비스 집계와 견줘 드립니다.</p>
      )}
      {/* 서버 문장이다 — 「누락입니다」가 아니라 「…일 수 있습니다」 */}
      {c?.note && <p className="v7-note v7-note--small">{c.note}</p>}
      <form
        className="tax-inline-form"
        onSubmit={(e) => {
          e.preventDefault();
          if (value !== null && !Number.isNaN(value)) onSave(value);
        }}
      >
        <label>
          국세청 수입금액
          <input type="text" inputMode="numeric" value={text} onChange={(e) => setText(e.target.value)} placeholder="홈택스 신고도움서비스 값" />
        </label>
        <button type="submit" className="v7-secondary-button" disabled={saving || value === null || Number.isNaN(value)}>
          비교값 저장
        </button>
      </form>
    </Section>
  );
}

function FieldRow({
  draftId,
  field,
  saving,
  onSave,
  onConfirm,
  suggestion,
}: {
  /**
   * 다른 데서 이미 아는 값을 <b>후보로</b> 내놓는다. 지금은 기초재고액 하나뿐이다 —
   * 작년 기말재고액이 곧 올해 기초재고액이라고 서식 작성방법 4 가 정해 두었다.
   * 자동으로 채우지 않는다: 기준연도를 함께 보여 주고 사람이 누를 때만 들어간다(도메인 규칙 4).
   */
  suggestion?: { priorYear: number; value: number };
  draftId: number;
  field: TaxDraftField;
  saving: boolean;
  onSave: (value: number | null) => Promise<boolean>;
  onConfirm: () => void;
}) {
  const [open, setOpen] = useState(false);
  const [editing, setEditing] = useState(false);
  const [text, setText] = useState(typeof field.value === 'number' ? String(field.value) : '');
  const status = field.status ? FIELD_STATUS[field.status] : null;
  const value = parseWon(text);
  const detailable = hasBasis(field) || !!field.reviewNote || field.pendingAmount !== undefined || !!field.basisSummary;
  const guide = field.status === 'EMPTY' ? emptyFieldGuide(field.fieldKey) : null;
  /** 빈칸의 종류. 판정 규칙은 taxView 한 곳에 둔다 */
  const fill = fieldFillKind(field);

  return (
    <>
      <tr data-status={field.confirmed && field.status === 'NEEDS_REVIEW' ? 'FILLED' : field.status}>
        <td data-label="항목">
          <strong className="tax-field-label">{field.label}</strong>
          {needsFieldAttention(field) && (
            <p className="tax-field-help">
              {guide ? guide.help : field.reviewNote || (isEditable(field)
                ? '자료를 확인한 뒤 금액을 입력해 주세요. 해당 지출이 없다는 것을 확인한 경우에만 0원을 입력하세요.'
                : hasBasis(field)
                  ? '계산에 포함된 거래와 증빙을 살펴보고 내용이 맞는지 확인해 주세요.'
                  : field.source === 'CALCULATED'
                    ? '다른 항목에서 계산되는 값입니다. 먼저 입력이 필요한 금액을 확인해 주세요.'
                    : '원본 정보에서 가져온 항목입니다. 빠진 내용은 원본 자료나 저장한 파일에서 확인해 주세요.')}
            </p>
          )}
          {/* 작년 기말재고액 후보. 「가져옵니다」가 아니라 「가져올까요」다 — 누르기 전에는
            * 아무것도 들어가지 않고, 어느 해 값인지 먼저 보인다(도메인 규칙 4). */}
          {suggestion && field.status === 'EMPTY' && (
            <p className="tax-suggest">
              <span>
                {suggestion.priorYear}년 기말재고액 <b>{money(suggestion.value)}원</b>
                <small>서식 작성방법 4 — 직전 과세기간의 기말재고액이 올해 기초재고액입니다. 값이 맞는지 확인해 주세요.</small>
              </span>
              <button
                type="button"
                className="v7-secondary-button"
                disabled={saving}
                onClick={() => void onSave(suggestion.value)}
              >
                이 값 쓰기
              </button>
            </p>
          )}
          {field.carriedOver && <small>지난번에 적으신 값입니다</small>}
          {field.aggregatedValue !== undefined && (
            <small>
              고치기 전 집계 {fieldValueLabel(field.aggregatedValue)}
              {field.overrideNote ? ` · ${field.overrideNote}` : ''}
            </small>
          )}
        </td>
        <td className="tx-num" data-label="금액·내용">
          {editing ? (
            <form
              className="tax-inline-form"
              onSubmit={async (e) => {
                e.preventDefault();
                if (Number.isNaN(value)) return;
                try {
                  if (await onSave(value)) setEditing(false);
                } catch {
                  // 실패한 입력은 그대로 두어 다시 저장할 수 있게 한다.
                }
              }}
            >
              <input
                type="text"
                inputMode="numeric"
                aria-label={`${field.label} 금액`}
                value={text}
                autoFocus
                onChange={(e) => setText(e.target.value)}
              />
              <button type="submit" className="v7-secondary-button" disabled={saving || Number.isNaN(value)}>
                저장
              </button>
              <button type="button" className="v7-secondary-button" onClick={() => setEditing(false)}>
                취소
              </button>
            </form>
          ) : (
            fieldValueLabel(field.value, field.fieldKey)
          )}
        </td>
        <td className="v7-muted" data-label="출처">{field.source ? FIELD_SOURCE[field.source] : '—'}</td>
        <td data-label="확인 상태">
          {/* 빈칸은 「비어 있음」 대신 <b>어떤 빈칸인지</b>를 말한다 — 값 칸의 「—」가 이미
              비었다는 것은 말해 주고 있어서, 같은 말을 한 번 더 놓으면 여덟 줄이 같아진다 */}
          {fill ? (
            <span className="tax-badge" data-tone={FILL_KIND[fill].tone} title={FILL_KIND[fill].help}>
              <Icon name={FILL_KIND[fill].icon} size={13} />
              {FILL_KIND[fill].label}
            </span>
          ) : (
            status &&
            !(field.confirmed && field.status === 'NEEDS_REVIEW') && (
              <span className="tax-badge" data-tone={status.tone}>
                <Icon name={status.icon} size={13} />
                {status.label}
              </span>
            )
          )}
          {field.confirmed && (
            <span className="tax-badge" data-tone="good">
              <Icon name="check" size={13} />
              내용 확인 완료
            </span>
          )}
        </td>
        <td className="tax-actions">
          {guide?.action?.to && (
            <Link to={guide.action.to} className="tax-text-button">
              {guide.action.label}
            </Link>
          )}
          {guide?.action?.anchor && (
            <a
              href={`#${guide.action.anchor}`}
              className="tax-text-button"
              onClick={(e) => {
                // 내보내기 칸의 그 입력으로 옮겨 바로 적게 한다
                const input = document.getElementById(guide.action!.anchor!);
                if (!input) return;
                e.preventDefault();
                input.scrollIntoView({ block: 'center' });
                input.focus({ preventScroll: true });
              }}
            >
              {guide.action.label}
            </a>
          )}
          {isEditable(field) && !editing && (
            <button type="button" className="tax-text-button" disabled={saving} onClick={() => {
              setText(typeof field.value === 'number' ? String(field.value) : '');
              setEditing(true);
            }}>
              {field.value === null || field.value === undefined ? '금액 입력' : '금액 수정'}
            </button>
          )}
          {field.status === 'NEEDS_REVIEW' && !field.confirmed && (
            <button type="button" className="tax-text-button" disabled={saving} onClick={onConfirm}>
              확인했어요
            </button>
          )}
          {detailable && (
            <button type="button" className="tax-text-button" aria-expanded={open} onClick={() => setOpen(!open)}>
              {open ? '계산 근거 접기' : '계산 근거 보기'}
            </button>
          )}
        </td>
      </tr>
      {open && (
        <tr className="tax-detail-row">
          <td colSpan={5}>
            {/* 서버 문장이다. 화면에서 새로 짓지 않는다 */}
            {field.reviewNote && <p className="v7-note v7-note--small">{field.reviewNote}</p>}
            {field.pendingAmount !== undefined && (
              <p className="v7-note v7-note--small">
                항목에 넣지 않은 금액 {formatMoney(field.pendingAmount)} — 설비·비품처럼 감가상각 검토가 필요한 지출입니다.
              </p>
            )}
            {field.basisSummary && (
              <p className="tax-basis-summary">
                {[
                  field.basisSummary.transactionCount !== undefined && `거래 ${field.basisSummary.transactionCount}건`,
                  field.basisSummary.evidenceCount !== undefined && `증빙 ${field.basisSummary.evidenceCount}건`,
                  field.basisSummary.evidenceMissingCount ? `증빙 누락 ${field.basisSummary.evidenceMissingCount}건` : null,
                  field.basisSummary.settlementCount !== undefined && `정산 ${field.basisSummary.settlementCount}건`,
                  field.basisSummary.unmatchedSettlementCount ? `대사 미완 정산 ${field.basisSummary.unmatchedSettlementCount}건 포함` : null,
                  field.basisSummary.unreviewedCount ? `미검토 ${field.basisSummary.unreviewedCount}건 포함` : null,
                  field.basisSummary.settlementFeeIncluded ? '정산 수수료 포함' : null,
                ]
                  .filter(Boolean)
                  .join(' · ')}
              </p>
            )}
            {hasBasis(field) && <BasisList draftId={draftId} fieldKey={field.fieldKey} />}
          </td>
        </tr>
      )}
    </>
  );
}

function BasisList({ draftId, fieldKey }: { draftId: number; fieldKey: string }) {
  const [page, setPage] = useState(0);
  const basis = useQuery({
    queryKey: ['v7', 'tax-draft-basis', draftId, fieldKey, page],
    placeholderData: (prev) => prev,
    queryFn: () => getTaxDraftBasis(draftId, fieldKey, page, 20),
  });
  const r = basis.data;
  if (basis.isPending) return <p className="v7-note">근거를 불러오는 중…</p>;
  if (!r?.ready || !r.ok) return <p className="v7-inline-error">⚠ 근거를 불러오지 못했습니다 — {errorOf(r)}</p>;
  const b = r.data;
  return (
    <div className="tax-basis">
      {b.settlementSummary && (
        <p className="v7-note v7-note--small">
          정산 {b.settlementSummary.count}건 · 총매출 {formatMoney(b.settlementSummary.grossSales)} · 취소 {formatMoney(b.settlementSummary.cancellations)} · 수수료{' '}
          {formatMoney(b.settlementSummary.fees)}. {b.settlementSummary.note}
        </p>
      )}
      {b.items.length === 0 ? (
        <p className="v7-note v7-note--small">이 항목에 묶인 거래가 없습니다.</p>
      ) : (
        <table className="tx-table">
          <thead>
            <tr>
              <th scope="col">일시</th>
              <th scope="col">적요</th>
              <th scope="col" className="tx-num">금액</th>
              <th scope="col">검토 상태</th>
              <th scope="col">증빙</th>
            </tr>
          </thead>
          <tbody>
            {b.items.map((t) => {
              const review = TAX_REVIEW[t.taxReviewStatus as TaxReviewStatus];
              return (
                <tr key={t.transactionId}>
                  <td data-label="일시">{formatDateTime(t.occurredAt)}</td>
                  <td data-label="적요">
                    {t.description}
                    {t.settlementLinked && <small>정산에 묶인 입금 — 금액은 정산 총매출로 셉니다</small>}
                  </td>
                  <td data-label="금액" className="tx-num">
                    {t.direction === 'WITHDRAWAL' ? '−' : '+'}
                    {money(t.amount)}원
                  </td>
                  <td data-label="검토 상태">{review?.label ?? t.taxReviewStatus}</td>
                  <td data-label="증빙">
                    {t.evidences.length === 0 ? (
                      <span className="v7-muted">없음</span>
                    ) : (
                      t.evidences.map((e) => (
                        <Link key={e.evidenceId} to={`/evidences/${e.evidenceId}`} className="tax-link">
                          {e.fileName}
                        </Link>
                      ))
                    )}
                  </td>
                </tr>
              );
            })}
          </tbody>
        </table>
      )}
      {b.totalPages > 1 && (
        <nav className="tx-pager" aria-label="근거 쪽 넘김">
          <button type="button" className="v7-secondary-button" disabled={page === 0} onClick={() => setPage(page - 1)}>
            이전
          </button>
          <span>
            {page + 1} / {b.totalPages} · {b.totalElements}건
          </span>
          <button type="button" className="v7-secondary-button" disabled={page + 1 >= b.totalPages} onClick={() => setPage(page + 1)}>
            다음
          </button>
        </nav>
      )}
    </div>
  );
}

/**
 * 내보내기 — 서식 PDF(브라우저 인쇄)와 홈택스 입력용 XLSX(서버).
 * 민감 항목(주소지·생년월일)은 **이 입력칸과 인쇄 화면·요청 본문에만** 있다 — 저장하지 않고,
 * 받거나 인쇄 창을 닫으면 입력칸도 비운다. 비워 두면 「직접 기재」로 남는다.
 */
function ExportPanel({
  draft,
  form,
  selfFiling,
}: {
  draft: TaxDraftDetail;
  form: TaxFilingForm | undefined;
  /** 「직접 신고」를 고른 사람. 이 사람의 마지막 행동은 파일 저장이 아니라 옮겨 적기다 */
  selfFiling: boolean;
}) {
  const fields = sensitiveFields(form);
  const [values, setValues] = useState<Record<string, string>>({});
  const [done, setDone] = useState(false);
  const ready = draft.status === 'READY';
  // 인쇄 레이아웃은 별지 제74호서식을 그린 것이라 간편장부 초안에만 붙는다
  const printable = draft.formType === 'SIMPLE_LEDGER';

  const run = useMutation({
    mutationFn: () => {
      const sensitiveValues = Object.fromEntries(Object.entries(values).filter(([, v]) => v.trim() !== ''));
      return exportTaxDraft(draft.draftId, {
        format: 'XLSX_HOMETAX_INPUT',
        sensitiveValues: Object.keys(sensitiveValues).length ? sensitiveValues : undefined,
      });
    },
    onSuccess: (res) => {
      // 결과와 관계없이 민감값은 화면에서도 지운다
      setValues({});
      if (!res.ready || !res.ok) return;
      saveFile(res.data, `신고서초안_${draft.draftId}.xlsx`);
      setDone(true);
    },
  });

  function print() {
    setDone(false);
    // 인쇄 창이 닫히면(저장·취소 모두) 민감값을 지운다
    window.addEventListener('afterprint', () => setValues({}), { once: true });
    window.print();
  }

  return (
    <Section title="확인한 초안을 파일로 저장하세요">
      <p className="tax-field-help">세무전문가와 검토할 때는 PDF를, 홈택스에 옮겨 입력할 때는 엑셀을 활용하세요. 파일을 저장해도 신고가 접수되지는 않습니다.</p>
      {draft.sections.some((section) => section.fields.some(needsFieldAttention)) && (
        <p className="v7-note v7-note--wait">확인하지 않은 항목이나 거래가 남아 있습니다. 검토용으로 저장할 수 있으며, 최종 사용 전 내용을 확인해 주세요.</p>
      )}
      <p className="v7-note v7-note--small">
        {printable && (
          <>
            <b>서식 PDF</b>는 별지 제74호서식 본표와 부표를 서식 칸 그대로 채운 것입니다. 인쇄 창에서 「PDF로 저장」을 고르세요.
            <br />
          </>
        )}
        <b>XLSX</b>는 홈택스 입력 순서대로 정리한 목록입니다. 소득공제·세액공제 같은 뒤쪽 칸은 서비스가 계산하지 않으니 홈택스에서 직접 입력해 주세요.
      </p>
      {fields.length > 0 && (
        <div className="tax-sensitive">
          <p className="v7-note v7-note--small">
            <Icon name="shield" size={14} /> 아래 칸은 저장하지 않습니다. 이번 파일에만 들어가고 받은 뒤에는 지워집니다. 비워 두면 「직접 기재」로 남습니다.
          </p>
          {fields.map((f) => (
            <label key={f.fieldKey}>
              {f.label}
              <input
                id={`sensitive-${f.fieldKey}`}
                type="text"
                autoComplete="off"
                value={values[f.fieldKey] ?? ''}
                onChange={(e) => setValues({ ...values, [f.fieldKey]: e.target.value })}
              />
            </label>
          ))}
        </div>
      )}
      <div className="tax-export-row">
        {/* 직접 신고면 이 자리가 옮겨 적기다. PDF 는 세무전문가 검토용이라 회계사 경로에
          * 그대로 남는다 — 양쪽 다 지우면 넘길 파일을 받을 길이 없어진다. */}
        {selfFiling ? (
          <Link
            to={`/tax/drafts/${draft.draftId}/hometax`}
            className={`auth-submit tax-export${ready ? '' : ' is-off'}`}
            aria-disabled={!ready || undefined}
            onClick={(e) => {
              if (!ready) e.preventDefault();
            }}
          >
            <Icon name="arrow" size={16} />
            홈택스에 옮겨 적기
          </Link>
        ) : (
          printable && (
            <button type="button" className="auth-submit tax-export" disabled={!ready} onClick={print}>
              <Icon name="fold" size={16} />
              검토용 PDF 저장
            </button>
          )
        )}
        <button type="button" className="v7-secondary-button tax-export" disabled={!ready || run.isPending} onClick={() => run.mutate()}>
          <Icon name="receipt" size={16} />
          {run.isPending ? '파일을 만드는 중…' : '홈택스 입력용 엑셀 받기'}
        </button>
      </div>
      {!ready && (
        <p className="v7-note v7-note--small">
          {draft.status === 'STALE' ? '근거가 바뀐 초안은 받을 수 없습니다. 위에서 다시 만들어 주세요.' : '초안이 준비된 뒤에 받을 수 있습니다.'}
        </p>
      )}
      {errorOf(run.data) && (
        <p className="v7-inline-error" role="alert">
          ⚠ 파일을 받지 못했습니다 — {errorOf(run.data)}
        </p>
      )}
      {done && (
        <p className="v7-note v7-note--good" role="status">
          <Icon name="check" size={16} /> 파일을 받았습니다. 입력한 주소지·생년월일은 화면에서 지웠습니다.
        </p>
      )}
      {ready && printable && <TaxFormPrint draft={draft} sensitive={values} />}
    </Section>
  );
}
