/* ══════════════════════════════════════════════════════════════
   잔고예보 v4 · 다사업장 (P1-12 · SC-03-4 · API 19)
   sky.js 다음에 불러옵니다.

   ── 설계의 핵심: 잔액은 자금 묶음 것이고, 수익은 가게 것입니다 ──

   가게가 여러 개면 두 가지 질문이 생기는데 답의 단위가 다릅니다.

   "어느 가게가 버나"  → 가게 단위로 답할 수 있습니다.
                          거래를 가게에 귀속시키면 됩니다.
                          (TRANSACTION.business_id 가 이미 있습니다)

   "돈이 마르나"       → 가게 단위로 답할 수 없습니다.
                          돈은 가게가 아니라 통장에 있습니다.

   ── 자금 묶음(pot) ──
   계좌와 가게의 관계는 양쪽으로 다 갈립니다.

     한 계좌를 여러 가게가 쓴다     사장이 통장 하나로 두 매장을 돌립니다.
                                    그 통장 잔액은 두 가게 몫이 섞여 있어서
                                    가게별로 나눌 수 없습니다.

     한 가게가 여러 계좌를 쓴다     주계좌 · 배달앱 정산용 · 세금용처럼
                                    통장을 나눠 두지만 사장 머릿속에서는
                                    하나입니다. 매일 옮겨 쓰기 때문입니다.

   그래서 판정 단위를 계좌도 가게도 아닌 자금 묶음으로 둡니다.
   묶음 = 사장이 "같이 본다"고 정한 계좌들의 집합.
   묶음 안에서는 돈이 자유롭게 오갑니다. 묶음 사이에만 이체가 필요합니다.

   묶음은 시스템이 정할 수 없습니다. 통장 성격을 아는 건 사장뿐입니다.
   세금용 통장을 생활비 통장과 묶으면 안 되는데, 그 판단은 사람의 것입니다.
   그래서 묶음 구성은 설정(SET-03 계좌·데이터 관리)에서 사장이 정합니다.

   여기서 따라오는 것들:
   · 같은 묶음을 쓰는 가게 사이에는 "융통"이 없습니다. 이미 한 지갑입니다.
     시뮬레이터는 묶음이 다른 가게 사이에서만 이체안을 만듭니다.
   · 위험 등급 · 안전선 · 공백은 묶음 단위로만 말할 수 있습니다.
   · 공유 통장 거래 가운데 어느 가게 것인지 못 가른 것이 남습니다(미배분).
     미배분이 있으면 가게별 수익은 확정이 아니라 범위입니다.

   ── 스펙과 다른 점 (팀에 알려야 합니다) ──
   ERD 의 ACCOUNT.business_id 는 NN 이라 계좌가 사업장 하나에 매입니다.
   account_number_hash 는 UK 라 같은 계좌를 두 번 연결할 수도 없습니다
   (ACCOUNT_ALREADY_LINKED). 지금 스펙으로는 위 두 경우를 표현할 수 없습니다.
   테이블 변경이 가장 적은 길:
     · 계좌는 한 번만 연결하고, 가게 귀속은 TRANSACTION.business_id 로 가릅니다.
     · 묶음은 ACCOUNT 에 pot_id 한 칼럼을 더하면 표현됩니다.
       ACCOUNT.business_id 는 "관리 사업장"으로 뜻만 좁힙니다.
   이 파일은 그 모델을 따릅니다.
   ══════════════════════════════════════════════════════════════ */

/* ── 계좌 ──
   묶음 main 의 두 계좌를 합치면 4,182,400원으로,
   기존 19장이 쓰는 오늘 잔액과 같습니다. */
var ACCOUNTS = [
  { id:'A1', bank:'기업은행',   name:'주계좌',      masked:'123-***-****71',
    balance:2864200, pot:'main', note:'임차료 · 대출 · 급여가 나가는 통장입니다' },
  { id:'A2', bank:'카카오뱅크', name:'배달 정산용', masked:'3333-**-****18',
    balance:1318200, pot:'main', note:'배민 · 쿠팡이츠 정산금이 들어옵니다. 사장이 그날그날 주계좌로 옮겨 씁니다' },
  { id:'B1', bank:'신한은행',   name:'왕십리 계좌', masked:'110-***-****89',
    balance:3184000, pot:'ws',   note:'왕십리 매장만 씁니다' }
];

/* ── 가게 ──
   8월에 실제로 오간 돈입니다. 남는 돈 = 매출 − 재료비 − 인건비 − 임차료 − 그 밖.

   전에는 고정비 한 칸에 급여까지 넣고 인건비를 또 따로 세서
   급여 482만원이 두 번 빠졌습니다. 그래서 남는 돈이 매출의 26% 로 나왔는데
   요식업에서 그런 이익률은 나오지 않습니다. 열을 갈라서 다시 셌습니다.

   원가율은 요식업 통상 범위에 맞췄습니다.
   재료비 35~40% · 인건비 25~32% · 임차료 4~9% · 남는 돈 3~11%.
   '그 밖'은 배달 수수료 · 카드 수수료 · 공과금 · 대출 원리금 · 소모품입니다.

   남는 돈은 사장 몫입니다. 여기서 생활비를 가져가면 통장은 늘지 않습니다.
   그래서 이 표가 검다고 통장이 안전한 것은 아닙니다. 위층을 봐야 합니다. */
var SHOPS = [
  { id:'seongsu',   name:'온기식당 성수',  opened:'2023-04', pot:'main', safety:2300000, vat:1873200,
    sales:42184000, food:16020000, labor:11880000, rent:1684000, other:8268000 },
  { id:'forest',    name:'온기식당 서울숲', opened:'2025-11', pot:'main', safety:1500000, vat:812400,
    sales:18470000, food:7388000,  labor:5910000,  rent:1500000, other:3140000 },
  { id:'wangsimni', name:'온기밥상 왕십리', opened:'2024-08', pot:'ws',   safety:1500000, vat:1204600,
    sales:27310000, food:10105000, labor:7370000,  rent:1850000, other:4218000 }
];
SHOPS.forEach(function (s) {
  s.profit = s.sales - s.food - s.labor - s.rent - s.other;
  s.margin = Math.round(s.profit / s.sales * 1000) / 10;
});

/* 공유 통장에서 가르지 못한 거래. 건수와 금액은 목록에서 셉니다.
   손으로 적어 두면 목록을 고칠 때 어긋납니다. */
var UNALLOC = { reason:'단말기 번호가 없는 카드 정산과 현금 매출, 두 가게가 함께 쓰는 재료 · 소모품입니다' };

/* ── 자금 묶음 ── */
var POT_DEF = [
  { id:'main', name:'성수 · 서울숲 지갑', draw:5200000,
    why:'사장이 배달 정산금을 매일 주계좌로 옮겨 쓰기 때문에 두 통장을 하나로 봅니다' },
  { id:'ws',   name:'왕십리 지갑', draw:3800000,
    why:'왕십리는 통장을 따로 씁니다. 성수 쪽과 돈이 오가지 않습니다' }
];

var POTS = POT_DEF.map(function (d) {
  var accts = ACCOUNTS.filter(function (a) { return a.pot === d.id; });
  var shops = SHOPS.filter(function (s) { return s.pot === d.id; });
  return { id:d.id, name:d.name, why:d.why, draw:d.draw, accts:accts, shops:shops,
           balance: accts.reduce(function (t,a) { return t + a.balance; }, 0),
           safety:  shops.reduce(function (t,s) { return t + s.safety; }, 0) };
});
var POT = {};
POTS.forEach(function (p) { POT[p.id] = p; });

/* 묶음 main — 기존 예보를 그대로 씁니다 (오늘 4,182,400 · 9월 14일 −418,300) */
POT.main.curve = FC;
POT.main.low   = { date: lowest.date, v: lowest.v };

/* 묶음 ws — 온기밥상 왕십리.
   근거 없는 숫자를 쓰지 않으려고 구성 요소를 적어 두고 더합니다.
   월 매출 27,310,000 · 재료비 8,940,000 · 인건비 6,180,000 · 고정비 6,420,000.
   휴무는 화요일입니다(성수는 월요일이라 두 가게의 공백이 겹치지 않습니다). */
var WS = {
  settle:  881000,   /* 카드 · 배달 정산. 27,310,000 / 31일 */
  food:   1030000,   /* 재료 매입. 월 · 목 두 번 */
  rent:   1850000,   /* 임차료. 5일 */
  draw:   3800000,   /* 대표 인출. 10일. 사장이 생활비로 가져가는 돈입니다.
                        이걸 빼지 않으면 통장에 30일치 이익이 그대로 쌓여
                        실제보다 여유로운 가게가 됩니다. */
  loan:    680000,   /* 대출 원리금. 15일 */
  util:    412000,   /* 전기 · 가스. 20일 */
  pay:    6180000,   /* 급여. 25일 */
  closed:  2         /* 휴무 요일 (0=일) */
};
var FC_B = (function () {
  var out = [], v = POT.ws.balance;
  for (var i = 0; i < FC.length; i++) {
    var iso = FC[i][0], dt = new Date(iso + 'T00:00:00+09:00');
    var dow = dt.getUTCDay(), dd = +iso.slice(8);
    if (i) {
      if (dow !== WS.closed) v += WS.settle;      /* 휴무일엔 정산이 없습니다 */
      if (dow === 1 || dow === 4) v -= WS.food;
      if (dd === 5)  v -= WS.rent;
      if (dd === 10) v -= WS.draw;
      if (dd === 15) v -= WS.loan;
      if (dd === 20) v -= WS.util;
      if (dd === 25) v -= WS.pay;
    }
    out.push([iso, v]);
  }
  return out;
})();
POT.ws.curve = FC_B;
POT.ws.low   = FC_B.reduce(function (m, d) { return d[1] < m.v ? { date:d[0], v:d[1] } : m; },
                           { date:FC_B[0][0], v:FC_B[0][1] });

POTS.forEach(function (p) {
  p.risk    = riskFor(p.low.v < p.safety ? p.low.v : p.balance, p.safety);
  p.lowRisk = riskFor(p.low.v, p.safety);
  p.at = function (date) {
    var hit = null;
    p.curve.forEach(function (d) { if (d[0] === date) hit = d[1]; });
    return hit;
  };
});

/* ══════════════════════════════════════════════════════════════
   손익과 현금을 잇는 다리

   "가게가 남긴 돈"과 "통장이 늘어난 돈"은 같지 않습니다.
   가게가 486만원을 남겨도 통장은 102만원 줄어들 수 있습니다.
   사장이 생활비를 가져가고, 월말 미수 정산이 늘기 때문입니다.

     남는 돈 − 대표 인출 − 월말 미수 증가 = 통장 순변화

   이 다리를 화면에 올리지 않으면 사장이 두 숫자를 보고
   "어느 쪽이 거짓말인가"를 묻게 됩니다. 둘 다 참인데 다른 것을 셉니다.

   미수 증가분은 나머지로 냅니다. 통장 곡선과 손익이 주어지면
   그 값은 하나로 정해집니다. 손으로 적으면 곡선을 고칠 때 어긋납니다.
   ══════════════════════════════════════════════════════════════ */
var BRIDGE = POTS.map(function (p) {
  var c = p.curve;
  var cash   = c[c.length - 1][1] - c[0][1];
  var profit = p.shops.reduce(function (t, s) { return t + s.profit; }, 0);
  return { pot:p, from:c[0][1], to:c[c.length - 1][1],
           cash:cash, profit:profit, draw:p.draw,
           timing: profit - cash - p.draw,
           shops: p.shops.map(function (s) { return { name:s.name, profit:s.profit }; }) };
});
var BRIDGE_BY = {};
BRIDGE.forEach(function (b) { BRIDGE_BY[b.pot.id] = b; });

var TOTAL_BALANCE = POTS.reduce(function (t, p) { return t + p.balance; }, 0);
var COMBINED_LOW  = (function () {
  var best = null;
  FC.forEach(function (d, i) {
    var v = d[1] + FC_B[i][1];
    if (!best || v < best.v) best = { date:d[0], v:v };
  });
  return best;
})();

/* ── 자금 융통 (API 19.2) ──
   가용잉여금 = 그날 예상잔액 − 안전잔액.
   묶음 단위로 계산합니다. 같은 묶음을 쓰는 가게끼리는 옮길 것이 없습니다.

   이체 건수를 줄이려고 큰 잉여 → 큰 부족 순서로 붙입니다.
   묶음이 두세 개일 때는 이게 최소 건수와 같습니다.
   서버는 MCMF 로 풀도록 명세에 적혀 있습니다(API 19.2). */
function transferPlan(date) {
  var rows = POTS.map(function (p) {
    var v = p.at(date);
    return { pot:p, expected:v, safety:p.safety,
             surplus:   Math.max(0, v - p.safety),
             shortfall: Math.max(0, p.safety - v) };
  });
  var give = rows.filter(function (r) { return r.surplus > 0; })
                 .sort(function (a, b) { return b.surplus - a.surplus; })
                 .map(function (r) { return { r:r, left:r.surplus }; });
  var take = rows.filter(function (r) { return r.shortfall > 0; })
                 .sort(function (a, b) { return b.shortfall - a.shortfall; })
                 .map(function (r) { return { r:r, need:r.shortfall }; });

  var plans = [], moved = 0;
  give.forEach(function (g) {
    take.forEach(function (t) {
      if (g.left <= 0 || t.need <= 0) return;
      var amt = Math.min(g.left, t.need);
      plans.push({ from:g.r.pot, to:t.r.pot, amount:amt });
      g.left -= amt; t.need -= amt; moved += amt;
    });
  });

  var unresolved = take.reduce(function (s, t) { return s + t.need; }, 0);
  var after = rows.map(function (r) {
    var d = 0;
    plans.forEach(function (p) {
      if (p.from === r.pot) d -= p.amount;
      if (p.to   === r.pot) d += p.amount;
    });
    var v = r.expected + d;
    return { pot:r.pot, expected:v, risk:riskFor(v, r.safety),
             was:riskFor(r.expected, r.safety), moved:d };
  });

  return { date:date, rows:rows, plans:plans,
           resolved:moved, unresolved:unresolved, after:after };
}

var TRANSFER_NOTE = '시뮬레이션 결과이며 실제 이체는 수행하지 않습니다.';

/* ══════════════════════════════════════════════════════════════
   가게 컨텍스트 (OV-02)

   스위처에서 고른 가게가 여기 저장되고, 모든 화면이 그 가게 기준으로 그려집니다.
   화면을 옮겨 다녀도 유지되어야 하므로 localStorage 에 둡니다.
   저장이 막힌 환경(시크릿 창 등)에서는 조용히 첫 가게로 갑니다.

   ── 무엇이 가게를 따라 바뀌고 무엇이 안 바뀌나 ──
   거래내역 · 고정비 일정 · 증빙 · 반복 패턴  → 가게 것입니다. 바뀝니다.
   잔액 곡선 · 안전선 · 위험 등급             → 지갑 것입니다. 같은 지갑을 쓰는
                                                가게끼리는 똑같이 보입니다.

   성수와 서울숲을 오갈 때 곡선이 안 바뀌는 건 버그가 아닙니다.
   두 가게가 한 통장을 쓰기 때문입니다. 화면이 그렇게 말해 줘야 합니다.
   ══════════════════════════════════════════════════════════════ */
var SHOP_KEY = 'jangoyebo.shop';

function readShop() {
  try {
    var v = localStorage.getItem(SHOP_KEY);
    if (v && SHOPS.some(function (s) { return s.id === v; })) return v;
  } catch (e) {}
  return SHOPS[0].id;
}
function setShop(id) {
  try { localStorage.setItem(SHOP_KEY, id); } catch (e) {}
}

var CURRENT_SHOP = readShop();
var SHOP  = SHOPS.filter(function (s) { return s.id === CURRENT_SHOP; })[0];
var PURSE = POT[SHOP.pot];                       /* 이 가게가 속한 지갑 */
var MATES = PURSE.shops.filter(function (s) { return s.id !== SHOP.id; });

/* ── 예보를 이 가게의 지갑 것으로 갈아탑니다 ──
   sky.js 의 FC 는 성수 지갑 곡선입니다. 왕십리를 고르면 그 지갑 곡선을 써야 합니다.
   이걸 안 하면 왕십리를 골라도 예보가 4,182,400원을 그대로 보여 주어,
   통합 화면이 말하는 3,184,000원과 어긋납니다.

   안전선은 지갑이 아니라 가게 것을 씁니다. 사장이 관리하는 값이라서입니다.
   같은 지갑을 쓰는 두 가게가 다 버티려면 두 안전선의 합(PURSE.safety)이 필요하고,
   그 사실은 화면이 따로 말해 줍니다(purseNote).

   부가세 예수금도 가게 것입니다. 매출이 다르면 예수금도 다릅니다. */
FC        = PURSE.curve;
SAFETY    = SHOP.safety;
VAT_HELD  = SHOP.vat;
deriveForecast();

/* ── 가게마다 자기 거래를 갖습니다 ──
   전에는 성수 목록 하나를 거래처 이름으로 걸러 썼는데,
   그러면 왕십리 거래내역이 두 건짜리 빈 화면이 됩니다.
   가게마다 자기 장부가 있어야 합니다.

   칸: [날짜, 거래처, 적요, 방향, 금액, 분류, 출처, 신뢰도, 증빙연결]
   출처 RULE=규칙 · DICT=거래처 사전 · AI=모델 · USER=직접 입력.
   신뢰도 0.8 아래는 확인이 필요한 것으로 셉니다. */
var TX_BY_SHOP = {
  seongsu: [
    ['08-31','우아한형제들','배달의민족 정산','in',843200,'정산','AI',0.97,true],
    ['08-31','삼우유통','재료 매입 · 돼지고기 외','out',318400,'재료비','DICT',0.94,false],
    ['08-30','서울우유','재료 매입 · 유제품','out',86700,'재료비','DICT',0.91,false],
    ['08-30','KG이니시스','카드 정산 · 8/28 성수 단말기','in',1287600,'정산','RULE',1,true],
    ['08-29','쿠팡이츠서비스','쿠팡이츠 정산','in',318400,'정산','AI',0.88,true],
    ['08-29','대성철물 성수점','소모품 · 주방 집기 3점','out',86400,'소모품','DICT',0.93,true],
    ['08-28','박정선','임차료','out',1500000,'임차료','USER',1,true],
    ['08-27','성수농협','재료 매입 · 채소 7종','out',241800,'재료비','DICT',0.71,false],
    ['08-26','카페 리코드 성수','원두 · 부자재','out',164300,'재료비','AI',0.68,false],
    ['08-25','한국전력','전기요금','out',312400,'공과금','RULE',1,true]
  ],
  forest: [
    ['08-31','우아한형제들','배달의민족 정산','in',612800,'정산','AI',0.96,true],
    ['08-31','서울우유','재료 매입 · 유제품','out',64200,'재료비','DICT',0.91,false],
    ['08-30','쿠팡이츠서비스','쿠팡이츠 정산','in',341200,'정산','AI',0.89,true],
    ['08-30','성수농협','재료 매입 · 채소','out',164500,'재료비','DICT',0.87,false],
    ['08-29','KG이니시스','카드 정산 · 8/27 서울숲 단말기','in',428600,'정산','RULE',1,true],
    ['08-29','서울숲현대타워 관리단','임차료','out',1500000,'임차료','USER',1,true],
    ['08-28','삼우유통','재료 매입 · 돼지고기','out',241800,'재료비','DICT',0.92,true],
    ['08-28','배민상회','소모품 · 포장용기','out',118400,'소모품','DICT',0.86,false],
    ['08-27','우아한형제들','배달의민족 정산','in',548300,'정산','AI',0.95,true]
  ],
  wangsimni: [
    ['08-31','KG이니시스','카드 정산 · 8/29 매출','in',921400,'정산','RULE',1,true],
    ['08-31','대성상회','재료 매입 · 쌀 20kg 외','out',148000,'재료비','DICT',0.93,false],
    ['08-30','KG이니시스','카드 정산 · 8/28 매출','in',1104800,'정산','RULE',1,true],
    ['08-30','왕십리축산','재료 매입 · 삼겹살 · 목살','out',412600,'재료비','DICT',0.95,true],
    ['08-29','요기요','요기요 정산','in',268300,'정산','AI',0.90,true],
    ['08-29','청과마트 왕십리','재료 매입 · 채소','out',187200,'재료비','DICT',0.88,false],
    ['08-28','왕십리제일상가 관리단','임차료','out',1850000,'임차료','USER',1,true],
    ['08-28','KG이니시스','카드 정산 · 8/26 매출','in',876500,'정산','RULE',1,true],
    ['08-27','신한은행','대출 원리금','out',680000,'대출','RULE',1,true],
    ['08-27','동아주방','소모품 · 위생용품','out',94300,'소모품','DICT',0.84,false],
    ['08-26','왕십리축산','재료 매입 · 등뼈','out',238400,'재료비','DICT',0.94,true]
  ]
};

/* ── 미배분 ──
   공용 지갑에서 어느 가게 것인지 못 가른 거래입니다.
   통장을 혼자 쓰는 가게에는 미배분이 있을 수 없습니다.
   이 조건을 빼면 성수의 카드 정산이 왕십리 거래내역에 떠서
   다른 가게 돈을 자기 것으로 읽게 됩니다. */
var TX_UNALLOC = [
  ['08-31','KG이니시스','카드 정산 · 단말기 구분 없음','in',1043200,'정산','RULE',1,true],
  ['08-30','이마트 트레이더스 성수','재료 매입 · 두 매장 공용','out',284700,'재료비','DICT',0.79,false],
  ['08-29','현금 매출 입금','현금 마감 · 매장 구분 없음','in',486000,'정산','USER',1,false],
  ['08-28','대성철물 성수점','소모품 · 두 매장 공용','out',96400,'소모품','DICT',0.82,false]
];

UNALLOC.count  = TX_UNALLOC.length;
UNALLOC.amount = TX_UNALLOC.reduce(function (t, x) { return t + x[4]; }, 0);

/* 지금 가게에서 볼 거래: 그 가게 장부 + (공용 지갑이면) 미배분 */
function txForShop() {
  var sharing = PURSE.shops.length > 1;
  var mine = (TX_BY_SHOP[SHOP.id] || []).map(function (t) {
    var c = t.slice(); c.shop = SHOP.id; return c;
  });
  var un = sharing ? TX_UNALLOC.map(function (t) {
    var c = t.slice(); c.shop = null; return c;
  }) : [];
  return mine.concat(un).sort(function (a, b) { return a[0] < b[0] ? 1 : a[0] > b[0] ? -1 : 0; });
}
/* ── 고정비 일정을 가게로 가릅니다 ──
   FIXED 는 성수 것입니다. 다른 가게는 자기 일정을 씁니다. */
var FIXED_BY_SHOP = {
  seongsu: FIXED,
  forest: [
    ['2026-09-01','임차료','서울숲현대타워 관리단',1500000],
    ['2026-09-10','직원 급여','1명',1970000],
    ['2026-09-18','4대보험','건강보험공단',88400],
    ['2026-09-21','전기와 가스','한국전력 · 도시가스',214000]
  ],
  wangsimni: [
    ['2026-09-05','임차료','왕십리제일상가 관리단',1850000],
    ['2026-09-10','대표 인출','생활비',3800000],
    ['2026-09-15','대출 상환','신한은행',680000],
    ['2026-09-20','전기와 가스','한국전력 · 도시가스',412000],
    ['2026-09-25','직원 급여','2명',6180000]
  ]
};
function fixedForShop() { return FIXED_BY_SHOP[SHOP.id] || []; }

/* 이 가게 화면 상단에 붙일 한 줄. 지갑을 함께 쓰면 그 사실을 말해야 합니다. */
function purseNote() {
  if (MATES.length === 0) return '';
  return '이 잔액은 ' + PURSE.name + ' 것입니다. '
    + MATES.map(function (s) { return s.name; }).join(' · ')
    + '과 한 통장을 쓰기 때문에 ' + SHOP.name + ' 몫만 떼어 말할 수 없습니다.';
}


/* ══════════════════════════════════════════════════════════════
   상품별 판매 구성비 (P1-22 · API 14.5)
   공통규약 1.5: 매출은 판매 / 정산 / 입금 세 층이고, 판매 층은 구성비만 밖으로 냅니다.
   금액은 여기 없습니다 — 금액의 단일 출처는 입금(TRANSACTION)이라 여기 넣으면 두 번 계상됩니다.
   share 는 합계 기준(billedAmount / 합계)입니다. 근거 기간을 늘 함께 보입니다.
   ══════════════════════════════════════════════════════════════ */
var PRODUCT_SHARES = {
  period: ['2026-08-01', '2026-08-31'], shareBasis: 'BILLED', incomplete: false,
  items: [
    { key:'제육덮밥',   share:0.312, qty:1184 },
    { key:'김치찌개',   share:0.214, qty:812 },
    { key:'된장찌개',   share:0.138, qty:561 },
    { key:'계란말이',   share:0.092, qty:748 },
    { key:'공기밥·음료', share:0.118, qty:2330 },
    { key:'그 밖',      share:0.126, qty:0 }
  ]
};

/* API 11.1 PRODUCT_SALES_CHANGE 의 환산식.
   상품 배율 = (1 + 판매량률) × (1 + 단가률). 총매출 배율 = 1 + Σ share × (상품 배율 − 1).
   구성비를 쓰므로 POS 총액과 입금액의 분모가 달라도 결과가 보존됩니다. */
function productMultiplier(changes) {
  var m = 1;
  PRODUCT_SHARES.items.forEach(function (p) {
    var c = changes[p.key]; if (!c) return;
    var pm = (1 + (c.q || 0) / 100) * (1 + (c.p || 0) / 100);
    m += p.share * (pm - 1);
  });
  return m;
}
