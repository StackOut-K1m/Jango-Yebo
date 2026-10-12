import { useCallback, useEffect, useRef, useState } from 'react';
import type { CSSProperties, KeyboardEvent, PointerEvent } from 'react';
import {
  LayoutGroup,
  MotionConfig,
  motion,
  useMotionTemplate,
  useMotionValue,
  useReducedMotion,
  useSpring,
  useTransform,
} from 'motion/react';
import type { MotionStyle } from 'motion/react';
import { Link, useNavigate } from 'react-router';
import { formatManwon, formatManwonRange, formatMoney, getBalanceForecast } from '@jangoyebo/core';
import { useQueryClient } from '@tanstack/react-query';
import { useBusiness } from '@/business/useBusiness';
import { AppHeader } from './AppHeader';
import { BasisDialog } from './BasisDialog';
import { Icon } from './icons';
import { RetryButton } from './Retry';
import { BalanceChart } from './BalanceChart';
import { CombinedBalanceChart } from './CombinedBalanceChart';
import { WeatherVisual, weatherLabel, weatherOf } from './Weather';
import { GlassWeather } from './GlassWeather';
import { SafetyLineEditor } from './SafetyLineEditor';
import { WalletWorkspace } from './WalletWorkspace';
import { Rolling } from './Rolling';
import './dashboard-controls.css';
import { koreanDate } from './transactionHistory';
import {
  money,
  shortMoney,
  shortRange,

  useCashGaps,
  useForecast,
  useLoans,
  useNeedsReview,
  useSafetyLine,
} from './model';
import type { ForecastState, LoadStatus, Point } from './model';
import { dayLabel, loanSummary, repaymentLabel } from './loanView';

const spring = { type: 'spring' as const, stiffness: 310, damping: 31 };
/** 틸트는 손을 따라가되 흔들리지 않게 — 스프링을 조금 무르게 둔다 */
const tilt = { stiffness: 220, damping: 20, mass: 0.6 };
/** 가장자리에서 기울어지는 최대 각도(도) */
const TILT_DEG = 9;

/** 카드 지갑에 꽂힌 기능들. 겉면은 제목과 핵심 수치 하나만 둔다(UI 리뷰 — 처음 읽을 문구 줄이기) */
/*
 * 셋째 칸은 한때 「자금 안전」이었다. 겉면·펼친 면 모두 예보 판의 안전선 칩과 하는 일이 같아
 * 메인에 보이지 않던 대출 상환으로 바꿨다 — 금융망 대출은 매일 08:30 에 빠져 잔액에 바로 닿는다.
 */
type ToolId = 'forecast' | 'gaps' | 'loans' | 'review';
const toolMeta: Record<ToolId, { title: string; icon: string }> = {
  forecast: { title: '잔액 예보', icon: 'chart' },
  gaps: { title: '정산 공백', icon: 'warning' },
  loans: { title: '대출 상환', icon: 'wallet' },
  review: { title: '확인할 거래', icon: 'receipt' },
};
const toolIds = Object.keys(toolMeta) as ToolId[];

function Brand({ large = false }: { large?: boolean }) {
  return (
    <span className={`brand ${large ? 'brand-large' : ''}`}>
      <span className="brand-symbol">
        <i />
        <i />
      </span>
      <span>
        잔고예보
        <span className="brand-en">JANGO YEBO</span>
      </span>
    </span>
  );
}

function Intro({ onComplete }: { onComplete: () => void }) {
  const reduced = useReducedMotion();
  useEffect(() => {
    const timer = window.setTimeout(onComplete, reduced ? 100 : 3000);
    return () => window.clearTimeout(timer);
  }, [onComplete, reduced]);
  return (
    <motion.div
      className="intro"
      role="status"
      aria-label="잔고예보 화면이 펼쳐집니다"
      exit={{ opacity: 0 }}
      transition={{ duration: 0.35 }}
    >
      <div className="intro-wordmark">
        <Brand large />
        <span>우리 가게의 내일을 펼치다.</span>
      </div>
      <div className="intro-stage">
        <div className="intro-wing intro-wing-left">
          <span>우리 가게의</span>
          <strong>오늘.</strong>
          <div className="intro-wing-edge" />
        </div>
        <div className="intro-wing intro-wing-right">
          <span>미리 펼쳐보는</span>
          <strong>내일.</strong>
          <div className="intro-wing-edge" />
        </div>
        <div className="intro-hinge" />
      </div>
      <button className="intro-skip" onClick={onComplete}>
        건너뛰기 <Icon name="arrow" size={16} />
      </button>
    </motion.div>
  );
}

/** 곡선 자리의 상태 안내. 할 수 있는 일이 있으면 버튼을 함께 둔다(UI 리뷰 5) */
function PeriodNote({ forecast }: { forecast: ForecastState }) {
  const { phase } = forecast;
  if (phase === 'preparing')
    return (
      <p className="state-note">
        <Icon name="clock" size={16} /> 예보를 준비하고 있습니다. 곧 자동으로 나타납니다.
      </p>
    );
  if (phase === 'no-account')
    return (
      <div className="state-note load-error">
        <Icon name="info" size={16} /> 연결된 계좌가 없어 곡선을 만들 수 없습니다.
        <Link className="retry-button" to="/mypage">
          <Icon name="link" size={14} /> 계좌 연결하기
        </Link>
      </div>
    );
  if (phase === 'not-ready')
    return (
      <p className="state-note">
        <Icon name="clock" size={16} /> 예보 기능이 아직 준비되지 않았습니다.
      </p>
    );
  if (phase === 'error')
    return (
      <div className="state-note error load-error" role="alert">
        <Icon name="warning" size={16} /> 예보를 불러오지 못했습니다.
        <RetryButton onRetry={forecast.retry} busy={forecast.retrying} />
      </div>
    );
  return null;
}

/** 카드를 열면 맨 위에 보이는 요약 수치 하나. 문장으로 풀지 않는다 — 이름표와 값만 둔다 */
type Stat = { label: string; value: string; icon?: 'check' | 'warning' | 'info' };

function WalletCard({
  id,
  index,
  selected,
  onOpen,
  face,
  holder,
}: {
  id: ToolId;
  index: number;
  selected: ToolId | null;
  onOpen: (id: ToolId) => void;
  /** 겹쳐 꽂혀도 보이는 제목 줄의 핵심 수치 하나 */
  face: Stat;
  /** 카드 소유자 자리에 적는 사업장 이름 */
  holder?: string;
}) {
  const meta = toolMeta[id];
  // 3D 틸트 — 카드 안 포인터 위치(0~1)로 기울기와 반사광 자리를 정한다. 동작 줄이기 설정이면 기울이지 않는다
  const reduced = useReducedMotion();
  const px = useMotionValue(0.5);
  const py = useMotionValue(0.5);
  const rotateY = useSpring(useTransform(px, [0, 1], [-TILT_DEG, TILT_DEG]), tilt);
  const rotateX = useSpring(useTransform(py, [0, 1], [TILT_DEG * 0.8, -TILT_DEG * 0.8]), tilt);
  const glareX = useTransform(px, (v) => v * 100);
  const glareY = useTransform(py, (v) => v * 100);
  const glare = useMotionTemplate`radial-gradient(circle at ${glareX}% ${glareY}%, #ffffff47, #ffffff00 58%)`;

  function follow(event: PointerEvent<HTMLButtonElement>) {
    if (reduced) return;
    const box = event.currentTarget.getBoundingClientRect();
    px.set((event.clientX - box.left) / box.width);
    py.set((event.clientY - box.top) / box.height);
  }
  function settle() {
    px.set(0.5);
    py.set(0.5);
  }

  return (
    <div className={`wallet-slot slot-${id}`} style={{ '--index': index } as CSSProperties}>
      <div className="wallet-ghost" />
      {selected !== id && (
        <motion.button
          layoutId={`wallet-${id}`}
          className={`wallet-card card-${id}`}
          onClick={() => onOpen(id)}
          whileHover={{ y: -15 }}
          onPointerMove={follow}
          onPointerLeave={settle}
          style={
            (reduced ? {} : { rotateX, rotateY, transformPerspective: 900, '--glare': glare }) as MotionStyle
          }
          whileTap={{ scale: 0.985 }}
          transition={spring}
          drag="x"
          dragConstraints={{ left: 0, right: 0 }}
          dragElastic={0.2}
          onDragEnd={(_, info) => {
            if (info.offset.x < -45) onOpen(id);
          }}
          aria-label={`${meta.title} ${face.value} — 카드 꺼내기`}
        >
          <div className="wallet-card-heading">
            <span className="card-badge" aria-hidden="true">
              <Icon name={meta.icon} size={17} />
            </span>
            <span>{meta.title}</span>
            <b className="wallet-card-value">
              {/* 색만으로 상태를 말하지 않는다 — 아이콘과 글자를 함께 둔다(도메인 규칙 6) */}
              {face.icon && <Icon name={face.icon} size={15} />}
              {face.value}
            </b>
            <Icon name="diagonal" size={18} />
          </div>
          <span className="wallet-card-foot" aria-hidden="true">
            <span className="wallet-card-holder">{holder}</span>
            <span className="wallet-card-mark">
              <i />
              <i />
            </span>
          </span>
        </motion.button>
      )}
    </div>
  );
}

/* 인트로는 탭을 연 뒤 한 번만 튼다. 한눈에로 돌아올 때마다 틀면 대시보드를 가린다.
   다시 보고 싶으면 replayIntro 가 있다. 저장소를 못 쓰는 환경에서는 매번 튼다. */
const INTRO_SEEN = 'jango.v7.intro-seen';

function introSeen() {
  try {
    return sessionStorage.getItem(INTRO_SEEN) === '1';
  } catch {
    return false;
  }
}

function markIntroSeen() {
  try {
    sessionStorage.setItem(INTRO_SEEN, '1');
  } catch {
    // 못 남기면 다음에 한 번 더 볼 뿐이다.
  }
}

export function V7App() {
  const { businesses, current, select } = useBusiness();
  const navigate = useNavigate();
  const businessId = current?.businessId;
  const queryClient = useQueryClient();

  const [editingBusiness, setEditingBusiness] = useState<number | null>(null);
  const [refreshing, setRefreshing] = useState(false);
  // 숫자를 다시 굴리려고 Rolling 을 새로 마운트시키는 열쇠. 값이 같아도 한 번 더 돈다.
  const [rollSeq, setRollSeq] = useState(0);
  const [refreshError, setRefreshError] = useState<{ businessId: number; message: string } | null>(null);
  // 저장과 그래프 반영은 별개다. 백그라운드 조회도 새로고침 전 화면을 바꾸지 않는다.
  const [held, setHeld] = useState<{
    businessId: number; forecast: ForecastState; line: number | null; savedAmount: number | null;
  } | null>(null);

  const [intro, setIntro] = useState(() => !introSeen());
  const [introKey, setIntroKey] = useState(0);
  const [tool, setTool] = useState<ToolId | null>(null);
  const [selectedDay, setSelectedDay] = useState(0);
  /** 곡선·카드에서 고른 과거 날. 고르면 위쪽 날씨가 예보 대신 그날을 보여준다 */
  const [pastPick, setPastPick] = useState<Point | null>(null);
  const chooseDay = (index: number) => {
    setPastPick(null);
    setSelectedDay(index);
  };
  const [storeMenu, setStoreMenu] = useState(false);

  const dialogRef = useRef<HTMLDivElement>(null);
  const lastFocus = useRef<HTMLElement | null>(null);

  const liveForecast = useForecast(businessId);
  const gaps = useCashGaps(businessId);

  const review = useNeedsReview(businessId);
  const safety = useSafetyLine(businessId);
  const loans = useLoans(businessId);

  const heldForecast = held?.businessId === businessId ? held : null;
  const forecast = heldForecast?.forecast ?? liveForecast;

  const points = forecast.points;
  const safetyLine = heldForecast ? heldForecast.line : forecast.forecast?.safetyLine ?? safety.amount;

  async function refreshForecast() {
    if (businessId === undefined || refreshing) return;
    setRefreshing(true);
    setRefreshError(null);
    setRollSeq((seq) => seq + 1);
    setHeld({ businessId, forecast, line: safetyLine, savedAmount: heldForecast?.savedAmount ?? null });
    try {
      const result = await getBalanceForecast(businessId);
      if (!result.ready) throw new Error('예보 조회 기능이 아직 준비되지 않았습니다.');
      if (!result.ok) throw new Error(result.error.message);
      await queryClient.cancelQueries({ queryKey: ['v7', 'forecast', businessId] });
      queryClient.setQueryData(['v7', 'forecast', businessId], result);
      setHeld((previous) => previous?.businessId === businessId ? null : previous);
      for (const key of ['safety-line', 'cash-gaps', 'alerts']) {
        void queryClient.invalidateQueries({ queryKey: ['v7', key, businessId] });
      }
    } catch (error) {
      setRefreshError({ businessId, message: error instanceof Error ? error.message : '예보를 불러오지 못했습니다.' });
    } finally {
      setRefreshing(false);
    }
  }
  const lowest = points.length ? points.reduce((a, b) => (a.value < b.value ? a : b)) : null;
  const picked = points[Math.min(selectedDay, Math.max(0, points.length - 1))];
  // 총잔액은 서버가 곡선과 같은 실행에서 낸 값 하나만 쓴다(useAccounts 주석).
  const currentBalance = forecast.forecast?.currentBalance;
  const balanceLoading = forecast.phase === 'loading' || forecast.phase === 'preparing';
  // 날씨는 곡선·카드에서 고른 날의 잔액을 판정한 결과다. 무슨 날의 날씨인지 날짜를 함께 적는다.
  // 판정할 잔액이 없으면 날씨도 없다 — 0 으로 치면 잔액이 '—' 인 옆자리에 「맑음」이 뜬다.
  const judged = (pastPick ?? picked)?.value ?? currentBalance;
  const weather = judged === undefined ? null : weatherOf(judged, safetyLine);
  const today = koreanDate(new Date());
  const todayLabel = `${Number(today.slice(5, 7))}월 ${Number(today.slice(8, 10))}일`;
  /**
   * 머리말이 말하는 날. <b>라벨 · 숫자 · 날씨가 모두 같은 날을 가리켜야 한다.</b>
   *
   * 전에는 날씨만 고른 날을 따라가고 큰 숫자는 늘 오늘이라, 다른 날을 골라 두면 옆자리끼리
   * 다른 날을 말했다. 라벨까지 날짜를 따라가게 하면 그 어긋남이 거짓말이 된다.
   *
   * 처음 열 때 곡선이 오늘을 고르므로(`CombinedBalanceChart` 의 `goToday`) 기본값은
   * 오늘이고, 그 값은 서버가 준 현재 잔액과 같다 — 과거 곡선의 마지막 날이 현재 잔액이다.
   */
  const headPoint = pastPick ?? picked ?? null;
  const headValue = headPoint?.value ?? currentBalance;
  /** 예측인가. 이미 일어난 금액을 「예보」라 부르지 않는다(도메인 규칙 1) */
  const headForecast = !pastPick && !!picked;
  const headWhen = pastPick
    ? pastPick.date === todayLabel
      ? '오늘'
      : pastPick.date
    : picked?.date ?? '오늘';
  /**
   * 머리말 문구. <b>누구의 잔고인지</b>를 앞세운다 — 사장님은 가게를 여럿 둘 수 있고
   * (도메인 규칙 7), 화면에서 큰 숫자가 하나뿐이라 그것이 어느 가게 것인지가 먼저다.
   *
   * <b>날짜는 오늘이 아닐 때만 적는다.</b> 오늘이면 군더더기지만, 다른 날을 골라 두면
   * 숫자가 그날 것으로 바뀌므로 날짜가 빠진 라벨은 거짓말이 된다.
   *
   * 가게 이름을 아직 못 받았으면 날짜가 그 자리를 받는다 — 「의 잔고」로 시작하지 않게.
   */
  const headStore = current?.name;
  const headDay = headWhen === '오늘' ? null : headWhen;
  const headNoun = headForecast ? '잔고 예보' : '잔고';
  /** 가게 이름 뒤에 오는 말. 이름은 크고 굵게 따로 그리므로 문자열을 둘로 나눠 둔다 */
  const headRest = headDay ? `의 ${headDay} ${headNoun}` : `의 ${headNoun}`;
  /** 가게 이름을 아직 못 받았을 때의 한 줄. 이때는 크게 그릴 것이 없다 */
  const headPlain = `${headDay ?? '오늘'}의 ${headNoun}`;
  /**
   * 예측인 날에는 범위를 함께 적는다(도메인 규칙 1 — 확정 표현 금지).
   * 확정 금액인 오늘·과거에는 붙이지 않는다. 범위가 없으면(min·max 가 같으면) 뺀다.
   */
  const headRange =
    headForecast && headPoint && headPoint.min !== headPoint.max
      ? formatManwonRange(headPoint.min, headPoint.max)
      : null;

  // 카드를 열었을 때 맨 위에 보여 줄 요약 수치의 재료
  const statInput = {
    ready: points.length > 0,
    days: points.length,
    lowest,
    last: points[points.length - 1],
    confidence: forecast.forecast?.confidence,
    gaps: gaps.items,
    gapsStatus: gaps.status,
    loansStatus: loans.status,
    loans: loanSummary(loans.items),
    reviewStatus: review.status,
    reviewTotal: review.total,
    reviewSuggested: review.items.filter((t) => t.classification.status === 'SUGGESTED').length,
    reviewUnclassified: review.items.filter((t) => t.classification.status === 'UNCLASSIFIED').length,
  };

  const finishIntro = useCallback(() => {
    markIntroSeen();
    setIntro(false);
  }, []);
  useEffect(() => {
    document.body.style.overflow = intro || tool ? 'hidden' : '';
    return () => {
      document.body.style.overflow = '';
    };
  }, [intro, tool]);
  useEffect(() => {
    if (!tool) return;
    const id = window.setTimeout(() => dialogRef.current?.focus(), 80);
    return () => window.clearTimeout(id);
  }, [tool]);
  useEffect(() => {
    function escape(e: globalThis.KeyboardEvent) {
      if (e.key !== 'Escape') return;
      setStoreMenu(false);
      if (intro) finishIntro();
    }
    window.addEventListener('keydown', escape);
    return () => window.removeEventListener('keydown', escape);
  }, [intro, finishIntro]);

  function replayIntro() {
    setTool(null);
    setIntroKey((k) => k + 1);
    setIntro(true);
  }

  function openTool(id: ToolId) {
    lastFocus.current = document.activeElement as HTMLElement;
    setStoreMenu(false);
    setTool(id);
  }
  function closeTool() {
    const previous = tool;
    setTool(null);
    window.setTimeout(() => {
      if (lastFocus.current?.isConnected) lastFocus.current.focus();
      else if (previous)
        document
          .querySelector<HTMLElement>(`[aria-label^="${toolMeta[previous].title} "]`)
          ?.focus();
    }, 350);
  }

  return (
    <MotionConfig reducedMotion="user">
      <LayoutGroup>
        {intro && <Intro key={introKey} onComplete={finishIntro} />}

        <div className={`app ${intro ? 'app-entering' : 'app-ready'}`} inert={intro || tool !== null}>
          <a className="skip-link" href="#main">
            본문으로 바로가기
          </a>

          <AppHeader
            controls={
              <div className="store-controls">
                <div className="popover-anchor">
                  <button className="store-select" aria-expanded={storeMenu} onClick={() => setStoreMenu(!storeMenu)}>
                    <Icon name="store" size={18} />
                    {current?.name ?? '사업장'}
                    <Icon name="down" size={15} />
                  </button>
                  {storeMenu && (
                    <div className="header-popover store-popover">
                      {businesses.map((b) => (
                        <button
                          key={b.businessId}
                          onClick={() => {
                            select(b.businessId);
                            setStoreMenu(false);
                          }}
                        >
                          {b.name}
                          {b.businessId === businessId && <Icon name="check" size={17} />}
                        </button>
                      ))}
                      <button onClick={() => navigate('/businesses/new')}>
                        <Icon name="plus" size={16} /> 사업장 추가
                      </button>
                    </div>
                  )}
                </div>
              </div>
            }
            profileExtra={
              <button onClick={replayIntro}>
                <Icon name="fold" size={17} /> 처음 펼침 다시 보기
              </button>
            }
          />

          <main id="main" className="page-shell">
            <WalletWorkspace wallet={
              <aside className="right-page glass-pane" aria-label="나의 카드 지갑">
                <div className="dashboard-wallet-heading">
                  <span>사장님의 자금 지갑</span>
                </div>
                <div className="wallet-deck">
                  {toolIds.map((id, index) => (
                    <WalletCard key={id} id={id} index={index} selected={tool}
                      onOpen={openTool} face={cardFace(id, statInput)} holder={current?.name} />
                  ))}
                  <div className="wallet-bottom-shadow" />
                </div>
              </aside>
            }>
              <div className="left-page">
                <section className="forecast-surface glass-pane" aria-label="잔액 및 예보">
                  {weather && <GlassWeather weather={weather} />}
                  {/* 성함 · 잔액 · 날씨가 한 줄이다. 성함이 제 줄을 통째로 쓰던 때는 머리에만
                    * 179px 이 들어가 곡선과 날짜 띠가 화면 아래로 밀렸다. 새로고침·안전선
                    * 버튼은 곡선의 조회 기간 줄로 옮겼다 — 거기는 이미 있는 줄이라 높이를
                    * 더 쓰지 않고, 둘 다 곡선을 다시 그리게 하는 버튼이라 뜻으로도 맞는다. */}
                  <div className="dashboard-head">
                    {/* 성함은 걷었다. 상단 바의 프로필과 사업장 이름이 이미 누구의 화면인지
                      * 말하고, 이 자리에서 가장 먼저 읽혀야 하는 것은 금액이다. */}
                    <div className="dashboard-head__money">
                      <div className="section-eyebrow">
                        {headStore ? (
                          <>
                            <strong className="dashboard-head__store">{headStore}</strong>
                            {headRest}
                          </>
                        ) : (
                          headPlain
                        )}
                        {headRange && <em className="dashboard-head__range">{headRange}</em>}
                      </div>
                      <div className="hero-balance">
                        <span className="v7-rise">
                          {balanceLoading || headValue === undefined ? (
                            '불러오는 중'
                          ) : (
                            <Rolling key={`${businessId}-${rollSeq}`} values={[headValue]} format={([v]) => money(v)} />
                          )}
                        </span>
                        <span className="won">원</span>
                      </div>
                    </div>
                    {weather && (
                      <div className="weather-panel">
                        {/* 그림이 168x132 라 60px 짜리 금액보다 두 배 컸다. 이 화면에서 가장
                          * 중요한 것은 숫자다 — 그림을 줄여 무게를 되돌린다. */}
                        <WeatherVisual weather={weather} className="is-compact" />
                        {/* 날짜는 위 라벨이 말한다. 여기까지 적으면 같은 날이 두 번 적힌다 */}
                        <span className={`weather-readout is-${weather}`}>
                          <span className="state">
                            <i aria-hidden="true" />
                            {weatherLabel[weather]}
                          </span>
                        </span>
                      </div>
                    )}
                  </div>
                  {/* 줄 안에 두면 나타날 때 안전선 버튼을 왼쪽으로 밀어낸다. 아래로 뺀다. */}
                  {heldForecast?.savedAmount != null && (
                    <p className="dashboard-pending" role="status">
                      <Icon name="clock" size={14} /> 그래프 반영 대기 · 예보 새로고침을 눌러 주세요.
                    </p>
                  )}
                  {refreshError && refreshError.businessId === businessId && <p className="dashboard-error" role="alert">{refreshError.message} 기존 그래프를 유지합니다. 예보 새로고침으로 다시 시도해 주세요.</p>}
                  {/* 과거 곡선의 마지막 날은 서버가 준 현재 잔액이다 — 없으면 머리말의 큰
                      숫자와 곡선의 오늘이 갈린다. 곡선이 있으면 서버가 currentBalance 를 늘
                      함께 주므로(10.1은 ALWAYS) 아래 조건이 실제로 걸리는 경우는 없다.
                      타입이 요구하는 확인이다. */}
                  {points.length > 0 && currentBalance !== undefined ? (
                    <>
                      <CombinedBalanceChart
                        businessId={businessId}
                        currentBalance={currentBalance}
                        forecast={points}
                        safetyLine={safetyLine}
                        selectedFuture={selectedDay}
                        onSelectFuture={chooseDay}
                        selectedPast={pastPick}
                        onSelectPast={setPastPick}
                        actions={
                          <>
                            {/* 판 위의 글자 링크다 — 버튼 껍데기를 두면 옆 안전선 칩과 무게가 겹친다. */}
                            <button className="dashboard-refresh" onClick={refreshForecast} disabled={refreshing || businessId === undefined}>
                              <Icon name="refresh" size={15} /> {refreshing ? '새로고침 중…' : '예보 새로고침'}
                            </button>
                            <button className="dashboard-control" disabled={businessId === undefined || refreshing || safety.loading} onClick={() => setEditingBusiness(businessId ?? null)}>
                              <Icon name="shield" size={16} /> 안전선 {safety.amount === null ? '설정하기' : formatManwon(heldForecast?.savedAmount ?? safety.amount)}
                            </button>
                          </>
                        }
                      />
                    </>
                  ) : (
                    <PeriodNote forecast={forecast} />
                  )}

                  {/* 날짜·잔액 요약 줄은 뺐다 — 곡선 위 말풍선이 고른 날을 그대로 보여준다.
                      「잔액 예보 자세히 보기」는 지갑의 잔액 예보 카드가 같은 자리로 연다. */}


                  {forecast.forecast && <BasisDialog forecast={forecast.forecast} />}
                </section>

              </div>

            </WalletWorkspace>

          </main>
        </div>

        {businessId !== undefined && editingBusiness === businessId && (
          <SafetyLineEditor
            key={businessId}
            businessId={businessId}
            amount={heldForecast?.savedAmount ?? safety.amount}
            onClose={() => setEditingBusiness(null)}
            onSaved={(amount) => {
              setHeld({ businessId, forecast, line: safetyLine, savedAmount: amount });
              setRefreshError(null);
              setEditingBusiness(null);
            }}
          />
        )}

        {tool && (
          <>
              <motion.div
                className="tool-backdrop"
                initial={{ opacity: 0 }}
                animate={{ opacity: 1 }}
                exit={{ opacity: 0 }}
                onClick={closeTool}
              />
              <ToolDialog
                tool={tool}
                onClose={closeTool}
                titleRef={dialogRef}
                points={points}
                safetyLine={safetyLine}
                selectedDay={selectedDay}
                setSelectedDay={chooseDay}
                gaps={gaps}
                loans={loans}
                review={review}
                stats={toolStats(tool, statInput)}
              />
          </>
        )}

      </LayoutGroup>
    </MotionConfig>
  );
}

const CONFIDENCE: Record<string, string> = {
  HIGH: '높음',
  MEDIUM: '보통',
  LOW: '낮음',
  UNPREDICTABLE: '예측 어려움',
};

/** `2026-09-20` → `9.20` */
const dotDate = (date?: string) => (date ? `${Number(date.slice(5, 7))}.${date.slice(8, 10)}` : '—');

/** 결과를 아직 모르는 상태의 한 줄. 이때 「없음」을 띄우면 조회 실패가 안심 문구로 읽힌다(UI 리뷰 1) */
const PENDING: Record<Exclude<LoadStatus, 'ok'>, string> = {
  loading: '확인하는 중',
  preparing: '준비 중',
  error: '확인할 수 없음',
};
const pendingStat = (status: LoadStatus): Stat =>
  status === 'error'
    ? { icon: 'warning', label: '상태', value: PENDING.error }
    : { icon: 'info', label: '상태', value: PENDING[status as Exclude<LoadStatus, 'ok'>] };

/** 카드를 열었을 때 한눈에 볼 숫자 2~3개 */
function toolStats(
  id: ToolId,
  d: {
    ready: boolean;
    days: number;
    lowest: Point | null;
    last?: Point;
    confidence?: string;
    gaps: ReturnType<typeof useCashGaps>['items'];
    gapsStatus: LoadStatus;
    loansStatus: LoadStatus;
    loans: ReturnType<typeof loanSummary>;
    reviewStatus: LoadStatus;
    reviewTotal: number;
    reviewSuggested: number;
    reviewUnclassified: number;
  },
): Stat[] {
  if (id === 'forecast') {
    if (!d.ready || !d.lowest) return [{ icon: 'info', label: '상태', value: '곡선 준비 중' }];
    return [
      { label: '최저 예상', value: `${shortMoney(d.lowest.value)} · ${d.lowest.label}` },
      // 예측은 범위로 적는다(도메인 규칙 1)
      { label: `${d.days}일 뒤`, value: d.last ? shortRange(d.last.min, d.last.max) : '—' },
      { label: '신뢰', value: d.confidence ? (CONFIDENCE[d.confidence] ?? d.confidence) : '—' },
    ];
  }
  if (id === 'gaps') {
    if (d.gapsStatus !== 'ok') return [pendingStat(d.gapsStatus)];
    if (!d.ready) return [{ icon: 'info', label: '상태', value: '곡선 준비 중' }];
    if (d.gaps.length === 0)
      return [
        { icon: 'check', label: '공백', value: '없음' },
        { label: '살펴본 기간', value: `${d.days}일` },
      ];
    const worst = d.gaps.reduce((a, b) => ((b.maxShortfallAmount ?? 0) > (a.maxShortfallAmount ?? 0) ? b : a));
    return [
      { icon: 'warning', label: '공백', value: `${d.gaps.length}구간` },
      { label: '시작', value: dotDate(d.gaps[0].firstShortfallDate) },
      { label: '부족 가능', value: `최대 ${shortMoney(worst.maxShortfallAmount)}` },
    ];
  }
  if (id === 'loans') {
    if (d.loansStatus !== 'ok') return [pendingStat(d.loansStatus)];
    const l = d.loans;
    // 대출은 불러오기를 눌러야 들어온다 — 비어도 「없음」이라 단정하지 않는다
    if (l.active.length === 0) return [{ icon: 'info', label: '대출', value: '등록 없음' }];
    const stats: Stat[] = [{ label: '상환 중', value: `${l.active.length}건` }];
    // 대출 금액은 서버가 정확히 준다. 만원으로 줄이면 「8만」이 7만 8,908원인지 8만 4천원인지 모른다
    if (l.dailyTotal > 0) stats.push({ label: '매일 빠지는 돈', value: formatMoney(l.dailyTotal) });
    if (l.nextDate) stats.push({ label: '다음 상환', value: dotDate(l.nextDate) });
    if (l.balanceTotal !== null) stats.push({ label: '남은 금액', value: formatMoney(l.balanceTotal) });
    if (l.offForecast > 0) stats.push({ icon: 'warning', label: '예보 미반영', value: `${l.offForecast}건` });
    return stats;
  }
  if (d.reviewStatus !== 'ok') return [pendingStat(d.reviewStatus)];
  if (d.reviewTotal === 0) return [{ icon: 'check', label: '확인할 거래', value: '없음' }];
  // 목록은 20건까지만 받는다. 전부 받았을 때만 종류별로 나눠 센다 — 일부로 나누면 합이 총건수와 어긋난다
  if (d.reviewSuggested + d.reviewUnclassified !== d.reviewTotal)
    return [{ label: '확인할 거래', value: `${d.reviewTotal}건` }];
  return [
    { label: 'AI 제안', value: `${d.reviewSuggested}건` },
    { label: '미분류', value: `${d.reviewUnclassified}건` },
  ];
}

/** 겉면 제목 줄의 핵심 수치 하나. 조회 결과를 모르면 「없음」 대신 상태를 말한다(UI 리뷰 1) */
function cardFace(id: ToolId, d: Parameters<typeof toolStats>[1]): Stat {
  if (id === 'forecast')
    return d.lowest ? { label: '최저', value: `최저 ${shortMoney(d.lowest.value)}` } : { label: '상태', value: '준비 중' };
  if (id === 'gaps') {
    if (d.gapsStatus !== 'ok') return pendingStat(d.gapsStatus);
    return d.gaps.length === 0
      ? { icon: 'check', label: '공백', value: '없음' }
      : { icon: 'warning', label: '공백', value: `${dotDate(d.gaps[0].firstShortfallDate)}부터` };
  }
  if (id === 'loans') {
    if (d.loansStatus !== 'ok') return pendingStat(d.loansStatus);
    const l = d.loans;
    if (l.active.length === 0) return { icon: 'info', label: '대출', value: '등록 없음' };
    if (l.dailyTotal > 0) return { label: '대출', value: `매일 ${formatMoney(l.dailyTotal)}` };
    return l.nextDate
      ? { label: '대출', value: `${dotDate(l.nextDate)} 상환` }
      : { label: '대출', value: `${l.active.length}건` };
  }
  if (d.reviewStatus !== 'ok') return pendingStat(d.reviewStatus);
  return d.reviewTotal === 0 ? { icon: 'check', label: '확인', value: '없음' } : { label: '확인', value: `${d.reviewTotal}건` };
}

/**
 * 상세 목록 위의 상태 한 줄. `ok` 이고 비었을 때만 「없습니다」를 말한다.
 * 오류면 무엇을 못 불러왔는지 말하고 다시 불러오기를 둔다(UI 리뷰 1·5).
 */
function LoadNote({
  status,
  what,
  empty,
  onRetry,
  retrying,
}: {
  status: LoadStatus;
  what: string;
  empty?: string;
  onRetry: () => void;
  retrying: boolean;
}) {
  // 받침이 있으면 을·은, 없으면 를·는
  const last = what.charCodeAt(what.length - 1) - 0xac00;
  const [object, topic] = last >= 0 && last % 28 !== 0 ? ['을', '은'] : ['를', '는'];
  if (status === 'ok') return empty ? <p className="state-note">{empty}</p> : null;
  if (status === 'loading') return <p className="state-note">{what}{object} 확인하는 중…</p>;
  if (status === 'preparing') return <p className="state-note">{what}{topic} 예보 계산이 끝나면 나타납니다.</p>;
  return (
    <div className="state-note error load-error" role="alert">
      <Icon name="warning" size={16} /> {what}{object} 불러오지 못했습니다.
      <RetryButton onRetry={onRetry} busy={retrying} />
    </div>
  );
}

function ToolDialog({
  tool,
  onClose,
  titleRef,
  points,
  safetyLine,
  selectedDay,
  setSelectedDay,
  gaps,
  loans,
  review,
  stats,
}: {
  tool: ToolId;
  onClose: () => void;
  titleRef: React.RefObject<HTMLDivElement | null>;
  points: ReturnType<typeof useForecast>['points'];
  safetyLine: number | null;
  selectedDay: number;
  setSelectedDay: (n: number) => void;
  gaps: ReturnType<typeof useCashGaps>;
  loans: ReturnType<typeof useLoans>;
  review: ReturnType<typeof useNeedsReview>;
  stats: Stat[];
}) {
  const meta = toolMeta[tool];
  const activeLoans = loanSummary(loans.items).active;
  function trap(event: KeyboardEvent<HTMLDivElement>) {
    if (event.key === 'Escape') {
      event.stopPropagation();
      onClose();
    }
    if (event.key !== 'Tab') return;
    const elements = Array.from(
      event.currentTarget.querySelectorAll<HTMLElement>('button:not([disabled]), a[href], input:not([disabled]), [tabindex="0"]'),
    );
    const first = elements[0];
    const last = elements[elements.length - 1];
    if (event.shiftKey && (document.activeElement === first || document.activeElement === event.currentTarget)) {
      event.preventDefault();
      last?.focus();
    }
    if (!event.shiftKey && document.activeElement === last) {
      event.preventDefault();
      first?.focus();
    }
  }

  return (
    <motion.div
      layoutId={`wallet-${tool}`}
      className={`tool-dialog card-${tool}`}
      transition={{ ...spring, stiffness: 235 }}
      role="dialog"
      aria-modal="true"
      aria-labelledby="tool-title"
      tabIndex={-1}
      ref={titleRef}
      onKeyDown={trap}
    >
      <div className="tool-top">
        <Icon name={meta.icon} />
        <button onClick={onClose} className="return-card">
          카드 다시 넣기 <Icon name="close" size={17} />
        </button>
      </div>
      <motion.div
        className="tool-content"
        initial={{ opacity: 0, y: 18 }}
        animate={{ opacity: 1, y: 0 }}
        exit={{ opacity: 0 }}
        transition={{ delay: 0.15, duration: 0.28 }}
      >
        <h2 id="tool-title">{meta.title}</h2>

        <dl className="tool-stats">
          {stats.map((st) => (
            <div key={st.label}>
              <dt>{st.label}</dt>
              <dd>
                {/* 색만으로 상태를 말하지 않는다 — 아이콘과 글자를 함께 둔다(도메인 규칙 6) */}
                {st.icon && <Icon name={st.icon} size={16} />}
                {st.value}
              </dd>
            </div>
          ))}
        </dl>

        {tool === 'forecast' && points.length > 0 && (
          <>
            <BalanceChart points={points} safetyLine={safetyLine} selected={selectedDay} onSelect={setSelectedDay} compact />
            <div className="detail-note">
              <Icon name="info" />
              <p>
                <strong>예측 값은 범위입니다.</strong>
                <span>확정 금액이 아니며 정산 시점과 매출에 따라 달라집니다.</span>
              </p>
            </div>

          </>
        )}

        {tool === 'gaps' && (
          <>
            <LoadNote
              status={gaps.status}
              what="정산 공백"
              empty={gaps.items.length === 0 ? '앞으로 30일 안에 잔액이 비는 구간이 없습니다.' : undefined}
              onRetry={gaps.retry}
              retrying={gaps.retrying}
            />
            {gaps.items.map((g) => (
              <div className="detail-note" key={g.cashGapId ?? g.firstShortfallDate}>
                <Icon name="warning" />
                <p>
                  <strong>
                    {g.firstShortfallDate} ~ {g.expectedRecoveryDate ?? '조회 기간 안에 회복하지 않음'}
                  </strong>
                  <span>
                    가장 낮은 날 {g.maxShortfallDate} · {formatManwon(g.maxShortfallAmount ?? 0)} 부족할 가능성
                  </span>
                </p>
              </div>
            ))}

          </>
        )}

        {tool === 'loans' && (
          <>
            <LoadNote
              status={loans.status}
              what="대출"
              empty={
                activeLoans.length === 0
                  ? '등록된 대출이 없습니다. 대출·신용 화면에서 금융망 대출을 불러올 수 있습니다.'
                  : undefined
              }
              onRetry={loans.retry}
              retrying={loans.retrying}
            />
            {activeLoans.slice(0, 4).map((loan) => (
              <div className="detail-note" key={loan.loanId}>
                <Icon name={loan.affectsForecast ? 'wallet' : 'warning'} />
                <p>
                  <strong>
                    {loan.productName} · {repaymentLabel(loan).main}
                  </strong>
                  <span>
                    {loan.lenderName} · 다음 상환 {dayLabel(loan.nextPaymentDate)}
                    {!loan.affectsForecast && ' · 예보에 반영되지 않음'}
                  </span>
                </p>
              </div>
            ))}
            <Link className="solid-action wide-action" to="/loans">
              대출·신용 보기 <Icon name="arrow" size={17} />
            </Link>
          </>
        )}

        {tool === 'review' && (
          <>
            <LoadNote
              status={review.status}
              what="확인할 거래"
              empty={review.items.length === 0 ? '확인할 거래가 없습니다.' : undefined}
              onRetry={review.retry}
              retrying={review.retrying}
            />
            {review.items.slice(0, 6).map((t) => (
              <div className="detail-note" key={t.transactionId}>
                <Icon name="receipt" />
                <p>
                  <strong>
                    {t.description} · {money(t.amount)}원
                  </strong>
                  <span>
                    {t.classification.status === 'SUGGESTED' ? '제안된 분류를 확인해 주세요' : '아직 분류되지 않았습니다'}
                  </span>
                </p>
              </div>
            ))}
            <Link className="solid-action wide-action" to="/transactions">
              거래 화면에서 확인 <Icon name="arrow" size={17} />
            </Link>
          </>
        )}

      </motion.div>
    </motion.div>
  );
}
