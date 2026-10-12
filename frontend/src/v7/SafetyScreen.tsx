import { useState } from 'react';
import { useForm } from 'react-hook-form';
import { zodResolver } from '@hookform/resolvers/zod';
import { z } from 'zod';
import { useMutation, useQueryClient } from '@tanstack/react-query';
import { formatDateTime, formatManwon, formatMoney, putSafetyLine } from '@jangoyebo/core';
import type { SafetyLineMethod } from '@jangoyebo/core';
import { useBusiness } from '@/business/useBusiness';
import { Field, FormError } from './AuthShell';
import { PageShell, Section } from './PageShell';
import { Icon } from './icons';
import { money, useForecast, useSafetyLine } from './model';

/**
 * 자금 안전 — 운영자금 안전선 (API 12.1, SC-03-3).
 *
 * 설정 화면처럼 보이지만 실제로는 **예보를 켜는 스위치**다.
 * 안전선이 없으면 위험 등급 네 단계 중 0원 아래(공백)만 판정할 수 있어
 * 「주의」와 「흐림」이 구조적으로 나오지 않고, 경고도 0원 아래에서만 온다.
 *
 * ⚠ 하회일·부족액·원인은 **서버가 준 `current` 블록을 그대로 쓴다**. 곡선을 여기서 다시
 *   훑어 계산하면 알림 문구와 화면이 어긋난다 — 수치는 백엔드 엔진이 만든다(도메인 규칙 2).
 *
 * ⚠ 추천값(safetyLine.recommendations)은 아직 없다. 고정비 집계가 필요한 P1 기능이라
 *   여기서 임의로 계산해 채우지 않는다 — 세무·자금 값을 자동 확정하지 않는다는 규칙이다.
 */

const METHOD_LABEL: Record<SafetyLineMethod, string> = {
  MANUAL: '직접 정함',
  FIXED_COST_BASED: '고정비 기준',
  ESSENTIAL_EXPENSE_BASED: '필수 지출 기준',
};
const METHOD_HINT: Record<SafetyLineMethod, string> = {
  MANUAL: '',
  FIXED_COST_BASED: '임대료·인건비처럼 매달 나가는 돈을 기준으로 잡았습니다.',
  ESSENTIAL_EXPENSE_BASED: '없으면 장사가 멈추는 지출만 기준으로 잡았습니다.',
};

const schema = z.object({
  amount: z
    .string()
    .min(1, '금액을 입력해 주세요.')
    .refine((v) => /^\d+$/.test(v.replace(/,/g, '')), '숫자만 입력해 주세요.')
    .refine((v) => Number(v.replace(/,/g, '')) > 0, '0원보다 커야 합니다.'),
  method: z.enum(['MANUAL', 'FIXED_COST_BASED', 'ESSENTIAL_EXPENSE_BASED']),
});
type Form = z.infer<typeof schema>;

/** '2026-09-14' → '9월 14일'. LocalDate 라 시각이 없다 — formatDate 를 쓰면 안 된다. */
function dayLabel(date: string | undefined): string {
  if (!date) return '—';
  const [, m, d] = date.split('-');
  return m && d ? `${Number(m)}월 ${Number(d)}일` : date;
}

export function SafetyScreen() {
  const { current } = useBusiness();
  const businessId = current?.businessId;
  const queryClient = useQueryClient();
  const [saved, setSaved] = useState('');

  const safety = useSafetyLine(businessId);
  const forecast = useForecast(businessId);

  // 곡선이 내려준 값을 먼저 본다. 같은 실행을 근거로 해야 화면끼리 말이 안 갈린다.
  const line = forecast.forecast?.safetyLine ?? safety.amount;
  const method = safety.method ?? 'MANUAL';
  const now = safety.current;

  // 계좌 잔액을 여기서 다시 더하지 않는다 — 화면마다 다르게 세면 같은 사업장의
  // 총잔액이 화면마다 달라진다(model.ts useAccounts 주석).
  const balance = now?.currentBalance ?? forecast.forecast?.currentBalance;
  const gapToLine = line === null || balance === undefined ? null : balance - line;

  // 최저점도 서버 값이 먼저다. 없을 때만(예측 실행 전) 곡선에서 찾는다.
  const curveLowest = forecast.points.length
    ? forecast.points.reduce((a, b) => (a.value < b.value ? a : b))
    : null;
  const lowestValue = now?.next30dLowestBalance ?? curveLowest?.value;
  const lowestLabel = now?.next30dLowestDate
    ? dayLabel(now.next30dLowestDate)
    : (curveLowest?.label ?? null);

  const belowDate = now?.firstBelowLineDate;
  const shortfall = now?.shortfallAgainstLine;
  const causes = now?.causes ?? [];

  const {
    register,
    handleSubmit,
    setError,
    formState: { errors, isSubmitting },
  } = useForm<Form>({
    resolver: zodResolver(schema),
    mode: 'onTouched',
    values: { amount: line !== null ? String(line) : '', method },
  });

  const save = useMutation({
    mutationFn: (values: Form) =>
      putSafetyLine(businessId as number, {
        amount: Number(values.amount.replace(/,/g, '')),
        method: values.method,
      }),
    onSuccess: (res) => {
      if (!res.ready) {
        setError('root', { message: '안전선 기능이 아직 준비되지 않았습니다.' });
        return;
      }
      if (!res.ok) {
        setError('root', { message: `안전선을 저장하지 못했습니다 — ${res.error.message}` });
        return;
      }
      setSaved('안전선을 저장했습니다. 이 값을 기준으로 주의·위험 등급이 매겨지고, 아래로 내려갈 것 같으면 미리 알려 드립니다.');
      // 곡선도 이 값을 쓴다. 예보 캐시를 그대로 두면 화면마다 다른 기준선을 그린다.
      queryClient.invalidateQueries({ queryKey: ['v7', 'safety-line'] });
      queryClient.invalidateQueries({ queryKey: ['v7', 'forecast'] });
      queryClient.invalidateQueries({ queryKey: ['v7', 'cash-gaps'] });
      // 서버가 저장 직후 하회 알림을 새 기준으로 다시 맞춘다(API 12.1).
      // 목록을 그대로 두면 옛 기준의 경고가 남아 보인다.
      queryClient.invalidateQueries({ queryKey: ['v7', 'alerts'] });
      queryClient.invalidateQueries({ queryKey: ['safetyLine'] });
    },
  });

  return (
    <PageShell
      title="자금 안전"
    >
      {saved && (
        <div className="v7-note v7-note--good" role="status">
          <Icon name="check" size={18} />
          <div>
            <strong>저장했습니다</strong>
            <span>{saved}</span>
          </div>
        </div>
      )}

      <div className="v7-cols">
        <Section title="지금 안전선">
          {safety.loading && forecast.phase === 'loading' ? (
            <p className="v7-note">불러오는 중…</p>
          ) : line === null ? (
            <>
              <p className="v7-amount v7-muted">아직 정하지 않았습니다</p>
              <p className="v7-note v7-note--small">
                안전선이 없으면 잔고가 <b>0원 아래로 내려갈 때만</b> 알려 드릴 수 있습니다.
                「주의」와 「흐림」은 이 값이 있어야 판정합니다.
              </p>
              {lowestValue !== undefined && (
                <dl className="v7-facts">
                  <div>
                    <dt>오늘 잔고</dt>
                    <dd>{formatMoney(balance)}</dd>
                  </div>
                  <div>
                    <dt>30일 최저</dt>
                    <dd>
                      {formatManwon(lowestValue)}
                      {lowestLabel ? ` · ${lowestLabel}` : ''}
                    </dd>
                  </div>
                </dl>
              )}
            </>
          ) : (
            <>
              <p className="v7-figure">{METHOD_LABEL[method]}</p>
              <p className="v7-amount">
                {money(line)}
                <small>원</small>
              </p>
              <dl className="v7-facts">
                <div>
                  <dt>오늘 잔고</dt>
                  <dd>{formatMoney(balance)}</dd>
                </div>
                <div>
                  <dt>안전선과 차이</dt>
                  <dd className={gapToLine !== null && gapToLine < 0 ? 'is-gap' : ''}>
                    {gapToLine === null
                      ? '—'
                      : `${gapToLine >= 0 ? '+' : ''}${formatMoney(gapToLine)} ${gapToLine >= 0 ? '여유' : '아래'}`}
                  </dd>
                </div>
                {lowestValue !== undefined && (
                  <div>
                    <dt>기간 중 최저</dt>
                    <dd className={lowestValue < line ? 'is-gap' : ''}>
                      {formatManwon(lowestValue)}
                      {lowestLabel ? ` · ${lowestLabel}` : ''}
                    </dd>
                  </div>
                )}
                <div>
                  <dt>마지막 저장</dt>
                  <dd>{safety.updatedAt ? formatDateTime(safety.updatedAt) : '—'}</dd>
                </div>
              </dl>
              {METHOD_HINT[method] && <p className="v7-note v7-note--small">{METHOD_HINT[method]}</p>}
            </>
          )}
        </Section>

        <Section title={line === null ? '안전선 정하기' : '안전선 바꾸기'}>
          <form onSubmit={handleSubmit((v) => save.mutateAsync(v))} noValidate>
            <Field label="금액" error={errors.amount?.message} hint="한 달 고정비만큼 잡는 가게가 많습니다">
              <input type="text" inputMode="numeric" placeholder="2300000" {...register('amount')} />
            </Field>
            <Field label="정한 기준" error={errors.method?.message}>
              <select {...register('method')}>
                {(Object.keys(METHOD_LABEL) as SafetyLineMethod[]).map((m) => (
                  <option key={m} value={m}>
                    {METHOD_LABEL[m]}
                  </option>
                ))}
              </select>
            </Field>
            <FormError message={errors.root?.message} />
            <button type="submit" className="auth-submit" disabled={isSubmitting || businessId === undefined}>
              {isSubmitting ? '저장하는 중…' : '저장하기'}
            </button>
          </form>

          <div className="v7-note v7-note--wait" style={{ marginTop: 20 }}>
            <Icon name="clock" size={18} />
            <div>
              <strong>추천값은 아직 없습니다</strong>
              <span>
                고정비를 자동으로 집계해 금액을 제안하는 기능은 준비 중입니다. 지금은 사장님이 직접
                정하고, 어떤 기준으로 정했는지만 함께 남깁니다.
              </span>
            </div>
          </div>
        </Section>
      </div>

      {line !== null && belowDate && (
        <Section title="안전선 아래로 내려가는 날">
          <div className="v7-note v7-note--bad" role="status">
            <Icon name="warning" size={18} />
            <div>
              <strong>{dayLabel(belowDate)}부터 안전선 아래</strong>
              <span>
                현재 데이터 기준으로 그렇습니다.
                {shortfall !== undefined && ` 가장 낮은 날은 안전선보다 약 ${formatManwon(shortfall)} 모자랄 가능성이 있습니다.`}
                {' '}확정된 금액이 아니며 정산 시점과 매출에 따라 달라집니다.
              </span>
            </div>
          </div>

          {causes.length > 0 && (
            <>
              <h3 className="v7-subhead">원인</h3>
              <ul className="v7-rows">
                {causes.map((c, i) => (
                  <li key={`${c.date}-${i}`}>
                    <span className="t">{c.date?.slice(5).replace('-', '.') ?? '—'}</span>
                    <span className="d">
                      {c.title ?? '—'}
                      {!c.plannedTransactionId && <small>예정 거래가 아니라 관측된 사실입니다</small>}
                    </span>
                  </li>
                ))}
              </ul>
            </>
          )}
        </Section>
      )}

      {line !== null && !belowDate && now && (
        <Section title="안전선 아래로 내려가는 날">
          <div className="v7-note v7-note--good" role="status">
            <Icon name="check" size={18} />
            <div>
              <strong>앞으로 30일 안에는 없습니다</strong>
              <span>
                현재 데이터 기준입니다. 새 지출이 잡히거나 정산이 밀리면 달라질 수 있습니다.
              </span>
            </div>
          </div>
        </Section>
      )}

      <Section title="안전선이 하는 일">
        <ul className="v7-rows">
          <li>
            <span className="d">
              <b>맑음</b> — 안전선 위
              <small>지금 흐름이면 여유가 있습니다.</small>
            </span>
          </li>
          <li>
            <span className="d">
              <b>흐림</b> — 안전선 아래, 0원 위
              <small>당장 비지는 않지만 여유가 줄어드는 구간입니다. 이때 알림을 보내 드립니다.</small>
            </span>
          </li>
          <li>
            <span className="d">
              <b>비</b> — 0원 아래
              <small>정산 공백입니다. 예보가 며칠 전부터 알려 드립니다.</small>
            </span>
          </li>
        </ul>
        <p className="v7-note v7-note--small">
          모두 예측 범위 기준입니다. 확정 금액이 아니며 정산 시점과 매출에 따라 달라집니다.
        </p>
      </Section>
    </PageShell>
  );
}
