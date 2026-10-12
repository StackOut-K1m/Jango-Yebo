// 금융망 인터페이스 목록 — 서버와 브라우저가 같은 파일을 읽는다.
//
// 프록시(server.mjs)는 이 목록에 있는 것만 호출한다. 경로를 화면에서 받지 않는 이유:
//   1) MANAGER_02(reIssuedApiKey)는 부르는 순간 팀 apiKey 가 무효가 되고 재발급이 막힌다.
//   2) 없는 경로는 404 에 응답 형태까지 달라서(responseCode 가 아니라 code/payload)
//      오타 한 번이 "서버가 죽었나"로 번진다.
//
// fields 는 브라우저가 폼을 만드는 데 쓰고, kind·domain·apiName·userKey 는 서버가
// 요청을 조립하는 데 쓴다. 명세가 개정되면 이 파일 한 곳만 고친다.
//
// 담은 범위는 기술스택 문서 3.7 의 채택 판정을 따른다 —
// 수시입출금·예약이체·가상계좌·대출·마이너스통장. 미채택 계열(외화·가상카드·카드한도·
// 금은·비대면개설)과 파일 업로드가 필요한 일괄 API(BULK_*)는 넣지 않았다. README 참조.
//
// 출처: SSAFY_교육용_금융망_API_Inferface_예제.xlsx (173시트)

// ── 필드 helper ─────────────────────────────────────────────────────────
//
// ⚠ 금융망은 금액·기간·금리까지 전부 문자열로 받는다. 워크북 표에는 Long·int·double 로
//   적혀 있지만 요청 예제는 모두 따옴표가 붙어 있고, 숫자로 보내면 Q1001 이다(실측).
//   예외는 Boolean 뿐이다(autoTransfer·unreadOnly).

const amount = (name, label, opts = {}) => ({
  name,
  label,
  type: 'amount',
  required: opts.required !== false,
  hint: opts.hint,
});

const account = (name, label, opts = {}) => ({
  name,
  label,
  type: 'account',
  required: opts.required !== false,
  maxLength: 16,
  placeholder: opts.placeholder ?? '0000000000000000',
  hint: opts.hint,
});

const text = (name, label, opts = {}) => ({
  name,
  label,
  type: 'text',
  required: opts.required === true,
  maxLength: opts.maxLength,
  placeholder: opts.placeholder,
  default: opts.default,
  hint: opts.hint,
});

const date = (name, label, opts = {}) => ({
  name,
  label,
  type: 'date',
  required: opts.required !== false,
  hint: opts.hint,
});

const select = (name, label, values, opts = {}) => ({
  name,
  label,
  type: 'select',
  required: opts.required !== false,
  default: opts.default ?? values[0].value,
  options: values,
  hint: opts.hint,
});

/** 미입력을 허용하는 선택. 빈 값이면 키 자체를 빼고 보낸다. */
const optionalSelect = (name, label, values, hint) =>
  select(name, label, [{ value: '', label: '(전체)' }, ...values], {
    required: false,
    default: '',
    hint,
  });

const bool = (name, label, opts = {}) => ({
  name,
  label,
  type: 'boolean',
  required: false,
  default: opts.default ?? '',
  hint: opts.hint,
});

const summary = (name, label) =>
  text(name, label, {
    maxLength: 255,
    placeholder: '통장 적요에 남는 문구',
    hint: '비우면 금융망이 기본 적요를 넣는다.',
  });

/** 표 열. money·date·datetime·rate·mono·badge 는 값 표기 방식이다. */
const col = (key, label, opts = {}) => ({ key, label, ...opts });

// ── 인터페이스 ──────────────────────────────────────────────────────────

export const CATALOG = [
  // ══ 사용자 ═════════════════════════════════════════════════════════
  {
    id: 'MEMBER_02',
    title: '사용자 조회',
    note: '이메일 하나로 userKey 가 정해진다. 백엔드도 사용자 이메일로 같은 키를 찾는다.',
    kind: 'member',
    path: '/search',
    userKey: 'none',
    write: false,
    render: 'record',
    fields: [text('userId', '이메일', { required: true, placeholder: 'dev1@jango.test' })],
  },
  {
    id: 'MEMBER_01',
    title: '사용자 생성',
    note: '이메일은 금융망 전역에서 유니크하다. 다른 팀이 쓴 주소는 만들 수 없다(E4002).',
    kind: 'member',
    path: '/',
    userKey: 'none',
    write: true,
    render: 'record',
    fields: [text('userId', '이메일', { required: true, placeholder: 'dev1@jango.test' })],
  },

  // ══ 계좌 조회 ══════════════════════════════════════════════════════
  {
    id: 'DEMAND_DEPOSIT_04',
    title: '계좌 목록',
    note: '이 userKey 가 가진 수시입출금 계좌 전부. 행을 누르면 선택 계좌로 잡힌다.',
    kind: 'edu',
    domain: 'demandDeposit',
    apiName: 'inquireDemandDepositAccountList',
    userKey: 'required',
    write: false,
    render: 'accounts',
    fields: [],
  },
  {
    id: 'DEMAND_DEPOSIT_05',
    title: '계좌 조회',
    kind: 'edu',
    domain: 'demandDeposit',
    apiName: 'inquireDemandDepositAccount',
    userKey: 'required',
    write: false,
    render: 'record',
    fields: [account('accountNo', '계좌번호')],
  },
  {
    id: 'DEMAND_DEPOSIT_07',
    title: '잔액 조회',
    note: '백엔드가 ACCOUNT.balance 를 채우는 인터페이스다. 예측의 시작 잔액이 여기서 온다.',
    kind: 'edu',
    domain: 'demandDeposit',
    apiName: 'inquireDemandDepositAccountBalance',
    userKey: 'required',
    write: false,
    render: 'balance',
    fields: [account('accountNo', '계좌번호')],
  },
  {
    id: 'DEMAND_DEPOSIT_06',
    title: '예금주 조회',
    note: '남의 계좌도 예금주명은 볼 수 있다. 이체 전 확인용이다.',
    kind: 'edu',
    domain: 'demandDeposit',
    apiName: 'inquireDemandDepositAccountHolderName',
    userKey: 'required',
    write: false,
    render: 'record',
    fields: [account('accountNo', '계좌번호')],
  },

  // ══ 거래내역 ═══════════════════════════════════════════════════════
  {
    id: 'DEMAND_DEPOSIT_12',
    title: '거래내역',
    note: '정렬은 거래고유번호 기준이다. 같은 날 거래의 순서는 시각이 아니라 번호가 정한다.',
    kind: 'edu',
    domain: 'demandDeposit',
    apiName: 'inquireTransactionHistoryList',
    userKey: 'required',
    write: false,
    render: 'txlist',
    fields: [
      account('accountNo', '계좌번호'),
      date('startDate', '조회 시작일'),
      date('endDate', '조회 종료일'),
      select(
        'transactionType',
        '거래 구분',
        [
          { value: 'A', label: '전체' },
          { value: 'M', label: '입금만' },
          { value: 'D', label: '출금만' },
        ],
        { default: 'A' },
      ),
      select(
        'orderByType',
        '정렬',
        [
          { value: 'DESC', label: '최근 거래부터' },
          { value: 'ASC', label: '이전 거래부터' },
        ],
        { default: 'DESC' },
      ),
    ],
  },
  {
    id: 'DEMAND_DEPOSIT_13',
    title: '거래 단건',
    kind: 'edu',
    domain: 'demandDeposit',
    apiName: 'inquireTransactionHistory',
    userKey: 'required',
    write: false,
    render: 'record',
    fields: [
      account('accountNo', '계좌번호'),
      text('transactionUniqueNo', '거래고유번호', { required: true, placeholder: '59' }),
    ],
  },
  {
    id: 'TRANSACTION_MEMO_01',
    title: '거래 메모',
    kind: 'edu',
    domain: '',
    apiName: 'transactionMemo',
    userKey: 'required',
    write: true,
    render: 'record',
    fields: [
      account('accountNo', '계좌번호'),
      text('transactionUniqueNo', '거래고유번호', { required: true }),
      text('transactionMemo', '메모', { maxLength: 255 }),
    ],
  },

  // ══ 입금·출금·이체 ═════════════════════════════════════════════════
  {
    id: 'DEMAND_DEPOSIT_09',
    title: '입금',
    note: '날짜를 정할 수 없다 — 무조건 오늘 거래로 남는다. 과거 이력은 CSV 업로드가 담당한다.',
    kind: 'edu',
    domain: 'demandDeposit',
    apiName: 'updateDemandDepositAccountDeposit',
    userKey: 'required',
    write: true,
    render: 'record',
    fields: [
      account('accountNo', '입금 계좌'),
      amount('transactionBalance', '입금액'),
      summary('transactionSummary', '적요'),
    ],
  },
  {
    id: 'DEMAND_DEPOSIT_08',
    title: '출금',
    note: '상대 계좌가 필요 없다. 이체(DD_10)로 우회하지 않아도 된다.',
    kind: 'edu',
    domain: 'demandDeposit',
    apiName: 'updateDemandDepositAccountWithdrawal',
    userKey: 'required',
    write: true,
    render: 'record',
    fields: [
      account('accountNo', '출금 계좌'),
      amount('transactionBalance', '출금액'),
      summary('transactionSummary', '적요'),
    ],
  },
  {
    id: 'DEMAND_DEPOSIT_10',
    title: '이체',
    note: '출금 계좌는 원화만 가능하다. 기본 한도는 1회 1억·1일 5억이다.',
    kind: 'edu',
    domain: 'demandDeposit',
    apiName: 'updateDemandDepositAccountTransfer',
    userKey: 'required',
    write: true,
    render: 'transfer',
    fields: [
      account('withdrawalAccountNo', '출금 계좌', { hint: 'userKey 소유여야 한다.' }),
      account('depositAccountNo', '입금 계좌'),
      amount('transactionBalance', '이체액'),
      summary('withdrawalTransactionSummary', '적요 (출금계좌)'),
      summary('depositTransactionSummary', '적요 (입금계좌)'),
    ],
  },

  // ══ 1원 인증 ═══════════════════════════════════════════════════════
  {
    id: 'ACCOUNT_AUTH_01',
    title: '1원 송금',
    note: '적요 끝 네 자리가 인증코드다. 실사용에서는 사장님이 통장에서 읽는 값이다.',
    kind: 'edu',
    domain: 'accountAuth',
    apiName: 'openAccountAuth',
    userKey: 'required',
    write: true,
    render: 'record',
    fields: [
      account('accountNo', '계좌번호'),
      text('authText', '기업명', { required: true, default: '잔고예보', maxLength: 20 }),
    ],
  },
  {
    id: 'ACCOUNT_AUTH_02',
    title: '1원 검증',
    note: '실패 코드가 넷이다 — A1090·A1088 은 재입력, A1087·A1086 은 재발송이다.',
    kind: 'edu',
    domain: 'accountAuth',
    apiName: 'checkAuthCode',
    userKey: 'required',
    write: true,
    render: 'record',
    fields: [
      account('accountNo', '계좌번호'),
      text('authText', '기업명', { required: true, default: '잔고예보', maxLength: 20 }),
      text('authCode', '인증코드', { required: true, maxLength: 4, placeholder: '0000' }),
    ],
  },

  // ══ 예약이체 — P0-03~06 · P1-04 (채택) ════════════════════════════
  //
  // 우리 PLANNED_TRANSACTION 과 개념이 겹치지만 역할이 다르다. 예약이체는 금융망에서
  // 실제로 돈이 움직이고, PLANNED_TRANSACTION 은 우리가 예측하는 것이다.
  {
    id: 'TRANSFER_RESERVATION_03',
    title: '예약이체 목록',
    kind: 'edu',
    domain: 'bank',
    apiName: 'inquireTransferReservation',
    rowTo: { id: 'TRANSFER_RESERVATION_02', field: 'reservationId' },
    userKey: 'required',
    write: false,
    render: 'table',
    listKey: 'reservations',
    columns: [
      col('reservationId', '예약 ID', { mono: true }),
      col('reservationName', '이름'),
      col('depositAccountNo', '입금 계좌', { mono: true }),
      col('transactionBalance', '금액', { money: true, right: true }),
      col('transferCycle', '주기'),
      col('nextTransferDate', '다음 이체일', { date: true }),
      col('status', '상태', { badge: true }),
    ],
    fields: [
      account('withdrawalAccountNo', '출금 계좌', { required: false, hint: '비우면 전체 조회.' }),
      optionalSelect('status', '상태', [
        { value: 'ACTIVE', label: 'ACTIVE 진행중' },
        { value: 'PAUSED', label: 'PAUSED 일시정지' },
        { value: 'CANCELED', label: 'CANCELED 취소' },
        { value: 'COMPLETED', label: 'COMPLETED 완료' },
        { value: 'FAILED', label: 'FAILED 실패' },
      ]),
      text('keyword', '예약이체명 검색'),
    ],
  },
  {
    id: 'TRANSFER_RESERVATION_04',
    title: '예약이체 상세',
    kind: 'edu',
    domain: 'bank',
    apiName: 'inquireTransferReservationDetail',
    userKey: 'required',
    write: false,
    render: 'record',
    fields: [
      text('reservationId', '예약이체 ID', { required: true, placeholder: 'RSV4F8A1C2D3E5B67890' }),
    ],
  },
  {
    id: 'TRANSFER_RESERVATION_02',
    title: '예약이체 실행 내역',
    note: '실패해도 당일 재시도가 없다. 다음 날 09:00 에 다시 실행한다.',
    kind: 'edu',
    domain: 'bank',
    apiName: 'inquireTransferReservationHistory',
    userKey: 'required',
    write: false,
    render: 'table',
    listKey: 'history',
    columns: [
      col('transferDate', '이체일', { date: true }),
      col('transferTime', '시각', { time: true }),
      col('transactionBalance', '금액', { money: true, right: true }),
      col('resultCode', '결과', { badge: true, mono: true }),
      col('resultMessage', '메시지'),
    ],
    fields: [
      text('reservationId', '예약이체 ID', { required: true }),
      date('startDate', '조회 시작일', { required: false }),
      date('endDate', '조회 종료일', { required: false }),
    ],
  },
  {
    id: 'TRANSFER_RESERVATION_01',
    title: '예약이체 등록',
    note: 'WEEKLY 에는 요일 지정 필드가 없다 — startDate 의 요일로 반복된다. 화요일 정산을 만들려면 startDate 를 화요일로 잡는다.',
    kind: 'edu',
    domain: 'bank',
    apiName: 'transferReservation',
    userKey: 'required',
    write: true,
    render: 'record',
    fields: [
      account('withdrawalAccountNo', '출금 계좌'),
      text('depositBankCode', '입금 은행코드', { required: true, maxLength: 3, default: '999' }),
      account('depositAccountNo', '입금 계좌'),
      amount('transactionBalance', '이체 금액'),
      text('reservationName', '예약이체 이름', { required: true, placeholder: '배민 정산' }),
      select(
        'reservationType',
        '예약 유형',
        [
          { value: 'REPEAT', label: 'REPEAT 반복' },
          { value: 'ONCE', label: 'ONCE 1회' },
        ],
        { default: 'REPEAT' },
      ),
      date('startDate', '예약 시작일'),
      date('endDate', '반복 종료일', { required: false, hint: '비우면 무기한.' }),
      optionalSelect(
        'transferCycle',
        '반복 주기',
        [
          { value: 'DAILY', label: 'DAILY 매일' },
          { value: 'WEEKLY', label: 'WEEKLY 매주 — 시작일의 요일' },
          { value: 'MONTHLY', label: 'MONTHLY 매월' },
        ],
        'ONCE 면 비운다.',
      ),
      text('transferDay', '월간 이체일', {
        maxLength: 2,
        placeholder: '25',
        hint: 'MONTHLY 일 때만. 01~31.',
      }),
      summary('withdrawalTransactionSummary', '적요 (출금계좌)'),
      text('depositTransactionSummary', '적요 (입금계좌)', {
        maxLength: 255,
        placeholder: '우아한형제들',
        hint: '사장님 통장에 이 적요로 찍힌다 — 분류 데모의 입력이 여기서 만들어진다.',
      }),
    ],
  },
  {
    id: 'TRANSFER_RESERVATION_05',
    title: '예약이체 수정',
    kind: 'edu',
    domain: 'bank',
    apiName: 'updateTransferReservation',
    userKey: 'required',
    write: true,
    render: 'record',
    fields: [
      text('reservationId', '예약이체 ID', { required: true }),
      text('reservationName', '예약이체명'),
      amount('transactionBalance', '이체 금액', { required: false }),
      optionalSelect('transferCycle', '반복 주기', [
        { value: 'DAILY', label: 'DAILY 매일' },
        { value: 'WEEKLY', label: 'WEEKLY 매주' },
        { value: 'MONTHLY', label: 'MONTHLY 매월' },
      ]),
      text('transferDay', '이체일', { maxLength: 2 }),
      date('endDate', '종료일', { required: false }),
    ],
  },
  {
    id: 'TRANSFER_RESERVATION_07',
    title: '예약이체 정지·재개',
    kind: 'edu',
    domain: 'bank',
    apiName: 'pauseTransferReservation',
    userKey: 'required',
    write: true,
    render: 'record',
    fields: [
      text('reservationId', '예약이체 ID', { required: true }),
      select(
        'action',
        '동작',
        [
          { value: 'PAUSED', label: 'PAUSED 일시정지' },
          { value: 'ACTIVE', label: 'ACTIVE 재개' },
        ],
        { default: 'PAUSED' },
      ),
    ],
  },
  {
    id: 'TRANSFER_RESERVATION_06',
    title: '예약이체 취소',
    note: '되돌릴 수 없다. 잠시 멈추려면 정지·재개(TR_07)를 쓴다.',
    kind: 'edu',
    domain: 'bank',
    apiName: 'cancelTransferReservation',
    userKey: 'required',
    write: true,
    render: 'record',
    fields: [text('reservationId', '예약이체 ID', { required: true })],
  },

  // ══ 대출 — P1-13 현황 (채택) ══════════════════════════════════════
  //
  // ⚠ 상환이 매일이다. 개설일 다음날부터 매일 오전 08:30 에 (대출금+이자)/대출기간 이
  //   출금 계좌에서 빠진다. 목업은 월 1회 큰 금액으로 그렸으니 예보 곡선 모양이 다르다.
  {
    id: 'LOAN_08',
    title: '대출 가입 목록',
    note: '백엔드 LOAN_SYNC 가 이걸로 LOAN 테이블을 채운다.',
    kind: 'edu',
    domain: 'loan',
    apiName: 'inquireLoanAccountList',
    rowTo: { id: 'LOAN_09', field: 'accountNo' },
    userKey: 'required',
    write: false,
    render: 'table',
    columns: [
      col('accountNo', '계좌번호', { mono: true }),
      col('accountName', '상품명'),
      col('status', '상태', { badge: true }),
      col('loanBalance', '대출금', { money: true, right: true }),
      col('interestRate', '금리', { rate: true, right: true }),
      col('loanPeriod', '기간', { days: true, right: true }),
      col('loanDate', '대출일', { date: true }),
      col('maturityDate', '만기일', { date: true }),
      col('withdrawalAccountNo', '출금 계좌', { mono: true }),
    ],
    fields: [],
  },
  {
    id: 'LOAN_09',
    title: '대출 상환 내역',
    note: 'loanBalance 는 원금+이자 총액이고 회차 상환액은 그것을 대출기간으로 나눈 값이다 — 매일 같은 금액이다. failureReason 에 "자동이체 실패 잔액 부족" 이 그대로 온다 — 이 제품의 핵심 장면을 금융망이 재현한다.',
    kind: 'edu',
    domain: 'loan',
    apiName: 'inquireRepaymentRecords',
    userKey: 'required',
    write: false,
    render: 'table',
    listKey: 'repaymentRecords',
    columns: [
      col('installmentNumber', '회차', { right: true }),
      col('status', '상환 상태', { badge: true }),
      col('paymentBalance', '상환 금액', { money: true, right: true }),
      col('repaymentAttemptDate', '시도일', { date: true }),
      col('repaymentActualDate', '실제 상환일', { date: true }),
      col('failureReason', '실패 이유'),
    ],
    fields: [account('accountNo', '대출 계좌번호', { placeholder: '대출 가입 목록에서 복사' })],
  },
  {
    id: 'LOAN_10',
    title: '대출 일시납 상환',
    note: '남은 대출금 전액을 한 번에 상환한다. 계좌가 CLOSED 로 끝나고 되돌릴 수 없다.',
    kind: 'edu',
    domain: 'loan',
    apiName: 'updateRepaymentLoanBalanceInFull',
    userKey: 'required',
    write: true,
    render: 'record',
    fields: [account('accountNo', '대출 계좌번호')],
  },

  // ══ 신용·대출 상품 — P1-14 추천 (채택) ════════════════════════════
  {
    id: 'LOAN_04',
    title: '내 신용등급',
    note: '⚠ 자산 합계로만 산출된다 — 수시입출금 + 예·적금 잔액이 전부다. 연체 이력도 소득도 반영되지 않으니 화면에서 실제 신용등급처럼 보이게 하면 안 된다.',
    kind: 'edu',
    domain: 'loan',
    apiName: 'inquireMyCreditRating',
    userKey: 'required',
    write: false,
    render: 'credit',
    fields: [],
  },
  {
    id: 'LOAN_01',
    title: '신용등급 기준',
    note: '등급별 자산 구간. A~E 5등급이며 화면의 gradeCriteria 가 이 값이다.',
    kind: 'edu',
    domain: 'loan',
    apiName: 'inquireAssetBasedCreditRatingList',
    userKey: 'none',
    write: false,
    render: 'table',
    columns: [
      col('ratingName', '등급', { badge: true }),
      col('ratingUniqueNo', '기준 고유번호', { mono: true }),
      col('minAssetValue', '최소 자산', { money: true, right: true }),
      col('maxAssetValue', '최대 자산', { money: true, right: true }),
    ],
    fields: [],
  },
  {
    id: 'LOAN_03',
    title: '대출 상품 목록',
    kind: 'edu',
    domain: 'loan',
    apiName: 'inquireLoanProductList',
    rowTo: { id: 'LOAN_05', field: 'accountTypeUniqueNo' },
    userKey: 'none',
    write: false,
    render: 'table',
    columns: [
      col('accountName', '상품명'),
      col('bankName', '은행'),
      col('ratingName', '최소 등급', { badge: true }),
      col('interestRate', '금리', { rate: true, right: true }),
      col('loanPeriod', '기간', { days: true, right: true }),
      col('minLoanBalance', '최소 금액', { money: true, right: true }),
      col('maxLoanBalance', '최대 금액', { money: true, right: true }),
      col('accountTypeUniqueNo', '상품 고유번호', { mono: true }),
    ],
    fields: [],
  },
  {
    id: 'LOAN_02',
    title: '대출 상품 등록',
    note: '기간 2~365일(일 단위) · 금액 1,000원~3억원 · 금리 0.1~20%. 금리는 연이율이고 가입 기간만큼 일할 계산된다 — 이자 = 원금 × (금리/100) × (기간/365), 반올림.',
    kind: 'edu',
    domain: 'loan',
    apiName: 'createLoanProduct',
    userKey: 'none',
    write: true,
    render: 'record',
    fields: [
      text('bankCode', '은행코드', { required: true, maxLength: 3, default: '999' }),
      text('accountName', '상품명', {
        required: true,
        maxLength: 20,
        placeholder: '싸피은행 소상공인 대출',
      }),
      text('accountDescription', '상품설명', { maxLength: 255 }),
      text('ratingUniqueNo', '신용등급 기준 고유번호', {
        required: true,
        hint: '신용등급 기준(LOAN_01)에서 복사한다. 이 등급 이상만 가입할 수 있다.',
      }),
      text('loanPeriod', '대출기간', {
        required: true,
        placeholder: '30',
        hint: '2~365 (일 단위)',
      }),
      amount('minLoanBalance', '최소 대출 금액', { hint: '1,000원 이상' }),
      amount('maxLoanBalance', '최대 대출 금액', { hint: '3억원 이하' }),
      text('interestRate', '기본 금리', {
        required: true,
        placeholder: '5',
        hint: '0.1~20 — 연이율(%)이며 소수점을 쓸 수 있다. 30일 대출에 5 면 실제 이자는 원금의 5 × 30/365 ≈ 0.41% 다.',
      }),
    ],
  },
  {
    id: 'LOAN_05',
    title: '대출심사 신청',
    note: '심사 결과가 즉시 온다. status 는 승인 또는 거절(신용등급 미달)이다.',
    kind: 'edu',
    domain: 'loan',
    apiName: 'createLoanApplication',
    userKey: 'required',
    write: true,
    render: 'record',
    fields: [
      text('accountTypeUniqueNo', '상품 고유번호', {
        required: true,
        hint: '대출 상품 목록(LOAN_03)에서 복사한다.',
      }),
    ],
  },
  {
    id: 'LOAN_06',
    title: '대출심사 목록',
    kind: 'edu',
    domain: 'loan',
    apiName: 'inquireLoanApplicationList',
    rowTo: { id: 'LOAN_07', field: 'accountTypeUniqueNo' },
    userKey: 'required',
    write: false,
    render: 'table',
    columns: [
      col('accountName', '상품명'),
      col('status', '심사 상태', { badge: true }),
      col('ratingName', '요구 등급', { badge: true }),
      col('interestRate', '금리', { rate: true, right: true }),
      col('maxLoanBalance', '최대 금액', { money: true, right: true }),
      col('applicationDate', '신청일', { date: true }),
      col('accountTypeUniqueNo', '상품 고유번호', { mono: true }),
    ],
    fields: [],
  },
  {
    id: 'LOAN_07',
    title: '대출 상품 가입',
    note: '심사 승인된 상품만 가입된다. 대출금은 출금 계좌로 지급되고, 다음날부터 매일 08:30 에 같은 계좌에서 (대출금+이자)/대출기간 만큼 빠진다. 가입 순간 총 상환액이 확정되므로 하루치는 상수다.',
    kind: 'edu',
    domain: 'loan',
    apiName: 'createLoanAccount',
    userKey: 'required',
    write: true,
    render: 'record',
    fields: [
      text('accountTypeUniqueNo', '상품 고유번호', { required: true }),
      amount('loanBalance', '대출금'),
      account('withdrawalAccountNo', '출금 계좌', {
        hint: '수시입출금 계좌. 대출금이 여기로 들어오고 상환도 여기서 빠진다.',
      }),
    ],
  },

  // ══ 마이너스통장 — 16장이 대출과 함께 동기화한다 ═══════════════════
  //
  // 대출과 규칙이 다르다: 기간 12·24·36개월 · 이자 납입일·자동 상환일 매월 01~28일 ·
  // 상태 NORMAL/OVERDUE. 기술스택 4.13 이 "LOAN 하나로 둘을 담지 않는다"고 정리했다.
  {
    id: 'MINUS_ACCOUNT_07',
    title: '마이너스통장 목록',
    kind: 'edu',
    domain: 'loan',
    apiName: 'inquireMinusAccountList',
    rowTo: { id: 'MINUS_ACCOUNT_13', field: 'loanAccountNo' },
    userKey: 'required',
    write: false,
    render: 'table',
    listKey: 'accounts',
    columns: [
      col('loanAccountNo', '계좌번호', { mono: true }),
      col('productName', '상품명'),
      col('status', '상태', { badge: true }),
      col('creditLimit', '한도', { money: true, right: true }),
      col('usedAmount', '사용액', { money: true, right: true }),
      col('availableAmount', '가용액', { money: true, right: true }),
      col('accruedInterest', '누적 이자', { money: true, right: true }),
      col('nextInterestDate', '다음 이자일', { date: true }),
      col('overdueDays', '연체일수', { right: true }),
    ],
    fields: [],
  },
  {
    id: 'MINUS_ACCOUNT_06',
    title: '마이너스통장 상세',
    kind: 'edu',
    domain: 'loan',
    apiName: 'inquireMinusAccount',
    userKey: 'required',
    write: false,
    render: 'record',
    fields: [
      account('loanAccountNo', '마이너스통장 계좌번호', { placeholder: '0000000000000001' }),
    ],
  },
  {
    id: 'MINUS_ACCOUNT_13',
    title: '마이너스통장 거래내역',
    kind: 'edu',
    domain: 'loan',
    apiName: 'inquireMinusAccountHistory',
    userKey: 'required',
    write: false,
    render: 'table',
    listKey: 'history',
    columns: [
      col('transactionAt', '일시', { datetime: true }),
      col('transactionType', '유형', { badge: true, map: 'minusTx' }),
      col('amount', '금액', { money: true, right: true }),
      col('balanceAfter', '거래 후 사용액', { money: true, right: true }),
    ],
    fields: [
      account('loanAccountNo', '마이너스통장 계좌번호'),
      date('startDate', '조회 시작일', { required: false }),
      date('endDate', '조회 종료일', { required: false }),
    ],
  },
  {
    id: 'MINUS_ACCOUNT_09',
    title: '마이너스통장 이자 내역',
    // 워크북에 산출식이 없다. 예제로 검산하면 사용금액 × (금리/100) / 365 반올림이다
    // (100,000 × 0.045 / 365 = 12.33 → dailyInterest 12). 문서 근거가 아니라 추정이다.
    note: '이자는 매일 00:00 에 사용 금액에만 붙는다. 예제로 검산하면 사용금액 × (연이율/100) / 365 를 반올림한 값이다 — 워크북에 산출식이 적혀 있지 않아 추정이다.',
    kind: 'edu',
    domain: 'loan',
    apiName: 'inquireMinusAccountInterest',
    userKey: 'required',
    write: false,
    render: 'table',
    listKey: 'ledger',
    columns: [
      col('accrueDate', '발생일', { date: true }),
      col('usedAmount', '기준 사용액', { money: true, right: true }),
      col('dailyInterest', '일 이자', { money: true, right: true }),
      col('accruedTotal', '누적 이자', { money: true, right: true }),
    ],
    fields: [
      account('loanAccountNo', '마이너스통장 계좌번호'),
      date('startDate', '조회 시작일', { required: false }),
      date('endDate', '조회 종료일', { required: false }),
    ],
  },
  {
    id: 'MINUS_ACCOUNT_10',
    title: '마이너스통장 알림',
    note: '3일 전 납입 예정 · 연체 발생 · 연체 3일 경고를 금융망이 저장한다. 우리 ALERT 와 겹치니 도입 시 dedup_key 규칙을 먼저 정한다.',
    kind: 'edu',
    domain: 'loan',
    apiName: 'inquireMinusAccountAlerts',
    userKey: 'required',
    write: false,
    render: 'table',
    listKey: 'alerts',
    columns: [
      col('createdAt', '생성', { datetime: true }),
      col('alertType', '유형', { badge: true, mono: true }),
      col('title', '제목'),
      col('message', '내용'),
      col('amount', '금액', { money: true, right: true }),
      col('isRead', '읽음', { bool: true }),
    ],
    fields: [account('loanAccountNo', '마이너스통장 계좌번호'), bool('unreadOnly', '미읽음만')],
  },
  {
    id: 'MINUS_ACCOUNT_02',
    title: '마이너스통장 상품 목록',
    kind: 'edu',
    domain: 'loan',
    apiName: 'inquireMinusAccountProductList',
    rowTo: { id: 'MINUS_ACCOUNT_03', field: 'accountTypeUniqueNo' },
    userKey: 'none',
    write: false,
    render: 'table',
    columns: [
      col('accountName', '상품명'),
      col('bankName', '은행'),
      col('accountTypeUniqueNo', '상품 고유번호', { mono: true }),
      col('accountDescription', '설명'),
    ],
    fields: [],
  },
  {
    id: 'MINUS_ACCOUNT_01',
    title: '마이너스통장 상품 등록',
    kind: 'edu',
    domain: 'loan',
    apiName: 'createMinusAccountProduct',
    userKey: 'none',
    write: true,
    render: 'record',
    fields: [
      text('bankCode', '은행코드', { required: true, maxLength: 3, default: '999' }),
      text('accountName', '상품명', { required: true, placeholder: '싸피은행 한도대출' }),
      text('accountDescription', '상품 설명'),
    ],
  },
  {
    id: 'MINUS_ACCOUNT_03',
    title: '마이너스통장 개설',
    note: '이자는 매일 00:00, 납입·연체 판정은 00:10 에 돈다. 이자 납입일·자동 상환일은 01~28 만 가능하다.',
    kind: 'edu',
    domain: 'loan',
    apiName: 'createMinusAccount',
    userKey: 'required',
    write: true,
    render: 'record',
    fields: [
      text('accountTypeUniqueNo', '상품 고유번호', {
        required: true,
        hint: '상품 목록(MINUS_02)에서 복사한다.',
      }),
      amount('creditLimit', '신용 한도'),
      account('linkedAccountNo', '연결 계좌', {
        hint: '수시입출금 계좌. 이자·상환이 여기서 빠진다.',
      }),
      select(
        'loanPeriodMonths',
        '대출 기간',
        [
          { value: '12', label: '12개월' },
          { value: '24', label: '24개월' },
          { value: '36', label: '36개월' },
        ],
        { default: '12' },
      ),
      text('interestRate', '적용 금리', {
        required: true,
        placeholder: '4.5',
        hint: '연이율(%). 대출과 달리 사용한 금액에만 매일 00:00 에 붙는다.',
      }),
      select(
        'repaymentType',
        '상환 방식',
        [
          { value: 'FREE_REPAYMENT', label: 'FREE_REPAYMENT 자유상환' },
          { value: 'AUTO_REPAYMENT', label: 'AUTO_REPAYMENT 자동상환' },
        ],
        { default: 'FREE_REPAYMENT' },
      ),
      text('interestPaymentDay', '이자 납입일', {
        required: true,
        maxLength: 2,
        default: '25',
        hint: '01~28',
      }),
      text('autoRepaymentDay', '자동 상환일', {
        maxLength: 2,
        hint: 'AUTO_REPAYMENT 일 때만. 01~28.',
      }),
    ],
  },
  {
    id: 'MINUS_ACCOUNT_04',
    title: '마이너스통장 출금',
    kind: 'edu',
    domain: 'loan',
    apiName: 'minusAccountWithdraw',
    userKey: 'required',
    write: true,
    render: 'record',
    fields: [account('loanAccountNo', '마이너스통장 계좌번호'), amount('amount', '출금 금액')],
  },
  {
    id: 'MINUS_ACCOUNT_05',
    title: '마이너스통장 원금 상환',
    kind: 'edu',
    domain: 'loan',
    apiName: 'minusAccountRepay',
    userKey: 'required',
    write: true,
    render: 'record',
    fields: [
      account('loanAccountNo', '마이너스통장 계좌번호'),
      account('repayAccountNo', '상환 출금 계좌', {
        required: false,
        hint: '비우면 연결 계좌를 쓴다.',
      }),
      amount('amount', '상환 금액'),
    ],
  },
  {
    id: 'MINUS_ACCOUNT_12',
    title: '마이너스통장 이자 납부',
    kind: 'edu',
    domain: 'loan',
    apiName: 'payMinusAccountInterest',
    userKey: 'required',
    write: true,
    render: 'record',
    fields: [
      account('loanAccountNo', '마이너스통장 계좌번호'),
      account('paymentAccountNo', '납부 계좌', {
        required: false,
        hint: '비우면 연결 계좌를 쓴다.',
      }),
      amount('amount', '납부 금액', { required: false, hint: '비우면 누적 이자 전액.' }),
    ],
  },
  {
    id: 'MINUS_ACCOUNT_14',
    title: '마이너스통장 한도 변경',
    kind: 'edu',
    domain: 'loan',
    apiName: 'updateMinusAccountLimit',
    userKey: 'required',
    write: true,
    render: 'record',
    fields: [
      account('loanAccountNo', '마이너스통장 계좌번호'),
      amount('newCreditLimit', '변경 후 한도', { hint: '현재 사용액 이상이어야 한다.' }),
    ],
  },

  // ══ 가상계좌 — 조건부 채택 (1회성 입금 대사) ═══════════════════════
  //
  // 「예상 입금」을 미리 등록하는 구조라 우리 PLANNED_TRANSACTION 과 맞물린다.
  // nameMatched(입금자명 일치)·settledAt(정산 일시)이 대사(P0-11)의 입력이 된다.
  {
    id: 'VIRTUAL_ACCOUNT_02',
    title: '가상계좌 목록',
    kind: 'edu',
    domain: 'account',
    apiName: 'inquireVirtualAccountList',
    rowTo: { id: 'VIRTUAL_ACCOUNT_04', field: 'virtualAccountId' },
    userKey: 'required',
    write: false,
    render: 'table',
    listKey: 'virtualAccounts',
    columns: [
      col('virtualAccountId', '가상계좌 ID', { mono: true }),
      col('virtualAccountNo', '가상계좌번호', { mono: true }),
      col('depositorName', '입금자명'),
      col('expectedAmount', '예상 입금', { money: true, right: true }),
      col('expiryDate', '만료일', { date: true }),
      col('status', '상태', { badge: true }),
    ],
    fields: [
      account('mainAccountNo', '연결 계좌', { required: false, hint: '비우면 전체 조회.' }),
      optionalSelect('status', '상태', [
        { value: 'ACTIVE', label: 'ACTIVE 발급' },
        { value: 'DEPOSITED', label: 'DEPOSITED 입금됨' },
        { value: 'EXPIRED', label: 'EXPIRED 만료' },
        { value: 'CLOSED', label: 'CLOSED 해지' },
      ]),
    ],
  },
  {
    id: 'VIRTUAL_ACCOUNT_03',
    title: '가상계좌 상세',
    kind: 'edu',
    domain: 'account',
    apiName: 'inquireVirtualAccount',
    userKey: 'required',
    write: false,
    render: 'record',
    fields: [
      text('virtualAccountId', '가상계좌 ID', {
        required: true,
        placeholder: 'VA446552536458449003',
      }),
    ],
  },
  {
    id: 'VIRTUAL_ACCOUNT_04',
    title: '가상계좌 입금 내역',
    note: 'nameMatched(입금자명 일치)와 settledAt(미정산이면 null)이 대사의 단서다.',
    kind: 'edu',
    domain: 'account',
    apiName: 'inquireVirtualAccountDeposits',
    userKey: 'required',
    write: false,
    render: 'table',
    listKey: 'deposits',
    columns: [
      col('depositedAt', '입금 일시', { datetime: true }),
      col('depositAmount', '입금 금액', { money: true, right: true }),
      col('nameMatched', '입금자명 일치', { bool: true }),
      col('transferred', '연결계좌 이체', { bool: true }),
      col('settledAt', '정산 일시', { datetime: true }),
    ],
    fields: [text('virtualAccountId', '가상계좌 ID', { required: true })],
  },
  {
    id: 'VIRTUAL_ACCOUNT_01',
    title: '가상계좌 발급',
    note: '만료일은 발급일로부터 30일 이내다. autoTransfer 를 비우면 true — 입금되면 연결 계좌로 자동 이체된다.',
    kind: 'edu',
    domain: 'account',
    apiName: 'createVirtualAccount',
    userKey: 'required',
    write: true,
    render: 'record',
    fields: [
      account('mainAccountNo', '연결 계좌', { hint: '본인 소유의 정상 원화 계좌.' }),
      text('depositorName', '입금자명', {
        required: true,
        maxLength: 20,
        placeholder: '우아한형제들',
      }),
      amount('expectedAmount', '예상 입금 금액', { required: false }),
      date('expiryDate', '만료일', { hint: '발급일로부터 30일 이내.' }),
      text('memo', '메모', { maxLength: 255 }),
      bool('autoTransfer', '입금 후 자동 이체', { hint: '비우면 true.' }),
    ],
  },
  {
    id: 'VIRTUAL_ACCOUNT_06',
    title: '가상계좌 기간 연장',
    kind: 'edu',
    domain: 'account',
    apiName: 'extendVirtualAccount',
    userKey: 'required',
    write: true,
    render: 'record',
    fields: [
      text('virtualAccountId', '가상계좌 ID', {
        required: true,
        hint: 'ACTIVE 또는 EXPIRED 만 연장된다.',
      }),
      date('expiryDate', '변경할 만료일', { hint: '발급일로부터 30일 이내.' }),
    ],
  },
  {
    id: 'VIRTUAL_ACCOUNT_07',
    title: '가상계좌 보류금 정산',
    note: '미정산 보류 금액을 연결 계좌로 넘긴다.',
    kind: 'edu',
    domain: 'account',
    apiName: 'settleVirtualAccount',
    userKey: 'required',
    write: true,
    render: 'record',
    fields: [text('virtualAccountId', '가상계좌 ID', { required: true })],
  },
  {
    id: 'VIRTUAL_ACCOUNT_05',
    title: '가상계좌 해지',
    note: '되돌릴 수 없다.',
    kind: 'edu',
    domain: 'account',
    apiName: 'closeVirtualAccount',
    userKey: 'required',
    write: true,
    render: 'record',
    fields: [text('virtualAccountId', '가상계좌 ID', { required: true })],
  },

  // ══ 상품·은행 ══════════════════════════════════════════════════════
  {
    id: 'DEMAND_DEPOSIT_02',
    title: '수시입출금 상품',
    note: 'userKey 를 쓰지 않는다. 팀 전체가 같은 상품 목록을 본다.',
    kind: 'edu',
    domain: 'demandDeposit',
    apiName: 'inquireDemandDepositList',
    userKey: 'none',
    write: false,
    render: 'products',
    fields: [],
  },
  {
    id: 'DEMAND_DEPOSIT_01',
    title: '수시입출금 상품 등록',
    note: '수시입출금 상품은 팀에 하나면 된다. 이미 있으면 등록하지 않는다.',
    kind: 'edu',
    domain: 'demandDeposit',
    apiName: 'createDemandDeposit',
    userKey: 'none',
    write: true,
    render: 'record',
    fields: [
      text('bankCode', '은행코드', {
        required: true,
        default: '999',
        maxLength: 3,
        hint: '999 = 싸피은행. 개발·시연 계좌는 여기에 만든다.',
      }),
      text('accountName', '상품명', { required: true, maxLength: 20 }),
      text('accountDescription', '상품설명', { maxLength: 255 }),
    ],
  },
  {
    id: 'DEMAND_DEPOSIT_03',
    title: '수시입출금 계좌 생성',
    note: '만들어진 계좌는 헤더의 userKey 소유가 된다.',
    kind: 'edu',
    domain: 'demandDeposit',
    apiName: 'createDemandDepositAccount',
    userKey: 'required',
    write: true,
    render: 'record',
    fields: [
      text('accountTypeUniqueNo', '상품 고유번호', {
        required: true,
        hint: '상품 조회에서 복사한다.',
      }),
    ],
  },
  {
    id: 'BANK_01',
    title: '은행코드',
    kind: 'edu',
    domain: 'bank',
    apiName: 'inquireBankCodes',
    userKey: 'none',
    write: false,
    render: 'banks',
    fields: [],
  },
];

export const BY_ID = Object.fromEntries(CATALOG.map((entry) => [entry.id, entry]));

/**
 * 호출해서는 안 되는 apiName. 목록이 allow-list 라서 이론상 닿을 수 없지만,
 * 누군가 "편의로" 추가하는 것까지 막는다 — 부르는 순간 팀 키가 죽고 재발급이 안 된다.
 */
export const FORBIDDEN = ['reIssuedApiKey'];

for (const entry of CATALOG) {
  if (FORBIDDEN.includes(entry.apiName)) {
    throw new Error(`${entry.id}: ${entry.apiName} 은 절대 호출하지 않는다.`);
  }
}

/**
 * 왼쪽 목록에 쓰는 짧은 이름. 명세 ID 를 그대로 두면 사이드바를 넘친다 —
 * ID 자체는 워크북과 1:1 로 유지하고 표시만 줄인다.
 */
const SHORT = {
  DEMAND_DEPOSIT: 'DD',
  TRANSFER_RESERVATION: 'TR',
  VIRTUAL_ACCOUNT: 'VA',
  MINUS_ACCOUNT: 'MINUS',
  TRANSACTION_MEMO: 'MEMO',
  ACCOUNT_AUTH: 'AUTH',
};

export function shortId(id) {
  const at = id.lastIndexOf('_');
  const head = id.slice(0, at);
  return `${SHORT[head] ?? head}_${id.slice(at + 1)}`;
}

/** 왼쪽 목록 순서. 조회를 위에 둔다 — 대부분 조회하러 들어온다. */
export const NAV = [
  { group: '사용자', items: ['flow:user', 'MEMBER_02', 'MEMBER_01'] },
  {
    group: '계좌 · P0-01',
    items: ['DEMAND_DEPOSIT_04', 'DEMAND_DEPOSIT_07', 'DEMAND_DEPOSIT_05', 'DEMAND_DEPOSIT_06'],
  },
  {
    group: '거래내역 · P0-01',
    items: ['DEMAND_DEPOSIT_12', 'DEMAND_DEPOSIT_13', 'TRANSACTION_MEMO_01'],
  },
  {
    group: '개발 편의',
    items: [
      'flow:new-account',
      'DEMAND_DEPOSIT_09',
      'DEMAND_DEPOSIT_08',
      'DEMAND_DEPOSIT_10',
      'flow:auth',
      'ACCOUNT_AUTH_01',
      'ACCOUNT_AUTH_02',
    ],
  },
  {
    group: '예약이체 · P0-03~06',
    items: [
      'TRANSFER_RESERVATION_03',
      'TRANSFER_RESERVATION_04',
      'TRANSFER_RESERVATION_02',
      'TRANSFER_RESERVATION_01',
      'TRANSFER_RESERVATION_05',
      'TRANSFER_RESERVATION_07',
      'TRANSFER_RESERVATION_06',
    ],
  },
  { group: '대출 현황 · P1-13', items: ['LOAN_08', 'LOAN_09', 'LOAN_10'] },
  {
    group: '신용·추천 · P1-14',
    items: [
      'flow:loan',
      'LOAN_04',
      'LOAN_01',
      'LOAN_03',
      'LOAN_06',
      'LOAN_05',
      'LOAN_07',
      'LOAN_02',
    ],
  },
  {
    group: '마이너스통장 · 16장',
    items: [
      'MINUS_ACCOUNT_07',
      'MINUS_ACCOUNT_06',
      'MINUS_ACCOUNT_13',
      'MINUS_ACCOUNT_09',
      'MINUS_ACCOUNT_10',
      'MINUS_ACCOUNT_02',
      'MINUS_ACCOUNT_01',
      'MINUS_ACCOUNT_03',
      'MINUS_ACCOUNT_04',
      'MINUS_ACCOUNT_05',
      'MINUS_ACCOUNT_12',
      'MINUS_ACCOUNT_14',
    ],
  },
  {
    group: '가상계좌 · P0-11',
    items: [
      'VIRTUAL_ACCOUNT_02',
      'VIRTUAL_ACCOUNT_03',
      'VIRTUAL_ACCOUNT_04',
      'VIRTUAL_ACCOUNT_01',
      'VIRTUAL_ACCOUNT_06',
      'VIRTUAL_ACCOUNT_07',
      'VIRTUAL_ACCOUNT_05',
    ],
  },
  {
    group: '참조',
    items: ['DEMAND_DEPOSIT_02', 'DEMAND_DEPOSIT_01', 'DEMAND_DEPOSIT_03', 'BANK_01'],
  },
];

/** 스크립트·여러 단계를 대신하는 묶음 작업. */
export const FLOWS = {
  'flow:user': {
    title: 'userKey 확보',
    note: '조회해서 없으면 만든다. MEMBER_02 → MEMBER_01.',
  },
  'flow:new-account': {
    title: '계좌 만들기',
    note: '상품 확보 → 계좌 생성 → 초기 입금. scripts/fin-account.mjs 와 같은 일을 한다.',
  },
  'flow:auth': {
    title: '1원 인증',
    note: '송금 → 통장 적요에서 코드 읽기 → 검증. scripts/auth-code.mjs 를 대신한다.',
  },
  'flow:loan': {
    title: '대출 받기',
    note: '내 등급 → 상품 목록 → 심사 신청 → 가입. 기술스택 4.1 시드 7단계를 그대로 따른다 — 여기까지 하면 금융망이 스스로 공백을 만든다.',
  },
};

/** 값이 코드인 열의 사람 말 대응표. 색만으로 상태를 구분하지 않으려고 함께 띄운다. */
export const VALUE_MAPS = {
  minusTx: { W: '출금', R: '상환', I: '이자 납입' },
};

/** 나쁜 소식인 상태값. 배지를 붉게 칠하되 글자를 반드시 함께 보여준다. */
export const BAD_STATUS = new Set([
  'FAIL',
  'FAILED',
  'OVERDUE',
  '연체',
  '거절',
  'EXPIRED',
  'CANCELED',
]);

/** 자주 만나는 응답코드. 원인이 화면에 안 보이는 것들만 골랐다. */
export const CODE_HINTS = {
  H1003: '이 PC 시계가 금융망 기준 ±5분을 벗어났다. 시간 동기화를 확인한다.',
  H1002: '전송일자가 오늘이 아니다. 시계의 날짜를 확인한다.',
  H1007: '기관거래고유번호가 중복됐다. 같은 초에 너무 빨리 연달아 불렀다 — 다시 누르면 된다.',
  H1008: 'apiKey 가 유효하지 않다. backend/.env 의 FINOPENAPI_API_KEY 를 확인한다.',
  H1009: 'userKey 가 유효하지 않다. 사용자 조회부터 다시 한다.',
  H1010: '기관거래고유번호가 20자가 아니다.',
  A1003:
    '계좌번호가 유효하지 않다. 존재하지 않는 계좌·형식 이상·타인 계좌를 모두 이 코드로 뭉갠다.',
  A1014: '잔액이 부족하다.',
  A1086: '1원 인증 발급 기록이 없다. 송금부터 다시 한다.',
  A1087: '1원 인증 시간이 만료됐다. 송금부터 다시 한다.',
  A1088: '인증코드가 일치하지 않는다. 다시 입력한다.',
  A1090: '인증코드가 유효하지 않다. 다시 입력한다.',
  E4002: '이 이메일은 금융망 전역에서 이미 쓰이고 있다. 사용자 조회로 복구한다.',
  E4003: '등록되지 않은 사용자다. 사용자 생성이 필요하다.',
  Q1001: '요청 본문 형식이 잘못됐다. 금액·기간·금리를 문자열로 보내는지 확인한다.',
};
