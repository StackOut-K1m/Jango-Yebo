import { logout, updateMe } from '@jangoyebo/core';
import { zodResolver } from '@hookform/resolvers/zod';
import { useState } from 'react';
import { useForm } from 'react-hook-form';
import { Link, useNavigate } from 'react-router';
import { z } from 'zod';
import { useAuth } from '@/auth/useAuth';
import { useBusiness } from '@/business/useBusiness';
import { AccountManager } from '@/routes/AccountList';
import { applyFieldErrors } from '@/routes/fieldErrors';
import { Field, FormError } from '@/v7/AuthShell';
import { Icon } from '@/v7/icons';
import { PageShell, Section } from '@/v7/PageShell';
import { ThemeToggle } from '@/v7/ThemeToggle';
import '@/v7/mypage.css';

const schema = z.object({
  name: z.string().trim().min(1, '이름을 입력해 주세요.'),
  // 가입 화면(Signup)과 같은 규칙. 비워 두면 보내지 않는다
  phone: z
    .string()
    .trim()
    .refine((v) => !v || /^01[016789]-?\d{3,4}-?\d{4}$/.test(v), '휴대폰 번호 형식이 아닙니다.'),
});
type Form = z.infer<typeof schema>;

/** 마이페이지 — 내 정보(API 6.x users.me), 지금 사업장의 계좌, 화면 설정, 정책, 로그아웃 */
export function MyPage() {
  const { me, setMe } = useAuth();
  const { current } = useBusiness();
  const navigate = useNavigate();
  const [saved, setSaved] = useState(false);

  const {
    register,
    handleSubmit,
    setError,
    reset,
    formState: { errors, isSubmitting, isDirty },
  } = useForm<Form>({
    resolver: zodResolver(schema),
    mode: 'onTouched',
    values: { name: me?.name ?? '', phone: me?.phone ?? '' },
  });

  async function onSubmit(values: Form) {
    setSaved(false);
    const res = await updateMe({ name: values.name, ...(values.phone ? { phone: values.phone } : {}) });
    if (!res.ready) return setError('root', { message: '내 정보 수정 기능이 아직 준비되지 않았습니다.' });
    if (!res.ok) {
      if (!applyFieldErrors(res.error, setError, ['name', 'phone'])) setError('root', { message: res.error.message });
      return;
    }
    setMe(res.data);
    reset({ name: res.data.name, phone: res.data.phone ?? '' });
    setSaved(true);
  }

  async function onLogout() {
    await logout();
    setMe(null);
    navigate('/login', { replace: true });
  }

  return (
    <PageShell title="마이페이지">
      <Section title="내 정보">
        <form onSubmit={handleSubmit(onSubmit)} noValidate>
          <Field label="이메일" hint="로그인에 쓰는 주소는 바꿀 수 없습니다">
            <input type="email" value={me?.email ?? ''} readOnly />
          </Field>
          <Field label="이름" error={errors.name?.message}>
            <input type="text" autoComplete="name" {...register('name')} />
          </Field>
          <Field label="휴대폰 번호" error={errors.phone?.message} hint="선택">
            <input type="tel" autoComplete="tel" placeholder="010-1234-5678" {...register('phone')} />
          </Field>
          <FormError message={errors.root?.message} />
          {saved && !isDirty && (
            <p className="v7-note v7-note--small" role="status">
              <Icon name="check" size={15} /> 저장했습니다.
            </p>
          )}
          <button type="submit" className="auth-submit" disabled={isSubmitting || !isDirty}>
            {isSubmitting ? '저장하는 중…' : '저장하기'}
          </button>
        </form>
      </Section>

      {current && (
        <>
          <p className="mypage-caption">
            <Icon name="store" size={15} /> {current.name}의 계좌
          </p>
          <AccountManager key={current.businessId} businessId={current.businessId} />
        </>
      )}

      <Section title="설정">
        <div className="mypage-actions">
          <ThemeToggle />
          <Link to="/policy">
            <Icon name="info" size={17} /> 이용약관 · 개인정보처리방침
          </Link>
          <button type="button" onClick={onLogout}>
            <Icon name="close" size={17} /> 로그아웃
          </button>
        </div>
      </Section>
    </PageShell>
  );
}
