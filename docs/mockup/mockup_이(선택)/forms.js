/* ══════════════════════════════════════════════════════════════
   별지 제74호서식(간편장부소득금액계산서)과 그 부표(총수입금액 및 필요경비명세서).
   항목 번호 · 출처 여섯 가지 · 카테고리→서식 매핑은 API 14.3 을 따릅니다.

   칸을 한 번만 적고 두 가지로 그립니다.
     formSheet(which)  관보 서식처럼 — 사업장 세 열 + 계. 종이에 가깝습니다.
     formPaper(which)  항목별로 — 값마다 출처 · 상태 · 근거. 어디를 손댈지 보입니다.
   두 곳에 칸을 적으면 한쪽만 고쳐집니다.

   원본 PDF 두 개는 글자가 모두 곡선으로 바뀌어 들어 있어(글꼴 · 이미지 · 텍스트 연산자 0)
   글자를 읽어낼 수 없었습니다. 그래서 표 눈금은 PDF 의 선 좌표에서 뽑고,
   항목 번호와 이름은 project/docs/api/14_정산_내보내기.md 를 따랐습니다.

   금액은 세 가게의 8월 자료입니다. sky.js · store.js 뒤에 읽혀야 합니다.
   ══════════════════════════════════════════════════════════════ */
var FORM_SRC_KO = {
  AUTO_TRANSACTION:'거래 집계', AUTO_SETTLEMENT:'정산 대사', AUTO_PROFILE:'프로필',
  REFERENCE_HOMETAX:'홈택스 참고', USER_SENSITIVE:'직접 입력 · 미저장', CALCULATED:'서식 산식',
  USER_CONFIRM:'사장 확인'
};

/* 가게 하나의 서식 값. 고정비 일정에서 이자 · 4대보험 · 공과금을 끌어옵니다 —
   서식용으로 따로 적어 두면 일정을 고칠 때 갈립니다. */
function formValues(shop) {
  var fx = (typeof FIXED_BY_SHOP !== 'undefined' && FIXED_BY_SHOP[shop.id]) || [];
  var pick = function (name) {
    return fx.filter(function (f) { return f[1] === name; })
             .reduce(function (a, f) { return a + f[3]; }, 0);
  };
  var loan  = pick('대출 상환');
  var insur = pick('4대보험');
  var util  = pick('전기와 가스');
  var v = {
    shop:shop, sales:shop.sales, otherRev:0,
    purch:shop.food, cogs:shop.food,
    payroll:shop.labor - insur, insur:insur, rent:shop.rent, loan:loan, util:util,
    feePlat:null, feeCard:null, fee:null, supply:null, unclassified:0
  };
  if (shop.id === 'seongsu') {
    /* 성수만 거래 · 증빙이 붙어 있어 수수료 · 소모품까지 갈립니다 */
    v.feePlat = 3240000; v.feeCard = 640000; v.fee = v.feePlat + v.feeCard; v.supply = 890000;
    v.unclassified = shop.other - (v.fee + v.supply + loan + util);   /* 1,350,000 · 분류 안 됨 */
    v.etc = util;
    /* 매출 층 나누기 — 넷째 주 비율로 8월 총액을 가릅니다 */
    var card = 6184000 * 4.2, plat = 2847000 * 4.2, cash = 811000 * 4.2;
    var k = shop.sales / (card + plat + cash);
    v.card = Math.round(card * k); v.plat = Math.round(plat * k); v.cash = shop.sales - v.card - v.plat;
  } else {
    /* 나머지 두 가게는 거래가 아직 안 붙어 갈리지 않은 몫을 「기타」에 둡니다 */
    v.etc = shop.other - loan - insur;
  }
  v.expSum = v.cogs + v.payroll + v.insur + v.rent + v.loan
           + (v.fee || 0) + (v.supply || 0) + (v.etc || 0);
  v.adjRev = v.sales; v.adjExp = v.expSum; v.income = v.sales - v.expSum;
  return v;
}

var FORM_COLS = SHOPS.map(formValues);
/* 계 열. 값이 없는 칸(null)은 더하지 않습니다 — 0 으로 세면 없는 값이 있는 값이 됩니다 */
var FORM_SUM = (function () {
  var out = {};
  ['sales','otherRev','purch','cogs','payroll','insur','rent','loan','util','fee','supply','etc',
   'unclassified','expSum','adjRev','adjExp','income'].forEach(function (k) {
    var any = false, t = 0;
    FORM_COLS.forEach(function (v) { if (v[k] !== null && v[k] !== undefined) { any = true; t += v[k]; } });
    out[k] = any ? t : null;
  });
  return out;
})();
var FORM_V = FORM_COLS[0];                 /* 성수. 출처 · 근거가 붙는 열입니다 */
FORM_V.posTotal = 42804000;                /* 판매 층 참고값. 현금 미입금분이 있을 수 있어 입금 집계보다 큽니다 */
FORM_V.reviewRate = 0.92;

/* ── 칸 정의 ──
   g   그룹 이름 (서식 왼쪽 세로 칸)
   no  항목 번호 · label 이름 · sub 부제
   get 값 (가게 값 묶음을 받습니다) · text 면 세 열이 같은 글
   src 출처 · st 상태 — 성수 열 기준 (자료가 붙은 열)
   basis 근거 · note 못 채운 이유 · sum 합계 줄 */
var FORMS = {
  main: {
    name:'간편장부소득금액계산서',
    law:'소득세법 시행규칙 [별지 제74호서식]',
    rev:'개정 2023. 3. 20.',
    file:'별지74호_간편장부소득금액계산서_2026.pdf',
    decl:'「소득세법」 제70조제4항제3호 단서 및 같은 법 시행령 제132조에 따라 간편장부소득금액계산서를 제출합니다.',
    signYear:'2027',
    off:'소득세법 시행규칙 별지 제74호서식 · 개정 2023.3.20 · 사업장 세 곳을 열로 채웠습니다. 시안이라 서식 배치는 원문과 다를 수 있습니다.',
    head:[
      { no:'①', label:'주소지', wide:'주 소 지', value:null, src:'USER_SENSITIVE', st:'EMPTY', sensitive:true,
        sub:'내보내기 순간에만 입력합니다. 서버에 남지 않습니다' },
      { no:'②', label:'전화번호', wide:'전화번호', value:'010-4718-2936', src:'AUTO_PROFILE', st:'FILLED' },
      { no:'③', label:'성명', wide:'성　　명', value:'정유선', src:'AUTO_PROFILE', st:'FILLED' },
      { no:'④', label:'생년월일', wide:'생년월일', value:null, src:'USER_SENSITIVE', st:'EMPTY', sensitive:true }
    ],
    rows:[
      { g:'사업장', no:'⑤', label:'소 재 지', src:'AUTO_PROFILE', st:'FILLED', sumText:'합계',
        each:['서울 성동구 성수이로 ***','서울 성동구 서울숲2길 ***','서울 성동구 왕십리로 ***'], blank:'' },
      { g:'사업장', no:'⑥', label:'업　　종', src:'AUTO_PROFILE', st:'FILLED',
        each:['한식 음식점업','한식 음식점업','한식 음식점업'], blank:'' },
      { g:'사업장', no:'⑦', label:'주업종코드', src:'REFERENCE_HOMETAX', st:'FILLED',
        each:['552101','552101','552101'], blank:'',
        sub:'홈택스 신고도움서비스에서 옮겨 적은 값' },
      { g:'사업장', no:'⑧', label:'사업자등록번호', src:'AUTO_PROFILE', st:'FILLED',
        each:['206-**-*****','214-**-*****','305-**-*****'], blank:'' },
      { g:'사업장', no:'⑨', label:'과 세 기 간', src:'CALCULATED', st:'FILLED',
        each:['2026. 1. 1.부터<br>2026. 12. 31.까지','2026. 1. 1.부터<br>2026. 12. 31.까지','2026. 1. 1.부터<br>2026. 12. 31.까지'],
        blank:'. . .부터<br>. . .까지' },
      { g:'사업장', no:'⑩', label:'소 득 종 류', src:'CALCULATED', st:'FILLED',
        each:['(30, <u>40</u>)','(30, <u>40</u>)','(30, <u>40</u>)'], blank:'(30, 40)' },

      { g:'총수입금액', no:'⑪', label:'장 부 상  수 입 금 액', get:function (v) { return v.sales; },
        src:'CALCULATED', st:'FILLED', sub:'부표 ⑬ 총수입금액 계' },
      { g:'총수입금액', no:'⑫', label:'수입금액에서 제외할 금액', get:function () { return 0; },
        src:'USER_CONFIRM', st:'FILLED', sub:'세무조정이 있으면 별지 제48호서식을 첨부합니다' },
      { g:'총수입금액', no:'⑬', label:'수입금액에 가산할 금액', get:function () { return 0; },
        src:'USER_CONFIRM', st:'FILLED' },
      { g:'총수입금액', no:'⑭', label:'세무조정 후 수입금액', calc:'( ⑪ − ⑫ + ⑬ )', get:function (v) { return v.adjRev; },
        src:'CALCULATED', st:'FILLED', sum:true, sub:'별지 제40호서식(1) ⑨ 총수입금액과 같아야 합니다' },

      { g:'필요경비', no:'⑮', label:'장 부 상  필 요 경 비', calc:'( 부표 ㊵ 의 금액 )', get:function (v) { return v.expSum; },
        src:'CALCULATED', st:'FILLED', sub:'부표 ㊵ 필요경비 계' },
      { g:'필요경비', no:'⑯', label:'필요경비에서 제외할 금액', get:function () { return 0; },
        src:'USER_CONFIRM', st:'FILLED' },
      { g:'필요경비', no:'⑰', label:'필요경비에 가산할 금액', get:function () { return 0; },
        src:'USER_CONFIRM', st:'FILLED' },
      { g:'필요경비', no:'⑱', label:'세무조정 후 필요경비', calc:'( ⑮ − ⑯ + ⑰ )', get:function (v) { return v.adjExp; },
        src:'CALCULATED', st:'FILLED', sum:true },

      { g:'', no:'⑲', label:'차가감 소득금액', calc:'( ⑭ − ⑱ )', get:function (v) { return v.income; },
        src:'CALCULATED', st:'FILLED' },
      { g:'', no:'⑳', label:'기부금 한도초과액', get:function () { return 0; },
        src:'USER_CONFIRM', st:'FILLED' },
      { g:'', no:'㉑', label:'기부금이월액 중 필요경비 산입액', get:function () { return 0; },
        src:'USER_CONFIRM', st:'FILLED' },
      { g:'', no:'㉒', label:'해 당 연 도 소 득 금 액', calc:'( ⑲ + ⑳ − ㉑ )', get:function (v) { return v.income; },
        src:'CALCULATED', st:'FILLED', sum:true, sub:'세액은 계산하지 않습니다. 여기까지가 서비스의 몫입니다' }
    ]
  },

  sub: {
    name:'총수입금액 및 필요경비명세서',
    law:'소득세법 시행규칙 [별지 제74호서식 부표]',
    rev:'개정 2025. 3. 21.',
    file:'별지74호부표_총수입금액및필요경비명세서_2026.pdf',
    decl:'위 서식의 부표로 총수입금액과 필요경비의 내용을 다음과 같이 적습니다.',
    signYear:'2027', nosign:true,
    off:'별지 제74호서식 부표 · 개정 2025.3.21 · 제조비용란(⑱~㉔)은 제조업 전용이라 음식점업은 비웁니다.',
    head:[
      { no:'', label:'성명', wide:'성　　명', value:'정유선', src:'AUTO_PROFILE', st:'FILLED' },
      { no:'', label:'사업자등록번호', wide:'사업자등록번호', value:'206-**-*****', src:'AUTO_PROFILE', st:'FILLED' }
    ],
    rows:[
      { g:'총수입금액', no:'⑪', label:'매출액', get:function (v) { return v.sales; },
        src:'AUTO_SETTLEMENT', st:'FILLED',
        basis:'카드 정산 <b>' + won(FORM_V.card) + '</b> · 배달 정산 <b>' + won(FORM_V.plat)
              + '</b> (수수료 차감 전 총매출로 보정) · 현금 <b>' + won(FORM_V.cash) + '</b> · 거래 14건 · 정산 대사 8건' },
      { g:'총수입금액', no:'⑫', label:'기타', get:function (v) { return v.otherRev; },
        src:'AUTO_TRANSACTION', st:'FILLED', sub:'OTHER_INCOME 해당 없음' },
      { g:'총수입금액', no:'⑬', label:'총수입금액 계', calc:'( ⑪ + ⑫ )', get:function (v) { return v.adjRev; },
        src:'CALCULATED', st:'FILLED', sum:true },

      { g:'매출원가', no:'⑭', label:'기초재고액', get:function () { return null; },
        src:'USER_CONFIRM', st:'EMPTY',
        note:'재고 자료가 없습니다. 기초 · 기말을 가르기 어려우면 당기 매입액만 기재합니다(부표 작성방법 4).' },
      { g:'매출원가', no:'⑮', label:'당기 상품매입액', get:function (v) { return v.purch; },
        src:'AUTO_TRANSACTION', st:'FILLED',
        basis:'MATERIALS · 삼우유통 · 서울우유 · 성수농협 외 · 거래 23건 · 증빙 19건 · <b>증빙 없음 4건</b>' },
      { g:'매출원가', no:'⑯', label:'기말재고액', get:function () { return null; },
        src:'USER_CONFIRM', st:'EMPTY' },
      { g:'매출원가', no:'⑰', label:'매출원가', calc:'( ⑭ + ⑮ − ⑯ )', get:function (v) { return v.cogs; },
        src:'CALCULATED', st:'FILLED', sum:true, sub:'재고를 비웠으므로 당기 매입액과 같습니다' },

      { g:'일반관리비 등', no:'㉕', label:'급료', get:function (v) { return v.payroll; },
        src:'AUTO_TRANSACTION', st:'NEEDS_REVIEW', basis:'PAYROLL · 직원 2명 · 8월 급여 2건',
        note:'급여 2건 가운데 1건은 원천세 신고 자료가 연결되지 않았습니다. 지급명세서 제출 여부를 확인하세요.' },
      { g:'일반관리비 등', no:'㉖', label:'제세공과금', get:function () { return null; },
        src:'USER_CONFIRM', st:'EMPTY',
        sub:'재산세 · 상공회의소비 · 협회비만 해당. 소득세 · 부가세 납부분은 경비가 아닙니다' },
      { g:'일반관리비 등', no:'㉗', label:'임차료', get:function (v) { return v.rent; },
        src:'AUTO_TRANSACTION', st:'FILLED',
        basis:'RENT · 박정선(건물주) 1,500,000 + 성수제일빌딩 관리비 184,000 · 증빙 2건' },
      { g:'일반관리비 등', no:'㉘', label:'지급이자', get:function (v) { return v.loan || null; },
        src:'AUTO_TRANSACTION', st:'NEEDS_REVIEW',
        basis:'LOAN_REPAYMENT · 기업은행 · 소진공 2건 원리금 ' + won(FORM_V.loan),
        note:'원금과 이자를 가르지 못했습니다. 이자만 필요경비입니다. 대출 상환 스케줄(16.1)을 연결하면 자동으로 갈립니다.' },
      { g:'일반관리비 등', no:'㉙', label:'기업업무추진비', get:function () { return null; },
        src:'USER_CONFIRM', st:'EMPTY' },
      { g:'일반관리비 등', no:'㉚', label:'기부금', get:function () { return null; },
        src:'USER_CONFIRM', st:'EMPTY' },
      { g:'일반관리비 등', no:'㉛', label:'감가상각비', get:function () { return null; },
        src:'USER_CONFIRM', st:'EMPTY',
        note:'설비 · 비품 구입은 자산 등록 · 감가상각 검토 대상이라 항목 금액에 넣지 않습니다. 기재하면 조정명세서를 첨부해야 합니다.' },
      { g:'일반관리비 등', no:'㉜', label:'차량유지비', get:function () { return null; },
        src:'USER_CONFIRM', st:'EMPTY' },
      { g:'일반관리비 등', no:'㉝', label:'지급수수료', get:function (v) { return v.fee; },
        src:'AUTO_TRANSACTION', st:'FILLED',
        basis:'PLATFORM_FEE · 배달 수수료 <b>' + won(FORM_V.feePlat) + '</b> · 카드 수수료 <b>' + won(FORM_V.feeCard) + '</b>' },
      { g:'일반관리비 등', no:'㉞', label:'소모품비', get:function (v) { return v.supply; },
        src:'AUTO_TRANSACTION', st:'FILLED', basis:'SUPPLIES · 대성철물 성수점 외 · 증빙 3건' },
      { g:'일반관리비 등', no:'㉟', label:'복리후생비', get:function (v) { return v.insur || null; },
        src:'AUTO_TRANSACTION', st:'FILLED', basis:'SOCIAL_INSURANCE · 4대보험 사업자 부담분 · 건강보험공단' },
      { g:'일반관리비 등', no:'㊱', label:'운반비', get:function () { return null; },
        src:'USER_CONFIRM', st:'EMPTY' },
      { g:'일반관리비 등', no:'㊲', label:'광고선전비', get:function () { return null; },
        src:'USER_CONFIRM', st:'EMPTY' },
      { g:'일반관리비 등', no:'㊳', label:'여비교통비', get:function () { return null; },
        src:'USER_CONFIRM', st:'EMPTY' },
      { g:'일반관리비 등', no:'㊴', label:'기타', get:function (v) { return v.etc; },
        src:'AUTO_TRANSACTION', st:'NEEDS_REVIEW',
        basis:'UTILITIES · 한국전력 · 도시가스 <b>' + won(FORM_V.util) + '</b>',
        note:'분류되지 않은 지출 <b>' + won(FORM_V.unclassified) + '원</b>은 항목에 넣지 않았습니다. 거래내역에서 분류를 확정하면 여기로 들어옵니다.' },
      { g:'일반관리비 등', no:'㊵', label:'필요경비 계', calc:'( ⑰ + ㉕ ~ ㊴ )', get:function (v) { return v.expSum; },
        src:'CALCULATED', st:'FILLED', sum:true }
    ]
  }
};

/* 값 하나 꺼내기. text · each 는 글, get 은 금액입니다 */
function formCell(row, v, i) {
  if (row.text !== undefined) return row.text;
  if (row.each) return row.each[i];
  return row.get ? row.get(v) : null;
}
function formMoney(x) { return (x === null || x === undefined) ? '' : won(x); }

/* 출처 기호. 서식에는 뱃지가 들어갈 자리가 없어 기호 한 글자로 답니다.
   범례는 화면 곁줄에 둡니다 — 기호만 두면 무슨 뜻인지 알 수 없습니다. */
var FORM_MK = {
  AUTO_TRANSACTION:'●', AUTO_SETTLEMENT:'◆', AUTO_PROFILE:'■', REFERENCE_HOMETAX:'▲',
  USER_SENSITIVE:'🔒', CALCULATED:'=', USER_CONFIRM:'○'
};
var FORM_MK_KO = [
  ['●','자동 채움','검토 확정 거래 · 연결 증빙 집계'],
  ['◆','정산 대사','정산 대사의 총매출 · 수수료(승인금 보정)'],
  ['■','프로필','가게 · 사업자 정보에서 옮겨 적음'],
  ['▲','홈택스 참고','신고도움서비스에서 사장이 옮겨 적음'],
  ['○','사장 확인','비어 있음 · 사장이 채우는 칸'],
  ['=','서식 산식','다른 칸으로 계산됨'],
  ['🔒','직접 입력','내보내기 순간에만 입력 · 서버에 저장 안 함']
];

/* ══ 1) 관보 서식처럼 ══
   원본 PDF 의 선 좌표에서 표 눈금을 뽑고, 사업장 네 열 + 합계 열로 그립니다.
   열 하나는 비워 둡니다 — 원본 서식도 네 열이고, 가게가 늘면 그 칸에 들어갑니다. */
var FORM_SHOPCOLS = 4;

function gvVal(row, v, i, sumCol) {
  var money = row.text === undefined && !row.each;
  if (!sumCol && !v) {
    /* 가게가 없는 열. 원본 서식도 네 열이고 빈 열에는 점선 표시가 있습니다 */
    return '<td class="gv-c' + (money ? '' : ' txt') + ' gv-blank">' + (money ? '' : (row.blank || '')) + '</td>';
  }
  if (!money) {
    var t = sumCol ? (row.sumText || '') : formCell(row, v, i);
    return '<td class="gv-c txt' + (sumCol ? ' tot' : '') + '">' + (t === null || t === undefined ? '' : t) + '</td>';
  }
  var x = sumCol ? (row.get ? row.get(FORM_SUM) : null) : formCell(row, v, i);
  var mk = sumCol ? '' : FORM_MK[row.src];
  var empty = (x === null || x === undefined);
  return '<td class="gv-c' + (sumCol ? ' tot' : '') + '">'
    + '<i class="gv-mk">' + mk + '</i>'
    + (empty ? '<b class="gv-e">-</b>' : '<b>' + won(x) + '</b>') + '</td>';
}

function formSheet(which, opt) {
  opt = opt || {};
  var f = FORMS[which], year = '2026';
  var cols = [];
  for (var c = 0; c < FORM_SHOPCOLS; c++) cols.push(FORM_COLS[c] || null);

  /* 인적사항 — 두 칸씩 한 줄 */
  var idRows = '';
  for (var i = 0; i < f.head.length; i += 2) {
    idRows += '<tr class="gv-id">';
    f.head.slice(i, i + 2).forEach(function (h, k) {
      var val = h.value === null
        ? '<i class="gv-mk">' + FORM_MK[h.src] + '</i><span class="gv-e">직접 기재</span>'
        : '<i class="gv-mk">' + FORM_MK[h.src] + '</i>' + h.value;
      idRows += '<th colspan="' + (k === 0 ? 3 : 1) + '">' + (h.no ? h.no + ' ' : '') + h.wide + '</th>'
             +  '<td colspan="' + (k === 0 ? 3 : 2) + '" class="gv-c txt-l">' + val + '</td>';
    });
    idRows += '</tr>';
  }

  /* 본문 — 그룹별로 묶고, 그룹 이름은 세로 칸에 */
  var groups = [];
  f.rows.forEach(function (r) {
    var last = groups[groups.length - 1];
    if (last && last.g === r.g) last.rows.push(r); else groups.push({ g:r.g, rows:[r] });
  });

  var body = '';
  groups.forEach(function (grp) {
    var vert = grp.g !== '';
    grp.rows.forEach(function (r, k) {
      body += '<tr' + (r.sum ? ' class="gv-sum"' : '') + '>';
      if (vert && k === 0) {
        body += '<th class="gv-g" rowspan="' + grp.rows.length + '">'
             +  grp.g.replace(/\s/g, '').split('').join('<br>') + '</th>';
      }
      if (vert) {
        body += '<td class="gv-no">' + r.no + '</td>'
             +  '<td class="gv-lb">' + r.label + (r.calc ? '<span class="gv-calc">' + r.calc + '</span>' : '') + '</td>';
      } else {
        body += '<td class="gv-lb" colspan="3">' + r.no + r.label
             +  (r.calc ? '<span class="gv-calc">' + r.calc + '</span>' : '') + '</td>';
      }
      cols.forEach(function (v, ci) { body += gvVal(r, v, ci, false); });
      body += gvVal(r, FORM_SUM, 0, true) + '</tr>';
    });
  });

  var title = '<tr><td class="gv-title" colspan="' + (3 + FORM_SHOPCOLS + 1) + '">'
    + f.name + '( ' + year + ' 귀속 )</td></tr>';

  return '<div class="gvsheet' + (opt.tight ? ' tight' : '') + '">'
    + '<div class="gv-top"><span class="gv-law">■ ' + f.law + ' &lt;' + f.rev + '&gt;</span>'
      + '<span class="gv-side">(' + (which === 'main' ? '앞쪽' : '부표') + ')</span></div>'
    + '<table class="gv-t"><colgroup><col style="width:26px"><col style="width:22px"><col style="width:150px">'
      + cols.map(function () { return '<col>'; }).join('') + '<col style="width:112px"></colgroup>'
      + '<tbody>' + title + idRows + body + '</tbody></table>'
    + '<div class="gv-foot">'
      + '<p class="gv-decl">' + f.decl + '</p>'
      + (f.nosign ? '' :
          '<p class="gv-date">' + f.signYear + ' 년 　 월 　 일</p>'
        + '<p class="gv-sign"><span>제 출 인</span><span class="gv-seal">( 서명 또는 인 )</span></p>'
        + '<p class="gv-sign"><span>세무대리인</span><span class="gv-seal">( 서명 또는 인 )</span></p>'
        + '<p class="gv-to">세 무 서 장 <small>귀하</small></p>')
      + '<p class="gv-spec">210mm×297mm[백상지 80g/㎡ 또는 중질지 80g/㎡]</p>'
    + '</div>'
    + '<div class="gv-stamp" aria-hidden="true">초안 · 제출용 아님</div></div>';
}

/* ══ 2) 항목별로 ══
   값마다 출처 · 상태 · 근거가 붙습니다. 성수 열 기준입니다 — 자료가 붙은 열입니다. */
function formRow(no, label, value, source, status, opt) {
  opt = opt || {};
  var v = value === null || value === undefined
    ? (opt.sensitive ? '<span class="val mask">••••••</span>'
                     : '<span class="val empty">' + (opt.emptyText || '비어 있음') + '</span>')
    : '<span class="val' + (source === 'CALCULATED' ? ' calc' : '') + '">'
      + (typeof value === 'number' ? won(value) : value) + '</span>';
  var stt = status === 'FILLED' ? '<span class="stt filled">채움</span>'
          : status === 'NEEDS_REVIEW' ? '<span class="stt review">확인</span>'
          : opt.sensitive ? '<span class="stt sens">직접 입력</span>'
          : '<span class="stt empty">빈 칸</span>';
  return '<div class="frow' + (opt.sum ? ' sum' : '') + '"' + (opt.key ? ' data-key="' + opt.key + '"' : '') + '>'
    + '<span class="no">' + no + '</span>'
    + '<span class="lb">' + label + (opt.sub ? '<span class="sub">' + opt.sub + '</span>' : '') + '</span>'
    + v
    + '<span class="src">' + stt + '<span class="src-k">' + FORM_SRC_KO[source] + '</span></span>'
    + (opt.basis ? '<span class="basis">' + opt.basis + '</span>' : '')
    + (opt.note ? '<span class="note">' + opt.note + '</span>' : '')
    + '</div>';
}

function formPaper(which, opt) {
  opt = opt || {};
  var f = FORMS[which], v = FORM_V, out = '', lastG = null;
  f.head.forEach(function (h) {
    if (lastG !== '인적사항') { out += '<h3>인적사항</h3>'; lastG = '인적사항'; }
    out += formRow(h.no || '·', h.label, h.value, h.src, h.st,
      { sensitive:h.sensitive, sub:h.sub, key:which + ':' + h.no });
  });
  f.rows.forEach(function (r) {
    if (r.g !== lastG) { out += '<h3>' + (r.g || '소득금액') + '</h3>'; lastG = r.g; }
    var x = formCell(r, v, 0);
    out += formRow(r.no, r.label + (r.calc || ''), x, r.src, r.st,
      { sub:r.sub, basis:r.basis, note:r.note, sum:r.sum, sensitive:r.sensitive, key:which + ':' + r.no });
  });
  return '<div class="fpaper' + (opt.tight ? ' tight' : '') + '">'
    + '<p class="ttl">' + f.name + '</p>'
    + (opt.tight ? '' : '<p class="off">' + f.off + '</p>')
    + out + '</div>';
}

/* 칸 세기. 화면에 손으로 적은 수는 칸을 고칠 때 같이 낡습니다.
   성수 열 기준입니다 — 상태(출처 · 확인 · 빈 칸)가 붙는 열입니다. */
function formStats(which) {
  var keys = which ? [which] : Object.keys(FORMS);
  var out = { all:0, filled:0, review:0, empty:0, sens:0 };
  keys.forEach(function (k) {
    var f = FORMS[k];
    f.head.concat(f.rows).forEach(function (r) {
      out.all++;
      if (r.st === 'FILLED') out.filled++;
      else if (r.st === 'NEEDS_REVIEW') out.review++;
      else if (r.sensitive) out.sens++;
      else out.empty++;
    });
  });
  return out;
}

/* 항목 하나를 찾습니다. 열쇠는 '서식:번호' 입니다 — 번호만으로는 본표 ⑮ 와 부표 ⑮ 가 겹칩니다 */
function formFind(key) {
  var p = String(key).split(':'), which = p[0], no = p[1], out = null;
  var f = FORMS[which]; if (!f) return null;
  f.head.concat(f.rows).forEach(function (r) { if (r.no === no && !out) out = { form:which, row:r }; });
  return out;
}

/* ══ 3) 그 칸이 무엇으로 채워졌나 ══
   거래내역 · 고정비 일정에서 근거를 끌어옵니다. 서식용으로 따로 적어 두면
   거래를 고칠 때 갈립니다. 연결된 것이 없으면 없다고 말합니다. */
var FORM_BASIS = {
  'sub:⑪': { cat:'정산', dir:'in',  ko:'정산 입금' },
  'sub:⑮': { cat:'재료비', dir:'out', ko:'재료 매입' },
  'sub:㉞': { cat:'소모품', dir:'out', ko:'소모품' },
  /* 임차료 · 공과금은 고정비 일정이 정본입니다. 거래내역에도 같은 건이 있어 둘 다 세면 두 번 셉니다 */
  'sub:㉗': { fixed:['임차료','임차료 관리비'], ko:'임차료', kind:'fixed' },
  'sub:㊴': { fixed:['전기와 가스'], ko:'전기 · 가스', kind:'fixed' },
  'sub:㉕': { fixed:['직원 급여'], ko:'급여', kind:'fixed' },
  'sub:㉘': { fixed:['대출 상환'], ko:'대출 원리금', kind:'fixed' },
  'sub:㉟': { fixed:['4대보험'], ko:'4대보험 사업자 부담분', kind:'fixed' },
  'sub:㉝': { ko:'정산 수수료', fromSettle:true }
};

function formBasisFor(key) {
  var d = FORM_BASIS[key];
  var rows = [], docs = 0, unrev = 0;
  var TXL = (typeof TX_BY_SHOP !== 'undefined' && TX_BY_SHOP.seongsu) || [];
  var FXL = (typeof FIXED_BY_SHOP !== 'undefined' && FIXED_BY_SHOP.seongsu) || [];
  var EVL = (typeof EVIDENCES !== 'undefined' && EVIDENCES) || [];
  if (d && d.cat) {
    TXL.filter(function (t) { return t[5] === d.cat && (!d.dir || t[3] === d.dir) && t[1].indexOf('카페') < 0; })
       .forEach(function (t) {
         var ev = EVL.filter(function (e) { return e[0] === t[1] || t[1].indexOf(e[0].slice(0, 3)) >= 0; })[0];
         if (ev) docs++;
         if (!t[8]) unrev++;
         rows.push({ date:t[0], who:t[1], memo:t[2], amt:(t[3] === 'in' ? t[4] : -t[4]), doc:!!ev, rev:t[8] });
       });
  }
  if (d && d.fixed) {
    FXL.filter(function (fx) { return d.fixed.indexOf(fx[1]) >= 0; })
       .forEach(function (fx) {
         rows.push({ date:fx[0].slice(5), who:fx[2], memo:fx[1], amt:-fx[3], doc:true, rev:true });
         docs++;
       });
  }
  rows.sort(function (a, b) { return Math.abs(b.amt) - Math.abs(a.amt); });
  return { rows:rows, count:rows.length, docs:docs, unrev:unrev, kind:(d && d.kind) || 'tx',
           note: d ? null : '연결된 거래가 없습니다', ko: d ? d.ko : null,
           fromSettle: !!(d && d.fromSettle) };
}
