import { finMessage, resendVerification, verifyAccount, type Account } from '@jangoyebo/core';
import { zodResolver } from '@hookform/resolvers/zod';
import { useQueryClient } from '@tanstack/react-query';
import { useState } from 'react';
import { useForm } from 'react-hook-form';
import { z } from 'zod';
import { Field, FormError } from '@/v7/AuthShell';

// 인증코드는 항상 숫자 4자리다. 형식이 틀리면 서버가 금융망을 부르지 않고 즉시 400 을 준다.
const schema = z.object({
  authCode: z.string().regex(/^\d{4}$/, '통장에 찍힌 숫자 4자리를 넣어 주세요.'),
});
type Form = z.infer<typeof schema>;

/**
 * 1원 인증 (SC-01-4).
 *
 * 등록 시점에 이미 1원이 나가 있다. 사용자는 통장 적요에서 "잔고예보 5020" 을 읽어 온다.
 * 은행 앱으로 넘어갔다 오는 흐름이라 입력창을 위쪽에 두고 안내를 짧게 쓴다.
 */
export function AccountVerify({ account, businessId }: { account: Account; businessId: number }) {
  const queryClient = useQueryClient();
  const [resent, setResent] = useState<string | null>(null);

  const {
    register,
    handleSubmit,
    setError,
    reset,
    formState: { errors, isSubmitting },
  } = useForm<Form>({ resolver: zodResolver(schema), mode: 'onTouched' });

  async function onSubmit(values: Form) {
    const res = await verifyAccount(account.accountId, values);

    if (!res.ready) {
      setError('root', { message: '인증 기능이 아직 준비되지 않았습니다.' });
      return;
    }
    if (!res.ok) {
      // 서버 문구가 개발자용이거나(정규식 그대로) 뭉뚱그려져 있어 사용자 문구로 바꾼다.
      // 금융망 오류는 detail.finCode 가 실제 이유를 담고 있다.
      const message =
        res.error.code === 'ACCOUNT_AUTH_CODE_MISMATCH'
          ? '코드가 맞지 않습니다. 통장을 다시 확인해 주세요.'
          : res.error.code === 'ACCOUNT_AUTH_EXPIRED'
            ? '인증 시간이 지났습니다. 1원을 다시 받아 주세요.'
            : res.error.code === 'VALIDATION_FAILED'
              ? '통장에 찍힌 숫자 4자리를 넣어 주세요.'
              : (finMessage(res.error) ?? res.error.message);
      setError('authCode', { message });
      return;
    }

    await queryClient.invalidateQueries({ queryKey: ['accounts', businessId] });
  }

  async function onResend() {
    setResent(null);
    const res = await resendVerification(account.accountId);
    if (res.ready && res.ok) {
      reset();
      setResent('1원을 다시 보냈습니다. 통장을 확인해 주세요.');
      await queryClient.invalidateQueries({ queryKey: ['accounts', businessId] });
      return;
    }
    setError('root', {
      message: res.ready
        ? (finMessage(res.error) ?? res.error.message)
        : '재발송 기능이 아직 준비되지 않았습니다.',
    });
  }

  const expired = account.status === 'VERIFICATION_EXPIRED';

  return (
    <div className="verify">
      {/* 만료된 계좌에도 이 화면을 띄운다(AccountList). 그때 "코드를 읽어 넣어 주세요" 로
          시작하면 바로 아래 만료 안내와 어긋나므로 첫 문장을 갈라 둔다. 설명과 지시는
          아래 verify-expired 가 맡으므로 여기서는 어느 계좌인지만 밝힌다. */}
      <p className="verify-lede">
        {expired ? (
          <>{account.alias ?? account.accountNoMasked} 계좌는 연결을 마무리하지 못했습니다.</>
        ) : (
          <>
            {account.alias ?? account.accountNoMasked} 계좌로 <strong>1원</strong>을 보냈습니다.
            <br />
            통장 적요에서 <strong>잔고예보 뒤 숫자 4자리</strong>를 읽어 넣어 주세요.
          </>
        )}
      </p>

      {expired ? (
        <p className="verify-expired">
          <span aria-hidden="true">⚠</span> 인증 시간이 지났습니다. 1원을 다시 받아 주세요.
        </p>
      ) : (
        <form onSubmit={handleSubmit(onSubmit)} noValidate>
          <Field label="인증코드" error={errors.authCode?.message} hint="숫자 4자리">
            <input
              type="text"
              inputMode="numeric"
              autoComplete="one-time-code"
              maxLength={4}
              placeholder="0000"
              autoFocus
              {...register('authCode')}
            />
          </Field>

          <FormError message={errors.root?.message} />
          {resent && <p className="verify-resent">{resent}</p>}

          <button type="submit" className="auth-submit" disabled={isSubmitting}>
            {isSubmitting ? '확인하는 중…' : '연결 완료하기'}
          </button>
        </form>
      )}

      <button type="button" className="verify-resend" onClick={onResend}>
        1원을 다시 받기
      </button>
      <p className="verify-hint">은행 앱에서 확인하고 돌아오세요. 코드를 틀려도 다시 넣으면 됩니다.</p>
    </div>
  );
}
