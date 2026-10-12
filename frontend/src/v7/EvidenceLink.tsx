import { useState } from 'react';
import { useMutation, useQueries, useQuery, useQueryClient } from '@tanstack/react-query';
import { formatDateTime, getTransaction, linkEvidence, listMatchCandidates, unlinkEvidence } from '@jangoyebo/core';
import type { ApiResult, EvidenceDetail, MatchCandidate } from '@jangoyebo/core';
import { Icon } from './icons';
import { money } from './model';
import { Section } from './PageShell';

/**
 * 증빙 ↔ 거래 연결 (API 13.4, P0-10).
 *
 * 증빙은 거래 하나에만 붙는다 — 다른 거래로 옮기려면 떼고 다시 붙인다. 서버는 후보를 점수순으로 줄 뿐
 * 붙이지는 않는다(구현현황 3.29). 금액·날짜가 맞는 유일한 후보(autoLinkEligible)는 「추천」으로 맨 위에
 * 두되, 연결은 사장님이 누른다 — 틀린 자동 연결은 신고 직전에야 드러난다.
 *
 * 연결하면 그 거래의 검토 상태가 증빙 확인 완료로, 떼면 증빙 누락으로 바뀐다(8.7 자동 판정). 그래서
 * 검토·초안 캐시도 함께 버린다.
 */

const MATCHED_BY: Record<string, string> = {
  AMOUNT_EXACT: '금액 일치',
  DATE_SAME_DAY: '같은 날',
  DATE_NEAR: '3일 안',
  MERCHANT_SIMILAR: '상호 비슷',
};

/** 서버는 발행일 ±3일 안의 거래를 금액과 상관없이 모두 준다 — 앞의 몇 개만 먼저 보인다 */
const FIRST_CANDIDATES = 5;

const errorOf = (res: ApiResult<unknown> | undefined) =>
  !res ? null : !res.ready ? '아직 준비되지 않은 기능입니다.' : res.ok ? null : res.error.message;

function Amount({ amount, direction }: { amount: number; direction: MatchCandidate['direction'] }) {
  return (
    <td data-label="금액" className="tx-num" data-direction={direction}>
      {direction === 'WITHDRAWAL' ? '−' : '+'}
      {money(amount)}원
    </td>
  );
}

export function EvidenceLinkSection({ evidence }: { evidence: EvidenceDetail }) {
  const queryClient = useQueryClient();
  const evidenceId = evidence.evidenceId;
  const linkedIds = evidence.linkedTransactionIds ?? [];
  const linked = linkedIds.length > 0;
  const [showAll, setShowAll] = useState(false);

  const linkedTx = useQueries({
    queries: linkedIds.map((id) => ({
      queryKey: ['transactions', 'detail', id],
      queryFn: () => getTransaction(id),
    })),
  });
  const candidates = useQuery({
    queryKey: ['v7', 'evidence-candidates', evidenceId],
    // 붙어 있으면 후보를 볼 일이 없다 — 옮기려면 먼저 뗀다
    enabled: !linked,
    queryFn: () => listMatchCandidates(evidenceId),
  });

  function refresh() {
    for (const key of [
      ['v7', 'evidence', evidenceId],
      ['v7', 'evidences'],
      ['v7', 'evidence-candidates', evidenceId],
      ['v7', 'tax-review'],
      ['v7', 'tax-drafts'],
      ['v7', 'tax-draft'],
      ['transactions'],
    ]) {
      queryClient.invalidateQueries({ queryKey: key });
    }
  }
  const link = useMutation({ mutationFn: (transactionId: number) => linkEvidence(evidenceId, transactionId), onSuccess: refresh });
  const unlink = useMutation({ mutationFn: () => unlinkEvidence(evidenceId), onSuccess: refresh });
  const busy = link.isPending || unlink.isPending;
  const error = errorOf(link.data) ?? errorOf(unlink.data);

  const r = candidates.data;
  const items = r?.ready && r.ok ? [...r.data.items].sort((a, b) => Number(b.autoLinkEligible) - Number(a.autoLinkEligible)) : [];

  return (
    <Section title="거래 연결">
      {linked ? (
        <>
          <p className="v7-note v7-note--small">
            이 증빙이 근거인 거래입니다. 연결된 거래는 신고자료 검토에서 「증빙 확인 완료」로 표시됩니다.
          </p>
          <div className="tx-table-wrap">
            <table className="tx-table">
              <tbody>
                {linkedIds.map((id, i) => {
                  const res = linkedTx[i]?.data;
                  const t = res?.ready && res.ok ? res.data : null;
                  return (
                    <tr key={id}>
                      <td data-label="날짜">{t ? formatDateTime(t.occurredAt) : '—'}</td>
                      <td data-label="적요">{t?.description ?? `거래 ${id}`}</td>
                      {t ? <Amount amount={t.amount} direction={t.direction} /> : <td data-label="금액" className="tx-num">—</td>}
                      <td className="tax-actions">
                        <button type="button" className="tax-text-button" disabled={busy} onClick={() => unlink.mutate()}>
                          {unlink.isPending ? '떼는 중…' : '연결 해제'}
                        </button>
                      </td>
                    </tr>
                  );
                })}
              </tbody>
            </table>
          </div>
          <p className="v7-note v7-note--small">다른 거래로 옮기려면 연결을 해제한 뒤 다시 고르세요.</p>
        </>
      ) : candidates.isPending ? (
        <p className="v7-note">후보를 찾는 중…</p>
      ) : errorOf(r) ? (
        <p className="v7-inline-error" role="alert">
          ⚠ 후보를 불러오지 못했습니다 — {errorOf(r)}
        </p>
      ) : items.length === 0 ? (
        <div className="v7-note v7-note--small">
          <Icon name="info" size={16} />
          <span>
            {evidence.extraction?.issuedDate
              ? '발행일 앞뒤 3일 안에서 맞는 거래를 찾지 못했습니다. 금액과 발행일이 맞게 읽혔는지 확인해 주세요.'
              : '발행일을 알아야 후보를 찾을 수 있습니다. 읽은 값에서 발행일을 채워 저장해 주세요.'}
          </span>
        </div>
      ) : (
        <>
          <p className="v7-note v7-note--small">
            금액과 날짜가 가까운 거래입니다. 순위는 참고용이니 이 증빙이 근거인 거래를 골라 연결해 주세요.
          </p>
          <div className="tx-table-wrap">
            <table className="tx-table evidence-candidates">
              <thead>
                <tr>
                  <th scope="col">날짜</th>
                  <th scope="col">적요</th>
                  <th scope="col" className="tx-num">
                    금액
                  </th>
                  <th scope="col">맞는 점</th>
                  <th scope="col">
                    <span className="sr-only">동작</span>
                  </th>
                </tr>
              </thead>
              <tbody>
                {(showAll ? items : items.slice(0, FIRST_CANDIDATES)).map((c) => (
                  <tr key={c.transactionId} data-recommended={c.autoLinkEligible || undefined}>
                    <td data-label="날짜">{formatDateTime(c.occurredAt)}</td>
                    <td data-label="적요">
                      {c.description}
                      {c.autoLinkEligible && (
                        <span className="tax-badge" data-tone="good">
                          <Icon name="check" size={13} />
                          추천
                        </span>
                      )}
                    </td>
                    <Amount amount={c.amount} direction={c.direction} />
                    <td data-label="맞는 점">{c.matchedBy.map((m) => MATCHED_BY[m] ?? m).join(' · ') || '—'}</td>
                    <td className="tax-actions">
                      <button
                        type="button"
                        className="tax-text-button"
                        disabled={busy}
                        onClick={() => link.mutate(c.transactionId)}
                      >
                        {link.isPending && link.variables === c.transactionId ? '연결하는 중…' : '연결'}
                      </button>
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
          {items.length > FIRST_CANDIDATES && (
            <button type="button" className="tax-text-button" aria-expanded={showAll} onClick={() => setShowAll(!showAll)}>
              {showAll ? '후보 접기' : `후보 ${items.length - FIRST_CANDIDATES}개 더 보기`}
            </button>
          )}
        </>
      )}
      {error && (
        <p className="v7-inline-error" role="alert">
          ⚠ {error}
        </p>
      )}
    </Section>
  );
}
