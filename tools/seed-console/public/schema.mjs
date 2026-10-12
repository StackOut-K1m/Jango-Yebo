// 조정할 수 있는 값의 목록. <b>서버와 브라우저가 같은 파일을 읽는다</b> — 폼 렌더링과
// 값 검증이 함께 따라오게 하려는 것이고, 금융망 콘솔의 catalog.mjs 와 같은 규율이다.
//
// ⚠ <b>여기 없는 경로는 서버가 받지 않는다.</b> 화면이 보낸 설정을 통째로 믿고 생성기에
//   넘기면, 오타 하나가 6개월치 거래를 조용히 망가뜨린 채로 업로드된다. 목록에 있는 경로만
//   골라 타입대로 강제 변환하고 나머지는 버린다.
//
// 값의 <b>기본치는 여기 적지 않는다</b> — scripts/seed/plan.mjs 의 DEFAULT_TUNING 이 정한다.
// 이 파일은 "그 값을 어떻게 보여주고 어디까지 허용하나"만 안다.

/** @param type int · money · rate · text · date · day · dow · shift · dowList · monthList · dateList · textList · bool */
const f = (path, label, opts = {}) => ({ path, label, type: 'int', ...opts });

/**
 * 분류 코드. <b>백엔드 `V2__seed_transaction_category.sql` 이 가진 것만</b> 쓴다 —
 * 여기 없는 코드로 항목을 만들면 정답 라벨 CSV 가 조인되지 않는다.
 *
 * <p>지출 쪽만 추렸다. 수입 세 줄은 채널이 고정이라 화면에서 분류를 바꾸지 않는다.
 */
export const EXPENSE_CATEGORIES = [
  'MATERIALS', 'RENT', 'PAYROLL', 'SOCIAL_INSURANCE', 'INSURANCE', 'UTILITIES', 'TELECOM',
  'SUBSCRIPTION', 'ADVERTISING', 'TAX', 'LOAN_REPAYMENT', 'EQUIPMENT', 'SUPPLIES',
  'PLATFORM_FEE', 'PERSONAL', 'OTHER_EXPENSE', 'WITHDRAWAL',
];

// ── 기간·앵커 ─────────────────────────────────────────────────────────────────

export const SCALAR_GROUPS = [
  {
    id: 'window',
    label: '기간',
    note: '과거는 CSV 로 올라가고 미래는 예측이 맞혀야 할 몫이다. 예보 구간이 앵커의 「며칠 안」보다 짧으면 앵커가 잘린다.',
    fields: [
      f('window.pastDays', '과거 일수', { min: 30, max: 730, unit: '일', hint: '길수록 반복 탐지 표본이 늘지만 업로드도 느려진다.' }),
      f('window.horizonDays', '예보 구간', { min: 7, max: 180, unit: '일' }),
    ],
  },
  {
    id: 'anchor',
    label: '공백 앵커',
    note: '달력이 아니라 <b>기준일 기준</b>이다. 시연에 필요한 것은 "10월에 빈다"가 아니라 "발표일로부터 30일 안에 빈다"다 — 달력에 박아 두면 그 달이 과거가 되는 순간 공백이 조용히 사라진다.',
    fields: [
      f('anchor.withinDays', '며칠 안', { min: 3, max: 120, unit: '일', hint: '기준일로부터 이 안에서 저점을 찾는다.' }),
      f('anchor.lowest', '목표 저점', { type: 'money', min: -50_000_000, max: 50_000_000, hint: '음수라야 공백이 보인다. 시작 잔액을 여기서 역산한다.' }),
      f('anchor.tolerance', '허용 오차', { type: 'money', min: 0, max: 5_000_000, hint: '이보다 벌어지면 실패로 친다 — 공백 없는 시드는 시연이 안 되는 시드다.' }),
    ],
  },
  {
    id: 'trend',
    label: '추세·탐색',
    note: '매출이 <b>줄어야</b> 시나리오가 성립한다. 성장(+)으로 두면 잔액이 우상향해, 공백을 만들려면 시작 잔액이 음수여야 하고 과거 6개월이 통째로 마이너스가 된다.',
    fields: [
      f('monthlyGrowth', '월 성장률', { type: 'rate', min: -0.2, max: 0.2, step: 0.001, hint: '−0.005 = 월 −0.5%(연 −6%).' }),
      f('search.max', '시작 잔액 탐색 상한', { type: 'money', min: 1_000_000, max: 100_000_000 }),
      f('search.step', '탐색 간격', { type: 'money', min: 10_000, max: 1_000_000, hint: '좁히면 정확하고 느리다. 이 값마다 6개월치를 한 번 다시 만든다.' }),
    ],
  },
  {
    id: 'funding',
    label: '사장님 메우기',
    note: '과거에 잔액이 바닥나면 사장님이 개인 돈을 넣어 막는다 — 계좌는 마이너스가 될 수 없으니 실제로 그렇게 한다. <b>미래에는 그럴 수 없어서 공백이 드러난다.</b> 이 비대칭이 서비스가 파는 것이라, 메우기는 기준일 <b>전</b>에만 적용한다.',
    fields: [
      f('funding.description', '적요', { type: 'text', max: 30, hint: '사장님 본인 이름. 계정 이름과 같아야 통장에서 자기 입금으로 읽힌다.' }),
      f('funding.floor', '바닥선', { type: 'money', min: -10_000_000, max: 10_000_000, hint: '이 아래로 내려가려 하면 넣는다.' }),
      f('funding.headroom', '여유분', { type: 'money', min: 0, max: 10_000_000, hint: '바닥선에 딱 맞추지 않고 이만큼 위로 올린다.' }),
      f('funding.unit', '입금 단위', { type: 'money', min: 1_000, max: 10_000_000, hint: '1원 단위로 딱 맞춰 넣는 사람은 없다.' }),
    ],
  },
];

// ── 사업장·계정 ───────────────────────────────────────────────────────────────

export const BUSINESS_FIELDS = [
  f('business.name', '상호', { type: 'text', max: 40, hint: '같은 이름의 사업장이 이미 있으면 다시 만들지 않는다.' }),
  f('business.businessRegNo', '사업자등록번호', { type: 'text', max: 20 }),
  f('business.industryType', '업종', { type: 'text', max: 30 }),
  f('business.taxationType', '과세유형', { type: 'text', max: 30 }),
  f('business.region', '지역', { type: 'text', max: 20 }),
  f('business.annualRevenue', '연매출', {
    type: 'money', min: 0, max: 10_000_000_000,
    hint: '⚠ 1억 5천만 이상이면 음식점업은 복식부기 의무라 P0-12 신고서 초안의 대상 서식과 어긋난다(API 07장).',
  }),
  f('business.employeeCount', '직원 수', { min: 0, max: 100, unit: '명' }),
  f('business.openedOn', '개업일', { type: 'date' }),
];

export const OWNER_FIELDS = [
  f('owner.email', '이메일', {
    type: 'text', max: 100,
    hint: '<b>이 이메일 하나로 금융망 userKey 가 정해진다.</b> 백엔드도 같은 방식으로 찾으므로, 여기서 만든 계좌가 이 계정 것이 된다.',
  }),
  f('owner.password', '비밀번호', { type: 'text', max: 60, secret: true }),
  f('owner.name', '이름', { type: 'text', max: 20, hint: '메우기 적요와 같게 두면 통장에서 자기 입금으로 읽힌다.' }),
  f('owner.phone', '전화', { type: 'text', max: 20 }),
];

// ── 항목 표 ───────────────────────────────────────────────────────────────────

/** 수입. 카드는 영업일마다라 요일 칸이 없다. */
export const INCOME_TABLE = {
  id: 'income',
  label: '수입',
  base: 'income',
  note: '채널 비중은 SRS 1.1 의 카드 61% · 배달 30% · 현금 9% 에서 역산했다. 변동폭은 ±비율이고 100원 단위로 끊긴다. <b>수수료</b>는 거래를 만드는 데 쓰지 않는다 — 통장에 찍히는 금액이 이미 뗀 뒤라서다. 쓰는 곳은 아래 상품 구성이고, 이 비율을 되돌려 정산 전 총매출을 얻는다.',
  rows: [
    { key: 'card', label: '카드 정산', fixedNote: '영업일마다' },
    { key: 'platform', label: '배달 정산', dow: true },
    { key: 'cash', label: '현금 매출', dow: true },
  ],
  columns: [
    { field: 'description', label: '적요', type: 'text', max: 30 },
    { field: 'base', label: '기준액', type: 'money', min: 0, max: 100_000_000 },
    { field: 'spread', label: '변동폭', type: 'rate', min: 0, max: 1, step: 0.01 },
    { field: 'fee', label: '수수료', type: 'rate', min: 0, max: 0.9, step: 0.005 },
    { field: 'dow', label: '요일', type: 'dow', only: 'dow' },
  ],
};

/**
 * 상품 구성 (P1-22). <b>금액을 정하는 표가 아니다</b> — 그 달의 총매출은 매출성 입금을
 * 채널 수수료로 되돌려 환산하고, 이 표는 그 총액을 어떻게 나눌지만 정한다.
 */
export const PRODUCT_TABLE = {
  id: 'products',
  label: '상품 구성',
  base: 'products',
  note: '월 단위 「상품별 매출현황」 인쇄물 한 장이 될 표다(P1-22). <b>비중이 0 이면 그 상품은 안 만들어진다.</b> 비중은 서로 더해 1 이 아니어도 되고 비율로 정규화한다. <b>월 변화</b>가 시나리오를 만든다 — 전체 매출이 완만히 주는데 <b>무엇이 팔려서 줄었는지</b>가 이 기능이 답할 질문이라, 전 품목을 고르게 두면 구성비가 여섯 달 내내 같은 그림이다.',
  onField: 'weight',
  columns: [
    { field: 'label', label: '상품명', type: 'text', max: 40 },
    { field: 'price', label: '단가', type: 'money', min: 0, max: 10_000_000 },
    { field: 'weight', label: '비중', type: 'rate', min: 0, max: 1, step: 0.01 },
    { field: 'trend', label: '월 변화', type: 'rate', min: -0.5, max: 0.5, step: 0.01 },
    { field: 'discountRate', label: '할인율', type: 'rate', min: 0, max: 1, step: 0.01 },
  ],
  addable: true,
  // 화면에서 새로 만든 줄의 빈 칸. 표마다 칸이 달라 여기서 정한다 — 안 두면 고정비의
  // 기본값(금액·날짜·분류)이 상품 줄에 들어가고, 생성기는 단가가 없는 줄을 그냥 건너뛴다.
  newRow: { price: 5_000, weight: 0, trend: 0, discountRate: 0 },
};

/**
 * 고정비. 표로 두는 것은 <b>한 날에 몰렸는지를 눈으로 보려고</b>다 —
 * 몰리면 월 스윙이 깊어져 매달 잔액이 마이너스로 내려가고, 상시 켜진 알림은 알림이 아니다.
 */
export const FIXED_TABLE = {
  id: 'fixed',
  label: '고정비',
  base: 'fixed',
  note: '금액을 <b>0 으로 두면 그 항목은 안 만들어진다.</b> 휴일 보정은 자동이체가 밀리는 쪽(+1)과 급여처럼 앞당기는 쪽(−1)이 다르다.',
  columns: [
    { field: 'description', label: '적요', type: 'text', max: 30 },
    { field: 'amount', label: '금액', type: 'money', min: 0, max: 100_000_000 },
    { field: 'day', label: '날짜', type: 'day' },
    { field: 'shift', label: '휴일', type: 'shift' },
    { field: 'time', label: '시각', type: 'time' },
    { field: 'category', label: '분류', type: 'category' },
  ],
  addable: true,
};

export const IRREGULAR_TABLE = {
  id: 'irregular',
  label: '주기 없는 거래',
  base: 'irregular',
  note: '<b>모델이 맞혀야 할 몫(잔여)을 만드는 표다.</b> 나머지 항목은 전부 주기가 있어 탐지기가 예정 거래로 잡아가고, 잡힌 만큼은 잔여에서 빠진다 — 이 표가 없으면 모델에 들어가는 입력이 사실상 빈다. <b>하루 확률</b>이 0 이면 그 항목은 안 만들어진다. 적요를 여럿 두면 날마다 그중 하나를 골라 쓰므로 그룹마다 표본이 흩어져 패턴이 서지 않는다.',
  onField: 'probability',
  columns: [
    { field: 'descriptions', label: '적요 목록', type: 'textList' },
    { field: 'probability', label: '하루 확률', type: 'rate', min: 0, max: 1, step: 0.005 },
    { field: 'min', label: '최소 금액', type: 'money', min: 0, max: 100_000_000 },
    { field: 'max', label: '최대 금액', type: 'money', min: 0, max: 100_000_000 },
    // 시각을 흔드는 폭({@code timeWindowMinutes})은 칸을 두지 않는다 — 화면에서 만질 일이
    // 없는 값인데 칸을 하나 더 붙이면 표가 카드 폭을 넘어 가로로 스크롤된다(실측 656 > 633).
    { field: 'timeFrom', label: '시각', type: 'time' },
  ],
  // 항목을 화면에서 늘리지 않는다. 네 줄은 각각 **다른 탐지 규칙을 피하려고** 있는 것이라
  // (적요 흩뿌리기 · 간격 흔들기 · 드물게 일으키기), 뜻을 모르고 하나 더하면 규칙적인
  // 거래가 되어 탐지기가 그냥 잡아간다 — 잔여가 늘지 않는다. 늘릴 일이 생기면
  // `scripts/seed/plan.mjs` 의 `irregular` 주석을 읽고 거기서 더한다.
  addable: false,
};

export const WEEKLY_TABLE = {
  id: 'weekly',
  label: '주 단위 지출',
  base: 'weekly',
  note: '인건비를 월급과 주급으로 쪼갠 것은 의도다. 한 날에 몰리면 월 스윙이 깊어져 "과거는 안 뚫리고 앵커 구간만 빈다"가 성립하지 않는다.',
  columns: [
    { field: 'description', label: '적요', type: 'text', max: 30 },
    { field: 'amount', label: '금액', type: 'money', min: 0, max: 100_000_000 },
    { field: 'dow', label: '요일', type: 'dow' },
    { field: 'shift', label: '휴일', type: 'shift' },
    { field: 'time', label: '시각', type: 'time' },
    { field: 'category', label: '분류', type: 'category' },
  ],
  addable: true,
};

export const MATERIALS_FIELDS = [
  f('materials.base', '기준액', { type: 'money', min: 0, max: 100_000_000, hint: '매출이 늘면 원가도 따라 는다.' }),
  f('materials.spread', '변동폭', { type: 'rate', min: 0, max: 1, step: 0.01 }),
  f('materials.days', '요일', { type: 'dowList', hint: '주 2회(월·목)가 기본이다.' }),
  f('materials.time', '시각', { type: 'time' }),
  f('materials.vendors', '거래처', { type: 'textList', hint: '순서대로 돌려 쓴다. 쉼표로 나눈다.' }),
];

export const TAX_FIELDS = [
  f('tax.description', '적요', { type: 'text', max: 30 }),
  f('tax.amount', '금액', { type: 'money', min: 0, max: 100_000_000 }),
  f('tax.day', '날짜', { type: 'day' }),
  f('tax.months', '납부 월', { type: 'monthList', hint: '일반과세 개인사업자는 1·4·7·10월 연 4회다. <b>10월 건이 공백을 만든다.</b>' }),
  f('tax.shift', '휴일', { type: 'shift' }),
];

export const CALENDAR_FIELDS = [
  f('closedDays', '휴업일', {
    type: 'dateList',
    hint: '<b>문을 닫는 날.</b> 명절만 닫고 어린이날·개천절에는 연다 — 카페가 그렇다. 공휴일 전체는 영업일 계산용이라 여기서 고치지 않는다.',
  }),
];

// ── 계좌 ──────────────────────────────────────────────────────────────────────

export const ACCOUNT_COLUMNS = [
  { field: 'alias', label: '별칭', type: 'text', max: 30 },
  { field: 'primary', label: '대표', type: 'bool' },
  { field: 'verify', label: '1원 인증', type: 'bool' },
  { field: 'main', label: '거래 투입', type: 'bool' },
];

// ── 실행 단계 ─────────────────────────────────────────────────────────────────

/**
 * 실행 순서. {@code needs} 는 선행 단계이고, 화면이 고른 것에 자동으로 얹는다 —
 * "업로드만 할게" 를 눌렀는데 계좌가 없어서 실패하는 것보다 낫다.
 *
 * <p>{@code brings} 는 <b>뒤따라야 하는</b> 단계다. 앞의 단계가 뒤처리를 남기는 경우에 쓴다 —
 * 지금은 {@code balance} 가 넣은 흔적을 {@code exclude} 가 치운다. 고르고 말고를 사람에게
 * 맡기면 잊는다: 실제로 웹 콘솔에 이 단계가 없어 흔적이 한 달 넘게 예측에 섞였다(2026-09-15).
 *
 * <p>{@code danger} 는 <b>되돌릴 수 없는</b> 단계다. 지금은 {@code reset} 하나이고, 화면이
 * 눈에 띄게 그리고 실행 전에 따로 한 번 더 묻는다 — 나머지 단계는 몇 번을 돌려도 같은
 * 상태가 되지만 이것만 그렇지 않다.
 *
 * <p>전부 <b>쓰기</b>다. 금융망과 우리 DB 를 실제로 바꾸므로 화면이 실행 전에 한 번 확인받는다.
 * 얹힌 단계도 확인 화면에 함께 보인다.
 */
export const STEPS = [
  { key: 'login', label: '계정 확보', needs: [], note: '가입 → 로그인. 이미 있으면 로그인만 한다.' },
  // 지우기는 <b>세우기 앞</b>에 온다. 이 배열 차례가 곧 실행 순서라(expandSteps) 여기 있으면
  // 「지우고 → 다시 세우기」가 한 번의 실행으로 끝난다. 뒤에 두면 방금 세운 것을 지운다.
  {
    key: 'reset',
    label: '기존 시드 지우기 (선택)',
    needs: ['login'],
    danger: true,
    note: '⚠ <b>되돌릴 수 없다.</b> 이 계정에 연동된 계좌를 <b>거래째</b> 지우고(API 7.2), 그 계좌를 금융망에서도 해지한다(DD_14 — 잔액을 0 으로 비운 뒤). 잘못 넣은 시드는 다시 돌려도 안 고쳐지고 덧쌓이기만 한다. <b>사업장은 남는다</b> — 상호가 같으면 다음 단계가 그대로 재사용한다. 지우고 나서 멈추면 계좌가 0개로 남고, 그때는 예보 화면이 빈 상태가 아니라 옛 곡선을 보여준다 — 진행 로그가 크게 알린다.',
  },
  { key: 'business', label: '사업장 확보', needs: ['login'], note: '같은 상호가 있으면 다시 만들지 않는다.' },
  { key: 'finAccounts', label: '금융망 계좌 확보', needs: [], note: '싸피은행(999)에 모자란 만큼만 만든다.' },
  { key: 'link', label: '계좌 연동 · 1원 인증', needs: ['login', 'business', 'finAccounts'], note: '등록 → 통장 적요에서 코드 읽기 → 검증. 이미 인증된 계좌는 건드리지 않는다.' },
  { key: 'csv', label: '과거 거래 업로드', needs: ['login', 'business', 'finAccounts', 'link'], note: '생성한 CSV 를 투입 계좌에 올린다. 중복은 서버가 흡수한다.' },
  { key: 'balance', label: '금융망 잔액 맞추기', needs: ['finAccounts'], brings: ['exclude'], note: '기준일 잔액에 맞춰 「전기이월」 한 건을 넣는다 — 잔액은 CSV 가 아니라 DD_07 에서 온다.' },
  { key: 'sync', label: '동기화 접수', needs: ['login', 'business', 'finAccounts', 'link'], note: '오늘 거래를 금융망에서 가져온다. 끝날 때까지 기다린다 — 다음 단계가 그 거래를 찾는다.' },
  // needs 에 balance 를 넣지 않는다. 우리 API 만 부르므로 기술적으로 필요하지도 않고,
  // 넣으면 balance ↔ exclude 가 서로의 선행·후행이 되어 **둘 다 잠겨 아무도 못 끈다.**
  // 순서는 이 배열이 정한다 — expandSteps 가 배열 차례로 돌려주므로 balance 뒤다.
  { key: 'exclude', label: '잔액 맞추기 흔적 제외', needs: ['login', 'business', 'finAccounts', 'link', 'sync'], note: '「전기이월」·「개발용 초기 잔액」을 예측 입력에서 뺀다. 빼지 않으면 잔액 맞추기용 입금이 평소 매출로 읽힌다 — 지우지 않고 표시만 한다.' },
  { key: 'productSales', label: '상품별 매출 등록', needs: ['login', 'business'], note: '월 단위 상품 구성비를 넣는다(P1-22, API 14.4). 계좌와 무관하고 우리 DB 만 바꾼다. 같은 기간은 통째로 교체되므로 몇 번을 돌려도 같다.' },
  // 「휴업일」 칸이 CSV 생성에만 쓰이고 백엔드에는 안 가던 자리다. CLI(`scripts/seed.mjs`)는
  // 늘 이 단계를 밟았고, 화면만 빠져 있었다 — 시드 경로 둘이 갈리면 「명령어로는 되는데
  // 화면으로는 안 된다」가 생긴다.
  { key: 'calendar', label: '휴업일 등록', needs: ['login', 'business'], note: '위 「휴업일」에 적은 날짜를 백엔드 영업일 달력에 넣는다. <b>빼면 명절 주 정산 금액이 안 줄어든다</b> — 공휴일은 서버가 알지만 가게가 언제 닫는지는 등록해야 안다. 실측으로 추석 주 배달 정산이 497,400원 대 290,200원이다. 계좌와 무관하고 우리 DB 만 바꾼다.' },
  { key: 'patterns', label: '반복 후보 승인 (선택)', needs: ['login', 'business', 'finAccounts', 'link', 'csv'], note: '탐지한 후보를 전부 승인해 예정 거래를 만든다. 발표에서는 화면에서 직접 누르는 편이 시연 흐름에 맞다.' },
  { key: 'autoExecute', label: '예정 거래 자동 반영 켜기 (선택)', needs: ['login', 'business', 'finAccounts', 'link'], note: '⚠ 켜면 서버가 예정일마다 이 계좌에서 실제로 입출금한다. 로컬과 서버 중 한쪽에서만 켠다.' },
];

/** 고른 단계에 선행·후행 단계를 얹어 실행 순서대로 돌려준다. */
export function expandSteps(selected) {
  const want = new Set(selected);
  let grew = true;
  while (grew) {
    grew = false;
    for (const step of STEPS) {
      if (!want.has(step.key)) continue;
      for (const key of [...step.needs, ...(step.brings ?? [])]) {
        if (!want.has(key)) {
          want.add(key);
          grew = true;
        }
      }
    }
  }
  return STEPS.filter((s) => want.has(s.key)).map((s) => s.key);
}

// ── 값 강제 변환 ──────────────────────────────────────────────────────────────

const DOW_NAMES = ['일', '월', '화', '수', '목', '금', '토'];
export const dowName = (n) => DOW_NAMES[n] ?? '?';

const clamp = (n, min, max) => Math.min(max ?? Infinity, Math.max(min ?? -Infinity, n));
const isDate = (s) => /^\d{4}-\d{2}-\d{2}$/.test(s) && !Number.isNaN(Date.parse(`${s}T00:00:00Z`));

/**
 * 한 칸을 선언된 타입으로 강제 변환한다. 못 읽는 값은 {@code undefined} 를 돌려주고,
 * 그러면 호출한 쪽이 그 칸을 <b>지정하지 않은 것</b>으로 처리해 기본값이 남는다.
 */
export function coerce(spec, raw) {
  if (raw === undefined || raw === null) return undefined;

  switch (spec.type) {
    case 'int':
    case 'money': {
      const n = Number(raw);
      return Number.isFinite(n) ? Math.round(clamp(n, spec.min, spec.max)) : undefined;
    }
    case 'rate': {
      const n = Number(raw);
      if (!Number.isFinite(n)) return undefined;
      // 부동소수점 찌꺼기(0.15000000000000002)가 화면에 그대로 보이지 않게 끊는다.
      return Number(clamp(n, spec.min, spec.max).toFixed(6));
    }
    case 'day': {
      const n = Number(raw);
      // 29~31 은 없는 달이 있어 항목이 그 달만 통째로 빠진다. 28 까지만 받는다.
      return Number.isFinite(n) ? Math.round(clamp(n, 1, 28)) : undefined;
    }
    case 'dow': {
      const n = Number(raw);
      return Number.isFinite(n) ? Math.round(clamp(n, 0, 6)) : undefined;
    }
    case 'shift': {
      const n = Number(raw);
      return n < 0 ? -1 : 1;
    }
    case 'category': {
      const s = String(raw).trim().toUpperCase();
      return EXPENSE_CATEGORIES.includes(s) ? s : undefined;
    }
    case 'bool':
      return Boolean(raw);
    case 'text': {
      const s = String(raw).trim();
      return s ? s.slice(0, spec.max ?? 100) : undefined;
    }
    case 'time': {
      const s = String(raw).trim();
      return /^\d{2}:\d{2}:\d{2}$/.test(s) ? s : undefined;
    }
    case 'date':
      return isDate(String(raw).trim()) ? String(raw).trim() : undefined;
    case 'dowList':
      return toList(raw).map(Number).filter((n) => Number.isInteger(n) && n >= 0 && n <= 6);
    case 'monthList':
      return toList(raw).map(Number).filter((n) => Number.isInteger(n) && n >= 1 && n <= 12);
    case 'dateList':
      return toList(raw).map(String).map((s) => s.trim()).filter(isDate);
    case 'textList':
      return toList(raw).map(String).map((s) => s.trim()).filter(Boolean).slice(0, 50);
    default:
      return undefined;
  }
}

/** 배열이거나 쉼표로 나눈 문자열이거나. 화면은 문자열로, 파일은 배열로 보낸다. */
function toList(raw) {
  if (Array.isArray(raw)) return raw;
  return String(raw)
    .split(/[,\s]+/)
    .filter(Boolean);
}

// ── 경로 다루기 ───────────────────────────────────────────────────────────────

export function getAt(obj, path) {
  return path.split('.').reduce((node, key) => (node == null ? undefined : node[key]), obj);
}

export function setAt(obj, path, value) {
  const keys = path.split('.');
  const last = keys.pop();
  let node = obj;
  for (const key of keys) {
    if (typeof node[key] !== 'object' || node[key] === null) node[key] = {};
    node = node[key];
  }
  node[last] = value;
  return obj;
}

/** 이 스키마가 아는 모든 스칼라 칸. 표 항목은 base 가 정해지면 그때 펼친다. */
export function scalarFields() {
  return [
    ...SCALAR_GROUPS.flatMap((g) => g.fields),
    ...BUSINESS_FIELDS,
    ...MATERIALS_FIELDS,
    ...TAX_FIELDS,
    ...CALENDAR_FIELDS,
  ];
}

/**
 * 서버가 <b>화면이 보낸 값을 받아들이는 표 목록</b>이다 ({@code sanitizeTuning}).
 * 여기 없는 표는 통째로 버려진다 — 화면에는 보이는데 고쳐도 아무 일이 안 일어난다.
 * <b>표를 하나 만들면 여기에도 넣는다.</b>
 */
export const TABLES = [INCOME_TABLE, PRODUCT_TABLE, FIXED_TABLE, WEEKLY_TABLE, IRREGULAR_TABLE];
