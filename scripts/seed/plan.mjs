import { pathToFileURL } from 'node:url';

// 시연 데이터 생성기 — 순수 함수만. 네트워크도 파일도 건드리지 않는다.
//
// 여기서 나오는 결과가 시연 화면의 모든 숫자를 결정하므로 두 가지를 지킨다.
//
//   1. 결정적이다. 같은 asOf 로 몇 번을 돌려도 같은 거래가 나온다. 난수는 고정 시드 PRNG 이며
//      스트림마다 오프셋을 달리 준다 — 한 항목의 금액을 고쳐도 다른 항목이 밀리지 않게.
//   2. 공백이 우연이 아니다. ANCHOR 가 "발표일 기준 며칠 뒤에 얼마나 빈다"를 직접 지정하고,
//      시작 잔액은 거기서 역산한다. 금액을 손보면 시작 잔액이 따라 움직이지 공백이 사라지지 않는다.
//
// 단독 실행하면 잔액 곡선을 찍는다:  node scripts/seed/plan.mjs --as-of 2026-09-28

// ── 달력 ──────────────────────────────────────────────────────────────────────

/**
 * 2026 관공서 공휴일. 한 해를 다 넣는다 — {@code --as-of} 를 앞당기면 창이 3월까지
 * 내려가는데, 그때 빠진 공휴일이 있으면 영업일 계산이 조용히 틀린다.
 *
 * ⚠ 음력 기반(설날·부처님오신날·추석)은 매년 바뀐다. 해가 바뀌면 여기부터 고친다.
 * 추석 9/24~26 과 한글날 10/9 는 이 시나리오의 공백 위치를 직접 정하므로 특히 그렇다.
 */
const HOLIDAYS_2026 = new Set([
  '2026-01-01', // 신정 (목)
  '2026-02-16', // 설날 연휴 (월)
  '2026-02-17', // 설날 (화)
  '2026-02-18', // 설날 연휴 (수)
  '2026-03-01', // 삼일절 (일)
  '2026-03-02', // 〃 대체공휴일 (월)
  '2026-05-05', // 어린이날 (화)
  '2026-05-24', // 부처님오신날 (일)
  '2026-05-25', // 〃 대체공휴일 (월)
  '2026-06-06', // 현충일 (토)
  '2026-08-15', // 광복절 (토)
  '2026-09-24', // 추석 연휴 (목)
  '2026-09-25', // 추석 (금)
  '2026-09-26', // 추석 연휴 (토)
  '2026-10-03', // 개천절 (토)
  '2026-10-09', // 한글날 (금)
  '2026-12-25', // 성탄절 (금)
]);

export const ymd = (d) => d.toISOString().slice(0, 10);
export const parse = (s) => new Date(`${s}T00:00:00Z`);
export const addDays = (d, n) => new Date(d.getTime() + n * 86400000);

/** 0=일 … 6=토. UTC 로 다루는 것은 시간대 때문에 하루가 밀리는 것을 막기 위해서다. */
export const dow = (d) => d.getUTCDay();
export const isBusinessDay = (d) => dow(d) !== 0 && dow(d) !== 6 && !HOLIDAYS_2026.has(ymd(d));

export function shiftToBusinessDay(d, direction) {
  let cursor = d;
  for (let i = 0; i < 14; i += 1) {
    if (isBusinessDay(cursor)) return cursor;
    cursor = addDays(cursor, direction);
  }
  throw new Error(`영업일을 14일 안에 못 찾았다: ${ymd(d)}`);
}


function* eachDay(from, to) {
  for (let d = from; d <= to; d = addDays(d, 1)) yield d;
}

// ── 결정적 난수 ────────────────────────────────────────────────────────────────

function mulberry32(seed) {
  let a = seed >>> 0;
  return () => {
    a = (a + 0x6d2b79f5) >>> 0;
    let t = Math.imul(a ^ (a >>> 15), 1 | a);
    t = (t + Math.imul(t ^ (t >>> 7), 61 | t)) ^ t;
    return ((t ^ (t >>> 14)) >>> 0) / 4294967296;
  };
}

/** 스트림마다 독립된 난수. 이름을 해시해 오프셋을 만든다. */
function streamRandom(name) {
  let h = 2166136261;
  for (const ch of name) h = Math.imul(h ^ ch.charCodeAt(0), 16777619);
  return mulberry32(h ^ 0x5eed);
}

/** 1원 단위 잡음은 시연에서 지저분하기만 하다. 100원 단위로 끊는다. */
const jitter = (rnd, base, spread) =>
  Math.round((base * (1 + (rnd() * 2 - 1) * spread)) / 100) * 100;

// ── 페르소나 ──────────────────────────────────────────────────────────────────

/**
 * 설정 한 벌. <b>숫자는 전부 여기 모은다</b> — 시드 콘솔(`tools/seed-console`)이 이 모양
 * 그대로 화면에 펼치고 고친 값을 돌려주므로, 여기 없는 상수를 생성 코드 안쪽에 박아 두면
 * 화면에서 조정할 수 없다. 항목을 하나 늘릴 때도 같은 규칙이다.
 *
 * <p>부분 지정은 {@link resolveTuning} 이 기본값에 얹는다 — 금액 하나만 보내도 나머지는 그대로다.
 *
 * <p>⚠ <b>스트림 이름을 바꾸지 않는다.</b> 난수는 이름을 해시해 만들므로(`streamRandom`)
 * `card`·`platform`·`cash`·`material` 을 고치면 6개월치 거래가 통째로 다른 값이 된다.
 */
export const DEFAULT_TUNING = {
  /**
   * 연매출 8,800만원. 1억 미만이라야 간편장부·기준경비율 D 유형에 맞는다 (2026-09-02 결정).
   * 채널 비중은 SRS 1.1 의 카드 61% · 배달 30% · 현금 9% 에서 역산했다.
   */
  business: {
    name: '카페 한잔',
    businessRegNo: '123-45-67890',
    industryType: 'FOOD_CAFE',
    taxationType: 'GENERAL',
    region: '광주',
    annualRevenue: 88_000_000,
    employeeCount: 2,
    openedOn: '2024-03-11',
  },

  /**
   * 매출이 완만히 준다(연 -6%). <b>이 부호가 시나리오를 성립시킨다.</b>
   * 성장(+)으로 두면 잔액이 우상향해 10월 공백을 만들려면 시작 잔액이 음수여야 하고,
   * 과거 6개월이 통째로 마이너스가 된다. 하락이라야 "6개월 전엔 여유가 있었는데
   * 이제 아슬아슬하다"가 되고 과거가 0 위에 남는다.
   */
  monthlyGrowth: -0.005,

  /**
   * 창. 과거는 CSV 로 들어가고 미래는 예측이 맞혀야 할 몫이다.
   * 예보 창(`anchor.withinDays`)보다 {@code horizonDays} 가 짧으면 앵커가 잘린다.
   */
  window: { pastDays: 180, horizonDays: 32 },

  /**
   * <b>문을 닫는 날.</b> 명절만 닫고 어린이날·개천절에는 연다 — 카페가 그렇다.
   * 공휴일 전체(`HOLIDAYS_2026`)는 영업일 계산용이라 여기서 고치지 않는다.
   */
  closedDays: [
    '2026-02-16', '2026-02-17', '2026-02-18', // 설날 연휴
    '2026-09-24', '2026-09-25', '2026-09-26', // 추석 연휴
  ],

  /**
   * 수입. {@code base} 는 1회 금액, {@code spread} 는 흔드는 폭이다(0.15 = ±15%).
   *
   * <p>{@code fee} 는 <b>입금되기 전에 이미 떼인 비율</b>이다. 거래를 만드는 데는 쓰지 않는다 —
   * 통장에 찍히는 금액이 이미 뗀 뒤의 것이라 그렇다. 쓰는 곳은 상품별 매출(P1-22)이고, 거기서
   * 이 비율을 되돌려 <b>정산 전 총매출</b>을 얻는다. 판매 층과 입금 층은 분모가 다르다 —
   * 공통규약 1.5 「판매 · 정산 · 입금 3층」이 그것을 못박았고 시드도 그 구분을 지킨다.
   */
  income: {
    // 카드사는 영업일에만 정산한다. 시각은 08:40~09:50 사이에서 흔든다.
    // 수수료 0.5% — 연매출 3억 이하 영세가맹점 우대 수수료율이다(우리 페르소나는 8,800만).
    card: {
      label: '카드 정산', description: '신한카드', base: 132_000, spread: 0.15, fee: 0.005,
      timeFrom: '08:40:00', timeWindowMinutes: 70, category: 'CARD_SETTLEMENT',
    },
    // 배달 플랫폼은 주 단위로 묶어서 화요일에.
    // 중개이용료·결제정산이용료·배달비를 떼고 주문금액의 8할쯤 들어온다.
    platform: {
      label: '배달 정산', description: '우아한형제들', base: 508_000, spread: 0.10, fee: 0.20,
      dow: 2, time: '11:40:00', category: 'PLATFORM_SETTLEMENT',
    },
    // 사장님이 은행 가는 날. 주 1회 월요일. 현금은 중간에 떼는 곳이 없다.
    cash: {
      label: '현금 매출', description: '현금입금', base: 152_000, spread: 0.25, fee: 0,
      dow: 1, time: '15:05:00', category: 'CASH_SALES',
    },
  },

  /**
   * <b>상품 구성 — 월 단위 「상품별 매출현황」 인쇄물 한 장이 될 표다</b> (P1-22).
   *
   * <p><b>금액을 정하는 표가 아니다.</b> 그 달의 총매출은 매출성 입금(카드·배달·현금)을
   * {@code income.*.fee} 로 되돌려 환산한 값이고, 이 표가 정하는 것은 <b>그 총액을 어떻게
   * 나눌지</b>뿐이다. 매출 금액의 단일 출처는 계좌 입금이라는 규칙(ERD 1.9 「소비처와
   * 비소비처」)이 시드 안에서도 그대로여야 한다 — 여기에 절대액을 따로 적어 두면 통장 합계와
   * 인쇄물 합계가 서로 다른 매출을 말하게 된다.
   *
   * <pre>
   * label        상품명. 인쇄물 원문 자리이고 그대로 API 14.4 의 productName 이 된다
   * price        단가. 수량은 배분액 ÷ 단가로 역산하고 총매출은 수량 × 단가로 다시 맞춘다
   * weight       총매출 기준 비중. 서로 더해 1 이 아니어도 된다 — 비율로 정규화한다
   *              <b>0 이면 그 상품은 안 만들어진다</b>
   * trend        월 복리 변화율. 아래 주석 참고
   * discountRate 쿠폰·서비스로 깎이는 비율. 인쇄물의 할인·결제금액 열이 여기서 갈린다
   * </pre>
   *
   * <p><b>시나리오는 {@code trend} 에서 나온다.</b> 전체 매출은 완만히 주는데(−0.5%/월)
   * <b>무엇이 팔려서 줄었는지</b>가 이 기능이 답할 질문이다(SRS 7장 「매출 감소 시 원인 상품
   * 식별」). 그래서 딸기라떼를 계절 메뉴로 크게 빼고 아인슈페너를 신메뉴로 올렸다 —
   * 전 품목을 고르게 두면 구성비가 여섯 달 내내 같은 그림이라 볼 것이 없다.
   */
  products: {
    americanoIce:    { label: '아메리카노(ICE)',  price: 4_000, weight: 0.22, trend:  0.03, discountRate: 0.02 },
    americanoHot:    { label: '아메리카노(HOT)',  price: 4_000, weight: 0.11, trend: -0.05, discountRate: 0.02 },
    cafeLatte:       { label: '카페라떼',         price: 4_800, weight: 0.14, trend:  0.00, discountRate: 0.02 },
    vanillaLatte:    { label: '바닐라라떼',       price: 5_300, weight: 0.08, trend:  0.01, discountRate: 0.03 },
    cafeMocha:       { label: '카페모카',         price: 5_300, weight: 0.05, trend:  0.00, discountRate: 0.03 },
    einspanner:      { label: '아인슈페너',       price: 5_800, weight: 0.04, trend:  0.09, discountRate: 0.00 },
    grapefruitAde:   { label: '자몽에이드',       price: 6_000, weight: 0.06, trend:  0.05, discountRate: 0.03 },
    peachIcedTea:    { label: '복숭아아이스티',   price: 5_000, weight: 0.04, trend:  0.04, discountRate: 0.03 },
    strawberryLatte: { label: '딸기라떼',         price: 6_000, weight: 0.05, trend: -0.18, discountRate: 0.05 },
    greenTeaLatte:   { label: '녹차라떼',         price: 5_300, weight: 0.03, trend:  0.00, discountRate: 0.03 },
    croffle:         { label: '크로플',           price: 6_500, weight: 0.07, trend: -0.02, discountRate: 0.06 },
    cheesecake:      { label: '치즈케이크',       price: 6_800, weight: 0.06, trend: -0.03, discountRate: 0.06 },
    sandwich:        { label: '샌드위치',         price: 7_500, weight: 0.05, trend:  0.02, discountRate: 0.04 },
  },

  /** 재료비 — 주 2회(월·목). 매출이 늘면 원가도 는다. 거래처는 순서대로 돌려 쓴다. */
  materials: {
    label: '재료비', base: 300_000, spread: 0.15, days: [1, 4], time: '08:30:00',
    category: 'MATERIALS',
    vendors: ['씨제이프레시웨이', '대상베스트코', '푸드머스', '동서식품'],
  },

  /**
   * 고정비. {@code day} 는 달력 날짜, {@code shift} 는 휴일 보정 방향(+1 뒤로 · −1 앞으로).
   *
   * <b>한 날에 몰지 않는 것이 요점이다.</b> 처음에는 임차료·급여를 3~12일에 몰아
   * 뒀는데, 그러면 월 스윙이 190만이 되어 매달 잔액이 마이너스로 내려간다 — 공백 알림이
   * 상시 켜져 있으면 알림이 아니다. 5·10·15·18·20일로 흩어 각 구간 지출을 그 구간 수입과
   * 비슷하게 맞췄다.
   *
   * 남는 골이 급여일(10일)이고 평소에는 양수로 버틴다. 10일이 주말이면 급여만
   * <b>앞당겨져</b> 카드 정산 이틀치가 덜 들어온 상태로 맞는데, 2026년에 그런 달은
   * 5월과 10월 둘뿐이다(10월은 10/9 한글날이라 이틀이 밀린다).
   *
   * <p><b>15일에는 대출상환 46만이 있었다</b>(2026-09-15 제거). 싸피 금융망의 대출은
   * 원리금을 <b>날마다 조금씩</b> 빼 가는 방식이라, 대출 기능(API 16장)이 붙으면 실제
   * 상환 거래가 그 모양으로 들어온다 — 월 1회 46만을 시드가 따로 만들어 두면 같은 대출이
   * 두 벌이 되고 주기마저 서로 다르다. 대신 관리비·렌탈·기장료 세 줄로 <b>같은 날 같은
   * 금액</b>을 채웠다. 15일이 비면 10일 골과 18일 골 사이가 평평해져 월 스윙이 달라지는데,
   * 이 곡선은 그 모양 위에서 공백을 맞춰 둔 것이다.
   *
   * ⚠ 임대인과 직원은 <b>개인 이름</b>으로 찍힌다. 실제 통장이 그렇고, 규격이 그걸
   * 요구한다(`ai/evals/p1-01/적요_표기_목록.md`) — 적요에 카테고리 이름을 넣으면
   * 모델이 그대로 베껴 100점이 나오는데 아무것도 검증하지 못한다. 빠진 대출상환은 적요가
   * 「대출상환」이라 <b>이 규칙을 어기고 있던 유일한 줄</b>이기도 했다.
   */
  fixed: {
    insurance:       { label: '화재보험',   description: '삼성화재',          amount:    80_000, day:  3, shift: +1, time: '06:00:00', category: 'INSURANCE' },
    pos:             { label: 'POS 구독',   description: '포스뱅크',          amount:    30_000, day:  3, shift: +1, time: '06:00:00', category: 'SUBSCRIPTION' },
    rent:            { label: '임차료',     description: '김영자',            amount:   900_000, day:  5, shift: +1, time: '06:00:00', category: 'RENT' },
    payroll:         { label: '급여',       description: '박정호',            amount: 1_200_000, day: 10, shift: -1, time: '17:30:00', category: 'PAYROLL' },
    socialInsurance: { label: '4대보험',    description: '국민건강보험공단',  amount:   210_000, day: 10, shift: +1, time: '06:00:00', category: 'SOCIAL_INSURANCE' },
    managementFee:   { label: '관리비',     description: '무등빌딩관리단',    amount:   260_000, day: 15, shift: +1, time: '06:00:00', category: 'RENT' },
    rental:          { label: '기기 렌탈',  description: 'SK매직',            amount:   120_000, day: 15, shift: +1, time: '06:00:00', category: 'OTHER_EXPENSE' },
    bookkeeping:     { label: '기장료',     description: '세무법인다연',      amount:    80_000, day: 15, shift: +1, time: '06:00:00', category: 'OTHER_EXPENSE' },
    power:           { label: '전기',       description: '한국전력공사',      amount:   240_000, day: 18, shift: +1, time: '06:00:00', category: 'UTILITIES' },
    gas:             { label: '가스',       description: '해양도시가스',      amount:   120_000, day: 18, shift: +1, time: '06:00:00', category: 'UTILITIES' },
    telecom:         { label: '통신',       description: '케이티',            amount:    70_000, day: 18, shift: +1, time: '06:00:00', category: 'TELECOM' },
    advertising:     { label: '광고',       description: '우아한형제들',      amount:   140_000, day: 20, shift: +1, time: '06:00:00', category: 'ADVERTISING' },
  },

  /**
   * 주급. 인건비 210만을 정규직 120만 + 주급 21만×4.3 으로 <b>쪼갠 것은 의도다.</b>
   * 한 날에 210만이 몰리면 월 스윙이 190만이 되는데, 그러면 10월과 평소 달의 차이(카드
   * 이틀치 39만)가 스윙에 묻혀 "과거는 안 뚫리고 10월만 빈다"가 성립하지 않는다.
   * 카페에서 정규직 월급과 알바 주급을 따로 주는 것은 실제로 흔하다.
   */
  weekly: {
    partTime: { label: '알바 주급', description: '최민서', amount: 210_000, dow: 5, shift: -1, time: '17:30:00', category: 'PAYROLL' },
  },

  /**
   * 부가세 — 일반과세 개인사업자는 <b>연 4회</b> 낸다. 처음에는 예정고지 둘만 넣어서
   * 7월 확정신고가 통째로 빠져 있었다(6개월 창에 1건뿐이던 원인이고, 그만큼 곡선이
   * 후하게 나왔다).
   *
   *   1월 · 7월   확정신고   당기 납부세액 − 예정고지분
   *   4월 · 10월  예정고지   직전 과세기간 납부세액의 1/2
   *
   * 반기 매출 4,230만 · 매입 2,490만 → 납부세액 174만이라 네 번 다 87만 언저리다.
   * <b>10월 건이 곡선의 공백을 만든다.</b> 지어낸 이벤트가 아니라 세법이 정한 날짜다.
   *
   * ⚠ 6개월 창에는 두 번만 들어와 반복 패턴(최소 3회)에는 못 미친다. 이건 결함이 아니다 —
   * ERD 가 `PLANNED_TRANSACTION.source_type` 에 TAX 를 두고 `TAX_SCHEDULE` 을 따로 정의했다.
   */
  tax: {
    label: '부가가치세', description: '부가가치세', amount: 870_000,
    day: 25, months: [1, 4, 7, 10], shift: +1, time: '06:00:00', category: 'TAX',
  },

  /**
   * <b>주기가 없는 거래.</b> 이 표의 목적은 하나다 — <b>잔여(residual) 축을 만드는 것</b>.
   *
   * <p>잔여는 {@code 실제 − 예정 거래가 설명한 것}이다. 그런데 이 시드는 모든 거래가
   * 주기를 갖도록 만들어져 있어서 <b>탐지기가 전부 잡아간다.</b> 2026-09-13 실측에서
   * 거래 290건 중 288건이 확정 패턴 18개로 설명됐고, 남은 잔여는 부가세 2건(금액의 2.3%)
   * 뿐이었다. 모델은 그 2건을 입력으로 받는다 — <b>맞힐 것이 없다.</b>
   *
   * <p>그래서 「예측할 수 없는 몫」을 일부러 만든다. 실제 카페 통장에는 이런 것들이
   * 섞이고, 사장님도 미리 알 수 없다. 지어낸 잡음이 아니라 <b>빠져 있던 현실</b>이다.
   *
   * <h2>세 가지 방식으로 탐지를 벗어난다</h2>
   *
   * <p>아무 거래나 더한다고 잔여가 되지 않는다. 규칙적이면 탐지기가 잡아가고, 잡히면
   * 예정 거래로 투영돼 다시 잔여에서 빠진다({@code RecurringPatternDetector}).
   *
   * <pre>
   * 적요 흩뿌리기   거래처가 매번 달라 그룹마다 표본이 MIN_SAMPLES(3) 에 못 미친다
   * 간격 흔들기     날짜가 무작위라 gap 의 (표준편차/평균) 이 0.25 를 넘어 INTERVAL 탈락
   *                 (WEEKLY 는 간격 7 고정 · MONTHLY 는 같은 날 ±2 · BUSINESS_DAILY 는 영업일 60%)
   * 드물게 일으키기 6개월에 몇 건이라 애초에 그룹이 서지 않는다
   * </pre>
   *
   * <p><b>확률을 낮추는 것으로 항목을 끌 수 있다</b> — {@code probability: 0} 이면 안 만든다.
   *
   * <p>⚠ 적요는 {@code GroupKey.normalize} 가 숫자·공백·구분자를 지우고 비교한다.
   * 「다이소 0912」와 「다이소」는 <b>같은 그룹</b>이다. 흩뿌리려면 단어 자체가 달라야 한다.
   *
   * <p>매출 연동(성장률)은 걸지 않는다. 창 6개월에 −3% 라 모양에 영향이 없고,
   * 수리·환불은 애초에 매출에 비례하지 않는다.
   */
  irregular: {
    // 컵·빨대·냅킨·세제·포장재. 사장님이 필요할 때 가까운 데서 산다 — 그래서 거래처가 흩어진다.
    supplies: {
      label: '소모품', flow: 'WITHDRAWAL', probability: 0.60,
      descriptions: ['다이소', '쿠팡', '이마트에브리데이', '홈플러스', '오피스디포'],
      min: 8_000, max: 55_000, timeFrom: '13:00:00', timeWindowMinutes: 240,
      category: 'SUPPLIES', skipClosed: true,
    },
    // 에스프레소 머신 AS · 정수기 필터. 6개월에 두세 건이라 그룹이 서지 않는다.
    repair: {
      label: '수리·AS', flow: 'WITHDRAWAL', probability: 0.015,
      descriptions: ['커피동아', '청호나이스'],
      min: 40_000, max: 180_000, timeFrom: '11:00:00', timeWindowMinutes: 180,
      category: 'OTHER_EXPENSE', skipClosed: true,
    },
    // 손님 환불. 적요는 늘 같지만 날짜가 무작위라 주기로 분류되지 않는다.
    refund: {
      label: '카드 취소', flow: 'WITHDRAWAL', probability: 0.03,
      descriptions: ['카드취소'],
      min: 3_000, max: 25_000, timeFrom: '16:00:00', timeWindowMinutes: 200,
      category: 'OTHER_EXPENSE', skipClosed: true,
    },
    // 기프티콘(카카오 선물하기 등) 정산. **입금 축의 잔여가 이것뿐이다** — 없으면
    // 모델이 보는 입금 잔여가 180일 내내 0 이 되고, 두 축 중 하나가 통째로 빈다.
    voucher: {
      label: '기프티콘 정산', flow: 'DEPOSIT', probability: 0.25,
      descriptions: ['쿠프마케팅', '스마일콘'],
      min: 18_000, max: 120_000, timeFrom: '10:20:00', timeWindowMinutes: 120,
      category: 'PLATFORM_SETTLEMENT', skipClosed: false,
    },
  },

  /**
   * 사장님 개인 돈 메우기. {@code unit} 단위로 올려 넣는다 — 1원까지 딱 맞춰 넣는 사람은 없다.
   * 적요는 사장님 이름이라 계정 이름과 같이 움직여야 한다.
   */
  funding: {
    label: '사장님 입금', description: '정유선', floor: 0, headroom: 400_000,
    unit: 1_000_000, time: '07:00:00', category: 'OWNER_FUNDING',
  },

  /** 공백을 어디에 둘지. 아래 {@link ANCHOR} 주석이 왜 달력이 아니라 기준일 기준인지 설명한다. */
  anchor: { withinDays: 30, lowest: -250_000, tolerance: 150_000 },

  /** 시작 잔액 격자 탐색 범위. 넓히면 느려지고 좁히면 답을 놓친다. */
  search: { max: 8_000_000, step: 50_000 },
};

/**
 * 항목을 <b>늘리고 줄일 수 있는</b> 표. 지정하면 거기 있는 키가 곧 있는 항목 전부다.
 *
 * <p>깊은 병합만 하면 <b>지운 항목이 기본값에서 되살아난다.</b> 고정비를 하나 없애고 곡선을
 * 맞췄는데 실제로 돌리면 그 고정비가 그대로 나가는 것이라, 조용히 틀리는 쪽이다.
 *
 * <p>{@code income} 은 여기 없다 — {@code deposits()} 가 card·platform·cash 를 이름으로
 * 읽고 난수 스트림도 그 이름에 매여 있어서, 줄을 늘리거나 줄일 수 없다.
 *
 * <p>{@code products} 는 여기 있다. 메뉴에서 뺀 상품이 기본값에서 되살아나면 인쇄물에 없던
 * 줄이 구성비 분모에 들어간다 — 14.4 가 「같은 기간 재등록은 통째로 교체」인 것과 같은 이유다.
 */
const REPLACING_TABLES = ['fixed', 'weekly', 'irregular', 'products'];

/**
 * 부분 지정을 기본값에 얹는다. 평범한 객체는 파고들고 <b>배열은 통째로 갈아 끼운다</b> —
 * 거래처 목록이나 휴업일을 줄이려는데 기본값이 남아 있으면 고친 티가 안 난다.
 *
 * <p>{@link REPLACING_TABLES} 는 표 단위로 갈아 끼운다. 각 줄은 같은 이름의 기본값 위에
 * 얹히므로, 금액 하나만 보낸 줄도 나머지 칸은 그대로다.
 *
 * <p>{@code undefined} 와 {@code null} 은 "지정하지 않음"이다. 화면에서 빈 칸으로 온 값이
 * 기본값을 지우지 않게 한다.
 */
export function resolveTuning(overrides) {
  const merge = (base, over) => {
    if (over === undefined || over === null) return base;
    if (Array.isArray(base) || Array.isArray(over)) return over;
    if (typeof base !== 'object' || typeof over !== 'object') return over;
    const out = { ...base };
    for (const [key, value] of Object.entries(over)) out[key] = merge(base[key], value);
    return out;
  };

  const resolved = merge(DEFAULT_TUNING, overrides);

  for (const table of REPLACING_TABLES) {
    const given = overrides?.[table];
    if (typeof given !== 'object' || given === null) continue;
    resolved[table] = Object.fromEntries(
      Object.entries(given).map(([key, row]) => [key, merge(DEFAULT_TUNING[table][key] ?? {}, row)]),
    );
  }
  return resolved;
}

/**
 * <b>휴일 보정 방향을 거래처별로 모은다</b> — 백엔드에 알려 줄 값이다.
 *
 * <p>`shift` 는 이 파일이 거래를 만들 때 쓰는 방향이다(+1 뒤로 · −1 앞으로). 그런데
 * <b>탐지기는 그 방향을 이력에서 못 읽을 수 있다.</b> 월 반복은 기준일이 휴일에 걸린 회차가
 * 있으면 읽어내지만(`fitMonthly`), 주 반복은 그 추론 자체를 타지 않고 기본값
 * `NEXT_BUSINESS_DAY` 로 떨어진다.
 *
 * <p>실측(기준일 2026-09-17)에서 그것이 드러났다. 알바 주급은 매주 금요일인데 180일 창에
 * <b>금요일 공휴일이 하나도 없어</b> 증거가 0 이었다 — 25회가 전부 금요일이다. 그래서
 * 한글날(10/09) 회차를 탐지는 10/12 로 밀고 생성기는 10/08 로 당겨, 저점일에서 21만원이
 * 빠졌다. 월급(박정호)은 5/10 이 일요일이라 증거가 있어 맞았다.
 *
 * <p><b>그래서 아는 쪽이 알려준다</b> — 휴무일 등록(`registerClosedDays`)과 같은 논리다.
 * 실제 서비스에서는 사장님이 반복 패턴 화면에서 고르는 값이고, 여기서는 생성기가 자기
 * `shift` 를 그대로 넘긴다. 승인 요청의 필드가 전부 선택이라 한 번에 실어 보낸다(API 9.3).
 *
 * <p>키는 <b>적요</b>다 — 탐지기가 표본 적요에서 패턴 이름을 뽑으므로(`displayName`)
 * 후보의 `name` 과 여기 키가 만난다. 분류로 가르지 않는 이유는 CSV 로 들어온 거래가
 * `UNCLASSIFIED` 라 패턴의 `category_id` 가 비어 있기 때문이다.
 *
 * @returns `{ 적요: 'PREVIOUS_BUSINESS_DAY' | 'NEXT_BUSINESS_DAY' }`. `shift` 가 없는 항목은 빠진다
 */
export function holidayRules(tuning) {
  const t = resolveTuning(tuning);
  const rules = {};
  const put = (spec) => {
    if (!spec?.description || !spec.shift) return;
    rules[spec.description] = spec.shift < 0 ? 'PREVIOUS_BUSINESS_DAY' : 'NEXT_BUSINESS_DAY';
  };

  for (const spec of Object.values(t.fixed ?? {})) put(spec);
  for (const spec of Object.values(t.weekly ?? {})) put(spec);
  put(t.tax);

  // 정산 3종은 `shift` 칸이 없다 — `deposits()` 가 +1 을 직접 박아 쓴다. 여기 적어 두지 않으면
  // 배달 정산이 광고비(우아한형제들)와 적요가 같아서 <b>우연히</b> 값을 받는다.
  for (const spec of Object.values(t.income ?? {})) {
    if (spec?.description) rules[spec.description] = 'NEXT_BUSINESS_DAY';
  }
  return rules;
}

/** 예전 이름. 사업장 정보를 이 이름으로 읽는 곳이 있다. */
export const PERSONA = {
  business: DEFAULT_TUNING.business,
  monthlyGrowth: DEFAULT_TUNING.monthlyGrowth,
};

/**
 * 시드 계정. <b>이메일 하나로 금융망 userKey 가 정해진다</b> — 백엔드(`FinUserKeyService`)도
 * 같은 방식으로 찾으므로, 이 이메일로 만든 계좌가 그대로 이 사용자 것이 된다.
 *
 * <p>{@code name} 은 사장님 본인 이름이라 메우기 적요({@code funding.description})와
 * 같아야 통장에서 "내가 넣은 돈"으로 읽힌다. 둘을 따로 둔 것은 일부러다 —
 * 임대인·직원도 개인 이름이라, 한쪽만 바꿔야 하는 경우가 생긴다.
 */
export const DEFAULT_OWNER = {
  email: 'owner@example.test',
  password: '__DEMO_PASSWORD__',
  name: '정유선',
  phone: '010-0000-0000',
};

/**
 * 세울 계좌들. <b>계좌번호를 적지 않는다</b> — 없으면 싸피은행(999)에 만든다.
 *
 * <p>{@code main} 인 계좌 하나가 CSV 와 잔액 맞추기를 받는다. {@code verify:false} 는
 * 1원 인증을 일부러 남겨 두는 것이다 — 인증 화면(SC-01-4)에 시연할 대상이 있어야 한다.
 */
export const DEFAULT_ACCOUNTS = [
  // 잔액의 출처. 거래 300여 건은 CSV 가 넣고 이 계좌는 잔액만 준다 — 그 값이 예측 곡선의
  // 출발점이다(ERD: FORECAST_RUN.opening_balances "실행 당시 계좌별 시작 잔액").
  { alias: '주거래 사업용', primary: true, verify: true, main: true },
  { alias: '보조 통장', primary: false, verify: false, main: false },
];

// ── 거래 생성 ─────────────────────────────────────────────────────────────────

const row = (date, time, description, flow, amount, category) => ({
  date: ymd(date),
  time,
  description,
  flow,
  amount,
  category,
});

/** 월 기준 성장 배수. from 으로부터 몇 달 지났는지로 계산한다. */
function growth(d, from, monthlyGrowth) {
  const months =
    (d.getUTCFullYear() - from.getUTCFullYear()) * 12 + (d.getUTCMonth() - from.getUTCMonth());
  return (1 + monthlyGrowth) ** months;
}

function deposits(from, to, t) {
  const out = [];
  const closed = new Set(t.closedDays);
  const card = streamRandom('card');
  const platform = streamRandom('platform');
  const cash = streamRandom('cash');

  for (const d of eachDay(from, to)) {
    const g = growth(d, from, t.monthlyGrowth);

    // 카드 매출대금 — 카드사는 <b>영업일에만</b> 정산한다. 공휴일 매출은 다음 영업일에
    // 얹혀 들어오므로 연휴 뒤 첫 건이 그만큼 크다.
    //
    // 처음에는 "연휴엔 가게를 닫으니 매출도 없다"고 보고 이월을 뺐는데, 카페가 어린이날·
    // 개천절에 문을 닫지는 않는다. 명절(추석·설)만 닫고 나머지 공휴일은 연다 —
    // 그래야 연휴가 곡선에 흔적을 남기고 SEASONAL_FACTOR 가 배울 것이 생긴다.
    //
    // 시각도 흔든다. 6개월 내내 09:12:00 이면 사람이 만든 티가 나고, 예측·탐지 양쪽에
    // 실제보다 쉬운 문제를 준다(예측 담당 지적).
    if (isBusinessDay(d)) {
      const carried = closedSalesDaysBefore(d, closed);
      const spec = t.income.card;
      out.push(
        row(d, settlementTime(card, spec), spec.description, 'DEPOSIT',
          jitter(card, spec.base * g * (1 + carried), spec.spread), spec.category));
    }

    // 배달 플랫폼 정산 — 주 단위로 묶여서 화요일에. 휴일이면 다음 영업일.
    // 대상 주에 쉰 날이 있으면 그만큼 적게 들어온다.
    if (dow(d) === t.income.platform.dow) {
      const spec = t.income.platform;
      const paidOn = shiftToBusinessDay(d, +1);
      out.push(
        row(paidOn, spec.time, spec.description, 'DEPOSIT',
          jitter(platform, spec.base * g * weekOpenRatio(d, closed), spec.spread), spec.category));
    }

    // 현금 매출 입금 — 사장님이 은행 가는 날. 주 1회 월요일.
    if (dow(d) === t.income.cash.dow) {
      const spec = t.income.cash;
      const paidOn = shiftToBusinessDay(d, +1);
      out.push(
        row(paidOn, spec.time, spec.description, 'DEPOSIT',
          jitter(cash, spec.base * g * weekOpenRatio(d, closed), spec.spread), spec.category));
    }
  }
  return out;
}

/**
 * 이 영업일 직전에 <b>영업은 했지만 정산이 안 된 날</b>이 몇이나 되나.
 * 그만큼 오늘 정산에 얹힌다 — 주말 뒤 월요일이 평소의 3배가 되는 이유다.
 */
function closedSalesDaysBefore(d, closed) {
  let carried = 0;
  for (let cursor = addDays(d, -1); !isBusinessDay(cursor); cursor = addDays(cursor, -1)) {
    if (!closed.has(ymd(cursor))) carried += 1;
    if (carried > 9) break;
  }
  return carried;
}

/** 정산 시각. 08:40~09:50 사이에서 흔든다 — 고정 시각은 사람이 만든 티가 난다. */
function settlementTime(rnd, spec) {
  const [h, m] = String(spec.timeFrom).split(':').map(Number);
  const minutes = h * 60 + m + Math.floor(rnd() * spec.timeWindowMinutes);
  const p = (n) => String(n).padStart(2, '0');
  return `${p(Math.floor(minutes / 60) % 24)}:${p(minutes % 60)}:00`;
}

/**
 * 직전 7일 중 <b>문을 연</b> 비율. 명절이 낀 주는 매출이 그만큼 적다.
 *
 * <p>전에는 영업일(주말·공휴일 제외)로 셌는데, 카페는 주말에도 열고 개천절에도 연다.
 * 그렇게 두면 배달·현금 매출이 주말마다 반토막 나는 이상한 데이터가 된다.
 */
function weekOpenRatio(d, closed) {
  let open = 0;
  for (let i = 1; i <= 7; i += 1) if (!closed.has(ymd(addDays(d, -i)))) open += 1;
  return open / 7;
}

function withdrawals(from, to, t) {
  const out = [];
  const material = streamRandom('material');
  let vendorIndex = 0;

  // 금액 0 인 항목은 안 만든다 — 화면에서 항목을 끄는 방법이 그것이다.
  const on = (spec) => spec && spec.amount > 0;

  for (const d of eachDay(from, to)) {
    // 재료비 — 주 2회(월·목). 매출이 늘면 원가도 는다.
    const mat = t.materials;
    if (mat.days.includes(dow(d)) && mat.base > 0 && mat.vendors.length) {
      const paidOn = shiftToBusinessDay(d, +1);
      const vendor = mat.vendors[vendorIndex % mat.vendors.length];
      vendorIndex += 1;
      out.push(
        row(paidOn, mat.time, vendor, 'WITHDRAWAL',
          jitter(material, mat.base * growth(d, from, t.monthlyGrowth), mat.spread), mat.category));
    }

    const day = d.getUTCDate();
    const month = d.getUTCMonth() + 1;

    // 고정비. 선언 순서대로 돈다 — 같은 날 같은 시각인 항목(18일 전기·가스·통신)의
    // 줄 순서가 그 순서로 정해진다.
    for (const spec of Object.values(t.fixed)) {
      if (!on(spec) || day !== spec.day) continue;
      out.push(row(shiftToBusinessDay(d, spec.shift), spec.time, spec.description,
        'WITHDRAWAL', spec.amount, spec.category));
    }

    for (const spec of Object.values(t.weekly)) {
      if (!on(spec) || dow(d) !== spec.dow) continue;
      out.push(row(shiftToBusinessDay(d, spec.shift), spec.time, spec.description,
        'WITHDRAWAL', spec.amount, spec.category));
    }

    const tax = t.tax;
    if (on(tax) && day === tax.day && tax.months.includes(month)) {
      out.push(row(shiftToBusinessDay(d, tax.shift), tax.time, tax.description,
        'WITHDRAWAL', tax.amount, tax.category));
    }
  }
  return out;
}

/**
 * 주기 없는 거래 ({@link DEFAULT_TUNING.irregular}). <b>모델이 맞혀야 할 몫이다.</b>
 *
 * <p>날마다 항목별로 주사위를 굴린다. 거래처와 금액은 <b>맞든 안 맞든 매일 뽑는다</b> —
 * 확률만 바꿨을 때 이후 날짜의 거래처·금액이 통째로 밀리는 것을 막는다. 시드 콘솔에서
 * 확률 슬라이더를 움직이면 "붙었다 떨어지는 것"만 달라져야지 6개월치가 다른 데이터가
 * 되면 조정이 불가능하다.
 *
 * <p>스트림 이름에 항목 키를 넣어 항목끼리도 독립시킨다. 하나를 끄거나 켜도 나머지는
 * 그대로다 (`streamRandom` 주석의 "스트림 이름을 바꾸지 않는다"와 같은 이유).
 */
function irregularFlows(from, to, t) {
  const out = [];
  const closed = new Set(t.closedDays);

  for (const [key, spec] of Object.entries(t.irregular ?? {})) {
    if (!spec || !(spec.probability > 0) || !spec.descriptions?.length) continue;
    const rnd = streamRandom(`irregular:${key}`);

    for (const d of eachDay(from, to)) {
      const hit = rnd() < spec.probability;
      const pick = rnd();
      const size = rnd();
      const when = settlementTime(rnd, spec);
      if (!hit) continue;
      if (spec.skipClosed && closed.has(ymd(d))) continue;

      const description = spec.descriptions[Math.floor(pick * spec.descriptions.length)];
      const amount = Math.round((spec.min + size * (spec.max - spec.min)) / 100) * 100;
      out.push(row(d, when, description, spec.flow, amount, spec.category));
    }
  }
  return out;
}

const byDateTime = (a, b) => (a.date + a.time).localeCompare(b.date + b.time);

/**
 * 주계좌의 전체 흐름. `to` 까지 **포함**해 만든다 — 과거(CSV)와 미래(예측 검증)를
 * 같은 규칙으로 뽑아야 "우리가 의도한 공백"과 "엔진이 그린 공백"을 비교할 수 있다.
 */
export function primaryFlows(from, to, tuning) {
  const t = resolveTuning(tuning);
  return [...deposits(from, to, t), ...withdrawals(from, to, t), ...irregularFlows(from, to, t)]
    .filter((r) => r.date >= ymd(from) && r.date <= ymd(to))
    .sort(byDateTime);
}

/** 잔액을 누적해 붙인다. 시작 잔액은 밖에서 준다. */
export function withBalances(rows, opening) {
  let balance = opening;
  return rows.map((r) => {
    balance += r.flow === 'DEPOSIT' ? r.amount : -r.amount;
    return { ...r, balanceAfter: balance };
  });
}

/**
 * 명세 7.4 의 CSV. 헤더 이름과 날짜 형식은 {@code CsvTransactionParser} 가 정한 것을 따른다 —
 * <b>날짜는 {@code yyyy-MM-dd HH:mm:ss} 하나뿐이다.</b> 헤더 별칭은 열려 있어도 형식은 아니다.
 */
export function toCsv(rows) {
  const quote = (s) => (/[",\n]/.test(s) ? `"${s.replaceAll('"', '""')}"` : s);
  const lines = ['거래일시,적요,입금액,출금액,거래후잔액'];
  for (const r of rows) {
    lines.push([
      `${r.date} ${r.time}`,
      quote(r.description),
      r.flow === 'DEPOSIT' ? r.amount : '',
      r.flow === 'WITHDRAWAL' ? r.amount : '',
      r.balanceAfter ?? '',
    ].join(','));
  }
  return `${lines.join('\n')}\n`;
}

/**
 * 정답 카테고리표. 업로드 CSV 와 <b>같은 네 칸에 카테고리 한 칸을 더한 모양</b>이라
 * `(거래일시, 적요, 입금액, 출금액)` 으로 저장된 거래에 그대로 조인된다 — 중복 판정 키와
 * 같은 튜플이다.
 *
 * <p>생성기는 각 거래를 어떤 카테고리로 만들었는지 알고 있는데 CSV 에 그 칸이 없어
 * 업로드 경계에서 버려진다. 분류 채점 도구(S15P21C105-93)가 정답 세트를 손으로 라벨링하는
 * 대신 이걸 쓰면 된다.
 *
 * <p>⚠ <b>순환논법에 주의한다.</b> 우리가 만든 적요를 우리가 만든 라벨로 채점하는 것이라
 * "분류기가 잘한다"의 근거로는 약하다. 고쳤는데 안 깨졌나 보는 <b>회귀</b> 용도다.
 */
export function toLabelCsv(rows) {
  const quote = (s) => (/[",\n]/.test(s) ? `"${s.replaceAll('"', '""')}"` : s);
  const lines = ['거래일시,적요,입금액,출금액,카테고리'];
  for (const r of rows) {
    lines.push([
      `${r.date} ${r.time}`,
      quote(r.description),
      r.flow === 'DEPOSIT' ? r.amount : '',
      r.flow === 'WITHDRAWAL' ? r.amount : '',
      r.category,
    ].join(','));
  }
  return `${lines.join('\n')}\n`;
}

/** 날짜별 종가. 곡선을 눈으로 확인하고 공백을 판정하는 데 쓴다. */
export function dailyClosing(rowsWithBalance) {
  const out = new Map();
  for (const r of rowsWithBalance) out.set(r.date, r.balanceAfter);
  return out;
}

// ── 상품별 매출 (P1-22) ───────────────────────────────────────────────────────

/**
 * 매출성 입금 → 채널. 매출성 입금 4종(API 8장) 중 이 시드가 만드는 셋만 있다 —
 * {@code TRANSFER_SALES} 는 생성기에 없다.
 *
 * <p>⚠ 기프티콘 정산({@code irregular.voucher})도 {@code PLATFORM_SETTLEMENT} 라 여기 섞인다.
 * <b>일부러 빼지 않았다</b> — 기프티콘도 상품이 팔린 것이고, 빼면 통장에는 있는 매출이
 * 인쇄물에서 사라진다.
 */
const SALES_CHANNEL = {
  CARD_SETTLEMENT: 'card',
  PLATFORM_SETTLEMENT: 'platform',
  CASH_SALES: 'cash',
};

/**
 * 시드가 넣는 출처. <b>전부 {@code MANUAL} 이다</b> — 스크립트가 직접 넣은 것이 사실이고,
 * 이 칸이 있는 이유가 「어떻게 들어왔나」를 남기는 것이라서다.
 *
 * <p>한때 {@code OCR} 로 뒀다. "사장님이 매달 인쇄물을 찍어 올려 왔다"로 보이게 하려던
 * 것인데 <b>이 값은 어느 응답에도 안 나간다</b> — 14.4 등록 응답에도 14.5 구성비·추이에도
 * 없어서(`ProductSalesDtos`) 화면이 읽을 방법이 없다. 얻을 것이 없는 대신 잃을 것은 있었다:
 * 이 스키마에서 {@code OCR} 은 <b>「모델이 읽은 그대로, 아무도 손대지 않았다」</b>는 뜻이라
 * (14.6 이 사람 손이 닿은 행을 {@code OCR_CORRECTED} 로 올린다), 모델이 본 적 없는 65행이
 * 그 버킷에 들어가면 나중에 <b>실사용 정확도가 거짓으로 오른다</b>(구현현황 §3.26).
 *
 * <p>화면에 「출처」 뱃지가 생겨 과거 달이 "수기 입력"으로 보이는 것이 걸리면 그때 이 한 줄을
 * 되돌린다. 없는 화면을 위해 지금 사실이 아닌 값을 심어 둘 일은 아니다(2026-09-15).
 */
const PRODUCT_SALES_SOURCE_TYPE = 'MANUAL';

/**
 * 월 단위 상품별 매출 (API 14.4). {@link buildPrimary} 가 만든 <b>과거 거래에서</b> 뽑는다 —
 * 판매 층을 따로 난수로 만들면 통장 합계와 인쇄물 합계가 서로 다른 매출을 말하게 된다.
 *
 * <p><b>창에 통째로 들어온 달만</b> 만든다. 인쇄물은 달이 끝나야 뽑고, 반달치를 넣으면
 * 기간 추이에서 그 달만 반토막으로 보인다 — 구성비는 비율이라 멀쩡한데 14.5 의 합계는
 * 절대액이라 그렇다. 180일 창이면 대개 다섯 달이 남는다.
 *
 * <p>금액은 <b>정수로 딱 맞춘다.</b> 14.4 는 네 값이 모두 있을 때
 * {@code 총매출 − 할인 = 공급가 + 부가세} 를 검산하고, 1원이라도 어긋나면 그 기간이 통째로
 * {@code 400} 이다.
 *
 * @param rows 과거 구간 거래. {@code flow}·{@code category}·{@code date}·{@code amount} 를 읽는다
 * @return 14.4 요청 본문 그대로인 객체 배열
 */
export function productSalesPeriods({ rows, from, asOf, tuning }) {
  const t = resolveTuning(tuning);
  const menu = Object.entries(t.products ?? {})
    .filter(([, item]) => item?.weight > 0 && item?.price > 0);
  if (!menu.length) return [];

  // 달마다 정산 전 총매출. 통장에 찍힌 금액은 이미 수수료를 뗀 뒤라 되돌려 놓는다.
  const grossByMonth = new Map();
  for (const r of rows) {
    const channel = SALES_CHANNEL[r.category];
    if (!channel || r.flow !== 'DEPOSIT') continue;
    const fee = Math.min(0.9, Math.max(0, t.income[channel]?.fee ?? 0));
    const month = r.date.slice(0, 7);
    grossByMonth.set(month, (grossByMonth.get(month) ?? 0) + r.amount / (1 - fee));
  }

  const windowFrom = ymd(from);
  const today = ymd(asOf);
  const rnd = streamRandom('products');
  const out = [];

  for (const [month, monthGross] of [...grossByMonth.entries()].sort()) {
    const [year, index] = month.split('-').map(Number);
    const periodStart = `${month}-01`;
    // Date.UTC 의 0일은 그 전달 말일이다. 28·30·31 을 직접 세지 않는다.
    const periodEnd = ymd(new Date(Date.UTC(year, index, 0)));
    if (periodStart < windowFrom || periodEnd >= today || !(monthGross > 0)) continue;

    // 첫 달을 0 으로 두고 복리로 민다. 창이 옮겨 가도 상대적인 모양은 그대로다.
    const age = out.length;
    const weights = menu.map(([, item]) => item.weight * (1 + (item.trend ?? 0)) ** age);
    const total = weights.reduce((sum, w) => sum + w, 0);

    const items = menu.map(([, item], at) => {
      const quantity = Math.max(1, Math.round((monthGross * (weights[at] / total)) / item.price));
      const grossAmount = quantity * item.price;
      // 할인율은 달마다 흔든다. 여섯 달 내리 같은 비율이면 사람이 만든 티가 난다.
      const discountAmount = Math.round(grossAmount * (item.discountRate ?? 0) * (0.6 + rnd() * 0.8));
      const billedAmount = grossAmount - discountAmount;
      const vatAmount = Math.round(billedAmount / 11);
      return {
        productName: item.label,
        quantity,
        grossAmount,
        discountAmount,
        netAmount: billedAmount - vatAmount,
        vatAmount,
        billedAmount,
      };
    });

    const sum = (field) => items.reduce((acc, it) => acc + it[field], 0);
    out.push({
      periodStart,
      periodEnd,
      sourceType: PRODUCT_SALES_SOURCE_TYPE,
      // 인쇄물 맨 아래 합계 행. 항목 합 그대로라 서버 검산은 늘 MATCHED 다 — 시드가 만든
      // 인쇄물은 잘리지 않는다. 실물은 다섯 장 중 한 장꼴로 어긋나고 그것도 정상이다(14.4).
      checksum: {
        quantity: sum('quantity'),
        grossAmount: sum('grossAmount'),
        billedAmount: sum('billedAmount'),
      },
      items,
    });
  }
  return out;
}

// ── 공백 설계 ─────────────────────────────────────────────────────────────────

/**
 * 시작 잔액을 역산하는 기준점.
 *
 * 발표일(2026-09-28) 기준 D+10 인 <b>10/8(목)</b> 급여일에 25만원이 비게 맞춘다.
 * 10일이 토요일이고 그 앞 10/9 가 한글날이라 급여가 이틀 앞당겨지는 날이다 —
 * 이 시나리오에서 가장 설명하기 좋은 공백이라 여기를 골랐다.
 */
/**
 * 과거에는 잔액이 바닥나면 사장님이 개인 돈을 넣어 막는다. 계좌가 마이너스로 내려가면
 * 자동이체가 실패하므로 실제로 그렇게 한다 — 입금 카테고리에 {@code OWNER_FUNDING} 이
 * 있는 이유가 이것이다.
 *
 * <b>미래에는 그럴 수 없어서 공백이 그대로 드러난다.</b> 이 비대칭이 서비스가 파는 것이라,
 * 메우기는 기준일 <b>전</b>에만 적용한다.
 *
 * 부수 효과로 시작 잔액이 곡선의 수준을 결정하지 않게 된다 — 첫 메우기 이후로는
 * 바닥값이 수준을 잡는다. 그래서 아래 solveOpeningBalance 를 더 쓰지 않는다.
 */
export function patchWithOwnerFunding(rows, opening, { floor, headroom, until, spec = DEFAULT_TUNING.funding }) {
  const out = [];
  let balance = opening;
  const unit = spec.unit > 0 ? spec.unit : 1;

  for (const r of rows) {
    const delta = r.flow === 'DEPOSIT' ? r.amount : -r.amount;
    if (r.date < until && balance + delta < floor) {
      // 사장님은 100만원 단위로 넣는다. 1원 단위로 딱 맞춰 넣는 사람은 없다.
      const need = floor + headroom - (balance + delta);
      const amount = Math.ceil(need / unit) * unit;
      out.push({ ...row(parse(r.date), spec.time, spec.description, 'DEPOSIT', amount, spec.category) });
      balance += amount;
    }
    out.push(r);
    balance += delta;
  }
  return out;
}

/**
 * 주계좌 전체를 만든다 — 과거(CSV 로 넣을 것)와 미래(예측이 맞혀야 할 것)를 한 번에.
 *
 * 시작 잔액은 페르소나 상수다. 메우기가 수준을 잡으므로 이 값은 첫 달에만 영향을 준다.
 */
export function buildPrimary({ from, asOf, to, opening = 1_800_000, tuning }) {
  const t = resolveTuning(tuning);
  const raw = primaryFlows(from, to, t);
  const rows = patchWithOwnerFunding(raw, opening, {
    floor: t.funding.floor,
    headroom: t.funding.headroom,
    until: ymd(asOf),
    spec: t.funding,
  });
  return { opening, rows: withBalances(rows, opening) };
}

/**
 * 공백을 어디에 둘지. <b>달력이 아니라 기준일 기준이다.</b>
 *
 * 처음에는 {@code {from:'2026-10-01', to:'2026-10-31'}} 로 달력 10월에 박아 뒀는데,
 * 12월에 돌려 보니 공백이 통째로 사라졌다(저점 +41,400). 10월이 과거가 되면
 * "과거는 0 아래로 안 내려간다" 제약이 이기기 때문이고, 그런데도 조용히 넘어갔다.
 * 시연에 필요한 것은 "10월에 빈다"가 아니라 <b>"발표일로부터 30일 안에 빈다"</b> 다.
 */
export const ANCHOR = DEFAULT_TUNING.anchor;

/**
 * 두 조건을 동시에 만족하는 시작 잔액을 찾는다.
 *
 * <ul>
 *   <li>앵커 구간(10월)의 저점이 {@code anchor.lowest} — 발표 때 보일 공백</li>
 *   <li>기준일 <b>이전</b>은 한 번도 0 아래로 안 내려간다 — 실제 계좌는 마이너스가 될 수 없다</li>
 * </ul>
 *
 * 닫힌 식으로 못 푸는 이유는 사장님 입금이 잔액에 의존하기 때문이다. 시작 잔액을 올리면
 * 메우기가 사라지면서 곡선이 100만 단위로 <b>내려앉는다</b> — 톱니 모양이라 이분탐색도 안 먹는다.
 * 격자 탐색이 81회면 끝나므로 영리하게 굴 이유가 없다.
 *
 * @returns 가장 가까운 후보. 조건을 만족하는 값이 아예 없으면 던진다 —
 *          그건 금액 설계가 잘못됐다는 뜻이라 조용히 넘기면 안 된다.
 */
export function solveOpening({ from, asOf, to, tuning, anchor }) {
  const t = resolveTuning(tuning);
  const goal = anchor ?? t.anchor;
  let best = null;

  // 예보 창 = [기준일, 기준일+withinDays]. to 를 넘지 않게 자른다.
  const windowFrom = ymd(asOf);
  const windowTo = ymd(addDays(asOf, goal.withinDays) < to ? addDays(asOf, goal.withinDays) : to);

  const step = t.search.step > 0 ? t.search.step : 50_000;
  for (let opening = 0; opening <= t.search.max; opening += step) {
    const { rows } = buildPrimary({ from, asOf, to, opening, tuning: t });
    let anchorLow = Infinity;
    let pastLow = Infinity;

    for (const [date, balance] of dailyClosing(rows)) {
      if (date >= windowFrom && date <= windowTo) anchorLow = Math.min(anchorLow, balance);
      if (date < ymd(asOf)) pastLow = Math.min(pastLow, balance);
    }
    if (pastLow < 0) continue;

    const error = Math.abs(anchorLow - goal.lowest);
    const funding = rows.filter((r) => r.category === 'OWNER_FUNDING').length;

    // 메우기가 적은 쪽을 고른다. 시작 잔액을 낮게 잡으면 첫 두 달에 메우기가 몰리는데,
    // 그건 설계가 아니라 <b>시작을 낮게 잡아서 생긴 인공물</b>이다. 게다가 학습 데이터에
    // "잔액이 낮으면 입금이 들어온다"가 섞이면 잔여 축이 공백을 메워 버릴 수 있다.
    // 메우기를 지우지 않고 남겨 둔 것은, 금액을 바꿨을 때 곡선이 마이너스로 빠지는 대신
    // 눈에 보이게 만들기 위해서다.
    const better = !best
      || error < best.error - 20_000
      || (Math.abs(error - best.error) <= 20_000 && funding < best.funding);
    if (better) best = { opening, error, anchorLow, pastLow, funding };
  }

  if (!best) {
    throw new Error(
      '과거를 0 위로 유지하는 시작 잔액이 아예 없다. '
      + `월 스윙이 너무 깊다 — 고정비 금액·날짜를 조정하거나 탐색 상한(${t.search.max.toLocaleString()}원)을 올린다.`);
  }

  // 오차를 조용히 넘기지 않는다. 공백이 없는 시드는 <b>시연이 안 되는 시드</b>이고,
  // 숫자만 찍고 넘어가면 발표 직전에야 알아차리게 된다.
  if (best.error > goal.tolerance) {
    throw new Error(
      `${windowFrom} ~ ${windowTo} 안에 공백을 만들 수 없다 — 그 구간 저점이 `
      + `${best.anchorLow.toLocaleString()}원이고 목표는 ${goal.lowest.toLocaleString()}원이다.\n`
      + '  이 시나리오의 공백은 부가세 예정고지(4·10월)와 급여 앞당김에 기대고 있다. '
      + '그 사건이 창 안에 없으면 과거를 0 위로 유지한 채로는 마이너스를 만들 수 없다.\n'
      + '  기준일을 그 사건이 창에 들어오는 날로 잡거나, 앵커의 「며칠 안」을 늘린다.');
  }
  return { ...best, windowFrom, windowTo, anchor: goal };
}

/**
 * ANCHOR 를 맞추는 시작 잔액. 금액이나 일정을 손보면 이 값이 따라 움직인다 —
 * 그래서 공백이 조정에 견딘다.
 *
 * 특정 <b>날짜</b>가 아니라 10월 <b>구간의 저점</b>에 건다. 저점이 급여일(10/8)일지
 * 4대보험·대출이 겹치는 날일지는 그 해 달력이 정하는 것이라, 날짜를 박아 두면
 * 정작 가장 깊은 날은 앵커가 통제하지 못한다.
 */
export function solveOpeningBalance(from, anchor = ANCHOR) {
  // ⚠ 구간 끝에서 순회를 멈추면 안 된다. 휴일이면 <b>앞당겨지는</b> 항목(급여)은
  // 원래 날짜를 방문해야 생기는데, 10/10(토) 급여가 10/8 로 당겨지는 것이 정확히 그 경우다.
  // 여기서 멈췄다가 급여 210만이 통째로 빠져 시작 잔액이 그만큼 어긋났었다.
  const closing = dailyClosing(withBalances(primaryFlows(from, addDays(parse(anchor.to), 14)), 0));

  let lowest = Infinity;
  for (const [date, balance] of closing) {
    if (date >= anchor.from && date <= anchor.to && balance < lowest) lowest = balance;
  }
  if (lowest === Infinity) throw new Error(`앵커 구간에 거래가 없다: ${anchor.from} ~ ${anchor.to}`);
  return anchor.lowest - lowest;
}

// ── 단독 실행: 곡선 점검 ───────────────────────────────────────────────────────

// Windows 는 file:///C:/... 라 문자열 조립으로 비교하면 조용히 안 맞는다.
if (process.argv[1] && import.meta.url === pathToFileURL(process.argv[1]).href) {
  const argAt = process.argv.indexOf('--as-of');
  const asOf = parse(argAt > 0 ? process.argv[argAt + 1] : ymd(new Date()));
  const from = addDays(asOf, -180);
  const horizon = addDays(asOf, 30);

  const solved = solveOpening({ from, asOf, to: horizon });
  const { opening, rows } = buildPrimary({ from, asOf, to: horizon, opening: solved.opening });
  const closing = dailyClosing(rows);

  const past = rows.filter((r) => r.date < ymd(asOf));
  const future = rows.filter((r) => r.date >= ymd(asOf));
  const funding = past.filter((r) => r.category === 'OWNER_FUNDING');

  console.log(`기준일        ${ymd(asOf)}`);
  console.log(`과거 구간     ${ymd(from)} ~ ${ymd(addDays(asOf, -1))}  ${past.length}건`);
  console.log(`시작 잔액     ${opening.toLocaleString()}원`);
  console.log(`기준일 잔액   ${(past.at(-1)?.balanceAfter ?? opening).toLocaleString()}원`);
  console.log(`사장님 입금   ${funding.length}회 · 합계 ${funding.reduce((s, r) => s + r.amount, 0).toLocaleString()}원`);
  console.log(`미래 구간     ${future.length}건 (예측이 맞혀야 할 것)`);

  let low = Infinity;
  let lowDate = null;
  const gap = [];
  for (const [date, balance] of closing) {
    if (date < ymd(asOf)) continue;
    if (balance < low) { low = balance; lowDate = date; }
    if (balance < 0) gap.push(date);
  }
  console.log(`
공백          ${gap.length ? `${gap[0]} ~ ${gap.at(-1)} (${gap.length}일)` : '없음'}`);
  console.log(`최저          ${low.toLocaleString()}원  (${lowDate})`);

  // 월별 저점이 이 시나리오의 핵심 진단이다 — 과거가 바닥을 안 뚫는지, 10월만 마이너스인지.
  console.log('\n월별 저점');
  const monthly = new Map();
  for (const [date, balance] of closing) {
    const key = date.slice(0, 7);
    const seen = monthly.get(key);
    if (!seen || balance < seen.balance) monthly.set(key, { date, balance });
  }
  for (const [month, { date, balance }] of monthly) {
    const mark = balance < 0 ? ' ◀ 공백' : '';
    console.log(`  ${month}  ${String(balance.toLocaleString()).padStart(12)}원  (${date})${mark}`);
  }

  console.log('\n일별 종가 (기준일 이후 30일)');
  for (const [date, balance] of closing) {
    if (date < ymd(asOf)) continue;
    const d = parse(date);
    const bar = balance < 0 ? '▼' : ' ';
    console.log(`  ${date} ${'일월화수목금토'[dow(d)]} ${bar} ${String(balance.toLocaleString()).padStart(12)}`);
  }
}
