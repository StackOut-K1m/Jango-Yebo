import type { Account, TaxDraftDetail } from '@jangoyebo/core';
import { needsFieldAttention } from './taxView';

/**
 * 홈택스에 옮겨 적기 전에 손볼 것 (S15P21C105-264).
 *
 * **빠진 것을 먼저 보여 준다.** 옮겨 적기는 한 번에 끝내는 일이라, 다 적고 나서 「분류 안 한
 * 거래가 400건 있었다」를 알면 처음부터 다시 해야 한다.
 *
 * 여기서 세는 것은 **자료가 덜 모인 것**뿐이다. 사용자가 검토에서 일부러 뺀 거래(개인 사용
 * 의심·판단 대기)는 세지 않는다 — 그건 누락이 아니라 사장님이 내린 판단이고, 초안 화면이
 * 이미 따로 보여 준다.
 */

export interface FilingIssue {
  id: string;
  title: string;
  detail: string;
  /** 고치러 갈 화면. 없으면 안내만 한다 */
  to?: string;
  linkLabel?: string;
}

/**
 * 금융망 동기화가 돌지 않는 계좌.
 *
 * `DISCONNECTED` 는 빼는데, 사장님이 **일부러 끊은** 것이라 누락이 아니기 때문이다.
 * 반대로 인증 대기·만료는 **끊을 뜻이 없었는데 거래가 안 들어오고 있는** 상태다.
 */
export const needsReauth = (account: Pick<Account, 'status'>) =>
  account.status === 'PENDING_VERIFICATION' || account.status === 'VERIFICATION_EXPIRED';

export function filingIssues(draft: TaxDraftDetail, accounts: Account[]): FilingIssue[] {
  const issues: FilingIssue[] = [];

  // ⓪ 근거가 바뀐 초안. **가장 먼저 말한다** — 아래 항목들을 다 손봐도 숫자 자체가 낡았으면
  //    옮겨 적을 값이 틀린다. 여기 들어오는 문이 여럿이라(초안 상세의 순서줄·다음 행동,
  //    목록의 바로 가기) 문마다 막는 대신 도착한 자리에서 한 번 더 잡는다.
  if (draft.status === 'STALE') {
    issues.push({
      id: 'stale',
      title: '자료가 바뀌어 초안이 낡았습니다',
      detail: '초안을 만든 뒤 거래·분류·검토가 바뀌었습니다. 지금 값을 옮겨 적으면 바뀌기 전 숫자를 적게 됩니다.',
      to: `/tax/drafts/${draft.draftId}`,
      linkLabel: '초안 다시 만들기',
    });
  }

  // ① 분류 전 거래 — 초안 금액에 **아예 들어가지 않은** 돈이다(14.3 excluded.unclassified).
  //    빼고 보면 매출이 0원인 초안도 그럴듯해 보인다.
  const unclassified = draft.excluded?.unclassified;
  if (unclassified && unclassified.count > 0) {
    issues.push({
      id: 'unclassified',
      title: '아직 분류하지 않은 거래가 있습니다',
      detail: `${unclassified.count.toLocaleString()}건 · ${unclassified.amount.toLocaleString()}원이 초안 금액에 들어가지 않았습니다.`,
      to: '/transactions',
      linkLabel: '거래 화면에서 분류하기',
    });
  }

  // ② 사람이 채워야 하는데 빈 칸 — 파일용 개인정보는 뺀다(내보내기 단계에서 따로 묻는다)
  const attention = draft.sections.flatMap((s) => s.fields).filter(needsFieldAttention);
  if (attention.length > 0) {
    issues.push({
      id: 'fields',
      title: '확인이 필요한 항목이 남았습니다',
      detail: `${attention.length}개 항목이 비어 있거나 확인 전입니다 — ${attention
        .slice(0, 3)
        .map((f) => f.label)
        .join(' · ')}${attention.length > 3 ? ' 외' : ''}`,
      to: `/tax/drafts/${draft.draftId}`,
      linkLabel: '초안에서 채우기',
    });
  }

  // ③ 증빙 누락 — 금액은 맞아도 나중에 근거를 못 댄다. 항목마다 센 것을 합친다
  const evidenceMissing = draft.sections
    .flatMap((s) => s.fields)
    .reduce((n, f) => n + (f.basisSummary?.evidenceMissingCount ?? 0), 0);
  if (evidenceMissing > 0) {
    issues.push({
      id: 'evidence',
      title: '증빙이 없는 거래가 있습니다',
      detail: `${evidenceMissing.toLocaleString()}건에 영수증·세금계산서가 붙어 있지 않습니다. 금액은 그대로 들어가지만 나중에 근거를 대기 어렵습니다.`,
      to: '/evidences',
      linkLabel: '증빙 화면으로',
    });
  }

  // ④ 인증이 끊긴 계좌 — 그 계좌의 최근 거래가 안 들어왔을 수 있다
  const stale = accounts.filter(needsReauth);
  if (stale.length > 0) {
    issues.push({
      id: 'accounts',
      title: '거래를 받아오지 못하는 계좌가 있습니다',
      detail: `${stale.length}개 계좌(${stale
        .map((a) => a.alias ?? a.accountNoMasked)
        .join(' · ')})가 인증 대기·만료 상태입니다. 그 계좌의 최근 거래는 초안에 없습니다.`,
      to: '/mypage',
      linkLabel: '계좌 다시 연결하기',
    });
  }

  return issues;
}
