import { useEffect, useId, useRef, useState } from 'react';
import type { KeyboardEvent } from 'react';
import { formatManwon, formatManwonRange, formatMoney } from '@jangoyebo/core';
import { money, shortMoney } from './model';
import type { Point } from './model';

/** 휠로 좁힐 수 있는 최소 날짜 수. 이보다 좁히면 곡선이 아니라 선분 하나가 된다. */
const ZOOM_MIN = 4;

/**
 * 잔액 곡선.
 *
 * 시안에서는 더미 배열을 받았지만 여기서는 서버 곡선(10.1)에서 만든 점을 받는다.
 * 곡선 색은 그날의 날씨다 — 예보 그림과 그래프가 다른 색을 쓰면 같은 날을
 * 두 번 다르게 설명하는 셈이 된다.
 */
/**
 * 곡선 범례. 선 색은 그날 잔액의 날씨를 말한다. 실제·예상은 「오늘」 선과 예상 범위 음영으로 갈린다.
 * 색만으로 말하지 않도록 날씨 이름을 붙인다(도메인 규칙 6). 대시보드와 예보 화면이 같이 쓴다.
 */
export function ChartLegend({ safetyLine }: { safetyLine: number | null }) {
  return (
    <>
      <span>
        <i className="legend-band" />
        예상 범위
      </span>
      <span>
        <i className="legend-hue legend-hue--sun" />
        맑음 {safetyLine === null ? '0원 위' : '안전선 위'}
      </span>
      {safetyLine !== null && (
        <span>
          <i className="legend-hue legend-hue--cloud" />
          흐림 안전선 아래
        </span>
      )}
      <span>
        <i className="legend-hue legend-hue--rain" />비 0원 아래
      </span>
    </>
  );
}

export function BalanceChart({
  points,
  safetyLine,
  compare,
  compact = false,
  narrow = false,
  roomy = false,
  compareAccent = false,
  mainColor,
  zoomable = false,
  focused = null,
  onFocusCurve,
  onExpand,
  mainLabel,
  compareLabel,
  forecastStart = 0,
  todayIndex,
  selected,
  onSelect,
  spans,
  activeSpan = null,
  onPickSpan,
  pickSeq,
}: {
  points: Point[];
  /** null = 안전선 미설정. 그때는 0원 미만만 판정한다. */
  safetyLine: number | null;
  /** 함께 그릴 두 번째 곡선. 기본은 흐린 점선이다. */
  compare?: Point[];
  /**
   * 값 상자에서 두 곡선을 부를 이름. 곡선이 하나뿐이면 붙이지 않는다 — 이름이
   * 하나면 무엇과 구별하는 말인지 알 수 없다. 범례와 같은 말을 써야 둘이 연결된다.
   */
  mainLabel?: string;
  compareLabel?: string;
  /**
   * `compare` 를 <b>보라 실선</b>으로 그린다 (시나리오의 「가정 반영」).
   *
   * 시나리오에서는 `points` 가 원본 예보다 — 평소 보던 날씨 색 곡선을 그대로 두고, 가정을
   * 넣은 곡선을 그 위에 한 가지 색으로 얹어야 <b>두 곡선이 어디서 갈라지는지</b>가 보인다.
   * 반대로 두면 가정 곡선이 잔액에 따라 색을 바꾸고 원본이 흐린 점선이 되어, 무엇이 기준인지
   * 알 수 없다.
   */
  compareAccent?: boolean;
  /**
   * 본선을 <b>날씨 색 대신 이 한 색</b>으로 그린다.
   *
   * 두 곡선을 나란히 견주는 자리(시나리오)에서는 색이 값에 따라 변하면 안 된다 — 한 곡선이
   * 구간마다 다른 색으로 보여서 "저 파란 선과 보라 선이 무슨 차이냐" 가 된다. 대비되는
   * 두 단색으로 두면 갈라지는 지점만 눈에 들어온다.
   */
  mainColor?: string;
  /**
   * 휠로 날짜 범위를 좁혔다 넓혔다 할 수 있게 한다. 두 번 누르면 전체로 돌아온다.
   *
   * <b>페이지 스크롤을 막는다.</b> 차트 위에서만 막으므로 밖으로 나가면 평소대로 굴러간다.
   * React 의 `onWheel` 은 passive 로 붙어 `preventDefault()` 가 듣지 않아, 직접
   * `{ passive: false }` 로 단다.
   */
  zoomable?: boolean;
  /** 강조할 곡선. 나머지는 흐려진다. `null` 이면 둘 다 제 색이다. */
  focused?: 'main' | 'compare' | null;
  /**
   * 곡선을 클릭했을 때. 주면 선 위에 집을 수 있는 투명한 띠가 깔린다.
   * 배경을 클릭하면 `null` 로 부른다 — 원상태로 돌리는 길이다.
   */
  onFocusCurve?: (which: 'main' | 'compare' | null) => void;
  compact?: boolean;
  /**
   * 화면 폭의 절반쯤 되는 칸에 들어갈 때 켠다 (시나리오의 4:6 배치).
   *
   * <b>SVG 는 폭에 맞춰 통째로 늘어난다.</b> `viewBox` 를 그대로 두고 칸만 좁히면 배율이
   * 1 아래로 떨어져 **그림 영역도 글자도 같이 줄어든다** — 시나리오 칸(약 586px)에서
   * 0.72배가 되어 축 글자가 7.2px, 안전선 라벨이 6.5px 였다.
   *
   * 그래서 `W` 를 줄여 배율을 1 위로 되돌리고, `H` 를 키워 세로를 더 쓴다. 폭을 늘리는
   * 것이 아니라 **좌표계를 좁히는 것**이라 다른 화면은 영향을 받지 않는다.
   */
  narrow?: boolean;
  /**
   * 메인 화면처럼 넓고 높은 자리에 들어갈 때 켠다.
   *
   * narrow 와 푸는 문제는 같다(viewBox 배율). 다만 narrow 는 고정 폭 560 으로 좁히고,
   * 이쪽은 **칸 폭을 재서 배율을 1 로 맞춘다** — 지갑을 여닫으면 폭이 계속 바뀌기 때문이다.
   * 높이는 상수로 둔다. 폭에 엮으면 지갑을 여닫을 때마다 아래 내용이 세로로 들썩인다.
   *
   * ⚠ 둘이 같은 문제를 두 방식으로 푼다 — 언젠가 하나로 합쳐야 한다(S15P21C105-225 MR 참고).
   */
  roomy?: boolean;
  /**
   * 있으면 <b>칸을 클릭했을 때 날짜를 고르는 대신 이것을 부른다</b> (크게 보기 팝업).
   *
   * 좁은 칸의 차트는 훑어보는 용도라 클릭 한 번에 팝업을 여는 편이 낫다. 마우스를 올려
   * 그날 잔액을 보는 것과 <b>방향키로 날짜를 옮기는 것은 그대로다</b> — 키보드 조작까지
   * 팝업으로 돌리면 큰 화면 없이는 차트를 읽을 수 없게 된다.
   */
  onExpand?: () => void;
  /** 이 인덱스부터 예측. 앞쪽은 실제 잔액이다. */
  forecastStart?: number;
  todayIndex?: number;
  selected: number;
  onSelect: (index: number) => void;
  /**
   * 곡선 위에 이름 붙여 짚어 둘 구간들 (정산 공백). 원본 인덱스로 센다.
   *
   * 곡선이 0 원 아래로 내려간다는 것은 색으로 이미 말하지만, <b>어디부터 어디까지가 한 건인지</b>
   * 는 색이 말해 주지 않는다. 띠로 묶어야 두 건이 붙어 있을 때 갈라 볼 수 있다.
   */
  spans?: { from: number; to: number; label: string }[];
  /** 짚어 둔 구간 하나. 띠와 이름이 진해진다. */
  activeSpan?: number | null;
  /** 띠를 누르면 부른다. 같은 띠를 다시 누르면 `null` 이 온다. */
  onPickSpan?: (index: number | null) => void;
  /**
   * 클릭할 때마다 오르는 값. 같은 날을 다시 눌러 고정을 푸는 길이다.
   * 주면 <b>이 값이 오를 때만</b> 고정한다 — selected 는 데이터가 들어올 때나 「오늘로 이동」
   * 에서도 바뀌어, 그것까지 클릭으로 세면 아무것도 안 눌렀는데 오늘이 박힌다.
   * 안 주면 selected 가 바뀔 때 고정한다.
   */
  pickSeq?: number;
}) {
  const id = useId().replace(/:/g, '');
  const [hover, setHover] = useState<number | null>(null);
  /** 말풍선을 손으로 내린 상태. 다른 날을 고르면 도로 올라온다. */
  const [dismissed, setDismissed] = useState(false);
  /**
   * 클릭으로 못 박은 날. 여기 값이 있으면 **마우스를 올려도 말풍선이 안 움직인다** —
   * 고른 날의 값을 읽는 중에 커서가 스치기만 해도 숫자가 바뀌면 읽을 수가 없다.
   *
   * 규칙은 곡선이든 날짜 카드든 하나다 — **같은 날을 다시 누르면 풀고, 다른 날을 누르면
   * 그 날에 박는다.** 곳마다 규칙이 다르면 카드가 「풀기」를 표현할 방법이 없다.
   * 곡선·날짜 카드 바깥을 눌러도 풀리고, 풀린 동안에는 호버가 말풍선을 끌고 다닌다.
   */
  const [pinned, setPinned] = useState<number | null>(null);
  /**
   * 마지막으로 본 고르기. 마운트 때 들어오는 값은 클릭이 아니다 — 그걸로 못 박으면 처음부터 호버가
   * 죽는다. 「첫 실행은 건너뛴다」로 풀면 StrictMode 가 effect 를 두 번 돌릴 때 두 번째에 박힌다.
   * 값이 실제로 바뀌었을 때만 반응해야 몇 번을 돌아도 같다.
   */
  const lastPick = useRef({ selected, pickSeq });

  const svgRef = useRef<SVGSVGElement>(null);
  /** 보이는 날짜 구간. `null` 이면 전체다. 원본 배열의 인덱스로 센다. */
  const [span, setSpan] = useState<{ from: number; to: number } | null>(null);
  /** 끌기 시작한 자리와 그때의 구간. 시작점을 기준으로 재야 끌수록 오차가 쌓이지 않는다. */
  const drag = useRef<{ x: number; from: number; to: number } | null>(null);
  /** 방금 끈 것인지. 끌고 손을 떼면 클릭이 뒤따라오는데, 그것까지 날짜 선택으로 먹으면 안 된다. */
  const dragged = useRef(false);
  const total = points.length;

  useEffect(() => {
    const el = svgRef.current;
    if (!el || !zoomable || total <= ZOOM_MIN) return undefined;
    // React 의 onWheel 은 passive 라 preventDefault 가 먹지 않는다. 직접 달아야 한다.
    const onWheel = (event: WheelEvent) => {
      const out = event.deltaY > 0;
      // 그래프 위의 휠은 <b>언제나</b> 그래프의 것이다. 전체까지 넓힌 뒤 한 번 더 굴리면
      // 페이지가 따라 내려가 버려, 넓히려던 손짓이 화면을 옮기는 손짓이 됐다.
      // 페이지를 움직이려면 곡선 밖으로 커서를 내면 된다.
      event.preventDefault();
      const now = span ?? { from: 0, to: total - 1 };
      const size = now.to - now.from + 1;
      const next = Math.max(ZOOM_MIN, Math.min(total, Math.round(size * (out ? 1.25 : 0.8))));
      if (next === size) return;
      if (next >= total) {
        setSpan(null);
        return;
      }
      // 커서가 가리키는 날을 제자리에 두고 좁힌다. 가운데 기준으로 줄이면 보고 있던 구간이
      // 화면 밖으로 밀려난다.
      const box = el.getBoundingClientRect();
      const at = Math.min(1, Math.max(0, (event.clientX - box.left) / box.width));
      const anchor = now.from + at * (size - 1);
      const start = Math.max(0, Math.min(total - next, Math.round(anchor - at * (next - 1))));
      setSpan({ from: start, to: start + next - 1 });
    };
    el.addEventListener('wheel', onWheel, { passive: false });
    return () => el.removeEventListener('wheel', onWheel);
  }, [zoomable, total, span]);

  // 곡선·날짜 카드 바깥을 누르면 말풍선을 내린다. 카드를 누르면 selected 가 바뀌어 아래가 되돌린다.
  useEffect(() => {
    const away = (event: Event) => {
      const target = event.target as HTMLElement | null;
      if (target?.closest('.balance-chart') || target?.closest('.day-cards')) return;
      setDismissed(true);
      setPinned(null);
    };
    // Esc 도 바깥 클릭과 같다. 날짜 카드로 고정했으면 초점이 곡선에 없으니 문서에서 받는다.
    const escape = (event: globalThis.KeyboardEvent) => {
      if (event.key !== 'Escape') return;
      setDismissed(true);
      setPinned(null);
    };
    document.addEventListener('pointerdown', away);
    document.addEventListener('keydown', escape);
    return () => {
      document.removeEventListener('pointerdown', away);
      document.removeEventListener('keydown', escape);
    };
  }, []);
  useEffect(() => {
    const last = lastPick.current;
    lastPick.current = { selected, pickSeq };
    const clicked = pickSeq === undefined ? last.selected !== selected : last.pickSeq !== pickSeq;
    if (!clicked) return;
    setPinned((prev) => (prev === selected ? null : selected));
    setDismissed(false);
    // selected 가 그대로여도(같은 날 재클릭) 돌아야 해서 pickSeq 를 함께 본다.
  }, [selected, pickSeq]);

  const container = useRef<HTMLDivElement>(null);
  const [roomyWidth, setRoomyWidth] = useState(810);
  useEffect(() => {
    if (!roomy || !container.current) return;
    // 늦추지 않는다. 지갑을 끌면 바깥 유리창은 매 프레임 자라는데 여기만 뒤늦게
    // 따라잡으면 둘의 타이밍이 갈려 어색하다 — 같은 레이아웃 패스에서 함께 움직인다.
    const observer = new ResizeObserver(([entry]) => setRoomyWidth(Math.max(240, entry.contentRect.width)));
    observer.observe(container.current);
    return () => observer.disconnect();
  }, [roomy]);

  /**
   * `roomy` 는 viewBox 를 `W = 잰 폭` 으로 두므로 <b>그려지는 높이가 곧 H 다</b> —
   * 다른 갈래처럼 폭에 따라 줄지 않는다. 400 일 때 메인 판(741px)의 절반을 넘게 먹어
   * 1366x768 에서 카드가 한 화면에 안 들어갔다(판 741px · 82px 모자람). 위아래 여백 50 을 빼면 곡선이 놓이는
   * 자리는 250px 이고, 좁은 갈래(260)와 거의 같다.
   *
   * 이 값은 아래 svg 에 픽셀로도 박는다. `height: auto` 로 두면 높이가
   * `(H / W) × 실제폭` 인데, `roomyWidth` 는 ResizeObserver 가 넣는 state 라 한 렌더
   * 늦다 — 지갑을 끄는 동안 실제 폭은 매 프레임 바뀌고 W 는 직전 값이라 그 차이만큼
   * 높이가 흔들린다. 박아 두면 세로로는 들썩이지 않는다.
   */
  const W = roomy ? roomyWidth : narrow ? 560 : 810,
    H = roomy ? 300 : narrow ? 260 : compact ? 205 : 208,
    left = 0,
    right = 72,
    top = 20,
    bottom = 30;

  if (points.length === 0) return null;

  // 확대는 x 좌표 계산에만 반영한다 — 보이는 구간을 원본 인덱스로 들고, x() 가 그 구간을
  // 화면 폭에 편다. 배열을 잘라 다시 번호를 매기면 selected·todayIndex 가 가리키는 곳이
  // 어긋난다.
  const from = Math.max(0, Math.min(span?.from ?? 0, total - 1));
  const to = Math.max(from, Math.min(span?.to ?? total - 1, total - 1));
  const visible = points.slice(from, to + 1);
  const inView = (index: number) => index >= from && index <= to;
  const visibleCompare = compare?.filter((d) => inView(d.index));

  // 눈금은 보이는 구간에서 만든다. 전체로 잡으면 확대해도 세로가 그대로라 달라진 것이 없다.
  //
  // <b>비교 곡선도 같이 재야 한다.</b> 본선만 보고 눈금을 잡으면, 큰 금액을 넣어 가정 곡선이
  // 크게 벌어질 때 그 곡선이 그림 밖으로 나가 버린다 — svg 가 overflow: visible 이라 화면까지 밀고 나간다.
  const values = [...visible, ...(visibleCompare ?? [])].flatMap((p) => [p.min, p.max, p.value]);
  // 안전선도 그림 안에 들어와야 한다 — 기준선이 화면 밖이면 무엇을 넘었는지 볼 수 없다.
  const reach = safetyLine === null ? values : [...values, safetyLine];
  const dataMax = Math.max(...reach);
  const dataMin = Math.min(...reach);
  const pad = Math.max((dataMax - dataMin) * 0.12, 100_000);

  /**
   * 0 을 넘나들고 <b>짧은 쪽이 긴 쪽의 20% 이상</b>일 때만 위아래를 같은 크기로 편다.
   *
   * 0 은 잔액이 바닥나는 선이라 위쪽 100만원과 아래쪽 100만원이 같은 거리로 보여야 한다 —
   * 한쪽만 길면 부족한 쪽이 실제보다 얕아 보인다. 다만 <b>살짝만 넘은 곡선까지</b> 대칭으로
   * 만들면 없는 쪽을 거울처럼 벌리게 되어 그림의 절반이 비고 곡선은 그만큼 납작해진다.
   * 0 을 아예 넘지 않을 때도 같은 이유로 있는 쪽만 편다.
   */
  const MIRROR_FLOOR = 0.2;
  const up = Math.max(dataMax, 0);
  const down = Math.max(-dataMin, 0);
  const mirrored = Math.min(up, down) >= Math.max(up, down) * MIRROR_FLOOR && up > 0 && down > 0;
  const half = Math.max(up, down) + pad;
  const upper = mirrored ? half : dataMax > 0 ? dataMax + pad : 0;
  const lower = mirrored ? -half : dataMin < 0 ? dataMin - pad : 0;

  // 눈금 4단. 자릿수가 큰 쪽에 맞춰 100만 단위로 끊는다.
  const step = Math.max(1_000_000, Math.round((upper - lower) / 4 / 1_000_000) * 1_000_000);
  // 위아래를 눈금의 배수로 맞춘다. 여백을 그냥 더하면 맨 아래 눈금선 아래에 눈금도 값도 없는
  // 띠가 남아, 그림의 가장 낮은 곳이 얼마인지를 읽을 수 없다. 맞추면 맨 위·맨 아래 선이
  // 그림 가장자리에 붙고 둘 다 값을 달고 나온다. floor(-x) = -ceil(x) 라 대칭도 그대로다.
  const min = Math.floor(lower / step) * step;
  // 잔액이 내내 0 원이면 위아래가 같아져 나눗셈이 깨진다. 최소 한 칸은 벌려 둔다.
  const max = Math.max(Math.ceil(upper / step) * step, min + step);

  /** 원본 인덱스를 받아 보이는 구간 기준으로 편다. */
  const x = (index: number) =>
    to === from ? left : left + ((index - from) / (to - from)) * (W - right - left);
  const y = (value: number) => top + ((max - value) / (max - min)) * (H - top - bottom);

  // 축 눈금 자리는 51px 뿐이라 '−1억 800만'이 잘린다. 눈금만 억을 소수로 줄인다(−1.08억).
  const axisMoney = (value: number) =>
    Math.abs(value) >= 100_000_000
      ? `${value < 0 ? '−' : ''}${Number((Math.abs(value) / 100_000_000).toFixed(2))}억`
      : shortMoney(value);

  const line = (list: Point[], field: 'value' | 'min' | 'max') =>
    list.map((d, i) => `${i ? 'L' : 'M'}${x(d.index)},${y(d[field])}`).join(' ');
  const forecastPoints =
    forecastStart > 0 ? visible.filter((d) => d.index >= forecastStart - 1) : visible;
  const actualPoints = forecastStart > 0 ? visible.filter((d) => d.index < forecastStart) : [];
  const area = `${line(forecastPoints, 'max')} ${forecastPoints
    .slice()
    .reverse()
    .map((d) => `L${x(d.index)},${y(d.min)}`)
    .join(' ')} Z`;

  /**
   * 화면에 실제로 걸치는 띠만 남기고 좌우를 잘라 둔다. 하루를 칸의 가운데로 그리므로
   * 양끝을 반 칸씩 넓혀야 첫날과 마지막 날이 띠 안에 온전히 들어온다.
   */
  const halfCol = (W - right) / Math.max(1, visible.length) / 2;
  const visibleSpans = (spans ?? [])
    .map((span, i) => ({ span, i }))
    .filter(({ span }) => span.to >= from && span.from <= to)
    .map(({ span, i }) => ({
      span,
      i,
      x1: Math.max(0, x(Math.max(span.from, from)) - halfCol),
      x2: Math.min(W - right, x(Math.min(span.to, to)) + halfCol),
    }));

  const active = Math.max(from, Math.min(pinned ?? hover ?? selected, to));
  const point = points[active];
  const isForecast = active >= forecastStart;
  /**
   * 고정 중에 커서가 가리키는 날. 말풍선은 고정한 날에 두고, 여기는 흐린 선과 한 줄만 띄운다 —
   * 아무것도 안 보이면 눌러 보기 전에는 어디로 옮겨 갈지 알 수 없다.
   * 차이는 예측 가운데 값끼리 뺀 것이라 예측이 끼면 「약」을 붙인다(도메인 규칙 1).
   */
  const ghost = pinned !== null && hover !== null && hover !== active && inView(hover) ? hover : null;
  const ghostLabel = (() => {
    if (ghost === null) return '';
    const diff = points[ghost].value - point.value;
    const rough = isForecast || ghost >= forecastStart ? '약 ' : '';
    const amount = diff === 0 ? '같음' : `${rough}${diff > 0 ? '+' : ''}${formatManwon(diff)}`;
    return `${points[ghost].date} · 고정일 대비 ${amount}`;
  })();
  const isToday = active === todayIndex;

  /** 강조 중이면 나머지 곡선을 거의 지운다. 흐리게만 두면 어느 쪽을 고른 건지 애매하다. */
  const dimmed = (which: 'main' | 'compare') => (focused && focused !== which ? 0.1 : 1);

  const hue = (value: number) =>
    value < 0
      ? 'var(--w-rain)'
      : safetyLine !== null && value < safetyLine
        ? 'var(--w-cloud)'
        : 'var(--w-sun)';

  // 짚은 날의 점과 값 상자가 같은 목록을 쓴다. 곡선이 둘이면 둘 다 찍고, 색은 그 곡선의
  // 색을 따른다 — 하나만 찍으면 어느 쪽 값인지 알 수 없고, 두 점 사이가 곧 그날의 차이다.
  const comparePoint = compare?.find((d) => d.index === active);
  const mainDot = {
    at: point.value,
    min: point.min,
    max: point.max,
    color: mainColor ?? hue(point.value),
    label: mainLabel,
    which: 'main' as const,
  };
  const compareDot = comparePoint && {
    at: comparePoint.value,
    min: comparePoint.min,
    max: comparePoint.max,
    color: compareAccent ? 'var(--lavender)' : 'var(--muted)',
    label: compareLabel,
    which: 'compare' as const,
  };
  /** 읽는 순서. 본선이 먼저다. */
  const dots = compareDot ? [mainDot, compareDot] : [mainDot];
  /** 곡선이 하나면 말풍선을 고른 날 위로 띄운다. 가장자리에서 잘리지 않게 11~81% 로 가둔다. */
  const floating = dots.length === 1;
  const tooltipLeft = Math.min(81, Math.max(11, (x(active) / W) * 100));
  /** 그리는 순서. 곡선 순서와 같다 — 기준선으로 까는 비교 곡선은 본선 아래다. */
  const painted = compareDot && !compareAccent ? [compareDot, mainDot] : dots;

  const ticks: number[] = [];
  for (let v = min; v <= max; v += step) ticks.push(v);

  function handleKey(event: KeyboardEvent<SVGSVGElement>) {
    if (event.key === 'ArrowRight' || event.key === 'ArrowLeft') {
      event.preventDefault();
      // 확대 중이면 보이는 구간 밖으로 나가지 않는다 — 안 보이는 날을 고르면 선택점이 사라진다.
      onSelect(Math.max(from, Math.min(to, active + (event.key === 'ArrowRight' ? 1 : -1))));
    }
    if (event.key === 'Home') {
      event.preventDefault();
      onSelect(from);
    }
    if (event.key === 'End') {
      event.preventDefault();
      onSelect(to);
    }
  }

  return (
    <div ref={container} className={`balance-chart ${compact ? 'compact' : ''} ${roomy ? 'balance-chart--roomy' : ''}`}>
      {/* 짚은 날의 값. 마우스를 올렸을 때만 띄우면 그만한 상자가 드나들며 화면을 밀어낸다.
        * 안내선과 점은 이미 고른 날을 늘 가리키고 있으니, 그 날의 금액도 늘 적혀 있으면 된다. */}
      {(!floating || !dismissed || hover !== null) && (
      <div
        className={`chart-tooltip ${floating ? 'chart-tooltip--float' : ''}`}
        style={floating ? { left: `${tooltipLeft}%` } : undefined}
      >
        <span className="chart-tooltip__when">
          {point.date} ({point.weekday})
          <small>
            {isToday
              ? '현재 잔액'
              : isForecast
                ? '예상 범위 · 확정 금액이 아닙니다'
                : '실제 일 마감 잔액'}
          </small>
        </span>
        {dots.map((d) => (
          <span
            key={d.which}
            className="chart-tooltip__read"
            style={{ opacity: dimmed(d.which) }}
          >
            <i style={{ background: d.color }} />
            {d.label && <em>{d.label}</em>}
            <strong>
              {isForecast ? formatManwonRange(d.min, d.max) : formatMoney(d.at)}
            </strong>
          </span>
        ))}
      </div>
      )}

      <svg
        ref={svgRef}
        viewBox={`0 0 ${W} ${H}`}
        // 위 H 주석 참고. 인라인이라 styles.css 의 `height: auto` 를 덮는다 —
        // 숫자가 한 곳에만 있어 둘이 어긋날 길이 없다.
        style={roomy ? { height: H } : undefined}
        role="slider"
        aria-label={
          zoomable
            ? '과거 실제 잔액과 미래 예상 잔액. 좌우 방향키로 날짜 선택, 휠로 확대·축소'
            : '과거 실제 잔액과 미래 예상 잔액. 좌우 방향키로 날짜 선택'
        }
        aria-valuemin={from + 1}
        aria-valuemax={to + 1}
        aria-valuenow={active + 1}
        aria-valuetext={
          isForecast
            ? `${point.date}, ${money(point.min)}~${money(point.max)}원 예상`
            : `${point.date}, ${money(point.value)}원 실제 잔액`
        }
        tabIndex={0}
        // 확대한 뒤에만 끌 수 있다. 전체가 보이는데 끌면 갈 곳이 없다.
        // 쥐는 손 모양은 CSS 로 준다 — 끄는 중인지는 ref 에만 있고, ref 는 그릴 때 읽지 않는다.
        className={span && zoomable ? 'can-pan' : undefined}
        onPointerDown={(event) => {
          if (!span || !zoomable || event.button !== 0) return;
          // 누른 자리에서 글자 선택이 시작되는 것을 막는다. 막지 않으면 끄는 동안 곡선 아래
          // 문단까지 파랗게 잡힌다. 다만 기본 동작을 막으면 초점도 안 오므로 직접 준다 —
          // 방향키로 날짜를 옮기는 길이 끊기면 안 된다.
          event.preventDefault();
          event.currentTarget.focus();
          drag.current = { x: event.clientX, from: span.from, to: span.to };
          dragged.current = false;
          event.currentTarget.setPointerCapture(event.pointerId);
        }}
        onPointerMove={(event) => {
          const start = drag.current;
          if (!start) return;
          const dx = event.clientX - start.x;
          // 4px 까지는 흔들림으로 본다. 누르다 손이 조금 밀린 것을 끌기로 읽으면 날짜를 못 고른다.
          if (!dragged.current && Math.abs(dx) < 4) return;
          dragged.current = true;
          const box = event.currentTarget.getBoundingClientRect();
          const size = start.to - start.from + 1;
          // 왼쪽으로 끌면 뒷날이 온다 — 종이를 미는 것과 같은 방향이다.
          const shift = Math.round((-dx / box.width) * size);
          const from = Math.max(0, Math.min(total - size, start.from + shift));
          setSpan({ from, to: from + size - 1 });
        }}
        onPointerUp={() => {
          drag.current = null;
        }}
        onPointerCancel={() => {
          drag.current = null;
          dragged.current = false;
        }}
        // 끌고 난 뒤의 클릭은 삼킨다. 날짜를 고르거나 구간을 여는 것은 제자리에서 누른 것만이다.
        onClickCapture={(event) => {
          if (!dragged.current) return;
          dragged.current = false;
          event.stopPropagation();
        }}
        onKeyDown={handleKey}
        // 날짜 칸(rect)에만 맡기면 곡선을 집는 띠(아래 transparent stroke)가 그 위에 깔려
        // 있어, 선 위에 마우스를 올리면 칸이 호버를 받지 못해 점이 멈췄다. svg 에서 재면
        // 무엇이 위에 깔리든 상관없다.
        onMouseMove={(event) => {
          if (to === from) return setHover(from);
          const box = event.currentTarget.getBoundingClientRect();
          const units = ((event.clientX - box.left) / box.width) * W;
          const ratio = (units - left) / (W - right - left);
          setHover(Math.max(from, Math.min(to, from + Math.round(ratio * (to - from)))));
        }}
        onMouseLeave={() => setHover(null)}
        onDoubleClick={() => setSpan(null)}
      >
        <defs>
          <linearGradient id={`band-${id}`} x1="0" y1="0" x2="0" y2="1">
            <stop offset="0%" style={{ stopColor: 'var(--band)' }} stopOpacity=".2" />
            <stop offset="100%" style={{ stopColor: 'var(--band)' }} stopOpacity=".1" />
          </linearGradient>
          <linearGradient id={`actual-${id}`}>
            {actualPoints.map((d, i) => (
              <stop
                key={d.index}
                offset={`${(i / Math.max(1, actualPoints.length - 1)) * 100}%`}
                style={{ stopColor: hue(d.value) }}
              />
            ))}
          </linearGradient>
          <linearGradient id={`line-${id}`}>
            {forecastPoints.map((d, i) => (
              <stop
                key={d.index}
                offset={`${(i / Math.max(1, forecastPoints.length - 1)) * 100}%`}
                style={{ stopColor: hue(d.value) }}
              />
            ))}
          </linearGradient>
        </defs>

        {ticks.map((value) => (
          <g key={value}>
            <line
              className={value === 0 ? 'zero-line' : 'grid-line'}
              x1={0}
              y1={y(value)}
              x2={W - right + 8}
              y2={y(value)}
            />
            <text className="axis-label" x={W - right + 21} y={y(value) + 4}>
              {axisMoney(value)}
            </text>
          </g>
        ))}

        {safetyLine !== null && (
          <>
            <line
              className="safety-line"
              x1={0}
              y1={y(safetyLine)}
              x2={W - right + 8}
              y2={y(safetyLine)}
            />
            {/* 선의 오른쪽 끝에 붙인다. 왼쪽(x=0)에 두면 과거 곡선이 지나가며 글자를 덮는다. */}
            <text className="safety-label" textAnchor="end" x={W - right + 8} y={y(safetyLine) - 9}>
              안전선 {formatManwon(safetyLine)}
            </text>
          </>
        )}

        {/* 구간 띠. 곡선 <b>뒤</b>에 깔아야 곡선이 띠에 덮이지 않는다. 누르는 자리는 날짜 칸
          * 뒤에 따로 둔다 — 띠 위에서 누른 것이 칸에 먼저 먹히면 구간을 고를 수 없다. */}
        {visibleSpans.map(({ span, i, x1, x2 }) => (
          <g key={i} opacity={activeSpan === null || activeSpan === i ? 1 : 0.45}>
            <rect
              className={`gap-span ${activeSpan === i ? 'is-on' : ''}`}
              x={x1}
              y={top}
              width={x2 - x1}
              height={H - bottom - top}
            />
            <text className="gap-span-label" x={(x1 + x2) / 2} y={top + 12} textAnchor="middle">
              {span.label}
            </text>
          </g>
        ))}
        <path d={area} fill={`url(#band-${id})`} />
        {/* 범위 위·아래 경계. 폭이 좁은 날에도 범위가 있다는 것이 보이게 한다. */}
        {(['max', 'min'] as const).map((field) => (
          <path
            key={field}
            d={line(forecastPoints, field)}
            fill="none"
            style={{ stroke: 'var(--band)' }}
            strokeOpacity=".35"
            strokeWidth="1"
          />
        ))}
        {/* 기준선으로 쓰는 비교 곡선은 본선 아래에 깐다. 강조하는 경우(시나리오의 가정
          * 반영)는 본선 위에 얹어야 겹치는 구간에서 가려지지 않는다. */}
        {visibleCompare && visibleCompare.length > 0 && !compareAccent && (
          <path
            d={line(visibleCompare!, 'value')}
            fill="none"
            style={{ stroke: 'var(--muted)' }}
            strokeWidth="1.5"
            strokeDasharray="5 5"
          />
        )}
        {actualPoints.length > 1 && (
          <path
            d={line(actualPoints, 'value')}
            fill="none"
            stroke={`url(#actual-${id})`}
            strokeWidth="2.8"
            strokeLinejoin="round"
            strokeLinecap="round"
          />
        )}
        <path
          d={line(forecastPoints, 'value')}
          fill="none"
          stroke={mainColor ?? `url(#line-${id})`}
          strokeWidth="2.8"
          /* 가정 반영 곡선을 도드라지게 하는 방법이다 — <b>가정을 키우는 대신 원본을 낮춘다.</b>
           * 굵게 하거나 번지게 하면 화면이 시끄러워지는데, 두 곡선이 붙은 구간에서는 오히려
           * 경계가 뭉개진다. 낮추는 쪽은 조용해지면서도 어느 선이 새로 그린 것인지가 분명하고,
           * 색이 아니라 밝기로도 갈려 도메인 규칙 6 을 지킨다.
           *
           * <b>낮추는 정도는 배합마다 다르다</b>(--main-fade). 흰 바탕에서는 어두운 배합의
           * 값이 예상 범위 띠에 그대로 묻힌다 — 그래서 여기 숫자로 박지 않고 토큰을 쓴다.
           * 속성 대신 style 로 주는 것도 그 때문이다. 표현 속성은 var() 를 못 받는다.
           *
           * <b>원본만 남기고 본 경우에는 낮추지 않는다.</b> 일부러 원본을 고른 사람에게
           * 그 선을 흐리게 보여 줄 이유가 없다. */
          style={{
            strokeOpacity:
              compareAccent && focused !== 'main'
                ? `calc(var(--main-fade) * ${dimmed('main')})`
                : String(dimmed('main')),
          }}
          strokeLinejoin="round"
          strokeLinecap="round"
        />
        {visibleCompare && visibleCompare.length > 0 && compareAccent && (
          <g opacity={dimmed('compare')}>
            {/*
              * 왼쪽에서 오른쪽으로 그려진다. `pathLength="1"` 로 길이를 1 로 재어 두면
              * 곡선이 길든 짧든 같은 시간에 그려진다.
              *
              * 멈춘 상태를 <b>다 그려진 쪽</b>(dashoffset 0)으로 두는 것이 중요하다.
              * 움직임을 줄이는 설정이면 base.css 가 animation 을 통째로 걷어내는데, 멈춘
              * 상태가 0 이 아니면 곡선이 아예 안 보이게 된다.
              */}
            <path
              className="compare-draw"
              pathLength={1}
              d={line(visibleCompare!, 'value')}
              fill="none"
              style={{ stroke: 'var(--lavender)' }}
              strokeWidth="2.4"
              strokeLinejoin="round"
              strokeLinecap="round"
            />
          </g>
        )}

        {todayIndex !== undefined && inView(todayIndex) && (
          <>
            <line
              className="today-line"
              x1={x(todayIndex)}
              y1={top}
              x2={x(todayIndex)}
              y2={H - bottom}
            />
            <text className="today-label" x={x(todayIndex)} y={top + 10} textAnchor="middle">
              오늘
            </text>
          </>
        )}

        <line
          x1={x(active)}
          y1={top}
          x2={x(active)}
          y2={H - bottom}
          style={{ stroke: 'var(--accent-dim)' }}
          strokeOpacity=".35"
          strokeDasharray="3 5"
        />
        {ghost !== null && (
          <>
            <line className="ghost-line" x1={x(ghost)} y1={top} x2={x(ghost)} y2={H - bottom} />
            {/* 아래쪽에 적는다. 위쪽은 고정한 말풍선이 떠 있어 겹친다. 가장자리에서 잘리지 않게
              * 곡선 칸의 오른쪽 절반이면 왼쪽으로 편다. */}
            <text
              className="ghost-label"
              x={x(ghost) + (x(ghost) > (W - right) / 2 ? -8 : 8)}
              y={H - bottom - 8}
              textAnchor={x(ghost) > (W - right) / 2 ? 'end' : 'start'}
            >
              {ghostLabel}
            </text>
          </>
        )}
        {painted.map((d) => (
          <g key={d.which} opacity={dimmed(d.which)}>
            <circle cx={x(active)} cy={y(d.at)} r={10} style={{ fill: d.color }} fillOpacity=".2" />
            <circle
              cx={x(active)}
              cy={y(d.at)}
              r={4.5}
              style={{ fill: d.color, stroke: 'var(--dot-ring)' }}
              strokeWidth="2"
            />
          </g>
        ))}

        {visible.map((d) => (
          <rect
            key={d.index}
            x={Math.max(0, x(d.index) - (W - right) / visible.length / 2)}
            y={0}
            width={(W - right) / visible.length + 1}
            height={H - bottom}
            fill="transparent"
            style={onExpand ? { cursor: focused ? 'default' : 'zoom-in' } : undefined}
            onClick={() => {
              // 강조 중이면 배경 클릭은 "원상태로" 다. 그때까지 확대는 잠시 물러난다 —
              // 한 번의 클릭에 두 가지 뜻을 담으면 어느 쪽이 일어날지 예측할 수 없다.
              if (focused && onFocusCurve) return onFocusCurve(null);
              if (onExpand) return onExpand();
              onSelect(d.index);
            }}
          >
            <title>
              {d.date}: {money(d.value)}원
            </title>
          </rect>
        ))}

        {/* 구간을 누르는 자리. 그림은 곡선 뒤에 깔았고 클릭은 여기서 받는다 */}
        {onPickSpan &&
          visibleSpans.map(({ i, x1, x2 }) => (
            <rect
              key={i}
              x={x1}
              y={top}
              width={x2 - x1}
              height={H - bottom - top}
              fill="transparent"
              style={{ cursor: 'pointer' }}
              onClick={(event) => {
                event.stopPropagation();
                onPickSpan(activeSpan === i ? null : i);
              }}
            />
          ))}

        {/* 선을 집는 띠. 날짜 칸(위 rect) 보다 뒤에 그려야 선 위에서의 클릭이 칸에 먼저
          * 먹히지 않는다. 눈에 보이지 않게 두되 stroke 위에서만 클릭을 받는다. */}
        {onFocusCurve && (
          <>
            <path
              d={line(forecastPoints, 'value')}
              fill="none"
              stroke="transparent"
              strokeWidth="14"
              style={{ cursor: 'pointer', pointerEvents: 'stroke' }}
              onClick={() => onFocusCurve(focused === 'main' ? null : 'main')}
            />
            {visibleCompare && visibleCompare.length > 0 && (
              <path
                d={line(visibleCompare!, 'value')}
                fill="none"
                stroke="transparent"
                strokeWidth="14"
                style={{ cursor: 'pointer', pointerEvents: 'stroke' }}
                onClick={() => onFocusCurve(focused === 'compare' ? null : 'compare')}
              />
            )}
          </>
        )}

        {visible
          .filter(
            (_, i) =>
              i === 0 ||
              i === visible.length - 1 ||
              (i < visible.length - 2 && i % Math.max(1, Math.round(visible.length / 5)) === 0),
          )
          .map((d) => (
            <text
              key={d.index}
              className={`date-label ${d.index === active ? 'active' : ''}`}
              x={x(d.index)}
              y={H - 6}
              textAnchor={d.index === from ? 'start' : 'middle'}
            >
              {d.index === todayIndex ? '오늘' : d.label}
            </text>
          ))}

        {/* 확대 중일 때만 — 며칠 치의 날을 보고 있는지와 되돌리는 방법을 적어둔다.
          * 안내가 없으면 좌표가 달라진 이유도, 전체로 돌아가는 길도 알 수 없다. */}
        {span && (
          <text className="zoom-hint" x={2} y={11}>
            {visible.length}일 보는 중 · 더블클릭하면 전체
          </text>
        )}
      </svg>
    </div>
  );
}
