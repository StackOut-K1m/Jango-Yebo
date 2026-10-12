import { useRef, useState } from 'react';
import { Link } from 'react-router';
import { useBusiness } from '@/business/useBusiness';
import { PageShell, Section } from './PageShell';
import { BalanceChart } from './BalanceChart';
import { Icon } from './icons';
import { RetryButton } from './Retry';
import { shortMoney, useCashGaps, useForecast, useSafetyLine } from './model';
import type { ForecastState as Forecast } from './model';
import type { CashGapItem, CashGapSuggestion, RiskLevel } from '@jangoyebo/core';
import type { Point } from './model';

/**
 * 위험도는 <b>이름과 아이콘으로</b> 말한다. 색만으로 상태를 구분하지 않는다(도메인 규칙 6).
 * 공백 목록에는 사실상 DANGER·GAP 만 오지만, 서버가 넷 중 무엇을 보내도 읽히게 둔다.
 */
const RISK: Record<RiskLevel, { label: string; icon: 'check' | 'info' | 'warning' }> = {
  SAFE: { label: '안전', icon: 'check' },
  CAUTION: { label: '주의', icon: 'info' },
  DANGER: { label: '위험', icon: 'warning' },
  GAP: { label: '공백', icon: 'warning' },
};

/* ══════════════════════════════════════════════════════════════════════
   정산 공백(10.2) 전용 화면. 잔액 예보(10.1)는 메인 화면으로 옮겼다.
   메인의 카드는 요약만 보여 준다 — 공백 구간과 원인은 여기서 편다.
   ══════════════════════════════════════════════════════════════════════ */

/**
 * 곡선을 못 그리는 상태들. 전부 정상 흐름이라 오류처럼 보이지 않게 적는다.
 * 사용자가 할 수 있는 일이 있으면 그 버튼을 함께 둔다(UI 리뷰 5) — 계좌 연결, 다시 불러오기.
 */
function ForecastState({ forecast }: { forecast: Forecast }) {
  const { phase } = forecast;
  if (phase === 'loading') return <p className="v7-note">예보를 불러오는 중…</p>;
  if (phase === 'preparing')
    return (
      <div className="v7-note v7-note--wait">
        <Icon name="clock" size={18} />
        <div>
          <strong>예보를 준비하고 있습니다</strong>
          <span>계산이 끝나면 자동으로 나타납니다. 잠시만 기다려 주세요.</span>
        </div>
      </div>
    );
  if (phase === 'no-account')
    return (
      <div className="v7-note v7-note--wait">
        <Icon name="info" size={18} />
        <div>
          <strong>연결된 계좌가 없습니다</strong>
          <span>계좌를 연결하면 거래가 쌓이고, 그 위에서 예보가 시작됩니다.</span>
          <Link className="retry-button" to="/mypage">
            <Icon name="link" size={14} /> 계좌 연결하기
          </Link>
        </div>
      </div>
    );
  if (phase === 'not-ready')
    return (
      <div className="v7-note v7-note--wait">
        <Icon name="clock" size={18} />
        <div>
          <strong>예보 기능을 준비하고 있습니다</strong>
          <span>준비가 끝나면 이 자리에 곡선이 나타납니다.</span>
        </div>
      </div>
    );
  return (
    <div className="v7-note v7-note--bad">
      <Icon name="warning" size={18} />
      <div>
        <strong>예보를 불러오지 못했습니다</strong>
        <span>잠시 뒤 다시 시도해 주세요.</span>
        <RetryButton onRetry={forecast.retry} busy={forecast.retrying} />
      </div>
    </div>
  );
}

/** 공백의 시작·회복 날짜를 곡선 위 인덱스로 옮긴다. 곡선에 없는 날은 양끝으로 민다. */
function toSpan(gap: CashGapItem, points: Point[], label: string) {
  if (points.length === 0 || !gap.firstShortfallDate) return null;
  const at = (iso: string) => points.findIndex((p) => p.iso >= iso);
  const start = at(gap.firstShortfallDate);
  if (start < 0) return null;
  // 회복일은 「그날 아침에 회복」이라 띠에 넣지 않는다. 회복이 없으면 조회 끝까지가 공백이다.
  const recovered = gap.expectedRecoveryDate ? at(gap.expectedRecoveryDate) : -1;
  const end = recovered > start ? recovered - 1 : points.length - 1;
  return { from: start, to: Math.max(start, end), label };
}

/** 고른 공백의 <b>재는 것만</b>. 원인과 제안은 「상세 보기」 팝업으로 넘긴다. */
function GapBrief({ gap, onOpen }: { gap: CashGapItem; onOpen: () => void }) {
  const risk = gap.riskLevel ? RISK[gap.riskLevel] : null;
  return (
    <div className="v7-gap-brief">
      <div className="v7-gap-brief__top">
        {risk && (
          <span className="v7-gap-risk" data-risk={gap.riskLevel}>
            <Icon name={risk.icon} size={13} /> {risk.label}
          </span>
        )}
        <span className="v7-figure">가장 낮은 날 {gap.maxShortfallDate ?? '—'}</span>
      </div>
      <p className="v7-amount is-gap">
        {shortMoney(gap.maxShortfallAmount ?? 0)}
        <small>원 부족 가능</small>
      </p>
      <dl className="v7-facts v7-facts--wide">
        <div>
          <dt>부족 시작</dt>
          <dd>{gap.firstShortfallDate ?? '—'}</dd>
        </div>
        <div>
          <dt>회복 예상</dt>
          <dd>
            {gap.expectedRecoveryDate ?? (
              <span className="v7-muted">조회 기간 안에는 회복하지 않습니다</span>
            )}
          </dd>
        </div>
        <div>
          <dt>지속</dt>
          <dd>{gap.durationDays !== undefined ? `${gap.durationDays}일` : '—'}</dd>
        </div>
      </dl>
      <button type="button" className="v7-secondary-button" onClick={onOpen}>
        상세 보기 <Icon name="arrow" size={15} />
      </button>
    </div>
  );
}

/** 원인과 해볼 수 있는 것. 곡선을 가리지 않도록 팝업으로 띄운다. */
/** 공백 제안이 데려갈 화면. 타입(CashGapSuggestion['type'])의 셋이 모두 갈 곳이 있다 */
const SUGGESTION_ROUTE: Record<NonNullable<CashGapSuggestion['type']>, string> = {
  SCENARIO: '/scenarios',
  SUPPORT_PROGRAM: '/support-programs',
  LOAN: '/loans',
};

function GapDetail({ gap, index }: { gap: CashGapItem; index: number }) {
  const detail = (gap.causes?.length ?? 0) > 0 || (gap.suggestions?.length ?? 0) > 0;
  return (
    <>
      <p className="v7-figure">
        공백 {index + 1} · {gap.firstShortfallDate ?? '—'} ~{' '}
        {gap.expectedRecoveryDate ?? '회복 미정'}
      </p>
      {gap.causes && gap.causes.length > 0 && (
        <>
          <h3 className="v7-subhead">원인</h3>
          <ul className="v7-rows">
            {gap.causes.map((c, ci) => (
              <li key={`${c.date}-${ci}`}>
                <span className="t">{c.date?.slice(5).replace('-', '.') ?? '—'}</span>
                <span className="d">
                  {c.title ?? '—'}
                  {!c.plannedTransactionId && <small>예정 거래가 아니라 관측된 사실입니다</small>}
                </span>
              </li>
            ))}
          </ul>
        </>
      )}
      {gap.suggestions && gap.suggestions.length > 0 && (
        <>
          <h3 className="v7-subhead">해볼 수 있는 것</h3>
          <ul className="v7-rows">
            {gap.suggestions.map((sg, si) => (
              <li key={si}>
                <span className="d">
                  {/* 문구는 서버가 만든다 — 「공백 전 집행 가능성이 있는」과 「신청할 수 있는」은
                    * 지급 시기를 아는지로 갈리고, 그 판단이 서버에만 있다(도메인 규칙 1) */}
                  {sg.type && SUGGESTION_ROUTE[sg.type] ? (
                    <Link to={SUGGESTION_ROUTE[sg.type]}>{sg.description ?? '—'}</Link>
                  ) : (
                    sg.description ?? '—'
                  )}
                </span>
              </li>
            ))}
          </ul>
        </>
      )}
      {/* 원인도 제안도 없이 오는 공백이 있다. 빈 채로 열리면 고장으로 읽힌다 */}
      {!detail && (
        <p className="v7-note v7-note--small">
          이 공백의 원인과 제안은 아직 계산되지 않았습니다.
        </p>
      )}
      <p className="v7-note v7-note--small">
        이 화면은 일정을 바꾸거나 이체하지 않습니다. 실행은 사장님이 직접 합니다.
      </p>
    </>
  );
}

export function CashGapScreen() {
  const { current } = useBusiness();
  const businessId = current?.businessId;
  const forecast = useForecast(businessId);
  const gaps = useCashGaps(businessId);
  const safety = useSafetyLine(businessId);
  const [picked, setPicked] = useState(0);
  /** 곡선 위에서 고른 공백 하나. 아래에 재는 것만 펴 보인다. */
  const [openedGap, setOpenedGap] = useState<number | null>(null);
  const detail = useRef<HTMLDialogElement>(null);

  const points = forecast.points;
  const safetyLine = forecast.forecast?.safetyLine ?? safety.amount;
  /** 공백을 곡선 위 구간으로 옮긴다. 곡선에 못 앉는 공백(계산 창 밖)은 빠진다. */
  const spans = gaps.items
    .map((g, i) => ({ gap: g, i, span: toSpan(g, points, `공백 ${i + 1}`) }))
    .filter((r): r is { gap: CashGapItem; i: number; span: { from: number; to: number; label: string } } => r.span !== null);
  const chosen = spans.find((r) => r.i === openedGap);

  return (
    <PageShell
      title="정산 공백"
    >
      {gaps.status !== 'ok' && (
        <Section title="공백 구간">
          {gaps.status === 'loading' ? (
            <p className="v7-note">불러오는 중…</p>
          ) : gaps.status === 'error' ? (
            <div className="v7-note v7-note--bad" role="alert">
              <Icon name="warning" size={18} />
              <div>
                <strong>정산 공백을 확인할 수 없습니다</strong>
                <span>공백이 없다는 뜻이 아닙니다. 다시 불러와 주세요.</span>
                <RetryButton onRetry={gaps.retry} busy={gaps.retrying} />
              </div>
            </div>
          ) : (
            <ForecastState forecast={forecast} />
          )}
        </Section>
      )}

      {/* 「공백 없음」은 정상 조회가 0건일 때만 말한다 — 실패·준비 중에 띄우면 안심 문구가 된다 */}
      {gaps.status === 'ok' && gaps.items.length === 0 && (
        <Section title="공백 구간">
          {points.length === 0 ? (
            <ForecastState forecast={forecast} />
          ) : (
            <div className="v7-note v7-note--good">
              <Icon name="check" size={18} />
              <div>
                <strong>앞으로 {points.length}일 안에는 공백이 없습니다</strong>
                <span>잔액이 0원 아래로 내려가는 날이 계산되지 않았습니다.</span>
              </div>
            </div>
          )}
        </Section>
      )}

      {points.length > 0 && (
        <Section title="공백 전후 흐름">
          {/* 처음에는 곡선만 선다. 고른 구간이 없는데 빈 칸을 남기면 화면이 무엇을 기다리는지
            * 모르겠고, 곡선만 폭을 다 쓰는 편이 읽기에도 낫다. 띠를 누르면 카드가 오른쪽에서
            * 들어오고 곡선이 그만큼 자리를 내준다. */}
          <div className={`v7-gap-work ${chosen ? 'has-brief' : ''}`}>
            <div className="v7-gap-work__chart">
              {/*
                * <b>좁은 viewBox 는 카드가 섰을 때만 쓴다.</b> SVG 는 칸 폭에 맞춰 통째로
                * 늘어난다 — 7:3 의 왼쪽 칸은 본문(최대 1180px) 안에서 740px 안팎이라 기본
                * viewBox(810)로는 배율이 0.9 로 떨어져 축 글자가 9px 이 된다. 반대로 카드가
                * 없어 곡선이 폭을 다 쓸 때 좁은 viewBox(560)를 쓰면 배율이 1.9 까지 올라
                * 높이가 494px 이 된다. 어느 쪽이든 1.3 배 언저리로 맞춘다.
                */}
              <BalanceChart
                points={points}
                safetyLine={safetyLine}
                selected={picked}
                onSelect={setPicked}
                narrow={!!chosen}
                spans={spans.map((r) => r.span)}
                activeSpan={
                  spans.findIndex((r) => r.i === openedGap) >= 0
                    ? spans.findIndex((r) => r.i === openedGap)
                    : null
                }
                onPickSpan={(at) => setOpenedGap(at === null ? null : spans[at].i)}
              />
              <p className="v7-note v7-note--small">
                {spans.length > 0
                  ? '묶어 표시한 구간이 정산 공백입니다. 눌러서 그 구간을 보세요'
                  : '곡선이 0원 선 아래로 내려가는 구간이 공백입니다'}{' '}
                · <Link to="/">메인 화면에서 자세히</Link>
              </p>
            </div>

            {/* 고른 구간의 재는 것만. 원인·제안은 팝업으로 넘겨 곡선을 가리지 않는다.
              * `key` 로 카드를 갈아 끼워야 다른 띠로 옮겼을 때도 들어오는 움직임이 다시 인다 —
              * 같은 요소를 두고 내용만 바꾸면 숫자가 소리 없이 바뀌어 옮겨 간 줄 모른다. */}
            {chosen && (
              <aside
                className="v7-gap-work__brief"
                aria-label={`공백 ${chosen.i + 1}`}
                key={chosen.i}
              >
                <div className="v7-gap-work__brief-top">
                  <h3 className="v7-subhead">공백 {chosen.i + 1}</h3>
                  {/* 띠를 다시 눌러도 닫히지만, 그것을 아는 사람만 닫을 수 있다 */}
                  <button type="button" aria-label="닫기" onClick={() => setOpenedGap(null)}>
                    <Icon name="close" size={16} />
                  </button>
                </div>
                <GapBrief gap={chosen.gap} onOpen={() => detail.current?.showModal()} />
              </aside>
            )}
          </div>
        </Section>
      )}

      {/* 곡선 밖에 앉는 공백이 있을 수 있다 — 조회 창이 잘려 시작일이 곡선에 없는 경우다.
        * 그냥 감추면 「공백 2건」이라 해 놓고 곡선에는 하나만 보인다. */}
      {gaps.items.length > spans.length && (
        <p className="v7-note v7-note--small">
          공백 {gaps.items.length}건 가운데 {gaps.items.length - spans.length}건은 곡선의 조회
          기간 밖이라 곡선에 표시하지 못했습니다.
        </p>
      )}

      <dialog
        ref={detail}
        className="v7-chart-dialog"
        aria-label="정산 공백 상세"
        onClick={(e) => e.target === e.currentTarget && detail.current?.close()}
      >
        <div className="v7-chart-dialog__body">
          <div className="v7-chart-dialog__top">
            <h2>공백 상세</h2>
            <button type="button" aria-label="닫기" onClick={() => detail.current?.close()}>
              <Icon name="close" size={18} />
            </button>
          </div>
          {chosen && <GapDetail gap={chosen.gap} index={chosen.i} />}
        </div>
      </dialog>

    </PageShell>
  );
}
