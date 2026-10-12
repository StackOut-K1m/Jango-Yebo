/* ══════════════════════════════════════════════════════════════
   잔고예보 v4 · 공용 자료와 도구

   자료를 화면마다 손으로 적지 않습니다. 여기 한 곳에서 계산합니다.
   그래야 PC 와 모바일의 숫자가 어긋날 수 없습니다.

   ── 지켜야 하는 것 ──
   자리와 값은 애니메이션에 매달지 않습니다.
   requestAnimationFrame 은 화면 밖 프레임에서 오지 않으므로,
   값을 먼저 못박고 움직임을 그 위에 얹습니다. 타이머로 한 번 더 확정합니다.
   ══════════════════════════════════════════════════════════════ */

var BASE_TIME = '2026년 8월 31일 월요일 오전 11시 20분';
var STORE     = '온기식당 · 성수';
var SAFETY    = 2300000;   /* 안전선 · 최근 30일 고정비 기준 (P1-08) */
var VAT_HELD  = 1873200;   /* 부가세 예수금 (P1-11) */
var ALERTS    = 2;

/* 오늘 확정 잔고부터 서른 날. 예보 엔진의 출력입니다. */
var FC = [
  ['2026-08-31',4182400],['2026-09-01',2731600],['2026-09-02',3208900],['2026-09-03',3641200],
  ['2026-09-04',3987500],['2026-09-05',4302800],['2026-09-06',4188300],['2026-09-07',4055700],
  ['2026-09-08',4319600],['2026-09-09',4472100],['2026-09-10',2794600],['2026-09-11',3061800],
  ['2026-09-12',2408300],['2026-09-13',1742900],['2026-09-14',-418300],['2026-09-15',1284700],
  ['2026-09-16',2617400],['2026-09-17',3104800],['2026-09-18',2731200],['2026-09-19',3086500],
  ['2026-09-20',3402100],['2026-09-21',3018700],['2026-09-22',3391400],['2026-09-23',3708900],
  ['2026-09-24',4012600],['2026-09-25',512800],['2026-09-26',1762300],['2026-09-27',2415600],
  ['2026-09-28',2809300],['2026-09-29',3164700]
];

/* 날짜가 정해진 것. 공백의 원인을 여기서 끌어옵니다. */
var FIXED = [
  ['2026-09-01','임차료','박정선 (건물주)',1500000],
  ['2026-09-10','대출 상환','기업은행 · 소진공 2건',1680000],
  ['2026-09-18','4대보험','건강보험공단',214800],
  ['2026-09-21','전기와 가스','한국전력 · 도시가스',468000],
  ['2026-09-25','직원 급여','2명',4820000],
  ['2026-09-25','임차료 관리비','성수제일빌딩 관리단',184000]
];
/* 정산이 들어오지 않는 날 (토 · 일 미정산 + 월요일 휴무) */
var NO_SETTLE = ['2026-09-12','2026-09-13','2026-09-14'];

/* 오늘 오갈 돈 (화면구성도 5.2 · 오늘 예정) */
var TODAY_ITEMS = [
  ['카드 정산','KG이니시스 · 오전 6시 입금 완료','in',1043200,'CONFIRMED'],
  ['배달의민족 정산','화요일 새벽 · 82만~96만원','in',912400,'RANGE'],
  ['재료 매입','삼우유통 · 오후 예정','out',318400,'PREDICTED']
];
/* 확인할 일 (화면구성도 5.2 · SC-02-2) */
var TODO = [
  ['반복 후보 2건','매주 화요일 정산금으로 보입니다','patterns'],
  ['분류 확인 5건','새 거래처 3곳이 섞여 있습니다','tx'],
  ['증빙 누락 3건','3만원 넘는 지출입니다','evidence']
];

var BRIEF_HL = '정산 3일 공백이 대출 상환과 겹칩니다';
var BASIS    = '확정 11건 · 예측 7건 · 직접 입력 2건 기준';
var WARN     = '쿠팡이츠 정산은 관측이 4회라 범위를 넓게 잡았습니다. 관측이 6회를 넘으면 좁아집니다.';

/* 급여 적립과 예보 정확도 — PC 와 모바일 홈이 같은 값을 씁니다.
   두 화면에 따로 적어 두면 한쪽만 고쳐지고 갈립니다. */
var RESERVE = { goal:4820000, saved:1873000, today:284000, days:7 };
RESERVE.pct = Math.round(RESERVE.saved / RESERVE.goal * 1000) / 10;
var ACCURACY = { pct:84.2, dayGap:1.3, amtGap:3.2 };

/* ── 도구 ── */
/* 금액. 음수는 빼기 기호(−)로 적습니다. 하이픈(-)은 글꼴에서 짧아 붙임표로 읽힙니다.
   부호를 직접 붙이는 곳(m-calendar · pc-safety · pc-gap)은 모두 양수 차이를 넘기므로 겹치지 않습니다. */
function won(n) { var v = Math.round(n); return (v < 0 ? '−' : '') + Math.abs(v).toLocaleString('ko-KR'); }
/* 조사. 앞말의 끝소리를 보고 고릅니다. "직원 급여과" · "관리비이" 같은 문장을 막습니다.
   두 짝을 어느 순서로 적어도 됩니다 — 받침 쪽을 표에서 찾습니다. */
var JOSA_JONG = ['이','을','은','과','으로','아','이라','이나','이랑'];   /* 받침 있을 때 쓰는 쪽 */
function josa(word, pick) {
  var p = String(pick).split('/');
  var withJong = JOSA_JONG.indexOf(p[0]) >= 0 ? p[0] : p[1];
  var noJong   = withJong === p[0] ? p[1] : p[0];
  var w = String(word).replace(/[)]s]+$/, ''), last = w.slice(-1), c = last.charCodeAt(0);
  var jong;
  if (c >= 0xac00 && c <= 0xd7a3) jong = (c - 0xac00) % 28 !== 0;
  else if (/[0-9]/.test(last)) jong = '1367800'.indexOf(last) >= 0;   /* 일 삼 육 칠 팔 영 — 받침 있는 숫자 */
  else jong = /[lmnr]/i.test(last);
  return word + (jong ? withJong : noJong);
}

function man(n) { var v = Math.round(n/10000); return (v<0?'−':'')+Math.abs(v).toLocaleString('ko-KR')+'만'; }
function md(s)  { var d=s.split('-'); return +d[1]+'.'+(+d[2]); }
function mdKo(s){ var d=s.split('-'); return +d[1]+'월 '+(+d[2])+'일'; }
var DOW = ['일','월','화','수','목','금','토'];

/* 위험 등급 (공통규약 2.6). DANGER 는 안전선의 25% 미만입니다. */
/* 위험 등급 — 안전선을 받습니다.
   지갑마다 안전선이 다릅니다. 성수 혼자면 230만이지만
   서울숲과 통장을 합치면 두 가게 몫을 더한 380만이 기준입니다.
   전역 SAFETY 로만 재면 "부족한데 안전" 같은 말이 나옵니다. */
function riskFor(v, line) {
  var L = line || SAFETY;
  if (v < 0)       return 'gap';
  if (v < L * 0.25) return 'danger';
  if (v < L)        return 'caution';
  return 'safe';
}
function riskOf(v) { return riskFor(v, SAFETY); }
var KO   = { safe:'안전', caution:'주의', danger:'위험', gap:'공백' };
/* 등급 순서와 표시 조각 — 여러 화면이 같은 방식으로 써야 합니다 */
var RANK = { safe:0, caution:1, danger:2, gap:3 };
function mark(r)  { return '<span class="mk mk-' + r + '"></span>'; }
function badge(r) { return '<span class="st st-' + r + '">' + mark(r) + WORD[r] + '</span>'; }

/* 상태말. 전에는 날씨 낱말(맑음 · 흐림 · 비 · 폭풍)이었습니다.
   색으로만 분위기를 내고, 말은 뜻이 바로 읽히는 상태말을 씁니다.
   색 하나로만 두면 화면구성도 6.1(색상 단독 금지)에 걸리므로 말과 모양은 남깁니다. */
var WORD = { safe:'안전', caution:'주의', danger:'위험', gap:'공백' };
var SKYC = {
  safe:    ['#0c2c55','#2a6ba5','#8dbfe2'],
  caution: ['#3a2a4c','#8a5f3d','#d8ab72'],
  danger:  ['#46201b','#a3462a','#d98d5f'],
  gap:     ['#250e0b','#7a1f18','#b3452c']
};

/* ── 파생값 ──
   FC 와 SAFETY 에서 나오는 값들입니다. 함수로 둔 까닭:
   가게를 바꾸면 지갑 곡선과 안전선이 달라지므로 다시 내야 합니다.
   전에는 여기가 한 번만 도는 코드여서, 왕십리를 골라도 예보가
   성수 지갑의 4,182,400원을 그대로 보여 주었습니다. */
var days, today, lowest, badDays, firstBad, dDay, gaps;

function deriveForecast() {
  days = FC.map(function (d) {
    var dt = new Date(d[0]);
    return { date:d[0], v:d[1], risk:riskOf(d[1]), dow:DOW[dt.getDay()] };
  });
  today  = days[0];
  lowest = days.reduce(function (a, c) { return c.v < a.v ? c : a; }, days[0]);
  badDays = days.filter(function (d) { return d.risk !== 'safe'; });
  /* 안전선 아래인 날이 하나도 없는 가게도 있습니다. 그때는 가장 낮은 날을 씁니다. */
  firstBad = badDays[0] || lowest;
  dDay = Math.round((new Date(firstBad.date) - new Date(today.date)) / 86400000);

  /* 안전선 연속 하회 구간 = 정산 공백 (API 10.2) */
  gaps = [];
  days.forEach(function (d, i) {
    if (d.risk === 'safe') return;
    var last = gaps[gaps.length - 1];
    if (last && last.endIdx === i - 1) { last.endIdx = i; last.items.push(d); }
    else gaps.push({ startIdx:i, endIdx:i, items:[d] });
  });
  gaps.forEach(function (g) {
    g.worst = g.items.reduce(function (a, c) { return c.v < a.v ? c : a; }, g.items[0]);
    g.shortfall = SAFETY - g.worst.v;
    g.recover = days[g.endIdx + 1] ? days[g.endIdx + 1].date : null;
    g.level = g.items.some(function (d) { return d.risk === 'gap'; }) ? 'gap'
            : g.items.some(function (d) { return d.risk === 'danger'; }) ? 'danger' : 'caution';
  });
}
deriveForecast();

/* ── 날씨 글리프 ── */
/* 해 · 구름 · 비 그림(glyph)은 뺐습니다. 상태는 색 · 모양 표지(.mk) · 상태말로 말합니다. */

/* ── 하늘 칠하기 ── */
function paintSky(d) {
  var sky = document.querySelector('.sky');
  var k = SKYC[d.risk];
  sky.dataset.w = d.risk;
  sky.style.setProperty('--k0',k[0]); sky.style.setProperty('--k1',k[1]); sky.style.setProperty('--k2',k[2]);
  var set = function (id, html) { var e = document.getElementById(id); if (e) e.innerHTML = html; };
  set('word', WORD[d.risk]);
  /* 상태 뱃지는 말이 아니라 안전선과의 거리를 말합니다. 말은 옆 .word 가 이미 했습니다. */
  set('state', d.v >= SAFETY ? '안전선 위 +' + man(d.v - SAFETY) : '안전선 아래 −' + man(SAFETY - d.v));
  /* 해 · 구름 · 비 그림 대신 다른 화면과 같은 모양 표지(원 · 마름모 · 삼각 · 별)를 씁니다 */
  set('glyph', '<span class="mk mk-' + d.risk + '" aria-hidden="true"></span>');
  set('when', d === today ? BASE_TIME + ' 기준' : mdKo(d.date) + ' ' + d.dow + '요일 예보');
  countTo(d.v);

  var short = SAFETY - d.v;
  set('lede', d === today
    ? '안전선 ' + won(SAFETY) + '원보다 <b>' + won(d.v-SAFETY) + '원</b> 위에 있습니다. '
      + '부가세 예수금 ' + man(VAT_HELD) + '원을 빼면 지금 쓸 수 있는 돈은 <b>' + won(d.v-VAT_HELD) + '원</b>입니다.'
    : (short > 0
        ? '안전선보다 <b>' + won(short) + '원</b> 모자랄 가능성이 있습니다.'
          + (d.v < 0 ? ' 통장이 <b>' + won(-d.v) + '원</b> 빕니다.' : '')
        : '안전선보다 <b>' + won(-short) + '원</b> 위에 있을 것으로 봅니다.'));
}

/* ── 숫자 세기 ──
   값을 먼저 못박고, 세는 동작을 그 위에 얹습니다. 타이머로 최종값을 한 번 더 확정합니다. */
var _cv = 0, _raf, _fix;
/* 막대 너비는 값입니다. 등장 감지가 안 돌아도 반드시 칠합니다. */
function paintRails(root) {
  (root || document).querySelectorAll('.rail i, .pbar i').forEach(function (i) {
    if (i.dataset.w != null) i.style.width = i.dataset.w + '%';
  });
}

function countTo(t) {
  var el = document.getElementById('bigv'); if (!el) return;
  var from = _cv, D = 620;
  _cv = t; el.textContent = won(t);
  if (matchMedia('(prefers-reduced-motion: reduce)').matches) return;
  cancelAnimationFrame(_raf); clearTimeout(_fix);
  _fix = setTimeout(function () { el.textContent = won(t); }, D + 80);
  var s = performance.now();
  /* step 을 동기로 부르면 p=0 이라 방금 쓴 값을 0 으로 덮습니다.
     첫 프레임이 늦게 오면(화면 밖 · 백그라운드 탭) 애니메이션을 버리고 값만 남깁니다. */
  _raf = requestAnimationFrame(function step(now) {
    if (now - s > 250) { el.textContent = won(t); return; }
    var p = Math.min(1,(now-s)/D), e = 1-Math.pow(1-p,3);
    el.textContent = won(from + (t-from)*e);
    if (p < 1) _raf = requestAnimationFrame(step); else el.textContent = won(t);
  });
}

/* ── 예보 띠 ──
   막대는 내용입니다. 등장 애니메이션이 안 돌아도 보여야 합니다. */
var _view = 30, _picked = 0;   /* _view 는 며칠분인가 (칸 수) */
/* ── 띠에서 고른 구간과 고른 날 ──
   _view   보고 있는 기간. 버튼에 적힌 그대로 7 · 14 · 30 입니다
   _picked 고른 날의 인덱스
   화면들이 이 둘을 함께 봐야 하므로 도우미로 꺼내 둡니다. */
function _sliceDays() { return days.slice(0, Math.min(_view, days.length)); }

/* 고른 날이 바뀔 때 화면이 받아 갈 자리.
   예보 화면은 「왜 비는가」와 「오늘의 셈」을 그날 기준으로 다시 그립니다. */
var _onPick = null;
function onPick(fn) { _onPick = fn; }


function selectDay(i, doScroll) {
  var strip = document.getElementById('strip'); if (!strip) return;
  var max = _sliceDays().length - 1;
  i = Math.max(0, Math.min(i, max));
  _picked = i;
  strip.querySelectorAll('.day').forEach(function (o) {
    o.setAttribute('aria-pressed', String(+o.dataset.i === i));
  });
  paintSky(days[i]);
  markCurve(i);
  if (_onPick) _onPick(days[i], i);
  if (doScroll) {
    /* 위치는 값입니다. scrollIntoView({behavior:'smooth'}) 는 프레임이 와야 움직이는데
       화면 밖 · 백그라운드에서는 프레임이 오지 않아 클릭해도 띠가 그 자리에 머물렀습니다.
       scrollLeft 를 직접 놓고, 부드럽게 보이는 것은 CSS scroll-behavior 가 맡습니다.
       움직임이 안 돌아도 위치는 맞습니다. */
    var b = strip.querySelector('.day[data-i="' + i + '"]');
    if (b) {
      var sw = strip.clientWidth, bl = b.offsetLeft, bw = b.offsetWidth, cur = strip.scrollLeft;
      var target;
      if (doScroll === 'nearest') {
        if (bl < cur) target = bl - 8;
        else if (bl + bw > cur + sw) target = bl + bw - sw + 8;
        else target = cur;
      } else {
        target = bl + bw / 2 - sw / 2;                 /* 가운데로 */
      }
      target = Math.max(0, Math.min(target, strip.scrollWidth - sw));
      strip.scrollLeft = target;                       /* CSS scroll-behavior 가 부드럽게 옮깁니다 */
      /* 부드러운 스크롤은 프레임이 와야 진행됩니다. 화면 밖 · 백그라운드에서는
         프레임이 오지 않아 위치가 그 자리에 머무릅니다. 320ms 안에 도착하지 못하면
         애니메이션을 끄고 즉시 놓습니다. 위치는 값이라 움직임에 맡기지 않습니다. */
      clearTimeout(strip._snap);
      strip._snap = setTimeout(function () {
        if (Math.abs(strip.scrollLeft - target) > 2) {
          var prev = strip.style.scrollBehavior;
          strip.style.scrollBehavior = 'auto';
          strip.scrollLeft = target;
          strip.style.scrollBehavior = prev;
        }
      }, 320);
    }
  }
}

function buildStrip(view) {
  var strip = document.getElementById('strip'); if (!strip) return;
  if (view) _view = view;
  var d = _sliceDays();
  var vs = d.map(function (x) { return x.v; });
  var lo = Math.min.apply(null, vs.concat([0])), hi = Math.max.apply(null, vs.concat([SAFETY]));
  var H = 74, zero = H * (0 - lo) / (hi - lo);
  var h = function (v) { return Math.max(3, Math.abs(v) / (hi - lo) * H); };
  var C = { safe:'var(--safe)', caution:'var(--caution)', danger:'var(--danger)', gap:'var(--gap)' };

  strip.innerHTML = d.map(function (x, i) {
    var neg = x.v < 0;
    return '<button class="day' + (x.risk !== 'safe' ? ' bad' : '') + '" data-i="' + i + '"'
      + ' aria-pressed="' + (i === _picked) + '" style="transition-delay:' + Math.min(i*26,700) + 'ms">'
      + '<span class="dow' + (x.dow === '일' ? ' sun' : '') + '">' + x.dow + '</span>'
      + '<span class="dt">' + md(x.date) + '</span>'
      + '<span class="bars">'
        + '<span class="zero" style="bottom:' + zero.toFixed(1) + 'px"></span>'
        + '<span class="line" style="bottom:' + (zero + h(SAFETY)).toFixed(1) + 'px"></span>'
        + '<span class="bar' + (neg ? ' neg' : '') + '" style="'
          + (neg ? 'top:' + (H - zero).toFixed(1) + 'px;' : 'bottom:' + zero.toFixed(1) + 'px;')
          + 'height:' + h(x.v).toFixed(1) + 'px;background:' + C[x.risk]
          + ';transition-delay:' + Math.min(i*26+120,860) + 'ms"></span>'
      + '</span>'
      + '<span class="mk mk-' + x.risk + '" aria-hidden="true"></span>'
      + '<span class="val">' + man(x.v) + '</span></button>';
  }).join('');

  var reveal = function () { strip.querySelectorAll('.day').forEach(function (b) { b.classList.add('in'); }); };
  requestAnimationFrame(reveal); setTimeout(reveal, 60);
  /* 등장 순서를 만들던 인라인 지연은 등장이 끝나면 지웁니다.
     남겨 두면 그 지연이 선택 배경 전환에도 붙어, 끌 때 검은 칸이 최대 700ms 뒤에 따라옵니다. */
  setTimeout(function () {
    strip.querySelectorAll('.day, .day .bar').forEach(function (el) { el.style.transitionDelay = '0ms'; });
  }, 1300);
}

function wireStrip() {
  var strip = document.getElementById('strip'); if (!strip) return;

  /* ══ 이 띠는 슬라이더입니다 ══
     전에는 "스크롤 위치 → 날"로 골랐습니다. 그러면 세 가지가 깨집니다.
       · PC 는 서른 날이 거의 다 보여 스크롤 폭이 7칸뿐이라 8일 뒤는 못 고릅니다
       · 휠 한 번은 100px ≈ 칸 두 개라 하루씩 못 움직입니다
       · 클릭은 가운데로 스크롤하는데 스크롤 감지는 왼쪽 끝 기준이라 서로 싸웁니다
     그래서 선택을 스크롤에서 떼어 냅니다.
       끌기  = 끌린 거리 ÷ 칸 간격 만큼 날이 옮겨 갑니다 (검은 칸이 손가락을 따라옵니다)
       휠    = 한 번에 정확히 하루
       클릭  = 그날을 고르고, 그 칸이 보이도록 띠를 옮깁니다
     띠 자체의 스크롤은 고른 칸이 밖으로 나갈 때만 따라 움직입니다. */

  function pitch() {
    var c = strip.querySelectorAll('.day');
    if (c.length < 2) return 60;
    return c[1].getBoundingClientRect().left - c[0].getBoundingClientRect().left || 60;
  }
  function count() { return strip.querySelectorAll('.day').length; }

  /* 끌기 */
  var down = false, moved = false, sx = 0, startPick = 0, pressed = null;
  strip.addEventListener('pointerdown', function (e) {
    if (e.button !== 0 && e.pointerType === 'mouse') return;
    down = true; moved = false; sx = e.clientX; startPick = _picked;
    pressed = e.target.closest('.day');            /* 누른 칸을 여기서 기억합니다 */
    strip.classList.add('drag'); strip.setPointerCapture(e.pointerId);
  });
  strip.addEventListener('pointermove', function (e) {
    if (!down) return;
    var dx = e.clientX - sx;
    /* 누르기와 끌기를 가르는 문턱. 4px 은 마우스 떨림이나 띠가 아직 스크롤 중일 때
       누른 것까지 끌기로 봐서 클릭이 삼켜졌습니다. 브라우저의 클릭 판정(8~10px)에 맞춥니다.
       끌기 자체는 칸 간격의 절반(30px)을 넘어야 날이 바뀌므로 손맛에는 영향이 없습니다. */
    if (Math.abs(dx) > 8) moved = true;
    /* 왼쪽으로 끌면(dx<0) 시간이 앞으로 갑니다 → 인덱스가 늘어납니다 */
    var i = startPick - Math.round(dx / pitch());
    i = Math.max(0, Math.min(i, count() - 1));
    if (i !== _picked) selectDay(i, 'nearest');
  });
  /* ── 누르기는 pointerup 에서 판정합니다 ──
     setPointerCapture 로 띠가 포인터를 잡으면 브라우저가 만드는 click 의 대상은
     누른 버튼이 아니라 띠 자체가 됩니다(pointerdown 대상과 pointerup 대상의 공통 조상).
     그래서 click 에서 closest('.day') 를 찾으면 null 이라 실제 마우스 클릭이 통째로 무시됐습니다.
     테스트는 button.click() 으로 직접 쏴서 이걸 못 잡았습니다.
     누른 칸은 pointerdown 에서 기억해 두고, 끌지 않았으면 pointerup 에서 고릅니다. */
  ['pointerup', 'pointercancel'].forEach(function (t) {
    strip.addEventListener(t, function (e) {
      down = false; strip.classList.remove('drag');
      if (t === 'pointerup' && !moved && pressed) selectDay(+pressed.dataset.i, true);
      pressed = null;
      if (moved) setTimeout(function () { moved = false; }, 0);
    });
  });

  /* 키보드(Enter · Space)로 누른 버튼만 여기서 받습니다. 마우스는 위에서 끝났습니다.
     detail 이 0 이면 키보드가 만든 click 입니다. */
  strip.addEventListener('click', function (e) {
    if (e.detail !== 0) return;
    var b = e.target.closest('.day'); if (!b) return;
    selectDay(+b.dataset.i, true);
  });

  /* 휠 = 하루. deltaY 크기에 관계없이 방향만 봅니다. */
  strip.addEventListener('wheel', function (e) {
    var d = Math.abs(e.deltaY) > Math.abs(e.deltaX) ? e.deltaY : e.deltaX;
    if (!d) return;
    e.preventDefault();
    var i = Math.max(0, Math.min(_picked + (d > 0 ? 1 : -1), count() - 1));
    if (i !== _picked) selectDay(i, 'nearest');
  }, { passive:false });

  /* 키보드도 슬라이더처럼 */
  strip.addEventListener('keydown', function (e) {
    var step = e.key === 'ArrowRight' ? 1 : e.key === 'ArrowLeft' ? -1
             : e.key === 'Home' ? -999 : e.key === 'End' ? 999 : 0;
    if (!step) return;
    e.preventDefault();
    selectDay(Math.max(0, Math.min(_picked + step, count() - 1)), 'nearest');
  });

  /* 누르면 뜨는 읽음창 */
  var pin = document.getElementById('pin');
  if (pin) {
    strip.addEventListener('pointerover', function (e) {
      var b = e.target.closest('.day'); if (!b) return;
      var d = days[+b.dataset.i], r = b.getBoundingClientRect();
      pin.style.left = (r.left + r.width/2) + 'px'; pin.style.top = r.top + 'px';
      pin.textContent = mdKo(d.date) + ' · ' + won(d.v) + '원 · ' + WORD[d.risk] + '(' + KO[d.risk] + ')';
      pin.classList.add('on');
    });
    strip.addEventListener('pointerout', function () { pin.classList.remove('on'); });
  }

  document.querySelectorAll('.segs button').forEach(function (b) {
    b.addEventListener('click', function () {
      document.querySelectorAll('.segs button').forEach(function (o) {
        o.setAttribute('aria-pressed', String(o === b));
      });
      /* _view 는 칸 수이므로 마지막 인덱스는 n-1 입니다 */
      _picked = Math.min(_picked, +b.dataset.n - 1);
      buildStrip(+b.dataset.n);
      window._curveDays = _sliceDays();
      if (window._curveSize) drawCurve(window._curveSize[0], window._curveSize[1]);
      selectDay(_picked, true);
    });
  });
}

/* ── 하늘의 곡선 ── */
/* 곡선은 띠에서 고른 기간(7 · 14 · 30일)을 따릅니다.
   전에는 항상 서른 날을 그려서, 7일을 눌러도 뒤 그래프가 그대로였습니다.
   띠와 곡선이 같은 구간을 보여 주지 않으면 둘이 다른 이야기를 합니다. */
/* ── 정산 공백 구간 띠 ──
   안전선 아래로 연속해서 내려가는 구간(gaps)을 띠로 깔고 며칠짜리 공백인지 적습니다.
   등급이 공백(0원 아래)이면 공백색, 위험만이면 위험색입니다. 색만 쓰지 않고 글자를 함께 둡니다.
   그릴 구간(days)이 7 · 14일로 잘려 있으면 그 안에 든 부분만 그립니다. */
function gapBands(days, px, py, H, ink) {
  if (typeof gaps === 'undefined' || !gaps.length) return '';
  var first = days[0] ? days[0].date : null, last = days[days.length - 1] ? days[days.length - 1].date : null;
  return gaps.map(function (g) {
    var s = Math.max(0, days.findIndex(function (d) { return d.date === window.days[g.startIdx].date; }));
    var eIdx = days.findIndex(function (d) { return d.date === window.days[g.endIdx].date; });
    if (eIdx < 0) { if (window.days[g.startIdx].date > last) return ''; eIdx = days.length - 1; }
    if (s < 0) s = 0;
    var x0 = s === 0 ? px(0) : (px(s - 1) + px(s)) / 2;
    var x1 = eIdx === days.length - 1 ? px(eIdx) : (px(eIdx) + px(eIdx + 1)) / 2;
    var n = g.endIdx - g.startIdx + 1;
    var col = 'var(--' + (g.level === 'gap' ? 'gap' : 'danger') + ')';
    var label = (g.level === 'gap' ? '공백 ' : '부족 ') + n + '일';
    var lx = (x0 + x1) / 2, anchor = 'middle';
    if (lx < 70) { lx = x0 + 4; anchor = 'start'; } else if (lx > px(days.length - 1) - 70) { lx = x1 - 4; anchor = 'end'; }
    return '<rect class="gapband" x="' + x0.toFixed(1) + '" y="0" width="' + (x1 - x0).toFixed(1) + '" height="' + H + '" fill="' + col + '" fill-opacity="' + (ink ? '.10' : '.22') + '"/>'
      + '<text class="gaplbl" x="' + lx.toFixed(1) + '" y="14" text-anchor="' + anchor + '" fill="' + (ink ? col : '#fff') + '">' + label + '</text>';
  }).join('');
}

function drawCurve(W, H) {
  var el = document.getElementById('curve'); if (!el) return;
  W = W || 700; H = H || 300;
  window._curveSize = [W, H];                        /* 다시 그릴 때 쓸 크기 */
  var PAD = 10;
  var days = window._curveDays || _sliceDays();      /* 그릴 구간 */
  /* 하늘 위에서는 흰 선, 땅(크림 바탕) 위에서는 잉크 선. 홈은 곡선을 띠 옆으로 내렸습니다. */
  var ink = !!window._curveInk, col = ink ? 'var(--ink)' : '#fff';
  var lineCol = ink ? 'var(--danger)' : '#fff';
  var vs = days.map(function (d) { return d.v; });
  var lo = Math.min.apply(null, vs.concat([0])), hi = Math.max.apply(null, vs.concat([SAFETY]));
  var pad = (hi-lo)*0.22; lo -= pad*0.5; hi += pad;
  var px = function (i) { return PAD + (W-PAD*2)*i/(days.length-1); };
  var py = function (v) { return H-18-(H-46)*(v-lo)/(hi-lo); };

  var line = days.map(function (d,i) { return (i?'L':'M')+px(i).toFixed(1)+' '+py(d.v).toFixed(1); }).join(' ');
  var area = line + ' L'+px(days.length-1).toFixed(1)+' '+H+' L'+px(0).toFixed(1)+' '+H+' Z';

  el.setAttribute('viewBox', '0 0 '+W+' '+H);
  el.innerHTML =
    '<defs><linearGradient id="skyg" x1="0" y1="0" x2="0" y2="1">'
    + '<stop offset="0" stop-color="' + col + '" stop-opacity="' + (ink ? '.10' : '.22') + '"/><stop offset="1" stop-color="' + col + '" stop-opacity="0"/>'
    + '</linearGradient></defs>'
    + '<path class="fade" d="'+area+'" fill="url(#skyg)"/>'
    + '<line class="fade" x1="'+PAD+'" y1="'+py(SAFETY).toFixed(1)+'" x2="'+(W-PAD)+'" y2="'+py(SAFETY).toFixed(1)
      + '" stroke="' + lineCol + '" stroke-opacity="' + (ink ? '.7' : '.5') + '" stroke-width="1.5" stroke-dasharray="7 5"/>'
    + '<path class="draw" d="'+line+'" fill="none" stroke="' + col + '" stroke-width="2.6"'
      + ' stroke-linejoin="round" stroke-linecap="round" opacity=".95"/>'
    + gapBands(days, px, py, H, ink)
    + days.filter(function (d) { return d.risk!=='safe'; }).map(function (d) {
        var i = days.indexOf(d);
        return '<circle class="fade" cx="'+px(i).toFixed(1)+'" cy="'+py(d.v).toFixed(1)+'" r="3.4" fill="' + col + '"/>';
      }).join('')
    + '<g class="mark" id="curveMark" style="display:none">'
    + '<line y1="0" y2="' + H + '"/><circle r="4.2"/><text text-anchor="middle"/></g>';
  /* 표식이 쓸 좌표. 그릴 때마다 남겨 두면 표식은 다시 그리지 않고 옮기기만 합니다. */
  window._curveGeom = { px:px, py:py, n:days.length, H:H, W:W, days:days };
  markCurve(typeof _picked === 'number' ? _picked : 0);
}

/* 고른 날을 곡선 위에 표시합니다. 끌면 점과 세로선이 함께 움직입니다.
   위치는 transform 으로 값으로 놓고, 부드러움은 CSS transition 이 맡습니다. */
function markCurve(i) {
  var g = document.getElementById('curveMark'), G = window._curveGeom;
  if (!g || !G) return;
  if (i < 0 || i >= G.n) { g.style.display = 'none'; return; }
  var d = G.days[i], x = G.px(i), y = G.py(d.v);
  g.style.display = '';
  g.setAttribute('transform', 'translate(' + x.toFixed(1) + ',0)');
  var c = g.querySelector('circle'), t = g.querySelector('text');
  c.setAttribute('cy', y.toFixed(1));
  var above = y > 28;                                  /* 점이 너무 위면 라벨을 아래에 */
  t.setAttribute('y', (above ? y - 12 : y + 20).toFixed(1));
  t.textContent = md(d.date) + ' · ' + man(d.v);
  /* 양 끝에서는 라벨이 잘리지 않게 정렬을 바꿉니다 */
  var edge = x < 60 ? 'start' : x > G.W - 60 ? 'end' : 'middle';
  t.setAttribute('text-anchor', edge);
  t.setAttribute('x', edge === 'start' ? 6 : edge === 'end' ? -6 : 0);
}

/* ── 스크롤로 나타나기 · 관찰이 안 돌아도 내용은 보여야 합니다 ── */
function wireReveal() {
  var els = document.querySelectorAll('.cz, .fig');
  var show = function (el) {
    el.classList.add('in');
    el.querySelectorAll('.rail i, .pbar i').forEach(function (i) { i.style.width = i.dataset.w + '%'; });
  };
  var io = new IntersectionObserver(function (es) {
    es.forEach(function (e) { if (e.isIntersecting) { show(e.target); io.unobserve(e.target); } });
  }, { threshold:.2 });
  els.forEach(function (el, n) { el.style.transitionDelay = Math.min(n*70,500)+'ms'; io.observe(el); });
  paintRails();                                  /* 너비는 등장을 기다리지 않습니다 */
  setTimeout(function () { els.forEach(show); paintRails(); }, 2400);
}

/* ══════════════════════════════════════════════════════════════
   그날의 흐름 · 막을 방법
   홈 아래 절이 답할 질문은 셋입니다.
     이 날 무슨 돈이 오가서 이 잔고가 되나       → dayFlow
     비면 뭘 하면 되나                            → levers
     예보가 더 맞으려면 내가 할 일                 → 화면에서 TODO · PATTERNS 로
   숫자를 줄로 세우는 대신 흐름도(폭포) 하나가 첫 질문을 답합니다.
   ══════════════════════════════════════════════════════════════ */

/* 정산 일평균과 카드 하루치. pc-scenarios 와 같은 값입니다(출처 동일).
   카드 영업일 약 105만 + 배민 84만/7 + 쿠팡 32만/7 = 1,216,000 */
var SETTLE_EST = 1216000;
var CARD_DAY   = 1043200;

/* 그날 오간 돈을 단계로 냅니다. 전날 잔고에서 시작해 그날 잔고로 정확히 닫힙니다.
   고정비는 날짜가 정해진 확정값, 정산은 규칙(휴무·주말이면 0)으로 낸 예보값,
   재료 매입과 잡비는 나머지입니다 — pc-calendar 가 그날 내역을 내는 방식과 같습니다.
   나머지로 두는 까닭: 매일의 재료값을 예보할 근거가 없습니다. 있는 척하지 않습니다.
   오늘(i=0)은 예보가 아니라 실제 오간 돈(TODAY_ITEMS)을 씁니다. */
function dayFlow(i) {
  var d = days[i], steps = [];
  if (i === 0) {
    var net = 0;
    TODAY_ITEMS.forEach(function (t) {
      var v = t[2] === 'in' ? t[3] : -t[3]; net += v;
      steps.push({ k:t[0], s:t[1], v:v, cert:t[4] });
    });
    return { start: d.v - net, end: d.v, steps: steps, real: true };
  }
  var prev = days[i - 1].v, run = prev;
  var settle = NO_SETTLE.indexOf(d.date) >= 0 ? 0 : SETTLE_EST;
  steps.push({ k:'정산 입금', s: settle ? '카드 · 배달 정산 일평균' : '주말이거나 휴무일입니다', v:settle, cert:'PREDICTED' });
  run += settle;
  FIXED.filter(function (f) { return f[0] === d.date; })
       .sort(function (a, b) { return b[3] - a[3]; })
       .forEach(function (f) { steps.push({ k:f[1], s:f[2], v:-f[3], cert:'CONFIRMED' }); run -= f[3]; });
  var rest = d.v - run;                       /* 닫히도록 나머지를 잡비로 */
  /* 정산이 없는 날(주말 · 휴무)엔 가게가 재료를 사지 않습니다. 그날의 하락은
     들어오지 않은 정산 몫입니다. 이걸 '재료 매입'이라 적으면 거짓말이 됩니다(캘린더와 같은 판단). */
  var restK = settle === 0 ? (rest < 0 ? '들어오지 않은 정산 몫' : '그 밖에 들어온 돈')
                            : (rest < 0 ? '재료 매입과 잡비' : '그 밖에 들어온 돈');
  var restS = settle === 0 ? '휴무일 · 정산이 밀린 만큼 비어 있습니다' : '나머지 · 예보 범위 안';
  steps.push({ k:restK, s:restS, v:rest, cert:'PREDICTED' });
  return { start: prev, end: d.v, steps: steps, real: false };
}

/* 그날이 안전선 아래일 때 쓸 수 있는 세 가지. 각각 그날 잔고에 얼마를 더하는지 냅니다.
   계산은 pc-scenarios 와 같은 모델입니다. 여기서는 그날 하나만 봅니다.
     빠른정산   카드 대금이 하루 앞당겨져 그날에 하루치가 더 있습니다 (i ≥ 1)
     고정비 미룸 그날 이전 열흘 안에 나간 가장 큰 고정비를 열흘 미룹니다
     융통       옆 지갑의 여유를 옮깁니다 (store.js 가 있을 때만) */
function levers(i) {
  var d = days[i], out = [];
  if (d.v >= SAFETY) return out;
  var need = SAFETY - d.v;
  if (i >= 1) out.push({ k:'빠른정산 켜기', gain:CARD_DAY,
    s:'카드 대금이 하루 앞당겨집니다 · 수수료 매출의 0.2%', href:'pc-scenarios.html' });
  var t = new Date(d.date).getTime(), best = null;
  FIXED.forEach(function (f) {
    var ft = new Date(f[0]).getTime(), gap = (t - ft) / 86400000;
    if (gap >= 0 && gap < 10 && f[1] !== '직원 급여' && (!best || f[3] > best[3])) best = f;
  });
  if (best) out.push({ k: best[1] + ' 열흘 미루기', gain:best[3],
    s: mdKo(best[0]) + ' · ' + best[2] + ' · 전화로 양해를 구합니다', href:'pc-scenarios.html' });
  if (typeof transferPlan === 'function' && typeof PURSE !== 'undefined') {
    var p = transferPlan(d.date), sum = 0, from = null;
    p.plans.forEach(function (x) { if (x.to === PURSE) { sum += x.amount; from = x.from; } });
    if (sum > 0) out.push({ k: from.name.replace(' 지갑','') + '에서 옮기기', gain:sum,
      s:'옆 가게 지갑의 여유 · 실제 이체는 하지 않습니다', href:'pc-overview.html' });
  }
  out.forEach(function (o) { o.after = d.v + o.gain; o.risk = riskOf(o.after); o.enough = o.gain >= need; });
  return out;
}

/* ── 특보 띠 문구 ── */
function alertText() {
  if (!badDays.length) return '서른 날 모두 안전선 위입니다 · 가장 낮은 날 ' + mdKo(lowest.date) + ' <b>' + won(lowest.v) + '원</b>';
  /* 회복일을 같이 말합니다. "언제 내려가나"만 있고 "언제 올라오나"가 없으면 반은 말한 것입니다. */
  var g = gaps.filter(function (x) { return days[x.startIdx].date === firstBad.date; })[0] || gaps[0];
  var back = g && g.recover ? ' · ' + mdKo(g.recover) + ' 회복' : '';
  return '<b>' + (dDay === 1 ? '내일' : dDay + '일 뒤') + '</b> 안전선 아래로 내려갑니다 · '
    + mdKo(firstBad.date) + back + ' · 가장 깊은 날 ' + mdKo(lowest.date) + ' <b>' + won(lowest.v) + '원</b>';
}
