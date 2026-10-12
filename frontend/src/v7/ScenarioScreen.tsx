import { useEffect, useMemo, useRef, useState } from 'react';
import { useFieldArray, useForm, useWatch } from 'react-hook-form';
import { zodResolver } from '@hookform/resolvers/zod';
import { z } from 'zod';
import type { UseFormRegister } from 'react-hook-form';
import { describe, isMoney, toParameter } from './scenarioParams';
import { scenarioBasis } from './scenarioBasis';
import {
  CONFIDENCE_HINT,
  FORECAST_SCORE,
  GRADE_TABLE,
  NOT_YET,
  PATTERN_SCORE,
} from './confidenceRules';
import { Link } from 'react-router';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import {
  createScenario,
  daysAgo,
  listRecurringPatterns,
  deleteScenario,
  formatDateTime,
  formatManwon,
  getProductSalesShares,
  getScenario,
  listScenarios,
} from '@jangoyebo/core';
import type {
  ApiResult,
  PlannedTransaction,
  RecurringPattern,
  ScenarioParameter,
  ScenarioResult,
  ScenarioSummary,
} from '@jangoyebo/core';
import { useBusiness } from '@/business/useBusiness';
import { EmptyState } from '@/components/EmptyState';
import { Field, FormError } from './AuthShell';
import { BalanceChart } from './BalanceChart';
import { Icon } from './icons';
import { useForecast, usePlanned, useSafetyLine } from './model';
import { PlannedCalendar } from './PlannedCalendar';
import { weatherOf } from './Weather';
import type { Weather } from './Weather';
import type { Point } from './model';
import { PageShell, Section } from './PageShell';

/**
 * What-if 시나리오 (P0-09·P0-13, API 11장).
 *
 * 원본 예보는 절대 바뀌지 않는다 — 가정을 넣어 복제 계산한 곡선을 나란히 볼 뿐이다.
 * 계산은 서버가 한다. 화면은 가정을 모아 보내고 돌아온 두 곡선과 지표를 그린다(도메인 규칙 2).
 */

type ParamType = ScenarioParameter['type'];

const KINDS: { type: ParamType; label: string; icon: string }[] = [
  { type: 'SETTLEMENT_DELAY', label: '정산 지연', icon: 'clock' },
  { type: 'DEPOSIT_DECREASE', label: '입금 감소', icon: 'chart' },
  { type: 'EXTRA_EXPENSE', label: '추가 지출', icon: 'receipt' },
  { type: 'EXTRA_DEPOSIT', label: '추가 입금', icon: 'wallet' },
  { type: 'EXPENSE_DATE_SHIFT', label: '지출일 변경', icon: 'repeat' },
  // 가상 시나리오의 「매출 변화」가 조건으로 들어오려면 여기에도 자리가 있어야 한다.
  { type: 'SALES_CHANGE', label: '매출 변화', icon: 'chart' },
  { type: 'PRODUCT_SALES_CHANGE', label: '상품별 매출', icon: 'box' },
  { type: 'LOAN_EXECUTION', label: '대출 실행', icon: 'card' },
];

/** 갚는 방식. 금융망 대출은 DAILY 다 — 개설 다음날부터 매일 08:30 에 빠진다(16장). */
const CYCLES: { value: string; label: string }[] = [
  { value: 'DAILY', label: '매일 갚음' },
  { value: 'MONTHLY', label: '매달 원리금' },
  { value: 'INTEREST_MONTHLY', label: '매달 이자만' },
];
const LABEL = Object.fromEntries(KINDS.map((k) => [k.type, k.label])) as Record<string, string>;

const row = z
  .object({
    type: z.string(),
    /** 어느 입금 패턴인지. 빈 문자열이면 「전체 입금」 — 명세 11.1 에서 생략과 같은 뜻이다. */
    patternId: z.string(),
    delayDays: z.string(),
    rate: z.string(),
    date: z.string(),
    amount: z.string(),
    title: z.string(),
    plannedTransactionId: z.string(),
    newDate: z.string(),
    productKey: z.string(),
    quantityRate: z.string(),
    priceRate: z.string(),
    interestRate: z.string(),
    loanPeriodDays: z.string(),
    repaymentCycle: z.string(),
  })
  .superRefine((v, ctx) => {
    const need = (path: string, message: string) =>
      ctx.addIssue({ code: 'custom', path: [path], message });
    const today = daysAgo(0);
    if (v.type === 'SETTLEMENT_DELAY' && !(Number(v.delayDays) >= 1 && Number(v.delayDays) <= 30))
      need('delayDays', '1~30일로 넣어 주세요.');
    if (v.type === 'DEPOSIT_DECREASE' && !(Number(v.rate) > 0 && Number(v.rate) <= 100))
      need('rate', '1~100% 사이로 넣어 주세요.');
    // 매출은 늘 수도 줄 수도 있다(명세 11.1 은 음수를 허용한다). 0 은 가정이 아니다.
    if (v.type === 'SALES_CHANGE' && !(Number(v.rate) >= -100 && Number(v.rate) <= 100 && Number(v.rate) !== 0))
      need('rate', '−100~100% 사이에서 0 이 아닌 값을 넣어 주세요.');
    if (v.type === 'EXTRA_EXPENSE' || v.type === 'EXTRA_DEPOSIT') {
      if (!v.title.trim()) need('title', '무슨 돈인지 적어 주세요.');
      if (!isMoney(v.amount))
        need('amount', '0원보다 큰 숫자로 넣어 주세요.');
      if (!v.date || v.date <= today) need('date', '내일 이후 날짜를 골라 주세요.');
    }
    const pct = (x: string) => Number(x) >= -100 && Number(x) <= 100 && Number(x) !== 0;
    if (v.type === 'PRODUCT_SALES_CHANGE') {
      if (!v.productKey.trim()) need('productKey', '어느 상품인지 골라 주세요.');
      // 둘 다 비면 아무것도 가정하지 않은 것이다. 하나만 넣어도 된다(명세 11.1).
      if (!v.quantityRate.trim() && !v.priceRate.trim())
        need('quantityRate', '수량이나 가격 가운데 하나는 넣어 주세요.');
      if (v.quantityRate.trim() && !pct(v.quantityRate))
        need('quantityRate', '−100~100% 사이에서 0 이 아닌 값을 넣어 주세요.');
      if (v.priceRate.trim() && !pct(v.priceRate))
        need('priceRate', '−100~100% 사이에서 0 이 아닌 값을 넣어 주세요.');
    }
    if (v.type === 'LOAN_EXECUTION') {
      if (!isMoney(v.amount))
        need('amount', '0원보다 큰 숫자로 넣어 주세요.');
      if (!(Number(v.interestRate) > 0 && Number(v.interestRate) <= 30))
        need('interestRate', '연 이자율을 0~30% 사이로 넣어 주세요.');
      if (!(Number(v.loanPeriodDays) >= 1 && Number(v.loanPeriodDays) <= 3650))
        need('loanPeriodDays', '1~3,650일로 넣어 주세요.');
      if (!v.repaymentCycle) need('repaymentCycle', '갚는 방식을 골라 주세요.');
      if (!v.date || v.date <= today) need('date', '내일 이후 날짜를 골라 주세요.');
    }
    if (v.type === 'EXPENSE_DATE_SHIFT') {
      if (!v.plannedTransactionId) need('plannedTransactionId', '옮길 지출을 골라 주세요.');
      if (!v.newDate || v.newDate <= today) need('newDate', '내일 이후 날짜를 골라 주세요.');
    }
  });

const schema = z.object({
  name: z.string(),
  days: z.enum(['7', '14', '30']),
  save: z.boolean(),
  parameters: z.array(row).min(1, '가정을 하나 이상 넣어 주세요.'),
});
type Form = z.infer<typeof schema>;
type Row = Form['parameters'][number];

/**
 * 폼이 든 예정 지출이 고를 수 있는 목록에 없는가.
 *
 * 저장해 둔 조합을 다시 열었는데 그사이 그 예정 거래가 지워졌거나 예측 창 밖으로
 * 나가면 이렇게 된다. 목록에 없다고 그냥 두면 브라우저가 첫 항목을 대신 보여 주는데
 * 폼이 든 값은 그대로라, <b>화면과 보낼 값이 서로 다른 말을 한다.</b>
 */
const shiftMissing = (id: string | undefined, rows: { plannedTransactionId: number }[]) =>
  !!id && !rows.some((p) => String(p.plannedTransactionId) === id);

const emptyRow = (type: ParamType): Row => ({
  type,
  // 빈 문자열이 「전체 입금」이다. 고르지 않은 채로 두면 지금까지와 똑같이 동작한다.
  patternId: '',
  delayDays: '2',
  rate: '10',
  date: '',
  amount: '',
  title: '',
  plannedTransactionId: '',
  newDate: '',
  productKey: '',
  quantityRate: '',
  priceRate: '',
  interestRate: '4.5',
  loanPeriodDays: '365',
  repaymentCycle: 'DAILY',
});

/**
 * 어느 입금이 늦는지(줄어드는지) 고르는 칸.
 *
 * 실제로는 <b>카드사 정산 하나만</b> 늦는 경우가 대부분인데, 고르지 못하면 임대료 입금까지
 * 같이 늦춰 계산해 숫자가 실제보다 나빠진다.
 *
 * 고를 것이 없으면 <b>칸 자체를 그리지 않는다.</b> 「전체」 하나뿐인 선택지는 고르는 척만
 * 하는 칸이고, 패턴을 못 불러온 것은 오류가 아니라 정상 빈 상태다(화면구성도 6.4).
 */
function PatternPick({
  index,
  deposits,
  register,
}: {
  index: number;
  deposits: RecurringPattern[];
  register: UseFormRegister<Form>;
}) {
  if (deposits.length === 0) return null;
  return (
    <Field label="어느 입금인가" hint="고르지 않으면 입금 전체에 적용합니다">
      <select {...register(`parameters.${index}.patternId`)}>
        <option value="">입금 전체</option>
        {deposits.map((r) => (
          <option key={r.patternId} value={r.patternId}>
            {r.name} · {formatManwon(r.amountRange.expected)}
          </option>
        ))}
      </select>
    </Field>
  );
}

/**
 * 어느 상품의 매출이 달라지는지 고르는 칸.
 *
 * 구성비(14.5)에서 팔린 상품을 점유율 큰 순으로 세운다. <b>골라도 되고 적어도 된다</b> —
 * 서버가 이름을 같은 규칙으로 정규화하므로(14.5 주석) 목록에 없는 신상품도 넣을 수 있다.
 * 목록을 못 불러와도 칸은 남는다. 여기서 목록은 편의이지 자물쇠가 아니다.
 *
 * `datalist` 를 쓰지 않는 이유는 <b>열 수 있다는 표시가 없어서</b>다. 무엇을 팔았는지
 * 기억나지 않을 때 빈 칸을 눌러 전부 훑는 길이 있어야 한다.
 */
function ProductPick({
  index,
  products,
  note,
  error,
  value,
  onPick,
  register,
}: {
  index: number;
  products: { productKey: string; productName: string; share?: number }[];
  /** 목록이 왜 비었는지 · 골라도 되는지. 빈 것과 못 불러온 것은 다른 말이다 */
  note: string;
  error?: string;
  value: string;
  onPick: (name: string) => void;
  register: UseFormRegister<Form>;
}) {
  const [open, setOpen] = useState(false);
  const [at, setAt] = useState(0);
  const box = useRef<HTMLDivElement>(null);
  const labelId = `product-label-${index}`;
  const listId = `product-list-${index}`;

  // 적은 글자로 좁힌다. 비어 있으면 전부 — 눌러서 훑는 길이 그것이다.
  const term = value.trim().toLowerCase();
  const shown = term
    ? products.filter((r) => r.productName.toLowerCase().includes(term))
    : products;

  useEffect(() => {
    if (!open) return undefined;
    const outside = (e: PointerEvent) => {
      if (!box.current?.contains(e.target as Node)) setOpen(false);
    };
    window.addEventListener('pointerdown', outside);
    return () => window.removeEventListener('pointerdown', outside);
  }, [open]);

  const choose = (name: string) => {
    onPick(name);
    setOpen(false);
  };

  return (
    <div className="auth-field v7-product-pick" ref={box}>
      <span className="auth-label" id={labelId}>
        어느 상품인가
      </span>
      <div className="v7-product-pick__row">
        <input
          role="combobox"
          aria-labelledby={labelId}
          aria-expanded={open}
          aria-controls={listId}
          aria-autocomplete="list"
          autoComplete="off"
          placeholder="아메리카노"
          {...register(`parameters.${index}.productKey`, {
            onChange: () => {
              setOpen(true);
              setAt(0);
            },
          })}
          onFocus={() => setOpen(true)}
          onKeyDown={(event) => {
            if (event.key === 'Escape') return setOpen(false);
            if (event.key === 'ArrowDown' || event.key === 'ArrowUp') {
              event.preventDefault();
              if (!open) return setOpen(true);
              setAt((i) =>
                Math.max(0, Math.min(shown.length - 1, i + (event.key === 'ArrowDown' ? 1 : -1))),
              );
            }
            // 목록이 열려 있을 때의 Enter 는 고르는 것이다. 폼이 먼저 제출되면 고를 수 없다.
            if (event.key === 'Enter' && open && shown[at]) {
              event.preventDefault();
              choose(shown[at].productName);
            }
          }}
        />
        {products.length > 0 && (
          <button
            type="button"
            className="v7-product-pick__toggle"
            aria-label={open ? '상품 목록 닫기' : '상품 목록 열기'}
            aria-expanded={open}
            tabIndex={-1}
            onClick={() => setOpen((o) => !o)}
          >
            <Icon name="down" size={15} />
          </button>
        )}
      </div>

      {open && shown.length > 0 && (
        <ul className="v7-product-pick__list" id={listId} role="listbox">
          {shown.map((r, i) => (
            <li key={r.productKey}>
              <button
                type="button"
                role="option"
                aria-selected={i === at}
                className={i === at ? 'is-on' : ''}
                // 클릭보다 먼저 일어나는 pointerdown 이 바깥 닫기와 겹치지 않게 막는다
                onPointerDown={(event) => event.preventDefault()}
                onClick={() => choose(r.productName)}
              >
                <span>{r.productName}</span>
                {r.share !== undefined && <em>구성비 {Math.round(r.share * 100)}%</em>}
              </button>
            </li>
          ))}
        </ul>
      )}

      {!error && <span className="auth-hint">{note}</span>}
      {error && (
        <span className="auth-error" role="alert">
          <span aria-hidden="true">⚠</span> {error}
        </span>
      )}
    </div>
  );
}

function toPoints(curve: ScenarioResult['originalCurve']): Point[] {
  return (curve ?? [])
    .filter((p) => p.date && p.expectedBalance !== undefined)
    .map((p, index) => {
      const d = new Date(`${p.date}T00:00:00Z`);
      const value = p.expectedBalance as number;
      return {
        iso: p.date as string,
        index,
        label: `${d.getUTCMonth() + 1}.${String(d.getUTCDate()).padStart(2, '0')}`,
        date: `${d.getUTCMonth() + 1}월 ${d.getUTCDate()}일`,
        weekday: ['일', '월', '화', '수', '목', '금', '토'][d.getUTCDay()],
        value,
        min: value,
        max: value,
      };
    });
}

/**
 * 자주 묻는 질문. 가정 종류를 사장님이 고르지 않아도 되게 카드 하나가 파라미터 하나다.
 * 숫자만 슬라이더로 조절하고, 조절하면 바로 다시 계산한다.
 */
type PresetKey = 'delay' | 'sales' | 'expense' | 'shift';
interface Preset {
  key: PresetKey;
  icon: string;
  /** 칩에 적는 짧은 이름. 물음 문장은 좁은 조건 칸에서 한 줄씩 차지해 가로로 놓이지 않는다. */
  short: string;
  question: (v: number, extra?: string) => string;
  min: number;
  max: number;
  step: number;
  initial: number;
  unit: (v: number) => string;
}
/** 서버가 보내는 신뢰 수준을 사람 말로. BasisDialog 와 같은 표다. */
const CONFIDENCE: Record<string, string> = {
  HIGH: '높음',
  MEDIUM: '보통',
  LOW: '낮음',
  UNPREDICTABLE: '예측 어려움',
};

const PRESETS: Preset[] = [
  {
    key: 'delay',
    icon: 'clock',
    short: '정산 지연',
    question: (v) => `배달앱·카드 정산이 ${v}일 늦어지면?`,
    min: 1,
    max: 7,
    step: 1,
    initial: 3,
    unit: (v) => `${v}일`,
  },
  {
    key: 'sales',
    icon: 'chart',
    short: '매출 감소',
    question: (v) => `매출이 ${v}% 줄면?`,
    min: 5,
    max: 50,
    step: 5,
    initial: 10,
    unit: (v) => `${v}%`,
  },
  {
    key: 'expense',
    icon: 'receipt',
    short: '갑작스런 지출',
    question: (v) => `다음 주에 ${formatManwon(v * 10_000)} 나갈 일이 생기면?`,
    min: 50,
    max: 1000,
    step: 10,
    initial: 150,
    unit: (v) => formatManwon(v * 10_000),
  },
  {
    key: 'shift',
    icon: 'repeat',
    short: '지출 미루기',
    question: (v, title) =>
      title ? `「${title}」 지출을 ${v}일 미루면?` : `가장 큰 지출을 ${v}일 미루면?`,
    min: 1,
    max: 14,
    step: 1,
    initial: 7,
    unit: (v) => `${v}일`,
  },
];

/** 결과를 낸 뒤 이 자리로 데려간다. `revealGraph()` 참고 */
const GRAPH_ID = 'scenario-graph';

const shiftDate = (date: string, days: number) =>
  new Date(Date.parse(`${date}T00:00:00Z`) + days * 86_400_000).toISOString().slice(0, 10);

const failure = (r: ApiResult<unknown>) =>
  !r.ready ? '시나리오 계산 기능이 아직 준비되지 않았습니다.' : r.ok ? null : r.error.message;

export function ScenarioScreen() {
  const { current } = useBusiness();
  const businessId = current?.businessId;
  const queryClient = useQueryClient();
  const safety = useSafetyLine(businessId);
  const planned = usePlanned(businessId);
  const withdrawals = planned.items.filter(
    (p) => p.direction === 'WITHDRAWAL' && p.status !== 'CANCELLED',
  );
  const [result, setResult] = useState<ScenarioResult | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [picked, setPicked] = useState(0);
  const [preset, setPreset] = useState<PresetKey | null>(null);
  const [amounts, setAmounts] = useState<Record<PresetKey, number>>(
    () => Object.fromEntries(PRESETS.map((p) => [p.key, p.initial])) as Record<PresetKey, number>,
  );
  // 달력에서 끌어다 옮긴 지출. 원본 예정 거래는 건드리지 않고 이 목록만 시나리오로 보낸다.
  const [moves, setMoves] = useState<Map<number, string>>(new Map());
  const forecast = useForecast(businessId);
  const weather = new Map<string, Weather>();
  for (const day of forecast.forecast?.curve ?? []) {
    if (day.date && day.expectedBalance !== undefined)
      weather.set(
        day.date,
        weatherOf(day.expectedBalance, forecast.forecast?.safetyLine ?? safety.amount),
      );
  }
  const calendarRows = planned.items
    .filter((p) => p.status !== 'CANCELLED')
    .map((p) =>
      moves.has(p.plannedTransactionId)
        ? { ...p, expectedDate: moves.get(p.plannedTransactionId) as string }
        : p,
    );

  // 미룰 지출은 앞으로 30일 안의 가장 큰 예정 지출이다. 없으면 그 카드를 내리지 않고 숨긴다.
  const biggest = [...withdrawals].sort((a, b) => b.amountMax - a.amountMax)[0];

  const {
    register,
    control,
    handleSubmit,
    reset,
    setValue,
    formState: { errors, isSubmitting },
  } = useForm<Form>({
    resolver: zodResolver(schema),
    mode: 'onTouched',
    defaultValues: {
      name: '',
      days: '30',
      save: false,
      // 빈 채로 연다. 넣은 적 없는 가정이 놓여 있으면 무엇을 가정하고 있는지 흐려지고,
      // 쓰지 않을 사람은 먼저 지워야 했다. 0 건일 때 무엇을 하면 되는지는 저장 경고가 말한다.
      parameters: [],
    },
  });
  const { fields, append, remove, update } = useFieldArray({ control, name: 'parameters' });
  const calendarDialog = useRef<HTMLDialogElement>(null);
  /** 가정 없이 저장을 눌렀다. 가정이 들어오면 렌더에서 저절로 걷힌다. */
  const [saveWarning, setSaveWarning] = useState(false);
  /** 달력에서 펼쳐 둔 날. 그 날만 예정 거래를 전부 보여 준다. */
  const [openDay, setOpenDay] = useState('');
  /**
   * 조건 추가 판이 열려 있나, 그 안에서 켜 둔 종류는 무엇인가.
   *
   * 종류마다 버튼을 두면 여럿 넣을 때 버튼을 여러 번 오가야 하고 종류가 늘 때마다 줄이
   * 길어진다. <b>켜 두었다가 한 번에 넣는다</b> — 무엇을 넣을지 먼저 정하고 그다음 넣는 순서다.
   */
  const [adding, setAdding] = useState(false);
  const [picks, setPicks] = useState<ParamType[]>([]);
  /**
   * 곡선이 보이는 자리로 데려간다.
   *
   * 결과를 새로 낸 버튼마다 부른다 — 비교하기·저장하기·지금 데이터로 다시 보기. 특히
   * 「지금 데이터로 다시 보기」는 화면 맨 아래 저장 목록에 있어서, 누르고 나서 곡선을
   * 찾아 올라가야 했다. 좁은 폭에서는 그래프가 조건 아래에 있어 반대로 내려가야 한다.
   *
   * <b>ref 가 아니라 id 로 찾는다.</b> `onSubmit` 이 ref 를 읽으면 그 함수를 렌더 중에
   * `handleSubmit()` 에 넘기는 것이 `react-hooks/refs` 에 걸린다 — 실제로 읽는 시점은
   * 제출할 때지만 린터는 그것까지 보지 못한다.
   *
   * <b>`show()` 안에 두지 않는다.</b> 달력에서 칩을 끌 때도 `show()` 가 불리는데, 끌
   * 때마다 화면이 움직이면 놓을 자리를 겨냥할 수 없다.
   */
  function revealGraph() {
    document.getElementById(GRAPH_ID)?.scrollIntoView({ block: 'start', behavior: 'smooth' });
  }

  function openCalendar() {
    calendarDialog.current?.showModal();
  }

  const paramRows = useWatch({ control, name: 'parameters' }) ?? [];
  const types = paramRows.map((p) => p.type);
  /** 폼에 이미 들어간 지출일 변경 줄의 위치. 달력에서 같은 지출을 또 넣지 않으려고 본다. */
  const shiftIndex = (plannedTransactionId: string) =>
    paramRows.findIndex(
      (p) => p.type === 'EXPENSE_DATE_SHIFT' && p.plannedTransactionId === plannedTransactionId,
    );

  function show(r: ApiResult<ScenarioResult>) {
    const reason = failure(r);
    setError(reason);
    if (r.ready && r.ok) {
      setResult(r.data);
      setPicked(0);
    }
  }

  const value = preset ? amounts[preset] : 0;

  /**
   * 고른 가상 시나리오를 곡선에 반영한다.
   *
   * <b>슬라이더를 움직일 때마다 스스로 계산하지 않는다.</b> 끄는 동안 호출이 이어지고,
   * 사장님이 값을 정하기도 전에 곡선이 먼저 움직여 무엇을 본 것인지 흐려진다. 누른
   * 시점의 값 하나로만 계산한다.
   */
  /**
   * 가상 시나리오를 <b>조건 한 줄로 옮긴다.</b>
   *
   * 전에는 프리셋이 폼을 무시하고 저 혼자 계산했다. 조건을 둘 넣어 둔 채 「적용하기」를
   * 누르면 그 둘이 빠진 곡선이 나왔는데, 조건 칸에는 두 줄이 그대로 남아 있어 화면이
   * 거짓말을 했다. 달력에서 옮기면 조건이 되는 것과 같은 방식으로 맞춘다 — 계산은 언제나
   * 「비교하기」가 폼 전체로 낸다.
   */
  function addPreset() {
    if (!preset) return;
    const made: Row | null =
      preset === 'delay'
        ? { ...emptyRow('SETTLEMENT_DELAY'), delayDays: String(value) }
        : preset === 'sales'
          ? // 프리셋의 물음이 「매출이 줄면?」이라 음수로 넣는다. 조건 칸에서 부호를 고칠 수 있다.
            { ...emptyRow('SALES_CHANGE'), rate: String(-value) }
          : preset === 'expense'
            ? {
                ...emptyRow('EXTRA_EXPENSE'),
                date: daysAgo(-7),
                amount: String(value * 10_000),
                title: '갑작스러운 지출',
              }
            : biggest
              ? {
                  ...emptyRow('EXPENSE_DATE_SHIFT'),
                  plannedTransactionId: String(biggest.plannedTransactionId),
                  newDate: shiftDate(biggest.expectedDate, value),
                }
              : null;
    if (!made) return;
    append(made);
    // 고른 것을 풀어 둔다. 남겨 두면 같은 가정을 두 번 넣기 쉽다.
    setPreset(null);
  }

  // 달력에서 옮길 때마다 스스로 계산하던 자리다. 이제 옮기면 조건이 폼에 들어가므로,
  // 여기서 또 계산하면 **지출일 변경만 반영한 곡선**이 나와 다른 조건을 덮는다. 곡선은
  // 「비교하기」가 폼 전체로 낸다.

  /**
   * 정산 지연·입금 감소에서 고를 <b>입금</b> 패턴들.
   *
   * 못 불러오거나 0건이면 고르는 칸을 감추고 전체로 동작한다 — 오류가 아니라 정상 빈
   * 상태다(화면구성도 6.4). 지출 패턴은 거른다. 늦게 들어오는 것은 입금뿐이다.
   */
  const patterns = useQuery({
    queryKey: ['v7', 'patterns', businessId],
    enabled: businessId !== undefined,
    queryFn: () => listRecurringPatterns(businessId as number),
  });
  /** 근거가 쓸 전체 패턴. 아래 `deposits` 는 조건에서 고를 입금 패턴만 거른 것이다 */
  const allPatterns = useMemo(
    () => (patterns.data?.ready && patterns.data.ok ? patterns.data.data.items : []),
    [patterns.data],
  );
  const deposits = useMemo(
    () =>
      patterns.data?.ready && patterns.data.ok
        ? patterns.data.data.items.filter((r) => r.direction === 'DEPOSIT')
        : [],
    [patterns.data],
  );
  const patternNames = useMemo(
    () => new Map(deposits.map((r) => [r.patternId, r.name])),
    [deposits],
  );

  /**
   * 상품별 매출 변화에서 고를 상품들. 못 불러와도 이름을 직접 적을 수 있어 막지 않는다.
   *
   * <b>기간을 90일로 넓힌다.</b> 14.5 는 생략하면 이번 달 1일부터인데, 그러면 달이 바뀐
   * 직후에는 아무것도 팔지 않은 것처럼 보인다. 지난달에 팔던 것도 고를 수 있어야 한다.
   */
  const shares = useQuery({
    queryKey: ['v7', 'product-shares', businessId],
    enabled: businessId !== undefined,
    queryFn: () => getProductSalesShares(businessId as number, { from: daysAgo(90), to: daysAgo(0) }),
  });
  const products = useMemo(() => {
    const items = shares.data?.ready && shares.data.ok ? shares.data.data.items : [];
    return [...items].sort((a, b) => (b.share ?? 0) - (a.share ?? 0));
  }, [shares.data]);
  /**
   * 목록이 왜 비었는지. <b>빈 것과 못 불러온 것은 다른 말이다</b> — 아무 말도 없이 칸만
   * 두면 적어도 되는 자리인지 기다려야 하는 자리인지 알 수 없다.
   */
  const productsNote = !shares.data
    ? '상품 목록을 불러오는 중입니다. 그동안 이름을 직접 적어도 됩니다'
    : !shares.data.ready
      ? '상품별 매출 기능이 아직 준비되지 않았습니다. 이름을 직접 적어 주세요'
      : !shares.data.ok
        ? '상품 목록을 불러오지 못했습니다. 이름을 직접 적어 주세요'
        : products.length === 0
          ? '최근 90일에 등록된 상품 매출이 없습니다. 이름을 직접 적어 주세요'
          : '골라도 되고 이름을 직접 적어도 됩니다';

  const saved = useQuery({
    queryKey: ['v7', 'scenarios', businessId],
    enabled: businessId !== undefined,
    queryFn: () => listScenarios(businessId as number),
  });
  const savedItems = saved.data?.ready && saved.data.ok ? saved.data.data.items : [];

  const open = useMutation({
    mutationFn: getScenario,
    onSuccess: (r) => {
      show(r);
      revealGraph();
    },
  });
  const remove_ = useMutation({
    mutationFn: deleteScenario,
    onSuccess: () => queryClient.invalidateQueries({ queryKey: ['v7', 'scenarios'] }),
  });

  async function onSubmit(v: Form) {
    const r = await createScenario(businessId as number, {
      ...(v.name.trim() ? { name: v.name.trim() } : {}),
      days: Number(v.days) as 7 | 14 | 30,
      save: v.save,
      parameters: v.parameters.map(toParameter),
    });
    show(r);
    revealGraph();
    if (v.save) queryClient.invalidateQueries({ queryKey: ['v7', 'scenarios'] });
  }

  return (
    <PageShell>
      {/* 조작과 결과를 한 화면에 둔다. 세로로 늘어놓으면 손잡이를 움직인 뒤 스크롤해야
        * 곡선이 보여서 「무엇을 바꿨더니 무엇이 달라졌다」가 끊긴다.
        *
        * <b>결과가 왼쪽이다.</b> 조건을 고칠 때마다 눈이 오른쪽 끝으로 갔다가 돌아와야 했고,
        * 같은 자금 예보 묶음인 정산 공백 화면은 곡선을 먼저 둔다. DOM 순서도 함께 바꿔
        * 보이는 순서와 낭독·탭 순서가 어긋나지 않게 한다. */}
      <div className="v7-scenario-work">
        <section className="v7-scenario-view" aria-label="그래프" id={GRAPH_ID}>
          {result ? (
            <Result
              result={result}
              picked={picked}
              onPick={setPicked}
              safetyLine={safety.amount}
              patterns={allPatterns}
              planned={planned.items}
            />
          ) : (
            <EmptyState
              title="아직 비교한 것이 없습니다"
              hint="오른쪽에서 가정을 넣고 「비교하기」를 누르면 여기에 곡선이 나옵니다."
            />
          )}
        </section>

        <section className="v7-scenario-controls" aria-labelledby="scenario-controls-title">
          <div className="v7-scenario-controls__head">
            <h2 id="scenario-controls-title">조건 설정</h2>
            {/* 달력은 팝업이라 닫으면 사라진다. 옮긴 건수를 배지로 남기지 않으면 무엇이
              * 걸려 있는지 화면 어디에도 안 보인다. 아이콘만 있는 버튼이라 이름은
              * aria-label 이 진다. */}
            <button
              type="button"
              className="v7-scenario-controls__cal"
              aria-label={
                moves.size > 0 ? `달력에서 옮기기 · ${moves.size}건 옮김` : '달력에서 옮기기'
              }
              title="달력에서 옮기기"
              onClick={openCalendar}
            >
              <Icon name="calendar" size={17} />
              {moves.size > 0 && <span aria-hidden="true">{moves.size}</span>}
            </button>
            {/* 조건을 넣는 유일한 버튼이다. 초기화 바로 왼쪽에 둔다 — 넣기와 비우기가
              * 한자리에 있어야 무엇을 되돌리는지가 분명하다. */}
            <button
              type="button"
              className="v7-secondary-button v7-scenario-controls__add"
              aria-expanded={adding}
              onClick={() => setAdding((o) => !o)}
            >
              <Icon name="plus" size={14} /> 조건 추가
            </button>
            {/* 넣은 가정과 결과를 처음 상태로 되돌린다. 저장한 시나리오는 지우지 않는다.
              *
              * **가정이 들어오는 길이 셋이라 셋 다 비운다** — 직접 조합하기 폼, 달력에서
              * 옮긴 표시, 고른 가상 시나리오. 하나라도 남기면 「초기화」를 눌렀는데 곡선이
              * 그대로이거나, 달력만 옛 상태로 남는다. */}
            <button
              type="button"
              className="v7-secondary-button v7-scenario-controls__reset"
              onClick={() => {
                reset();
                setMoves(new Map());
                setPreset(null);
                setOpenDay('');
                // 되돌린 직후에는 가정이 0건이라, 지우지 않으면 경고가 그대로 떠 있는다.
                setSaveWarning(false);
                setResult(null);
                setError(null);
                setAdding(false);
                setPicks([]);
              }}
            >
              <Icon name="refresh" size={13} /> 초기화
            </button>
          </div>
          <form onSubmit={handleSubmit(onSubmit)} noValidate className="v7-settlement-form">
            {/* 같은 종류를 여러 번 넣을 수 있다. 추가 지출 2건처럼 같은 종류를 여럿
              * 넣어야 하는 경우가 있어서, 한 번 잠갔다가 되돌렸다.
              *
              * 지출일 변경은 여기 두지 않는다. 머리말의 달력 버튼이 유일한 길이고, 거기서
              * 옮기면 조건이 바로 들어온다 — 빈 줄을 따로 넣게 하면 옮긴 것과 손으로 넣은
              * 것이 섞여 어느 쪽이 참인지 알 수 없다. */}
            {adding && (
              <div className="v7-add-panel">
                <div className="v7-chips" role="group" aria-label="넣을 조건 종류">
                  {KINDS.filter((k) => k.type !== 'EXPENSE_DATE_SHIFT').map((k) => {
                    const on = picks.includes(k.type);
                    return (
                      <button
                        key={k.type}
                        type="button"
                        // 눌린 상태를 색만으로 말하지 않는다 — 굵기·체크 아이콘과 함께 읽어 주는 이름에도 담는다
                        aria-pressed={on}
                        onClick={() =>
                          setPicks((cur) =>
                            on ? cur.filter((t) => t !== k.type) : [...cur, k.type],
                          )
                        }
                      >
                        <Icon name={on ? 'check' : k.icon} size={14} /> {k.label}
                      </button>
                    );
                  })}
                </div>
                <div className="v7-add-panel__foot">
                  <button
                    type="button"
                    className="auth-submit v7-scenario-action"
                    disabled={picks.length === 0}
                    onClick={() => {
                      // 고른 순서대로 들어간다. 한 번에 넣는 것이 이 판을 둔 이유다.
                      picks.forEach((t) => append(emptyRow(t)));
                      setPicks([]);
                      setAdding(false);
                    }}
                  >
                    {picks.length > 0 ? `${picks.length}건 추가하기` : '추가하기'}
                  </button>
                  <span className="v7-legend__hint">
                    {picks.length === 0 ? '넣을 조건을 골라 주세요' : '한 번에 들어갑니다'}
                  </span>
                </div>
              </div>
            )}

            {fields.map((field, i) => {
              const e = errors.parameters?.[i];
              const type = types[i];
              return (
                <fieldset key={field.id} className="v7-scenario-row">
                  <legend>{LABEL[type]}</legend>
                  {/* 카드 우측 상단. 아래에 두면 값 입력 칸과 섞여 잘못 누르기 쉽고,
                    * 줄이 여러 개일 때 어느 줄의 빼기인지도 흐려진다. */}
                  <button
                    type="button"
                    className="v7-scenario-row__remove"
                    aria-label={`${LABEL[type]} 빼기`}
                    onClick={() => remove(i)}
                  >
                    <Icon name="close" size={14} />
                  </button>
                  <input type="hidden" {...register(`parameters.${i}.type`)} />
                  {type === 'SETTLEMENT_DELAY' && (
                    <>
                      <PatternPick index={i} deposits={deposits} register={register} />
                      <Field label="며칠 늦게 들어오나" error={e?.delayDays?.message}>
                        <input
                          type="number"
                          min={1}
                          max={30}
                          {...register(`parameters.${i}.delayDays`)}
                        />
                      </Field>
                    </>
                  )}
                  {type === 'SALES_CHANGE' && (
                    <Field
                      label="매출이 몇 % 달라지나"
                      error={e?.rate?.message}
                      hint="줄어드는 쪽은 −10 처럼 빼기로 적습니다"
                    >
                      <input
                        type="number"
                        min={-100}
                        max={100}
                        {...register(`parameters.${i}.rate`)}
                      />
                    </Field>
                  )}
                  {type === 'PRODUCT_SALES_CHANGE' && (
                    <>
                      <ProductPick
                        index={i}
                        products={products}
                        note={productsNote}
                        error={e?.productKey?.message}
                        value={paramRows[i]?.productKey ?? ''}
                        onPick={(name) =>
                          setValue(`parameters.${i}.productKey`, name, {
                            shouldValidate: true,
                            shouldDirty: true,
                          })
                        }
                        register={register}
                      />
                      {/* 둘 중 하나만 넣어도 된다. 빈 칸은 「변화 없음」이라 보내지 않는다 */}
                      <Field
                        label="몇 개나 더 팔리나"
                        error={e?.quantityRate?.message}
                        hint="수량 증감 %. 덜 팔리면 −10 처럼 적습니다"
                      >
                        <input
                          type="number"
                          min={-100}
                          max={100}
                          placeholder="비워 두면 그대로"
                          {...register(`parameters.${i}.quantityRate`)}
                        />
                      </Field>
                      <Field
                        label="값을 몇 % 올리나"
                        error={e?.priceRate?.message}
                        hint="가격 증감 %. 내리면 −5 처럼 적습니다"
                      >
                        <input
                          type="number"
                          min={-100}
                          max={100}
                          placeholder="비워 두면 그대로"
                          {...register(`parameters.${i}.priceRate`)}
                        />
                      </Field>
                    </>
                  )}
                  {type === 'LOAN_EXECUTION' && (
                    <>
                      <Field label="얼마를 빌리나" error={e?.amount?.message}>
                        <input
                          type="text"
                          inputMode="numeric"
                          placeholder="3,000,000"
                          {...register(`parameters.${i}.amount`)}
                        />
                      </Field>
                      <Field
                        label="연 이자율"
                        error={e?.interestRate?.message}
                        hint="퍼센트로 적습니다(4.5). 실제 금리는 대출·신용 화면에서 찾아보세요"
                      >
                        <input
                          type="number"
                          min={0}
                          max={30}
                          step={0.1}
                          {...register(`parameters.${i}.interestRate`)}
                        />
                      </Field>
                      <Field label="며칠 동안 빌리나" error={e?.loanPeriodDays?.message}>
                        <input
                          type="number"
                          min={1}
                          max={3650}
                          {...register(`parameters.${i}.loanPeriodDays`)}
                        />
                      </Field>
                      <Field label="갚는 방식" error={e?.repaymentCycle?.message}>
                        <select {...register(`parameters.${i}.repaymentCycle`)}>
                          {CYCLES.map((c) => (
                            <option key={c.value} value={c.value}>
                              {c.label}
                            </option>
                          ))}
                        </select>
                      </Field>
                      <Field label="언제 받나" error={e?.date?.message}>
                        <input type="date" {...register(`parameters.${i}.date`)} />
                      </Field>
                    </>
                  )}
                  {type === 'DEPOSIT_DECREASE' && (
                    <>
                      <PatternPick index={i} deposits={deposits} register={register} />
                      <Field label="입금이 몇 % 줄어드나" error={e?.rate?.message}>
                        <input
                          type="number"
                          min={1}
                          max={100}
                          {...register(`parameters.${i}.rate`)}
                        />
                      </Field>
                    </>
                  )}
                  {(type === 'EXTRA_EXPENSE' || type === 'EXTRA_DEPOSIT') && (
                    <div className="v7-form-grid">
                      <Field label="내용" error={e?.title?.message}>
                        <input
                          type="text"
                          placeholder={type === 'EXTRA_EXPENSE' ? '머신 교체' : '지원금'}
                          {...register(`parameters.${i}.title`)}
                        />
                      </Field>
                      <Field label="금액" error={e?.amount?.message}>
                        <input
                          type="text"
                          inputMode="numeric"
                          placeholder="1500000"
                          {...register(`parameters.${i}.amount`)}
                        />
                      </Field>
                      <Field label="날짜" error={e?.date?.message}>
                        <input type="date" {...register(`parameters.${i}.date`)} />
                      </Field>
                    </div>
                  )}
                  {type === 'EXPENSE_DATE_SHIFT' && (
                    <div className="v7-form-grid v7-form-grid--two">
                      <Field label="날짜를 바꿀 지출" error={e?.plannedTransactionId?.message}>
                        {/* 「고르세요」를 두지 않는다. 이 줄은 달력에서 끌거나 가상 시나리오로만
                          * 들어오고, 그때 옮길 지출은 이미 정해져 있다 — 고를 일이 없는 빈 칸은
                          * 무언가를 더 해야 하는 것처럼 읽힌다.
                          *
                          * 대신 목록에 없는 지출일 때만 한 줄을 만들어 둔다. 사정은 shiftMissing 에. */}
                        <select {...register(`parameters.${i}.plannedTransactionId`)}>
                          {shiftMissing(paramRows[i]?.plannedTransactionId, withdrawals) && (
                            <option value={paramRows[i].plannedTransactionId}>
                              목록에 없는 예정 지출
                            </option>
                          )}
                          {withdrawals.map((p) => (
                            <option key={p.plannedTransactionId} value={p.plannedTransactionId}>
                              {p.expectedDate} · {p.title}
                            </option>
                          ))}
                        </select>
                      </Field>
                      <Field label="변경할 지출일" error={e?.newDate?.message}>
                        <input type="date" {...register(`parameters.${i}.newDate`)} />
                      </Field>
                    </div>
                  )}
                </fieldset>
              );
            })}
            <FormError message={errors.parameters?.root?.message ?? errors.parameters?.message} />

            <div className="v7-form-grid">
              <Field label="비교 기간">
                <select {...register('days')}>
                  <option value="7">7일</option>
                  <option value="14">14일</option>
                  <option value="30">30일</option>
                </select>
              </Field>
              <Field label="이름">
                <input type="text" {...register('name')} />
              </Field>
            </div>
            <FormError message={error ?? undefined} />
            <div className="v7-review-actions">
              <button
                type="submit"
                className="auth-submit v7-scenario-action"
                disabled={isSubmitting || businessId === undefined}
              >
                {isSubmitting ? '계산하는 중…' : '비교하기'}
              </button>
              {/* 저장은 체크박스였다. 눌러서 저장하는 편이 「지금 이 조합을 남긴다」는
                * 뜻에 맞고, 비교만 해 보고 마는 경우와도 갈린다. 저장도 계산을 거치므로
                * 곡선은 똑같이 갱신된다.
                *
                * 가정이 없을 때 **잠그지 않고 눌리게 둔다.** 잠긴 버튼은 왜 안 되는지를
                * 말해 주지 않아서, 사장님은 고장인지 조건이 모자란 건지 알 수 없다. */}
              <button
                type="button"
                className="auth-submit v7-scenario-action"
                disabled={isSubmitting || businessId === undefined}
                onClick={() => {
                  if (fields.length === 0) return setSaveWarning(true);
                  setSaveWarning(false);
                  void handleSubmit((v) => onSubmit({ ...v, save: true }))();
                }}
              >
                저장하기
              </button>
            </div>
            {/* 가정을 넣으면 저절로 사라진다 — 그때는 경고가 더 이상 참이 아니다. */}
            {saveWarning && fields.length === 0 && (
              <FormError message="넣은 가정이 없어 저장할 수 없습니다. 조건을 하나 이상 넣어 주세요." />
            )}
          </form>

          {/* 프리셋은 부가 요소다 — 기본은 위의 직접 조합하기다. 접어 두면 처음 온 사람이
            * 가정 하나를 빠르게 넣어 볼 수는 있으면서 주된 입력을 가리지 않는다. */}
          <details className="v7-panel v7-compose">
            <summary className="v7-panel__head">가상 시나리오 · 미리 만든 가정 하나로 빠르게</summary>
            <div className="v7-presets">
              {PRESETS.filter((p) => p.key !== 'shift' || biggest).map((p) => (
                <button
                  key={p.key}
                  type="button"
                  className="v7-preset"
                  aria-pressed={preset === p.key}
                  title={p.question(amounts[p.key], biggest?.title)}
                  onClick={() => setPreset(p.key)}
                >
                  <Icon name={p.icon} size={16} />
                  <span>{p.short}</span>
                </button>
              ))}
            </div>
            {/* 칩에는 짧은 이름만 적었다. 고른 뒤에는 무엇을 묻고 있는지 문장으로 보여
              * 준다 — 이름만 보고 숫자를 움직이면 무엇을 가정하는지 알 수 없다. */}
            {preset && (
              <p className="v7-note v7-note--small">
                {PRESETS.find((p) => p.key === preset)?.question(value, biggest?.title)}
              </p>
            )}
            {preset && (
              <label className="v7-preset-slider">
                <span>{PRESETS.find((p) => p.key === preset)?.unit(value)}</span>
                <input
                  type="range"
                  min={PRESETS.find((p) => p.key === preset)?.min}
                  max={PRESETS.find((p) => p.key === preset)?.max}
                  step={PRESETS.find((p) => p.key === preset)?.step}
                  value={value}
                  aria-label="가정 크기"
                  onChange={(e) => setAmounts((a) => ({ ...a, [preset]: Number(e.target.value) }))}
                />
              </label>
            )}
            {preset && (
              <div className="v7-review-actions">
                {/* 여기서 계산하지 않는다. 조건 한 줄로 올려 보내고 곡선은 「비교하기」가 낸다 —
                  * 그래야 이미 넣어 둔 조건이 덮이지 않는다. */}
                <button
                  type="button"
                  className="auth-submit v7-scenario-action"
                  onClick={addPreset}
                >
                  조건에 추가하기
                </button>
              </div>
            )}
          </details>
        </section>
      </div>

      <Section title={`저장한 시나리오 ${savedItems.length}개`}>
        {savedItems.length === 0 ? (
          <EmptyState
            title="저장한 시나리오가 없습니다"
            hint="가정을 넣고 「저장하기」를 누르면 여기에 남습니다."
          />
        ) : (
          <ul className="v7-rows">
            {savedItems.map((s) => (
              <li key={s.scenarioId}>
                <span className="d">
                  {s.name}
                  <small>
                    {(s.parameters ?? []).map((x) => describe(x, patternNames)).join(' · ')} ·{' '}
                    {formatDateTime(s.createdAt)}
                  </small>
                </span>
                <span className="v7-acts">
                  <button
                    type="button"
                    className="v7-secondary-button"
                    onClick={() => open.mutate(s.scenarioId)}
                  >
                    지금 데이터로 다시 보기
                  </button>
                  <button
                    type="button"
                    className="v7-secondary-button"
                    onClick={() => remove_.mutate(s.scenarioId)}
                  >
                    지우기
                  </button>
                </span>
              </li>
            ))}
          </ul>
        )}
      </Section>

      {/* 달력 팝업. 네이티브 dialog 라 Esc·포커스 가두기·배경 막기를 브라우저가 맡는다
        * (BasisDialog 와 같은 방식). 하단에 펼쳐 두면 조건과 곡선을 화면 밖으로 밀어낸다. */}
      <dialog
        ref={calendarDialog}
        className="v7-cal-dialog"
        aria-label="달력에서 옮기기"
        onClick={(e) => e.target === e.currentTarget && calendarDialog.current?.close()}
      >
        <div className="v7-cal-dialog__body">
          <div className="v7-cal-dialog__top">
            <h2>달력에서 옮겨 보기</h2>
            <button
              type="button"
              aria-label="닫기"
              onClick={() => calendarDialog.current?.close()}
            >
              <Icon name="close" size={18} />
            </button>
          </div>
          <PlannedCalendar
            from={daysAgo(-1)}
            to={daysAgo(-30)}
            rows={calendarRows}
            weather={weather}
            // 「외 N건」에 묶인 지출을 끌려면 그 날을 펴야 한다. 다시 누르면 접는다.
            selected={openDay}
            onSelect={(date) => setOpenDay((d) => (d === date ? '' : date))}
            moved={new Set(moves.keys())}
            /**
             * 옮기면 그 자리에서 조건이 된다.
             *
             * 예전에는 「직접 조합에 넣기」를 눌러야 폼에 들어갔다. 누르지 않으면 달력에
             * 표시만 남고 조건 목록에는 없어서, 무엇이 반영될지가 두 곳으로 갈렸다.
             *
             * 원래 날짜로 되돌리면 표시와 조건을 함께 뺀다. 한쪽만 지우면 달력은 제자리인데
             * 조건은 살아 있게 된다.
             */
            onMove={(row, date) => {
              setPreset(null);
              const id = row.plannedTransactionId;
              const original = planned.items.find(
                (p) => p.plannedTransactionId === id,
              )?.expectedDate;
              const next = new Map(moves);
              const at = shiftIndex(String(id));
              if (date === original) {
                next.delete(id);
                if (at >= 0) remove(at);
              } else {
                next.set(id, date);
                const line = {
                  ...emptyRow('EXPENSE_DATE_SHIFT'),
                  plannedTransactionId: String(id),
                  newDate: date,
                };
                // 같은 지출을 또 옮긴 것이면 날짜만 새로 쓴다. 줄이 쌓이면 어느 날짜가
                // 참인지 알 수 없다.
                if (at >= 0) update(at, line);
                else append(line);
              }
              setMoves(next);
            }}
          />
          <p className="v7-note v7-note--small">
            나갈 돈(−) 칩을 다른 날로 끌어다 놓으면 <strong>조건 설정에 바로 들어갑니다.</strong>{' '}
            원래 날짜로 되돌리면 빠집니다. 곡선은 「비교하기」를 누르면 다시 계산합니다.
            {moves.size > 0 && (
              <>
                {' '}
                <button
                  type="button"
                  className="v7-secondary-button"
                  onClick={() => {
                    // 달력 표시와 폼의 지출일 변경 줄을 함께 되돌린다. 넣은 뒤에도 표시가
                    // 남으므로, 한쪽만 지우면 달력은 원래대로인데 조건은 살아 있게 된다.
                    const ids = new Set([...moves.keys()].map(String));
                    for (let i = paramRows.length - 1; i >= 0; i -= 1)
                      if (
                        paramRows[i].type === 'EXPENSE_DATE_SHIFT' &&
                        ids.has(paramRows[i].plannedTransactionId)
                      )
                        remove(i);
                    setMoves(new Map());
                    setResult(null);
                  }}
                >
                  옮긴 {moves.size}건 되돌리기
                </button>
              </>
            )}
          </p>
        </div>
      </dialog>
    </PageShell>
  );
}

/** 두 곡선과 지표. 카드로 물은 결과와 직접 조합한 결과가 같은 자리에 뜬다. */
function Result({
  result,
  picked,
  onPick,
  safetyLine,
  patterns,
  planned,
}: {
  result: ScenarioResult;
  picked: number;
  onPick: (index: number) => void;
  safetyLine: number | null;
  /** 근거가 댈 재료 — 곡선이 기대고 있는 반복 패턴과 이 기간의 예정 거래 */
  patterns: RecurringPattern[];
  planned: PlannedTransaction[];
}) {
  const zoom = useRef<HTMLDialogElement>(null);
  /** 곡선 하나만 남기고 나머지를 지운다. 선이나 범례를 누르면 켜지고 배경을 누르면 꺼진다. */
  const [focused, setFocused] = useState<'main' | 'compare' | null>(null);
  const modified = toPoints(result.modifiedCurve);
  const original = toPoints(result.originalCurve);
  const warnings = result.warnings ?? [];
  const o = result.comparison?.original;
  const m = result.comparison?.modified;
  /**
   * 근거는 <b>결과를 다시 말하지 않는다.</b> 위의 비교표가 무엇이 어떻게 됐는지를 이미
   * 보여 주므로, 여기서는 그 곡선을 만든 재료를 댄다 — 어떤 예정 거래가 들어 있고, 반복
   * 패턴을 얼마나 믿고 있고, 넣은 가정이 그중 무엇을 건드렸는지.
   */
  const basis = scenarioBasis({
    parameters: result.parameters ?? [],
    planned,
    patterns,
    days: result.days ?? 30,
  });
  return (
    <Section title={result.name ? `결과 · ${result.name}` : '결과'}>
      <dl className="v7-facts v7-facts--wide">
        <Compare label="최저 잔액" o={o} m={m} pick={(v) => v?.lowestBalance} money />
        <Compare label="처음 부족한 날" o={o} m={m} pick={(v) => v?.firstShortfallDate ?? '없음'} />
        <Compare label="0원 아래인 날" o={o} m={m} pick={(v) => v?.gapDurationDays} unit="일" />
        <Compare
          label="안전선 아래인 날"
          o={o}
          m={m}
          pick={(v) => v?.belowSafetyLineDays}
          unit="일"
        />
      </dl>
      {/*
        * `key` 에 계산 시각을 물린다. 새로 비교할 때마다 곡선을 갈아 끼워야 왼쪽에서
        * 오른쪽으로 다시 그려진다 — 같은 요소에 좌표만 바꾸면 선이 소리 없이 자리를 옮겨
        * 무엇이 달라졌는지 모르고 지나간다.
        */}
      {/* 원본이 본선이다 — 평소 보던 날씨 색 곡선을 그대로 두고, 가정을 넣은 곡선을 보라
        * 한 가지 색으로 그 위에 얹는다. 그래야 둘이 어디서 갈라지는지가 보인다. */}
      <BalanceChart
        key={result.baseTime}
        points={original}
        compare={modified}
        compareAccent
        // 값에 따라 색이 변하면 한 곡선이 구간마다 달라 보인다. 대비되는 두 단색으로 둔다.
        mainColor="var(--mint)"
        mainLabel="원본 예보"
        compareLabel="가정 반영"
        safetyLine={safetyLine}
        narrow
        zoomable
        focused={focused}
        onFocusCurve={setFocused}
        onExpand={() => zoom.current?.showModal()}
        selected={Math.min(picked, Math.max(0, original.length - 1))}
        onSelect={onPick}
      />
      {/* 범례는 차트 아래다. 위에 두면 선 모양을 확인할 때마다 눈이 곡선과 범례를 왕복한다.
        *
        * 선 모양(실선/점선)이 어느 곡선인지, 색(맑음·흐림·비)이 그 날 잔액이 어디에 있는지를
        * 말한다. **둘 다 적어야 한다** — 색만 두면 도메인 규칙 6 을 어기고, 실제로 파랑과
        * 보라가 무슨 뜻인지 물어보게 됐다. */}
      <p className="v7-legend">
        {/* 누르면 그 곡선만 남는다. 색 조각이 곧 버튼이라 어느 선을 고르는지가 분명하다. */}
        <button
          type="button"
          className="v7-legend-pick"
          aria-pressed={focused === 'compare'}
          onClick={() => setFocused((f) => (f === 'compare' ? null : 'compare'))}
        >
          <i className="v7-scenario-line" />
          가정 반영
        </button>
        <button
          type="button"
          className="v7-legend-pick"
          aria-pressed={focused === 'main'}
          onClick={() => setFocused((f) => (f === 'main' ? null : 'main'))}
        >
          <i className="v7-scenario-line v7-scenario-line--base" />
          원본 예보
        </button>
        {focused && <span className="v7-legend__hint">배경을 누르면 둘 다 보입니다</span>}
      </p>
      {/*
        * 신뢰 수준과 근거는 <b>지우지 않고 접는다.</b> 곡선 아래에 네 줄이 늘 펼쳐져 있으면
        * 숫자를 다 읽기도 전에 경고부터 만나는데, 그렇다고 없애면 곡선만 남아 완전한 계산처럼
        * 보인다 — 도메인 규칙 1 은 예측에 범위·신뢰 수준·근거를 함께 붙이라고 못박고 있다.
        *
        * 특히 `warnings` 에는 「입금 1건이 예측 기간 밖으로 밀렸다」 처럼 <b>숫자 자체가
        * 덜 계산됐다</b>는 말이 온다.
        *
        * <b>닫힌 채로는 신뢰 수준만 보인다.</b> 알림 건수를 함께 적어 뒀었는데, 몇 건인지를
        * 안다고 할 일이 달라지지 않아 걷었다 — 읽어야 하는 것은 건수가 아니라 문장이고,
        * 그 문장은 펼치면 그대로 나온다.
        */}
      <details className="v7-panel v7-scenario-basis">
        <summary className="v7-panel__head">
          이 결과의 근거
          <span title={CONFIDENCE_HINT}>
            신뢰 {result.confidence ? (CONFIDENCE[result.confidence] ?? result.confidence) : '—'}
          </span>
        </summary>
        <div className="v7-basis">
          <h4>곡선을 만든 것</h4>
          {basis.inputs.map((line) => (
            <p key={line}>{line}</p>
          ))}
          {basis.ranking.length > 0 && (
            <>
              <h4>금액 순위</h4>
              <ul>
                {basis.ranking.map((row) => (
                  <li key={row.patternId}>{row.text}</li>
                ))}
              </ul>
            </>
          )}
          {basis.touched.length > 0 && (
            <>
              <h4>넣은 가정이 건드린 것</h4>
              <ul>
                {basis.touched.map((line) => (
                  <li key={line}>{line}</li>
                ))}
              </ul>
            </>
          )}
          {/* 설명이 길어 근거 안에서 또 접는다 — 곡선 이야기를 읽는 흐름을 끊지 않는다 */}
          <details className="v7-grade">
            <summary>확신 등급은 이렇게 매깁니다</summary>
            <table>
              <thead>
                <tr>
                  <th scope="col">점수</th>
                  <th scope="col">등급</th>
                </tr>
              </thead>
              <tbody>
                {GRADE_TABLE.map((row) => (
                  <tr key={row.grade}>
                    <td>{row.range}</td>
                    <td>{row.grade}</td>
                  </tr>
                ))}
              </tbody>
            </table>
            <p>반복 패턴 — {PATTERN_SCORE.formula}</p>
            <ul>
              {PATTERN_SCORE.notes.map((note) => (
                <li key={note}>{note}</li>
              ))}
            </ul>
            <p>예보 — {FORECAST_SCORE.formula}</p>
            <ul>
              {FORECAST_SCORE.notes.map((note) => (
                <li key={note}>{note}</li>
              ))}
            </ul>
            <p>{NOT_YET}</p>
          </details>
        </div>
        {warnings.map((w) => (
          <p key={w} className="v7-note v7-note--small">
            {w}
          </p>
        ))}
        {/* 두 문장은 하는 말이 다르다 — 앞은 이 숫자를 어떻게 읽어야 하는지고, 뒤는
          * 다음에 무엇을 할지다. 한 줄에 붙여 두면 뒤가 앞의 꼬리처럼 읽혀 넘어간다. */}
        <p className="v7-note v7-note--small">
          {formatDateTime(result.baseTime)} 예측이라 확정 금액이 아닙니다.
        </p>
        <p className="v7-note v7-note--small v7-note--cont">
          실제로 반영하려면 <Link to="/patterns">반복·예정</Link>에서 예정 거래를 고쳐 주세요.
        </p>
      </details>

      {/* 크게 보기. 4:6 칸의 차트는 훑어보는 용도라, 날짜별로 값을 짚어 보려면 넓은 자리가
        * 필요하다. 팝업 안에서는 기본 비율로 그리고 클릭이 날짜 고르기로 돌아온다. */}
      <dialog
        ref={zoom}
        className="v7-chart-dialog"
        aria-label="잔액 곡선 크게 보기"
        onClick={(e) => e.target === e.currentTarget && zoom.current?.close()}
      >
        <div className="v7-chart-dialog__body">
          <div className="v7-chart-dialog__top">
            <h2>잔액 곡선 비교</h2>
            <button type="button" aria-label="닫기" onClick={() => zoom.current?.close()}>
              <Icon name="close" size={18} />
            </button>
          </div>
          <BalanceChart
            key={result.baseTime}
            points={original}
            compare={modified}
            compareAccent
            mainColor="var(--mint)"
            mainLabel="원본 예보"
            compareLabel="가정 반영"
            safetyLine={safetyLine}
            zoomable
            focused={focused}
            onFocusCurve={setFocused}
            selected={Math.min(picked, Math.max(0, original.length - 1))}
            onSelect={onPick}
          />
          <p className="v7-legend">
            <button
              type="button"
              className="v7-legend-pick"
              aria-pressed={focused === 'compare'}
              onClick={() => setFocused((f) => (f === 'compare' ? null : 'compare'))}
            >
              <i className="v7-scenario-line" />
              가정 반영
            </button>
            <button
              type="button"
              className="v7-legend-pick"
              aria-pressed={focused === 'main'}
              onClick={() => setFocused((f) => (f === 'main' ? null : 'main'))}
            >
              <i className="v7-scenario-line v7-scenario-line--base" />
              원본 예보
            </button>
            {focused && <span className="v7-legend__hint">배경을 누르면 둘 다 보입니다</span>}
          </p>
        </div>
      </dialog>
    </Section>
  );
}

/** 원본 → 가정 반영. 값만 나란히 두고 차이 계산은 하지 않는다 — 날짜·일수·금액을 그대로 읽게 한다. */
function Compare({
  label,
  o,
  m,
  pick,
  money,
  unit = '',
}: {
  label: string;
  o?: ScenarioSummary;
  m?: ScenarioSummary;
  pick: (s?: ScenarioSummary) => number | string | undefined;
  money?: boolean;
  unit?: string;
}) {
  const text = (v: number | string | undefined) =>
    v === undefined ? '—' : money && typeof v === 'number' ? formatManwon(v) : `${v}${unit}`;
  const after = pick(m);
  return (
    <div>
      <dt>{label}</dt>
      <dd className={money && typeof after === 'number' && after < 0 ? 'is-gap' : ''}>
        <span className="v7-muted">{text(pick(o))} →</span> {text(after)}
      </dd>
    </div>
  );
}
