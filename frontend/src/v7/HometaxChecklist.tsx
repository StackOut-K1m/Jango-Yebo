import { useState } from 'react';
import { Link, useParams } from 'react-router';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { getTaxDraft, listAccounts, patchTaxDraft } from '@jangoyebo/core';
import type { Account, TaxDraftField } from '@jangoyebo/core';
import { useBusiness } from '@/business/useBusiness';
import { PageShell, Section } from './PageShell';
import { Icon } from './icons';
import { filingIssues } from './filingReady';
import { copyText, isSelfStep, resolveSteps } from './hometaxSteps';
import type { ResolvedStep } from './hometaxSteps';
import { FIELD_SOURCE, fieldValueLabel, isEditable } from './taxView';
import './tax.css';

/**
 * 홈택스 직접 신고 따라하기 (S15P21C105-264 · SC-07-8 하위).
 *
 * 우리는 사용자 홈택스 계정에 접속할 수 없다(도메인 규칙 5) — 사람이 칸마다 옮겨 적는다.
 * 그때 생기는 오타·누락을 줄이는 것이 이 화면의 전부다: **단계로 끊고, 칸 번호를 그대로 보여
 * 주고, 값은 복사 버튼으로 넘긴다.**
 *
 * 계산은 아무것도 하지 않는다. 값은 초안 상세를 옮겨 적기만 한다(도메인 규칙 2).
 */

export function HometaxChecklistScreen() {
  const { draftId } = useParams();
  const id = Number(draftId);
  const { current } = useBusiness();
  const businessId = current?.businessId;

  const draftQuery = useQuery({
    queryKey: ['v7', 'tax-draft', id],
    enabled: Number.isFinite(id),
    queryFn: () => getTaxDraft(id),
  });
  const accountsQuery = useQuery({
    queryKey: ['v7', 'accounts', businessId],
    enabled: businessId !== undefined,
    staleTime: 30_000,
    queryFn: () => listAccounts(businessId!),
  });

  const result = draftQuery.data;
  const draft = result?.ready && result.ok ? result.data : null;
  const accountResult = accountsQuery.data;
  // 해제까지 포함해 전부 본다 — 인증이 끊긴 계좌를 찾는 것이 목적이라 거른 목록을 쓰면 안 된다
  const accounts: Account[] = accountResult?.ready && accountResult.ok ? accountResult.data.items : [];

  const steps = draft ? resolveSteps(draft) : [];
  const issues = draft ? filingIssues(draft, accounts) : [];

  return (
    <PageShell
      title="홈택스 직접 신고 따라하기"
      actions={
        <Link to={`/tax/drafts/${draftId}`} className="v7-secondary-button">
          <Icon name="arrow" size={15} /> 초안으로 돌아가기
        </Link>
      }
    >
      {draftQuery.isPending ? (
        <p className="v7-note">불러오는 중…</p>
      ) : !result?.ready ? (
        <div className="empty-state">신고서 초안 기능이 아직 준비되지 않았습니다.</div>
      ) : !result.ok ? (
        <p className="v7-inline-error" role="alert">
          ⚠ 초안을 불러오지 못했습니다 — {result.error.message}
        </p>
      ) : !draft ? null : draft.formType !== 'SIMPLE_LEDGER' ? (
        <Section title="간편장부 초안에서만 쓸 수 있습니다">
          <p className="v7-note v7-note--small">
            이 따라하기는 간편장부소득금액계산서(별지 제74호서식)를 옮겨 적는 순서입니다. 다른 서식은 칸 배치가 달라 그대로 쓸 수
            없습니다.
          </p>
        </Section>
      ) : (
        <>
          <Section title="옮겨 적기 전에 확인하세요">
            {issues.length === 0 ? (
              <div className="v7-note v7-note--good" role="status">
                <Icon name="check" size={18} />
                <div>
                  <strong>덜 모인 자료는 없습니다</strong>
                  <span>아래 순서대로 옮겨 적으면 됩니다. 최종 판단은 사장님 또는 세무전문가가 합니다.</span>
                </div>
              </div>
            ) : (
              <>
                <p className="v7-note v7-note--small">
                  옮겨 적기는 한 번에 끝내는 일입니다. <b>다 적은 뒤에 빠진 자료를 알면 처음부터 다시 해야 합니다</b> — 아래를 먼저
                  보고 오세요.
                </p>
                <ul className="v7-alerts">
                  {issues.map((issue) => (
                    <li key={issue.id}>
                      <Icon name="warning" size={16} />
                      <span>
                        <strong>{issue.title}</strong>
                        <small>{issue.detail}</small>
                        {issue.to && (
                          <Link to={issue.to} className="tax-link">
                            {issue.linkLabel}
                          </Link>
                        )}
                      </span>
                    </li>
                  ))}
                </ul>
              </>
            )}
          </Section>

          <Section title={`옮겨 적기 ${steps.length}단계`}>
            <p className="v7-note v7-note--small">
              {draft.taxYear}년 귀속 · {draft.periodStart} ~ {draft.periodEnd} · 별지 제74호서식. {draft.disclaimer}
              <br />
              칸 이름 앞의 번호가 서식의 칸 번호입니다. <b>끝낸 단계에 표시해 두면 다시 열었을 때 이어서</b> 할 수 있습니다(이
              기기에만 남습니다).
            </p>
            <ol className="hometax-steps">
              {steps.map((resolved, index) => (
                <StepBlock
                  key={resolved.step.id}
                  draftId={id}
                  index={index + 1}
                  resolved={resolved}
                />
              ))}
            </ol>
          </Section>
        </>
      )}
    </PageShell>
  );
}

function StepBlock({
  index,
  draftId,
  resolved,
}: {
  index: number;
  /** 빈 칸을 그 자리에서 저장할 때 쓴다 */
  draftId: number;
  resolved: ResolvedStep;
}) {
  const { step, sections, fieldCount } = resolved;
  const self = isSelfStep(step);

  return (
    <li className="hometax-step">
      <div className="hometax-step__head">
        <h3>
          <span className="hometax-step__no">{index}</span>
          {step.title}
        </h3>
        {self ? <span className="tax-badge">홈택스에서 직접 입력</span> : <span className="hometax-step__count">{fieldCount}칸</span>}
      </div>
      {step.hint && <p className="hometax-step__hint">{step.hint}</p>}
      {!self &&
        sections.map((section) => (
          <div key={section.sectionKey} className="hometax-section">
            <h4>{section.label}</h4>
            <ul className="hometax-fields">
              {section.fields.map((field) => (
                <FieldRow key={field.fieldKey} draftId={draftId} field={field} />
              ))}
            </ul>
          </div>
        ))}
      {!self && fieldCount === 0 && (
        <p className="hometax-step__hint">이 초안에는 해당하는 칸이 없습니다. 홈택스 화면에서도 비워 두세요.</p>
      )}
    </li>
  );
}

/** 복사 결과. 되돌아갈 길을 보여 줘야 해서 실패를 조용히 삼키지 않는다 */
type CopyState = 'idle' | 'copied' | 'failed';

/**
 * 빈 칸을 여기서 채우는 작은 입력. 금액만 받는다 — PATCH 는 금액 override 만 받고
 * 산식·민감 항목은 400 이다(`isEditable`).
 *
 * ⚠ 0 원을 자동으로 넣지 않는다. 「해당 지출이 없었다」와 「아직 안 봤다」는 다른 말이고,
 *   빈 칸을 0 으로 메우면 그 차이가 영영 사라진다(초안 화면의 안내와 같은 규칙).
 */
function FillHere({ draftId, fieldKey }: { draftId: number; fieldKey: string }) {
  const queryClient = useQueryClient();
  const [text, setText] = useState('');
  const [open, setOpen] = useState(false);
  const value = Number(text.replace(/[^0-9-]/g, ''));
  const usable = text.trim() !== '' && !Number.isNaN(value);

  const save = useMutation({
    mutationFn: () => patchTaxDraft(draftId, { overrides: [{ fieldKey, value }] }),
    onSuccess: (res) => {
      // 응답이 재계산된 상세 전체라 그대로 캐시에 넣는다 — 합계 칸이 같이 갱신된다
      if (res.ready && res.ok) {
        queryClient.setQueryData(['v7', 'tax-draft', draftId], res);
        setOpen(false);
      }
    },
  });
  const error =
    save.data && (!save.data.ready ? '아직 준비되지 않은 기능입니다.' : !save.data.ok ? save.data.error.message : null);

  if (!open) {
    return (
      <button type="button" className="v7-secondary-button" onClick={() => setOpen(true)}>
        금액 입력
      </button>
    );
  }
  return (
    <form
      className="hometax-fill"
      onSubmit={(e) => {
        e.preventDefault();
        if (usable) save.mutate();
      }}
    >
      <input
        type="text"
        inputMode="numeric"
        autoFocus
        value={text}
        onChange={(e) => setText(e.target.value)}
        placeholder="원"
        aria-label="금액"
      />
      <button type="submit" className="v7-secondary-button" disabled={!usable || save.isPending}>
        {save.isPending ? '저장 중…' : '저장'}
      </button>
      <button type="button" className="tax-text-button" onClick={() => setOpen(false)}>
        취소
      </button>
      {error && (
        <span className="v7-inline-error" role="alert">
          {error}
        </span>
      )}
    </form>
  );
}

function FieldRow({ draftId, field }: { draftId: number; field: TaxDraftField }) {
  const [state, setState] = useState<CopyState>('idle');
  const text = copyText(field);

  async function copy() {
    if (!text) return;
    try {
      await navigator.clipboard.writeText(text);
      setState('copied');
    } catch {
      // 안전하지 않은 출처·권한 거부에서 던진다. 값은 그대로 보이니 직접 고를 수 있다
      setState('failed');
    }
    setTimeout(() => setState('idle'), 2_000);
  }

  return (
    <li className="hometax-field">
      <span className="hometax-field__label">{field.label}</span>
      <span className="hometax-field__value">{fieldValueLabel(field.value, field.fieldKey)}</span>
      <span className="hometax-field__source">
        {field.source ? FIELD_SOURCE[field.source] : '—'}
        {field.reviewNote && <small>{field.reviewNote}</small>}
      </span>
      {/* 빈 칸은 <b>그 자리에서</b> 채운다. 홈택스 화면을 띄워 놓고 한 칸씩 옮기다 빈 칸을
          만났을 때, 초안 화면으로 돌아갔다 오게 하면 하던 자리를 잃는다. 저장하면 서버가
          상세를 다시 계산해 돌려주므로 합계 칸도 함께 맞는다(도메인 규칙 2). */}
      {!text && isEditable(field) ? (
        <FillHere draftId={draftId} fieldKey={field.fieldKey} />
      ) : text ? (
        <button type="button" className="v7-secondary-button" onClick={() => void copy()}>
          {state === 'copied' ? (
            <>
              <Icon name="check" size={13} /> 복사됨
            </>
          ) : state === 'failed' ? (
            '직접 선택하세요'
          ) : (
            '복사'
          )}
        </button>
      ) : (
        <button type="button" className="v7-secondary-button" disabled title="값이 없어 복사할 것이 없습니다">
          값 없음
        </button>
      )}
    </li>
  );
}
