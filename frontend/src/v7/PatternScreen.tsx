import { useEffect, useMemo, useState } from 'react';
import { useForm, useWatch } from 'react-hook-form';
import { zodResolver } from '@hookform/resolvers/zod';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { z } from 'zod';
import {
  confirmRecurringPattern,
  createPlannedTransaction,
  daysAgo,
  deletePlannedTransaction,
  detectRecurringPatterns,
  dismissRecurringPattern,
  formatManwonRange,
  listAccounts,
  listPlannedTransactions,
  listRecurringPatterns,
  updatePlannedTransaction,
  updateRecurringPattern,
} from '@jangoyebo/core';
import type {
  Account,
  ApiResult,
  Direction,
  PlannedTransaction,
  RecurringPattern,
} from '@jangoyebo/core';
import { useBusiness } from '@/business/useBusiness';
import { EmptyState } from '@/components/EmptyState';
import { Field, FormError } from './AuthShell';
import { Icon } from './icons';
import { PageShell, Section } from './PageShell';
import { cycleLabel, PATTERN_CONFIDENCE } from './patternView';
import { PlannedCalendar } from './PlannedCalendar';
import { useForecast, useSafetyLine } from './model';
import { weatherOf } from './Weather';
import type { Weather } from './Weather';

/**
 * 반복·예정 (SC-05, P0-03·04·05, API 9장).
 *
 * 반복을 승인하면 서버가 앞으로 90일 예정 거래를 만들고, 그 예정 거래가 잔액 예보
 * 곡선의 직접 입력이 된다. 이 화면이 비어 있으면 곡선은 현재 잔액 그대로 평평하다.
 *
 * 승인·멈춤·예정 거래 변경은 서버가 곡선을 다시 계산하게 하지만 5초 디바운스 뒤다
 * (구현현황 3.19). 예보 캐시를 바로 한 번, 계산이 끝났을 즈음 한 번 더 버린다.
 */

// 주기·신뢰 라벨은 시나리오 근거도 쓴다 — 두 화면이 같은 패턴을 다른 말로 부르면 안 된다
const CONFIDENCE = PATTERN_CONFIDENCE;

const STATUS: Record<PlannedTransaction['status'], { label: string; icon: string }> = {
  EXPECTED: { label: '예정', icon: 'clock' },
  CONFIRMED: { label: '확정', icon: 'check' },
  MATCHED: { label: '실제 거래 확인', icon: 'check' },
  MISSED: { label: '안 들어옴', icon: 'warning' },
  CANCELLED: { label: '취소됨', icon: 'close' },
};

const SOURCE: Record<PlannedTransaction['source'], string> = {
  USER: '직접 넣음',
  PATTERN: '반복에서 생성',
  TAX: '세금',
  LOAN: '대출 상환',
};

/**
 * 한 줄의 금액. 부호를 붙여 색에만 기대지 않는다(도메인 규칙 6).
 *
 * <b>단위를 하나로 쓴다.</b> 전에는 값이 하나면 원(`1,340,000원`), 범위면 만원
 * (`120~140만원`)이라 같은 칸에서 단위가 갈렸다 — 목록을 훑을 때 자릿수가 눈에 안 잡히고
 * 130만원과 1,300,000원이 한 줄 건너 나왔다.
 *
 * 분기는 필요 없었다. `formatManwonRange` 가 <b>줄여 써서 같아지는 범위는 이미 하나로
 * 접고</b>(money.ts), 만 미만은 `formatManwon` 이 원 단위로 그대로 둔다 — 9,500원짜리
 * 구독료가 「1만원」으로 뭉개지지 않는다.
 */
function amountText(direction: Direction, min: number, max: number) {
  const sign = direction === 'DEPOSIT' ? '+' : '−';
  return `${sign}${formatManwonRange(min, max)}`;
}

/** 실패 사유. 성공이면 null. */
function failure(result: ApiResult<unknown>): string | null {
  if (!result.ready) return '이 기능이 아직 준비되지 않았습니다.';
  return result.ok ? null : result.error.message;
}

const parseWon = (value: string) => Number(value.replace(/,/g, '') || '0');
const won = z
  .string()
  .refine((value) => value === '' || /^\d[\d,]*$/.test(value), '0 이상의 숫자만 입력해 주세요.');

const schema = z
  .object({
    direction: z.enum(['DEPOSIT', 'WITHDRAWAL']),
    title: z.string().trim().min(1, '무슨 거래인지 적어 주세요.'),
    expectedDate: z.string().min(1, '날짜를 골라 주세요.'),
    // 빈 문자열은 「고르지 않음」이다. 계좌를 모르는 예정 거래도 곡선에는 들어간다 —
    // 곡선이 사업장 합계라서다. 다만 자동 반영(API 9.6)은 계좌 단위라 그쪽만 못 받는다.
    accountId: z.string(),
    amountMin: won
      .refine((value) => value !== '', '금액을 입력해 주세요.')
      .refine((value) => value === '' || parseWon(value) > 0, '0원보다 커야 합니다.'),
    amountMax: won,
  })
  .refine((v) => v.expectedDate >= daysAgo(0), {
    path: ['expectedDate'],
    message: '오늘 이후 날짜를 골라 주세요.',
  })
  .refine((v) => v.amountMax === '' || parseWon(v.amountMax) >= parseWon(v.amountMin), {
    path: ['amountMax'],
    message: '최대 금액은 최소 금액보다 작을 수 없습니다.',
  });

type Form = z.infer<typeof schema>;

interface Notice {
  tone: 'good' | 'bad';
  title: string;
  text: string;
}

export function PatternScreen() {
  const { current } = useBusiness();
  const businessId = current?.businessId;
  const queryClient = useQueryClient();
  const [notice, setNotice] = useState<Notice | null>(null);
  // 되돌릴 수 없는 동작(멈춤·취소·삭제)은 모달 없이 그 자리에서 한 번 더 묻는다.
  const [asking, setAsking] = useState<string | null>(null);

  // from·to 필수, 최대 92일이다.
  const from = daysAgo(0);
  const to = daysAgo(-90);

  const patterns = useQuery({
    queryKey: ['v7', 'patterns', businessId],
    enabled: businessId !== undefined,
    queryFn: () => listRecurringPatterns(businessId as number),
  });

  // 예정 거래를 어느 통장 기준으로 볼지. 인증이 끝난 계좌만 고를 수 있다 —
  // 인증 전·해제된 계좌는 예측 대상이 아니라 붙여 둬도 곡선에 안 잡힌다(API 7.2).
  const accounts = useQuery({
    queryKey: ['v7', 'accounts', businessId],
    enabled: businessId !== undefined,
    queryFn: () => listAccounts(businessId as number),
  });
  // useMemo 가 없으면 매 렌더마다 새 배열이라 아래 자동 채움 효과가 계속 다시 돈다.
  const connected: Account[] = useMemo(
    () =>
      accounts.data?.ready && accounts.data.ok
        ? accounts.data.data.items.filter((a) => a.status === 'CONNECTED')
        : [],
    [accounts.data],
  );

  const planned = useQuery({
    queryKey: ['v7', 'planned', businessId, 'manage', from],
    enabled: businessId !== undefined,
    // 한 페이지 상한이 100건이라 모자라게 올 때까지 이어 부른다. 영업일마다 도는 반복 하나만
    // 있어도 90일에 60건을 넘는다. 20페이지(2,000건)에서 멈추는 것은 무한 반복을 막는 안전장치다.
    queryFn: async () => {
      const items: PlannedTransaction[] = [];
      for (let page = 0; ; page++) {
        const result = await listPlannedTransactions(businessId as number, { from, to, size: 100, page });
        if (!result.ready || !result.ok) return result;
        items.push(...result.data.items);
        if (result.data.items.length < 100 || page >= 19) return { ...result, data: { items } };
      }
    },
  });

  function afterChange(result: ApiResult<unknown>, done: Omit<Notice, 'tone'>) {
    setAsking(null);
    const reason = failure(result);
    if (reason) {
      setNotice({ tone: 'bad', title: '반영하지 못했습니다', text: reason });
      return;
    }
    setNotice({ tone: 'good', ...done });
    queryClient.invalidateQueries({ queryKey: ['v7', 'patterns'] });
    // 대시보드의 「가까운 입출금」도 같은 접두어라 함께 버려진다.
    queryClient.invalidateQueries({ queryKey: ['v7', 'planned'] });
    const dropForecast = () => {
      queryClient.invalidateQueries({ queryKey: ['v7', 'forecast'] });
      queryClient.invalidateQueries({ queryKey: ['v7', 'cash-gaps'] });
    };
    dropForecast();
    setTimeout(dropForecast, 10_000);
  }

  const detect = useMutation({
    mutationFn: () => detectRecurringPatterns(businessId as number),
    onSuccess: (result) => {
      if (result.ready && result.ok) {
        queryClient.invalidateQueries({ queryKey: ['v7', 'patterns', businessId] });
      }
    },
  });

  const confirm = useMutation({
    mutationFn: (p: RecurringPattern) => confirmRecurringPattern(p.patternId),
    onSuccess: (result, p) =>
      afterChange(result, {
        title: `${p.name} · 반복으로 확인했습니다`,
        text: '앞으로 90일 예정 거래가 만들어져 잔액 예보에 들어갑니다. 곡선은 몇 초 뒤 다시 계산됩니다.',
      }),
  });

  /**
   * 후보를 한 번에 확인한다.
   *
   * 일괄 API 가 없어 한 건씩 보낸다. **동시에 쏘지 않는다** — 확인 한 건마다 서버가
   * 90일 예정 거래를 만들고 예보 재계산을 예약한다. 몰아 보내면 어느 건이 실패했는지
   * 가릴 수 없고, 일부만 들어간 채로 화면이 성공으로 보인다.
   *
   * 캐시 버리기와 안내는 afterChange 가 맡되 **끝에 한 번만** 부른다.
   */
  const confirmAll = useMutation({
    mutationFn: async (list: RecurringPattern[]) => {
      const failed: string[] = [];
      let last: ApiResult<unknown> | null = null;
      for (const pattern of list) {
        const result = await confirmRecurringPattern(pattern.patternId);
        last = result;
        if (failure(result)) failed.push(pattern.name);
      }
      return { failed, done: list.length - failed.length, last };
    },
    onSuccess: ({ failed, done, last }) => {
      if (failed.length && last) {
        setAsking(null);
        setNotice({
          tone: 'bad',
          title: `${done}건은 확인했고 ${failed.length}건은 실패했습니다`,
          text: `실패: ${failed.join(', ')} — 다시 시도해 주세요.`,
        });
        queryClient.invalidateQueries({ queryKey: ['v7', 'patterns'] });
        queryClient.invalidateQueries({ queryKey: ['v7', 'planned'] });
        return;
      }
      afterChange(last ?? { ready: true, ok: true, data: null } as ApiResult<unknown>, {
        title: `${done}건을 반복으로 확인했습니다`,
        text: '앞으로 90일 예정 거래가 만들어져 잔액 예보에 들어갑니다. 곡선은 몇 초 뒤 다시 계산됩니다.',
      });
    },
  });

  const dismiss = useMutation({
    mutationFn: (p: RecurringPattern) => dismissRecurringPattern(p.patternId),
    onSuccess: (result, p) =>
      afterChange(result, {
        title: `${p.name} · 후보에서 내렸습니다`,
        text: '다시 찾아도 이 후보는 올라오지 않습니다.',
      }),
  });

  const setPatternStatus = useMutation({
    mutationFn: (v: { pattern: RecurringPattern; status: 'CONFIRMED' | 'PAUSED' }) =>
      updateRecurringPattern(v.pattern.patternId, { status: v.status }),
    onSuccess: (result, v) =>
      afterChange(
        result,
        v.status === 'PAUSED'
          ? {
              title: `${v.pattern.name} · 멈췄습니다`,
              text: '앞으로의 예정 거래도 함께 취소돼 예보 곡선에서 빠집니다.',
            }
          : {
              title: `${v.pattern.name} · 다시 씁니다`,
              text: '멈춘 동안 취소된 예정 거래는 되살아나지 않습니다. 필요한 날은 아래에서 직접 넣어 주세요.',
            },
      ),
  });

  const cancelPlanned = useMutation({
    mutationFn: (row: PlannedTransaction) =>
      updatePlannedTransaction(row.plannedTransactionId, { status: 'CANCELLED' }),
    onSuccess: (result, row) =>
      afterChange(result, {
        title: `${row.title} · 취소했습니다`,
        text: '이 날의 예정 거래가 곡선에서 빠집니다.',
      }),
  });

  // 삭제는 직접 넣은 것(source=USER)만 된다. 나머지는 취소로 대신한다(구현현황 3.13).
  const deletePlanned = useMutation({
    mutationFn: (row: PlannedTransaction) => deletePlannedTransaction(row.plannedTransactionId),
    onSuccess: (result, row) =>
      afterChange(result, {
        title: `${row.title} · 지웠습니다`,
        text: '곡선에서도 빠집니다.',
      }),
  });

  const {
    register,
    handleSubmit,
    reset,
    setError,
    setValue,
    control,
    formState: { errors, isSubmitting },
  } = useForm<Form>({
    resolver: zodResolver(schema),
    mode: 'onTouched',
    defaultValues: {
      direction: 'WITHDRAWAL',
      title: '',
      expectedDate: from,
      amountMin: '',
      amountMax: '',
      accountId: '',
    },
  });

  const create = useMutation({
    mutationFn: (v: Form) => {
      const min = parseWon(v.amountMin);
      const max = v.amountMax === '' ? min : parseWon(v.amountMax);
      // 금액은 확정 또는 범위 중 정확히 한 형태로 보낸다. 둘 다 보내면 400 이다.
      return createPlannedTransaction(businessId as number, {
        direction: v.direction,
        title: v.title.trim(),
        expectedDate: v.expectedDate,
        ...(v.accountId === '' ? {} : { accountId: Number(v.accountId) }),
        ...(min === max ? { amount: min } : { amountMin: min, amountMax: max }),
      });
    },
    onSuccess: (result, v) => {
      const reason = failure(result);
      if (reason) {
        setError('root', { message: `예정 거래를 넣지 못했습니다 — ${reason}` });
        return;
      }
      // 넣은 뒤에도 고른 날짜에 머문다 — 같은 날 여러 건을 이어 넣는 경우가 많다.
      // 계좌는 남긴다 — 같은 통장에 여러 건을 이어 넣는 경우가 많다.
      reset({
        direction: v.direction,
        title: '',
        expectedDate: v.expectedDate,
        amountMin: '',
        amountMax: '',
        accountId: v.accountId,
      });
      afterChange(result, {
        title: `${v.title.trim()} · 예정 거래를 넣었습니다`,
        text: '잔액 예보에 들어갑니다. 곡선은 몇 초 뒤 다시 계산됩니다.',
      });
    },
  });

  // 통장이 하나뿐이면 고를 것이 없다 — 칸을 보이지 않고 조용히 채운다. 여러 개인 사람만
  // 고르게 하는 편이, 대부분의 사장님에게 묻지 않아도 되는 것을 묻지 않는 길이다.
  useEffect(() => {
    if (connected.length === 1) {
      setValue('accountId', String(connected[0].accountId));
    }
  }, [connected, setValue]);

  // 달력에서 고른 날이 곧 예정일이다. 폼에는 날짜 칸을 두지 않는다.
  const picked = useWatch({ control, name: 'expectedDate' });
  const forecast = useForecast(businessId);
  const safety = useSafetyLine(businessId);
  const safetyLine = forecast.forecast?.safetyLine ?? safety.amount;
  const curve = forecast.forecast?.curve ?? [];
  const weather = new Map<string, Weather>();
  for (const day of curve) {
    if (day.date && day.expectedBalance !== undefined) weather.set(day.date, weatherOf(day.expectedBalance, safetyLine));
  }
  const pickedDay = curve.find((day) => day.date === picked);

  const patternResult = patterns.data;
  const patternItems = patternResult?.ready && patternResult.ok ? patternResult.data.items : [];
  const patternError = patternResult?.ready && !patternResult.ok ? patternResult.error.message : null;
  const candidates = patternItems.filter((p) => p.status === 'CANDIDATE');
  const active = patternItems.filter((p) => p.status === 'CONFIRMED' || p.status === 'PAUSED');

  const plannedResult = planned.data;
  const plannedItems = plannedResult?.ready && plannedResult.ok ? plannedResult.data.items : [];
  const plannedError = plannedResult?.ready && !plannedResult.ok ? plannedResult.error.message : null;
  const rows = plannedItems.filter((row) => row.status !== 'CANCELLED');
  const cancelledCount = plannedItems.length - rows.length;

  // ponytail: 탐지를 언제 돌릴지 서버가 아직 정하지 않았다(구현현황 3.15 — 명세 밖 임시 경로).
  //   상시 버튼 대신 반복이 하나도 없을 때(처음 들어온 사업장)만 한 번 부른다.
  //   탐지 배치가 붙으면 이 effect 와 detect 를 지운다.
  const neverDetected = patternResult?.ready === true && patternResult.ok && patternItems.length === 0;
  const { isIdle: detectIdle, mutate: runDetect } = detect;
  useEffect(() => {
    if (neverDetected && detectIdle) runDetect();
  }, [neverDetected, detectIdle, runDetect]);

  const busy =
    confirm.isPending ||
    confirmAll.isPending ||
    dismiss.isPending ||
    setPatternStatus.isPending ||
    cancelPlanned.isPending ||
    deletePlanned.isPending;

  return (
    <PageShell
      title="반복·예정"
    >
      {notice && (
        <div
          className={`v7-note ${notice.tone === 'good' ? 'v7-note--good' : 'v7-note--bad'}`}
          role={notice.tone === 'good' ? 'status' : 'alert'}
        >
          <Icon name={notice.tone === 'good' ? 'check' : 'warning'} size={18} />
          <div>
            <strong>{notice.title}</strong>
            <span>{notice.text}</span>
          </div>
        </div>
      )}

      <Section title="예정 거래 달력">
        <div className="v7-cal-layout">
          <PlannedCalendar
            from={from}
            to={to}
            rows={rows}
            weather={weather}
            selected={picked}
            onSelect={(date) => setValue('expectedDate', date, { shouldValidate: true })}
          />
          <div className="v7-cal-panel">
            <p className="v7-cal-panel__date">
              {Number(picked.slice(5, 7))}월 {Number(picked.slice(8, 10))}일{' '}
              {['일', '월', '화', '수', '목', '금', '토'][new Date(`${picked}T00:00:00Z`).getUTCDay()]}요일
            </p>
            {pickedDay?.balanceMin !== undefined && pickedDay.balanceMax !== undefined && (
              <p className="v7-figure">예상 잔액 {formatManwonRange(pickedDay.balanceMin, pickedDay.balanceMax)}</p>
            )}
            <ul className="v7-rows">
              {rows.filter((row) => row.expectedDate === picked).length === 0 ? (
                <li className="v7-muted">이 날짜에 예정된 거래가 없습니다</li>
              ) : (
                rows
                  .filter((row) => row.expectedDate === picked)
                  .map((row) => (
                    <li key={row.plannedTransactionId}>
                      <span className="d">
                        {row.title}
                        <small>{SOURCE[row.source]}</small>
                      </span>
                      <span className={`v ${row.direction === 'DEPOSIT' ? 'in' : 'out'}`}>
                        {amountText(row.direction, row.amountMin, row.amountMax)}
                      </span>
                    </li>
                  ))
              )}
            </ul>
            <form
              className="v7-settlement-form v7-cal-panel__form"
              onSubmit={handleSubmit((values) => create.mutateAsync(values))}
              noValidate
            >
              <input type="hidden" {...register('expectedDate')} />
              <FormError message={errors.expectedDate?.message} />
              <Field label="입금·출금" error={errors.direction?.message}>
                <select {...register('direction')}>
                  <option value="WITHDRAWAL">출금 (나갈 돈)</option>
                  <option value="DEPOSIT">입금 (들어올 돈)</option>
                </select>
              </Field>
              {connected.length > 1 && (
                <Field
                  label="통장"
                  error={errors.accountId?.message}
                  hint="고르지 않으면 사업장 전체 기준으로만 예보에 들어갑니다."
                >
                  <select {...register('accountId')}>
                    <option value="">고르지 않음</option>
                    {connected.map((a) => (
                      <option key={a.accountId} value={String(a.accountId)}>
                        {a.alias} · {a.accountNoMasked}
                      </option>
                    ))}
                  </select>
                </Field>
              )}
              <Field label="내용" error={errors.title?.message}>
                <input type="text" placeholder="에스프레소 머신 교체" {...register('title')} />
              </Field>
              <div className="v7-form-grid v7-form-grid--two">
                <Field label="금액" error={errors.amountMin?.message}>
                  <input type="text" inputMode="numeric" placeholder="1500000" {...register('amountMin')} />
                </Field>
                <Field label="최대 금액" error={errors.amountMax?.message} hint="정확히 모르면 범위로">
                  <input type="text" inputMode="numeric" placeholder="비우면 확정" {...register('amountMax')} />
                </Field>
              </div>
              <FormError message={errors.root?.message} />
              <button type="submit" className="auth-submit" disabled={isSubmitting || businessId === undefined}>
                {isSubmitting ? '넣는 중…' : '내역 넣기'}
              </button>
            </form>
          </div>
        </div>
      </Section>

      <div className="v7-cols">
        <Section
          title={`확인할 반복 후보 ${candidates.length}건`}
          action={
            candidates.length > 1 ? (
              asking === 'confirm-all' ? (
                <span className="v7-acts">
                  <span className="v7-ask" role="status">
                    {candidates.length}건 모두 확인하면 각각 90일 예정 거래가 만들어집니다.
                  </span>
                  <button
                    type="button"
                    className="v7-secondary-button"
                    disabled={busy}
                    onClick={() => confirmAll.mutate(candidates)}
                  >
                    {confirmAll.isPending ? '확인하는 중…' : '모두 확인'}
                  </button>
                  <button type="button" className="v7-secondary-button" onClick={() => setAsking(null)}>
                    그만두기
                  </button>
                </span>
              ) : (
                <button
                  type="button"
                  className="v7-panel__head-action"
                  disabled={busy}
                  onClick={() => setAsking('confirm-all')}
                >
                  모두 확인
                </button>
              )
            ) : null
          }
        >
          {patterns.isPending || detect.isPending ? (
            <p className="v7-note">지난 거래에서 반복을 찾는 중…</p>
          ) : patternError ? (
            <div className="v7-note v7-note--bad" role="alert">
              <Icon name="warning" size={18} />
              <div>
                <strong>반복 패턴을 불러오지 못했습니다</strong>
                <span>{patternError}</span>
              </div>
            </div>
          ) : candidates.length === 0 ? (
            <EmptyState
              title="확인할 후보가 없습니다"
              hint="같은 거래가 비슷한 간격으로 3번 이상 있어야 반복으로 봅니다."
            />
          ) : (
            <ul className="v7-rows v7-scroll" tabIndex={0} aria-label="확인할 반복 후보 목록">
              {candidates.map((p) => (
                <li key={p.patternId}>
                  <span className="d">
                    {p.name}
                    <small>
                      {cycleLabel(p.cycle)} · {p.direction === 'DEPOSIT' ? '입금' : '출금'} ·{' '}
                      {CONFIDENCE[p.confidence]}
                    </small>
                  </span>
                  <span className={`v ${p.direction === 'DEPOSIT' ? 'in' : 'out'}`}>
                    {amountText(p.direction, p.amountRange.min, p.amountRange.max)}
                  </span>
                  <span className="v7-acts">
                    <button
                      type="button"
                      className="v7-secondary-button"
                      disabled={busy}
                      onClick={() => confirm.mutate(p)}
                    >
                      맞아요
                    </button>
                    <button
                      type="button"
                      className="v7-secondary-button"
                      disabled={busy}
                      onClick={() => dismiss.mutate(p)}
                    >
                      아니에요
                    </button>
                  </span>
                </li>
              ))}
            </ul>
          )}
        </Section>

        <Section title={`쓰고 있는 반복 ${active.length}건`}>
          {patterns.isPending ? (
            <p className="v7-note">불러오는 중…</p>
          ) : active.length === 0 ? (
            <EmptyState
              title="확인한 반복이 없습니다"
              hint="왼쪽 후보를 확인하면 여기로 옮겨지고 예정 거래가 만들어집니다."
            />
          ) : (
            <ul className="v7-rows v7-scroll" tabIndex={0} aria-label="쓰고 있는 반복 목록">
              {active.map((p) => {
                const paused = p.status === 'PAUSED';
                const key = `pause-${p.patternId}`;
                return (
                  <li key={p.patternId} data-paused={paused || undefined}>
                    <span className="d">
                      {p.name}
                      <small>
                        {cycleLabel(p.cycle)} ·{' '}
                        {paused
                          ? '멈춤'
                          : p.nextExpected
                            ? `다음 ${p.nextExpected.date}`
                            : '다음 예정일 미정'}
                      </small>
                    </span>
                    <span className={`v ${p.direction === 'DEPOSIT' ? 'in' : 'out'}`}>
                      {amountText(p.direction, p.amountRange.min, p.amountRange.max)}
                    </span>
                    <span className="v7-acts">
                      {paused ? (
                        <button
                          type="button"
                          className="v7-secondary-button"
                          disabled={busy}
                          onClick={() => setPatternStatus.mutate({ pattern: p, status: 'CONFIRMED' })}
                        >
                          다시 쓰기
                        </button>
                      ) : asking === key ? (
                        <>
                          <span className="v7-ask" role="status">
                            앞으로의 예정 거래가 함께 취소되고, 다시 켜도 돌아오지 않습니다.
                          </span>
                          <button
                            type="button"
                            className="v7-secondary-button"
                            disabled={busy}
                            onClick={() => setPatternStatus.mutate({ pattern: p, status: 'PAUSED' })}
                          >
                            멈추기
                          </button>
                          <button
                            type="button"
                            className="v7-secondary-button"
                            onClick={() => setAsking(null)}
                          >
                            그대로 두기
                          </button>
                        </>
                      ) : (
                        <button
                          type="button"
                          className="v7-secondary-button"
                          disabled={busy}
                          onClick={() => setAsking(key)}
                        >
                          멈추기
                        </button>
                      )}
                    </span>
                  </li>
                );
              })}
            </ul>
          )}
        </Section>
      </div>

      <Section title={`앞으로 90일 예정 거래 ${rows.length}건`}>
        {planned.isPending ? (
          <p className="v7-note">불러오는 중…</p>
        ) : plannedError ? (
          <div className="v7-note v7-note--bad" role="alert">
            <Icon name="warning" size={18} />
            <div>
              <strong>예정 거래를 불러오지 못했습니다</strong>
              <span>{plannedError}</span>
            </div>
          </div>
        ) : rows.length === 0 ? (
          <EmptyState
            title="예정된 거래가 없습니다"
            hint="반복을 확인하거나 아래에서 직접 넣으면 여기에 채워지고 곡선에 들어갑니다."
          />
        ) : (
          <div className="tx-table-wrap v7-scroll" tabIndex={0} aria-label="앞으로 90일 예정 거래 표">
            <table className="tx-table">
              <thead>
                <tr>
                  <th scope="col">예정일</th>
                  <th scope="col">내용</th>
                  <th scope="col" className="tx-num">
                    예상 금액
                  </th>
                  <th scope="col">상태</th>
                  <th scope="col">
                    <span className="sr-only">동작</span>
                  </th>
                </tr>
              </thead>
              <tbody>
                {rows.map((row) => {
                  const status = STATUS[row.status];
                  const open = row.status === 'EXPECTED' || row.status === 'CONFIRMED';
                  const deletable = row.source === 'USER';
                  const key = `plan-${row.plannedTransactionId}`;
                  return (
                    <tr key={row.plannedTransactionId}>
                      <td data-label="예정일" className="tnum v7-date">{row.expectedDate}</td>
                      <td data-label="내용">
                        {row.title}
                        <small>
                          {SOURCE[row.source]}
                          {row.memo ? ` · ${row.memo}` : ''}
                        </small>
                      </td>
                      <td data-label="예상 금액" className="tx-num" data-direction={row.direction}>
                        {amountText(row.direction, row.amountMin, row.amountMax)}
                      </td>
                      <td data-label="상태">
                        <span
                          className="v7-settlement-status"
                          data-status={row.status === 'MATCHED' ? 'MATCHED' : undefined}
                        >
                          <Icon name={status.icon} size={14} />
                          {status.label}
                        </span>
                        {row.status === 'MATCHED' && <small>이미 잔액에 반영돼 곡선에서 빠짐</small>}
                      </td>
                      <td>
                        {open && (
                          <span className="v7-acts">
                            {asking === key ? (
                              <>
                                <span className="v7-ask" role="status">
                                  {deletable ? '지우면 되돌릴 수 없습니다.' : '취소하면 되돌릴 수 없습니다.'}
                                </span>
                                <button
                                  type="button"
                                  className="v7-secondary-button"
                                  disabled={busy}
                                  onClick={() =>
                                    deletable ? deletePlanned.mutate(row) : cancelPlanned.mutate(row)
                                  }
                                >
                                  {deletable ? '지우기' : '취소하기'}
                                </button>
                                <button
                                  type="button"
                                  className="v7-secondary-button"
                                  onClick={() => setAsking(null)}
                                >
                                  그대로 두기
                                </button>
                              </>
                            ) : (
                              <button
                                type="button"
                                className="v7-secondary-button"
                                disabled={busy}
                                onClick={() => setAsking(key)}
                              >
                                {deletable ? '지우기' : '취소'}
                              </button>
                            )}
                          </span>
                        )}
                      </td>
                    </tr>
                  );
                })}
              </tbody>
            </table>
          </div>
        )}
        {cancelledCount > 0 && (
          <p className="v7-note v7-note--small">
            취소한 {cancelledCount}건은 곡선에 들어가지 않아 목록에서 뺐습니다.
          </p>
        )}
      </Section>

    </PageShell>
  );
}
