/* ══════════════════════════════════════════════════════════════
   잔고예보 v4 · 공용 껍데기와 화면별 자료
   sky.js 다음에 불러옵니다.
   ══════════════════════════════════════════════════════════════ */

/* ── PC 상단 줄 (화면구성도 4.2 를 한 줄 nav 로) ── */
var PCNAV = [
  ['home','예보','pc-home.html'],
  ['gap','정산 공백','pc-gap.html'],
  ['calendar','캘린더','pc-calendar.html'],
  ['tx','거래내역','pc-transactions.html'],
  ['patterns','반복·예정','pc-patterns.html'],
  ['scenario','시나리오','pc-scenarios.html'],
  ['evidence','증빙·신고','pc-evidences.html'],
  ['report','리포트','pc-reports.html'],
  ['loan','대출·지원','pc-loans.html'],
  ['safety','자금 안전','pc-safety.html'],
  ['overview','통합','pc-overview.html']
];

/* ── OV-02 사업장 스위처 (P0 · 공통) ──
   가게가 여러 곳이면 "지금 보고 있는 화면이 어느 가게 것인가"가
   화면마다 분명해야 합니다. 성수 화면을 왕십리로 착각하면
   모든 숫자를 잘못 읽습니다. 그래서 껍데기에 답니다.

   같은 지갑을 쓰는 가게는 함께 표시합니다. 잔액을 볼 때
   그 돈이 옆 가게와 섞여 있다는 걸 같이 알아야 하기 때문입니다.

   store.js 를 안 불러온 화면에서는 이름만 보여 주고 조용히 물러납니다. */
function storeSwitch(current) {
  if (typeof SHOPS === 'undefined') return '<span class="chip">' + STORE + '</span>';
  var here = current || SHOPS[0].id;
  var me = SHOPS.filter(function (s) { return s.id === here; })[0] || SHOPS[0];
  var pot = POT[me.pot];
  var mates = pot.shops.filter(function (s) { return s.id !== me.id; });

  return '<span class="sw">'
    + '<button class="chip swb" aria-expanded="false" aria-controls="swm">'
      + me.name
      + (mates.length ? '<span class="shr" title="지갑 공용">공용</span>' : '')
      + '<span class="cv" aria-hidden="true">›</span></button>'
    + '<span class="swm" id="swm" hidden>'
      + '<span class="swh">가게 고르기</span>'
      + SHOPS.map(function (s) {
          var p = POT[s.pot];
          return '<button class="swi" data-s="' + s.id + '"'
            + (s.id === me.id ? ' aria-current="true"' : '') + '>'
            + '<span class="swn">' + s.name + '</span>'
            + '<span class="swd">' + p.name + ' · ' + p.accts.length + '개 통장'
            + (p.shops.length > 1 ? ' · 공용' : '') + '</span></button>';
        }).join('')
      + '<span class="swh">전체</span>'
      + '<a class="swi" href="pc-overview.html">'
        + '<span class="swn">세 가게 통합</span>'
        + '<span class="swd">지갑별 위험과 자금 융통</span></a>'
    + '</span></span>';
}

/* 스위처 열고 닫기. 껍데기를 심은 뒤 한 번 부릅니다. */
function wireSwitch() {
  var b = document.querySelector('.swb'); if (!b) return;
  var m = document.getElementById('swm');
  var set = function (open) { b.setAttribute('aria-expanded', String(open)); m.hidden = !open; };
  b.addEventListener('click', function (e) {
    e.preventDefault(); e.stopPropagation();
    set(b.getAttribute('aria-expanded') !== 'true');
  });
  m.addEventListener('click', function (e) {
    var i = e.target.closest('.swi[data-s]'); if (!i) return;
    e.preventDefault(); e.stopPropagation();
    setShop(i.dataset.s);
    location.replace(location.pathname);      /* 같은 화면을 그 가게로 다시 */
  });
  document.addEventListener('click', function () { set(false); });
  document.addEventListener('keydown', function (e) { if (e.key === 'Escape') set(false); });
}

function pcTop(page) {
  return '<div class="top">'
    + '<a class="wm" href="pc-home.html"><i aria-hidden="true"></i>잔고예보</a>'
    + '<nav>' + PCNAV.map(function (n) {
        return '<a href="' + n[2] + '"' + (n[0] === page ? ' aria-current="page"' : '') + '>' + n[1] + '</a>';
      }).join('') + '</nav>'
    + '<span class="sp"></span>'
    + storeSwitch(typeof CURRENT_SHOP !== 'undefined' ? CURRENT_SHOP : null)
    + '<span class="chip">동기화 <b>11:20</b></span>'
    + '<span class="chip">알림 <b>' + ALERTS + '</b></span>'
    + '</div>';
}

/* ── 모바일 하단 탭 (화면구성도 4.1) ── */
var IC = {
  home: '<path d="M4 11l8-6 8 6M6 10v9h12v-9"/>',
  tx:   '<path d="M4 8h13l-3-3M20 16H7l3 3"/>',
  cal:  '<rect x="4" y="5" width="16" height="15" rx="2"/><path d="M4 10h16M9 3v4M15 3v4"/>',
  all:  '<path d="M4 7h16M4 12h16M4 17h16"/>'
};
function mTabs(page) {
  var t = function (id, label, href, ic, badge) {
    return '<a href="' + href + '"' + (id === page ? ' aria-current="page"' : '') + '>'
      + '<svg class="ic" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" aria-hidden="true">'
      + IC[ic] + '</svg>' + label
      + (badge ? '<span class="n">' + badge + '</span>' : '') + '</a>';
  };
  return '<nav class="tabs" aria-label="주요 메뉴">'
    + t('home','홈','m-home.html','home')
    + t('tx','거래','m-transactions.html','tx',5)
    + '<a class="shoot" href="m-shoot.html" aria-label="영수증 촬영">'
      + '<svg width="23" height="23" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" aria-hidden="true">'
      + '<path d="M3 8.5A2 2 0 0 1 5 6.5h2l1.4-2h7.2L17 6.5h2a2 2 0 0 1 2 2v9a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2Z"/>'
      + '<circle cx="12" cy="13" r="3.4"/></svg></a>'
    + t('cal','캘린더','m-calendar.html','cal')
    + t('all','전체','m-all.html','all')
    + '</nav>';
}

/* 모바일 홈 상단 줄 — 하위 화면(mTop)과 달리 알림 건수를 답니다 */
function mHomeTop() {
  return '<div class="top">'
    + '<span class="wm"><i aria-hidden="true"></i>잔고예보</span>'
    + '<span class="sp"></span>'
    + storeSwitch(typeof CURRENT_SHOP !== 'undefined' ? CURRENT_SHOP : null)
    + '<span class="chip">알림 <b>' + ALERTS + '</b></span>'
    + '</div>';
}

/* 모바일 상단 줄 — 하위 화면은 뒤로 가기를 답니다 */
function mTop(title, back) {
  return '<div class="top">'
    + (back ? '<a class="wm" href="' + back + '" aria-label="뒤로"><span style="font-size:17px">‹</span>' + title + '</a>'
            : '<span class="wm"><i aria-hidden="true"></i>' + title + '</span>')
    + '<span class="sp"></span>'
    + '<span class="chip">' + STORE + '</span>'
    + '</div>';
}

/* ── 작은 하늘 머리 ──
   예보 화면 말고는 하늘을 얇게 씁니다. 화면마다 답이 하나씩 있습니다. */
function skyHead(o) {
  var k = SKYC[o.risk || 'safe'];
  return '<header class="sky slim" data-w="' + (o.risk || 'safe') + '"'
    + ' style="--k0:' + k[0] + ';--k1:' + k[1] + ';--k2:' + k[2] + '">'
    + o.top
    + '<div class="answer">'
      + '<p class="when">' + o.eyebrow + '</p>'
      + '<h1 class="hero">' + o.head + '</h1>'
      + (o.lede ? '<p class="lede">' + o.lede + '</p>' : '')
    + '</div></header>';
}

/* ══════════════════ 화면별 자료 ══════════════════
   숫자는 서로 맞아떨어지게 두었습니다. 같은 거래가 여러 화면에 나옵니다. */

/* SC-04 거래내역 — 8월 말 실제 거래 */
var TX = [
  ['08-31','우아한형제들','배달의민족 정산','in',843200,'정산','AI',0.97,true],
  ['08-31','KG이니시스','카드 정산 · 8/29 매출','in',1043200,'정산','RULE',1,true],
  ['08-31','삼우유통','재료 매입 · 돼지고기 외','out',318400,'재료비','DICT',0.94,false],
  ['08-30','서울우유','재료 매입 · 유제품','out',86700,'재료비','DICT',0.91,false],
  ['08-30','KG이니시스','카드 정산 · 8/28 매출','in',1287600,'정산','RULE',1,true],
  ['08-29','쿠팡이츠서비스','쿠팡이츠 정산','in',318400,'정산','AI',0.88,true],
  ['08-29','대성철물 성수점','소모품 · 주방 집기','out',86400,'기타지출','AI',0.61,false],
  ['08-28','박정선','임차료','out',1500000,'임차료','USER',1,true],
  ['08-28','KG이니시스','카드 정산 · 8/26 매출','in',962300,'정산','RULE',1,true],
  ['08-27','성수농협','재료 매입 · 채소','out',241800,'재료비','DICT',0.93,false],
  ['08-27','한국전력공사','전기요금','out',312400,'공과금','RULE',1,true],
  ['08-26','KG이니시스','카드 정산 · 8/24 매출','in',1104700,'정산','RULE',1,true],
  ['08-26','민현주','아르바이트 급여','out',684000,'급여','AI',0.72,false],
  ['08-25','삼우유통','재료 매입 · 돼지고기','out',412600,'재료비','DICT',0.95,false],
  ['08-25','기업은행','대출 이자','out',187300,'대출상환','RULE',1,true]
];

/* SC-05 반복 후보 (P0-03·04) */
var PATTERNS = [
  ['배달의민족 정산','매주 화요일','in',[782000,961000],6,'HIGH','CANDIDATE',
   '최근 6주 화요일마다 들어왔습니다. 금액은 78만~96만원 사이입니다.'],
  ['쿠팡이츠 정산','매주 금요일','in',[287000,349000],4,'MEDIUM','CANDIDATE',
   '4주 관측했습니다. 관측이 6회를 넘으면 범위가 좁아집니다.'],
  ['삼우유통 재료 매입','영업일마다','out',[180000,462000],22,'HIGH','CANDIDATE',
   '월요일 휴무를 빼고 매 영업일에 나갔습니다.'],
  ['임차료','매월 1일','out',[1500000,1500000],7,'HIGH','CONFIRMED',
   '7개월 동안 금액이 같았습니다.'],
  ['대출 상환','매월 10일','out',[1680000,1680000],7,'HIGH','CONFIRMED',
   '기업은행과 소진공 2건 합계입니다.'],
  ['직원 급여','매월 25일','out',[4780000,4860000],7,'HIGH','CONFIRMED',
   '4대보험 포함. 근무일수에 따라 조금 움직입니다.']
];

/* SC-07 증빙 (P0-10 · P1-02) */
var EVIDENCES = [
  ['대성철물 성수점','2026-08-29',86400,'주방 집기 3점','matched',0.93],
  ['삼우유통','2026-08-31',318400,'돼지고기 · 대파 · 양파','matched',0.98],
  ['성수농협','2026-08-27',241800,'채소 7종','review',0.71],
  ['이마트 트레이더스 성수','2026-08-26',137200,'소모품','unmatched',0.88],
  ['카페 리코드 성수','2026-08-24',24800,'원두','unmatched',0.64]
];

/* SC-06 시나리오 (P0-09 · P0-13) */
var SCENARIOS = [
  ['정산이 이틀 밀리면','정산 지연 +2일',-1, 2, 0, 0],
  ['매출이 15% 줄면','입금 감소 −15%',-2, 0, 15, 0],
  ['냉장고를 지금 바꾸면','추가 지출 280만원',-3, 0, 0, 2800000],
  ['빠른정산을 켜면','카드 대금 하루 당김', 4, -1, 0, 0]
];

/* SC-10 대출·지원 (P1-13 · P1-14 · P1-15) */
var LOANS = [
  ['기업은행 사업자 우대','기업은행',24800000,5.7,'2026-09-10',1180000],
  ['소상공인진흥공단 경영안정','소진공',11600000,2.9,'2026-09-10',500000]
];
var PRODUCTS = [
  ['소상공인 경영안정자금','소진공',2.9,30000000,'9월 15일 마감','자격 확인 필요'],
  ['성동구 소상공인 이자지원','성동구청',0,10000000,'상시','서류 5종'],
  ['기업은행 새희망홀씨','기업은행',4.8,20000000,'상시','신용등급 6등급 이상']
];

/* SC-09 브리핑 (P1-17) */
var BRIEF_BODY = [
  ['이번 주에 있었던 일',
   '재료값이 남는 돈을 밀어냈습니다. 매출은 지난주보다 4.2% 늘었는데 돼지고기 매입가가 kg당 13,400원에서 15,800원으로 올라 재료비가 18.1% 늘었습니다. 남는 돈은 오히려 줄었습니다.'],
  ['다음 주 예보',
   '9월 13일부터 15일까지 안전선 아래로 내려갈 가능성이 있습니다. 9월 10일 대출 상환 1,680,000원이 나가고, 12일부터 14일까지 주말과 휴무가 겹쳐 정산이 들어오지 않습니다.'],
  ['해 볼 만한 것',
   '빠른정산을 켜면 카드 대금이 하루 앞당겨집니다. 수수료는 매출의 0.2%로 한 달 84,368원입니다. 9월 13일과 14일의 공백이 사라지고 15일 하나만 남습니다.']
];

/* SC-03-3 자금 안전 (P1-08 · P1-09 · P1-11) */
var RESERVES = [
  ['9월 급여','급여',4820000,1873000,284000,'2026-09-25'],
  ['부가세 예정고지','세금',1873200,1873200,0,'2026-10-25'],
  ['10월 임차료','고정비',1500000,412000,74000,'2026-10-01']
];

/* ══════════════════════════════════════════════════════════════
   모바일 바닥 시트 (공용)
   네 화면(캘린더 · 거래내역 · 확인 센터 · 증빙)이 같은 판을 씁니다.
   화면마다 따로 만들면 닫기 동작과 손가락 높이가 제각각이 됩니다.

   sheetOpen({title, sub, rows, acts, body}) 로 엽니다.
     rows: [[이름, 값, 숫자인가]] · acts: [[글자, 함수, 주버튼인가]]
   ══════════════════════════════════════════════════════════════ */
function sheetMount() {
  if (document.getElementById('bsdim')) return;
  var dim = document.createElement('div');
  dim.className = 'bsdim'; dim.id = 'bsdim'; dim.hidden = true;
  var bs = document.createElement('div');
  bs.className = 'bs'; bs.id = 'bs'; bs.hidden = true;
  bs.setAttribute('role', 'dialog'); bs.setAttribute('aria-modal', 'true');
  document.body.appendChild(dim); document.body.appendChild(bs);
  dim.addEventListener('click', sheetClose);
  document.addEventListener('keydown', function (e) { if (e.key === 'Escape') sheetClose(); });
}
function sheetClose() {
  var dim = document.getElementById('bsdim'), bs = document.getElementById('bs');
  if (!bs || bs.hidden) return;
  bs.classList.remove('on'); dim.classList.remove('on');
  setTimeout(function () { bs.hidden = true; dim.hidden = true; }, 240);
}
var _sheetActs = [];
function sheetOpen(o) {
  sheetMount();
  var dim = document.getElementById('bsdim'), bs = document.getElementById('bs');
  _sheetActs = (o.acts || []);
  bs.innerHTML = '<div class="bsg" aria-hidden="true"></div>'
    + '<div class="bsh"><span><span class="bst">' + o.title + '</span>'
      + (o.sub ? '<span class="bss">' + o.sub + '</span>' : '') + '</span>'
      + '<button class="bsx" data-x="1" aria-label="닫기">\u00d7</button></div>'
    + '<div class="bsb">'
      + (o.rows ? o.rows.map(function (r) {
          return '<div class="bsr"><span class="k">' + r[0] + '</span>'
            + '<span class="v' + (r[2] ? ' num' : '') + '">' + r[1] + '</span></div>';
        }).join('') : '')
      + (o.body || '')
      + (_sheetActs.length ? '<div class="bsact">' + _sheetActs.map(function (a, i) {
          return '<button class="btn' + (a[2] ? ' pri' : '') + '" data-a="' + i + '">' + a[0] + '</button>';
        }).join('') + '</div>' : '')
    + '</div>';
  bs.onclick = function (e) {
    if (e.target.closest('[data-x]')) return sheetClose();
    var a = e.target.closest('[data-a]');
    if (a) { var fn = _sheetActs[+a.dataset.a][1]; if (fn) fn(); }
  };
  dim.hidden = false; bs.hidden = false;
  requestAnimationFrame(function () { dim.classList.add('on'); bs.classList.add('on'); });
  setTimeout(function () { dim.classList.add('on'); bs.classList.add('on'); }, 40);
}

/* ── 손익 → 현금 다리 (PC · 모바일 공용) ──
   가게가 남긴 돈에서 대표 인출과 월말 미수 증가를 빼면 통장 순변화가 됩니다.
   이 표가 없으면 사장이 "남는 돈 486만"과 "통장 102만 줄었다"를 보고
   어느 쪽이 거짓말인지 묻습니다. 둘 다 참인데 다른 것을 셉니다. */
function bridgeHTML(b, short) {
  var money = function (v) { return (v < 0 ? '−' : '+') + won(Math.abs(v)); };
  return '<div class="bridge">'
    + b.shops.map(function (s) {
        return '<span class="k">' + s.name + '<span class="s">8월 남는 돈</span></span>'
          + '<span class="n">' + money(s.profit) + '</span>';
      }).join('')
    + '<span class="k">대표 인출<span class="s">'
      + (short ? '사장 생활비' : '사장이 생활비로 가져가는 돈입니다. 비용이 아니라 이익의 처분입니다')
      + '</span></span><span class="n">' + money(-b.draw) + '</span>'
    + '<span class="k">월말 미수 정산 증가<span class="s">'
      + (short ? '아직 안 들어온 돈' : '팔았지만 아직 통장에 들어오지 않은 돈이 그만큼 늘었습니다')
      + '</span></span><span class="n">' + money(-b.timing) + '</span>'
    + '<span class="k sum">통장 30일 변화</span>'
    + '<span class="n sum">' + money(b.cash) + '</span>'
    + '<span class="eq">' + won(b.from) + '원에서 ' + won(b.to) + '원으로. '
      + '가게가 남겨도 사장이 가져가면 통장은 늘지 않습니다.</span>'
    + '</div>';
}
