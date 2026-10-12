// 금융망 콘솔 화면. 인터페이스 정의는 catalog.mjs 에만 있고 여기는 그리기만 한다.
//
// 화면이 붙드는 상태는 둘뿐이다 — 지금 어느 userKey 로 부르는지, 어느 계좌를 보는지.
// 스크립트로 할 때 번거로웠던 게 정확히 이 둘을 매번 다시 찾는 일이라 왼쪽에 고정해 뒀다.

import { BAD_STATUS, BY_ID, CODE_HINTS, FLOWS, NAV, shortId, VALUE_MAPS } from './catalog.mjs';

// ── DOM 만들기 ──────────────────────────────────────────────────────────

/** innerHTML 을 쓰지 않는다. 금융망 응답에 뭐가 들어와도 그대로 문자로 남는다. */
function el(tag, props = {}, children = []) {
  const node = document.createElement(tag);
  for (const [key, value] of Object.entries(props)) {
    if (value === null || value === undefined || value === false) continue;
    if (key === 'class') node.className = value;
    else if (key === 'text') node.textContent = value;
    else if (key === 'html') throw new Error('html 은 쓰지 않는다');
    else if (key.startsWith('on')) node.addEventListener(key.slice(2), value);
    else node.setAttribute(key, value === true ? '' : String(value));
  }
  for (const child of [children].flat(3)) {
    if (child === null || child === undefined || child === false) continue;
    node.append(typeof child === 'object' ? child : document.createTextNode(String(child)));
  }
  return node;
}

const $ = (id) => document.getElementById(id);

/** replaceChildren 은 Node 가 아닌 인자를 텍스트로 붙인다 — null 이 그대로 "null" 로 찍힌다. */
function fill(node, ...children) {
  node.replaceChildren(...children.flat(3).filter(Boolean));
}

// ── 표기 ────────────────────────────────────────────────────────────────

const MINUS = '−'; // 하이픈은 글꼴에서 붙임표로 읽힌다

/** 1340000 → 1,340,000원. 음수는 −1,340,000원. */
function won(value) {
  const n = Number(value);
  if (!Number.isFinite(n)) return '—';
  const body = Math.abs(n).toLocaleString('ko-KR');
  return `${n < 0 ? MINUS : ''}${body}원`;
}

function num(value) {
  const n = Number(value);
  return Number.isFinite(n) ? n.toLocaleString('ko-KR') : '—';
}

/** 20260909 → 2026.09.09 */
function ymd(value) {
  const s = String(value ?? '');
  return s.length === 8 ? `${s.slice(0, 4)}.${s.slice(4, 6)}.${s.slice(6)}` : s || '—';
}

/** 102447 → 10:24:47 */
function hms(value) {
  const s = String(value ?? '').padStart(6, '0');
  return s.length === 6 ? `${s.slice(0, 2)}:${s.slice(2, 4)}:${s.slice(4)}` : s;
}

/** 금융망 기준일(한국시간). 브라우저 시간대에 기대지 않는다. */
function kstToday() {
  const kst = new Date(Date.now() + 9 * 3600 * 1000);
  const p = (n) => String(n).padStart(2, '0');
  return `${kst.getUTCFullYear()}${p(kst.getUTCMonth() + 1)}${p(kst.getUTCDate())}`;
}

function shiftDays(yyyymmdd, days) {
  const y = Number(yyyymmdd.slice(0, 4));
  const m = Number(yyyymmdd.slice(4, 6));
  const d = Number(yyyymmdd.slice(6));
  const at = new Date(Date.UTC(y, m - 1, d + days));
  const p = (n) => String(n).padStart(2, '0');
  return `${at.getUTCFullYear()}${p(at.getUTCMonth() + 1)}${p(at.getUTCDate())}`;
}

// ── 상태 ────────────────────────────────────────────────────────────────

// sessionStorage 를 쓴다. userKey 는 사실상 계좌 접근권이라 탭을 닫으면 지워지는 쪽이 낫다.
const STORE = 'jango-fin-console';

function loadState() {
  try {
    return JSON.parse(sessionStorage.getItem(STORE) ?? '{}');
  } catch {
    return {};
  }
}

const state = loadState();

function saveState() {
  try {
    sessionStorage.setItem(STORE, JSON.stringify(state));
  } catch {
    /* 시크릿 창 등에서 막힐 수 있다. 이번 화면만 기억하고 넘어간다. */
  }
}

/** 화면이 부를 때 쓰는 사용자 지정. 시드 키는 이름만 보내고 값은 서버가 채운다. */
function userRef() {
  if (state.userKeyRef) return { userKeyRef: state.userKeyRef };
  if (state.userKey) return { userKey: state.userKey };
  return {};
}

function setUser({ userKey, userKeyRef, label }) {
  state.userKey = userKey ?? null;
  state.userKeyRef = userKeyRef ?? null;
  state.userLabel = label ?? null;
  saveState();
  renderCtx();
}

function setAccount(accountNo, label) {
  state.accountNo = accountNo ?? null;
  state.accountLabel = label ?? null;
  saveState();
  renderCtx();
}

let boot = { ready: false, seedRefs: [] };

// ── 서버 호출 ───────────────────────────────────────────────────────────

async function api(id, payload = {}, override = {}) {
  const body = { id, payload, ...userRef(), ...override };
  try {
    const res = await fetch('./api/call', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(body),
    });
    return await res.json();
  } catch (err) {
    return {
      ok: false,
      code: 'LOCAL',
      message: `콘솔 서버에 닿지 못했다 — ${err.message}`,
      request: null,
      response: null,
    };
  }
}

/** REC 은 단건이 객체, 목록이 배열로 온다. 부르는 쪽에서 매번 갈라 쓰지 않게 통일한다. */
const recOf = (result) => result?.response?.REC ?? null;
const recOne = (result) => {
  const rec = recOf(result);
  return Array.isArray(rec) ? (rec[0] ?? null) : rec;
};
const recList = (result) => {
  const rec = recOf(result);
  return Array.isArray(rec) ? rec : rec ? [rec] : [];
};

// ── 왼쪽 ────────────────────────────────────────────────────────────────

let current = 'flow:user';

function renderNav() {
  $('nav').replaceChildren(
    ...NAV.map((group) =>
      el('div', {}, [
        el('div', { class: 'nav-group-label', text: group.group }),
        ...group.items.map((id) => {
          const entry = BY_ID[id];
          const flow = FLOWS[id];
          const label = flow ? flow.title : entry.title;
          return el(
            'button',
            {
              class: 'nav-item',
              type: 'button',
              // 라벨이 중첩 span 안에 있어서 접근성 이름이 안 잡힌다. 명시해 준다.
              'aria-label': `${label} ${flow ? '' : id}`.trim(),
              title: entry?.write ? `${label} — 쓰기 작업` : label,
              'aria-current': String(id === current),
              onclick: () => go(id),
            },
            [
              el('span', {}, [
                label,
                entry?.write ? el('span', { class: 'dot', title: '쓰기 작업', text: ' ●' }) : null,
              ]),
              el('span', { class: 'nav-id', text: flow ? '묶음' : shortId(id) }),
            ],
          );
        }),
      ]),
    ),
  );
}

function renderCtx() {
  const userValue = state.userKeyRef
    ? state.userKeyRef
    : state.userKey
      ? (state.userLabel ?? `${state.userKey.slice(0, 8)}…`)
      : null;

  fill(
    $('ctx'),
    el('div', { class: 'ctx-row' }, [
      el('div', { class: 'ctx-label', text: '사용자' }),
      el('div', {
        class: `ctx-value${userValue ? '' : ' empty'}`,
        text: userValue ?? '지정 안 됨',
      }),
    ]),
    el('div', { class: 'ctx-row' }, [
      el('div', { class: 'ctx-label', text: '선택 계좌' }),
      el('div', {
        class: state.accountNo ? 'ctx-value num mono' : 'ctx-value empty',
        text: state.accountNo ?? '지정 안 됨',
      }),
      state.accountLabel ? el('div', { class: 'ctx-label', text: state.accountLabel }) : null,
    ]),
    userValue || state.accountNo
      ? el('button', {
          class: 'ctx-clear',
          type: 'button',
          text: '지우기',
          onclick: () => {
            setUser({});
            setAccount(null);
            go(current);
          },
        })
      : null,
  );
}

function renderBoot() {
  $('boot').replaceChildren(
    el('div', { text: boot.ready ? `apiKey …${boot.keyTail}` : 'apiKey 없음' }),
    el('div', { text: boot.baseUrl?.replace(/^https?:\/\//, '') ?? '' }),
    el('div', { text: `기관 ${boot.institutionCode} · 앱 ${boot.fintechAppNo}` }),
  );
}

// ── 결과 그리기 ─────────────────────────────────────────────────────────

function errorBox(result) {
  const hint = CODE_HINTS[result.code];
  return el('div', { class: 'err' }, [
    el('div', { class: 'err-code', text: result.code }),
    el('div', { class: 'err-msg', text: result.message || '금융망이 실패로 응답했다.' }),
    hint ? el('div', { class: 'err-hint', text: hint }) : null,
  ]);
}

function rawPanel(result) {
  if (!result.request && !result.response) return null;
  const dump = (label, value) => [
    el('div', { class: 'raw-label' }, [
      label,
      el('button', {
        class: 'copy',
        type: 'button',
        text: '복사',
        onclick: () => navigator.clipboard?.writeText(JSON.stringify(value, null, 2)),
      }),
    ]),
    el('pre', { text: JSON.stringify(value, null, 2) }),
  ];

  return el('details', { class: 'raw' }, [
    el('summary', { text: '요청·응답 원문' }),
    result.request ? dump('요청 (apiKey 는 가림)', result.request) : null,
    result.response ? dump('응답', result.response) : null,
  ]);
}

function kv(pairs) {
  return el(
    'div',
    { class: 'kv' },
    pairs.flatMap(([k, v]) => [
      el('div', { class: 'k', text: k }),
      el('div', { class: 'v', text: v }),
    ]),
  );
}

function table(headers, rows) {
  return el('div', { class: 'scroll' }, [
    el('table', {}, [
      el('thead', {}, [
        el(
          'tr',
          {},
          headers.map((h) => el('th', { class: h.right ? 'r' : null, text: h.label })),
        ),
      ]),
      el('tbody', {}, rows),
    ]),
  ]);
}

const EMPTY = (text) => el('div', { class: 'empty-state', text });

/** 계좌 목록 — 행을 누르면 선택 계좌가 된다. */
function renderAccounts(result) {
  const list = recList(result);
  if (!list.length) return EMPTY('이 사용자에게 수시입출금 계좌가 없다.');

  return table(
    [
      { label: '은행' },
      { label: '계좌번호' },
      { label: '상품' },
      { label: '개설일' },
      { label: '잔액', right: true },
    ],
    list.map((row) => {
      const on = row.accountNo === state.accountNo;
      return el(
        'tr',
        {
          class: `pick${on ? ' on' : ''}`,
          title: '이 계좌를 선택 계좌로 잡는다',
          onclick: () => {
            setAccount(row.accountNo, row.bankName);
            go('DEMAND_DEPOSIT_12', { accountNo: row.accountNo }, true);
          },
        },
        [
          el('td', { text: row.bankName ?? row.bankCode ?? '—' }),
          el('td', { class: 'mono num', text: row.accountNo ?? '—' }),
          el('td', {}, [
            row.accountName ?? '—',
            on ? el('span', { class: 'type-badge in', text: ' 선택' }) : null,
          ]),
          el('td', { class: 'num', text: ymd(row.accountCreatedDate) }),
          el('td', { class: 'r num', text: won(row.accountBalance) }),
        ],
      );
    }),
  );
}

function renderBalance(result) {
  const rec = recOne(result);
  if (!rec) return EMPTY('응답에 REC 이 없다.');
  return el('div', {}, [
    el('div', { class: 'big num', text: won(rec.accountBalance) }),
    el('div', {
      class: 'big-sub num mono',
      text: `${rec.accountNo ?? ''} · 최종거래 ${ymd(rec.lastTransactionDate)}`,
    }),
  ]);
}

const TX_LABEL = { 1: '입금', 2: '출금' };

function renderTxList(result) {
  const rec = recOf(result);
  const list = rec?.list ?? [];
  if (!list.length) return EMPTY('이 기간에 거래가 없다.');

  let inSum = 0;
  let outSum = 0;
  for (const tx of list) {
    const value = Number(tx.transactionBalance) || 0;
    if (String(tx.transactionType) === '1') inSum += value;
    else outSum += value;
  }

  const rows = list.map((tx) => {
    const isIn = String(tx.transactionType) === '1';
    const amount = Number(tx.transactionBalance) || 0;
    return el('tr', {}, [
      el('td', { class: 'num mono' }, [
        ymd(tx.transactionDate),
        el('div', { class: 'sub-time', text: hms(tx.transactionTime) }),
      ]),
      el('td', {}, [
        el('span', {
          class: `type-badge${isIn ? ' in' : ''}`,
          text: tx.transactionTypeName ?? TX_LABEL[tx.transactionType] ?? '—',
        }),
      ]),
      el('td', {}, [
        tx.transactionSummary || '(적요 없음)',
        tx.transactionMemo ? el('div', { class: 'big-sub', text: tx.transactionMemo }) : null,
      ]),
      el('td', {
        class: `r num ${isIn ? 'amt-in' : 'amt-out'}`,
        text: `${isIn ? '+' : MINUS}${won(amount)}`,
      }),
      el('td', { class: 'r num', text: won(tx.transactionAfterBalance) }),
      el('td', { class: 'num mono', text: tx.transactionUniqueNo ?? '—' }),
    ]);
  });

  return el('div', {}, [
    el('div', { class: 'sum' }, [
      el('div', {}, [
        el('div', {
          class: 'k',
          text: `입금 ${list.filter((t) => String(t.transactionType) === '1').length}건`,
        }),
        el('div', { class: 'v num amt-in', text: won(inSum) }),
      ]),
      el('div', {}, [
        el('div', {
          class: 'k',
          text: `출금 ${list.filter((t) => String(t.transactionType) !== '1').length}건`,
        }),
        el('div', { class: 'v num', text: won(outSum) }),
      ]),
      el('div', {}, [
        el('div', { class: 'k', text: '순증' }),
        el('div', { class: 'v num', text: won(inSum - outSum) }),
      ]),
    ]),
    table(
      [
        { label: '일시' },
        { label: '구분' },
        { label: '적요' },
        { label: '금액', right: true },
        { label: '거래후잔액', right: true },
        { label: '고유번호' },
      ],
      rows,
    ),
    el('div', { class: 'big-sub', text: `금융망 조회 총건수 ${num(rec.totalCount)}건` }),
  ]);
}

function renderProducts(result) {
  const list = recList(result);
  if (!list.length) return EMPTY('등록된 수시입출금 상품이 없다. 상품 등록(DD_01)부터 한다.');

  return table(
    [
      { label: '은행' },
      { label: '상품명' },
      { label: '상품 고유번호' },
      { label: '통화' },
      { label: '' },
    ],
    list.map((row) =>
      el('tr', {}, [
        el('td', { text: `${row.bankName ?? ''} ${row.bankCode ?? ''}`.trim() }),
        el('td', { text: row.accountName ?? '—' }),
        el('td', { class: 'mono num', text: row.accountTypeUniqueNo ?? '—' }),
        el('td', { text: row.accountType === 'DOMESTIC' ? '원화' : (row.accountType ?? '—') }),
        el('td', { class: 'r' }, [
          el('button', {
            class: 'chip',
            type: 'button',
            text: '이 상품으로 계좌 생성',
            onclick: () =>
              go('DEMAND_DEPOSIT_03', { accountTypeUniqueNo: row.accountTypeUniqueNo }),
          }),
        ]),
      ]),
    ),
  );
}

function renderBanks(result) {
  const list = recList(result);
  return table(
    [{ label: '코드' }, { label: '은행명' }],
    list.map((row) =>
      el('tr', {}, [
        el('td', { class: 'mono num', text: row.bankCode ?? '—' }),
        el('td', {}, [
          row.bankName ?? '—',
          row.bankCode === '999' ? el('span', { class: 'type-badge in', text: ' 개발용' }) : null,
        ]),
      ]),
    ),
  );
}

function renderTransfer(result) {
  const list = recList(result);
  return table(
    [
      { label: '구분' },
      { label: '계좌번호' },
      { label: '상대 계좌' },
      { label: '거래일' },
      { label: '고유번호' },
    ],
    list.map((row) =>
      el('tr', {}, [
        el('td', {}, [
          el('span', {
            class: `type-badge${String(row.transactionType) === '1' ? ' in' : ''}`,
            text: row.transactionTypeName ?? '—',
          }),
        ]),
        el('td', { class: 'mono num', text: row.accountNo ?? '—' }),
        el('td', { class: 'mono num', text: row.transactionAccountNo || '—' }),
        el('td', { class: 'num', text: ymd(row.transactionDate) }),
        el('td', { class: 'mono num', text: row.transactionUniqueNo ?? '—' }),
      ]),
    ),
  );
}

/** 이름이 붙은 필드는 사람 말로 바꿔서 보여준다. 나머지는 그대로 둔다. */
const FIELD_LABEL = {
  userId: '이메일',
  username: '이름',
  userKey: 'userKey',
  institutionCode: '기관코드',
  created: '생성일',
  modified: '수정일',
  bankCode: '은행코드',
  bankName: '은행명',
  userName: '예금주명',
  accountNo: '계좌번호',
  accountName: '상품명',
  accountDescription: '상품설명',
  accountTypeUniqueNo: '상품 고유번호',
  accountTypeCode: '상품구분코드',
  accountTypeName: '상품종류명',
  accountCreatedDate: '계좌개설일',
  accountExpiryDate: '계좌만기일',
  accountBalance: '계좌잔액',
  lastTransactionDate: '최종거래일',
  dailyTransferLimit: '1일 이체한도',
  oneTimeTransferLimit: '1회 이체한도',
  currency: '통화',
  currencyName: '통화명',
  transactionUniqueNo: '거래고유번호',
  transactionDate: '거래일자',
  transactionTime: '거래시각',
  transactionType: '입금출금구분',
  transactionTypeName: '구분명',
  transactionAccountNo: '거래계좌번호',
  transactionBalance: '거래금액',
  transactionAfterBalance: '거래후잔액',
  transactionSummary: '적요',
  transactionMemo: '메모',
  memoUniqueNo: '메모 고유번호',
  status: '상태',
  authText: '기업명',
  createdAt: '생성 일시',
  updatedAt: '수정 일시',

  // 예약이체
  reservationId: '예약이체 ID',
  reservationName: '예약이체명',
  reservationType: '예약 유형',
  withdrawalAccountNo: '출금 계좌번호',
  depositAccountNo: '입금 계좌번호',
  depositBankCode: '입금 은행코드',
  withdrawalTransactionSummary: '적요 (출금계좌)',
  depositTransactionSummary: '적요 (입금계좌)',
  transferCycle: '이체 주기',
  transferDay: '월간 이체일',
  nextTransferDate: '다음 이체일',
  startDate: '시작일',
  endDate: '종료일',
  transferDate: '이체일',
  transferTime: '이체 시각',
  resultCode: '결과 코드',
  resultMessage: '결과 메시지',

  // 대출·신용
  ratingUniqueNo: '신용등급 기준 고유번호',
  ratingName: '신용등급명',
  minAssetValue: '최소 자산',
  maxAssetValue: '최대 자산',
  demandDepositAssetValue: '수시입출금 자산',
  depositSavingsAssetValue: '예·적금 자산',
  totalAssetValue: '전체 자산',
  loanPeriod: '대출기간',
  loanDate: '대출일',
  maturityDate: '만기일',
  loanBalance: '대출금',
  remainingLoanBalance: '남은 대출금',
  minLoanBalance: '최소 대출 금액',
  maxLoanBalance: '최대 대출 금액',
  interestRate: '금리',
  loanTypeName: '대출 구분',
  repaymentMethodTypeName: '상환 방법',
  applicationDate: '심사 신청일',
  applicationTime: '심사 신청 시각',
  decisionDate: '심사일',
  decisionTime: '심사 시각',
  installmentNumber: '회차',
  paymentBalance: '상환 금액',
  repaymentAttemptDate: '상환 시도일',
  repaymentAttemptTime: '상환 시도 시각',
  repaymentActualDate: '실제 상환일',
  repaymentActualTime: '실제 상환 시각',
  failureReason: '실패 이유',

  // 마이너스통장
  loanAccountNo: '마이너스통장 계좌번호',
  linkedAccountNo: '연결 계좌번호',
  productName: '상품명',
  creditLimit: '신용 한도',
  newCreditLimit: '변경 후 한도',
  usedAmount: '사용 금액',
  availableAmount: '가용 금액',
  accruedInterest: '누적 이자',
  interestPaymentDay: '이자 납입일',
  autoRepaymentDay: '자동 상환일',
  repaymentType: '상환 방식',
  nextInterestDate: '다음 이자 납입일',
  overdueDays: '연체 일수',
  loanPeriodMonths: '대출 기간(개월)',
  repaidInterest: '상환된 이자',
  repaidPrincipal: '상환된 원금',
  paidAmount: '납부 금액',
  transactionId: '거래 고유번호',
  balanceAfter: '거래 후 사용액',
  transactionAt: '거래 일시',
  accrueDate: '이자 발생일',
  dailyInterest: '일 이자',
  accruedTotal: '누적 이자 합계',
  alertId: '알림 고유번호',
  alertType: '알림 유형',
  title: '제목',
  message: '내용',
  isRead: '읽음',
  amount: '금액',

  // 가상계좌
  virtualAccountId: '가상계좌 ID',
  virtualAccountNo: '가상계좌번호',
  mainAccountNo: '연결 계좌번호',
  depositorName: '입금자명',
  expectedAmount: '예상 입금 금액',
  expiryDate: '만료일',
  memo: '메모',
  autoTransfer: '입금 후 자동 이체',
  depositedAmount: '누적 입금 금액',
  pendingAmount: '미정산 보류 금액',
  depositAmount: '입금 금액',
  nameMatched: '입금자명 일치',
  transferred: '연결계좌 이체',
  depositedAt: '입금 일시',
  settledAt: '정산 일시',
  settledAmount: '정산 금액',
};

const MONEY_FIELDS = new Set([
  'accountBalance',
  'transactionBalance',
  'transactionAfterBalance',
  'dailyTransferLimit',
  'oneTimeTransferLimit',
  'minAssetValue',
  'maxAssetValue',
  'demandDepositAssetValue',
  'depositSavingsAssetValue',
  'totalAssetValue',
  'loanBalance',
  'remainingLoanBalance',
  'minLoanBalance',
  'maxLoanBalance',
  'paymentBalance',
  'creditLimit',
  'newCreditLimit',
  'usedAmount',
  'availableAmount',
  'accruedInterest',
  'repaidInterest',
  'repaidPrincipal',
  'paidAmount',
  'balanceAfter',
  'dailyInterest',
  'accruedTotal',
  'expectedAmount',
  'depositedAmount',
  'pendingAmount',
  'depositAmount',
  'settledAmount',
  'amount',
]);

/** YYYYMMDD */
const DATE_FIELDS = new Set([
  'accountCreatedDate',
  'accountExpiryDate',
  'lastTransactionDate',
  'transactionDate',
  'nextTransferDate',
  'transferDate',
  'startDate',
  'endDate',
  'loanDate',
  'maturityDate',
  'applicationDate',
  'decisionDate',
  'repaymentAttemptDate',
  'repaymentActualDate',
  'nextInterestDate',
  'accrueDate',
  'expiryDate',
]);

/** YYYYMMDDHHmmss — 2026년에 추가된 계열은 일시를 14자 한 덩어리로 준다. */
const DATETIME_FIELDS = new Set([
  'createdAt',
  'updatedAt',
  'transactionAt',
  'depositedAt',
  'settledAt',
]);

const RATE_FIELDS = new Set(['interestRate']);
const DAYS_FIELDS = new Set(['loanPeriod', 'overdueDays']);
const TIME_FIELDS = new Set([
  'transactionTime',
  'transferTime',
  'applicationTime',
  'decisionTime',
  'repaymentAttemptTime',
  'repaymentActualTime',
]);

/** 20260909131608 → 2026.09.09 13:16:08 */
function ymdhms(value) {
  const s = String(value ?? '');
  return s.length === 14 ? `${ymd(s.slice(0, 8))} ${hms(s.slice(8))}` : s || '—';
}

const yesNo = (value) => {
  if (value === true || value === 'true') return '예';
  if (value === false || value === 'false') return '아니오';
  return '—';
};

/**
 * 필드 이름만 보고 표기를 정한다. 낱값 화면(record)과 표(table)가 같은 규칙을 써야
 * 같은 필드가 두 화면에서 다르게 보이지 않는다.
 */
function formatField(key, value) {
  if (value === null || value === undefined || value === '') return '—';
  if (typeof value === 'boolean') return yesNo(value);
  if (MONEY_FIELDS.has(key)) return won(value);
  if (RATE_FIELDS.has(key)) return `${value}%`;
  if (DAYS_FIELDS.has(key)) return `${num(value)}일`;
  if (DATETIME_FIELDS.has(key)) return ymdhms(value);
  if (DATE_FIELDS.has(key)) return ymd(value);
  if (TIME_FIELDS.has(key)) return hms(value);
  return String(value);
}

/** 상태는 색만으로 구분하지 않는다 — 코드값을 반드시 글자로 함께 띄운다. */
function statusBadge(value, mapName) {
  const raw = String(value ?? '');
  if (!raw) return '—';
  const gloss = mapName ? VALUE_MAPS[mapName]?.[raw] : null;
  return el('span', {
    class: `type-badge${BAD_STATUS.has(raw) ? ' bad' : ''}`,
    text: gloss ? `${raw} ${gloss}` : raw,
  });
}

const tile = (label, value) =>
  el('div', {}, [
    el('div', { class: 'k', text: label }),
    el('div', { class: 'v num', text: value }),
  ]);

function renderRecord(result) {
  // MEMBER 계열은 REC 없이 최상위로 온다.
  const rec = recOne(result) ?? (result.response?.userKey ? result.response : null);
  if (!rec || typeof rec !== 'object') return EMPTY('응답에 표시할 값이 없다. 아래 원문을 본다.');

  const pairs = Object.entries(rec)
    .filter(([, value]) => typeof value !== 'object' || value === null)
    .map(([key, value]) => [FIELD_LABEL[key] ?? key, formatField(key, value)]);

  return kv(pairs);
}

/**
 * columns 로 그리는 일반 표. 계열마다 렌더러를 새로 짜지 않으려고 둔 것이다.
 *
 * <p>2026년에 추가된 계열은 목록을 REC 밑의 <b>이름 붙은 키</b>에 담는다
 * (`reservations` · `history` · `deposits` · `accounts` · `alerts` · `ledger`) —
 * 2024년 계열은 REC 자체가 배열이다. 그 차이를 catalog 의 `listKey` 가 흡수한다.
 *
 * <p>목록과 나란히 온 낱값은 표 위에 요약으로 올린다. LOAN_09 의 남은 대출금이 그렇고,
 * 그게 상환 내역을 볼 때 정작 알고 싶은 값이다.
 */
function renderTable(result, entry) {
  const rec = recOf(result);
  if (!rec) return EMPTY('응답에 REC 이 없다.');

  const list = entry.listKey ? (rec[entry.listKey] ?? []) : Array.isArray(rec) ? rec : [];
  const siblings = Array.isArray(rec)
    ? []
    : Object.entries(rec).filter(
        ([key, value]) => key !== entry.listKey && (typeof value !== 'object' || value === null),
      );

  const rows = list.map((row) => {
    const to = entry.rowTo;
    return el(
      'tr',
      to
        ? {
            class: 'pick',
            title: `${BY_ID[to.id]?.title ?? to.id} 로 이어서 본다`,
            onclick: () => go(to.id, { [to.field]: row[to.field] }, true),
          }
        : {},
      entry.columns.map((column) => cell(row, column)),
    );
  });

  return el('div', {}, [
    siblings.length
      ? kv(siblings.map(([key, value]) => [FIELD_LABEL[key] ?? key, formatField(key, value)]))
      : null,
    siblings.length && list.length ? el('div', { class: 'spacer' }) : null,
    list.length
      ? table(
          entry.columns.map((column) => ({ label: column.label, right: column.right })),
          rows,
        )
      : EMPTY('목록이 비어 있다.'),
  ]);
}

function cell(row, column) {
  const value = row[column.key];
  if (column.badge)
    return el('td', { class: column.right ? 'r' : null }, [statusBadge(value, column.map)]);
  if (column.bool) return el('td', { text: yesNo(value) });
  const classes = ['num', column.mono ? 'mono' : null, column.right ? 'r' : null].filter(Boolean);
  return el('td', { class: classes.join(' '), text: formatField(column.key, value) });
}

/**
 * 내 신용등급. <b>실제 신용등급처럼 보이게 하면 안 된다</b> — 금융망 등급은 자산 합계로만
 * 산출되고 연체 이력도 소득도 반영되지 않는다(기술스택 4.13). 그래서 등급 옆에 근거를 붙인다.
 */
function renderCredit(result) {
  const rec = recOne(result);
  if (!rec) return EMPTY('응답에 REC 이 없다.');

  return el('div', {}, [
    el('div', { class: 'big', text: `${rec.ratingName ?? '—'} 등급` }),
    el('div', { class: 'big-sub', text: '보유 자산 기준 참고 등급 — 실제 신용등급이 아니다' }),
    el('div', { class: 'sum', style: 'margin-top:20px' }, [
      tile('수시입출금 자산', won(rec.demandDepositAssetValue)),
      tile('예·적금 자산', won(rec.depositSavingsAssetValue)),
      tile('전체 자산', won(rec.totalAssetValue)),
    ]),
    el('div', {
      class: 'notice',
      text: '금융망 신용등급은 자산 합계로만 산출된다. 연체 이력도 소득도 반영되지 않으므로 화면에서 실제 신용등급처럼 보이게 하면 안 된다 — "보유 자산 기준 참고 등급"으로 표기한다.',
    }),
  ]);
}

const RENDERERS = {
  record: renderRecord,
  accounts: renderAccounts,
  balance: renderBalance,
  txlist: renderTxList,
  products: renderProducts,
  banks: renderBanks,
  transfer: renderTransfer,
  table: renderTable,
  credit: renderCredit,
};

function renderResult(entry, result) {
  const body = el('div', { class: 'card' }, [
    el('div', { class: 'card-title', text: result.ok ? '응답' : '실패' }),
    result.ok ? null : errorBox(result),
    result.ok ? (RENDERERS[entry.render] ?? renderRecord)(result, entry) : null,
    rawPanel(result),
  ]);
  return body;
}

// ── 폼 ─────────────────────────────────────────────────────────────────

/** 계좌 필드는 선택 계좌를, 기간은 최근 30일을 미리 채운다. 매번 다시 치지 않게. */
function defaultFor(field, prefill) {
  if (prefill && field.name in prefill) return prefill[field.name];
  if (field.type === 'account' && state.accountNo) return state.accountNo;
  if (field.name === 'startDate') return shiftDays(kstToday(), -29);
  if (field.name === 'endDate') return kstToday();
  return field.default ?? '';
}

/** YYYYMMDD ↔ input[type=date] 의 YYYY-MM-DD */
const toInputDate = (value) =>
  /^\d{8}$/.test(value) ? `${value.slice(0, 4)}-${value.slice(4, 6)}-${value.slice(6)}` : '';
const fromInputDate = (value) => value.replaceAll('-', '');

function buildField(field, prefill) {
  const initial = defaultFor(field, prefill);
  const id = `f-${field.name}`;
  let input;

  if (field.type === 'select') {
    input = el(
      'select',
      { id, name: field.name },
      field.options.map((opt) =>
        el('option', { value: opt.value, selected: opt.value === initial, text: opt.label }),
      ),
    );
  } else if (field.type === 'boolean') {
    // 금융망에서 문자열이 아닌 유일한 값이다. 빈 값은 키 자체를 빼고 보낸다.
    input = el(
      'select',
      { id, name: field.name },
      [
        { value: '', label: '(미입력)' },
        { value: 'true', label: '예' },
        { value: 'false', label: '아니오' },
      ].map((opt) =>
        el('option', { value: opt.value, selected: opt.value === initial, text: opt.label }),
      ),
    );
  } else if (field.type === 'date') {
    input = el('input', { id, name: field.name, type: 'date', value: toInputDate(initial) });
  } else {
    input = el('input', {
      id,
      name: field.name,
      type: 'text',
      value: initial,
      placeholder: field.placeholder ?? '',
      maxlength: field.maxLength,
      inputmode: field.type === 'amount' || field.type === 'account' ? 'numeric' : null,
      autocomplete: 'off',
      class: field.type === 'account' || field.type === 'amount' ? 'num mono' : null,
    });
  }

  const hint = el('div', {
    class: `hint${field.type === 'amount' ? ' amount' : ''}`,
    text: field.hint ?? '',
  });
  if (field.type === 'amount') {
    // 0 이 몇 개인지 눈으로 세지 않게 즉시 한글 금액으로 되비춘다.
    const echo = () => {
      const raw = input.value.trim().replaceAll(',', '');
      hint.textContent = raw ? won(raw) : (field.hint ?? '금액만 숫자로 넣는다.');
    };
    input.addEventListener('input', echo);
    echo();
  }

  return el('div', { class: 'field' }, [
    el('label', { for: id }, [
      field.label,
      field.required ? el('span', { class: 'req', text: '*' }) : null,
    ]),
    input,
    field.type === 'amount' || hint.textContent ? hint : null,
  ]);
}

function readForm(form, fields) {
  const payload = {};
  for (const field of fields) {
    const node = form.elements[field.name];
    let value = (node?.value ?? '').trim();
    if (field.type === 'date') value = fromInputDate(value);
    if (field.type === 'amount') value = value.replaceAll(',', '');
    if (field.type === 'boolean' && value) {
      payload[field.name] = value === 'true';
      continue;
    }
    if (!value) {
      if (field.required) throw new Error(`${field.label}을(를) 채운다.`);
      continue; // 선택 항목은 키 자체를 빼고 보낸다
    }
    payload[field.name] = value; // ⚠ 금액도 문자열이다. 숫자로 보내면 Q1001 이다.
  }
  return payload;
}

/** 쓰기 작업은 확인을 한 번 받는다 — 금융망 잔액이 실제로 움직인다. */
function confirmBox(entry, payload, onRun) {
  const rows = entry.fields
    .filter((field) => payload[field.name] !== undefined)
    .map((field) => [
      field.label,
      field.type === 'amount' ? won(payload[field.name]) : payload[field.name],
    ]);

  const box = el('div', { class: 'confirm' }, [
    el('div', { class: 'confirm-title', text: '이 요청을 금융망에 보낸다' }),
    el(
      'dl',
      {},
      rows.flatMap(([k, v]) => [el('dt', { text: k }), el('dd', { text: v })]),
    ),
    el('div', { class: 'btn-row' }, [
      el('button', {
        class: 'btn ghost',
        type: 'button',
        text: '취소',
        onclick: () => box.remove(),
      }),
      el('button', {
        class: 'btn danger',
        type: 'button',
        text: '실행',
        onclick: () => {
          box.remove();
          onRun();
        },
      }),
    ]),
  ]);
  return box;
}

function needUserKeyNotice() {
  return el('div', { class: 'card' }, [
    el('div', { class: 'notice' }, [
      'userKey 가 지정되지 않았다. 이 인터페이스는 사용자를 특정해야 부를 수 있다. ',
      el('button', {
        class: 'chip',
        type: 'button',
        text: 'userKey 확보로 가기',
        onclick: () => go('flow:user'),
      }),
    ]),
  ]);
}

/** 제목이 이미 '조회'로 끝나면 덧붙이지 않는다 — "잔액 조회 조회" 가 된다. */
function buttonLabel(entry) {
  if (entry.write) return `${entry.title} 실행`;
  return entry.title.endsWith('조회') ? entry.title : `${entry.title} 조회`;
}

function renderApiView(entry, prefill, autorun) {
  const slot = $('slot');
  slot.replaceChildren();

  if (entry.userKey === 'required' && !state.userKey && !state.userKeyRef) {
    slot.append(needUserKeyNotice());
    return;
  }

  const results = el('div', {});
  const form = el(
    'form',
    { class: 'fields' },
    entry.fields.map((field) => buildField(field, prefill)),
  );

  // 거래내역만 기간 단추를 붙인다. 여기서만 매번 날짜를 바꿔 넣는다.
  const presets =
    entry.id === 'DEMAND_DEPOSIT_12'
      ? el(
          'div',
          { class: 'presets' },
          [
            ['오늘', 0],
            ['7일', 6],
            ['30일', 29],
            ['90일', 89],
            ['1년', 364],
          ].map(([label, back]) =>
            el('button', {
              class: 'chip',
              type: 'button',
              text: label,
              onclick: () => {
                form.elements.startDate.value = toInputDate(shiftDays(kstToday(), -back));
                form.elements.endDate.value = toInputDate(kstToday());
              },
            }),
          ),
        )
      : null;

  const button = el('button', {
    class: `btn${entry.write ? ' danger' : ''}`,
    type: 'submit',
    text: buttonLabel(entry),
  });

  const run = async (payload) => {
    button.disabled = true;
    button.textContent = '금융망 응답 대기';
    const result = await api(entry.id, payload);
    button.disabled = false;
    button.textContent = buttonLabel(entry);

    // 계좌를 조회했으면 그 계좌를 선택 계좌로 잡아 둔다 — 다음 화면에서 그대로 쓴다.
    if (result.ok && payload.accountNo) setAccount(payload.accountNo, state.accountLabel);
    if (result.ok && entry.kind === 'member' && result.response?.userKey) {
      setUser({ userKey: result.response.userKey, label: result.response.userId });
    }

    results.replaceChildren(renderResult(entry, result));
  };

  form.addEventListener('submit', (event) => {
    event.preventDefault();
    let payload;
    try {
      payload = readForm(form, entry.fields);
    } catch (err) {
      results.replaceChildren(
        el('div', { class: 'card' }, [
          el('div', { class: 'err' }, [el('div', { class: 'err-msg', text: err.message })]),
        ]),
      );
      return;
    }
    if (entry.write) form.append(confirmBox(entry, payload, () => run(payload)));
    else run(payload);
  });

  form.append(button);
  slot.append(el('div', { class: 'card' }, [presets, form]), results);

  // 파라미터가 없는 조회는 들어오자마자 부른다. 누를 이유가 없다.
  // autorun 은 계좌 목록에서 행을 눌러 들어온 경우다 — 이미 "이 계좌를 보겠다"고 누른 셈이다.
  if (!entry.write && (!entry.fields.length || autorun)) {
    try {
      run(readForm(form, entry.fields));
    } catch {
      /* 채울 값이 비어 있으면 그냥 폼만 보여준다 */
    }
  }
}

// ── 묶음 작업 ───────────────────────────────────────────────────────────

function steps(items) {
  return el(
    'div',
    { class: 'steps' },
    items.map((item) =>
      el('div', { class: `step ${item.state ?? ''}` }, [
        el('span', {
          class: 'step-mark',
          text: item.state === 'done' ? '✓' : item.state === 'fail' ? '✗' : '·',
        }),
        el('span', { class: 'step-text', text: item.text }),
      ]),
    ),
  );
}

/** userKey 확보 — MEMBER_02 로 찾고, 없으면 MEMBER_01 로 만든다. */
function flowUser() {
  const slot = $('slot');
  const results = el('div', {});

  const emailField = buildField(
    {
      name: 'userId',
      label: '이메일',
      type: 'text',
      required: true,
      placeholder: 'dev1@jango.test',
    },
    { userId: state.userLabel ?? '' },
  );
  const form = el('form', { class: 'fields' }, [emailField]);
  const button = el('button', { class: 'btn', type: 'submit', text: '조회' });
  form.append(button);

  const show = (nodes) => results.replaceChildren(el('div', { class: 'card' }, nodes));

  async function create(email) {
    const made = await api('MEMBER_01', { userId: email }, { userKey: null, userKeyRef: null });
    if (!made.ok) {
      show([
        steps([
          { text: `${email} 조회 — 없음`, state: 'done' },
          { text: '생성 실패', state: 'fail' },
        ]),
        errorBox(made),
        rawPanel(made),
      ]);
      return;
    }
    setUser({ userKey: made.response.userKey, label: made.response.userId });
    show([
      steps([
        { text: `${email} 조회 — 없음`, state: 'done' },
        { text: '생성 완료', state: 'done' },
      ]),
      el('div', { class: 'ok-banner', text: 'userKey 를 발급했다. 이제 이 사용자로 부른다.' }),
      renderRecord(made),
      el('div', { class: 'btn-row' }, [
        el('button', {
          class: 'btn ghost',
          type: 'button',
          text: '계좌 만들기',
          onclick: () => go('flow:new-account'),
        }),
      ]),
      rawPanel(made),
    ]);
  }

  form.addEventListener('submit', async (event) => {
    event.preventDefault();
    const email = form.elements.userId.value.trim();
    if (!email) return;

    button.disabled = true;
    button.textContent = '조회 중';
    // userKey 를 쓰지 않는 인터페이스지만, 지금 잡혀 있는 값을 함께 보내지 않도록 비운다.
    const found = await api('MEMBER_02', { userId: email }, { userKey: null, userKeyRef: null });
    button.disabled = false;
    button.textContent = '조회';

    if (found.ok) {
      setUser({ userKey: found.response.userKey, label: found.response.userId });
      show([
        el('div', { class: 'ok-banner', text: '찾았다. 이제 이 사용자로 부른다.' }),
        renderRecord(found),
        el('div', { class: 'btn-row' }, [
          el('button', {
            class: 'btn ghost',
            type: 'button',
            text: '계좌 목록 보기',
            onclick: () => go('DEMAND_DEPOSIT_04'),
          }),
          el('button', {
            class: 'btn ghost',
            type: 'button',
            text: '계좌 만들기',
            onclick: () => go('flow:new-account'),
          }),
        ]),
        rawPanel(found),
      ]);
      return;
    }

    show([
      steps([{ text: `${email} — 금융망에 없는 사용자다`, state: 'fail' }]),
      errorBox(found),
      el('div', {
        class: 'notice',
        text: '이 이메일로 계정을 만들면 userKey 가 발급된다. 이메일은 금융망 전역에서 유니크해서, 다른 팀이 이미 쓴 주소는 만들 수 없다(E4002).',
      }),
      el('button', {
        class: 'btn',
        type: 'button',
        text: `${email} 로 사용자 생성`,
        onclick: () => create(email),
      }),
      rawPanel(found),
    ]);
  });

  fill(
    slot,
    el('div', { class: 'card' }, [form]),
    boot.seedRefs.length
      ? el('div', { class: 'card' }, [
          el('div', { class: 'card-title', text: '시드 계정' }),
          el('div', {
            class: 'notice',
            text: 'backend/.env.seed.local 에 있는 userKey 다. 값은 콘솔 서버가 채우고 브라우저로 내려오지 않는다.',
          }),
          el(
            'div',
            { class: 'presets', style: 'margin-top:12px' },
            boot.seedRefs.map((ref) =>
              el('button', {
                class: 'chip',
                type: 'button',
                text: ref.replace('SEED_USERKEY_', ''),
                onclick: () => {
                  setUser({ userKeyRef: ref, label: ref });
                  go('DEMAND_DEPOSIT_04');
                },
              }),
            ),
          ),
        ])
      : null,
    results,
  );
}

/** 계좌 만들기 — 상품 확보 → 계좌 생성 → 초기 입금. scripts/fin-account.mjs 와 같은 순서다. */
function flowNewAccount() {
  const slot = $('slot');
  slot.replaceChildren();

  if (!state.userKey && !state.userKeyRef) {
    slot.append(needUserKeyNotice());
    return;
  }

  const results = el('div', {});
  const form = el('form', { class: 'fields' }, [
    buildField({
      name: 'bankCode',
      label: '은행코드',
      type: 'text',
      required: true,
      default: '999',
      maxLength: 3,
      hint: '999 = 싸피은행. 실제 은행망과 무관한 가상 은행이라 개발·시연은 여기에 만든다.',
    }),
    buildField({
      name: 'openingBalance',
      label: '초기 잔액',
      type: 'amount',
      required: false,
      hint: '비우면 입금하지 않는다. 넣으면 오늘 거래로 한 건 남는다.',
    }),
  ]);
  const button = el('button', { class: 'btn danger', type: 'submit', text: '계좌 만들기' });
  form.append(button);

  async function run(bankCode, opening) {
    const log = [
      { text: '싸피은행 수시입출금 상품 확보' },
      { text: '계좌 생성' },
      {
        text: opening ? `초기 잔액 ${won(opening)} 입금` : '초기 입금 없음',
        state: opening ? undefined : 'done',
      },
    ];
    const paint = (extra = []) =>
      results.replaceChildren(el('div', { class: 'card' }, [steps(log), ...extra]));

    button.disabled = true;
    button.textContent = '진행 중';
    paint();

    // 1) 상품 — 있으면 재사용한다. 팀 전체가 하나만 있으면 된다.
    const list = await api('DEMAND_DEPOSIT_02', {}, { userKey: null, userKeyRef: null });
    if (!list.ok) {
      log[0].state = 'fail';
      button.disabled = false;
      button.textContent = '계좌 만들기';
      paint([errorBox(list), rawPanel(list)]);
      return;
    }
    let product = recList(list).find((row) => row.bankCode === bankCode);

    if (!product) {
      const made = await api(
        'DEMAND_DEPOSIT_01',
        {
          bankCode,
          accountName: '잔고예보 개발용 수시입출금',
          accountDescription: '개발·시연용. 실제 은행망과 무관하다.',
        },
        { userKey: null, userKeyRef: null },
      );
      if (!made.ok) {
        log[0].state = 'fail';
        button.disabled = false;
        button.textContent = '계좌 만들기';
        paint([errorBox(made), rawPanel(made)]);
        return;
      }
      product = recOne(made);
      log[0].text = '싸피은행 상품 신규 등록';
    } else {
      log[0].text = `상품 재사용 — ${product.accountName}`;
    }
    log[0].state = 'done';
    paint();

    // 2) 계좌 — 헤더의 userKey 소유가 된다.
    const created = await api('DEMAND_DEPOSIT_03', {
      accountTypeUniqueNo: product.accountTypeUniqueNo,
    });
    if (!created.ok) {
      log[1].state = 'fail';
      button.disabled = false;
      button.textContent = '계좌 만들기';
      paint([errorBox(created), rawPanel(created)]);
      return;
    }
    const accountNo = recOne(created)?.accountNo;
    log[1].text = `계좌 생성 — ${accountNo}`;
    log[1].state = 'done';
    setAccount(accountNo, '싸피은행');
    paint();

    // 3) 초기 잔액 — 날짜를 정할 수 없어 전부 오늘 거래로 남는다.
    let last = created;
    if (opening) {
      const dep = await api('DEMAND_DEPOSIT_09', {
        accountNo,
        transactionBalance: String(opening),
        transactionSummary: '개발용 초기 잔액',
      });
      log[2].state = dep.ok ? 'done' : 'fail';
      last = dep;
      if (!dep.ok) {
        button.disabled = false;
        button.textContent = '계좌 만들기';
        paint([errorBox(dep), rawPanel(dep)]);
        return;
      }
    }

    button.disabled = false;
    button.textContent = '계좌 만들기';
    paint([
      el('div', { class: 'ok-banner', text: '계좌를 만들었다. 선택 계좌로 잡아 뒀다.' }),
      el('div', { class: 'big num mono', text: accountNo }),
      el('div', {
        class: 'big-sub',
        text: `싸피은행 ${bankCode} · ${state.userLabel ?? '현재 사용자'} 소유`,
      }),
      el('div', {
        class: 'notice',
        text: '화면에서 연결할 때 은행은 "싸피은행", 계좌번호는 위 값을 넣는다. 우리 백엔드는 사용자 이메일로 금융망 userKey 를 찾으므로, 같은 이메일로 앱에 가입해야 연결된다.',
      }),
      el('div', { class: 'btn-row' }, [
        el('button', {
          class: 'btn ghost',
          type: 'button',
          text: '거래내역 보기',
          onclick: () => go('DEMAND_DEPOSIT_12', { accountNo }, true),
        }),
        el('button', {
          class: 'btn ghost',
          type: 'button',
          text: '입금하기',
          onclick: () => go('DEMAND_DEPOSIT_09', { accountNo }),
        }),
      ]),
      rawPanel(last),
    ]);
  }

  form.addEventListener('submit', (event) => {
    event.preventDefault();
    const bankCode = form.elements.bankCode.value.trim();
    const opening = Number(form.elements.openingBalance.value.replaceAll(',', '') || 0);
    if (!bankCode) return;
    form.append(
      confirmBox(
        {
          fields: [
            { name: 'bankCode', label: '은행코드' },
            { name: 'openingBalance', label: '초기 잔액', type: 'amount' },
          ],
        },
        { bankCode, ...(opening ? { openingBalance: String(opening) } : {}) },
        () => run(bankCode, opening),
      ),
    );
  });

  slot.append(el('div', { class: 'card' }, [form]), results);
}

/** 1원 인증 — 송금하고 통장 적요에서 코드를 읽어 검증까지. scripts/auth-code.mjs 를 대신한다. */
function flowAuth() {
  const slot = $('slot');
  slot.replaceChildren();

  if (!state.userKey && !state.userKeyRef) {
    slot.append(needUserKeyNotice());
    return;
  }

  const results = el('div', {});
  const form = el('form', { class: 'fields' }, [
    buildField({
      name: 'accountNo',
      label: '계좌번호',
      type: 'account',
      required: true,
      maxLength: 16,
      placeholder: '0000000000000000',
    }),
    buildField({
      name: 'authText',
      label: '기업명',
      type: 'text',
      required: true,
      default: '잔고예보',
      maxLength: 20,
      hint: '적요에 이 문구와 네 자리 코드가 함께 남는다.',
    }),
  ]);
  const button = el('button', {
    class: 'btn danger',
    type: 'submit',
    text: '1원 송금하고 코드 읽기',
  });
  form.append(button);

  async function verify(accountNo, authText, code) {
    const checked = await api('ACCOUNT_AUTH_02', { accountNo, authText, authCode: code });
    const status = recOne(checked)?.status;
    results.append(
      el('div', { class: 'card' }, [
        el('div', { class: 'card-title', text: '검증' }),
        checked.ok
          ? el('div', {
              class: status === 'SUCCESS' ? 'ok-banner' : 'err',
              text:
                status === 'SUCCESS'
                  ? '검증 성공 — SUCCESS'
                  : `검증 실패 — ${status ?? '알 수 없음'}`,
            })
          : errorBox(checked),
        rawPanel(checked),
      ]),
    );
  }

  form.addEventListener('submit', async (event) => {
    event.preventDefault();
    const accountNo = form.elements.accountNo.value.trim();
    const authText = form.elements.authText.value.trim();
    if (!accountNo || !authText) return;

    const log = [{ text: '1원 송금' }, { text: '통장 적요에서 코드 읽기' }];
    const paint = (extra = []) =>
      results.replaceChildren(el('div', { class: 'card' }, [steps(log), ...extra]));

    button.disabled = true;
    button.textContent = '진행 중';
    paint();

    const sent = await api('ACCOUNT_AUTH_01', { accountNo, authText });
    if (!sent.ok) {
      log[0].state = 'fail';
      button.disabled = false;
      button.textContent = '1원 송금하고 코드 읽기';
      paint([errorBox(sent), rawPanel(sent)]);
      return;
    }
    log[0].state = 'done';
    setAccount(accountNo, state.accountLabel);
    paint();

    // 오늘 거래를 최신순으로 읽어 적요에서 네 자리를 뽑는다. 실사용에서는 사장님이 통장에서 읽는 값이다.
    const today = kstToday();
    const history = await api('DEMAND_DEPOSIT_12', {
      accountNo,
      startDate: today,
      endDate: today,
      transactionType: 'A',
      orderByType: 'DESC',
    });
    if (!history.ok) {
      log[1].state = 'fail';
      button.disabled = false;
      button.textContent = '1원 송금하고 코드 읽기';
      paint([errorBox(history), rawPanel(history)]);
      return;
    }

    let code = null;
    for (const tx of history.response?.REC?.list ?? []) {
      const summary = tx.transactionSummary ?? '';
      if (!summary.includes(authText)) continue;
      const found = summary.match(/(\d{4})\s*$/) ?? summary.match(/(\d{4})/);
      if (found) {
        code = found[1];
        break;
      }
    }

    button.disabled = false;
    button.textContent = '1원 송금하고 코드 읽기';

    if (!code) {
      log[1].state = 'fail';
      paint([
        el('div', {
          class: 'notice',
          text: `오늘 거래에서 "${authText}" 가 들어간 적요를 찾지 못했다. 거래내역(DD_12)을 직접 열어 확인한다.`,
        }),
        rawPanel(history),
      ]);
      return;
    }

    log[1].text = '적요에서 코드를 읽었다';
    log[1].state = 'done';
    paint([
      el('div', { class: 'big num', text: code }),
      el('div', { class: 'big-sub', text: '인증코드' }),
      el('button', {
        class: 'btn',
        type: 'button',
        text: '이 코드로 검증',
        onclick: () => verify(accountNo, authText, code),
      }),
      rawPanel(history),
    ]);
  });

  slot.append(el('div', { class: 'card' }, [form]), results);
}

/** 신용등급은 A 가 가장 높다. 상품의 최소 등급보다 같거나 높아야 가입된다. */
/**
 * 이 상품을 이 기간만큼 쓰면 원금의 몇 %가 이자인가.
 *
 * <p>금융망 금리는 <b>연이율</b>이고 가입 기간만큼 일할 계산된다 —
 * 이자 = 원금 × (금리/100) × (기간/365), 반올림(LOAN_09 시트).
 * 30일에 5% 면 0.41% 라서, 연이율인 줄 모르면 이자를 12배로 읽는다.
 */
const loanInterestPct = (product) =>
  ((Number(product.interestRate) * Number(product.loanPeriod)) / 365).toFixed(2);

const GRADES = ['A', 'B', 'C', 'D', 'E'];
const gradeMeets = (mine, required) => {
  const a = GRADES.indexOf(
    String(mine ?? '')
      .trim()
      .charAt(0),
  );
  const b = GRADES.indexOf(
    String(required ?? '')
      .trim()
      .charAt(0),
  );
  return a >= 0 && b >= 0 && a <= b;
};

/**
 * 대출 받기 — 내 등급 → 상품 → 심사 → 가입. 기술스택 4.1 시드 7단계를 그대로 따른다.
 *
 * <p>스크립트로 하면 상품 고유번호를 손으로 옮겨 붙이는 일이 세 번 생긴다. 여기서는
 * 등급으로 걸러 낸 상품을 눌러 심사까지 가고, 승인이면 그 자리에서 가입한다.
 *
 * <p>가입까지 하면 <b>금융망이 스스로 공백을 만든다</b> — 다음날부터 매일 08:30 에
 * (대출금+이자)/대출기간 이 출금 계좌에서 빠지고, 잔액이 모자라면 LOAN_09 의
 * failureReason 에 "자동이체 실패 잔액 부족" 이 남는다.
 */
function flowLoan() {
  const slot = $('slot');
  slot.replaceChildren();

  if (!state.userKey && !state.userKeyRef) {
    slot.append(needUserKeyNotice());
    return;
  }

  const results = el('div', {});
  const button = el('button', {
    class: 'btn',
    type: 'button',
    text: '내 등급과 가입 가능 상품 보기',
  });

  /** 4단계: 가입. 대출금과 출금 계좌를 받는다. */
  function joinStep(product) {
    const form = el('form', { class: 'fields' }, [
      buildField(
        {
          name: 'loanBalance',
          label: '대출금',
          type: 'amount',
          required: true,
          hint: `${won(product.minLoanBalance)} ~ ${won(product.maxLoanBalance)}`,
        },
        {},
      ),
      buildField(
        {
          name: 'withdrawalAccountNo',
          label: '출금 계좌',
          type: 'account',
          required: true,
          maxLength: 16,
          hint: '대출금이 여기로 들어오고, 다음날부터 매일 08:30 에 상환도 여기서 빠진다.',
        },
        {},
      ),
    ]);
    const join = el('button', { class: 'btn danger', type: 'submit', text: '대출 가입' });
    form.append(join);

    const card = el('div', { class: 'card' }, [
      el('div', { class: 'card-title', text: `가입 — ${product.accountName}` }),
      el('div', {
        class: 'notice',
        text:
          `금리 ${product.interestRate}% 는 연이율이다 — 이 상품은 ${num(product.loanPeriod)}일이므로 실제 이자는 ` +
          `원금 × ${product.interestRate} × ${num(product.loanPeriod)}/365 ≈ 원금의 ${loanInterestPct(product)}% 다. ` +
          `매일 상환액은 (대출금+이자)/${num(product.loanPeriod)} 로 고정된다.`,
      }),
      form,
    ]);
    results.append(card);

    async function run(payload) {
      join.disabled = true;
      join.textContent = '가입 중';
      const joined = await api('LOAN_07', {
        accountTypeUniqueNo: product.accountTypeUniqueNo,
        ...payload,
      });
      join.disabled = false;
      join.textContent = '대출 가입';

      if (!joined.ok) {
        results.append(el('div', { class: 'card' }, [errorBox(joined), rawPanel(joined)]));
        return;
      }
      const rec = recOne(joined);
      results.append(
        el('div', { class: 'card' }, [
          el('div', {
            class: 'ok-banner',
            text: '대출에 가입했다. 내일부터 매일 08:30 에 상환이 시작된다.',
          }),
          el('div', { class: 'big num mono', text: rec?.accountNo ?? '—' }),
          el('div', { class: 'big-sub', text: `${rec?.accountName ?? ''} · ${rec?.status ?? ''}` }),
          renderRecord(joined),
          el('div', { class: 'btn-row' }, [
            el('button', {
              class: 'btn ghost',
              type: 'button',
              text: '상환 내역 보기',
              onclick: () => go('LOAN_09', { accountNo: rec?.accountNo }, true),
            }),
          ]),
          rawPanel(joined),
        ]),
      );
    }

    form.addEventListener('submit', (event) => {
      event.preventDefault();
      let payload;
      try {
        payload = readForm(form, [
          { name: 'loanBalance', label: '대출금', type: 'amount', required: true },
          { name: 'withdrawalAccountNo', label: '출금 계좌', type: 'account', required: true },
        ]);
      } catch (err) {
        results.append(
          el('div', { class: 'card' }, [
            el('div', { class: 'err' }, [el('div', { class: 'err-msg', text: err.message })]),
          ]),
        );
        return;
      }
      form.append(
        confirmBox(
          {
            fields: [
              { name: 'loanBalance', label: '대출금', type: 'amount' },
              { name: 'withdrawalAccountNo', label: '출금 계좌' },
            ],
          },
          payload,
          () => run(payload),
        ),
      );
    });
  }

  /** 3단계: 심사. 결과가 즉시 온다. */
  async function applyStep(product) {
    const applied = await api('LOAN_05', { accountTypeUniqueNo: product.accountTypeUniqueNo });
    if (!applied.ok) {
      results.append(el('div', { class: 'card' }, [errorBox(applied), rawPanel(applied)]));
      return;
    }
    const rec = recOne(applied);
    const approved = String(rec?.status ?? '').includes('승인');
    results.append(
      el('div', { class: 'card' }, [
        el('div', { class: 'card-title', text: `심사 — ${product.accountName}` }),
        el('div', {
          class: approved ? 'ok-banner' : 'err',
          text: approved
            ? '심사 승인 — 가입할 수 있다.'
            : `심사 ${rec?.status ?? '거절'} — 신용등급이 이 상품의 기준에 미달한다.`,
        }),
        renderRecord(applied),
        rawPanel(applied),
      ]),
    );
    if (approved) joinStep({ ...product, ...rec });
  }

  /** 1·2단계: 내 등급과 상품 목록. */
  async function start() {
    button.disabled = true;
    button.textContent = '조회 중';
    const [credit, products] = await Promise.all([
      api('LOAN_04'),
      api('LOAN_03', {}, { userKey: null, userKeyRef: null }),
    ]);
    button.disabled = false;
    button.textContent = '내 등급과 가입 가능 상품 보기';

    if (!credit.ok) {
      results.replaceChildren(el('div', { class: 'card' }, [errorBox(credit), rawPanel(credit)]));
      return;
    }
    const myGrade = recOne(credit)?.ratingName;
    const list = recList(products);

    const rows = list.map((product) => {
      const ok = gradeMeets(myGrade, product.ratingName);
      return el('tr', {}, [
        el('td', {}, [product.accountName ?? '—']),
        el('td', {}, [statusBadge(product.ratingName)]),
        el('td', { class: 'r num', text: `${product.interestRate}%` }),
        el('td', { class: 'r num', text: `${num(product.loanPeriod)}일` }),
        el('td', { class: 'r num', text: won(product.maxLoanBalance) }),
        el('td', {}, [
          ok
            ? el('button', {
                class: 'chip',
                type: 'button',
                text: '심사 신청',
                onclick: () => applyStep(product),
              })
            : el('span', { class: 'type-badge', text: '등급 미달' }),
        ]),
      ]);
    });

    results.replaceChildren(
      el('div', { class: 'card' }, [
        el('div', { class: 'card-title', text: '내 신용등급' }),
        renderCredit(credit),
      ]),
      el('div', { class: 'card' }, [
        el('div', { class: 'card-title', text: `대출 상품 ${list.length}건` }),
        list.length
          ? table(
              [
                { label: '상품명' },
                { label: '최소 등급' },
                { label: '금리', right: true },
                { label: '기간', right: true },
                { label: '최대 금액', right: true },
                { label: '' },
              ],
              rows,
            )
          : EMPTY('등록된 대출 상품이 없다. 대출 상품 등록(LOAN_02)부터 한다.'),
        rawPanel(products),
      ]),
    );
  }

  button.addEventListener('click', start);
  slot.append(
    el('div', { class: 'card' }, [
      el('div', {
        class: 'notice',
        text: '가입까지 하면 금융망이 스스로 공백을 만든다 — 다음날부터 매일 08:30 에 (대출금+이자)/대출기간 이 출금 계좌에서 빠지고, 잔액이 모자라면 상환 내역에 "자동이체 실패 잔액 부족" 이 남는다.',
      }),
      button,
    ]),
    results,
  );
}

const FLOW_VIEWS = {
  'flow:user': flowUser,
  'flow:new-account': flowNewAccount,
  'flow:auth': flowAuth,
  'flow:loan': flowLoan,
};

// ── 이동 ────────────────────────────────────────────────────────────────

function go(id, prefill, autorun) {
  current = id;
  renderNav();

  const flow = FLOWS[id];
  const entry = BY_ID[id];

  $('view-title').textContent = flow ? flow.title : entry.title;
  $('view-note').textContent = (flow ? flow.note : entry.note) ?? '';
  const tag = $('view-id');
  tag.textContent = flow ? '묶음 작업' : id;
  tag.className = `tag${entry?.write ? ' write' : ''}`;

  if (flow) FLOW_VIEWS[id]();
  else renderApiView(entry, prefill, autorun);
}

// ── 기동 ────────────────────────────────────────────────────────────────

async function start() {
  renderCtx();

  try {
    boot = await (await fetch('./api/boot')).json();
  } catch {
    boot = { ready: false, seedRefs: [] };
  }
  renderBoot();

  if (!boot.ready) {
    renderNav();
    $('view-title').textContent = 'apiKey 가 없다';
    $('view-note').textContent = '';
    $('slot').replaceChildren(
      el('div', { class: 'card' }, [
        el('div', { class: 'err' }, [
          el('div', { class: 'err-code', text: 'NO_API_KEY' }),
          el('div', { class: 'err-msg', text: 'FINOPENAPI_API_KEY 를 읽지 못했다.' }),
          el('div', {
            class: 'err-hint',
            text: boot.envFound
              ? 'backend/.env 는 찾았지만 FINOPENAPI_API_KEY 가 비어 있거나 자리표시자다. 서버 ~/app/secrets/finopenapi.env 에서 받는다.'
              : 'backend/.env 를 찾지 못했다. 저장소 밖에서 띄웠다면 JANGO_REPO 로 저장소 경로를 지정한다.',
          }),
        ]),
      ]),
    );
    return;
  }

  go(current);
}

start();
