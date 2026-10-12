/* ============================================================================
   잔고예보 목업 동작
   화면 13개 · 기능명세서 P0 13개 / P1 18개 전체 시연용.
   요소가 없어도 죽지 않도록 모든 접근은 on()/each()로 감싼다.
   ========================================================================= */

const $  = (s, r = document) => r.querySelector(s);
const $$ = (s, r = document) => [...r.querySelectorAll(s)];
const on = (sel, ev, fn) => { const el = $(sel); if (el) el.addEventListener(ev, fn); };
const each = (sel, ev, fn) => $$(sel).forEach(el => el.addEventListener(ev, e => fn(e, el)));

/* ------------------------------------------------------------------ Toast -- */
const toastHost = $('#toastHost');
let toastTimer;

/** 되돌릴 수 있는 일에는 확인 창 대신 5초짜리 되돌리기를 붙인다. */
function showToast(message, undo) {
  if (!toastHost) return;
  toastHost.innerHTML = '';
  const box = document.createElement('div');
  box.className = 'toast';
  const text = document.createElement('span');
  text.textContent = message;
  box.appendChild(text);

  if (undo) {
    const btn = document.createElement('button');
    btn.textContent = undo.label || '되돌리기';
    btn.addEventListener('click', () => { undo.run(); hideToast(); });
    box.appendChild(btn);
  }

  toastHost.appendChild(box);
  toastHost.classList.add('show');
  clearTimeout(toastTimer);
  toastTimer = setTimeout(hideToast, undo ? 5000 : 2600);
}
function hideToast() { if (toastHost) toastHost.classList.remove('show'); }

/* ------------------------------------------------------------- 화면 전환 -- */
const views = $$('.view');
const navLinks = $$('[data-view]');
const moreSheet = $('#moreSheet');
const demoSheet = $('#demoSheet');

function closeSheets() {
  if (moreSheet) moreSheet.classList.remove('open');
  if (demoSheet) demoSheet.classList.remove('open');
}

function showView(name) {
  if (!$('#view-' + name)) return;
  views.forEach(v => v.classList.toggle('active', v.id === 'view-' + name));
  navLinks.forEach(a => a.classList.toggle('on', a.dataset.view === name));
  closeSheets();
  window.scrollTo({ top: 0, behavior: 'smooth' });
  if (location.hash !== '#' + name) history.replaceState(null, '', '#' + name);
}

each('[data-view]', 'click', (e, el) => { e.preventDefault(); showView(el.dataset.view); });
each('[data-goto]', 'click', (e, el) => showView(el.dataset.goto));

on('#moreTab', 'click', e => { e.preventDefault(); closeSheets(); moreSheet.classList.add('open'); });
on('#demoTab', 'click', () => { closeSheets(); demoSheet.classList.add('open'); });
each('[data-close-sheet]', 'click', () => moreSheet.classList.remove('open'));
each('[data-close-demo]', 'click', () => demoSheet.classList.remove('open'));

/* ---------------------------------------------- 탭 · 단계 · 목록 선택 -- */

/* 탭 전환 — 모든 화면이 같은 규칙을 쓴다 */
each('[data-tabs] button', 'click', (e, b) => {
  const bar = b.closest('[data-tabs]');
  const key = bar.dataset.tabs;
  $$('button', bar).forEach(x => {
    x.classList.toggle('on', x === b);
    const c = $('.count', x);
    if (c) c.classList.toggle('on', x === b);
  });
  $$(`[data-panelset="${key}"] > .tabpanel`).forEach(p => {
    p.classList.toggle('on', p.dataset.panel === b.dataset.tab);
  });
});

/* 온보딩 단계 전환 (SC-01) */
each('[data-go]', 'click', (e, el) => {
  const n = el.dataset.go;
  $$('[data-panelset="ob"] > .tabpanel').forEach(p => p.classList.toggle('on', p.dataset.panel === n));
  $$('.step[data-go]').forEach(s => {
    const i = s.dataset.go;
    s.classList.toggle('on', i === n);
    s.classList.toggle('done', Number(i) < Number(n));
    const no = $('.no', s);
    if (no) no.textContent = Number(i) < Number(n) ? '✓' : i;
  });
});

/* 목록 단일 선택 — 항목이 전부 button이라 키보드로도 열린다 */
$$('.list').forEach(list => {
  if (!$('button.list-row[aria-selected]', list)) return;
  $$('button.list-row', list).forEach(r => r.addEventListener('click', () => {
    $$('button.list-row', list).forEach(x => x.setAttribute('aria-selected', String(x === r)));
  }));
});

/* 칩 단일 선택 */
$$('.chips').forEach(bar => {
  $$('button', bar).forEach(b => b.addEventListener('click', () => {
    if (b.disabled) return;
    $$('button', bar).forEach(x => x.classList.toggle('on', x === b));
  }));
});

/* --------------------------------------------------------- 지표 계산 근거 -- */
on('#availHelp', 'click', () => {
  const f = $('#availFormula'), h = $('#availHelp');
  const open = f.hasAttribute('hidden');
  f.toggleAttribute('hidden', !open);
  h.setAttribute('aria-expanded', String(open));
});

/* --------------------------------------------------- P0-02 거래 목록 -- */
/* [일시, 적요, 거래처, 방향, 금액, 분류, 확인 상태, 영수증] */
const transactions = [
  ['08.26 09:12', '배달정산', '우아한형제들',      'in',  '+850,000원',    '배달 정산', 'need:86', '없음'],
  ['08.25 21:40', '카드매출입금', '신한카드',       'in',  '+1,230,000원',  '카드 정산', 'done',    '정산표'],
  ['08.25 09:30', '서울우유', '서울우유 협동조합',  'out', '−42,350원',     '재료비',    'need:91', '1장'],
  ['08.24 16:22', '다이소성수', '다이소 성수점',    'out', '−18,900원',     '소모품',    'need:72', '없음'],
  ['08.24 11:05', '성수농산', '성수농산㈜',         'out', '−128,000원',    '재료비',    'need:88', '1장'],
  ['08.23 10:00', '임대료', '좋은건물 임대',        'out', '−1,500,000원',  '임차료',    'fixed',   '계약서'],
  ['08.22 18:30', '쿠팡이츠정산', '쿠팡이츠',       'in',  '+312,500원',    '배달 정산', 'need:79', '없음'],
  ['08.22 08:15', '이마트에브리데이', '이마트 성수', 'out', '−64,200원',     '재료비',    'need:68', '없음'],
  ['08.21 14:02', '가스요금', '서울도시가스',        'out', '−87,400원',     '공과금',    'need:83', '1장'],
  ['08.20 19:55', '알바급여', '김ㅇㅇ',             'out', '−420,000원',    '인건비',    'need:74', '없음']
];

/** 확인 상태를 배지로. 색만으로 뜻을 전하지 않고 글자를 함께 적는다. */
function statusBadge(code) {
  if (code === 'done')  return '<span class="badge safe">확인함</span>';
  if (code === 'fixed') return '<span class="badge safe">확인함 · 매달 반복</span>';
  return '<span class="badge caution">확인 필요 · 자동분류 ' + code.split(':')[1] + '%</span>';
}
function receiptBadge(v) {
  return v === '없음'
    ? '<span class="badge mute">영수증 없음</span>'
    : '<span class="badge safe">' + v + '</span>';
}

const rowsHost = $('#transactionRows');
if (rowsHost) rowsHost.innerHTML = transactions.map((t, i) => `
  <button class="list-row tx-row" data-row="${i}">
    <span>${t[0]}</span>
    <span><b>${t[1]}</b><small>${t[2]}</small></span>
    <span>${t[3] === 'in' ? '들어옴' : '나감'}</span>
    <span class="money">${t[4]}</span>
    <span>${t[5]}</span>
    <span>${statusBadge(t[6])}</span>
    <span>${receiptBadge(t[7])}</span>
  </button>`).join('');

/* 좁은 화면에서는 열을 자르지 않고 카드로 바꾼다 */
const cardsHost = $('#transactionCards');
if (cardsHost) cardsHost.innerHTML = transactions.map((t, i) => `
  <button class="txcard tx-row" data-row="${i}">
    <span class="l1"><b>${t[2]}</b><span class="money">${t[4]}</span></span>
    <span class="l2"><span>${t[0]}</span><span aria-hidden="true">·</span><span>${t[5]}</span></span>
    <span class="l3">${statusBadge(t[6])}${receiptBadge(t[7])}</span>
  </button>`).join('');

/* ------------------------------------------------------------------ Drawer -- */
const drawerHost = $('#drawerHost');
let lastFocus = null;

function openDrawer() {
  lastFocus = document.activeElement;
  drawerHost.classList.add('open');
  const close = $('.drawer [data-close-drawer]', drawerHost);
  if (close) close.focus();
}
function closeDrawer() {
  drawerHost.classList.remove('open');
  if (lastFocus) lastFocus.focus();
}

each('.tx-row', 'click', openDrawer);
each('[data-close-drawer]', 'click', closeDrawer);

on('#confirmClass', 'click', () => {
  closeDrawer();
  showToast('배달 정산으로 확인했습니다 · 다음 예보부터 반영됩니다', {
    label: '되돌리기', run: () => showToast('확인을 되돌렸습니다')
  });
});
on('#rejectClass', 'click', () => {
  const form = $('#classifyForm');
  form.removeAttribute('hidden');
  form.querySelector('select').focus();
});

/* ------------------------------------------------- P0-07 예보 기간 전환 -- */
each('#daysFilter button', 'click', (e, b) => {
  const title = $('#chartTitle');
  if (title) title.textContent = `앞으로 ${b.dataset.days}일 예상 잔고`;
});

/* ------------------------------------- P0-05 반복 거래 승인 · 넘기기 -- */
each('.approve-pattern', 'click', (e, b) => {
  b.textContent = '등록했습니다';
  b.disabled = true;
  showToast('배달의민족 반복 정산을 등록했습니다 · 앞으로 90일치를 만들었습니다', {
    label: '예정 거래 보기', run: () => showView('recurring')
  });
});

/* 넘긴 항목은 목록에서 빼고 되돌리기를 준다. 흐린 카드로 남기지 않는다. */
each('.dismiss-pattern', 'click', (e, b) => {
  const card = b.closest('.pattern-card');
  const parent = card.parentNode;
  const next = card.nextElementSibling;
  card.remove();
  showToast('반복 후보를 넘겼습니다', {
    label: '되돌리기', run: () => parent.insertBefore(card, next)
  });
});

/* ------------------------------------------- P0-06 일회성 거래 Modal -- */
const backdrop = $('#modalBackdrop');
on('#addPlanned', 'click', () => {
  backdrop.hidden = false;
  backdrop.querySelector('input,select').focus();
});
each('.close-modal', 'click', () => { backdrop.hidden = true; });
if (backdrop) backdrop.addEventListener('click', e => { if (e.target === backdrop) backdrop.hidden = true; });
on('#savePlanned', 'click', () => {
  showToast('9월 10일 에스프레소 머신 교체를 예정 거래에 넣었습니다', {
    label: '되돌리기', run: () => showToast('예정 거래를 지웠습니다')
  });
});

document.addEventListener('keydown', e => {
  if (e.key !== 'Escape') return;
  if (backdrop && !backdrop.hidden) backdrop.hidden = true;
  if (drawerHost && drawerHost.classList.contains('open')) closeDrawer();
  closeSheets();
});

/* --------------------------------------- P0-09 · P0-13 시뮬레이션 -- */
const delay = $('#delayDays');
const decrease = $('#decreaseRate');
const BASE_LOW = 1487600;   /* 9월 3일 예상 잔고 */
const SAFE = 2300000;       /* 안전잔고 */

on('#delayDays', 'input', () => { $('#delayValue').textContent = delay.value + '일'; });
on('#decreaseRate', 'input', () => { $('#decreaseValue').textContent = decrease.value + '%'; });

on('#calculateScenario', 'click', () => {
  const d = Number(delay.value);
  const r = Number(decrease.value);
  const extra = Number(String($('#extraSpend').value).replace(/[^0-9]/g, '')) || 0;
  const low = BASE_LOW - d * 260000 - r * 21000 - extra;

  const fmt = n => (n < 0 ? '−' : '') + Math.abs(Math.round(n)).toLocaleString('ko-KR') + '원';
  $('#modifiedBalance').textContent = fmt(low);
  $('#modifiedDate').textContent = '9월 ' + Math.max(1, 3 - d) + '일';
  $('#modifiedDuration').textContent = Math.max(2, d + 3) + '일';
  $('#modifiedSafety').textContent = (d + 5) + '일';
  showToast(low < 0
    ? '이 조건이면 통장이 비는 날이 생깁니다'
    : `이 조건이면 안전잔고보다 ${(SAFE - low).toLocaleString('ko-KR')}원 모자랍니다`);
});

/* 조건 지우기 — 되돌릴 수 있으므로 확인 창 대신 되돌리기 */
each('.condition .rm', 'click', (e, b) => {
  const box = b.closest('.condition');
  const parent = box.parentNode;
  const next = box.nextElementSibling;
  box.remove();
  showToast('조건을 지웠습니다', { label: '되돌리기', run: () => parent.insertBefore(box, next) });
});

/* ----------------------------------------------------- 나머지 동작 -- */
const say = (sel, msg, undo) => on(sel, 'click', () => showToast(msg, undo));

on('#linkEvidence', 'click', e => {
  e.currentTarget.textContent = '붙였습니다';
  e.currentTarget.disabled = true;
  showToast('영수증을 서울우유 거래에 붙였습니다', {
    label: '되돌리기', run: () => showToast('붙인 것을 떼었습니다')
  });
});
say('#uploadEvidence', '파일 고르기 창을 여는 자리입니다');
say('#makeExport', '자료를 만들기 시작했습니다 · 1분쯤 걸립니다');
say('#syncBtn', '다시 불러오는 중입니다');
say('#syncNow', '금융망에서 거래를 다시 읽고 있습니다 · 202 Accepted');
say('#shopSwitch', '온기식당 성수점 / 연남점 중에서 고르는 자리입니다');
say('#savedScenarios', '저장한 시나리오 3개를 여는 자리입니다');
on('#reviewAll', 'click', () => { showView('transactions'); openDrawer(); });
on('#reconnectRow', 'click', () => { showView('transactions'); showToast('자료 가져오기 탭에서 다시 연결할 수 있습니다'); });

/* ------------------------------------------------- P1-18 캘린더 -- */
/* 2026년 9월 1일은 화요일. 셀에는 유형만 적고 금액은 상세에서 본다. */
const calendar = $('#calendarGrid');
if (calendar) {
  const weekdays = ['일', '월', '화', '수', '목', '금', '토'];
  const dayEvents = {
    3:  [['급여', 'risk'], ['배달 정산 밀림', '']],
    5:  [['밀린 배달 정산', '']],
    8:  [['배달 정산', '']],
    10: [['세금·4대보험', ''], ['대출 상환', '']],
    15: [['배달 정산', '']],
    22: [['배달 정산', '']],
    23: [['임차료', '']],
    25: [['공과금', ''], ['마통 이자', '']],
    29: [['배달 정산', '']]
  };
  const riskDays = [3, 4];

  let html = weekdays.map(d => `<div class="calendar-day head">${d}</div>`).join('');
  html += [30, 31].map(d => `<div class="calendar-day muted"><span class="daynum">8/${d}</span></div>`).join('');
  for (let d = 1; d <= 30; d++) {
    const risk = riskDays.includes(d);
    const events = (dayEvents[d] || [])
      .map(([label, cls]) => `<span class="calendar-event ${cls}">${label}</span>`).join('');
    html += `<div class="calendar-day ${risk ? 'risk' : ''} ${d === 3 ? 'selected' : ''}" data-day="${d}">
      <span class="daynum">${d}</span>${events}
      <button aria-label="9월 ${d}일 상세 보기"></button>
    </div>`;
  }
  calendar.innerHTML = html;

  $$('[data-day]', calendar).forEach(cell => cell.addEventListener('click', () => {
    $$('.calendar-day', calendar).forEach(c => c.classList.remove('selected'));
    cell.classList.add('selected');
    $('#selectedDate').textContent = `9월 ${cell.dataset.day}일`;
  }));
}

/* ------------------------------------------------------------------ 시작점 -- */
const initial = location.hash.replace('#', '');
if (initial) showView(initial);
