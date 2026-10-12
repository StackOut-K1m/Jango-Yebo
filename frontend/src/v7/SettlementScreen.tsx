import { useState } from 'react';
import { useForm } from 'react-hook-form';
import { zodResolver } from '@hookform/resolvers/zod';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { z } from 'zod';
import {
  ackAlert,
  createSettlement,
  daysAgo,
  formatDate,
  listAlerts,
  listSettlements,
} from '@jangoyebo/core';
import type { Alert, Settlement } from '@jangoyebo/core';
import { useBusiness } from '@/business/useBusiness';
import { EmptyState } from '@/components/EmptyState';
import { Field, FormError } from './AuthShell';
import { Icon } from './icons';
import { money } from './model';
import { PageShell, Section } from './PageShell';

const STATUS: Record<Settlement['status'], { label: string; icon: string }> = {
  PENDING: { label: '입금 대기', icon: 'clock' },
  MATCHED: { label: '맞음', icon: 'check' },
  MISMATCHED: { label: '차이 있음', icon: 'warning' },
};

const moneyText = z
  .string()
  .refine((value) => value === '' || /^\d[\d,]*$/.test(value), '0 이상의 숫자만 입력해 주세요.');

const schema = z
  .object({
    platformName: z.string().trim().min(1, '플랫폼 이름을 입력해 주세요.'),
    periodStart: z.string().min(1, '시작일을 골라 주세요.'),
    periodEnd: z.string().min(1, '종료일을 골라 주세요.'),
    grossSales: moneyText.refine((value) => value !== '', '총매출을 입력해 주세요.'),
    cancellations: moneyText,
    fees: moneyText,
  })
  .refine((values) => values.periodStart <= values.periodEnd, {
    path: ['periodEnd'],
    message: '종료일은 시작일보다 빠를 수 없습니다.',
  });

type Form = z.infer<typeof schema>;

const parseWon = (value: string) => Number(value.replace(/,/g, '') || '0');

export function SettlementScreen() {
  const { current } = useBusiness();
  const businessId = current?.businessId;
  const queryClient = useQueryClient();
  const [notice, setNotice] = useState('');

  const settlements = useQuery({
    queryKey: ['v7', 'settlements', businessId],
    enabled: businessId !== undefined,
    queryFn: () => listSettlements(businessId as number),
  });

  const alerts = useQuery({
    queryKey: ['v7', 'alerts', businessId],
    enabled: businessId !== undefined,
    queryFn: () => listAlerts(businessId as number, { status: 'ACTIVE' }),
  });

  const {
    register,
    handleSubmit,
    reset,
    setError,
    formState: { errors, isSubmitting },
  } = useForm<Form>({
    resolver: zodResolver(schema),
    mode: 'onTouched',
    defaultValues: {
      platformName: '',
      periodStart: daysAgo(7),
      periodEnd: daysAgo(0),
      grossSales: '',
      cancellations: '',
      fees: '',
    },
  });

  const create = useMutation({
    mutationFn: (values: Form) =>
      createSettlement(businessId as number, {
        platformName: values.platformName.trim(),
        periodStart: values.periodStart,
        periodEnd: values.periodEnd,
        grossSales: parseWon(values.grossSales),
        cancellations: parseWon(values.cancellations),
        fees: parseWon(values.fees),
      }),
    onSuccess: (result) => {
      if (!result.ready) {
        setError('root', { message: '정산 대사 기능이 아직 준비되지 않았습니다.' });
        return;
      }
      if (!result.ok) {
        setError('root', { message: `정산을 등록하지 못했습니다 — ${result.error.message}` });
        return;
      }
      setNotice(`${result.data.platformName} 정산을 등록했습니다.`);
      reset();
      queryClient.invalidateQueries({ queryKey: ['v7', 'settlements', businessId] });
      queryClient.invalidateQueries({ queryKey: ['v7', 'alerts', businessId] });
    },
  });

  const acknowledge = useMutation({
    mutationFn: (alertId: number) => ackAlert(alertId),
    onSuccess: (result) => {
      if (!result.ready || !result.ok) return;
      queryClient.invalidateQueries({ queryKey: ['v7', 'alerts', businessId] });
    },
  });

  const settlementResult = settlements.data;
  const rows = settlementResult?.ready && settlementResult.ok ? settlementResult.data.items : [];
  const settlementError =
    settlementResult?.ready && !settlementResult.ok ? settlementResult.error.message : null;
  const alertResult = alerts.data;
  const activeAlerts: Alert[] = alertResult?.ready && alertResult.ok ? alertResult.data.items : [];
  const alertError = alertResult?.ready && !alertResult.ok ? alertResult.error.message : null;
  const acknowledgeResult = acknowledge.data;
  const acknowledgeError = !acknowledgeResult
    ? null
    : !acknowledgeResult.ready
      ? '알림 확인 기능이 아직 준비되지 않았습니다.'
      : !acknowledgeResult.ok
        ? acknowledgeResult.error.message
        : null;

  return (
    <PageShell
      title="정산 대사"
    >
      {notice && (
        <div className="v7-note v7-note--good" role="status">
          <Icon name="check" size={18} />
          <div>
            <strong>등록했습니다</strong>
            <span>{notice}</span>
          </div>
        </div>
      )}

      <div className="v7-cols">
        <Section title={`확인할 알림 ${activeAlerts.length}건`}>
          {alerts.isPending ? (
            <p className="v7-note">불러오는 중…</p>
          ) : alertError ? (
            <div className="v7-note v7-note--bad" role="alert">
              <Icon name="warning" size={18} />
              <div>
                <strong>알림을 불러오지 못했습니다</strong>
                <span>{alertError}</span>
              </div>
            </div>
          ) : activeAlerts.length === 0 ? (
            <div className="v7-note v7-note--good">
              <Icon name="check" size={18} />
              <div>
                <strong>지금 확인할 정산 알림이 없습니다</strong>
                <span>입금액에 차이가 생기면 원인 옆에서 알려 드립니다.</span>
              </div>
            </div>
          ) : (
            <ul className="v7-alerts">
              {activeAlerts.map((alert) => (
                <li key={alert.alertId}>
                  <Icon name="warning" size={18} />
                  <span>
                    <strong>{alert.title}</strong>
                    <small>{alert.message}</small>
                  </span>
                  <button
                    type="button"
                    className="v7-secondary-button"
                    disabled={acknowledge.isPending}
                    onClick={() => acknowledge.mutate(alert.alertId)}
                  >
                    확인했어요
                  </button>
                </li>
              ))}
            </ul>
          )}
          {acknowledgeError && (
            <p className="v7-inline-error" role="alert">
              알림을 확인 처리하지 못했습니다 — {acknowledgeError}
            </p>
          )}
        </Section>

        <Section title="정산 등록">
          <form
            className="v7-settlement-form"
            onSubmit={handleSubmit((values) => create.mutateAsync(values))}
            noValidate
          >
            <Field
              label="플랫폼"
              error={errors.platformName?.message}
              hint="배달의민족 · 쿠팡이츠 등"
            >
              <input type="text" autoComplete="organization" {...register('platformName')} />
            </Field>
            <div className="v7-form-grid v7-form-grid--two">
              <Field label="시작일" error={errors.periodStart?.message}>
                <input type="date" {...register('periodStart')} />
              </Field>
              <Field label="종료일" error={errors.periodEnd?.message}>
                <input type="date" {...register('periodEnd')} />
              </Field>
            </div>
            <div className="v7-form-grid">
              <Field label="총매출" error={errors.grossSales?.message}>
                <input
                  type="text"
                  inputMode="numeric"
                  placeholder="1200000"
                  {...register('grossSales')}
                />
              </Field>
              <Field label="취소" error={errors.cancellations?.message} hint="없으면 비워 두세요">
                <input
                  type="text"
                  inputMode="numeric"
                  placeholder="0"
                  {...register('cancellations')}
                />
              </Field>
              <Field label="수수료" error={errors.fees?.message} hint="없으면 비워 두세요">
                <input type="text" inputMode="numeric" placeholder="0" {...register('fees')} />
              </Field>
            </div>
            <FormError message={errors.root?.message} />
            <button
              type="submit"
              className="auth-submit"
              disabled={isSubmitting || businessId === undefined}
            >
              {isSubmitting ? '등록하는 중…' : '등록하기'}
            </button>
          </form>
        </Section>
      </div>

      <Section title={`정산 ${rows.length}건`}>
        {settlements.isPending ? (
          <p className="v7-note">불러오는 중…</p>
        ) : settlementError ? (
          <div className="v7-note v7-note--bad" role="alert">
            <Icon name="warning" size={18} />
            <div>
              <strong>정산을 불러오지 못했습니다</strong>
              <span>{settlementError}</span>
            </div>
          </div>
        ) : rows.length === 0 ? (
          <EmptyState
            title="등록한 정산이 없습니다"
            hint="플랫폼 정산 내역을 등록하면 들어올 돈과 실제 입금을 맞춰 봅니다."
          />
        ) : (
          <div className="tx-table-wrap">
            <table className="tx-table">
              <thead>
                <tr>
                  <th scope="col">플랫폼</th>
                  <th scope="col">정산 기간</th>
                  <th scope="col" className="tx-num">
                    들어올 돈
                  </th>
                  <th scope="col" className="tx-num">
                    실제 입금
                  </th>
                  <th scope="col" className="tx-num">
                    차이
                  </th>
                  <th scope="col">상태</th>
                </tr>
              </thead>
              <tbody>
                {rows.map((settlement) => {
                  const status = STATUS[settlement.status];
                  return (
                    <tr key={settlement.settlementId}>
                      <td data-label="플랫폼">
                        <strong>{settlement.platformName}</strong>
                      </td>
                      <td data-label="정산 기간" className="v7-period">
                        {formatDate(settlement.periodStart)}
                        <small>~ {formatDate(settlement.periodEnd)}</small>
                      </td>
                      <td data-label="들어올 돈" className="tx-num">
                        {money(settlement.expectedPayout)}원<small>매출 − 취소 − 수수료</small>
                      </td>
                      <td data-label="실제 입금" className="tx-num">{money(settlement.actualDeposit)}원</td>
                      <td data-label="차이"
                        className="tx-num"
                        data-direction={
                          settlement.status === 'MISMATCHED' && settlement.difference < 0
                            ? 'WITHDRAWAL'
                            : undefined
                        }
                      >
                        {settlement.difference > 0 ? '+' : ''}
                        {money(settlement.difference)}원
                      </td>
                      <td data-label="상태">
                        <span className="v7-settlement-status" data-status={settlement.status}>
                          <Icon name={status.icon} size={14} />
                          {status.label}
                        </span>
                      </td>
                    </tr>
                  );
                })}
              </tbody>
            </table>
          </div>
        )}
      </Section>
    </PageShell>
  );
}
