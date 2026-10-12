// 시드 콘솔 화면. 조정할 수 있는 값의 목록은 schema.mjs 에만 있고 여기는 그리기만 한다.
//
// 화면이 붙드는 것은 설정 한 벌(기준일 · 계정 · 계좌 · 파라미터)뿐이다. 값이 바뀔 때마다
// 서버에 곡선을 다시 물어보고 <b>판정을 왼쪽에 고정해 둔다</b> — 스크립트로 할 때 번거로웠던
// 게 정확히 "지금 이 값으로 공백이 남아 있나"를 매번 다시 확인하는 일이었다.

import {
  ACCOUNT_COLUMNS, BUSINESS_FIELDS, CALENDAR_FIELDS, EXPENSE_CATEGORIES, FIXED_TABLE,
  INCOME_TABLE, IRREGULAR_TABLE, MATERIALS_FIELDS, OWNER_FIELDS, PRODUCT_TABLE, SCALAR_GROUPS,
  STEPS, TABLES, TAX_FIELDS, WEEKLY_TABLE,
  dowName, expandSteps, getAt, setAt,
} from './schema.mjs';

// ── DOM 만들기 ──────────────────────────────────────────────────────────

/** innerHTML 을 쓰지 않는다. 서버 응답에 뭐가 들어와도 그대로 문자로 남는다. */
function el(tag, props = {}, children = []) {
  const node = document.createElement(tag);
  apply(node, props);
  append(node, children);
  return node;
}

const SVG_NS = 'http://www.w3.org/2000/svg';

function sv(tag, props = {}, children = []) {
  const node = document.createElementNS(SVG_NS, tag);
  for (const [key, value] of Object.entries(props)) {
    if (value === null || value === undefined || value === false) continue;
    if (key === 'text') node.textContent = value;
    else node.setAttribute(key, String(value));
  }
  append(node, children);
  return node;
}

function apply(node, props) {
  for (const [key, value] of Object.entries(props)) {
    if (value === null || value === undefined || value === false) continue;
    if (key === 'class') node.className = value;
    else if (key === 'text') node.textContent = value;
    else if (key === 'html') throw new Error('html 은 쓰지 않는다');
    else if (key === 'value') node.value = value;
    else if (key === 'checked') node.checked = Boolean(value);
    else if (key.startsWith('on')) node.addEventListener(key.slice(2), value);
    else node.setAttribute(key, value === true ? '' : String(value));
  }
}

function append(node, children) {
  for (const child of [children].flat(4)) {
    if (child === null || child === undefined || child === false) continue;
    node.append(typeof child === 'object' ? child : document.createTextNode(String(child)));
  }
}

const $ = (id) => document.getElementById(id);

/** replaceChildren 은 Node 가 아닌 인자를 텍스트로 붙인다 — null 이 그대로 "null" 로 찍힌다. */
function fill(node, ...children) {
  node.replaceChildren(...children.flat(4).filter((c) => c !== null && c !== undefined && c !== false));
}

/**
 * 스키마의 hint 에 <b>가 들어 있다. innerHTML 을 안 쓰기로 했으므로 직접 쪼갠다 —
 * 태그 하나 때문에 규칙을 깨면 다음 사람이 어디까지 안전한지 모르게 된다.
 */
function richText(text) {
  return String(text)
    .split(/(<b>.*?<\/b>)/g)
    .filter(Boolean)
    .map((part) => (part.startsWith('<b>')
      ? el('b', { text: part.slice(3, -4) })
      : document.createTextNode(part)));
}

// ── 표기 ────────────────────────────────────────────────────────────────

const MINUS = '−'; // 하이픈은 글꼴에서 붙임표로 읽힌다

/** 1340000 → 1,340,000원. 음수는 −1,340,000원. */
function won(value) {
  const n = Number(value);
  if (!Number.isFinite(n)) return '—';
  return `${n < 0 ? MINUS : ''}${Math.abs(n).toLocaleString('ko-KR')}원`;
}

function num(value) {
  const n = Number(value);
  if (!Number.isFinite(n)) return '—';
  return `${n < 0 ? MINUS : ''}${Math.abs(n).toLocaleString('ko-KR')}`;
}

/** 0.237 → 23.7%. 음수는 빼기 기호로 찍는다. */
function pct(value, digits = 1) {
  const n = Number(value);
  if (!Number.isFinite(n)) return '—';
  return `${n < 0 ? MINUS : ''}${(Math.abs(n) * 100).toFixed(digits)}%`;
}

/** 2026-09-28 → 09.28(월) */
function shortDate(iso) {
  const at = new Date(`${iso}T00:00:00Z`);
  return `${iso.slice(5, 7)}.${iso.slice(8, 10)}(${dowName(at.getUTCDay())})`;
}

// ── 상태 ────────────────────────────────────────────────────────────────

// sessionStorage 를 쓴다. 비밀번호가 섞이므로 탭을 닫으면 지워지는 쪽이 낫다.
const STORE = 'jango-seed-console';

/**
 * 기본으로 켜는 단계. 선택 둘(반복 후보 승인·자동 반영)과 되돌릴 수 없는 하나(기존 시드
 * 지우기)는 여기 없다 — 사람이 직접 켠다.
 *
 * <p>exclude 는 기본으로 켠다. 빼고 돌리면 잔액 맞추기용 입금이 평소 매출로 읽혀
 * 예측과 반복 탐지가 통째로 틀어진다 — 켜 두는 것이 안전한 쪽이다.
 *
 * <p>calendar 도 같은 이유로 켠다. 위에서 적은 「휴업일」이 CSV 에는 들어가는데 백엔드에는
 * 안 가면, 명절 주 정산 금액이 안 줄어 <b>곡선이 그만큼 후해진다</b>. 생성기가 만든 정답과
 * 예보가 갈리는 자리라 조용히 틀리는 쪽이다.
 */
const DEFAULT_STEPS = [
  'login', 'business', 'finAccounts', 'link', 'csv', 'balance', 'sync', 'exclude', 'productSales',
  'calendar',
];

/**
 * 원버튼 재주입이 고르는 단계 — <b>기본 단계에 지우기를 얹은 것</b>이다.
 *
 * <p>실행 순서는 schema 의 {@code STEPS} 배열이 정하므로 여기 차례는 상관없다. 기본 단계가
 * 늘면 이쪽도 같이 느는 것이 요점이다: 두 벌로 적어 두면 「다시 세웠는데 한 단계가 빠져
 * 있다」가 생기고, 그건 시드가 조용히 틀리는 쪽이다.
 */
const REDO_STEPS = ['reset', ...DEFAULT_STEPS];

const state = {
  view: 'curve',
  asOf: null,
  apiBase: '',
  owner: null,
  accounts: null,
  tuning: null,
  steps: [...DEFAULT_STEPS],
};

function saveState() {
  try {
    // ⚠ 비밀번호는 저장하지 않는다. 새로고침하면 다시 넣는다.
    const { owner, ...rest } = state;
    sessionStorage.setItem(STORE, JSON.stringify({
      ...rest,
      owner: { ...owner, password: '' },
      // 저장 당시 있던 단계. 뒤에 생긴 단계를 <b>사용자가 끈 것</b>과 구분하려고 남긴다 —
      // 둘 다 "steps 배열에 없음"으로 똑같이 보여서, 이게 없으면 새 단계가 조용히 빠진다.
      knownSteps: STEPS.map((s) => s.key),
    }));
  } catch {
    /* 시크릿 창 등에서 막힐 수 있다. 이번 화면만 기억하고 넘어간다. */
  }
}

/** 표 이름. 저장된 설정에서 <b>표는 통째로 쓴다</b> — 지운 줄이 되살아나면 안 된다. */
const TABLE_BASES = TABLES.map((t) => t.base);

/** 저장본에 <b>없는 키만</b> 기본값으로 채운다. 배열은 통째로 갈아 끼운다. */
function fillMissing(base, saved) {
  if (saved === undefined) return structuredClone(base);
  if (saved === null || Array.isArray(base) || Array.isArray(saved)) return saved;
  if (typeof base !== 'object' || base === null || typeof saved !== 'object') return saved;

  const out = { ...saved };
  for (const [key, value] of Object.entries(base)) out[key] = fillMissing(value, saved[key]);
  return out;
}

/**
 * 저장된 설정을 기본값 위에 얹는다. <b>없는 키만</b> 채운다 — 표를 통째로 지운 것({})과
 * 저장 당시 그 표가 아예 없던 것(undefined)은 다른 뜻이다.
 *
 * <p>이게 없으면 <b>나중에 생긴 표가 빈 채로 보인다.</b> 실제로 상품 구성을 넣은 날 그렇게
 * 됐다 — 탭을 열어 둔 사람의 {@code sessionStorage} 에 그 표가 없어서, 화면에 머리글만 남고
 * 13줄이 통째로 사라졌다(2026-09-15). 서버를 다시 띄워도 안 고쳐진다: 저장본은 탭에 있다.
 *
 * <p>표 <b>안쪽</b>도 얹는다. 수입에 수수료 칸이 늘었을 때 옛 저장본에는 그 칸이 없고,
 * 그러면 상품별 매출의 총매출 환산이 조용히 0% 수수료로 돈다.
 */
function mergeTuning(base, saved) {
  const out = fillMissing(base, saved);
  for (const table of TABLE_BASES) {
    const rows = saved?.[table];
    // 저장 당시 없던 표다. fillMissing 이 넣어 둔 기본값을 그대로 둔다.
    if (typeof rows !== 'object' || rows === null) continue;
    out[table] = Object.fromEntries(Object.entries(rows)
      .map(([key, row]) => [key, fillMissing(base[table]?.[key] ?? {}, row)]));
  }
  return out;
}

function loadState() {
  try {
    return JSON.parse(sessionStorage.getItem(STORE) ?? '{}');
  } catch {
    return {};
  }
}

let boot = null;
let last = null; // 마지막 미리보기 결과
let pending = false;

// ── 서버 호출 ───────────────────────────────────────────────────────────

async function post(path, body) {
  const res = await fetch(path, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(body),
  });
  return res.json();
}

const payload = () => ({
  asOf: state.asOf,
  tuning: state.tuning,
  owner: state.owner,
  accounts: state.accounts,
  apiBase: state.apiBase,
});

let previewTimer = null;

/**
 * 값이 바뀔 때마다 곡선을 다시 구한다. 서버가 시작 잔액을 격자로 훑느라 시간이 좀 걸려서
 * 타이핑 중에는 미룬다 — 한 글자마다 부르면 화면이 계속 「계산 중」에 머문다.
 */
function schedulePreview() {
  saveState();
  pending = true;
  renderCtx();
  clearTimeout(previewTimer);
  previewTimer = setTimeout(runPreview, 300);
}

/**
 * 미리보기 결과를 읽는 화면. 계산이 끝나면 <b>이들만</b> 다시 그린다 — 설정 폼을 다시
 * 그리면 입력 중이던 칸의 커서가 튄다.
 *
 * <p><b>{@code run} 이 빠지면 실행 버튼이 영영 죽는다.</b> 「시드 세우기」를 띄운 채로
 * 새로고침하면(탭이 마지막 화면을 기억한다) 첫 미리보기가 끝나도 그 화면이 다시 안 그려져
 * <b>{@code last} 가 {@code null} 이던 때의 화면에 머문다</b> — 「올릴 거래 —」와 비활성
 * 버튼이다. 다른 화면에 들렀다 와야 풀린다. 이 화면에는 체크박스와 버튼뿐이라 다시 그려도
 * 잃을 커서가 없다.
 */
const PREVIEW_VIEWS = new Set(['curve', 'rows', 'run']);

async function runPreview() {
  const result = await post('/api/preview', { asOf: state.asOf, tuning: state.tuning });
  pending = false;
  last = result;
  renderCtx();
  if (PREVIEW_VIEWS.has(state.view)) render();
}

// ── 왼쪽: 지금 상태 ─────────────────────────────────────────────────────

function verdict() {
  if (pending) return el('div', { class: 'verdict busy', text: '계산 중…' });
  if (!last) return el('div', { class: 'verdict busy', text: '아직 계산하지 않았다' });

  if (!last.ok) {
    return el('div', { class: 'verdict warn' }, [
      '✕ 이 설정으로는 못 만든다',
      el('div', { style: 'font-weight:500;margin-top:4px', text: last.message.split('\n')[0] }),
    ]);
  }

  // 색만으로 가르지 않는다 — 기호와 말과 숫자를 함께 붙인다(도메인 규칙 6).
  if (!last.gap) {
    return el('div', { class: 'verdict warn' }, [
      '✕ 공백 없음',
      el('div', { style: 'font-weight:500;margin-top:4px' },
        `예보 창 저점 ${won(last.low?.balance)} — 시연할 장면이 없다`),
    ]);
  }
  return el('div', { class: 'verdict good' }, [
    `✓ 공백 ${last.gap.days}일`,
    el('div', { style: 'font-weight:500;margin-top:4px' },
      `${shortDate(last.gap.from)} ~ ${shortDate(last.gap.to)} · 최저 ${won(last.low.balance)}`),
  ]);
}

function ctxRow(label, value, empty) {
  return el('div', { class: 'ctx-row' }, [
    el('span', { class: 'ctx-label', text: label }),
    el('span', { class: `ctx-value${empty ? ' empty' : ''}`, text: value }),
  ]);
}

function renderCtx() {
  fill($('ctx'),
    ctxRow('기준일', state.asOf ?? '—'),
    verdict(),
    ctxRow('시작 잔액', last?.ok ? won(last.opening) : '—', !last?.ok),
    ctxRow('기준일 잔액', last?.ok ? won(last.closingBalance) : '—', !last?.ok),
    ctxRow('과거 거래', last?.ok ? `${num(last.pastCount)}건` : '—', !last?.ok),
    ctxRow('대상 계정', state.owner?.email ?? '—'),
    ctxRow('백엔드', state.apiBase || '—'));
}

// ── 왼쪽: 목록 ──────────────────────────────────────────────────────────

const VIEWS = [
  {
    group: '보기',
    items: [
      { id: 'curve', label: '잔액 곡선', title: '잔액 곡선', note: '이 설정으로 만들면 잔액이 어떻게 움직이는지. 공백이 예보 창 안에 남아 있는지가 여기서 판정된다.' },
      { id: 'rows', label: '거래 · 합계', title: '거래와 합계', note: '만들어질 거래의 표본과 분류별 합계, 그리고 등록될 <b>상품별 매출 구성비</b>. 금액을 손봤을 때 어디가 얼마나 움직였는지 본다.' },
    ],
  },
  {
    group: '설정',
    items: [
      { id: 'params', label: '기간 · 앵커', title: '기간과 공백 앵커', note: '얼마나 긴 구간을 만들고, 공백을 어디에 얼마나 깊게 둘지. 시작 잔액은 여기서 역산된다.' },
      { id: 'items', label: '수입 · 지출 · 메뉴', title: '수입과 지출, 메뉴 구성', note: '적요·금액·날짜와 <b>메뉴별 비중</b>. 고정비가 한 날에 몰리면 월 스윙이 깊어져 매달 잔액이 마이너스로 내려간다. 메뉴 비중은 상품별 매출 구성비(P1-22)가 된다.' },
      { id: 'account', label: '계정 · 사업장 · 계좌', title: '계정과 사업장, 계좌', note: '이메일 하나로 금융망 userKey 가 정해진다. 여기서 만든 계좌가 그 계정 것이 된다.' },
    ],
  },
  {
    group: '실행',
    items: [
      { id: 'run', label: '시드 세우기', title: '시드 세우기', note: '고른 단계를 순서대로 실행한다. 전부 금융망과 우리 DB 를 실제로 바꾼다.', write: true },
      { id: 'files', label: 'CSV · 설정 파일', title: 'CSV 와 설정 파일', note: '거래 CSV, 분류 정답표, 그리고 CLI 로 넘길 설정 파일을 받는다.' },
      { id: 'direct', label: '직접 넣기', title: 'CSV 직접 넣기', note: '이미 있는 계정의 연결 계좌에 CSV 파일을 올린다. 시드 생성과 무관하게 쓴다.', write: true },
    ],
  },
];

const viewSpec = (id) => VIEWS.flatMap((g) => g.items).find((v) => v.id === id);

function renderNav() {
  fill($('nav'), VIEWS.map((group) => el('div', {}, [
    el('div', { class: 'nav-group-label', text: group.group }),
    group.items.map((item) => el('button', {
      class: 'nav-item',
      type: 'button',
      'aria-current': state.view === item.id,
      onclick: () => go(item.id),
    }, [
      el('span', { text: item.label }),
      item.write && el('span', { class: 'dot', text: '●' }),
    ])),
  ])));
}

function go(id) {
  state.view = id;
  saveState();
  renderNav();
  render();
  window.scrollTo(0, 0);
}

// ── 입력 칸 ─────────────────────────────────────────────────────────────

/**
 * 스키마 한 줄을 입력 칸으로 만든다. 값을 고치면 {@code onChange} 로 바로 올려 보내고
 * 곡선을 다시 구한다 — 「적용」 단추를 두면 누르는 걸 잊고 왜 안 바뀌냐고 한다.
 */
function input(spec, value, onChange) {
  const common = { onchange: (e) => onChange(read(spec, e.target)) };

  switch (spec.type) {
    case 'text':
      return el('input', {
        type: spec.secret ? 'password' : 'text',
        value: value ?? '',
        maxlength: spec.max ?? 100,
        autocomplete: spec.secret ? 'current-password' : 'off',
        ...common,
      });
    case 'date':
      return el('input', { type: 'date', value: value ?? '', ...common });
    case 'time':
      return el('input', { type: 'time', step: 1, value: value ?? '', ...common });
    case 'rate':
      return el('input', {
        type: 'number', class: 'num', value: value ?? 0,
        step: spec.step ?? 0.001, min: spec.min, max: spec.max, ...common,
      });
    case 'day':
      return el('input', { type: 'number', class: 'num', value: value ?? 1, min: 1, max: 28, ...common });
    case 'dow':
      return el('select', common, [0, 1, 2, 3, 4, 5, 6].map((n) =>
        el('option', { value: n, selected: Number(value) === n, text: dowName(n) })));
    case 'shift':
      return el('select', common, [
        el('option', { value: '1', selected: Number(value) >= 0, text: '휴일이면 뒤로' }),
        el('option', { value: '-1', selected: Number(value) < 0, text: '휴일이면 앞으로' }),
      ]);
    case 'category':
      return el('select', common, EXPENSE_CATEGORIES.map((code) =>
        el('option', { value: code, selected: value === code, text: code })));
    case 'bool':
      return el('input', {
        type: 'checkbox', checked: Boolean(value),
        onchange: (e) => onChange(e.target.checked),
      });
    case 'dowList':
      return checkRow([0, 1, 2, 3, 4, 5, 6], value ?? [], dowName, onChange);
    case 'monthList':
      return checkRow([1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12], value ?? [], (n) => `${n}월`, onChange);
    case 'dateList':
    case 'textList':
      return el('textarea', {
        value: (value ?? []).join(spec.type === 'dateList' ? '\n' : ', '),
        onchange: (e) => onChange(e.target.value.split(/[,\n]/).map((s) => s.trim()).filter(Boolean)),
      });
    default: // int · money
      return el('input', {
        type: 'number', class: 'num', value: value ?? 0,
        min: spec.min, max: spec.max, step: spec.type === 'money' ? 1000 : 1, ...common,
      });
  }
}

function read(spec, target) {
  if (spec.type === 'text' || spec.type === 'date' || spec.type === 'time') return target.value;
  if (spec.type === 'category') return target.value;
  return Number(target.value);
}

/** 요일·월처럼 여러 개를 고르는 칸. 체크박스를 줄로 늘어놓는 것이 가장 덜 헷갈린다. */
function checkRow(options, picked, label, onChange) {
  const set = new Set(picked.map(Number));
  return el('div', { style: 'display:flex;flex-wrap:wrap;gap:4px 10px;padding-top:8px' },
    options.map((n) => el('label', {
      style: 'display:inline-flex;align-items:center;gap:4px;font-size:13px;font-weight:500;cursor:pointer',
    }, [
      el('input', {
        type: 'checkbox',
        checked: set.has(n),
        style: 'width:16px;height:16px;accent-color:var(--blue)',
        onchange: (e) => {
          if (e.target.checked) set.add(n);
          else set.delete(n);
          onChange([...set].sort((a, b) => a - b));
        },
      }),
      label(n),
    ])));
}

/** 스키마 한 줄을 라벨·입력·도움말이 붙은 덩어리로. */
function field(spec, value, onChange) {
  const money = spec.type === 'money' && Number.isFinite(Number(value));
  return el('div', { class: `field${spec.type === 'dateList' || spec.type === 'textList' ? ' wide' : ''}` }, [
    el('label', {}, [spec.label, spec.unit && el('span', { style: 'color:var(--ink-4);font-weight:500', text: ` (${spec.unit})` })]),
    input(spec, value, onChange),
    money && el('div', { class: 'hint', text: won(value) }),
    spec.hint && el('div', { class: 'hint' }, richText(spec.hint)),
  ]);
}

/** 설정 안의 경로 하나를 고치는 칸. */
const tuned = (spec) => field(spec, getAt(state.tuning, spec.path), (value) => {
  setAt(state.tuning, spec.path, value);
  schedulePreview();
});

function card(title, note, body) {
  return el('section', { class: 'card' }, [
    title && el('h2', { class: 'card-title', text: title }),
    note && el('p', { class: 'card-note' }, richText(note)),
    body,
  ]);
}

// ── 곡선 그리기 ─────────────────────────────────────────────────────────

/**
 * 날짜별 종가 곡선. 색만으로 공백을 가르지 않으려고 <b>빗금</b>을 함께 깔고,
 * 저점에는 날짜와 금액을 글자로 붙인다(도메인 규칙 6).
 */
function chart(view) {
  const series = view.series;
  if (series.length < 2) return el('div', { class: 'empty-state', text: '그릴 것이 없다' });

  const W = 900;
  const H = 280;
  const PAD = { l: 76, r: 16, t: 16, b: 30 };
  const innerW = W - PAD.l - PAD.r;
  const innerH = H - PAD.t - PAD.b;

  const values = series.map((s) => s.balance);
  let min = Math.min(0, ...values);
  let max = Math.max(0, ...values);
  const span = max - min || 1;
  min -= span * 0.1;
  max += span * 0.1;

  const x = (i) => PAD.l + (i * innerW) / (series.length - 1);
  const y = (v) => PAD.t + innerH * (1 - (v - min) / (max - min));
  const at = (iso) => series.findIndex((s) => s.date === iso);

  const line = series.map((s, i) => `${i ? 'L' : 'M'}${x(i).toFixed(1)},${y(s.balance).toFixed(1)}`).join(' ');
  const area = `${line} L${x(series.length - 1).toFixed(1)},${y(0).toFixed(1)} L${x(0).toFixed(1)},${y(0).toFixed(1)} Z`;

  const asOfAt = Math.max(0, at(view.asOf));
  const winFrom = Math.max(0, at(view.windowFrom));
  const winTo = Math.max(winFrom, at(view.windowTo));
  const lowAt = view.low ? at(view.low.date) : -1;

  return el('div', {}, [
    sv('svg', { class: 'chart', viewBox: `0 0 ${W} ${H}`, role: 'img', 'aria-label': '날짜별 예상 잔액 곡선' }, [
      sv('defs', {}, [
        // 빗금. 공백 구간을 색 말고도 질감으로 구분한다.
        sv('pattern', { id: 'gap', width: 6, height: 6, patternUnits: 'userSpaceOnUse', patternTransform: 'rotate(45)' }, [
          sv('rect', { width: 6, height: 6, fill: 'var(--red-weak)' }),
          sv('line', { x1: 0, y1: 0, x2: 0, y2: 6, stroke: 'var(--red)', 'stroke-width': 1.5, opacity: 0.45 }),
        ]),
        // 0 아래만 빗금이 보이게 자른다.
        sv('clipPath', { id: 'below' }, [
          sv('rect', { x: PAD.l, y: y(0), width: innerW, height: Math.max(0, PAD.t + innerH - y(0)) }),
        ]),
      ]),

      // 예보 창
      sv('rect', {
        x: x(winFrom), y: PAD.t, width: Math.max(1, x(winTo) - x(winFrom)), height: innerH,
        fill: 'var(--blue-weak)', opacity: 0.7,
      }),

      // 0 아래로 내려간 부분
      sv('path', { d: area, fill: 'url(#gap)', 'clip-path': 'url(#below)' }),

      // 0 선
      sv('line', { x1: PAD.l, y1: y(0), x2: W - PAD.r, y2: y(0), stroke: 'var(--ink-4)', 'stroke-dasharray': '4 4' }),

      // 기준일
      sv('line', { x1: x(asOfAt), y1: PAD.t, x2: x(asOfAt), y2: PAD.t + innerH, stroke: 'var(--ink-2)', 'stroke-width': 1.5 }),
      sv('text', {
        x: x(asOfAt) + 5, y: PAD.t + 11, 'font-size': 11, 'font-weight': 700, fill: 'var(--ink-2)',
        text: '기준일',
      }),

      sv('path', { d: line, fill: 'none', stroke: 'var(--blue-dark)', 'stroke-width': 2, 'stroke-linejoin': 'round' }),

      // 저점
      lowAt >= 0 && sv('g', {}, [
        sv('circle', {
          cx: x(lowAt), cy: y(view.low.balance), r: 4,
          fill: view.low.balance < 0 ? 'var(--red)' : 'var(--blue-dark)',
        }),
        sv('text', {
          x: Math.min(x(lowAt) + 8, W - PAD.r - 150),
          y: y(view.low.balance) + (view.low.balance < 0 ? 18 : -9),
          'font-size': 11, 'font-weight': 700,
          fill: view.low.balance < 0 ? 'var(--red)' : 'var(--ink-2)',
          text: `저점 ${won(view.low.balance)} · ${shortDate(view.low.date)}`,
        }),
      ]),

      // 세로 눈금
      [max, 0, min].map((v) => sv('text', {
        x: PAD.l - 8, y: y(v) + 4, 'text-anchor': 'end', 'font-size': 11, fill: 'var(--ink-3)',
        text: Math.abs(v) >= 10000 ? `${num(Math.round(v / 10000))}만` : num(Math.round(v)),
      })),

      // 가로 눈금
      [0, asOfAt, series.length - 1].map((i) => sv('text', {
        x: Math.min(Math.max(x(i), PAD.l + 16), W - PAD.r - 16),
        y: H - 8, 'text-anchor': 'middle', 'font-size': 11, fill: 'var(--ink-3)',
        text: series[i].date.slice(2).replace(/-/g, '.'),
      })),
    ]),

    el('div', { class: 'chart-legend' }, [
      el('span', {}, [el('span', { class: 'swatch', style: 'background:var(--blue-dark)' }), '예상 잔액']),
      el('span', {}, [el('span', { class: 'swatch', style: 'background:var(--blue-weak)' }), `예보 창 ${view.windowFrom} ~ ${view.windowTo}`]),
      el('span', {}, [el('span', { class: 'swatch', style: 'background:var(--red-weak);border:1px solid var(--red)' }), '0원 아래 (공백)']),
    ]),
  ]);
}

// ── 화면: 잔액 곡선 ─────────────────────────────────────────────────────

function sumTile(k, v, s) {
  return el('div', {}, [
    el('div', { class: 'k', text: k }),
    el('div', { class: 'v num', text: v }),
    s && el('div', { class: 's', text: s }),
  ]);
}

function failCard() {
  return card(null, null, [
    el('div', { class: 'err' }, [
      el('div', { class: 'err-title', text: '이 설정으로는 곡선을 만들 수 없다' }),
      el('div', { class: 'err-msg', text: last.message }),
    ]),
    el('p', { class: 'card-note' }, richText(
      '<b>공백 없는 시드는 시연이 안 되는 시드다.</b> 그래서 조용히 넘기지 않고 여기서 멈춘다. '
      + '기준일을 부가세 예정고지(1·4·7·10월 25일)나 급여 앞당김이 창에 들어오는 날로 옮기거나, '
      + '앵커의 「며칠 안」을 늘린다.')),
  ]);
}

function viewCurve() {
  if (!last) return card(null, null, el('div', { class: 'empty-state', text: '계산 중…' }));
  if (!last.ok) return failCard();

  const v = last;
  return [
    card(null, null, [
      el('div', { class: 'sum' }, [
        sumTile('기준일 잔액', won(v.closingBalance), `${v.from} ~ ${v.asOf}`),
        sumTile('예보 창 저점', won(v.low?.balance), v.low ? shortDate(v.low.date) : '—'),
        sumTile('공백', v.gap ? `${v.gap.days}일` : '없음', v.gap ? `${shortDate(v.gap.from)} ~ ${shortDate(v.gap.to)}` : '예보 창 안에서'),
        sumTile('시작 잔액', won(v.opening), `목표 저점과 ${won(v.anchorError)} 차이`),
      ]),
      chart(v),
    ]),

    card('월별 저점', '과거가 0 을 뚫지 않는지, 앵커 구간만 마이너스인지가 여기서 보인다. 과거가 뚫리면 실제 계좌로는 있을 수 없는 데이터다.',
      el('div', { class: 'scroll' }, el('table', {}, [
        el('thead', {}, el('tr', {}, [
          el('th', { text: '월' }), el('th', { text: '저점 날짜' }),
          el('th', { class: 'r', text: '저점' }), el('th', { text: '' }),
        ])),
        el('tbody', {}, v.monthly.map((m) => el('tr', {}, [
          el('td', { class: 'mono', text: m.month }),
          el('td', { text: shortDate(m.date) }),
          el('td', { class: `r num ${m.balance < 0 ? 'amt-out' : ''}`, text: won(m.balance) }),
          el('td', {}, m.balance < 0 && el('span', { class: 'type-badge bad', text: '공백' })),
        ]))),
      ]))),

    card('예보 창 일별 종가', '발표 때 화면에 뜰 구간이다. 하루하루가 곡선의 어느 지점인지 숫자로 확인한다.',
      el('div', { class: 'scroll' }, el('table', {}, [
        el('thead', {}, el('tr', {}, [
          el('th', { text: '날짜' }), el('th', { class: 'r', text: '예상 잔액' }), el('th', { text: '' }),
        ])),
        el('tbody', {}, v.series
          .filter((s) => s.date >= v.windowFrom && s.date <= v.windowTo)
          .map((s) => el('tr', {}, [
            el('td', { text: shortDate(s.date) }),
            el('td', { class: `r num ${s.balance < 0 ? 'amt-out' : ''}`, text: won(s.balance) }),
            el('td', {}, s.balance < 0 && el('span', { class: 'type-badge bad', text: '공백' })),
          ]))),
      ]))),
  ];
}

// ── 화면: 거래 · 합계 ───────────────────────────────────────────────────

function txTable(rows) {
  if (!rows.length) return el('div', { class: 'empty-state', text: '거래가 없다' });
  return el('div', { class: 'scroll' }, el('table', {}, [
    el('thead', {}, el('tr', {}, [
      el('th', { text: '거래일시' }), el('th', { text: '적요' }), el('th', { text: '분류' }),
      el('th', { class: 'r', text: '금액' }), el('th', { class: 'r', text: '거래 후 잔액' }),
    ])),
    el('tbody', {}, rows.map((r) => el('tr', {}, [
      el('td', {}, [shortDate(r.date), el('div', { class: 'sub-time', text: r.time })]),
      el('td', { text: r.description }),
      el('td', {}, el('span', { class: `type-badge${r.flow === 'DEPOSIT' ? ' in' : ''}`, text: r.category })),
      el('td', { class: `r num ${r.flow === 'DEPOSIT' ? 'amt-in' : 'amt-out'}`, text: `${r.flow === 'DEPOSIT' ? '' : MINUS}${num(r.amount)}` }),
      el('td', { class: `r num ${r.balanceAfter < 0 ? 'amt-out' : ''}`, text: num(r.balanceAfter) }),
    ]))),
  ]));
}

function viewRows() {
  if (!last) return card(null, null, el('div', { class: 'empty-state', text: '계산 중…' }));
  if (!last.ok) return failCard();

  const v = last;
  const deposits = v.totals.filter((t) => t.flow === 'DEPOSIT');
  const withdrawals = v.totals.filter((t) => t.flow === 'WITHDRAWAL');
  const sum = (list) => list.reduce((s, t) => s + t.amount, 0);

  return [
    card('분류별 합계', `과거 구간 ${v.from} ~ ${v.asOf} 의 ${num(v.pastCount)}건. 금액을 손봤을 때 어디가 얼마나 움직였는지 본다.`, [
      el('div', { class: 'sum' }, [
        sumTile('입금 합계', won(sum(deposits)), `${deposits.length}개 분류`),
        sumTile('출금 합계', won(sum(withdrawals)), `${withdrawals.length}개 분류`),
        sumTile('순증', won(sum(deposits) - sum(withdrawals))),
        sumTile('사장님 메우기', `${v.funding.count}회`, won(v.funding.amount)),
      ]),
      el('div', { class: 'scroll' }, el('table', {}, [
        el('thead', {}, el('tr', {}, [
          el('th', { text: '분류' }), el('th', { text: '방향' }),
          el('th', { class: 'r', text: '건수' }), el('th', { class: 'r', text: '합계' }),
          el('th', { class: 'r', text: '건당 평균' }),
        ])),
        el('tbody', {}, v.totals.map((t) => el('tr', {}, [
          el('td', {}, el('span', { class: `type-badge${t.flow === 'DEPOSIT' ? ' in' : ''}`, text: t.category })),
          el('td', { text: t.flow === 'DEPOSIT' ? '입금' : '출금' }),
          el('td', { class: 'r num', text: num(t.count) }),
          el('td', { class: 'r num', text: won(t.amount) }),
          el('td', { class: 'r num', text: won(Math.round(t.amount / t.count)) }),
        ]))),
      ])),
    ]),

    productSalesCard(v.productSales),

    card('기준일 직전 거래', 'CSV 로 업로드될 구간의 끝이다. 마지막 행의 잔액이 곡선의 출발점이 된다.', txTable(v.samplePast)),
    card('기준일 이후 거래', '업로드하지 않는다 — <b>예측 엔진이 맞혀야 할 몫</b>이다. 우리가 의도한 공백과 엔진이 그린 공백을 비교하는 데 쓴다.', txTable(v.sampleFuture)),
  ];
}

/**
 * 인쇄물 미리보기 (P1-22). 「상품 구성」 표를 고쳤을 때 무엇이 달라지는지 보이는 자리다 —
 * 안 보이면 화면에 표만 있고 고쳐도 아무 일이 안 일어난다.
 *
 * <p>마지막 달만 펼친다. 다섯 달치를 다 늘어놓으면 읽을 것이 없고, 정작 볼 것은
 * <b>첫 달 대비 구성비가 얼마나 움직였나</b>다 — 매출이 줄 때 무엇이 팔려서 줄었는지가
 * 이 기능이 답할 질문이다.
 */
function productSalesCard(v) {
  if (!v?.periods) {
    return card('상품별 매출', '창에 통째로 들어온 달이 없거나 상품 비중이 전부 0 이다. 인쇄물은 달이 끝나야 뽑으므로 반달치는 만들지 않는다.',
      el('div', { class: 'empty-state', text: '등록할 기간이 없다' }));
  }

  const p = v.lastPeriod;
  return card('상품별 매출', `${v.from} ~ ${v.to} 의 <b>${v.periods}개 기간 · ${v.rows}행</b>이 등록된다(P1-22). 아래는 마지막 달 ${p.from} ~ ${p.to} 이고, 「첫 달 대비」는 그 사이 구성비가 움직인 폭이다. 금액은 <b>정산 전 총매출</b>이라 통장 입금 합계보다 크다 — 수수료를 되돌린 값이다.`, [
    el('div', { class: 'sum' }, [
      sumTile('등록 기간', `${v.periods}개월`, `${v.rows}행`),
      sumTile('마지막 달 총매출', won(p.grossAmount), '정산 전'),
      sumTile('결제금액', won(p.billedAmount), '구성비 분모'),
      sumTile('수량', `${num(p.quantity)}개`),
    ]),
    el('div', { class: 'scroll' }, el('table', {}, [
      el('thead', {}, el('tr', {}, [
        el('th', { text: '상품' }),
        el('th', { class: 'r', text: '수량' }),
        el('th', { class: 'r', text: '결제금액' }),
        el('th', { class: 'r', text: '구성비' }),
        el('th', { class: 'r', text: '첫 달 대비' }),
      ])),
      el('tbody', {}, v.items.map((item) => el('tr', {}, [
        el('td', { text: item.productName }),
        el('td', { class: 'r num', text: num(item.quantity) }),
        el('td', { class: 'r num', text: won(item.billedAmount) }),
        el('td', { class: 'r num', text: pct(item.share) }),
        // 색만으로 방향을 가르지 않는다(도메인 규칙 6) — 늘었나 줄었나를 글자로 함께 적는다.
        el('td', { class: `r num ${item.shift < -0.005 ? 'amt-out' : ''}` }, [
          pct(item.shift),
          Math.abs(item.shift) >= 0.005
            && el('div', { class: 'sub-time', text: item.shift < 0 ? '줄었다' : '늘었다' }),
        ]),
      ]))),
    ])),
  ]);
}

// ── 화면: 기간 · 앵커 ───────────────────────────────────────────────────

function viewParams() {
  return [
    card('기준일', '<b>이 날짜가 전부를 정한다.</b> 과거 구간도 예보 창도 여기서부터 센다. 발표 당일 날짜로 두는 것이 가장 깔끔하다 — 미래로 두면 금융망 거래가 실제 오늘로 찍혀 CSV 구간 한가운데 앉는다.',
      el('div', { class: 'fields' }, [
        field({ type: 'date', label: '기준일' }, state.asOf, (value) => {
          state.asOf = value;
          schedulePreview();
          render();
        }),
        el('div', { class: 'field' }, [
          el('label', { text: '오늘로' }),
          el('button', {
            class: 'btn ghost', type: 'button', style: 'width:100%;height:44px',
            text: boot?.today ?? '—',
            onclick: () => {
              state.asOf = boot.today;
              schedulePreview();
              render();
            },
          }),
        ]),
      ])),

    ...SCALAR_GROUPS.map((group) =>
      card(group.label, group.note, el('div', { class: 'fields' }, group.fields.map(tuned)))),

    card('달력', null, el('div', { class: 'fields' }, CALENDAR_FIELDS.map(tuned))),
  ];
}

// ── 화면: 항목 ──────────────────────────────────────────────────────────

/**
 * 표 한 줄. base 는 tuning 안의 키(income·fixed·weekly·irregular)다.
 *
 * <p>「이 항목을 끄는 칸」이 표마다 다르다 — 금액이 0 이면 안 만드는 표가 대부분이지만,
 * 주기 없는 거래는 <b>하루 확률</b>이 그 자리다({@code onField}). 수입 세 줄은 끌 수 없다.
 */
function itemRow(table, key, row, rowMeta) {
  const onField = table.onField ?? 'amount';
  const off = table.base !== 'income' && !(row[onField] > 0);
  return el('tr', { class: off ? 'off' : null }, [
    el('td', {}, el('span', { class: 'row-label' }, [
      rowMeta?.label ?? row.label ?? key,
      el('span', { class: 'row-key', text: key }),
    ])),
    ...table.columns.map((column) => {
      // 카드 정산은 영업일마다라 요일 칸이 없다.
      if (column.only === 'dow' && !rowMeta?.dow) {
        return el('td', {}, el('span', { class: 'sub-time', text: rowMeta?.fixedNote ?? '—' }));
      }
      return el('td', {}, input(column, row[column.field], (value) => {
        state.tuning[table.base][key][column.field] = value;
        schedulePreview();
        if (column.field === onField) render(); // 껐다 켰다가 표시에 바로 보이게
      }));
    }),
    table.addable && el('td', {}, el('button', {
      class: 'row-drop', type: 'button', title: '이 항목 지우기', text: '✕',
      onclick: () => {
        delete state.tuning[table.base][key];
        schedulePreview();
        render();
      },
    })),
  ]);
}

function itemTable(table) {
  const group = state.tuning[table.base] ?? {};
  const keys = table.rows ? table.rows.map((r) => r.key) : Object.keys(group);

  return card(table.label, table.note, [
    el('div', { class: 'scroll' }, el('table', { class: 'grid-table' }, [
      el('thead', {}, el('tr', {}, [
        el('th', { text: '항목' }),
        ...table.columns.map((c) => el('th', { text: c.label })),
        table.addable && el('th', { text: '' }),
      ])),
      el('tbody', {}, keys
        .filter((key) => group[key])
        .map((key) => itemRow(table, key, group[key], table.rows?.find((r) => r.key === key)))),
    ])),
    table.addable && el('div', { class: 'btn-row' }, el('button', {
      class: 'btn ghost small', type: 'button', text: '+ 항목 추가',
      onclick: () => addItem(table),
    })),
  ]);
}

/** 표마다 칸이 다르다. 스키마가 정한 것이 있으면 그것으로, 없으면 지출 항목 모양으로 채운다. */
function blankRow(table, key) {
  return {
    label: key,
    ...(table.newRow ?? {
      description: key,
      amount: 0,
      day: 1,
      dow: 1,
      shift: 1,
      time: '06:00:00',
      category: 'OTHER_EXPENSE',
    }),
  };
}

/** 새 항목. 키는 영문이어야 한다 — 설정 파일과 코드가 이 키로 항목을 가리킨다. */
function addItem(table) {
  const key = window.prompt('항목 키 (영문·숫자·밑줄)', '');
  if (!key) return;
  if (!/^[A-Za-z][A-Za-z0-9_]{0,30}$/.test(key)) {
    window.alert('영문으로 시작하는 영문·숫자·밑줄만 쓴다.');
    return;
  }
  if (state.tuning[table.base][key]) {
    window.alert('이미 있는 키다.');
    return;
  }
  state.tuning[table.base][key] = blankRow(table, key);
  schedulePreview();
  render();
}

function viewItems() {
  return [
    itemTable(INCOME_TABLE),
    // 상품 구성은 수입 바로 아래다 — 총매출이 수입에서 오고 이 표는 그 총액을 나누기만 한다.
    // 고친 결과는 「거래 · 합계」의 상품별 매출 카드에서 본다(여기서 다시 그리면 커서가 튄다).
    itemTable(PRODUCT_TABLE),
    card('재료비', '주 2회 들어가는 변동 지출. 매출이 늘면 원가도 따라 늘도록 성장률이 함께 곱해진다.',
      el('div', { class: 'fields' }, MATERIALS_FIELDS.map(tuned))),
    itemTable(FIXED_TABLE),
    itemTable(WEEKLY_TABLE),
    itemTable(IRREGULAR_TABLE),
    card('부가가치세', null, el('div', { class: 'fields' }, TAX_FIELDS.map(tuned))),
  ];
}

// ── 화면: 계정 · 사업장 · 계좌 ──────────────────────────────────────────

function viewAccount() {
  const owned = (spec) => field(spec, state.owner[spec.path.split('.')[1]], (value) => {
    state.owner[spec.path.split('.')[1]] = value;
    saveState();
    renderCtx();
  });

  return [
    card('백엔드', '시드를 밀어 넣을 서버. 로컬 개발은 <b>http://localhost:8080</b>, 배포 서버로 보내려면 그 주소를 넣는다.',
      el('div', { class: 'fields' },
        field({ type: 'text', label: '주소', max: 200 }, state.apiBase, (value) => {
          state.apiBase = value;
          saveState();
          renderCtx();
        }))),

    card('계정', null, el('div', { class: 'fields' }, OWNER_FIELDS.map(owned))),

    card('사업장', null, el('div', { class: 'fields' }, BUSINESS_FIELDS.map(tuned))),

    card('계좌', '별칭으로 구분한다 — 계좌번호는 적지 않고, 없으면 싸피은행(999)에 만든다. <b>1원 인증을 일부러 꺼 두는 계좌가 하나 있어야</b> 인증 화면(SC-01-4)에 시연할 대상이 남는다.', [
      el('div', { class: 'scroll' }, el('table', { class: 'grid-table' }, [
        el('thead', {}, el('tr', {}, [
          el('th', { text: '#' }),
          ...ACCOUNT_COLUMNS.map((c) => el('th', { class: c.type === 'bool' ? 'c' : null, text: c.label })),
          el('th', { text: '' }),
        ])),
        el('tbody', {}, state.accounts.map((row, index) => el('tr', {}, [
          el('td', { class: 'mono', text: String(index + 1) }),
          ...ACCOUNT_COLUMNS.map((column) => el('td', { class: column.type === 'bool' ? 'c' : null },
            input(column, row[column.field], (value) => {
              // 거래를 받는 계좌와 대표 계좌는 하나씩이다. 켜면 나머지를 끈다.
              if (value && (column.field === 'main' || column.field === 'primary')) {
                state.accounts.forEach((a) => { a[column.field] = false; });
              }
              row[column.field] = value;
              saveState();
              render();
            }))),
          el('td', {}, state.accounts.length > 1 && el('button', {
            class: 'row-drop', type: 'button', title: '이 계좌 지우기', text: '✕',
            onclick: () => {
              state.accounts.splice(index, 1);
              if (!state.accounts.some((a) => a.main)) state.accounts[0].main = true;
              saveState();
              render();
            },
          })),
        ]))),
      ])),
      state.accounts.length < 5 && el('div', { class: 'btn-row' }, el('button', {
        class: 'btn ghost small', type: 'button', text: '+ 계좌 추가',
        onclick: () => {
          state.accounts.push({ alias: `계좌 ${state.accounts.length + 1}`, primary: false, verify: false, main: false });
          saveState();
          render();
        },
      })),
      el('p', { class: 'card-note', style: 'margin:16px 0 0' }, richText(
        '<b>거래 투입</b>은 CSV 와 잔액 맞추기를 받을 계좌다. 하나만 켤 수 있다.')),
    ]),
  ];
}

// ── 화면: 실행 ──────────────────────────────────────────────────────────

let running = false;
let runLog = [];

/**
 * 원버튼이 <b>한 번 눌린 상태</b>. 두 번째 누름이 실제로 시작한다.
 *
 * <p>처음에는 {@code window.confirm} 이었다. 사람에게는 멀쩡하지만 <b>임베디드 브라우저가
 * 네이티브 대화상자를 자동으로 닫아 「취소」로 돌려준다</b> — 그런 데서 띄우면 빨간 버튼이
 * 아무 일도 안 하는 것처럼 보인다. 이 도구는 개발자가 별별 브라우저에서 여는 것이라,
 * 확인을 화면 안에 두는 편이 맞다. 콘솔의 다른 부분과 모양도 맞는다.
 */
let redoArmed = false;

function viewRun() {
  const implied = expandSteps(state.steps);
  const ready = boot?.keyReady && last?.ok && !running;

  return [
    !boot?.keyReady && card(null, null, el('div', { class: 'err' }, [
      el('div', { class: 'err-title', text: 'FINOPENAPI_API_KEY 가 없다' }),
      el('div', { class: 'err-msg', text: 'backend/.env 의 FINOPENAPI_API_KEY 를 채운다. 금융망을 부르지 않는 미리보기와 CSV 는 그대로 쓸 수 있다.' }),
    ])),

    last && !last.ok && card(null, null, el('div', { class: 'err' }, [
      el('div', { class: 'err-title', text: '지금 설정으로는 곡선이 안 나온다' }),
      el('div', { class: 'err-msg', text: last.message }),
    ])),

    card('단계 고르기', '고른 것에 <b>선행 단계와 뒤처리가 자동으로 얹힌다</b> — "업로드만 할게"를 눌렀는데 계좌가 없어서 실패하는 것보다 낫다. <b>얹힌 단계는 켠 채로 잠긴다</b>: 풀어도 어차피 돌기 때문이다. 다시 돌려도 같은 상태가 된다: 사업장·계좌는 이미 있으면 안 만들고, 중복 거래는 서버가 흡수한다.', [
      el('div', {}, STEPS.map((step) => {
        const picked = state.steps.includes(step.key);
        // 풀어도 그대로 도는 단계는 켠 채로 잠근다. 눌러도 결과가 같은 체크박스는 거짓말이고,
        // 「분명히 껐는데 돌았다」가 「분명히 켰는데 안 돌았다」보다 나을 것도 없다.
        const locked = expandSteps(state.steps.filter((key) => key !== step.key))
          .includes(step.key);
        // 선행(needs)이라 얹힌 것과 뒤처리(brings)라 따라온 것은 이유가 다르다 — 문구도 다르다.
        const brought = locked
          && STEPS.some((s) => implied.includes(s.key) && (s.brings ?? []).includes(step.key));
        // 되돌릴 수 없는 단계는 다른 줄과 같은 모양이면 안 된다 — 전체를 훑어 켜다가
        // 딸려 켜진다. 색만으로 가르지 않는다(도메인 규칙 6): 왼쪽 띠와 함께, 설명
        // 첫머리의 ⚠ 와 실행 전 확인이 같은 말을 글자로 한 번 더 한다.
        return el('label', { class: `pick-step${step.danger ? ' danger' : ''}${locked ? ` locked${brought ? ' brought' : ''}` : ''}` }, [
          el('input', {
            type: 'checkbox',
            checked: picked || locked,
            disabled: locked,
            onchange: (e) => {
              state.steps = e.target.checked
                ? [...state.steps, step.key]
                : state.steps.filter((k) => k !== step.key);
              saveState();
              render();
            },
          }),
          el('span', { class: 'step-text' }, [
            el('span', { class: 't', text: step.label }),
            el('span', { class: 'n' }, richText(step.note)),
          ]),
        ]);
      })),

      el('div', { class: 'confirm' }, [
        el('div', { class: 'confirm-title', text: '실행하면 이렇게 된다' }),
        el('dl', {}, [
          el('dt', { text: '백엔드' }), el('dd', { text: state.apiBase }),
          el('dt', { text: '계정' }), el('dd', { text: state.owner.email }),
          el('dt', { text: '사업장' }), el('dd', { text: state.tuning.business.name }),
          el('dt', { text: '기준일' }), el('dd', { text: state.asOf }),
          el('dt', { text: '올릴 거래' }), el('dd', { text: last?.ok ? `${num(last.pastCount)}건 (${last.from} ~ ${last.asOf})` : '—' }),
          el('dt', { text: '맞출 잔액' }), el('dd', { text: last?.ok ? won(last.closingBalance) : '—' }),
          el('dt', { text: '단계' }), el('dd', { text: implied.length ? implied.map((k) => STEPS.find((s) => s.key === k).label).join(' → ') : '고른 것 없음' }),
          // 지우기가 섞였으면 숫자 사이에 묻히면 안 된다. 무엇이 사라지는지 말로 적는다.
          implied.includes('reset') && el('dt', { class: 'danger', text: '⚠ 먼저 지운다' }),
          implied.includes('reset') && el('dd', { class: 'danger', text: `${state.owner.email} 에 연동된 계좌 전부 — 거래·예정 거래·반복 패턴까지. 금융망 계좌도 해지한다. 되돌릴 수 없다.` }),
        ]),
      ]),

      el('button', {
        class: 'btn block', type: 'button', disabled: !ready || !implied.length,
        text: running ? '실행 중…' : '금융망과 백엔드에 실제로 반영한다',
        onclick: doRun,
      }),

      // 원버튼 재주입. 위 버튼과 하는 일은 같고 <b>단계를 대신 골라 준다</b> —
      // 잘못 넣은 시드를 고칠 때마다 체크박스를 다시 맞추던 일이 이것이다.
      !redoArmed && el('button', {
        class: 'btn block danger', type: 'button', disabled: !ready,
        text: running ? '실행 중…' : '싹 지우고 처음부터 다시 세운다',
        onclick: () => { redoArmed = true; render(); },
      }),
      !redoArmed && el('p', { class: 'card-note', style: 'margin:8px 0 0' }, richText(
        '연동 계좌를 <b>거래째</b> 지우고 금융망에서 해지한 뒤(DD_14), 기본 단계를 그대로 돌린다. '
        + '<b>되돌릴 수 없다</b> — 누르면 한 번 더 묻는다.')),

      // 두 번째 누름. 계정 이름을 눈으로 한 번 읽게 한다 — 되돌릴 수 없는 일에
      // 「같은 것을 두 번 말하게」 하는 장치이고, 계좌 삭제가 별칭을 되묻는 것과 같은 뜻이다.
      redoArmed && el('div', { class: 'confirm danger', style: 'margin-top:20px' }, [
        el('div', { class: 'confirm-title', text: '정말 지울까' }),
        el('p', { class: 'card-note', style: 'margin:0 0 4px' }, richText(
          `<b>${state.owner.email}</b> 에 연동된 계좌를 <b>거래째</b> 지우고 금융망에서도 해지한다. `
          + '그 뒤 기본 단계를 그대로 돌려 처음부터 다시 세운다. <b>되돌릴 수 없다.</b>')),
        el('div', { class: 'btn-row' }, [
          el('button', {
            class: 'btn danger', type: 'button', disabled: !ready,
            text: running ? '실행 중…' : '지우고 다시 세운다',
            onclick: doRedo,
          }),
          el('button', {
            class: 'btn ghost', type: 'button', text: '취소',
            onclick: () => { redoArmed = false; render(); },
          }),
        ]),
      ]),
    ]),

    runLog.length && card('진행', null, el('div', { class: 'steps' }, runLog.map((entry) =>
      el('div', { class: `step ${entry.status ?? ''}` }, [
        el('span', { class: 'step-mark', text: { done: '✓', fail: '✕', running: '·' }[entry.status] ?? '·' }),
        el('span', { class: 'step-text' }, [
          entry.label,
          entry.lines.map((line) => el('span', { class: `step-log${line.bad ? ' bad' : ''}`, text: line.text })),
        ]),
      ])))),
  ];
}

/**
 * <b>원버튼 재주입 — 지우고 처음부터 다시 세운다.</b>
 *
 * <p>두 번째 누름에서만 여기 온다({@link redoArmed}). 단계 체크박스는 확인이 아니다 —
 * 훑어 켜다가 지나갈 수 있고, 그때 사라지는 것이 다른 사람이 맞춰 둔 시연 데이터일 수 있다.
 */
async function doRedo() {
  redoArmed = false;
  state.steps = [...REDO_STEPS];
  saveState();
  await doRun();
}

/**
 * 실행. 진행이 NDJSON 으로 흘러 들어오므로 한 줄씩 읽어 그때그때 그린다 —
 * 다 끝나고 한꺼번에 보여 주면 어디서 멈췄는지 알 수 없다.
 */
async function doRun() {
  running = true;
  runLog = [];
  render();

  const res = await fetch('/api/run', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ ...payload(), steps: state.steps }),
  });

  if (!res.body) {
    running = false;
    runLog = [{ label: '실행', status: 'fail', lines: [{ text: '응답을 읽을 수 없다', bad: true }] }];
    render();
    return;
  }

  const reader = res.body.getReader();
  const decoder = new TextDecoder();
  let buffer = '';

  const find = (key) => runLog.find((e) => e.key === key);

  for (;;) {
    const { value, done } = await reader.read();
    if (done) break;
    buffer += decoder.decode(value, { stream: true });

    const lines = buffer.split('\n');
    buffer = lines.pop() ?? '';

    for (const raw of lines) {
      if (!raw.trim()) continue;
      let event;
      try {
        event = JSON.parse(raw);
      } catch {
        continue;
      }

      if (event.type === 'start') {
        runLog = event.steps.map((key) => ({
          key,
          label: STEPS.find((s) => s.key === key)?.label ?? key,
          status: null,
          lines: [],
        }));
      } else if (event.type === 'step') {
        const entry = find(event.step);
        if (entry) {
          entry.status = event.status;
          if (event.message) entry.lines.push({ text: event.message, bad: true });
        }
      } else if (event.type === 'log') {
        find(event.step)?.lines.push({ text: event.text, bad: event.bad === true });
      } else if (event.type === 'done') {
        runLog.push({ key: '__done', label: event.ok ? '끝' : '멈춤', status: event.ok ? 'done' : 'fail', lines: [{ text: event.message, bad: !event.ok }] });
      }
      render();
    }
  }

  running = false;
  render();
}

// ── 화면: CSV · 설정 파일 ───────────────────────────────────────────────

function download(name, text, type = 'text/csv;charset=utf-8') {
  const url = URL.createObjectURL(new Blob([text], { type }));
  const link = el('a', { href: url, download: name });
  document.body.append(link);
  link.click();
  link.remove();
  URL.revokeObjectURL(url);
}

async function getCsv(kind) {
  const result = await post('/api/csv', { asOf: state.asOf, tuning: state.tuning, kind });
  if (!result.ok) {
    window.alert(result.message);
    return;
  }
  download(result.filename, result.csv);
}

async function exportPreset() {
  const result = await post('/api/preset', payload());
  if (!result.ok) {
    window.alert(result.message);
    return;
  }
  download(`시드설정_${state.asOf}.json`, JSON.stringify(result.preset, null, 2), 'application/json');
}

function importPreset(file) {
  const reader = new FileReader();
  reader.onload = () => {
    try {
      const preset = JSON.parse(String(reader.result));
      if (preset.asOf) state.asOf = preset.asOf;
      if (preset.owner) state.owner = { ...state.owner, ...preset.owner };
      if (preset.accounts?.length) state.accounts = preset.accounts;
      if (preset.tuning) state.tuning = preset.tuning;
      schedulePreview();
      render();
    } catch (err) {
      window.alert(`설정 파일을 읽을 수 없다: ${err.message}`);
    }
  };
  reader.readAsText(file);
}

function viewFiles() {
  return [
    card('CSV 내려받기', '과거 구간만 받는다. 기준일 이후는 <b>예측이 맞혀야 할 몫</b>이라 넣지 않는다. 엑셀에서 열 때 깨지지 않게 BOM 을 붙여 둔다.', [
      el('div', { class: 'btn-row', style: 'margin-top:0' }, [
        el('button', { class: 'btn', type: 'button', text: '거래 CSV', onclick: () => getCsv('tx'), disabled: !last?.ok }),
        el('button', { class: 'btn ghost', type: 'button', text: '분류 정답표', onclick: () => getCsv('label'), disabled: !last?.ok }),
      ]),
      el('p', { class: 'card-note', style: 'margin:16px 0 0' }, richText(
        '<b>분류 정답표</b>는 거래 CSV 와 같은 네 칸에 분류 한 칸을 더한 모양이라, 업로드된 거래에 그대로 조인된다. '
        + '⚠ 우리가 만든 적요를 우리가 만든 라벨로 채점하는 것이라 "분류기가 잘한다"의 근거로는 약하다 — '
        + '고쳤는데 안 깨졌나 보는 회귀용이다.')),
    ]),

    card('설정 파일', '화면에서 맞춘 값을 그대로 CLI 로 넘긴다. 발표 당일 아침에 브라우저를 열 것 없이 이 파일 하나면 된다.', [
      el('div', { class: 'btn-row', style: 'margin-top:0' }, [
        el('button', { class: 'btn', type: 'button', text: '설정 내보내기', onclick: exportPreset }),
        el('label', { class: 'btn ghost', style: 'display:inline-flex;align-items:center;cursor:pointer' }, [
          '설정 불러오기',
          el('input', {
            type: 'file', accept: '.json,application/json', style: 'display:none',
            onchange: (e) => e.target.files[0] && importPreset(e.target.files[0]),
          }),
        ]),
      ]),
      el('div', { class: 'notice', style: 'margin-top:16px' }, [
        el('code', { text: 'node scripts/seed.mjs --tuning 시드설정_' }),
        el('code', { text: `${state.asOf}.json` }),
        el('div', { style: 'margin-top:8px' }, richText(
          '⚠ <b>비밀번호가 들어 있는 파일이다.</b> 저장소에 커밋하지 않는다.')),
      ]),
    ]),
  ];
}

// ── 화면: 직접 넣기 ─────────────────────────────────────────────────────

const direct = { email: '', password: '', businesses: null, accountId: null, csv: '', filename: '', result: null, error: null, busy: false };

async function loadDirectAccounts() {
  direct.busy = true;
  direct.error = null;
  render();
  const result = await post('/api/accounts', { apiBase: state.apiBase, email: direct.email, password: direct.password });
  direct.busy = false;
  if (result.ok) direct.businesses = result.businesses;
  else direct.error = result.message;
  render();
}

async function doDirectImport() {
  direct.busy = true;
  direct.error = null;
  direct.result = null;
  render();
  const result = await post('/api/import', {
    apiBase: state.apiBase,
    email: direct.email,
    password: direct.password,
    accountId: direct.accountId,
    csv: direct.csv,
  });
  direct.busy = false;
  if (result.ok) direct.result = result.result;
  else direct.error = result.message;
  render();
}

function viewDirect() {
  const accounts = (direct.businesses ?? []).flatMap((b) =>
    b.accounts.map((a) => ({ ...a, businessName: b.name })));

  return [
    card('대상 계정', '시드 생성과 무관하게, <b>이미 있는 계정</b>의 연결 계좌에 CSV 파일을 올린다. 토큰을 서버에 두지 않고 요청마다 로그인한다.', [
      el('div', { class: 'fields' }, [
        field({ type: 'text', label: '이메일', max: 100 }, direct.email, (v) => { direct.email = v; }),
        field({ type: 'text', label: '비밀번호', max: 60, secret: true }, direct.password, (v) => { direct.password = v; }),
      ]),
      el('div', { class: 'btn-row' }, el('button', {
        class: 'btn', type: 'button', disabled: direct.busy || !direct.email || !direct.password,
        text: direct.busy ? '불러오는 중…' : '연결 계좌 불러오기',
        onclick: loadDirectAccounts,
      })),
    ]),

    direct.error && card(null, null, el('div', { class: 'err' }, [
      el('div', { class: 'err-title', text: '실패' }),
      el('div', { class: 'err-msg', text: direct.error }),
    ])),

    direct.businesses && card('올릴 계좌', null,
      accounts.length
        ? el('div', { class: 'scroll' }, el('table', {}, [
          el('thead', {}, el('tr', {}, [
            el('th', { text: '' }), el('th', { text: '사업장' }), el('th', { text: '별칭' }),
            el('th', { text: '계좌번호' }), el('th', { text: '상태' }),
          ])),
          el('tbody', {}, accounts.map((a) => el('tr', {}, [
            el('td', {}, el('input', {
              type: 'radio', name: 'direct-account', checked: direct.accountId === String(a.accountId),
              style: 'width:18px;height:18px;accent-color:var(--blue)',
              onchange: () => { direct.accountId = String(a.accountId); render(); },
            })),
            el('td', { text: a.businessName }),
            el('td', { text: a.alias }),
            el('td', { class: 'mono', text: a.accountNoMasked ?? '—' }),
            el('td', {}, el('span', { class: `type-badge${a.status === 'CONNECTED' ? ' in' : ' bad'}`, text: a.status })),
          ]))),
        ]))
        : el('div', { class: 'empty-state', text: '연결된 계좌가 없다' })),

    direct.businesses && card('CSV 파일', '명세 7.4 의 형식. 첫 줄은 <b>거래일시,적요,입금액,출금액,거래후잔액</b> 이고 날짜는 yyyy-MM-dd HH:mm:ss 하나뿐이다.', [
      el('div', { class: 'btn-row', style: 'margin-top:0' }, [
        el('label', { class: 'btn ghost', style: 'display:inline-flex;align-items:center;cursor:pointer' }, [
          direct.filename || 'CSV 고르기',
          el('input', {
            type: 'file', accept: '.csv,text/csv', style: 'display:none',
            onchange: (e) => {
              const file = e.target.files[0];
              if (!file) return;
              const reader = new FileReader();
              reader.onload = () => {
                // BOM 이 붙어 있으면 첫 헤더 이름이 안 맞는다. 여기서 떼고 올린다.
                direct.csv = String(reader.result).replace(/^\uFEFF/, '');
                direct.filename = file.name;
                render();
              };
              reader.readAsText(file, 'utf-8');
            },
          }),
        ]),
        el('button', {
          class: 'btn', type: 'button',
          disabled: direct.busy || !direct.accountId || !direct.csv,
          text: direct.busy ? '올리는 중…' : '이 계좌에 올린다',
          onclick: doDirectImport,
        }),
      ]),
      direct.csv && el('div', { class: 'notice', style: 'margin-top:16px' },
        `${direct.filename} · ${num(direct.csv.trim().split('\n').length - 1)}행`),
    ]),

    direct.result && card(null, null, [
      el('div', { class: 'ok-banner', text: '✓ 업로드 접수' }),
      el('div', { class: 'sum' }, [
        sumTile('신규', `${num(direct.result.importedCount ?? 0)}건`),
        sumTile('중복', `${num(direct.result.duplicateCount ?? 0)}건`),
        sumTile('거부', `${num((direct.result.rejectedRows ?? []).length)}건`),
      ]),
    ]),
  ];
}

// ── 그리기 ──────────────────────────────────────────────────────────────

const RENDERERS = {
  curve: viewCurve,
  rows: viewRows,
  params: viewParams,
  items: viewItems,
  account: viewAccount,
  run: viewRun,
  files: viewFiles,
  direct: viewDirect,
};

function render() {
  const spec = viewSpec(state.view);
  $('view-title').textContent = spec.title;
  fill($('view-note'), richText(spec.note));
  const tag = $('view-tag');
  tag.textContent = spec.write ? '쓰기' : '';
  tag.className = spec.write ? 'tag write' : 'tag';
  fill($('slot'), RENDERERS[state.view]());
}

// ── 기동 ────────────────────────────────────────────────────────────────

async function main() {
  boot = await (await fetch('/api/boot')).json();

  const saved = loadState();
  state.view = RENDERERS[saved.view] ? saved.view : 'curve';
  state.asOf = saved.asOf ?? boot.today;
  state.apiBase = saved.apiBase ?? boot.apiBase;
  state.owner = { ...boot.defaults.owner, ...(saved.owner ?? {}) };
  // 비밀번호는 저장하지 않는다. 비어 있으면 기본값을 다시 넣는다 — 시드 계정은 코드에 있다.
  if (!state.owner.password) state.owner.password = boot.defaults.owner.password;
  state.accounts = saved.accounts ?? structuredClone(boot.defaults.accounts);
  state.tuning = mergeTuning(boot.defaults.tuning, saved.tuning);
  if (Array.isArray(saved.steps)) {
    // 저장 뒤에 생긴 단계는 기본값을 따른다 — 안 그러면 어제 열어 둔 탭이 옛 파이프라인을
    // 돌린다. knownSteps 가 없는 옛 저장본은 아는 단계가 하나도 없는 것으로 친다.
    const known = new Set(saved.knownSteps ?? []);
    state.steps = [...new Set([...saved.steps, ...state.steps.filter((key) => !known.has(key))])];
  }

  fill($('boot'),
    el('div', { text: boot.keyReady ? `apiKey …${boot.keyTail}` : 'apiKey 없음 — backend/.env' }),
    el('div', { text: boot.envFound ? 'backend/.env 읽음' : 'backend/.env 없음' }),
    el('div', { text: '개발 전용. 배포 경로에 넣지 않는다.' }));

  renderNav();
  renderCtx();
  render();
  runPreview();
}

main();
