import {
  finMessage,
  createAccount,
  disconnectAccount,
  listAccounts,
  listBankCodes,
  type Account,
  type BankCode,
} from '@jangoyebo/core';
import { useQuery, useQueryClient } from '@tanstack/react-query';
import { zodResolver } from '@hookform/resolvers/zod';
import { useState } from 'react';
import { useForm } from 'react-hook-form';
import { useParams } from 'react-router';
import { z } from 'zod';
import { AccountPurgeButton, AccountPurgePanel } from '@/business/AccountPurge';
import { AccountSync } from '@/business/AccountSync';
import { AccountVerify } from '@/routes/AccountVerify';
import { EmptyState } from '@/components/EmptyState';
import { PageShell, Section as V7Section } from '@/v7/PageShell';
import { applyFieldErrors } from '@/routes/fieldErrors';
import { Field, FormError } from '@/v7/AuthShell';
import { Icon } from '@/v7/icons';
import '@/styles/business.css';

const schema = z.object({
  bankCode: z.string().min(1, '은행을 골라 주세요.'),
  // 하이픈은 있어도 없어도 된다. 서버가 정규화한다(실측).
  // 입력하면서 숫자만 남긴다(아래 onChange). 은행마다 끊는 자리가 달라 서식을 강제하지 않는다.
  accountNo: z.string().regex(/^\d{9,20}$/, '계좌번호를 다시 확인해 주세요.'),
  // 서버에서 필수다(@NotBlank). 명세 7.2 에는 필수 표시가 없어 실측으로 확인했다.
  alias: z.string().min(1, '계좌를 알아볼 이름을 넣어 주세요.'),
});
type Form = z.infer<typeof schema>;

const STATUS_LABEL: Record<Account['status'], string> = {
  PENDING_VERIFICATION: '인증 대기',
  VERIFICATION_EXPIRED: '인증 만료',
  CONNECTED: '연결됨',
  DISCONNECTED: '연결 끊김',
};

/**
 * v7 아이콘 이름. 고리가 이어졌는가로 상태를 말한다 — 체크·X 는 끊김을 「오류」로 읽게 했다.
 * 인증 대기는 같은 고리를 점선으로 그린다(page.css). 색이 안 보여도 모양과 상태명으로 구분된다.
 */
const STATUS_ICON: Record<Account['status'], string> = {
  PENDING_VERIFICATION: 'link',
  VERIFICATION_EXPIRED: 'unlink',
  CONNECTED: 'link',
  DISCONNECTED: 'unlink',
};

/** 사업장 등록 직후 계좌부터 붙이는 화면. 평소 계좌 관리는 마이페이지가 같은 구획을 쓴다 */
export function AccountList() {
  const { businessId } = useParams();

  return (
    <PageShell title="계좌">
      <AccountManager businessId={Number(businessId)} />
    </PageShell>
  );
}

export function AccountManager({ businessId: id }: { businessId: number }) {
  const queryClient = useQueryClient();

  /** 해제를 되묻는 중인 계좌. 한 번에 하나만 묻는다 */
  const [confirming, setConfirming] = useState<number | null>(null);
  /** 삭제 패널이 열린 계좌. 해제와 따로 센다 — 둘은 같은 무게의 동작이 아니다 */
  const [purging, setPurging] = useState<number | null>(null);

  const { data: accounts = [], isPending } = useQuery({
    queryKey: ['accounts', id],
    queryFn: async (): Promise<Account[]> => {
      const res = await listAccounts(id);
      return res.ready && res.ok ? res.data.items : [];
    },
  });

  // 은행 목록은 금융망 실시간이라 실패할 수 있다. 실패해도 화면은 뜬다.
  // 서버가 캐시하고 자주 바뀌지 않으므로 클라이언트에서도 오래 들고 있는다.
  const { data: banks = [] } = useQuery({
    queryKey: ['bankCodes'],
    staleTime: 1000 * 60 * 60,
    queryFn: async (): Promise<BankCode[]> => {
      const res = await listBankCodes();
      return res.ready && res.ok ? res.data.items : [];
    },
  });

  const {
    register,
    handleSubmit,
    reset,
    setError,
    formState: { errors, isSubmitting },
  } = useForm<Form>({
    resolver: zodResolver(schema),
    // 첫 검사는 칸을 벗어날 때, 그 뒤로는 고치는 동안 실시간으로 갱신한다.
    // onBlur 로 두면 오류를 고쳐도 다시 벗어나야 사라져서 답답하다.
    mode: 'onTouched',
  });
  const accountNoField = register('accountNo');

  // 검증은 zodResolver 가 이미 끝냈다. 여기 오면 값이 유효하다.
  async function onSubmit(values: Form) {

    // 해제된 계좌는 세지 않는다. 그것 하나만 남은 상태에서 계좌를 붙이면 주계좌여야 하고,
    // 주계좌였던 계좌를 다시 연결할 때도 주계좌로 돌아와야 한다(서버가 새 값으로 덮어쓴다).
    const res = await createAccount(id, {
      ...values,
      isPrimary: accounts.every((a) => a.status === 'DISCONNECTED'),
    });

    if (!res.ready) {
      setError('root', { message: '계좌 연결 기능이 아직 준비되지 않았습니다.' });
      return;
    }
    if (!res.ok) {
      if (res.error.code === 'ACCOUNT_ALREADY_LINKED') {
        // 해제·만료된 내 계좌는 서버가 되살리므로 여기 오지 않는다. 남은 경우는 살아 있는
        // 연결이거나 내 다른 사업장에 붙어 있는 계좌다.
        setError('accountNo', { message: '이미 연결된 계좌입니다.' });
        return;
      }
      // 금융망이 인정하지 않는 계좌번호이거나, 남이 먼저 등록한 계좌다. 서버가 둘을 구분해
      // 알려주지 않는다 — 구분하면 계좌 존재 여부가 새어 나간다(명세 7.2).
      if (res.error.code === 'ACCOUNT_NO_INVALID') {
        setError('accountNo', { message: '계좌번호를 다시 확인해 주세요.' });
        return;
      }
      // 금융망 오류(502)는 detail.finCode 가 실제 이유다 — 계좌번호가 틀렸는지 설정 문제인지.
      const fin = finMessage(res.error);
      if (fin) {
        setError('accountNo', { message: fin });
        return;
      }
      if (!applyFieldErrors(res.error, setError, ['bankCode', 'accountNo', 'alias'])) {
        setError('root', { message: res.error.message });
      }
      return;
    }

    reset();
    await queryClient.invalidateQueries({ queryKey: ['accounts', id] });
  }

  async function onDisconnect(accountId: number) {
    setConfirming(null);
    const res = await disconnectAccount(accountId);
    if (!res.ready || !res.ok) return;
    await queryClient.invalidateQueries({ queryKey: ['accounts', id] });
  }

  const bankName = (code: string) => banks.find((b) => b.bankCode === code)?.bankName ?? code;

  // 인증을 기다리는 계좌가 있으면 그것부터 처리하게 한다. 등록만 하고 두면 쓸 수 없는 계좌다.
  //
  // 만료도 포함한다. AccountVerify 가 만료 안내와 재발송 버튼을 이미 갖고 있는데 여기서
  // PENDING 만 골라 그 화면에 닿을 길이 없었다 — 유효시간을 넘긴 사용자는 인증 화면을 잃고
  // 같은 계좌를 다시 등록하다 409 에 막혔다. 대기 중인 계좌를 먼저 보여준다.
  const awaiting =
    accounts.find((a) => a.status === 'PENDING_VERIFICATION') ??
    accounts.find((a) => a.status === 'VERIFICATION_EXPIRED');
  const expired = awaiting?.status === 'VERIFICATION_EXPIRED';

  return (
    <>
      <div className="v7-panel v7-rise">
        <h2 className="v7-panel__head">
          {!awaiting ? '계좌 연결하기' : expired ? '1원을 다시 받아 주세요' : '통장을 확인해 주세요'}
        </h2>
        {awaiting && (
          <p className="v7-note v7-note--small">
            {expired
              ? '인증 시간이 지났습니다. 1원을 다시 보내면 이어서 연결됩니다.'
              : '1원과 함께 보낸 코드를 넣으면 연결이 끝납니다.'}
          </p>
        )}
        {awaiting ? (
          <AccountVerify account={awaiting} businessId={id} />
        ) : (
        <form onSubmit={handleSubmit(onSubmit)} noValidate>
          <Field label="은행" error={errors.bankCode?.message}>
            <select {...register('bankCode')} disabled={banks.length === 0}>
              <option value="">
                {banks.length === 0 ? '은행 목록을 불러오지 못했습니다' : '은행을 고르세요'}
              </option>
              {banks.map((bank) => (
                <option key={bank.bankCode} value={bank.bankCode}>
                  {bank.bankName}
                </option>
              ))}
            </select>
          </Field>

          <Field label="계좌번호" error={errors.accountNo?.message}>
            <input
              type="text"
              inputMode="numeric"
              autoComplete="off"
              placeholder="ex) 110123456789"
              {...accountNoField}
              onChange={(e) => {
                // 하이픈·공백을 치거나 붙여 넣어도 숫자만 남긴다.
                e.target.value = e.target.value.replace(/\D/g, '');
                accountNoField.onChange(e);
              }}
            />
          </Field>

          <Field label="별칭" error={errors.alias?.message} hint="주거래 사업용 · 배달 정산용 등">
            <input type="text" {...register('alias')} />
          </Field>

          <FormError message={errors.root?.message} />

          <button type="submit" className="auth-submit" disabled={isSubmitting}>
            {isSubmitting ? '연결하는 중…' : '계좌 연결하기'}
          </button>
        </form>
        )}
      </div>

        <V7Section title={`연결된 계좌 ${accounts.length}개`}>
          {isPending ? null : accounts.length === 0 ? (
            <EmptyState
              title="아직 연결된 계좌가 없습니다"
              hint="위에서 계좌를 연결하면 여기에 표시됩니다."
            />
          ) : (
            <ul className="account-list">
              {accounts.map((account) => (
                <li key={account.accountId}>
                  <div className="account-id">
                    <strong>
                      {account.alias ?? bankName(account.bankCode)}
                      {account.isPrimary && <span className="account-primary">주계좌</span>}
                    </strong>
                    <small>
                      {bankName(account.bankCode)} · {account.accountNoMasked}
                    </small>
                  </div>
                  {/* 색만으로 상태를 말하지 않는다 — 상태명과 아이콘을 함께 둔다(화면구성도 6.1) */}
                  <span className="account-status" data-status={account.status}>
                    <Icon name={STATUS_ICON[account.status]} size={13} />
                    {STATUS_LABEL[account.status]}
                  </span>
                  <AccountSync account={account} businessId={id}>
                    {/* 동기화가 멈추는 동작이라 확인을 받는다(도메인 규칙 5).
                        모달을 띄우지 않고 그 자리에서 묻는다 — 화면에 카드·박스를 두지 않는다.
                        되돌릴 수 있게 됐다(S15P21C105-115): 같은 계좌를 다시 등록하면 서버가
                        그 행을 되살린다. 다만 1원 인증을 새로 받아야 하므로 그것을 적는다.
                        거래는 남는다 — "기록이 지워진다"로 읽히면 안 된다 */}
                    {account.status !== 'DISCONNECTED' &&
                      (confirming === account.accountId ? (
                        <span className="account-confirm" role="status">
                          다시 연결하려면 1원 인증을 새로 받습니다. 거래는 남습니다.
                          <button type="button" onClick={() => onDisconnect(account.accountId)}>
                            해제
                          </button>
                          <button type="button" onClick={() => setConfirming(null)}>
                            취소
                          </button>
                        </span>
                      ) : (
                        <button
                          type="button"
                          className="account-disconnect"
                          onClick={() => {
                            setConfirming(account.accountId);
                            // 한 줄에 되묻기가 둘 뜨면 어느 쪽을 누르는지 알 수 없다.
                            // 되돌릴 수 있는 것과 없는 것이 나란히 서면 더 나쁘다.
                            setPurging(null);
                          }}
                        >
                          연결 해제
                        </button>
                      ))}
                    {/* 삭제는 연결이 끊긴 계좌에도 둔다 — 해제해 둔 계좌를 목록에서
                        치우는 것이 이 기능이 생긴 이유다. 되묻기는 해제와 섞지 않는다:
                        한쪽은 되돌릴 수 있고 한쪽은 못 하므로 같은 무게로 보이면 안 된다.
                        패널이 열려 있는 동안에는 버튼을 숨긴다 — 패널 안의 확정 버튼도
                        「계좌 삭제」라 같은 글자가 한 줄에 둘 뜬다 */}
                    {confirming !== account.accountId && purging !== account.accountId && (
                      <AccountPurgeButton
                        account={account}
                        onOpen={() => {
                          setPurging(account.accountId);
                          setConfirming(null);
                        }}
                      />
                    )}
                  </AccountSync>
                  {/* 패널은 AccountSync 밖이어야 한다. 그 안은 버튼 칸(span)이라
                      flex-basis: 100% 가 li 에 닿지 않아 줄을 차지하지 못한다 */}
                  {purging === account.accountId && (
                    <AccountPurgePanel
                      account={account}
                      businessId={id}
                      onClose={() => setPurging(null)}
                    />
                  )}
                </li>
              ))}
            </ul>
          )}

        </V7Section>
    </>
  );
}
