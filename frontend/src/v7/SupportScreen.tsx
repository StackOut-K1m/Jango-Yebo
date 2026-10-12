import { useId, useState } from 'react';
import type { FormEvent, ReactNode } from 'react';
import { Link, useParams } from 'react-router';
import { useQuery } from '@tanstack/react-query';
import type { UseQueryResult } from '@tanstack/react-query';
import {
  formatDateTime,
  formatManwon,
  formatMoney,
  getSupportProgram,
  getSupportRecommendations,
  listDocumentGuides,
  listSupportPrograms,
} from '@jangoyebo/core';
import type {
  ApiResult,
  ProgramSort,
  RecommendationItem,
  SupportProgramListItem,
  SupportProgramQuery,
  SupportProgramRecommendations,
  SupportType,
} from '@jangoyebo/core';
import { useBusiness } from '@/business/useBusiness';
import { PageShell, Section } from './PageShell';
import { Icon } from './icons';
import { dayLabel } from './loanView';
import {
  PROGRAM_STATUS,
  SUPPORT_TYPE,
  TIMING,
  deadlineLabel,
  disbursementLabel,
  eligibilityRows,
  feeLabel,
  filterRecommendations,
  isEmptyProfile,
  percentLabel,
  timingOf,
  todayIso,
} from './supportView';
import './support.css';

/**
 * 지원 제도 (SC-10-3·4, API 17.1~17.4, P1-15).
 *
 * 하나의 검색 영역에서 전체 공고(17.1)와 맞춤 추천(17.3)을 전환한다.
 * 점수(`matchScore`)는 띄우지 않는다 — 조건을 적지 않은 공고가 0.5 를 받아 「반쯤 맞는다」로
 * 읽힌다. 대신 맞은 조건의 이름을 보인다.
 *
 * ⚠ 서버가 모르는 값은 키를 뺀다. 지급 시기·공백 전 도착은 대부분 없고, 없으면 「모름」이다.
 */

type Loaded<T> = { state: 'ready'; data: T } | { state: 'not-ready' } | { state: 'error'; message: string };

/** NOT_READY 는 오류가 아니라 정상 빈 상태다(화면구성도 6.4) */
function settle<T>(res: ApiResult<T>): Loaded<T> {
  if (!res.ready) return { state: 'not-ready' };
  if (!res.ok) return { state: 'error', message: res.error.message };
  return { state: 'ready', data: res.data };
}

/** 모든 화면에 고정하는 문구(화면구성도 6장 고정 disclaimer) */
const FINAL_CHECK = '최종 자격은 주관 기관 확인이 필요합니다.';

/** 서버는 첫 토막으로 대조한다(「광주 서구」 → 「광주」). 시·도 약칭만 둔다 */
const REGIONS = ['서울', '부산', '대구', '인천', '광주', '대전', '울산', '세종', '경기', '강원', '충북', '충남', '전북', '전남', '경북', '경남', '제주'];

function Badge({ icon, children, tone }: { icon: string; children: ReactNode; tone?: 'good' | 'warn' | 'muted' }) {
  return (
    <span className="support-badge" data-tone={tone}>
      <Icon name={icon} size={13} />
      {children}
    </span>
  );
}

/** 확인 필요 표시. 조건이 불명확한 공고다 — 접거나 숨기지 않는다(도메인 규칙 1) */
function VerifyBadge({ on }: { on: boolean }) {
  return on ? (
    <Badge icon="warning" tone="warn">
      자격 확인 필요
    </Badge>
  ) : null;
}

/** 시드의 가짜 공고. 시연 화면에 섞여도 진짜로 읽히지 않게 이름표를 단다 */
function SampleBadge({ site }: { site?: string }) {
  return site === 'SAMPLE' ? (
    <Badge icon="info" tone="muted">
      샘플 데이터
    </Badge>
  ) : null;
}

function SourceLink({ url, title }: { url?: string; title: string }) {
  return url ? (
    <a href={url} target="_blank" rel="noopener noreferrer" className="v7-secondary-button support-source-button" aria-label={`${title} 원문 보기 (새 탭)`}>
      원문 보기 <Icon name="diagonal" size={14} />
    </a>
  ) : (
    <span className="support-source-unavailable">원문 링크 미제공</span>
  );
}

export function SupportScreen() {
  const { current } = useBusiness();
  const businessId = current?.businessId;
  const [today] = useState(() => todayIso());

  return (
    <PageShell
      title="지원 제도"
    >
      <Finder today={today} businessId={businessId} />
    </PageShell>
  );
}

// --- 17.3 맞춤 추천 --------------------------------------------------------------

function RecommendationContent({ recs, today, query }: {
  recs: UseQueryResult<Loaded<SupportProgramRecommendations>>;
  today: string;
  query: SupportProgramQuery;
}) {
  if (recs.isPending) {
    return <p className="v7-note">불러오는 중…</p>;
  }
  if (recs.data?.state !== 'ready') {
    return (
      <>
        {recs.data?.state === 'not-ready' ? (
          <div className="empty-state">맞춤 추천 기능이 아직 준비되지 않았습니다.</div>
        ) : (
          <p className="v7-inline-error" role="alert">
            ⚠ 추천을 불러오지 못했습니다{recs.data?.state === 'error' ? ` — ${recs.data.message}` : ''}{' '}
            <button type="button" className="v7-secondary-button" onClick={() => void recs.refetch()}>
              다시 불러오기
            </button>
          </p>
        )}
      </>
    );
  }

  const r = recs.data.data;
  const emptyProfile = isEmptyProfile(r.profile);
  const items = filterRecommendations(r.items, query);

  return (
    <>
      {/* 네 칸이 다 비면 모든 공고가 같은 점수다. 왜 순서가 마감순인지 말해 둔다 */}
      {emptyProfile && (
        <p className="support-context-note">사업장 정보가 없어 마감이 가까운 순서로 표시합니다. 지역·업종·매출·업력을 등록하면 더 정확하게 추천받을 수 있습니다.</p>
      )}
      {r.cashGap && (
        <p className="support-context-note">
          {dayLabel(r.cashGap.firstShortfallDate)}부터 최대 약 {formatManwon(r.cashGap.maxShortfallAmount)}의 자금 공백이 예상되어 지급 시기를 함께 확인했습니다. 공고에 지급 시기가 없으면 확인이 필요합니다.
        </p>
      )}
      <div className="support-results-head" aria-live="polite">
        <p className="support-count">추천 공고 <strong>{items.length}</strong>건</p>
        <span>{emptyProfile ? '마감 가까운 순' : '추천순'} · 최대 20건 내 검색</span>
      </div>
      {r.items.length === 0 ? (
        <div className="empty-state">지금 추천할 지원 제도가 없습니다.</div>
      ) : items.length === 0 ? (
        <div className="empty-state">추천 공고 중 검색 조건에 맞는 공고가 없습니다. 검색 조건을 바꾸거나 전체 공고에서 찾아보세요.</div>
      ) : (
        <ul className="support-list">
          {items.map((item) => (
            <ProgramRow key={item.programId} program={item} today={today} emptyProfile={emptyProfile} />
          ))}
        </ul>
      )}
      <p className="v7-note v7-note--small">{r.disclaimer} · {formatDateTime(r.baseTime)} 기준</p>
    </>
  );
}

function RecommendationDetails({ item, emptyProfile }: { item: RecommendationItem; emptyProfile: boolean }) {
  const timing = TIMING[timingOf(item.canArriveBeforeGap)];
  return (
    <>
      <div className="support-item__why">
        {item.matchedConditions.length > 0 ? (
          <>
            <span className="support-item__label">추천 이유</span>
            {item.matchedConditions.map((c) => (
              <span key={c} className="support-chip">
                <Icon name="check" size={12} />
                {c}
              </span>
            ))}
          </>
        ) : (
          <span className="support-item__label">
            {emptyProfile ? '사업장 정보가 없어 조건을 대조하지 못했습니다' : '맞는지 판단할 조건이 공고에 없습니다'}
          </span>
        )}
      </div>
      <p className="support-item__timing">
        <Badge icon={timing.icon} tone={item.canArriveBeforeGap === true ? 'good' : item.canArriveBeforeGap === false ? 'warn' : 'muted'}>
          {timing.label}
        </Badge>
        {/* 문구는 서버가 만든다 — 지급 시기를 아는지 모르는지가 서버에만 있다 */}
        {item.timingNote && <span>{item.timingNote}</span>}
      </p>
    </>
  );
}

// --- 17.1 검색 -----------------------------------------------------------------

const SIZE = 10;

function Finder({ today, businessId }: { today: string; businessId?: number }) {
  const [view, setView] = useState<'all' | 'recommended'>('all');
  const tabId = useId();
  const recommended = view === 'recommended';
  const [draft, setDraft] = useState({ q: '', region: '', supportType: '', includeClosed: false, sort: 'DEADLINE' as ProgramSort });
  const [query, setQuery] = useState<SupportProgramQuery>({ sort: 'DEADLINE', page: 0, size: SIZE });

  const list = useQuery({
    queryKey: ['v7', 'support-programs', query],
    queryFn: async () => settle(await listSupportPrograms(query)),
    placeholderData: (prev) => prev,
    enabled: !recommended,
  });

  const recs = useQuery({
    queryKey: ['v7', 'support-recommendations', businessId],
    queryFn: async () => settle(await getSupportRecommendations(businessId!, true)),
    enabled: businessId !== undefined && recommended,
  });

  function search(event: FormEvent) {
    event.preventDefault();
    setQuery({
      q: draft.q.trim() || undefined,
      region: draft.region || undefined,
      supportType: (draft.supportType || undefined) as SupportType | undefined,
      includeClosed: draft.includeClosed || undefined,
      sort: draft.sort,
      page: 0,
      size: SIZE,
    });
  }

  const page = list.data?.state === 'ready' ? list.data.data : null;

  return (
    <Section label="지원 공고 검색">
      <div className="support-browse-head">
        <div className="support-tabs" role="tablist" aria-label="공고 목록 선택">
          {(['all', 'recommended'] as const).map((tab, index) => (
            <button
              key={tab}
              type="button"
              role="tab"
              id={`${tabId}-${tab}`}
              aria-selected={view === tab}
              aria-controls={`${tabId}-panel`}
              tabIndex={view === tab ? 0 : -1}
              onClick={() => setView(tab)}
              onKeyDown={(event) => {
                if (!['ArrowLeft', 'ArrowRight', 'Home', 'End'].includes(event.key)) return;
                event.preventDefault();
                const next = event.key === 'Home' ? 'all' : event.key === 'End' ? 'recommended' : index === 0 ? 'recommended' : 'all';
                setView(next);
                document.getElementById(`${tabId}-${next}`)?.focus();
              }}
            >
              {tab === 'all' ? '전체 공고' : '맞춤 추천'}
            </button>
          ))}
        </div>
        <span className="support-browse-caption">{recommended ? '우리 가게의 사업장 정보 기준' : '정부·지자체 지원 공고'}</span>
      </div>
      <div id={`${tabId}-panel`} role="tabpanel" aria-labelledby={`${tabId}-${view}`} tabIndex={0}>
        <form className="support-filters" onSubmit={search}>
          <label className="support-filters__q">
            검색어
            <input
              type="search"
              placeholder={recommended ? '추천 공고명이나 주관 기관' : '공고명이나 주관 기관'}
              value={draft.q}
              onChange={(e) => setDraft({ ...draft, q: e.target.value })}
            />
          </label>
          {!recommended && <label>
            지역
            <select value={draft.region} onChange={(e) => setDraft({ ...draft, region: e.target.value })}>
              <option value="">전체</option>
              {REGIONS.map((r) => (
                <option key={r} value={r}>
                  {r}
                </option>
              ))}
            </select>
          </label>}
          <label>
            지원 유형
            <select value={draft.supportType} onChange={(e) => setDraft({ ...draft, supportType: e.target.value })}>
              <option value="">전체</option>
              {(Object.keys(SUPPORT_TYPE) as SupportType[]).map((t) => (
                <option key={t} value={t}>
                  {SUPPORT_TYPE[t]}
                </option>
              ))}
            </select>
          </label>
          {!recommended && <label>
            정렬
            <select value={draft.sort} onChange={(e) => setDraft({ ...draft, sort: e.target.value as ProgramSort })}>
              <option value="DEADLINE">마감 가까운 순</option>
              <option value="COLLECTED_AT">최근 수집 순</option>
            </select>
          </label>}
          {!recommended && <label className="support-filters__check">
            <input
              type="checkbox"
              checked={draft.includeClosed}
              onChange={(e) => setDraft({ ...draft, includeClosed: e.target.checked })}
            />
            마감된 공고도 보기
          </label>}
          <button type="submit" className="v7-secondary-button">
            <Icon name="arrow" size={15} />
            찾기
          </button>
        </form>

        {recommended ? (
          businessId === undefined ? (
            <div className="empty-state">사업장을 등록하면 맞춤 추천을 확인할 수 있습니다.</div>
          ) : <RecommendationContent recs={recs} today={today} query={query} />
        ) : list.isPending ? (
          <p className="v7-note">불러오는 중…</p>
        ) : list.data?.state === 'not-ready' ? (
          <div className="empty-state">지원 제도 검색 기능이 아직 준비되지 않았습니다.</div>
        ) : list.isError || list.data?.state === 'error' ? (
          <p className="v7-inline-error" role="alert">
            공고를 불러오지 못했습니다{list.data?.state === 'error' ? ` — ${list.data.message}` : ''}{' '}
            <button type="button" className="v7-secondary-button" onClick={() => void list.refetch()}>다시 불러오기</button>
          </p>
        ) : page && page.items.length === 0 ? (
          // 자격 조건을 아직 읽지 못한 공고는 서버가 뺀다 — 비었다고 고장이 아니다
          <div className="empty-state">
            조건에 맞는 공고가 없습니다.
            <br />
            공고는 매일 수집하며, 자격 조건을 정리하기 전의 공고는 여기에 나오지 않습니다.
          </div>
        ) : page ? (
          <>
            <div className="support-results-head" aria-live="polite">
              <p className="support-count">전체 공고 <strong>{page.totalElements}</strong>건{query.includeClosed ? ' · 마감 포함' : ''}</p>
              {list.isFetching && <span>검색 중…</span>}
            </div>
            <ul className="support-list">
              {page.items.map((p) => (
                <ProgramRow key={p.programId} program={p} today={today} />
              ))}
            </ul>
            {page.totalPages > 1 && (
              <nav className="tx-pager" aria-label="쪽 넘김">
                <button
                  type="button"
                  className="v7-secondary-button"
                  disabled={page.page === 0}
                  onClick={() => setQuery({ ...query, page: page.page - 1 })}
                >
                  이전
                </button>
                <span>
                  {page.page + 1} / {page.totalPages}
                </span>
                <button
                  type="button"
                  className="v7-secondary-button"
                  disabled={page.page + 1 >= page.totalPages}
                  onClick={() => setQuery({ ...query, page: page.page + 1 })}
                >
                  다음
                </button>
              </nav>
            )}
          </>
        ) : null}
        {!recommended && <p className="v7-note v7-note--small">
          공고 내용은 수집 이후 바뀔 수 있습니다. {FINAL_CHECK}
        </p>}
      </div>
    </Section>
  );
}

function ProgramRow({ program: p, today, emptyProfile = false }: { program: SupportProgramListItem | RecommendationItem; today: string; emptyProfile?: boolean }) {
  const summary = 'status' in p ? p : null;
  const status = summary ? PROGRAM_STATUS[summary.status] : null;
  const deadline = deadlineLabel(p.applyEnd, today);
  const where = summary ? [...summary.eligibilitySummary.regions, ...summary.eligibilitySummary.industries] : [];
  return (
    <li className="support-item" data-closed={summary?.status === 'CLOSED' || undefined}>
      <div className="support-item__head">
        <div className="support-item__heading">
          <Link to={`/support-programs/${p.programId}`} className="support-item__title">
            {p.title}
          </Link>
          {status && <Badge icon={status.icon} tone={summary?.status === 'OPEN' ? 'good' : 'muted'}>
            {status.label}
          </Badge>}
          <VerifyBadge on={p.needsVerification} />
          <SampleBadge site={summary?.sourceSite} />
        </div>
        <SourceLink url={p.sourceUrl} title={p.title} />
      </div>
      <p className="support-item__meta">
        {[
          p.organizer,
          p.supportType && SUPPORT_TYPE[p.supportType],
          p.maxAmount !== undefined && `최대 ${formatManwon(p.maxAmount)}`,
          summary?.interestRate !== undefined && `금리 ${percentLabel(summary.interestRate)}`,
        ]
          .filter(Boolean)
          .join(' · ')}
        {deadline && <span className={deadline.urgent && summary?.status !== 'CLOSED' ? 'is-urgent' : undefined}> · {deadline.text}</span>}
      </p>
      {where.length > 0 && <p className="support-item__where">대상 {where.join(' · ')}</p>}
      {'matchedConditions' in p && <RecommendationDetails item={p} emptyProfile={emptyProfile} />}
    </li>
  );
}

// --- 17.2 + 17.4 상세 ----------------------------------------------------------

export function SupportDetailScreen() {
  const { programId: raw } = useParams();
  const programId = Number(raw);
  const valid = Number.isInteger(programId) && programId > 0;
  const [today] = useState(() => todayIso());

  const detail = useQuery({
    queryKey: ['v7', 'support-program', programId],
    enabled: valid,
    queryFn: async () => settle(await getSupportProgram(programId)),
  });
  // 사전(17.4)은 발급 소요 시간·유의 사항을 더해 준다. 발급처·링크·수수료는 이미 17.2 에 붙어 온다
  const guides = useQuery({
    queryKey: ['v7', 'document-guides', programId],
    enabled: valid,
    queryFn: async () => settle(await listDocumentGuides({ programId })),
  });

  const back = (
    <Link to="/support-programs" className="v7-secondary-button">
      지원 제도 목록
    </Link>
  );

  if (!valid) {
    return (
      <PageShell title="지원 제도" actions={back}>
        <div className="empty-state">공고 주소가 올바르지 않습니다.</div>
      </PageShell>
    );
  }
  if (detail.isPending) {
    return (
      <PageShell title="지원 제도" actions={back}>
        <p className="v7-note">불러오는 중…</p>
      </PageShell>
    );
  }
  if (detail.data?.state !== 'ready') {
    return (
      <PageShell title="지원 제도" actions={back}>
        {detail.data?.state === 'not-ready' ? (
          <div className="empty-state">지원 제도 상세 기능이 아직 준비되지 않았습니다.</div>
        ) : (
          <p className="v7-inline-error" role="alert">
            ⚠ 공고를 불러오지 못했습니다{detail.data?.state === 'error' ? ` — ${detail.data.message}` : ''}
          </p>
        )}
      </PageShell>
    );
  }

  const d = detail.data.data;
  const s = d.summary;
  const status = PROGRAM_STATUS[s.status];
  const deadline = deadlineLabel(s.applyEnd, today);
  const rows = eligibilityRows(d.eligibility);
  const guideList = guides.data?.state === 'ready' ? guides.data.data.items : [];
  // 이름으로 먼저 찾는다. 링크로는 그 링크를 쓰는 사전 항목이 하나뿐일 때만 — 홈택스 서류들은
  // 링크가 같아서, 링크만 보면 사업자등록증명의 안내가 다른 서류에 붙는다
  const guideFor = (name: string, url?: string) => {
    const byName = guideList.find((g) => g.documentName === name);
    if (byName || !url) return byName;
    const byUrl = guideList.filter((g) => g.issueUrl === url);
    return byUrl.length === 1 ? byUrl[0] : undefined;
  };

  return (
    <PageShell title={s.title} lede={s.organizer} showTitle actions={
      <div className="support-detail__actions">
        <SourceLink url={s.sourceUrl} title={s.title} />
        {back}
      </div>
    }>
      <div className="support-detail__badges">
        <Badge icon={status.icon} tone={s.status === 'OPEN' ? 'good' : 'muted'}>
          {status.label}
        </Badge>
        <VerifyBadge on={s.needsVerification} />
        <SampleBadge site={s.sourceSite} />
      </div>

      {s.needsVerification && (
        <div className="v7-note v7-note--wait">
          <Icon name="warning" size={18} />
          <div>
            <strong>자격 조건 일부가 공고에서 분명하지 않습니다</strong>
            <span>아래 조건은 공고를 정리한 것입니다. 신청 전에 원문과 주관 기관에 꼭 확인해 주세요.</span>
          </div>
        </div>
      )}

      <div className="v7-cols">
        <Section title="지원 내용">
          <dl className="v7-facts">
            <div>
              <dt>지원 유형</dt>
              <dd>{s.supportType ? SUPPORT_TYPE[s.supportType] : '—'}</dd>
            </div>
            <div>
              <dt>최대 금액</dt>
              <dd>{formatMoney(s.maxAmount)}</dd>
            </div>
            {s.interestRate !== undefined && (
              <div>
                <dt>금리</dt>
                <dd>{percentLabel(s.interestRate)}</dd>
              </div>
            )}
            <div>
              <dt>접수 기간</dt>
              <dd>
                {s.applyStart || s.applyEnd ? `${dayLabel(s.applyStart)} ~ ${dayLabel(s.applyEnd)}` : '—'}
                {deadline && s.status !== 'CLOSED' && (
                  <small className={deadline.urgent ? 'is-urgent' : undefined}> {deadline.text}</small>
                )}
              </dd>
            </div>
            <div>
              {/* 없으면 「빠르다」가 아니라 「공고가 말하지 않았다」 */}
              <dt>지급 시기</dt>
              <dd className={d.expectedDisbursementDays === undefined ? 'v7-muted' : undefined}>
                {disbursementLabel(d.expectedDisbursementDays)}
              </dd>
            </div>
            <div>
              <dt>출처</dt>
              <dd className="v7-muted">
                {s.sourceSite ?? '—'}
                {s.collectedAt && ` · ${formatDateTime(s.collectedAt)} 수집`}
                {s.sourceUrl && (
                  <>
                    {' · '}
                    <a href={s.sourceUrl} target="_blank" rel="noopener noreferrer" className="support-link">
                      공고 원문 <Icon name="diagonal" size={12} />
                    </a>
                  </>
                )}
              </dd>
            </div>
          </dl>
        </Section>

        <Section title="자격 조건">
          {rows.length === 0 ? (
            <p className="v7-note v7-note--small">정리된 자격 조건이 없습니다. 공고 원문을 확인해 주세요.</p>
          ) : (
            <dl className="v7-facts">
              {rows.map((row) => (
                <div key={row.key}>
                  <dt>{row.label}</dt>
                  <dd className={row.key === 'uncertainConditions' ? 'is-uncertain' : 'v7-muted'}>{row.value}</dd>
                </div>
              ))}
            </dl>
          )}
        </Section>
      </div>

      {d.rawSummary && (
        <Section title="공고 요약">
          <p className="support-raw">{d.rawSummary}</p>
        </Section>
      )}

      <Section title="제출 서류">
        {d.requiredDocuments.length === 0 ? (
          <p className="v7-note v7-note--small">공고에서 정리된 제출 서류가 없습니다. 공고 원문을 확인해 주세요.</p>
        ) : (
          <div className="tx-table-wrap">
            <table className="tx-table support-docs">
              <thead>
                <tr>
                  <th scope="col">서류</th>
                  <th scope="col">발급처</th>
                  <th scope="col">방법</th>
                  <th scope="col" className="tx-num">수수료</th>
                  <th scope="col">소요 시간</th>
                  <th scope="col">발급</th>
                </tr>
              </thead>
              <tbody>
                {d.requiredDocuments.map((doc) => {
                  const guide = guideFor(doc.documentName, doc.issueUrl);
                  return (
                    <tr key={doc.documentName}>
                      <td data-label="서류">
                        <strong>{doc.documentName}</strong>
                        {guide?.notes && <small>{guide.notes}</small>}
                      </td>
                      <td data-label="발급처">{doc.issuer ?? '—'}</td>
                      <td data-label="방법">{doc.method ?? '—'}</td>
                      <td data-label="수수료" className="tx-num">{feeLabel(doc.fee)}</td>
                      <td data-label="소요 시간">{guide?.estimatedTime ?? '—'}</td>
                      <td data-label="발급">
                        {doc.issueUrl ? (
                          <a href={doc.issueUrl} target="_blank" rel="noopener noreferrer" className="support-link">
                            발급처 열기 <Icon name="diagonal" size={12} />
                          </a>
                        ) : (
                          // 통장 사본처럼 발급처가 없는 서류도 지우지 않는다 — 준비해야 할 서류가 조용히 사라진다
                          <span className="v7-muted">직접 준비</span>
                        )}
                      </td>
                    </tr>
                  );
                })}
              </tbody>
            </table>
          </div>
        )}
      </Section>

      <p className="v7-note v7-note--small">
        {FINAL_CHECK} 신청 서류 작성과 제출은 주관 기관 안내에 따라 직접 하셔야 합니다.
      </p>
    </PageShell>
  );
}
